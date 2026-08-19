# ============================================================================
#  Smart versioned backup for A Bloq / SecureGuardMDM
# ============================================================================
# Creates a verified working-tree snapshot outside the project directory.
# Generated Android/Gradle/IDE files are excluded, while authored and untracked
# project files are retained. Signing secrets are intentionally never copied.

[CmdletBinding()]
param(
    [string]$Description = "",
    [string]$BackupRoot = "",
    [switch]$IncludeGitHistory,
    [switch]$SkipReleaseArtifacts,
    [switch]$DryRun,
    [switch]$NonInteractive
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptVersion = "1.0.0"
$ProjectRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$ProjectName = (Get-Item -LiteralPath $ProjectRoot).Name

if ([string]::IsNullOrWhiteSpace($BackupRoot)) {
    $BackupRoot = Join-Path -Path $ProjectRoot -ChildPath "..\backups"
}
$BackupRoot = [System.IO.Path]::GetFullPath($BackupRoot)
$ProjectBackupRoot = Join-Path -Path $BackupRoot -ChildPath "${ProjectName}_backup"

$projectPrefix = $ProjectRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
if ($BackupRoot.Equals($ProjectRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
    $BackupRoot.StartsWith($projectPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "BackupRoot must be outside the project directory to prevent recursive backups: $BackupRoot"
}

if ([string]::IsNullOrWhiteSpace($Description) -and -not $NonInteractive -and -not $DryRun) {
    $Description = Read-Host "`nEnter a short backup description (or press Enter to skip)"
}

$SanitizedDescription = ($Description -replace '[\\/*?:"<>|]', '' -replace '\s+', '_').Trim('_', '.')
if ($SanitizedDescription.Length -gt 64) {
    $SanitizedDescription = $SanitizedDescription.Substring(0, 64).TrimEnd('_', '.')
}

function Format-ByteSize {
    param([long]$Bytes)

    if ($Bytes -ge 1GB) { return "{0:N2} GiB" -f ($Bytes / 1GB) }
    if ($Bytes -ge 1MB) { return "{0:N2} MiB" -f ($Bytes / 1MB) }
    if ($Bytes -ge 1KB) { return "{0:N2} KiB" -f ($Bytes / 1KB) }
    return "$Bytes bytes"
}

function Get-DirectoryExclusionReason {
    param([string]$RelativePath, [System.IO.FileSystemInfo]$Item)

    $path = $RelativePath.Replace('\', '/').Trim('/')
    $lower = $path.ToLowerInvariant()

    if (($Item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        return "Reparse point/symlink (external or recursive content is not followed)"
    }
    if (-not $IncludeGitHistory -and ($lower -eq ".git" -or $lower.StartsWith(".git/"))) {
        return "Git object database/history (use -IncludeGitHistory to include)"
    }
    if ($lower -eq ".idea" -or $lower.StartsWith(".idea/")) {
        return "IDE-local state"
    }
    if ($lower -match '(^|/)(build|\.gradle|\.kotlin|\.cxx|\.externalnativebuild|captures|node_modules|out)(/|$)') {
        return "Generated build/cache directory"
    }
    if ($lower -eq "third_party/firestack/bin" -or $lower.StartsWith("third_party/firestack/bin/")) {
        return "Generated vendored binary output"
    }
    if ($lower -eq "sec" -or $lower.StartsWith("sec/")) {
        return "Local operator secrets; requires separate encrypted backup"
    }

    return $null
}

function Get-FileExclusionReason {
    param([string]$RelativePath, [System.IO.FileInfo]$Item)

    $path = $RelativePath.Replace('\', '/').Trim('/')
    $lower = $path.ToLowerInvariant()
    $name = $Item.Name.ToLowerInvariant()
    $extension = $Item.Extension.ToLowerInvariant()

    if ($name -eq "local.properties") {
        return "Machine-specific Android SDK configuration"
    }
    if ($name -in @(".ds_store", "thumbs.db", "desktop.ini")) {
        return "Operating-system metadata"
    }
    if ($extension -in @(".iml", ".log", ".tmp", ".temp", ".swp")) {
        return "Generated editor/log/temporary file"
    }
    if ($name.EndsWith("~")) {
        return "Editor temporary file"
    }
    if ($SkipReleaseArtifacts -and (
            $lower -eq "app/release/abloq-release.apk" -or
            $lower -match '^app/release/מפתח-[^/]*-release\.apk$' -or
            $lower -eq "app/release/output-metadata.json")) {
        return "Release artifact omitted by -SkipReleaseArtifacts"
    }

    # Sensitive signing and environment files are never copied into a normal
    # project backup. Keep them in a separate encrypted secrets backup.
    if ($extension -in @(".jks", ".keystore", ".p12", ".pfx", ".key")) {
        return "Sensitive signing/private-key file; requires separate encrypted backup"
    }
    if ($name -in @("signing.properties", "keystore.properties", "secrets.properties")) {
        return "Sensitive signing/credential configuration; requires separate encrypted backup"
    }
    if ($name -eq ".env" -or
        (($name.StartsWith(".env.")) -and ($name -notmatch '\.(example|sample|template)$'))) {
        return "Environment secrets; requires separate encrypted backup"
    }

    return $null
}

$SourceFiles = [System.Collections.Generic.List[object]]::new()
$ExcludedItems = [System.Collections.Generic.List[object]]::new()

function Add-BackupInventory {
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [string]$RelativePrefix = ""
    )

    foreach ($item in @(Get-ChildItem -LiteralPath $Directory -Force)) {
        $relativePath = if ([string]::IsNullOrEmpty($RelativePrefix)) {
            $item.Name
        }
        else {
            Join-Path -Path $RelativePrefix -ChildPath $item.Name
        }

        if ($item.PSIsContainer) {
            $reason = Get-DirectoryExclusionReason -RelativePath $relativePath -Item $item
            if ($null -ne $reason) {
                [void]$ExcludedItems.Add([pscustomobject]@{
                    RelativePath = $relativePath.Replace('\', '/')
                    Kind = "Directory"
                    Reason = $reason
                })
                continue
            }
            Add-BackupInventory -Directory $item.FullName -RelativePrefix $relativePath
            continue
        }

        $reason = Get-FileExclusionReason -RelativePath $relativePath -Item $item
        if ($null -ne $reason) {
            [void]$ExcludedItems.Add([pscustomobject]@{
                RelativePath = $relativePath.Replace('\', '/')
                Kind = "File"
                Reason = $reason
            })
            continue
        }

        [void]$SourceFiles.Add([pscustomobject]@{
            SourcePath = $item.FullName
            RelativePath = $relativePath
            Length = [long]$item.Length
            LastWriteTimeUtc = $item.LastWriteTimeUtc
        })
    }
}

Write-Host "============================================================"
Write-Host "  SecureGuardMDM smart backup v$ScriptVersion"
Write-Host "============================================================"
Write-Host "Project:      $ProjectRoot"
Write-Host "Backup root:  $ProjectBackupRoot"
Write-Host "Git history:  $(if ($IncludeGitHistory) { 'included' } else { 'excluded (default)' })"
Write-Host "Release APK:  $(if ($SkipReleaseArtifacts) { 'excluded by request' } else { 'included when present' })"
Write-Host ""
Write-Host "Scanning project files..."

Add-BackupInventory -Directory $ProjectRoot
$SortedSourceFiles = @($SourceFiles | Sort-Object RelativePath)
$SourceBytes = [long](($SortedSourceFiles | Measure-Object -Property Length -Sum).Sum)
if ($null -eq $SourceBytes) { $SourceBytes = 0 }

Write-Host "Included files: $($SortedSourceFiles.Count)"
Write-Host "Included size:  $(Format-ByteSize $SourceBytes)"
Write-Host "Excluded items: $($ExcludedItems.Count)"

$SensitiveExclusions = @($ExcludedItems | Where-Object { $_.Reason -like "Sensitive*" -or $_.Reason -like "Environment secrets*" })
if ($SensitiveExclusions.Count -gt 0) {
    Write-Warning "$($SensitiveExclusions.Count) sensitive file(s) were intentionally excluded. Store signing keys and credentials in a separate encrypted backup."
}
else {
    Write-Warning "No release signing keystore was found in the project. Ensure the real release keystore and passwords have a separate encrypted, tested backup."
}

if ($SortedSourceFiles.Count -eq 0) {
    throw "No source files remained after applying backup exclusions."
}

if ($DryRun) {
    Write-Host ""
    Write-Host "Dry run complete. No files were copied."
    [pscustomobject]@{
        ProjectRoot = $ProjectRoot
        BackupRoot = $ProjectBackupRoot
        IncludedFiles = $SortedSourceFiles.Count
        IncludedBytes = $SourceBytes
        ExcludedItems = $ExcludedItems.Count
        DryRun = $true
    }
    return
}

New-Item -Path $ProjectBackupRoot -ItemType Directory -Force | Out-Null

$escapedProjectName = [regex]::Escape($ProjectName)
$highestNumber = 0
$existingNumbers = @(Get-ChildItem -LiteralPath $ProjectBackupRoot -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.Name -match "^${escapedProjectName}_(\d+)(?:_|$)") {
        [int]$Matches[1]
    }
})
if ($existingNumbers.Count -gt 0) {
    $highestNumber = [int](($existingNumbers | Measure-Object -Maximum).Maximum)
}

$BackupNumber = $highestNumber + 1
$TargetFolderName = if ([string]::IsNullOrWhiteSpace($SanitizedDescription)) {
    "${ProjectName}_${BackupNumber}"
}
else {
    "${ProjectName}_${BackupNumber}_${SanitizedDescription}"
}

$TargetDir = Join-Path -Path $ProjectBackupRoot -ChildPath $TargetFolderName
$StagingDir = "${TargetDir}.incomplete-$PID"
if ((Test-Path -LiteralPath $TargetDir) -or (Test-Path -LiteralPath $StagingDir)) {
    throw "Backup target already exists: $TargetDir"
}

Write-Host ""
Write-Host "Creating staged backup: $StagingDir"
New-Item -Path $StagingDir -ItemType Directory | Out-Null

$ManifestRows = [System.Collections.Generic.List[object]]::new()
$copiedCount = 0

try {
    foreach ($file in $SortedSourceFiles) {
        $destinationPath = Join-Path -Path $StagingDir -ChildPath $file.RelativePath
        $destinationParent = Split-Path -Path $destinationPath -Parent
        if (-not (Test-Path -LiteralPath $destinationParent)) {
            New-Item -Path $destinationParent -ItemType Directory -Force | Out-Null
        }

        Copy-Item -LiteralPath $file.SourcePath -Destination $destinationPath -Force
        $sourceHash = (Get-FileHash -LiteralPath $file.SourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
        [void]$ManifestRows.Add([pscustomobject]@{
            RelativePath = $file.RelativePath.Replace('\', '/')
            Length = $file.Length
            LastWriteTimeUtc = $file.LastWriteTimeUtc.ToString("o")
            SHA256 = $sourceHash
        })

        $copiedCount++
        if (($copiedCount % 250) -eq 0 -or $copiedCount -eq $SortedSourceFiles.Count) {
            Write-Progress -Activity "Backing up SecureGuardMDM" -Status "$copiedCount / $($SortedSourceFiles.Count) files" -PercentComplete (($copiedCount / $SortedSourceFiles.Count) * 100)
        }
    }
    Write-Progress -Activity "Backing up SecureGuardMDM" -Completed

    $MetadataDir = Join-Path -Path $StagingDir -ChildPath "_backup_metadata"
    New-Item -Path $MetadataDir -ItemType Directory | Out-Null

    if (-not [string]::IsNullOrWhiteSpace($Description)) {
        Set-Content -LiteralPath (Join-Path $MetadataDir "description.txt") -Value $Description -Encoding UTF8
    }

    $ManifestPath = Join-Path -Path $MetadataDir -ChildPath "manifest-sha256.csv"
    $ManifestRows | Export-Csv -LiteralPath $ManifestPath -NoTypeInformation -Encoding UTF8
    $ExcludedItems | Sort-Object RelativePath | Export-Csv -LiteralPath (Join-Path $MetadataDir "excluded-items.csv") -NoTypeInformation -Encoding UTF8

    $criticalRows = @($ManifestRows | Where-Object {
        $_.RelativePath -match '(^|/)(Abloq-release\.apk|מפתח-[^/]*-release\.apk|nophone\.apk|gradle-wrapper\.jar|secureguard_mini_store_public_key\.json)$'
    })
    $criticalRows | Export-Csv -LiteralPath (Join-Path $MetadataDir "critical-file-hashes.csv") -NoTypeInformation -Encoding UTF8

    $gitExecutable = Get-Command git -ErrorAction SilentlyContinue
    if ($null -ne $gitExecutable -and (Test-Path -LiteralPath (Join-Path $ProjectRoot ".git"))) {
        $gitHead = (& git -C $ProjectRoot rev-parse HEAD 2>$null | Out-String).Trim()
        $gitBranch = (& git -C $ProjectRoot branch --show-current 2>$null | Out-String).Trim()
        $gitStatus = (& git -C $ProjectRoot status --short --branch 2>$null | Out-String).TrimEnd()
        Set-Content -LiteralPath (Join-Path $MetadataDir "git-head.txt") -Value @(
            "Branch: $gitBranch"
            "HEAD: $gitHead"
        ) -Encoding UTF8
        Set-Content -LiteralPath (Join-Path $MetadataDir "git-status.txt") -Value $gitStatus -Encoding UTF8
    }
    else {
        Set-Content -LiteralPath (Join-Path $MetadataDir "git-status.txt") -Value "Git metadata unavailable." -Encoding UTF8
    }

    $restoreNotes = @'
# SecureGuardMDM backup restore notes

- Restore this snapshot into a new empty directory.
- Generated `.gradle`, `.kotlin`, `.idea`, module `build`, `.cxx`, and `.externalNativeBuild` content is intentionally absent.
- Recreate `local.properties` for the destination machine's Android SDK.
- Git history is included only when `-IncludeGitHistory` was used.
- Release signing keystores and passwords are never included. Restore them separately from an encrypted secrets backup.
- `app/src/main/assets/nophone.apk` is a required runtime input and is included.
- A stored release APK under `app/release` (`מפתח-<versionName>-release.apk`, or the legacy `Abloq-release.apk`) is included unless `-SkipReleaseArtifacts` was used.
- Run Gradle dependency resolution and a clean build after restoration.
'@
    Set-Content -LiteralPath (Join-Path $MetadataDir "RESTORE.md") -Value $restoreNotes -Encoding UTF8

    Write-Host "Verifying copied files with SHA-256..."
    $verifiedCount = 0
    foreach ($manifestRow in $ManifestRows) {
        $copiedPath = Join-Path -Path $StagingDir -ChildPath $manifestRow.RelativePath
        if (-not (Test-Path -LiteralPath $copiedPath -PathType Leaf)) {
            throw "Verification failed; copied file is missing: $($manifestRow.RelativePath)"
        }

        $copiedItem = Get-Item -LiteralPath $copiedPath
        if ([long]$copiedItem.Length -ne [long]$manifestRow.Length) {
            throw "Verification failed; size mismatch: $($manifestRow.RelativePath)"
        }

        $copiedHash = (Get-FileHash -LiteralPath $copiedPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($copiedHash -ne $manifestRow.SHA256) {
            throw "Verification failed; SHA-256 mismatch: $($manifestRow.RelativePath)"
        }
        $verifiedCount++
    }

    $backupInfo = [ordered]@{
        ScriptVersion = $ScriptVersion
        CreatedUtc = [DateTime]::UtcNow.ToString("o")
        ProjectName = $ProjectName
        SourceRoot = $ProjectRoot
        Description = $Description
        BackupNumber = $BackupNumber
        FileCount = $ManifestRows.Count
        SourceBytes = $SourceBytes
        IncludedGitHistory = [bool]$IncludeGitHistory
        IncludedReleaseArtifacts = -not [bool]$SkipReleaseArtifacts
        ExcludedItemCount = $ExcludedItems.Count
        VerifiedFileCount = $verifiedCount
        Verification = "SHA-256 verified"
    }
    $backupInfo | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $MetadataDir "backup-info.json") -Encoding UTF8

    Move-Item -LiteralPath $StagingDir -Destination $TargetDir
}
catch {
    Write-Error "Backup failed. The incomplete staging directory was kept for inspection: $StagingDir`n$($_.Exception.Message)"
    throw
}

Write-Host ""
Write-Host "============================================================"
Write-Host "  Backup completed and verified successfully"
Write-Host "============================================================"
Write-Host "Location:     $TargetDir"
Write-Host "Files:        $($ManifestRows.Count)"
Write-Host "Source size:  $(Format-ByteSize $SourceBytes)"
Write-Host "Manifest:     _backup_metadata\manifest-sha256.csv"
Write-Host ""
Write-Warning "The release signing keystore is not part of this backup. Keep a separate encrypted and tested recovery copy."

[pscustomobject]@{
    BackupPath = $TargetDir
    BackupNumber = $BackupNumber
    FileCount = $ManifestRows.Count
    SourceBytes = $SourceBytes
    Verified = $true
}
