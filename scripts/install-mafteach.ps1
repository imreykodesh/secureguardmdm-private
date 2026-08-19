# ============================================================================
#  Mafteach installer: state-aware ADB install and Device Owner provisioning
# ============================================================================
# Inspects the connected device first, then chooses the only safe path for the
# state it is actually in. Nothing destructive runs unless explicitly allowed.
#
# Paths handled:
#   1. Not installed, no owner, no accounts        -> install + set-device-owner
#   2. Installed, same signer, higher versionCode  -> in-place update (owner kept)
#   3. Installed, different signer, IS owner       -> stop with instructions
#   4. Installed, different signer, not owner      -> uninstall + install + owner
#   5. Owner belongs to another package           -> stop
#
# A Device Owner app cannot be removed with ADB. Release it from inside the
# installed app (Settings -> remove app), which calls clearDeviceOwnerApp.

[CmdletBinding()]
param(
    [string]$ApkPath = "",
    [string]$Serial = "",
    [switch]$AllowUninstall,
    [switch]$Force,
    [switch]$CheckOnly,
    [string]$PlanOutputPath = ""
)

Set-StrictMode -Version Latest
# Native tools such as adb and apksigner write notices to stderr. With "Stop"
# those notices would abort the script, so exit codes are checked explicitly
# instead, and every real failure below raises with throw.
$ErrorActionPreference = "Continue"

$PackageName = "com.secureguard.mdm"
$AdminComponent = "com.secureguard.mdm/.SecureGuardDeviceAdminReceiver"
$ProjectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$WorkDirectory = Join-Path $ProjectRoot "app\build\tmp\agent"

function Write-Section {
    param([string]$Title)
    Write-Host ""
    Write-Host "=== $Title ===" -ForegroundColor Cyan
}

function Resolve-Adb {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
        (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"),
        (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe")
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) { return $candidate }
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $portable = Get-PortableAndroidTool -ToolName "adb.exe"
    if ($portable) { return $portable }
    throw "לא נמצא adb.exe ולא ניתן היה להוריד אותו אוטומטית. יש לוודא חיבור לאינטרנט או להתקין Android platform-tools."
}

function Resolve-BuildTool {
    param([string]$ToolName)
    $roots = @(
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools"),
        (Join-Path $env:ANDROID_HOME "build-tools"),
        (Join-Path $env:ANDROID_SDK_ROOT "build-tools")
    )
    foreach ($root in $roots) {
        if (-not $root -or -not (Test-Path -LiteralPath $root)) { continue }
        $tool = Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName $ToolName } |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
            Select-Object -First 1
        if ($tool) { return $tool }
    }
    return $null
}

# Most end users do not have the Android SDK installed. Rather than failing with
# "adb.exe was not found", the missing command line tools are fetched once from
# Google's official repository into a per-user cache and reused from there. The
# archives are never redistributed inside this project or inside the bundle.
$script:PortableToolsRoot = if ($env:LOCALAPPDATA) {
    Join-Path $env:LOCALAPPDATA "Mafteach\android-tools"
}
else {
    Join-Path $env:TEMP "Mafteach\android-tools"
}

$script:PortableToolSources = @{
    "adb.exe"   = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
    # build-tools carries aapt2.exe, which reads APK metadata without needing Java.
    "aapt2.exe" = "https://dl.google.com/android/repository/build-tools_r34-windows.zip"
}

