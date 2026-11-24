param([switch]$UseBuiltBackend)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tools/common.ps1')
Set-Location -LiteralPath $PSScriptRoot

$taskNode = Set-ProjectBuildEnvironment
$taskJavaBin = Split-Path (Get-Command java -ErrorAction Stop).Source -Parent
$taskLinker = Join-Path $taskJavaBin 'jlink.exe'
$taskCompiler = Join-Path $env:windir 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
if (-not (Test-Path -LiteralPath $taskLinker)) { throw 'Packaging requires the x64 JDK 25 with jlink.' }
if (-not (Test-Path -LiteralPath $taskCompiler)) { throw 'Packaging requires the .NET Framework 4.8 compiler.' }

if (-not $UseBuiltBackend) { & (Join-Path $PSScriptRoot 'build.ps1') }
$taskJar = Join-Path $PSScriptRoot 'backend/target/hospitality-1.1.0.jar'
if (-not (Test-Path -LiteralPath $taskJar)) { throw 'Build the backend JAR before packaging.' }

$taskCache = Join-Path $PSScriptRoot '.runtime'
$null = New-Item -ItemType Directory -Path $taskCache -Force
$taskSdkVersion = '1.0.4314.35'
$taskSdkHash = '90778DEB1B77E36292828B4B5833C9255C27533F40E0DDD55891AE718944EDC4'
$taskSdk = Join-Path $taskCache "webview2-$taskSdkVersion.nupkg"
if (-not (Test-Path -LiteralPath $taskSdk)) {
    $taskSdkUrl = "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/$taskSdkVersion/microsoft.web.webview2.$taskSdkVersion.nupkg"
    & $taskNode (Join-Path $PSScriptRoot 'tools/download.mjs') $taskSdkUrl $taskSdk
    if ($LASTEXITCODE -ne 0) { throw 'The WebView2 SDK download failed.' }
}
if ((Get-FileHash -LiteralPath $taskSdk -Algorithm SHA256).Hash -ne $taskSdkHash) {
    throw 'The cached WebView2 SDK does not match the expected checksum.'
}

$taskRuntime = Join-Path $taskCache 'desktop-java25'
if (-not (Test-Path -LiteralPath (Join-Path $taskRuntime 'bin/java.exe'))) {
    $taskModules = 'java.se,jdk.unsupported,jdk.crypto.ec,jdk.security.auth,jdk.management,jdk.naming.dns,jdk.charsets,jdk.localedata,jdk.zipfs'
    & $taskLinker '--add-modules' $taskModules '--strip-debug' '--no-header-files' '--no-man-pages' '--compress=zip-6' '--output' $taskRuntime
    if ($LASTEXITCODE -ne 0) { throw 'Could not generate the bundled Java runtime.' }
}

$taskStaging = Join-Path $taskCache 'desktop-build'
$null = New-Item -ItemType Directory -Path $taskStaging -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskSdkFiles = @{
    'lib/net462/Microsoft.Web.WebView2.Core.dll' = 'Microsoft.Web.WebView2.Core.dll'
    'lib/net462/Microsoft.Web.WebView2.WinForms.dll' = 'Microsoft.Web.WebView2.WinForms.dll'
    'runtimes/win-x64/native/WebView2Loader.dll' = 'WebView2Loader.dll'
    'LICENSE.txt' = 'WEBVIEW2-LICENSE.txt'
}
$taskArchive = [IO.Compression.ZipFile]::OpenRead($taskSdk)
try {
    foreach ($taskEntryPath in $taskSdkFiles.Keys) {
        $taskEntry = $taskArchive.GetEntry($taskEntryPath)
        if ($null -eq $taskEntry) { throw "Missing WebView2 SDK entry: $taskEntryPath" }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($taskEntry, (Join-Path $taskStaging $taskSdkFiles[$taskEntryPath]), $true)
    }
} finally { $taskArchive.Dispose() }

$taskExe = Join-Path $taskStaging 'CampusHospitality.exe'
$taskCore = Join-Path $taskStaging 'Microsoft.Web.WebView2.Core.dll'
$taskWinForms = Join-Path $taskStaging 'Microsoft.Web.WebView2.WinForms.dll'
$taskDesktopSource = (Resolve-Path -LiteralPath 'desktop/Program.cs').ProviderPath
& $taskCompiler '/nologo' '/utf8output' '/target:winexe' '/platform:x64' '/optimize+' '/codepage:65001' "/out:$taskExe" `
    '/reference:System.Drawing.dll' '/reference:System.Windows.Forms.dll' "/reference:$taskCore" "/reference:$taskWinForms" `
    $taskDesktopSource
if ($LASTEXITCODE -ne 0) { throw 'The desktop executable could not be compiled.' }

$taskOutput = Join-Path $PSScriptRoot 'release'
$taskOutputExe = Join-Path $taskOutput 'CampusHospitality.exe'
if (Test-Path -LiteralPath $taskOutputExe) {
    try { $taskHandle = [IO.File]::Open($taskOutputExe, 'Open', 'ReadWrite', 'None'); $taskHandle.Dispose() }
    catch [IO.IOException] { throw 'Close the running desktop application before updating the release folder.' }
}
$taskOutputRuntime = Join-Path $taskOutput 'runtime'
$null = New-Item -ItemType Directory -Path $taskOutputRuntime -Force
Get-ChildItem -LiteralPath $taskRuntime -Force | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $taskOutputRuntime -Recurse -Force
}
foreach ($taskFile in $taskSdkFiles.Values) {
    Copy-Item -LiteralPath (Join-Path $taskStaging $taskFile) -Destination $taskOutput -Force
}
Copy-Item -LiteralPath $taskJar -Destination (Join-Path $taskOutput 'hospitality.jar') -Force
Copy-Item -LiteralPath $taskExe -Destination $taskOutputExe -Force
Write-Host "Desktop build complete: $taskOutputExe"
