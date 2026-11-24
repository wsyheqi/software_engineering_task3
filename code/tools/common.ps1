$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path $PSScriptRoot -Parent

function Find-Node {
    $taskNode = Get-Command node -ErrorAction SilentlyContinue
    if ($taskNode) { return $taskNode.Source }
    $taskPortable = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot '.runtime') -Directory -Filter 'node-*-win-x64' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if ($taskPortable) { return Join-Path $taskPortable.FullName 'node.exe' }
    throw 'Node.js 22.12+ is required. Run .\setup.ps1 or install Node.js LTS first.'
}

function Find-Maven {
    $taskMaven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($taskMaven) { return $taskMaven.Source }
    $taskRuntime = Join-Path $ProjectRoot '.runtime'
    $taskPortable = Get-ChildItem -LiteralPath $taskRuntime -Directory -Filter 'apache-maven-*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if ($taskPortable) { return Join-Path $taskPortable.FullName 'bin/mvn.cmd' }
    $taskJetBrains = Join-Path $env:ProgramFiles 'JetBrains'
    if (Test-Path -LiteralPath $taskJetBrains) {
        foreach ($taskIde in Get-ChildItem -LiteralPath $taskJetBrains -Directory | Sort-Object Name -Descending) {
            $taskBundled = Join-Path $taskIde.FullName 'plugins/maven-plugin/lib/maven3/bin/mvn.cmd'
            if (Test-Path -LiteralPath $taskBundled) { return $taskBundled }
        }
    }
    throw 'Maven 3.9+ is required. Run .\setup.ps1 or install Maven first.'
}

function Set-ProjectBuildEnvironment {
    $taskNode = Find-Node
    $env:PATH = (Split-Path $taskNode -Parent) + ';' + $env:PATH
    $env:MAVEN_OPTS = (($env:MAVEN_OPTS + ' -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8').Trim())
    return $taskNode
}