function Get-PortableAndroidTool {
    param([Parameter(Mandatory = $true)][string]$ToolName)

    if (-not $script:PortableToolSources.ContainsKey($ToolName)) { return $null }

    $cacheRoot = Join-Path $script:PortableToolsRoot ([System.IO.Path]::GetFileNameWithoutExtension($ToolName))
    if (Test-Path -LiteralPath $cacheRoot) {
        $cached = Get-ChildItem -LiteralPath $cacheRoot -Recurse -Filter $ToolName -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($cached) { return $cached.FullName }
    }

    $url = $script:PortableToolSources[$ToolName]
    Write-Host ""
    Write-Host "$ToolName אינו מותקן במחשב. מוריד אותו פעם אחת מהמאגר הרשמי של Google..." -ForegroundColor Yellow

    $stagingRoot = Join-Path $env:TEMP ("mafteach-tools-" + [Guid]::NewGuid().ToString("N"))
    $archivePath = Join-Path $stagingRoot "download.zip"
    $extractRoot = Join-Path $stagingRoot "extracted"
    try {
        New-Item -ItemType Directory -Force -Path $stagingRoot | Out-Null

        try {
            [System.Net.ServicePointManager]::SecurityProtocol =
                [System.Net.ServicePointManager]::SecurityProtocol -bor [System.Net.SecurityProtocolType]::Tls12
        }
        catch {
            Write-Verbose "Could not raise the TLS protocol level: $($_.Exception.Message)"
        }

        $previousProgress = $ProgressPreference
        $ProgressPreference = "SilentlyContinue"
        try {
            Invoke-WebRequest -Uri $url -OutFile $archivePath -UseBasicParsing -TimeoutSec 600
        }
        finally {
            $ProgressPreference = $previousProgress
        }

        if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf)) { return $null }

        Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
        [System.IO.Compression.ZipFile]::ExtractToDirectory($archivePath, $extractRoot)

        $extracted = Get-ChildItem -LiteralPath $extractRoot -Recurse -Filter $ToolName -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if (-not $extracted) { return $null }

        # The whole extracted tree is kept, because adb needs its sibling DLLs.
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $cacheRoot) | Out-Null
        if (Test-Path -LiteralPath $cacheRoot) {
            Remove-Item -LiteralPath $cacheRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
        Move-Item -LiteralPath $extractRoot -Destination $cacheRoot

        $tool = Get-ChildItem -LiteralPath $cacheRoot -Recurse -Filter $ToolName -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if (-not $tool) { return $null }

        Write-Host "  הכלי הורד בהצלחה: $($tool.FullName)" -ForegroundColor Green
        return $tool.FullName
    }
    catch {
        Write-Host "  ההורדה האוטומטית נכשלה: $($_.Exception.Message)" -ForegroundColor Yellow
        return $null
    }
    finally {
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Resolve-Device {
    param([string]$AdbPath, [string]$RequestedSerial)
    $lines = & $AdbPath devices | Select-Object -Skip 1
    $devices = @()
    foreach ($line in $lines) {
        if ($line -match '^(\S+)\s+device$') { $devices += $Matches[1] }
    }
    if ($devices.Count -eq 0) {
        throw "No authorized device is connected. Enable USB debugging and accept the RSA prompt."
    }
    if ($RequestedSerial) {
        if ($devices -notcontains $RequestedSerial) {
            throw "Requested serial '$RequestedSerial' is not connected. Connected: $($devices -join ', ')"
        }
        return $RequestedSerial
    }
    if ($devices.Count -gt 1) {
        throw "Multiple devices are connected ($($devices -join ', ')). Re-run with -Serial <serial>."
    }
    return $devices[0]
}

function Resolve-Apk {
    param([string]$RequestedPath)
    if ($RequestedPath) {
        if (-not (Test-Path -LiteralPath $RequestedPath -PathType Leaf)) {
            throw "APK was not found: $RequestedPath"
        }
        return (Get-Item -LiteralPath $RequestedPath).FullName
    }
    # The newest artifact across all output locations wins, so a stale release
    # APK is never picked over a freshly built one. Names are matched by
    # pattern, so a renamed or versioned artifact is still found.
    $searchRoots = @(
        (Join-Path $ProjectRoot "app\build\outputs\apk\release"),
        (Join-Path $ProjectRoot "app\release"),
        (Join-Path $ProjectRoot "app\build\outputs\apk\debug")
    )
    $candidate = $searchRoots |
        Where-Object { Test-Path -LiteralPath $_ } |
        ForEach-Object { Get-ChildItem -LiteralPath $_ -Filter "*.apk" -File -ErrorAction SilentlyContinue } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($candidate) { return $candidate.FullName }
    throw "No APK was found. Build one or pass -ApkPath."
}

function Get-ApkIdentity {
    param([string]$Aapt2Path, [string]$Path)
    if (-not $Aapt2Path) { throw "aapt2.exe was not found; cannot read APK metadata." }
    $badging = & $Aapt2Path dump badging $Path 2>$null
    $packageLine = $badging | Select-String -Pattern "^package:" | Select-Object -First 1
    if (-not $packageLine) { throw "Could not read APK metadata: $Path" }
    $text = $packageLine.Line
    $package = if ($text -match "name='([^']+)'") { $Matches[1] } else { "" }
    $versionCode = if ($text -match "versionCode='(\d+)'") { [long]$Matches[1] } else { 0L }
    $versionName = if ($text -match "versionName='([^']*)'") { $Matches[1] } else { "" }
    return [pscustomobject]@{
        Package = $package
        VersionCode = $versionCode
        VersionName = $versionName
    }
}

# apksigner.bat requires a Java runtime, which most end users do not have. This
# reads the v1 (JAR) signature block directly with .NET instead and produces the
# same value apksigner prints: SHA-256 over the signer certificate DER bytes.
# APKs signed only with v2/v3 have no such block and still return $null, which
# the caller treats as "signer unknown" rather than as a match.
function Get-ApkSignerDigestFromArchive {
    param([string]$Path)

    $archive = $null
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
        Add-Type -AssemblyName System.Security -ErrorAction SilentlyContinue

        $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
        $entry = $archive.Entries |
            Where-Object { $_.FullName -match '^META-INF/[^/]+\.(RSA|DSA|EC)$' } |
            Sort-Object FullName |
            Select-Object -First 1
        if (-not $entry) { return $null }

        $stream = $entry.Open()
        try {
            $buffer = New-Object System.IO.MemoryStream
            $stream.CopyTo($buffer)
            $signatureBytes = $buffer.ToArray()
        }
        finally {
            $stream.Dispose()
        }

        $signedCms = New-Object System.Security.Cryptography.Pkcs.SignedCms
        $signedCms.Decode($signatureBytes)

        $certificate = $null
        if ($signedCms.SignerInfos.Count -gt 0) { $certificate = $signedCms.SignerInfos[0].Certificate }
        if (-not $certificate -and $signedCms.Certificates.Count -gt 0) { $certificate = $signedCms.Certificates[0] }
        if (-not $certificate) { return $null }

        $sha256 = [System.Security.Cryptography.SHA256]::Create()
        try {
            $digestBytes = $sha256.ComputeHash($certificate.RawData)
        }
        finally {
            $sha256.Dispose()
        }
        return (($digestBytes | ForEach-Object { $_.ToString("x2") }) -join "")
    }
    catch {
        Write-Verbose "Reading the embedded signature block failed: $($_.Exception.Message)"
        return $null
    }
    finally {
        if ($archive) { $archive.Dispose() }
    }
}

