<#
.SYNOPSIS
    בונה את אפליקציית מפתח ומפרסם אותה לערוץ העדכון ב-R2, בפקודה אחת.

.DESCRIPTION
    מיועד להרצה מתיקיית השורש של פרויקט Android. הסקריפט מבצע:

      1. בדיקת סביבה: JDK, gradlew, כלי Android וסקריפט הפרסום באתר.
      2. בניית APK באמצעות Gradle, בלי clean, כדי לנצל build אינקרמנטלי.
      3. אימות מקומי של package, גרסה וחתימת ה-APK באמצעות aapt2 ו-apksigner.
      4. העברת ה-APK ל-publish-mafteach-update.ps1 בפרויקט האתר, שמעלה
         ל-R2, מאמת את התוכן שחזר, כותב metadata ו-pointer ב-R2 ומאמת
         את ה-endpoint.

    הסקריפט אינו דורש adb או מכשיר מחובר, אינו נוגע ב-Device Owner,
    אינו מסיר אפליקציות ואינו מתקין דבר על מכשיר.

.PARAMETER Channel
    stable או prebuild. ברירת המחדל stable.

.PARAMETER BuildType
    debug או release. ברירת המחדל release, עם ה-keystore הקבוע שמוגדר
    מחוץ לפרויקט.

.PARAMETER ReleaseNotes
    הערות גרסה שיוצגו למשתמש בדיאלוג העדכון באפליקציה.

.PARAMETER SkipBuild
    מדלג על Gradle ומשתמש ב-APK שכבר נבנה.

.PARAMETER ApkPath
    נתיב APK מפורש עבור `-SkipBuild`. כאשר לא סופק, נבחר ה-versionCode
    הגבוה ביותר מתיקיית הפלט במקום להסתמך על זמן שינוי.

.PARAMETER DryRun
    מבצע בנייה ובדיקות בלבד, בלי כתיבה ל-R2.

.PARAMETER AllowSameVersionReplacement
    מעביר לפרסום אישור מפורש להחלפת pointer באותו versionCode. מיועד רק
    למיגרציה של artifact שלא הופץ.

.EXAMPLE
    powershell -File ".\scripts\release-mafteach.ps1" -ReleaseNotes "שיפורי יציבות" -DryRun

.EXAMPLE
    powershell -File ".\scripts\release-mafteach.ps1" -BuildType release -ReleaseNotes "מרכז אפליקציות מחודש"

.NOTES
    דרישות: JDK 17, Android SDK build-tools ו-wrangler מאומת ל-Cloudflare.
    אין צורך ב-Firebase או ב-Firestore במסלול העדכון.

    לפני פרסום יש להעלות את shippingVersionCode ב-app/build.gradle.kts,
    אחרת הפרסום ייעצר משום שהגרסה אינה גבוהה מהגרסה הפעילה.
#>

[CmdletBinding()]
param(
    [ValidateSet('stable', 'prebuild')]
    [string] $Channel = 'stable',

    [ValidateSet('debug', 'release')]
    [string] $BuildType = 'release',

    [string] $ReleaseNotes = '',

    [string] $SitePath = 'C:\projects\site\my-landing-page',

    [string] $ApkPath = '',

    [switch] $SkipBuild,

    [switch] $AllowSameVersionReplacement,

    [switch] $DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
$expectedPackage = 'com.secureguard.mdm'

function Write-Step {
    param([string] $Message)
    Write-Host ''
    Write-Host "==> $Message" -ForegroundColor Cyan
}

# כלי Android כותבים הודעות מידע ל-stderr (למשל "Picked up _JAVA_OPTIONS").
# תחת ErrorActionPreference=Stop זה נחשב שגיאה קריטית, ולכן כל קריאה
# לכלי חיצוני עוברת דרך כאן ונבדקת לפי exit code בלבד.
function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string] $FilePath,
        [string[]] $Arguments = @()
    )

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $FilePath @Arguments 2>&1
        return [PSCustomObject]@{
            ExitCode = $LASTEXITCODE
            Lines = @($output | ForEach-Object { "$_" })
        }
    }
    finally {
        $ErrorActionPreference = $previous
    }
}

