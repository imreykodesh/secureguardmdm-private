<#
.SYNOPSIS
    Builds the single-file Mafteach installer launcher.
.DESCRIPTION
    Bundles the wizard HTML, the localhost host script and the existing
    install-mafteach.ps1 safety engine into one self-extracting .cmd file.
    The payloads are stored as base64, so the bundle stays pure ASCII and is
    unaffected by Windows PowerShell 5.1 ANSI/UTF-8 parsing differences.

    There is no second installation engine: the bundle carries the same
    install-mafteach.ps1 that the repository uses, so signer, versionCode,
    Device Owner and account checks remain the single source of truth.

.PARAMETER OutputPath
    Destination of the generated launcher.

.EXAMPLE
    powershell -File ".\scripts\build-mafteach-installer-bundle.ps1"
#>

[CmdletBinding()]
param(
    [string]$OutputPath = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$installerScript = Join-Path $projectRoot 'scripts\install-mafteach.ps1'
$hostScript = Join-Path $projectRoot 'scripts\mafteach-installer\Start-MafteachInstaller.ps1'
$htmlFile = Join-Path $projectRoot 'scripts\mafteach-installer\index.html'

if (-not $OutputPath) {
    $OutputPath = Join-Path $projectRoot 'dist\Mafteach-Installer.cmd'
}
$OutputPath = [System.IO.Path]::GetFullPath($OutputPath)

foreach ($required in @($installerScript, $hostScript, $htmlFile)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required source file was not found: $required"
    }
}

function Get-Base64Block {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $base64 = [Convert]::ToBase64String($bytes)
    $lines = [System.Collections.Generic.List[string]]::new()
    [void]$lines.Add("REM ---BEGIN $Name---")
    for ($offset = 0; $offset -lt $base64.Length; $offset += 200) {
        $length = [Math]::Min(200, $base64.Length - $offset)
        [void]$lines.Add('REM ' + $base64.Substring($offset, $length))
    }
    [void]$lines.Add("REM ---END $Name---")
    return $lines
}

# The batch stage never reads past "exit /b", so the payload lines below are
# only ever parsed by PowerShell. The marker is assembled at runtime so the
# literal marker text appears exactly once in the generated file.
$bootstrap = @'
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$selfPath = $env:MAFTEACH_BUNDLE_SELF
if (-not $selfPath -or -not (Test-Path -LiteralPath $selfPath -PathType Leaf)) {
    throw 'The bundle could not locate itself. Run the .cmd file directly.'
}

$allLines = Get-Content -LiteralPath $selfPath -Encoding ASCII

function Expand-Payload {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    $startMarker = "REM ---BEGIN $Name---"
    $endMarker = "REM ---END $Name---"
    $startIndex = -1
    $endIndex = -1
    for ($index = 0; $index -lt $allLines.Count; $index++) {
        if ($allLines[$index] -eq $startMarker) { $startIndex = $index }
        elseif ($allLines[$index] -eq $endMarker) { $endIndex = $index; break }
    }
    if ($startIndex -lt 0 -or $endIndex -le $startIndex) {
        throw "The embedded payload '$Name' is missing or damaged. Download the installer again."
    }

    $builder = New-Object System.Text.StringBuilder
    for ($index = $startIndex + 1; $index -lt $endIndex; $index++) {
        [void]$builder.Append($allLines[$index].Substring(4))
    }
    [System.IO.File]::WriteAllBytes($Destination, [Convert]::FromBase64String($builder.ToString()))
}

$sessionRoot = Join-Path $env:TEMP ('mafteach-installer-' + [Guid]::NewGuid().ToString('N'))
$scriptsRoot = Join-Path $sessionRoot 'scripts'
$wizardRoot = Join-Path $scriptsRoot 'mafteach-installer'
New-Item -ItemType Directory -Force -Path $wizardRoot | Out-Null

