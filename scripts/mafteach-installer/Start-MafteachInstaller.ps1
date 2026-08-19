<#
.SYNOPSIS
    Localhost host for the visual Mafteach installation wizard.
.DESCRIPTION
    Serves the bundled HTML UI on 127.0.0.1 and exposes a token-protected local
    API. Device inspection and installation are delegated to install-mafteach.ps1,
    which remains the safety authority for signer, version and Device Owner checks.
#>

[CmdletBinding()]
param(
    [int]$Port = 8765,
    [switch]$ValidateOnly,
    [switch]$NoBrowser,
    [string]$InstallerScriptPath = '',
    [string]$HtmlFilePath = '',
    [string]$WorkRoot = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$PackageName = 'com.secureguard.mdm'
$ScriptDirectory = $PSScriptRoot
$ProjectRoot = [System.IO.Path]::GetFullPath((Join-Path $ScriptDirectory '..\..'))

# When bundled into the single-file launcher the sources are extracted next to
# this script, so the caller supplies explicit paths instead of project paths.
$InstallerScript = if ($InstallerScriptPath) {
    [System.IO.Path]::GetFullPath($InstallerScriptPath)
} else {
    Join-Path $ProjectRoot 'scripts\install-mafteach.ps1'
}
$HtmlPath = if ($HtmlFilePath) {
    [System.IO.Path]::GetFullPath($HtmlFilePath)
} else {
    Join-Path $ScriptDirectory 'index.html'
}
$WorkDirectory = if ($WorkRoot) {
    [System.IO.Path]::GetFullPath($WorkRoot)
} else {
    Join-Path $ProjectRoot 'app\build\tmp\agent\mafteach-installer'
}
$PowerShellPath = Join-Path $PSHOME 'powershell.exe'
$script:StopRequested = $false
$script:LastActivity = [DateTime]::UtcNow
$SessionToken = [Guid]::NewGuid().ToString('N')

function ConvertTo-JsonText {
    param([object]$Value)
    return ($Value | ConvertTo-Json -Depth 8 -Compress)
}

function New-ApiResult {
    param(
        [bool]$Success,
        [object]$Data = $null,
        [string]$Message = ''
    )
    return [pscustomobject]@{
        success = $Success
        message = $Message
        data = $Data
    }
}

# Most end users have no Android SDK. The command line tools are fetched once
# from Google's official repository into a per-user cache instead of failing, and
# nothing is redistributed inside this project or inside the single-file bundle.
$script:PortableToolsRoot = if ($env:LOCALAPPDATA) {
    Join-Path $env:LOCALAPPDATA 'Mafteach\android-tools'
}
else {
    Join-Path $env:TEMP 'Mafteach\android-tools'
}

$script:PortableToolSources = @{
    'adb.exe'   = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'
    'aapt2.exe' = 'https://dl.google.com/android/repository/build-tools_r34-windows.zip'
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
    Write-Host ''
    Write-Host "  Downloading $ToolName once from Google's official repository..." -ForegroundColor Yellow

    $stagingRoot = Join-Path $env:TEMP ('mafteach-tools-' + [Guid]::NewGuid().ToString('N'))
    $archivePath = Join-Path $stagingRoot 'download.zip'
    $extractRoot = Join-Path $stagingRoot 'extracted'
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
        $ProgressPreference = 'SilentlyContinue'
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

        Write-Host "  Ready: $($tool.FullName)" -ForegroundColor Green
        return $tool.FullName
    }
    catch {
        Write-Host "  Automatic download failed: $($_.Exception.Message)" -ForegroundColor Yellow
        return $null
    }
    finally {
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Resolve-Adb {
    param([switch]$NoDownload)
    $candidates = @()
    if ($env:LOCALAPPDATA) { $candidates += Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
    if ($env:ANDROID_HOME) { $candidates += Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' }
    if ($env:ANDROID_SDK_ROOT) { $candidates += Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe' }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) { return $candidate }
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    if (-not $NoDownload) {
        $portable = Get-PortableAndroidTool -ToolName 'adb.exe'
        if ($portable) { return $portable }
    }
    throw 'adb.exe was not found and could not be downloaded automatically. Check the internet connection or install Android platform-tools.'
}

function Get-ConnectedDevices {
    $adb = Resolve-Adb
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $adb devices -l 2>&1 | ForEach-Object { "$_" })
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0) {
        throw "adb devices failed: $($output -join ' ')"
    }

    $devices = [System.Collections.Generic.List[object]]::new()
    foreach ($line in $output) {
        if ($line -notmatch '^(\S+)\s+(device|unauthorized|offline|no permissions)(?:\s+(.*))?$') { continue }
        $serial = [string]$Matches[1]
        $state = [string]$Matches[2]
        $details = [string]$Matches[3]
        $model = if ($details -match '(?:^|\s)model:(\S+)') { $Matches[1] } else { '' }
        $product = if ($details -match '(?:^|\s)product:(\S+)') { $Matches[1] } else { '' }
        $androidDevice = if ($details -match '(?:^|\s)device:(\S+)') { $Matches[1] } else { '' }
        [void]$devices.Add([pscustomobject]@{
            serial = $serial
            state = $state
            model = $model
            product = $product
            device = $androidDevice
            ready = ($state -eq 'device')
        })
    }
    return [pscustomobject]@{
        adbPath = $adb
        devices = @($devices)
    }
}

# The dialog runs in a dedicated STA runspace with a top-most owner window.
# Without an owner the dialog opens behind the browser and looks like nothing
# happened, and without a guaranteed STA apartment it can fail to show at all.
function Select-ApkFile {
    $dialogScript = @'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$owner = New-Object System.Windows.Forms.Form
$owner.Text = 'Mafteach'
$owner.StartPosition = 'CenterScreen'
$owner.Size = New-Object System.Drawing.Size(2, 2)
$owner.ShowInTaskbar = $false
$owner.TopMost = $true
$owner.Opacity = 0
[void]$owner.Show()
[void]$owner.Activate()
[System.Windows.Forms.Application]::DoEvents()

$dialog = New-Object System.Windows.Forms.OpenFileDialog
$dialog.Title = 'Select the Mafteach APK file'
$dialog.Filter = 'Android APK (*.apk)|*.apk'
$dialog.CheckFileExists = $true
$dialog.Multiselect = $false
$dialog.RestoreDirectory = $true

$picked = ''
try {
    if ($dialog.ShowDialog($owner) -eq [System.Windows.Forms.DialogResult]::OK) {
        $picked = $dialog.FileName
    }
}
finally {
    $dialog.Dispose()
    $owner.Close()
    $owner.Dispose()
}
$picked
'@

    $runspace = [runspacefactory]::CreateRunspace()
    $runspace.ApartmentState = 'STA'
    $runspace.ThreadOptions = 'ReuseThread'
    $runspace.Open()
    try {
        $shell = [powershell]::Create()
        try {
            $shell.Runspace = $runspace
            [void]$shell.AddScript($dialogScript)
            $output = $shell.Invoke()
            if ($shell.Streams.Error.Count -gt 0) {
                throw (($shell.Streams.Error | ForEach-Object { $_.ToString() }) -join '; ')
            }
            $value = @($output | Where-Object { "$_" -ne '' }) | Select-Object -First 1
            if ($value) { return [string]$value }
            return $null
        }
        finally {
            $shell.Dispose()
        }
    }
    finally {
        $runspace.Close()
        $runspace.Dispose()
    }
}

function Invoke-Installer {
    param(
        [Parameter(Mandatory = $true)][string]$ApkPath,
        [Parameter(Mandatory = $true)][string]$Serial,
        [switch]$CheckOnly
    )

    if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
        throw "APK file was not found: $ApkPath"
    }
    if ([System.IO.Path]::GetExtension($ApkPath) -ne '.apk') {
        throw 'Select an APK file.'
    }
    if ([string]::IsNullOrWhiteSpace($Serial)) {
        throw 'No device was selected.'
    }

    New-Item -ItemType Directory -Force -Path $WorkDirectory | Out-Null
    $operationId = [Guid]::NewGuid().ToString('N')
    $planPath = Join-Path $WorkDirectory "$operationId-plan.json"
    $arguments = @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', $InstallerScript,
        '-ApkPath', $ApkPath,
        '-Serial', $Serial
    )
    if ($CheckOnly) {
        $arguments += @('-CheckOnly', '-PlanOutputPath', $planPath)
    }

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = @(& $PowerShellPath @arguments 2>&1 | ForEach-Object { "$_" })
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }

    $plan = $null
    try {
        if ($CheckOnly -and (Test-Path -LiteralPath $planPath -PathType Leaf)) {
            $plan = Get-Content -LiteralPath $planPath -Raw -Encoding UTF8 | ConvertFrom-Json
        }
    }
    finally {
        Remove-Item -LiteralPath $planPath -Force -ErrorAction SilentlyContinue
    }

    return [pscustomobject]@{
        exitCode = $exitCode
        output = ($lines -join "`r`n")
        plan = $plan
    }
}