function Resolve-BuildTool {
    param([string] $ToolName)
    $sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $buildTools = Join-Path $sdkRoot 'build-tools'
    if (-not (Test-Path -LiteralPath $buildTools)) { return $null }
    $candidate = Get-ChildItem -LiteralPath $buildTools -Directory |
        Sort-Object -Property Name -Descending |
        ForEach-Object { Join-Path $_.FullName $ToolName } |
        Where-Object { Test-Path -LiteralPath $_ } |
        Select-Object -First 1
    return $candidate
}

function Get-SignerDigest {
    param([string] $ApkSignerPath, [string] $ApkPath)
    if (-not $ApkSignerPath) { return $null }
    $result = Invoke-Native -FilePath $ApkSignerPath -Arguments @('verify', '--print-certs', $ApkPath)
    if ($result.ExitCode -ne 0) { return $null }
    $line = $result.Lines | Where-Object { $_ -match 'SHA-256 digest:' } | Select-Object -First 1
    if (-not $line) { return $null }
    return ([regex]::Match($line, '([a-f0-9]{64})')).Groups[1].Value
}

# ------------------------------------------------------------ preflight

Write-Step 'בודק סביבה'

$publishScript = Join-Path $SitePath 'scripts\publish-mafteach-update.ps1'
if (-not (Test-Path -LiteralPath $publishScript)) {
    throw "סקריפט הפרסום לא נמצא ב-$publishScript. ודא את הנתיב עם -SitePath."
}

$gradlew = Join-Path $projectRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradlew)) { throw "gradlew.bat לא נמצא ב-$projectRoot." }

if (-not $env:JAVA_HOME) {
    $defaultJdk = Join-Path $env:USERPROFILE '.jdks\temurin-17.0.20'
    if (Test-Path -LiteralPath $defaultJdk) {
        $env:JAVA_HOME = $defaultJdk
        Write-Host "  JAVA_HOME הוגדר ל-$defaultJdk"
    }
    else {
        throw 'JAVA_HOME אינו מוגדר ולא נמצא JDK 17 בברירת המחדל.'
    }
}

$aapt2 = Resolve-BuildTool -ToolName 'aapt2.exe'
if (-not $aapt2) { throw 'aapt2.exe לא נמצא. התקן Android SDK Build-Tools.' }
$apksigner = Resolve-BuildTool -ToolName 'apksigner.bat'
if (-not $apksigner) { throw 'apksigner.bat לא נמצא. לא ניתן לאמת שה-APK חתום.' }

Write-Host "  פרויקט: $projectRoot"
Write-Host "  אתר:    $SitePath"
Write-Host "  ערוץ:   $Channel"
Write-Host "  build:  $BuildType"

# ---------------------------------------------------------------- build

if ($SkipBuild) {
    Write-Step 'מדלג על הבנייה לפי בקשה'
}
else {
    $task = if ($BuildType -eq 'release') { ':app:assembleRelease' } else { ':app:assembleDebug' }
    Write-Step "בונה $task"

    Push-Location $projectRoot
    try {
        & $gradlew --no-daemon --console=plain $task
        if ($LASTEXITCODE -ne 0) { throw 'ה-build נכשל. לא בוצע פרסום.' }
    }
    finally {
        Pop-Location
    }
}

# --------------------------------------------------------------- locate

Write-Step 'מאתר את ה-APK שנבנה'

$outputDirectory = Join-Path $projectRoot "app\build\outputs\apk\$BuildType"
if (-not (Test-Path -LiteralPath $outputDirectory)) {
    throw "תיקיית הפלט לא נמצאה: $outputDirectory"
}

