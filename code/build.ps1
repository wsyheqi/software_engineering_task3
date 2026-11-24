param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tools/common.ps1')
Set-Location -LiteralPath $PSScriptRoot
$taskNode = Set-ProjectBuildEnvironment
$taskNpm = Join-Path (Split-Path $taskNode -Parent) 'npm.cmd'
$taskMaven = Find-Maven
Push-Location -LiteralPath 'frontend'
try {
    & $taskNpm ci --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    & $taskNpm run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
} finally { Pop-Location }
$taskBuildArgs = @('-s', (Join-Path $PSScriptRoot 'deploy/maven-settings.xml'), "-Dmaven.repo.local=$(Join-Path $PSScriptRoot '.runtime/m2')", '-f', 'backend/pom.xml', 'package')
if ($SkipTests) { $taskBuildArgs += '-DskipTests' }
& $taskMaven @taskBuildArgs
if ($LASTEXITCODE -ne 0) { throw 'Backend build or tests failed.' }
Write-Host 'Build complete: backend/target/hospitality-1.1.0.jar'
