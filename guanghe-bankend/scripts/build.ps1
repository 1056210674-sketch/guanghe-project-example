[CmdletBinding()]
param(
    [string]$JdkHome = $(
        if ([string]::IsNullOrWhiteSpace($env:ZHIXIAOHE_JDK_HOME)) {
            'D:\Dev\Java\jdk21'
        }
        else {
            $env:ZHIXIAOHE_JDK_HOME
        }
    ),
    [string[]]$MavenOptions = @(),
    [switch]$RequireCleanWorktree,
    [switch]$RequireZeroSkipped
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$mavenWrapper = Join-Path $repositoryRoot 'mvnw.cmd'

if (-not (Test-Path -LiteralPath $JdkHome -PathType Container)) {
    throw "JDK directory does not exist: $JdkHome"
}
if (-not (Test-Path -LiteralPath $mavenWrapper -PathType Leaf)) {
    throw "Maven wrapper does not exist: $mavenWrapper"
}

$resolvedJdkHome = (Resolve-Path -LiteralPath $JdkHome).Path.TrimEnd('\')
$javaExecutable = Join-Path $resolvedJdkHome 'bin\java.exe'
$javacExecutable = Join-Path $resolvedJdkHome 'bin\javac.exe'

if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
    throw "java.exe does not exist in the selected JDK: $javaExecutable"
}
if (-not (Test-Path -LiteralPath $javacExecutable -PathType Leaf)) {
    throw "javac.exe does not exist in the selected JDK: $javacExecutable"
}

$javaVersion = (& $javaExecutable -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($javaVersion)) {
    throw 'java -version did not produce a successful, non-empty result.'
}
if ($javaVersion -notmatch '(?:java|openjdk) version "21(?:\.|\")') {
    throw "The selected java runtime is not JDK 21:`n$javaVersion"
}

$javacVersion = (& $javacExecutable -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $javacVersion -notmatch '^javac 21(?:\.|$)') {
    throw "The selected javac is unavailable or is not JDK 21:`n$javacVersion"
}

Write-Host "[preflight] JAVA_HOME=$resolvedJdkHome"
Write-Host '[preflight] java runtime version=21'
Write-Host '[preflight] javac version=21'

$mavenPropertyPrefix = '(?:-D\s*|--define(?:=|\s+))'
$separatedDefinitionPattern = '(?i)(?<![A-Za-z0-9_.-])(?:-D|--define)(?![A-Za-z0-9_.=-])'
$testSkippingPattern = "(?i)$mavenPropertyPrefix(?:maven\.test\.skip|skipTests|skipITs|failsafe\.skip|surefire\.skip)(?:=|\s|$)"
$testSelectionPattern = "(?i)$mavenPropertyPrefix(?:test|it\.test|groups|excludedGroups|includeJUnit5Engines|excludeJUnit5Engines|includesFile|excludesFile|suiteXmlFiles|(?:surefire|failsafe)\.(?:(?:includes|excludes)(?:File)?|suiteXmlFiles))(?:=|\s|$)"

function Assert-NoInheritedTestControl {
    param(
        [string]$Source,
        [AllowEmptyString()][string]$Value
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return
    }
    if ($Value -match $separatedDefinitionPattern) {
        throw "$Source contains a separated -D/--define option; the trusted build requires one-token property definitions."
    }
    if ($Value -match $testSkippingPattern) {
        throw "$Source contains a test-skipping option; the trusted build refuses inherited test controls."
    }
    if ($Value -match $testSelectionPattern) {
        throw "$Source contains a test-selection option; the trusted build refuses inherited test controls."
    }
}

if (-not [string]::IsNullOrWhiteSpace($env:MAVEN_ARGS)) {
    throw 'MAVEN_ARGS must be unset for the trusted build.'
}

Assert-NoInheritedTestControl `
    -Source 'MavenOptions' `
    -Value ($MavenOptions -join ' ')

foreach ($variableName in @('MAVEN_OPTS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS')) {
    Assert-NoInheritedTestControl `
        -Source $variableName `
        -Value ([Environment]::GetEnvironmentVariable($variableName))
}

foreach ($configPath in @(
    (Join-Path $repositoryRoot '.mvn\maven.config'),
    (Join-Path $repositoryRoot '.mvn\jvm.config')
)) {
    if (Test-Path -LiteralPath $configPath -PathType Leaf) {
        Assert-NoInheritedTestControl `
            -Source $configPath `
            -Value (Get-Content -LiteralPath $configPath -Raw)
    }
}

$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:Path
$previousGitDirectory = $env:GIT_DIR
$previousGitWorkTree = $env:GIT_WORK_TREE

Push-Location $repositoryRoot
try {
    $env:JAVA_HOME = $resolvedJdkHome
    $env:Path = "$(Join-Path $resolvedJdkHome 'bin');$previousPath"

    # git-commit-id resolves linked worktrees to the primary checkout. Pin the
    # native Git process to this checkout so release metadata cannot leak from
    # another agent's branch.
    $env:GIT_DIR = $null
    $env:GIT_WORK_TREE = $null
    $gitDirectory = (& git -C $repositoryRoot rev-parse --absolute-git-dir 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $gitDirectory -PathType Container)) {
        throw 'Unable to resolve the active Git worktree metadata directory.'
    }
    $env:GIT_DIR = $gitDirectory
    $env:GIT_WORK_TREE = (Resolve-Path -LiteralPath $repositoryRoot).Path

    $mavenVersion = (& $mavenWrapper --version 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($mavenVersion)) {
        throw 'Maven Wrapper --version did not produce a successful, non-empty result.'
    }
    if ($mavenVersion -notmatch '(?m)^Java version:\s*21(?:\.|,|\s)') {
        throw 'Maven is not running on JDK 21.'
    }
    $normalizedMavenVersion = $mavenVersion.Replace('/', '\').ToLowerInvariant()
    $expectedRuntime = "runtime: $resolvedJdkHome".ToLowerInvariant()
    if (-not $normalizedMavenVersion.Contains($expectedRuntime)) {
        throw "Maven did not select the requested JDK runtime $resolvedJdkHome."
    }

    $mavenBanner = $mavenVersion -split "`r?`n" | Where-Object { $_ -match '^Apache Maven ' } | Select-Object -First 1
    Write-Host "[preflight] $mavenBanner"

    Write-Host "[preflight] Git metadata directory=$gitDirectory"

    $initialRepositoryCommit = $null
    if ($RequireCleanWorktree) {
        $initialRepositoryCommit = (& git -C $repositoryRoot rev-parse HEAD 2>&1 | Out-String).Trim().ToLowerInvariant()
        if ($LASTEXITCODE -ne 0 -or $initialRepositoryCommit -notmatch '^[0-9a-f]{40,64}$') {
            throw 'Unable to resolve the repository HEAD commit before the build.'
        }
        $initialWorktreeStatus = (& git -C $repositoryRoot status --porcelain --untracked-files=normal 2>&1 | Out-String)
        if ($LASTEXITCODE -ne 0) {
            throw 'Unable to inspect the Git worktree before the build.'
        }
        if (-not [string]::IsNullOrWhiteSpace($initialWorktreeStatus)) {
            throw 'The release build requires a clean Git worktree before Maven starts.'
        }
    }

    $buildStartedAt = [DateTime]::UtcNow
    & $mavenWrapper clean verify @MavenOptions
    $mavenExitCode = $LASTEXITCODE
    if ($mavenExitCode -ne 0) {
        throw "Maven clean verify failed with exit code $mavenExitCode."
    }

    $surefireDirectory = Join-Path $repositoryRoot 'target\surefire-reports'
    $surefireReports = @(
        Get-ChildItem -LiteralPath $surefireDirectory -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue
    )
    if ($surefireReports.Count -eq 0) {
        throw 'Maven returned success but produced no Surefire XML reports; the build is rejected.'
    }
    $failsafeDirectory = Join-Path $repositoryRoot 'target\failsafe-reports'
    $failsafeReports = @(
        Get-ChildItem -LiteralPath $failsafeDirectory -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue
    )
    $testReports = @($surefireReports) + @($failsafeReports)

    $tests = 0L
    $failures = 0L
    $errors = 0L
    $skipped = 0L
    foreach ($report in $testReports) {
        if ($report.LastWriteTimeUtc -lt $buildStartedAt.AddSeconds(-2)) {
            throw "Stale Surefire report detected after clean verify: $($report.FullName)"
        }
        try {
            [xml]$reportXml = Get-Content -LiteralPath $report.FullName -Raw
        }
        catch {
            throw "Surefire report is not valid XML: $($report.FullName)"
        }
        $suites = @($reportXml.SelectNodes("//*[local-name()='testsuite' and not(ancestor::*[local-name()='testsuite'])]"))
        if ($suites.Count -eq 0) {
            throw "Surefire report contains no testsuite element: $($report.FullName)"
        }
        foreach ($suite in $suites) {
            $tests += [long]$suite.tests
            $failures += [long]$suite.failures
            $errors += [long]$suite.errors
            $skipped += [long]$suite.skipped
            $flakyResults = @($suite.SelectNodes(".//*[local-name()='flakyFailure' or local-name()='flakyError']"))
            if ($flakyResults.Count -ne 0) {
                throw "Surefire report contains a flaky retry result: $($report.FullName)"
            }
        }
    }

    if ($tests -eq 0) {
        throw 'Surefire reports contain zero tests; the build is rejected.'
    }
    if ($failures -ne 0 -or $errors -ne 0) {
        throw "Surefire reports contain failures: tests=$tests failures=$failures errors=$errors skipped=$skipped"
    }
    if ($RequireZeroSkipped -and $skipped -ne 0) {
        throw "The remote Gate requires zero skipped tests, but Surefire reports contain $skipped skipped tests."
    }

    $gitPropertiesPath = Join-Path $repositoryRoot 'target\classes\git.properties'
    if (-not (Test-Path -LiteralPath $gitPropertiesPath -PathType Leaf)) {
        throw 'The build did not generate target/classes/git.properties.'
    }
    $gitPropertiesText = Get-Content -LiteralPath $gitPropertiesPath -Raw
    $commitMatch = [regex]::Match($gitPropertiesText, '(?m)^git\.commit\.id(?:\.full)?=([0-9a-fA-F]{40,64})\s*$')
    if (-not $commitMatch.Success) {
        throw 'git.properties does not contain a full Git commit ID.'
    }
    $builtCommit = $commitMatch.Groups[1].Value.ToLowerInvariant()
    $repositoryCommit = (& git -C $repositoryRoot rev-parse HEAD 2>&1 | Out-String).Trim().ToLowerInvariant()
    if ($LASTEXITCODE -ne 0 -or $repositoryCommit -notmatch '^[0-9a-f]{40,64}$') {
        throw 'Unable to resolve the repository HEAD commit.'
    }
    if ($builtCommit -ne $repositoryCommit) {
        throw "Generated Git commit $builtCommit does not match repository HEAD $repositoryCommit."
    }
    $dirtyMatch = [regex]::Match($gitPropertiesText, '(?m)^git\.dirty=(true|false)\s*$')
    if (-not $dirtyMatch.Success) {
        throw 'git.properties does not contain the Git worktree state.'
    }
    $gitDirty = [bool]::Parse($dirtyMatch.Groups[1].Value)
    if ($RequireCleanWorktree -and $gitDirty) {
        throw 'The release build requires a clean Git worktree, but git.properties reports dirty=true.'
    }
    if ($RequireCleanWorktree) {
        if ($repositoryCommit -ne $initialRepositoryCommit) {
            throw 'The repository HEAD commit changed during the build.'
        }
        $finalWorktreeStatus = (& git -C $repositoryRoot status --porcelain --untracked-files=normal 2>&1 | Out-String)
        if ($LASTEXITCODE -ne 0) {
            throw 'Unable to inspect the Git worktree after the build.'
        }
        if (-not [string]::IsNullOrWhiteSpace($finalWorktreeStatus)) {
            throw 'The release build requires a clean Git worktree after Maven completes.'
        }
    }

    Write-Host "[verify] Surefire reports=$($surefireReports.Count) Failsafe reports=$($failsafeReports.Count) tests=$tests failures=$failures errors=$errors skipped=$skipped"
    Write-Host "[verify] Git commit=$builtCommit dirty=$($gitDirty.ToString().ToLowerInvariant())"
    Write-Host '[verify] BUILD PASSED'
}
finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
    $env:Path = $previousPath
    $env:GIT_DIR = $previousGitDirectory
    $env:GIT_WORK_TREE = $previousGitWorkTree
}