try {
    $installerPath = Join-Path $scriptsRoot 'install-mafteach.ps1'
    $hostPath = Join-Path $wizardRoot 'Start-MafteachInstaller.ps1'
    $htmlPath = Join-Path $wizardRoot 'index.html'

    Expand-Payload -Name 'installer' -Destination $installerPath
    Expand-Payload -Name 'host' -Destination $hostPath
    Expand-Payload -Name 'html' -Destination $htmlPath

    Write-Host ''
    Write-Host '  Mafteach installer' -ForegroundColor Cyan
    Write-Host '  The wizard is opening in your browser. Keep this window open.'
    Write-Host '  Closing the wizard from its own button also closes this window.'
    Write-Host ''

    & (Join-Path $PSHOME 'powershell.exe') @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-STA',
        '-File', $hostPath,
        '-Port', '0',
        '-InstallerScriptPath', $installerPath,
        '-HtmlFilePath', $htmlPath,
        '-WorkRoot', (Join-Path $sessionRoot 'work')
    )
}
finally {
    Remove-Item -LiteralPath $sessionRoot -Recurse -Force -ErrorAction SilentlyContinue
}
'@

$batchHeader = @'
@echo off
setlocal
title Mafteach installer
set "MAFTEACH_BUNDLE_SELF=%~f0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -STA -Command "& ([scriptblock]::Create(((((Get-Content -LiteralPath $env:MAFTEACH_BUNDLE_SELF -Raw -Encoding ASCII) -split ('REM #MAFTEACH' + '_BOOTSTRAP_BEGIN#'))[1]) -split ('REM #MAFTEACH' + '_BOOTSTRAP_END#'))[0]))"
if errorlevel 1 (
  echo.
  echo The installer wizard stopped with an error. Nothing was changed on the device unless the wizard reported otherwise.
  echo.
  pause
)
endlocal
exit /b 0
'@

$content = [System.Collections.Generic.List[string]]::new()
foreach ($line in ($batchHeader -split "\r?\n")) { [void]$content.Add($line) }
[void]$content.Add('REM #MAFTEACH_BOOTSTRAP_BEGIN#')
foreach ($line in ($bootstrap -split "\r?\n")) { [void]$content.Add($line) }
# The bootstrap must end here. Without this terminator the extracted scriptblock
# also contained the base64 payload lines, and PowerShell executed them as
# commands once the wizard returned ("base64 is not recognized...").
[void]$content.Add('REM #MAFTEACH_BOOTSTRAP_END#')
[void]$content.Add('')
[void]$content.Add('REM Embedded payloads (base64). Do not edit by hand.')
foreach ($line in (Get-Base64Block -Name 'installer' -Path $installerScript)) { [void]$content.Add($line) }
foreach ($line in (Get-Base64Block -Name 'host' -Path $hostScript)) { [void]$content.Add($line) }
foreach ($line in (Get-Base64Block -Name 'html' -Path $htmlFile)) { [void]$content.Add($line) }

$text = ($content -join "`r`n") + "`r`n"
$nonAscii = [regex]::Matches($text, '[^\x00-\x7F]')
if ($nonAscii.Count -gt 0) {
    throw "The generated bundle must be pure ASCII but contains $($nonAscii.Count) non-ASCII character(s)."
}

$outputDirectory = Split-Path -Parent $OutputPath
if (-not (Test-Path -LiteralPath $outputDirectory)) {
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}
[System.IO.File]::WriteAllText($OutputPath, $text, (New-Object System.Text.ASCIIEncoding))

$size = (Get-Item -LiteralPath $OutputPath).Length
Write-Host ''
Write-Host 'Single-file installer created.' -ForegroundColor Green
Write-Host "  Path: $OutputPath"
Write-Host "  Size: $('{0:N0}' -f $size) bytes"
Write-Host "  Payloads: install-mafteach.ps1, Start-MafteachInstaller.ps1, index.html"