function Get-ApkSignerDigest {
    param([string]$ApkSignerPath, [string]$Path)
    if ($ApkSignerPath) {
        $output = & $ApkSignerPath verify --print-certs $Path 2>$null
        if ($LASTEXITCODE -eq 0) {
            $line = $output | Select-String -Pattern "certificate SHA-256 digest:" | Select-Object -First 1
            if ($line) {
                return ($line.Line -replace '.*digest:\s*', '').Trim().ToLowerInvariant()
            }
        }
    }
    return Get-ApkSignerDigestFromArchive -Path $Path
}

function Get-InstalledVersionCode {
    param([string]$AdbPath, [string]$DeviceSerial)
    $dump = & $AdbPath -s $DeviceSerial shell dumpsys package $PackageName 2>$null
    $line = $dump | Select-String -Pattern "versionCode=" | Select-Object -First 1
    if (-not $line) { return $null }
    if ($line.Line -match "versionCode=(\d+)") { return [long]$Matches[1] }
    return $null
}

function Test-PackageInstalled {
    param([string]$AdbPath, [string]$DeviceSerial)
    $paths = & $AdbPath -s $DeviceSerial shell pm path $PackageName 2>$null
    return [bool]($paths | Select-String -Pattern "^package:")
}

function Get-InstalledSignerDigest {
    param([string]$AdbPath, [string]$DeviceSerial, [string]$ApkSignerPath)
    $pathLine = (& $AdbPath -s $DeviceSerial shell pm path $PackageName 2>$null |
        Select-String -Pattern "^package:.*base\.apk" | Select-Object -First 1)
    if (-not $pathLine) {
        $pathLine = (& $AdbPath -s $DeviceSerial shell pm path $PackageName 2>$null |
            Select-String -Pattern "^package:" | Select-Object -First 1)
    }
    if (-not $pathLine) { return $null }
    $remotePath = ($pathLine.Line -replace '^package:', '').Trim()

    New-Item -ItemType Directory -Force -Path $WorkDirectory | Out-Null
    $localCopy = Join-Path $WorkDirectory "installed-base.apk"
    Remove-Item -LiteralPath $localCopy -Force -ErrorAction SilentlyContinue
    & $AdbPath -s $DeviceSerial pull $remotePath $localCopy *> $null
    if (-not (Test-Path -LiteralPath $localCopy -PathType Leaf)) { return $null }
    try {
        return Get-ApkSignerDigest -ApkSignerPath $ApkSignerPath -Path $localCopy
    }
    finally {
        Remove-Item -LiteralPath $localCopy -Force -ErrorAction SilentlyContinue
    }
}