function Read-HttpLine {
    param([System.IO.Stream]$Stream)

    $bytes = [System.Collections.Generic.List[byte]]::new()
    while ($bytes.Count -lt 16384) {
        # A browser may open a speculative socket and never send a request. The
        # read then fails on timeout or reset, which must be reported as "no
        # request" instead of terminating the wizard.
        try {
            $value = $Stream.ReadByte()
        }
        catch [System.IO.IOException] {
            return $null
        }
        catch [System.ObjectDisposedException] {
            return $null
        }
        if ($value -lt 0) { break }
        if ($value -eq 13) {
            $next = $Stream.ReadByte()
            if ($next -eq 10) { break }
            [void]$bytes.Add([byte]$value)
            if ($next -ge 0) { [void]$bytes.Add([byte]$next) }
            continue
        }
        [void]$bytes.Add([byte]$value)
    }
    return [System.Text.Encoding]::ASCII.GetString($bytes.ToArray())
}

function Read-HttpRequest {
    param([System.Net.Sockets.TcpClient]$Client)

    $stream = $Client.GetStream()
    $stream.ReadTimeout = 5000
    $stream.WriteTimeout = 15000
    $requestLine = Read-HttpLine -Stream $stream
    if ($null -eq $requestLine -or [string]::IsNullOrWhiteSpace($requestLine)) { return $null }
    $parts = $requestLine.Split(' ')
    if ($parts.Count -lt 2) { throw 'Invalid HTTP request line.' }

    $headers = @{}
    while ($true) {
        $line = Read-HttpLine -Stream $stream
        if ($null -eq $line) { return $null }
        if ($line.Length -eq 0) { break }
        $separator = $line.IndexOf(':')
        if ($separator -gt 0) {
            $headers[$line.Substring(0, $separator).Trim().ToLowerInvariant()] = $line.Substring($separator + 1).Trim()
        }
    }

    $contentLength = 0
    if ($headers.ContainsKey('content-length')) {
        $contentLength = [int]$headers['content-length']
    }
    if ($contentLength -lt 0 -or $contentLength -gt 1048576) {
        throw 'Request body is too large.'
    }

    $body = ''
    if ($contentLength -gt 0) {
        $buffer = New-Object byte[] $contentLength
        $read = 0
        while ($read -lt $contentLength) {
            $count = $stream.Read($buffer, $read, $contentLength - $read)
            if ($count -le 0) { break }
            $read += $count
        }
        if ($read -ne $contentLength) { throw 'Incomplete HTTP request body.' }
        $body = [System.Text.Encoding]::UTF8.GetString($buffer)
    }

    return [pscustomobject]@{
        Method = $parts[0].ToUpperInvariant()
        Target = $parts[1]
        Headers = $headers
        Body = $body
        Stream = $stream
    }
}

