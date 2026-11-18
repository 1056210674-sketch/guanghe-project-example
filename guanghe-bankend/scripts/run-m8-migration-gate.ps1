<#
.SYNOPSIS
Runs the M8 migration Gate against throwaway PostgreSQL databases on the test server.

.DESCRIPTION
Creates three databases, all named with the zhixiaohe_gate_ prefix so the isolation guard in the
tests accepts them:

  * zhixiaohe_gate_m8_empty_*    migrates from nothing to the current version
  * zhixiaohe_gate_m8_upgrade_*  holds data at V027 and upgrades through V028 to V032
  * zhixiaohe_gate_m8_runtime_*  the pre-existing database Gate (least privilege and write paths)

It never connects to the shared test database or to production: the only databases it names are the
three it just created, and it drops them again in the finally block, printing what it removed. The
databases are reached through a temporary SSH tunnel bound to loopback, so no database port is
opened to the network.

The Maven output is kept under target/m8-migration-gate/ as the run's evidence.
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$TestServerAddress,
    [Parameter(Mandatory = $true)]
    [string]$SshKeyPath
)

$ErrorActionPreference = 'Stop'

if ($TestServerAddress -notmatch '^(?:\d{1,3}\.){3}\d{1,3}$') {
    throw 'TestServerAddress must be an IPv4 address'
}
if (-not (Test-Path -LiteralPath $SshKeyPath -PathType Leaf)) {
    throw 'SshKeyPath does not identify a file'
}

$suffix = [guid]::NewGuid().ToString('N').Substring(0, 10)
$emptyDatabase = "zhixiaohe_gate_m8_empty_$suffix"
$upgradeDatabase = "zhixiaohe_gate_m8_upgrade_$suffix"
$runtimeDatabase = "zhixiaohe_gate_m8_runtime_$suffix"
$migrationRole = "gate_m8_migration_$suffix"
$runtimeRole = "gate_m8_runtime_$suffix"
foreach ($database in @($emptyDatabase, $upgradeDatabase, $runtimeDatabase)) {
    if ($database -notmatch '^zhixiaohe_gate_m8_[a-z0-9_]+$') {
        throw 'Generated Gate database did not pass the isolation prefix guard'
    }
}

$evidenceDirectory = Join-Path $PSScriptRoot '..\target\m8-migration-gate'
$null = New-Item -ItemType Directory -Path $evidenceDirectory -Force
$evidenceFile = Join-Path $evidenceDirectory "migration-gate-$suffix.log"

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

# Native tools write progress, notices and warnings to stderr. Under $ErrorActionPreference = 'Stop'
# a `2>&1` pipeline turns the first such line into a statement-terminating NativeCommandError: the
# statement is abandoned at that point and the enclosing block jumps to `finally`. Every native call
# in this script therefore runs with the preference relaxed, so a psql NOTICE cannot abort the run —
# and, worse, cannot abort the cleanup that drops the databases this script created.
function Invoke-NativeWithStderr($command, [string[]]$commandArgs, [string]$standardInput) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($null -eq $standardInput) {
            & $command @commandArgs 2>&1
        } else {
            $standardInput | & $command @commandArgs 2>&1
        }
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Invoke-RemoteSql([string]$sql) {
    Invoke-NativeWithStderr 'ssh' ($sshArgs + 'sudo -u postgres psql -X -v ON_ERROR_STOP=1 -d postgres') $sql
}

# The read-back after the drop is what makes "the databases are gone" a verified claim rather than a
# command that was issued. Error text is therefore kept in the result instead of being filtered away:
# a psql failure has to look like leftover output so the cleanup reports as failed, not as clean.
function Invoke-RemoteQuery([string]$sql) {
    @(Invoke-NativeWithStderr 'ssh' ($sshArgs + 'sudo -u postgres psql -X -tA -v ON_ERROR_STOP=1 -d postgres') $sql) |
        ForEach-Object {
            if ($_ -is [System.Management.Automation.ErrorRecord]) { "stderr: $($_.ToString())" } else { "$_" }
        } |
        Where-Object { $_.Trim() -ne '' }
}