function ConvertFrom-DpmOwnersOutput {
    [CmdletBinding()]
    param(
        [AllowEmptyCollection()][string[]]$Lines,
        [int]$ExitCode = 0
    )

    $textLines = @($Lines | ForEach-Object { [string]$_ })
    $rawOutput = ($textLines -join "`n").Trim()
    if ($ExitCode -ne 0) {
        return [pscustomobject]@{
            State = "Unknown"
            Package = $null
            Reason = "dpm list-owners exited with code $ExitCode"
            RawOutput = $rawOutput
        }
    }
    if (-not $rawOutput) {
        return [pscustomobject]@{
            State = "Unknown"
            Package = $null
            Reason = "dpm list-owners returned no output"
            RawOutput = $rawOutput
        }
    }
    if ($rawOutput -match '(?im)^\s*0\s+owners?\s*:?\s*$' -or
        $rawOutput -match '(?im)^\s*no\s+owners?\s*[.:]?\s*$') {
        return [pscustomobject]@{
            State = "None"
            Package = $null
            Reason = "dpm explicitly reported no owners"
            RawOutput = $rawOutput
        }
    }

    $insideDeviceOwnerBlock = $false
    foreach ($line in $textLines) {
        $isDeviceOwnerHeading = $line -match '(?i)^\s*Device\s+Owner\b.*:?\s*$'
        if ($isDeviceOwnerHeading) {
            $insideDeviceOwnerBlock = $true
        }
        elseif ($line -match '(?i)^\s*(?:Profile\s+Owner|Managed\s+Profile)\b.*:?\s*$') {
            $insideDeviceOwnerBlock = $false
        }

        $hasCompactDeviceOwnerMarker = $line -match '(?i)\bDeviceOwner\b'
        if (-not $insideDeviceOwnerBlock -and -not $hasCompactDeviceOwnerMarker) { continue }

        $ownerPackage = $null
        if ($line -match '(?i)admin\s*=\s*(?:ComponentInfo\{)?([A-Za-z0-9_.]+)\/') {
            $ownerPackage = $Matches[1]
        }
        elseif ($line -match '(?i)ComponentInfo\{([A-Za-z0-9_.]+)\/') {
            $ownerPackage = $Matches[1]
        }
        elseif ($line -match '(?i)package\s*[:=]\s*([A-Za-z0-9_.]+)') {
            $ownerPackage = $Matches[1]
        }

        if ($ownerPackage) {
            return [pscustomobject]@{
                State = "Found"
                Package = $ownerPackage
                Reason = "explicit Device Owner entry"
                RawOutput = $rawOutput
            }
        }
    }

    return [pscustomobject]@{
        State = "Unknown"
        Package = $null
        Reason = "dpm output did not explicitly identify a Device Owner or zero owners"
        RawOutput = $rawOutput
    }
}

function Get-DeviceOwnerStatus {
    param([string]$AdbPath, [string]$DeviceSerial)
    $lines = @(& $AdbPath -s $DeviceSerial shell dpm list-owners 2>&1)
    $exitCode = $LASTEXITCODE
    return ConvertFrom-DpmOwnersOutput -Lines $lines -ExitCode $exitCode
}