function Write-HttpResponse {
    param(
        [System.IO.Stream]$Stream,
        [int]$StatusCode,
        [string]$ContentType,
        [string]$Body,
        [hashtable]$ExtraHeaders = @{}
    )

    $statusText = switch ($StatusCode) {
        200 { 'OK' }
        204 { 'No Content' }
        400 { 'Bad Request' }
        403 { 'Forbidden' }
        404 { 'Not Found' }
        405 { 'Method Not Allowed' }
        default { 'Internal Server Error' }
    }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
    $headers = @(
        "HTTP/1.1 $StatusCode $statusText",
        "Content-Type: $ContentType",
        "Content-Length: $($bytes.Length)",
        'Cache-Control: no-store',
        'X-Content-Type-Options: nosniff',
        'Referrer-Policy: no-referrer',
        'Connection: close'
    )
    foreach ($key in $ExtraHeaders.Keys) { $headers += "$key`: $($ExtraHeaders[$key])" }
    $headerBytes = [System.Text.Encoding]::ASCII.GetBytes(($headers -join "`r`n") + "`r`n`r`n")
    $Stream.Write($headerBytes, 0, $headerBytes.Length)
    if ($bytes.Length -gt 0) { $Stream.Write($bytes, 0, $bytes.Length) }
    $Stream.Flush()
}

