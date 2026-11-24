param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tools/common.ps1')
Set-Location -LiteralPath $PSScriptRoot
New-Item -ItemType Directory -Path '.runtime' -Force | Out-Null
$taskDownloadNode = Get-Command node -ErrorAction SilentlyContinue
$taskEmbeddedNode = Join-Path $env:LOCALAPPDATA 'Programs/Microsoft VS Code/Code.exe'

function Get-ProjectDownload([string]$Uri,[string]$Output) {
    if ($taskDownloadNode) { & $taskDownloadNode.Source (Join-Path $PSScriptRoot 'tools/download.mjs') $Uri $Output }
    elseif (Test-Path -LiteralPath $taskEmbeddedNode) {
        $taskElectronValue = $env:ELECTRON_RUN_AS_NODE
        try { $env:ELECTRON_RUN_AS_NODE = '1'; & $taskEmbeddedNode (Join-Path $PSScriptRoot 'tools/download.mjs') $Uri $Output }
        finally { $env:ELECTRON_RUN_AS_NODE = $taskElectronValue }
    } else { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri $Uri -OutFile $Output -UseBasicParsing }
    if (-not (Test-Path -LiteralPath $Output)) { throw "Download failed: $Output" }
}

try { $taskNodePath = Find-Node } catch {
    $taskVersion = 'v22.23.4'
    $taskArchive = Join-Path $PSScriptRoot '.runtime/node.zip'
    Get-ProjectDownload "https://nodejs.org/dist/$taskVersion/node-$taskVersion-win-x64.zip" $taskArchive
    Get-ProjectDownload "https://nodejs.org/dist/$taskVersion/SHASUMS256.txt" (Join-Path $PSScriptRoot '.runtime/node-sha256.txt')
    $taskChecksumLine = Get-Content -LiteralPath '.runtime/node-sha256.txt' | Where-Object { $_ -match "node-$taskVersion-win-x64.zip$" } | Select-Object -First 1
    $taskExpected = ($taskChecksumLine -split '\s+')[0]
    if ((Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash -ne $taskExpected) { throw 'Node.js checksum mismatch.' }
    Expand-Archive -LiteralPath $taskArchive -DestinationPath '.runtime' -Force
    $taskNodePath = Find-Node
}
$env:PATH = (Split-Path $taskNodePath -Parent) + ';' + $env:PATH
$taskDownloadNode = Get-Command node
try { $null = Find-Maven } catch {
    $taskMavenVersion = '3.9.16'
    $taskArchive = Join-Path $PSScriptRoot '.runtime/maven.zip'
    $taskUrl = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$taskMavenVersion/apache-maven-$taskMavenVersion-bin.zip"
    Get-ProjectDownload $taskUrl $taskArchive
    Get-ProjectDownload ($taskUrl + '.sha512') (Join-Path $PSScriptRoot '.runtime/maven.sha512')
    $taskExpected = (Get-Content -LiteralPath '.runtime/maven.sha512' -Raw).Trim().Split(' ')[0]
    if ((Get-FileHash -LiteralPath $taskArchive -Algorithm SHA512).Hash -ne $taskExpected) { throw 'Maven checksum mismatch.' }
    Expand-Archive -LiteralPath $taskArchive -DestinationPath '.runtime' -Force
}
$taskJava = Get-Command java -ErrorAction SilentlyContinue
if (-not $taskJava) { throw 'Install JDK 25+ before building: https://adoptium.net/' }
$taskJavaVersion = (cmd /c 'java -version 2>&1') -join "`n"
if ($taskJavaVersion -notmatch 'version "25(?:\.|")') { throw 'JDK 25+ is required to build this project. Install it from https://adoptium.net/.' }
Write-Host 'Development tools are ready. Run .\package-desktop.ps1 to build the desktop app.'