function Get-AccountCount {
    param([string]$AdbPath, [string]$DeviceSerial)
    $dump = & $AdbPath -s $DeviceSerial shell dumpsys account 2>$null | Out-String
    return ([regex]::Matches($dump, 'Account\s*\{name=')).Count
}

function Write-PlanSnapshot {
    param(
        [string]$OutputPath,
        [object]$Snapshot
    )
    if ([string]::IsNullOrWhiteSpace($OutputPath)) { return }

    $fullPath = [System.IO.Path]::GetFullPath($OutputPath)
    $parent = Split-Path -Parent $fullPath
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $temporaryPath = "$fullPath.tmp-$PID"
    try {
        $Snapshot | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $temporaryPath -Encoding UTF8
        Move-Item -LiteralPath $temporaryPath -Destination $fullPath -Force
    }
    finally {
        Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
    }
}

function Confirm-Destructive {
    param([string]$Message)
    if ($Force) { return $true }
    Write-Host ""
    Write-Warning $Message
    $answer = Read-Host "Type YES to continue"
    return ($answer -ceq "YES")
}

# --- Environment -----------------------------------------------------------
$adb = Resolve-Adb
$aapt2 = Resolve-BuildTool -ToolName "aapt2.exe"
if (-not $aapt2) { $aapt2 = Get-PortableAndroidTool -ToolName "aapt2.exe" }
# Named distinctly from the digest variables below: PowerShell variable names
# are case-insensitive, so $apkSigner would collide with this tool path.
$apkSignerTool = Resolve-BuildTool -ToolName "apksigner.bat"
$device = Resolve-Device -AdbPath $adb -RequestedSerial $Serial
$apk = Resolve-Apk -RequestedPath $ApkPath

Write-Section "קובץ ההתקנה"
$apkIdentity = Get-ApkIdentity -Aapt2Path $aapt2 -Path $apk
$localSignerDigest = Get-ApkSignerDigest -ApkSignerPath $apkSignerTool -Path $apk
Write-Host "קובץ:        $apk"
Write-Host "חבילה:       $($apkIdentity.Package)"
Write-Host "גרסה:        $($apkIdentity.VersionName) (versionCode $($apkIdentity.VersionCode))"
Write-Host "חתימה:       $(if ($localSignerDigest) { $localSignerDigest } else { 'לא ידועה (apksigner אינו זמין)' })"

if ($apkIdentity.Package -ne $PackageName) {
    throw "שם החבילה ב-APK ('$($apkIdentity.Package)') אינו תואם לצפוי ('$PackageName')."
}

Write-Section "מצב המכשיר"
$isInstalled = Test-PackageInstalled -AdbPath $adb -DeviceSerial $device
$installedVersion = if ($isInstalled) { Get-InstalledVersionCode -AdbPath $adb -DeviceSerial $device } else { $null }
$ownerStatus = Get-DeviceOwnerStatus -AdbPath $adb -DeviceSerial $device
$ownerPackage = if ($ownerStatus.State -eq "Found") { $ownerStatus.Package } else { $null }
$accountCount = Get-AccountCount -AdbPath $adb -DeviceSerial $device
$installedSignerDigest = if ($isInstalled) {
    Get-InstalledSignerDigest -AdbPath $adb -DeviceSerial $device -ApkSignerPath $apkSignerTool
} else { $null }
$ownerDisplay = if ($ownerStatus.State -eq "Found") {
    $ownerPackage
} elseif ($ownerStatus.State -eq "None") {
    "לא מוגדר"
} else {
    "לא ניתן לקבוע"
}

Write-Host "מזהה מכשיר:  $device"
Write-Host "מותקן:       $(if ($isInstalled) { "כן (versionCode $installedVersion)" } else { 'לא' })"
Write-Host "חתימה:       $(if ($installedSignerDigest) { $installedSignerDigest } else { 'לא זמינה' })"
Write-Host "מנהל מכשיר:  $ownerDisplay"
Write-Host "חשבונות:     $accountCount"

