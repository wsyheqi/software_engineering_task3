param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$taskJava = Get-Command java -ErrorAction Stop
$taskJar = Join-Path $PSScriptRoot 'backend/target/hospitality-1.1.0.jar'
if (-not (Test-Path -LiteralPath $taskJar)) { & (Join-Path $PSScriptRoot 'build.ps1'); if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }
Write-Host "Open http://localhost:$Port after startup. Press Ctrl+C to stop."
& $taskJava.Source '-Dfile.encoding=UTF-8' -jar $taskJar "--server.port=$Port" '--debug=false' '--logging.level.org.springframework=INFO' '--logging.level.org.springframework.jdbc=WARN'
exit $LASTEXITCODE
