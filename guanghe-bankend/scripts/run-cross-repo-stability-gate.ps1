[CmdletBinding()]
param(
    [string]$JdkHome = $(
        if ([string]::IsNullOrWhiteSpace($env:ZHIXIAOHE_JDK_HOME)) {
            'D:\JAVA\jdk'
        }
        else {
            $env:ZHIXIAOHE_JDK_HOME
        }
    ),
    [string]$PythonRepository,
    [string]$WebRepository,
    [switch]$RequireCleanWorktree
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$javaRepository = Split-Path -Parent $PSScriptRoot
$workspace = Split-Path -Parent $javaRepository
if ([string]::IsNullOrWhiteSpace($PythonRepository)) {
    $PythonRepository = Join-Path $workspace 'guanghe-agent'
}
if ([string]::IsNullOrWhiteSpace($WebRepository)) {
    $WebRepository = Join-Path $workspace 'guanghe-web'
}

$javaRepository = (Resolve-Path -LiteralPath $javaRepository).Path
$PythonRepository = (Resolve-Path -LiteralPath $PythonRepository).Path
$WebRepository = (Resolve-Path -LiteralPath $WebRepository).Path
$JdkHome = (Resolve-Path -LiteralPath $JdkHome).Path
$mavenWrapper = Join-Path $javaRepository 'mvnw.cmd'
$javaOpenApi = Join-Path $javaRepository 'src\main\resources\contracts\openapi\java-internal-openapi.yaml'
$pythonVerifier = Join-Path $PythonRepository 'scripts\verify_java_internal_contract.py'
$catalogVerifier = Join-Path $PythonRepository 'scripts\verify_runtime_catalog.py'

foreach ($requiredFile in @($mavenWrapper, $javaOpenApi, $pythonVerifier, $catalogVerifier)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required stability Gate file is missing: $requiredFile"
    }
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [Parameter(Mandatory)][string]$Command,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    Push-Location -LiteralPath $WorkingDirectory
    try {
        & $Command @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Gate command failed with exit code ${LASTEXITCODE}: $Command"
        }
    }
    finally {
        Pop-Location
    }
}

$uv = (Get-Command uv -ErrorAction Stop).Source
$catalogArguments = @(
    'run', 'python', $catalogVerifier,
    '--java-repository', $javaRepository,
    '--web-repository', $WebRepository
)
if ($RequireCleanWorktree) {
    $catalogArguments += '--require-clean'
}
Invoke-Checked -WorkingDirectory $PythonRepository -Command $uv -Arguments $catalogArguments

$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:Path
try {
    $env:JAVA_HOME = $JdkHome
    $env:Path = "$(Join-Path $JdkHome 'bin');$previousPath"
    Invoke-Checked -WorkingDirectory $javaRepository -Command $mavenWrapper -Arguments @(
        '-q',
        '-Dtest=com.guanghe.backend.integration.contract.OpenApiContractTests',
        'test'
    )
}
finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:Path = $previousPath
}

Invoke-Checked -WorkingDirectory $PythonRepository -Command $uv -Arguments @(
    'run',
    'python',
    $pythonVerifier,
    $javaOpenApi
)

$npm = (Get-Command npm.cmd -ErrorAction Stop).Source
Invoke-Checked -WorkingDirectory $WebRepository -Command $npm -Arguments @('run', 'check:api')

Write-Output 'CROSS_REPO_STABILITY_GATE=PASS'