if ($ownerStatus.State -eq "Unknown") {
    throw "לא ניתן לקבוע בבטחה את מצב מנהל המכשיר: $($ownerStatus.Reason). הסקריפט נעצר ללא שינוי."
}

# --- Decide the path -------------------------------------------------------
$isOwnPackageOwner = ($ownerStatus.State -eq "Found" -and $ownerPackage -eq $PackageName)
$signersKnown = ($localSignerDigest -and $installedSignerDigest)
$signersMatch = ($signersKnown -and ($localSignerDigest -eq $installedSignerDigest))

if ($ownerPackage -and -not $isOwnPackageOwner) {
    throw "אפליקציה אחרת מוגדרת כמנהל המכשיר ($ownerPackage). יש לטפל בכך קודם; הסקריפט לא ייגע בה."
}

$plan = $null
if (-not $isInstalled) {
    $plan = "FRESH_INSTALL"
}
elseif ($signersMatch) {
    $plan = "UPDATE_IN_PLACE"
}
elseif (-not $signersKnown) {
    $plan = "UNKNOWN_SIGNER"
}
elseif ($isOwnPackageOwner) {
    $plan = "BLOCKED_OWNER_SIGNER_MISMATCH"
}
else {
    $plan = "REPLACE_UNINSTALL_FIRST"
}

$planTitles = @{
    "UPDATE_IN_PLACE" = "עדכון מעל ההתקנה הקיימת"
    "FRESH_INSTALL" = "התקנה חדשה והגדרת מנהל מכשיר"
    "REPLACE_UNINSTALL_FIRST" = "הסרה ואז התקנה מחדש"
    "BLOCKED_OWNER_SIGNER_MISMATCH" = "חסום: חתימה שונה והאפליקציה היא מנהל המכשיר"
    "UNKNOWN_SIGNER" = "לא ניתן להשוות חתימות"
}

$blockingReasons = [System.Collections.Generic.List[string]]::new()
switch ($plan) {
    "UPDATE_IN_PLACE" {
        if ($null -eq $installedVersion) {
            [void]$blockingReasons.Add("לא ניתן לקרוא את versionCode המותקן.")
        }
        elseif ($apkIdentity.VersionCode -le $installedVersion) {
            [void]$blockingReasons.Add("ה-versionCode של ה-APK אינו גבוה מהגרסה המותקנת.")
        }
        if (-not $isOwnPackageOwner -and $accountCount -gt 0) {
            [void]$blockingReasons.Add("העדכון אפשרי, אך לא ניתן להשלים הגדרת Device Owner כל עוד קיימים חשבונות במכשיר.")
        }
    }
    "FRESH_INSTALL" {
        if ($accountCount -gt 0) {
            [void]$blockingReasons.Add("יש להסיר את כל חשבונות Google לפני התקנה חדשה והגדרת Device Owner.")
        }
    }
    "REPLACE_UNINSTALL_FIRST" {
        [void]$blockingReasons.Add("נדרשת הסרה שמוחקת נתונים. אשף ההתקנה החזותי אינו מבצע פעולה זו.")
    }
    "BLOCKED_OWNER_SIGNER_MISMATCH" {
        [void]$blockingReasons.Add("החתימה שונה והאפליקציה המותקנת היא Device Owner. יש לשחרר ולהסיר רק מתוך האפליקציה.")
    }
    "UNKNOWN_SIGNER" {
        [void]$blockingReasons.Add("לא ניתן להשוות את חתימות ה-APK והאפליקציה המותקנת.")
    }
}

Write-PlanSnapshot -OutputPath $PlanOutputPath -Snapshot ([pscustomobject]@{
    SchemaVersion = 1
    Plan = $plan
    PlanTitle = $planTitles[$plan]
    CanExecuteSafely = ($blockingReasons.Count -eq 0 -and $plan -in @("UPDATE_IN_PLACE", "FRESH_INSTALL"))
    BlockingReasons = @($blockingReasons)
    Device = [pscustomobject]@{
        Serial = $device
        PackageInstalled = $isInstalled
        InstalledVersionCode = $installedVersion
        InstalledSignerDigest = $installedSignerDigest
        OwnerState = $ownerStatus.State
        OwnerPackage = $ownerPackage
        OwnerReason = $ownerStatus.Reason
        AccountCount = $accountCount
    }
    Apk = [pscustomobject]@{
        Path = $apk
        Package = $apkIdentity.Package
        VersionCode = $apkIdentity.VersionCode
        VersionName = $apkIdentity.VersionName
        SignerDigest = $localSignerDigest
    }
})

