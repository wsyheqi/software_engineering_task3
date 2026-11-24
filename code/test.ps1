param([switch]$Browser)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tools/common.ps1')
Set-Location -LiteralPath $PSScriptRoot
$taskNode = Set-ProjectBuildEnvironment
$taskMaven = Find-Maven
& $taskMaven -s deploy/maven-settings.xml "-Dmaven.repo.local=$(Join-Path $PSScriptRoot '.runtime/m2')" -f backend/pom.xml test
if ($LASTEXITCODE -ne 0) { throw 'Backend tests failed.' }
Push-Location -LiteralPath frontend
try {
    $taskNpm = Join-Path (Split-Path $taskNode -Parent) 'npm.cmd'
    & $taskNpm run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend verification failed.' }
    if ($Browser) { & $taskNpm run test:e2e; if ($LASTEXITCODE -ne 0) { throw 'Browser tests failed. Start the demo app before running with -Browser.' } }
} finally { Pop-Location }