$apkCandidates = @(
    if ($ApkPath) {
        if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) { throw "ה-APK המפורש לא נמצא: $ApkPath" }
        Get-Item -LiteralPath $ApkPath
    }
    else {
        Get-ChildItem -LiteralPath $outputDirectory -Filter '*.apk'
    }
)
if ($apkCandidates.Count -eq 0) { throw "לא נמצא APK ב-$outputDirectory." }

$apkMetadata = foreach ($candidate in $apkCandidates) {
    $candidateBadging = Invoke-Native -FilePath $aapt2 -Arguments @('dump', 'badging', $candidate.FullName)
    if ($candidateBadging.ExitCode -ne 0) { continue }
    $candidatePackageLine = $candidateBadging.Lines | Where-Object { $_ -like 'package:*' } | Select-Object -First 1
    if (-not $candidatePackageLine) { continue }
    $candidatePackageName = ([regex]::Match($candidatePackageLine, "name='([^']+)'")).Groups[1].Value
    $candidateVersionCodeMatch = [regex]::Match($candidatePackageLine, "versionCode='([^']+)'")
    $candidateVersionNameMatch = [regex]::Match($candidatePackageLine, "versionName='([^']+)'")
    if (-not ($candidateVersionCodeMatch.Success -and $candidateVersionNameMatch.Success)) { continue }
    [PSCustomObject]@{
        File = $candidate
        PackageName = $candidatePackageName
        VersionCode = [int]$candidateVersionCodeMatch.Groups[1].Value
        VersionName = $candidateVersionNameMatch.Groups[1].Value
    }
}

$selectedApk = $apkMetadata |
    Where-Object { $_.PackageName -eq $expectedPackage } |
    Sort-Object -Property @{ Expression = 'VersionCode'; Descending = $true }, @{ Expression = { $_.File.LastWriteTime }; Descending = $true } |
    Select-Object -First 1

if (-not $selectedApk) { throw "לא נמצא APK תקין עבור $expectedPackage ב-$outputDirectory." }

$apk = $selectedApk.File
$packageName = $selectedApk.PackageName
$versionCode = $selectedApk.VersionCode
$versionName = $selectedApk.VersionName

if ($packageName -ne $expectedPackage) {
    throw "package name שגוי: $packageName. מצופה $expectedPackage."
}

$signerDigest = Get-SignerDigest -ApkSignerPath $apksigner -ApkPath $apk.FullName
if (-not $signerDigest) {
    throw 'אימות חתימת ה-APK נכשל או שה-APK אינו חתום. לא בוצע פרסום.'
}

Write-Host "  קובץ:       $($apk.Name)"
Write-Host "  גודל:       $('{0:N0}' -f $apk.Length) bytes"
Write-Host "  versionCode: $versionCode"
Write-Host "  versionName: $versionName"
Write-Host "  signer:      $signerDigest"

# -------------------------------------------------------------- publish

Write-Step 'מעביר לפרסום ל-R2 ולערוץ העדכון'

$publishArguments = @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $publishScript,
    '-ApkPath', $apk.FullName,
    '-Channel', $Channel
)
if ($ReleaseNotes) { $publishArguments += @('-ReleaseNotes', $ReleaseNotes) }
if ($AllowSameVersionReplacement) { $publishArguments += '-AllowSameVersionReplacement' }
if ($DryRun) { $publishArguments += '-DryRun' }

Push-Location $SitePath
try {
    & powershell.exe @publishArguments
    $publishExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}

if ($publishExitCode -ne 0) {
    throw 'הפרסום נכשל. ראה את הפלט שלמעלה.'
}

Write-Host ''
Write-Host '=============================================='
if ($DryRun) {
    Write-Host '  DryRun הושלם. לא הועלה דבר ולא נכתב pointer.' -ForegroundColor Yellow
}
else {
    Write-Host "  גרסה $versionName פורסמה בערוץ $Channel." -ForegroundColor Green
    Write-Host '  אין צורך ב-git push או ב-netlify deploy לפרסום גרסה.'
}
Write-Host '=============================================='