function Write-JsonResponse {
    param(
        [System.IO.Stream]$Stream,
        [int]$StatusCode,
        [object]$Value
    )
    Write-HttpResponse -Stream $Stream -StatusCode $StatusCode -ContentType 'application/json; charset=utf-8' -Body (ConvertTo-JsonText $Value)
}

function Test-ApiToken {
    param([hashtable]$Headers)
    return ($Headers.ContainsKey('x-mafteach-token') -and $Headers['x-mafteach-token'] -ceq $SessionToken)
}

function Handle-Request {
    param([object]$Request)

    $path = ([Uri]("http://127.0.0.1" + $Request.Target)).AbsolutePath
    if ($Request.Method -eq 'GET' -and ($path -eq '/' -or $path -eq '/index.html')) {
        $html = Get-Content -LiteralPath $HtmlPath -Raw -Encoding UTF8
        $html = $html.Replace('__SESSION_TOKEN__', $SessionToken)
        Write-HttpResponse -Stream $Request.Stream -StatusCode 200 -ContentType 'text/html; charset=utf-8' -Body $html -ExtraHeaders @{
            'Content-Security-Policy' = "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
        }
        return
    }

    if ($path -notlike '/api/*') {
        Write-HttpResponse -Stream $Request.Stream -StatusCode 404 -ContentType 'text/plain; charset=utf-8' -Body 'Not found'
        return
    }
    if ($Request.Method -ne 'POST') {
        Write-JsonResponse -Stream $Request.Stream -StatusCode 405 -Value (New-ApiResult -Success $false -Message 'Method not allowed')
        return
    }
    if (-not (Test-ApiToken -Headers $Request.Headers)) {
        Write-JsonResponse -Stream $Request.Stream -StatusCode 403 -Value (New-ApiResult -Success $false -Message 'Invalid session token')
        return
    }

    $payload = if ([string]::IsNullOrWhiteSpace($Request.Body)) { $null } else { $Request.Body | ConvertFrom-Json }
    switch ($path) {
        '/api/devices' {
            $result = Get-ConnectedDevices
            Write-JsonResponse -Stream $Request.Stream -StatusCode 200 -Value (New-ApiResult -Success $true -Data $result)
        }
        '/api/pick-apk' {
            $selected = Select-ApkFile
            Write-JsonResponse -Stream $Request.Stream -StatusCode 200 -Value (New-ApiResult -Success $true -Data @{ path = $selected })
        }
        '/api/inspect' {
            if ($null -eq $payload) { throw 'Missing request body.' }
            $result = Invoke-Installer -ApkPath ([string]$payload.apkPath) -Serial ([string]$payload.serial) -CheckOnly
            $success = ($null -ne $result.plan)
            Write-JsonResponse -Stream $Request.Stream -StatusCode 200 -Value (New-ApiResult -Success $success -Data $result -Message $(if ($success) { '' } else { 'Device inspection did not complete.' }))
        }
        '/api/install' {
            if ($null -eq $payload) { throw 'Missing request body.' }
            $result = Invoke-Installer -ApkPath ([string]$payload.apkPath) -Serial ([string]$payload.serial)
            $success = ($result.exitCode -eq 0)
            Write-JsonResponse -Stream $Request.Stream -StatusCode 200 -Value (New-ApiResult -Success $success -Data $result -Message $(if ($success) { 'Installation and verification completed.' } else { 'Installation did not complete. Review the instructions and technical output.' }))
        }
        '/api/shutdown' {
            $script:StopRequested = $true
            Write-JsonResponse -Stream $Request.Stream -StatusCode 200 -Value (New-ApiResult -Success $true -Message 'Wizard closed.')
        }
        default {
            Write-JsonResponse -Stream $Request.Stream -StatusCode 404 -Value (New-ApiResult -Success $false -Message 'Unknown API endpoint')
        }
    }
}

