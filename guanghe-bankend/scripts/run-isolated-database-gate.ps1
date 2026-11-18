param(
    [Parameter(Mandatory = $true)]
    [string]$TestServerAddress,
    [Parameter(Mandatory = $true)]
    [string]$SshKeyPath,
    [switch]$ChatRevisionOnly,
    [switch]$GeoTrendOnly,
    [switch]$GeoCompetitiveOnly,
    [switch]$UsernameProvisioningOnly,
    [switch]$AssetMediaFormatOnly,
    [switch]$ConversationResourcesOnly,
    [switch]$AgentPlatformOnly,
    [switch]$SettlementAccountingOnly,
    [switch]$MonitorExecutionOnly,
    [switch]$ArticleFullOnly,
    [switch]$GeoInterpretationOnly,
    [switch]$GeoDiagnosticOnly,
    [switch]$GeoDiagnosticDeliveryOnly,
    [switch]$MediaSubmissionOnly,
    [switch]$GeoInterpretationFromEmpty
)

$ErrorActionPreference = 'Stop'
if ((@($ChatRevisionOnly, $GeoTrendOnly, $GeoCompetitiveOnly, $GeoInterpretationOnly, $GeoDiagnosticOnly, $GeoDiagnosticDeliveryOnly, $MediaSubmissionOnly, $UsernameProvisioningOnly, $AssetMediaFormatOnly, $ConversationResourcesOnly, $AgentPlatformOnly, $SettlementAccountingOnly, $MonitorExecutionOnly, $ArticleFullOnly | Where-Object { $_ }).Count -gt 1) -or ($GeoInterpretationFromEmpty -and -not $GeoInterpretationOnly)) {
    throw 'Choose one Gate; GeoInterpretationFromEmpty requires GeoInterpretationOnly'
}

if ($TestServerAddress -notmatch '^(?:\d{1,3}\.){3}\d{1,3}$') {
    throw 'TestServerAddress must be an IPv4 address'
}
if (-not (Test-Path -LiteralPath $SshKeyPath -PathType Leaf)) {
    throw 'SshKeyPath does not identify a file'
}

$suffix = [guid]::NewGuid().ToString('N').Substring(0, 10)
$gateDatabase = "zhixiaohe_gate_video_$suffix"
$migrationRole = "gate_migration_$suffix"
$runtimeRole = "gate_runtime_$suffix"
if ($gateDatabase -notmatch '^zhixiaohe_gate_video_[a-f0-9]{10}$' -or
        $migrationRole -ne "gate_migration_$suffix" -or $runtimeRole -ne "gate_runtime_$suffix") {
    throw 'Generated Gate database did not pass the isolation prefix guard'
}

$migrationPassword = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$runtimePassword = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$localPort = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
$listener.Stop()
$sshArgs = @(
    '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-i', $SshKeyPath,
    "root@$TestServerAddress"
)
$tunnel = $null
$gateExit = 1