try {
    $createSql = @"
CREATE ROLE "$migrationRole" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '$migrationPassword';
CREATE ROLE "$runtimeRole" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '$runtimePassword';
CREATE DATABASE "$emptyDatabase" OWNER "$migrationRole";
CREATE DATABASE "$upgradeDatabase" OWNER "$migrationRole";
CREATE DATABASE "$runtimeDatabase" OWNER "$migrationRole";
REVOKE ALL ON DATABASE "$emptyDatabase" FROM PUBLIC;
REVOKE ALL ON DATABASE "$upgradeDatabase" FROM PUBLIC;
REVOKE ALL ON DATABASE "$runtimeDatabase" FROM PUBLIC;
GRANT CONNECT ON DATABASE "$emptyDatabase" TO "$migrationRole";
GRANT CONNECT ON DATABASE "$upgradeDatabase" TO "$migrationRole";
GRANT CONNECT ON DATABASE "$runtimeDatabase" TO "$migrationRole", "$runtimeRole";
"@
    $created = Invoke-RemoteSql $createSql
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create isolated PostgreSQL Gate resources: $created"
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

    $emptyUrl = "jdbc:postgresql://127.0.0.1:$localPort/$emptyDatabase`?sslmode=require"
    $upgradeUrl = "jdbc:postgresql://127.0.0.1:$localPort/$upgradeDatabase`?sslmode=require"
    $runtimeUrl = "jdbc:postgresql://127.0.0.1:$localPort/$runtimeDatabase`?sslmode=require"

    $env:DATABASE_GATE_ENABLED = 'true'
    # The pre-existing database Gate keeps its own database and its two roles.
    $env:GATE_EXPECTED_DATABASE = $runtimeDatabase
    $env:GATE_MIGRATION_DB_URL = $runtimeUrl
    $env:GATE_MIGRATION_DB_USERNAME = $migrationRole
    $env:GATE_MIGRATION_DB_PASSWORD = $migrationPassword
    $env:GATE_RUNTIME_DB_URL = $runtimeUrl
    $env:GATE_RUNTIME_DB_USERNAME = $runtimeRole
    $env:GATE_RUNTIME_DB_PASSWORD = $runtimePassword
    # The M8 migration Gate: an empty database and one that upgrades from V027.
    $env:GATE_EMPTY_EXPECTED_DATABASE = $emptyDatabase
    $env:GATE_EMPTY_DB_URL = $emptyUrl
    $env:GATE_EMPTY_DB_USERNAME = $migrationRole
    $env:GATE_EMPTY_DB_PASSWORD = $migrationPassword
    $env:GATE_UPGRADE_EXPECTED_DATABASE = $upgradeDatabase
    $env:GATE_UPGRADE_DB_URL = $upgradeUrl
    $env:GATE_UPGRADE_DB_USERNAME = $migrationRole
    $env:GATE_UPGRADE_DB_PASSWORD = $migrationPassword

    "M8_MIGRATION_GATE databases=$emptyDatabase,$upgradeDatabase,$runtimeDatabase" |
        Tee-Object -FilePath $evidenceFile
    # Maven runs with its two streams sent to files rather than through a `2>&1` pipeline. The forked
    # test JVM writes a Mockito self-attach warning to stderr on every run; merged into the pipeline
    # that warning became a statement-terminating NativeCommandError, so `$LASTEXITCODE` below was
    # never assigned and the run reported a failure exit code for a suite that had passed.
    $mavenStdout = Join-Path $evidenceDirectory "maven-stdout-$suffix.log"
    $mavenStderr = Join-Path $evidenceDirectory "maven-stderr-$suffix.log"
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & .\mvnw.cmd -q test "-Dtest=DatabaseGateIntegrationTests,CommercialMigrationGateIntegrationTests" `
            > $mavenStdout 2> $mavenStderr
        $gateExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    Get-Content -LiteralPath $mavenStdout | Tee-Object -FilePath $evidenceFile -Append
    Get-Content -LiteralPath $mavenStderr | Tee-Object -FilePath $evidenceFile -Append
    "M8_MIGRATION_GATE maven_exit=$gateExit" | Tee-Object -FilePath $evidenceFile -Append
} finally {
    Remove-Item Env:DATABASE_GATE_ENABLED, Env:GATE_EXPECTED_DATABASE,
        Env:GATE_MIGRATION_DB_URL, Env:GATE_MIGRATION_DB_USERNAME,
        Env:GATE_MIGRATION_DB_PASSWORD, Env:GATE_RUNTIME_DB_URL,
        Env:GATE_RUNTIME_DB_USERNAME, Env:GATE_RUNTIME_DB_PASSWORD,
        Env:GATE_EMPTY_EXPECTED_DATABASE, Env:GATE_EMPTY_DB_URL,
        Env:GATE_EMPTY_DB_USERNAME, Env:GATE_EMPTY_DB_PASSWORD,
        Env:GATE_UPGRADE_EXPECTED_DATABASE, Env:GATE_UPGRADE_DB_URL,
        Env:GATE_UPGRADE_DB_USERNAME, Env:GATE_UPGRADE_DB_PASSWORD -ErrorAction SilentlyContinue
    if ($null -ne $tunnel -and -not $tunnel.HasExited) {
        Stop-Process -Id $tunnel.Id -Force -ErrorAction SilentlyContinue
    }

    $cleanupSql = @"
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname IN ('$emptyDatabase', '$upgradeDatabase', '$runtimeDatabase')
  AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS "$emptyDatabase";
DROP DATABASE IF EXISTS "$upgradeDatabase";
DROP DATABASE IF EXISTS "$runtimeDatabase";
DROP ROLE IF EXISTS "$runtimeRole";
DROP ROLE IF EXISTS "$migrationRole";
"@
    $cleanup = Invoke-RemoteSql $cleanupSql
    $dropFailed = $LASTEXITCODE -ne 0
    # Read back with tuples-only output, so "the query returned nothing" is distinguishable from
    # "the query returned a header". A name appearing here means the drop did not take effect.
    $leftoverDatabases = (Invoke-RemoteQuery @"
SELECT datname FROM pg_database
WHERE datname IN ('$emptyDatabase', '$upgradeDatabase', '$runtimeDatabase');
"@) -join ' '
    $leftoverRoles = (Invoke-RemoteQuery @"
SELECT rolname FROM pg_roles WHERE rolname IN ('$runtimeRole', '$migrationRole');
"@) -join ' '
    if ($dropFailed -or $leftoverDatabases -or $leftoverRoles) {
        "M8_MIGRATION_GATE cleanup=FAILED drop_failed=$dropFailed leftover_databases=$leftoverDatabases leftover_roles=$leftoverRoles" |
            Tee-Object -FilePath $evidenceFile -Append
        $gateExit = 1
    } else {
        "M8_MIGRATION_GATE cleanup=deleted databases=$emptyDatabase,$upgradeDatabase,$runtimeDatabase roles=$migrationRole,$runtimeRole" |
            Tee-Object -FilePath $evidenceFile -Append
    }
    if ($gateExit -eq 0) {
        "M8_MIGRATION_GATE result=PASSED evidence=$evidenceFile exit=$gateExit" |
            Tee-Object -FilePath $evidenceFile -Append
    } else {
        "M8_MIGRATION_GATE result=FAILED evidence=$evidenceFile exit=$gateExit" |
            Tee-Object -FilePath $evidenceFile -Append
    }
}

exit $gateExit