if (-not (Test-Path -LiteralPath $InstallerScript -PathType Leaf)) { throw "Installer script was not found: $InstallerScript" }
if (-not (Test-Path -LiteralPath $HtmlPath -PathType Leaf)) { throw "Installer HTML was not found: $HtmlPath" }
if (-not (Test-Path -LiteralPath $PowerShellPath -PathType Leaf)) { throw "Windows PowerShell was not found: $PowerShellPath" }

if ($ValidateOnly) {
    $html = Get-Content -LiteralPath $HtmlPath -Raw -Encoding UTF8
    if ($html -notmatch '__SESSION_TOKEN__') { throw 'HTML session token placeholder is missing.' }
    if ($html -notmatch '/api/install' -or $html -notmatch '/api/inspect') { throw 'Required API calls are missing from HTML.' }
    # Validation must never download anything, so the local lookup is used only.
    try { [void](Resolve-Adb -NoDownload) } catch { Write-Host '  adb is not installed locally; the wizard would fetch it at runtime.' }
    Write-Host 'Mafteach installer host validation passed.'
    exit 0
}

# Preparing the tools before the browser opens keeps the one-time download
# visible in this console instead of stalling the first request from the wizard.
try {
    [void](Resolve-Adb)
}
catch {
    Write-Host ''
    Write-Host "  $($_.Exception.Message)" -ForegroundColor Yellow
    Write-Host '  The wizard will still open and will report the problem in step 3.'
}

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
try {
    $listener.Start()
    $actualPort = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    $url = "http://127.0.0.1:$actualPort/"
    if (-not $NoBrowser) { Start-Process $url }

    while (-not $script:StopRequested) {
        if (-not $listener.Pending()) {
            if (([DateTime]::UtcNow - $script:LastActivity).TotalHours -ge 2) { break }
            Start-Sleep -Milliseconds 100
            continue
        }

        $client = $null
        try {
            $client = $listener.AcceptTcpClient()
            $client.NoDelay = $true
            $script:LastActivity = [DateTime]::UtcNow
            $request = Read-HttpRequest -Client $client
            if ($null -ne $request) {
                try {
                    Handle-Request -Request $request
                }
                catch {
                    Write-Verbose "Request handling failed: $($_.Exception.Message)"
                    try {
                        Write-JsonResponse -Stream $request.Stream -StatusCode 500 -Value (New-ApiResult -Success $false -Message $_.Exception.Message)
                    }
                    catch { }
                }
            }
        }
        catch {
            # A single bad, idle or aborted socket must never end the session.
            # Under ErrorActionPreference=Stop an unhandled socket exception here
            # killed the wizard while the browser was still open.
            Write-Verbose "Connection dropped: $($_.Exception.Message)"
        }
        finally {
            if ($null -ne $client) { $client.Dispose() }
        }
    }
}
finally {
    $listener.Stop()
}