Write-Section "מסלול: $($planTitles[$plan])"

switch ($plan) {
    "UPDATE_IN_PLACE" {
        Write-Host "זוהתה אותה חתימה. זהו עדכון רגיל, והגדרת מנהל המכשיר תישמר."
        if ($installedVersion -ne $null -and $apkIdentity.VersionCode -le $installedVersion) {
            throw "ה-versionCode של ה-APK ($($apkIdentity.VersionCode)) אינו גבוה מהמותקן ($installedVersion). יש להעלות את versionCode."
        }
    }
    "FRESH_INSTALL" {
        Write-Host "האפליקציה אינה מותקנת. היא תותקן ותוגדר כמנהל המכשיר."
        if ($accountCount -gt 0) {
            throw "במכשיר קיימים $accountCount חשבונות. יש להסיר את כל חשבונות Google ולהריץ שוב, אחרת הגדרת מנהל המכשיר תיכשל."
        }
    }
    "REPLACE_UNINSTALL_FIRST" {
        Write-Host "מותקנת גרסה בחתימה שונה, והיא אינה מנהל המכשיר, ולכן ניתן להסיר אותה."
        if (-not $AllowUninstall) {
            throw "נדרשת הסרה אך היא אינה מאושרת. הרץ שוב עם -AllowUninstall אם אתה מסכים שנתוני האפליקציה יימחקו."
        }
        if ($accountCount -gt 0) {
            throw "יש להסיר את כל חשבונות Google לפני ההגדרה; במכשיר קיימים $accountCount."
        }
    }
    "BLOCKED_OWNER_SIGNER_MISMATCH" {
        Write-Host "האפליקציה המותקנת היא מנהל המכשיר ונחתמה במפתח אחר." -ForegroundColor Yellow
        Write-Host ""
        Write-Host "לא ניתן להסיר מנהל מכשיר באמצעות ADB. בצע כך:"
        Write-Host "  1. פתח את האפליקציה המותקנת, עבור להגדרות ובחר הסרת האפליקציה (נדרשת סיסמה)."
        Write-Host "     היא משחררת את עצמה ממנהל המכשיר ומסירה את עצמה. אין צורך באיפוס יצרן."
        Write-Host "  2. הסר את כל חשבונות Google מהמכשיר."
        Write-Host "  3. הרץ את הסקריפט מחדש."
        exit 2
    }
    "UNKNOWN_SIGNER" {
        Write-Host "לא ניתן היה להשוות חתימות (apksigner נכשל או שההעברה מהמכשיר נכשלה)." -ForegroundColor Yellow
        Write-Host "הרץ שוב כאשר Android build-tools זמינים, או אמת את החתימות ידנית."
        exit 3
    }
}

if ($CheckOnly) {
    Write-Host ""
    Write-Host "הופעל במצב בדיקה בלבד (-CheckOnly). לא בוצע שום שינוי." -ForegroundColor Green
    exit 0
}

# --- Execute ---------------------------------------------------------------
if ($plan -eq "REPLACE_UNINSTALL_FIRST") {
    $preUninstallOwnerStatus = Get-DeviceOwnerStatus -AdbPath $adb -DeviceSerial $device
    if ($preUninstallOwnerStatus.State -eq "Unknown") {
        throw "מצב מנהל המכשיר השתנה או אינו ודאי לפני ההסרה. הסקריפט נעצר ללא הסרה."
    }
    if ($preUninstallOwnerStatus.State -eq "Found") {
        throw "זוהה מנהל מכשיר לפני ההסרה ($($preUninstallOwnerStatus.Package)). אין לבצע הסרה באמצעות ADB."
    }
    if (-not (Confirm-Destructive "פעולה זו מסירה את $PackageName ומוחקת את נתוניה במכשיר $device.")) {
        throw "בוטל לפני ההסרה."
    }
    Write-Section "מסיר את הגרסה המותקנת"
    & $adb -s $device uninstall $PackageName
    if ($LASTEXITCODE -ne 0) { throw "ההסרה נכשלה. אם האפליקציה היא מנהל המכשיר, יש להסיר אותה מתוך האפליקציה עצמה." }
}