try {
    $createSql = @"
CREATE ROLE "$migrationRole" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '$migrationPassword';
CREATE ROLE "$runtimeRole" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '$runtimePassword';
CREATE DATABASE "$gateDatabase" OWNER "$migrationRole";
REVOKE ALL ON DATABASE "$gateDatabase" FROM PUBLIC;
GRANT CONNECT ON DATABASE "$gateDatabase" TO "$migrationRole", "$runtimeRole";
"@
    $null = $createSql | & ssh @sshArgs 'sudo -u postgres psql -X -v ON_ERROR_STOP=1 -d postgres' 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to create isolated PostgreSQL Gate resources'
    }

    $tunnel = Start-Process -FilePath 'ssh.exe' -ArgumentList @(
        '-N', '-o', 'BatchMode=yes', '-o', 'ExitOnForwardFailure=yes',
        '-o', 'StrictHostKeyChecking=yes', '-i', $SshKeyPath,
        '-L', "127.0.0.1:${localPort}:127.0.0.1:5432", "root@$TestServerAddress"
    ) -WindowStyle Hidden -PassThru
    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        try {
            $client = [System.Net.Sockets.TcpClient]::new('127.0.0.1', $localPort)
            $client.Dispose()
            $ready = $true
            break
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
    if (-not $ready) {
        throw 'Temporary PostgreSQL Gate tunnel did not become ready'
    }

    $gateUrl = "jdbc:postgresql://127.0.0.1:$localPort/$gateDatabase`?sslmode=require"
    $env:DATABASE_GATE_ENABLED = 'true'
    $env:GATE_EXPECTED_DATABASE = $gateDatabase
    $env:GATE_MIGRATION_DB_URL = $gateUrl
    $env:GATE_MIGRATION_DB_USERNAME = $migrationRole
    $env:GATE_MIGRATION_DB_PASSWORD = $migrationPassword
    $env:GATE_RUNTIME_DB_URL = $gateUrl
    $env:GATE_RUNTIME_DB_USERNAME = $runtimeRole
    $env:GATE_RUNTIME_DB_PASSWORD = $runtimePassword
    $env:GATE_GEO_INTERPRETATION_FROM_EMPTY = if ($GeoInterpretationFromEmpty) { 'true' } else { 'false' }
    $gateTest = if ($MediaSubmissionOnly) {
        '-Dtest=MediaSubmissionDatabaseGateIntegrationTests'
    } elseif ($GeoDiagnosticDeliveryOnly) {
        '-Dtest=GeoDiagnosticDeliveryDatabaseGateIntegrationTests'
    } elseif ($GeoDiagnosticOnly) {
        '-Dtest=GeoDiagnosticDatabaseGateIntegrationTests'
    } elseif ($ArticleFullOnly) {
        '-Dtest=ArticleFullDatabaseGateIntegrationTests'
    } elseif ($MonitorExecutionOnly) {
        '-Dtest=MonitorExecutionDatabaseGateIntegrationTests'
    } elseif ($SettlementAccountingOnly) {
        '-Dtest=DatabaseGateIntegrationTests#settlementAccountingUsesLockedAccountDayAndIdempotentReplay'
    } elseif ($AgentPlatformOnly) {
        '-Dtest=AgentPlatformDatabaseGateIntegrationTests'
    } elseif ($ConversationResourcesOnly) {
        '-Dtest=RunResourceDatabaseGateIntegrationTests'
    } elseif ($AssetMediaFormatOnly) {
        '-Dtest=AssetMediaFormatMigrationGateIntegrationTests'
    } elseif ($UsernameProvisioningOnly) {
        '-Dtest=AccountProvisioningDatabaseGateIntegrationTests'
    } elseif ($ChatRevisionOnly) {
        '-Dtest=DatabaseGateIntegrationTests#chatDraftRevisionSavepointRollsBackWhileRunFailureCommits'
    } elseif ($GeoInterpretationOnly) {
        '-Dtest=DatabaseGateIntegrationTests#geoInterpretationRecoveryUsesBoundedOwnedLeasesWithoutCharging'
    } elseif ($GeoCompetitiveOnly) {
        '-Dtest=DatabaseGateIntegrationTests#geoCompetitiveDiscoveryUsesOwnedDurableEvidenceAndLeases'
    } elseif ($GeoTrendOnly) {
        '-Dtest=DatabaseGateIntegrationTests#geoCollectionMethodMigrationPreservesHistoricalEvidence'
    } else {
        '-Dtest=DatabaseGateIntegrationTests'
    }
    & .\mvnw.cmd -q $gateTest test
    $gateExit = $LASTEXITCODE
} finally {
    Remove-Item Env:DATABASE_GATE_ENABLED, Env:GATE_EXPECTED_DATABASE,
        Env:GATE_MIGRATION_DB_URL, Env:GATE_MIGRATION_DB_USERNAME,
        Env:GATE_MIGRATION_DB_PASSWORD, Env:GATE_RUNTIME_DB_URL,
        Env:GATE_RUNTIME_DB_USERNAME, Env:GATE_RUNTIME_DB_PASSWORD,
        Env:GATE_GEO_INTERPRETATION_FROM_EMPTY -ErrorAction SilentlyContinue
    if ($null -ne $tunnel -and -not $tunnel.HasExited) {
        Stop-Process -Id $tunnel.Id -Force -ErrorAction SilentlyContinue
    }

    $cleanupSql = @"
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname = '$gateDatabase' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS "$gateDatabase";
DROP ROLE IF EXISTS "$runtimeRole";
DROP ROLE IF EXISTS "$migrationRole";
"@
    $null = $cleanupSql | & ssh @sshArgs 'sudo -u postgres psql -X -v ON_ERROR_STOP=1 -d postgres' 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Output 'DB_GATE cleanup=FAILED'
        $gateExit = 1
    } else {
        Write-Output 'DB_GATE cleanup=temporary_database_and_roles_deleted'
    }
}

exit $gateExit