Write-Section "מתקין"
$installArguments = @("-s", $device, "install", "-r", "-t", $apk)
& $adb @installArguments
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "ההתקנה נכשלה. סיבות נפוצות:" -ForegroundColor Yellow
    Write-Host "  INSTALL_FAILED_UPDATE_INCOMPATIBLE   - חתימה שונה; יש להסיר את האפליקציה מתוכה."
    Write-Host "  INSTALL_FAILED_VERSION_DOWNGRADE     - יש להעלות את versionCode."
    Write-Host "  User restriction prevents installing - יש לכבות חסימת התקנת אפליקציות באפליקציה."
    throw "פקודת adb install נכשלה."
}

# Re-read ownership after installation and immediately before any provisioning.
# Unknown output is never treated as "no owner", even when -Force was supplied.
$postInstallOwnerStatus = Get-DeviceOwnerStatus -AdbPath $adb -DeviceSerial $device
if ($postInstallOwnerStatus.State -eq "Unknown") {
    throw "האפליקציה הותקנה, אך לא ניתן לקבוע בבטחה את מצב מנהל המכשיר לפני ההגדרה. לא בוצעה פקודת set-device-owner."
}
if ($postInstallOwnerStatus.State -eq "Found" -and $postInstallOwnerStatus.Package -ne $PackageName) {
    throw "האפליקציה הותקנה, אך אפליקציה אחרת היא מנהל המכשיר ($($postInstallOwnerStatus.Package))."
}

$ownerExpected = ($postInstallOwnerStatus.State -eq "Found" -and $postInstallOwnerStatus.Package -eq $PackageName)
if ($postInstallOwnerStatus.State -eq "None") {
    if ($accountCount -gt 0) {
        Write-Host ""
        Write-Warning "האפליקציה הותקנה אך לא הוגדרה כמנהל המכשיר, מפני שקיימים $accountCount חשבונות במכשיר."
        Write-Host "הסר את כל חשבונות Google והרץ את הפקודה הבאה:"
        Write-Host "  adb -s $device shell dpm set-device-owner $AdminComponent"
    }
    else {
        Write-Section "מגדיר כמנהל המכשיר"
        & $adb -s $device shell dpm set-device-owner $AdminComponent
        if ($LASTEXITCODE -ne 0) {
            throw "הגדרת מנהל המכשיר נכשלה. ודא שאין חשבונות במכשיר ושאין מנהל מכשיר אחר."
        }
        $ownerExpected = $true
    }
}

Write-Section "אימות"
$finalVersion = Get-InstalledVersionCode -AdbPath $adb -DeviceSerial $device
$finalOwnerStatus = Get-DeviceOwnerStatus -AdbPath $adb -DeviceSerial $device
if ($finalOwnerStatus.State -eq "Unknown") {
    throw "האימות נכשל: לא ניתן לקבוע בבטחה את מצב מנהל המכשיר."
}
$finalOwner = if ($finalOwnerStatus.State -eq "Found") { $finalOwnerStatus.Package } else { $null }
Write-Host "versionCode מותקן:  $finalVersion"
Write-Host "מנהל מכשיר:         $(if ($finalOwner) { $finalOwner } else { 'לא מוגדר' })"

if ($finalVersion -ne $apkIdentity.VersionCode) {
    throw "האימות נכשל: צפוי versionCode $($apkIdentity.VersionCode) אך נמצא $finalVersion."
}
if ($ownerExpected -and $finalOwner -ne $PackageName) {
    throw "האימות נכשל: $PackageName אינה מנהל המכשיר."
}

Write-Host ""
if ($ownerExpected) {
    Write-Host "הסתיים. האפליקציה הותקנה ומנהל המכשיר אומת." -ForegroundColor Green
}
else {
    Write-Host "ההתקנה הסתיימה. נותר להגדיר מנהל מכשיר לאחר הסרת החשבונות." -ForegroundColor Yellow
}
