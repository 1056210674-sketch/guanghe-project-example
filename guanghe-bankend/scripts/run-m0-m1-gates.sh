#!/usr/bin/env bash

# Runs every JUnit Gate guarded by @EnabledIfEnvironmentVariable as a deliberate,
# zero-skip M0/M1 evidence run. Invoke this script only with a dedicated test
# environment. It never prints environment values, credentials, tokens, URLs, or
# Maven output. Surefire/Failsafe XML is the source of the emitted aggregate counts.
# The successful run directory is finalized read-only with a SHA256SUMS manifest.
# Usage: bash scripts/run-m0-m1-gates.sh

set +x
set -Eeuo pipefail
umask 077

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly REPOSITORY_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
readonly MAVEN_WRAPPER="${REPOSITORY_ROOT}/mvnw"
readonly EXPECTED_DATABASE="zhixiaohe_test"
readonly AGENT_SOURCE_MANIFEST="${REPOSITORY_ROOT}/src/main/resources/contracts/openapi/agent-openapi-source.json"
readonly -a MAVEN_COMMAND=(bash "${MAVEN_WRAPPER}")
readonly DEFAULT_SUREFIRE_DIRECTORY="${REPOSITORY_ROOT}/target/surefire-reports"
readonly DEFAULT_FAILSAFE_DIRECTORY="${REPOSITORY_ROOT}/target/failsafe-reports"
readonly EXPECTED_SUREFIRE_REPORTS=13
readonly EXPECTED_FAILSAFE_REPORTS=1
readonly EXPECTED_TOTAL_SUITES=14
readonly EXPECTED_TOTAL_TESTS=39
readonly EXPECTED_HMAC_TESTS=12
readonly EXPECTED_CONTRACT_TESTS=11
readonly JAVA_GATE_SYSTEMD_UNIT="zhixiaohe-java-test.service"
readonly PYTHON_GATE_SYSTEMD_UNIT="zhixiaohe-agent-test.service"
TEMPORARY_LOGS=()
TEMPORARY_EVIDENCE_DIRECTORY=""

cleanup_temporary_logs() {
    local protected_log
    for protected_log in "${TEMPORARY_LOGS[@]}"; do
        if [[ -n "${protected_log}" && -f "${protected_log}" ]]; then
            rm -f -- "${protected_log}"
        fi
    done
}

cleanup_temporary_evidence() {
    [[ -n "${TEMPORARY_EVIDENCE_DIRECTORY}" ]] || return
    if [[ -z "${EVIDENCE_ROOT:-}" ]]; then
        printf '[gate] ERROR: temporary evidence exists before its root was validated\n' >&2
        return
    fi
    case "${TEMPORARY_EVIDENCE_DIRECTORY}" in
        "${EVIDENCE_ROOT:-}"/*/.incomplete-*)
            if [[ -d "${TEMPORARY_EVIDENCE_DIRECTORY}" ]]; then
                chmod -R u+w -- "${TEMPORARY_EVIDENCE_DIRECTORY}"
                rm -rf -- "${TEMPORARY_EVIDENCE_DIRECTORY}"
            fi
            ;;
        *)
            printf '[gate] ERROR: refusing to remove an unexpected temporary evidence path\n' >&2
            ;;
    esac
}

cleanup() {
    cleanup_temporary_logs
    cleanup_temporary_evidence
}

trap cleanup EXIT

fail() {
    printf '[gate] ERROR: %s\n' "$1" >&2
    exit 1
}

require_commands() {
    local command_name
    for command_name in "$@"; do
        command -v "${command_name}" >/dev/null 2>&1 \
            || fail "required command is unavailable: ${command_name}"
    done
}

require_environment() {
    local missing=()
    local variable_name
    for variable_name in "$@"; do
        if [[ -z "${!variable_name:-}" ]]; then
            missing+=("${variable_name}")
        fi
    done
    if (( ${#missing[@]} > 0 )); then
        printf '[gate] ERROR: required environment variables are missing:\n' >&2
        printf '  - %s\n' "${missing[@]}" >&2
        exit 1
    fi
}

require_exact_value() {
    local variable_name="$1"
    local expected="$2"
    if [[ "${!variable_name}" != "${expected}" ]]; then
        fail "${variable_name} must equal the audited M0 test value"
    fi
}

assert_no_inherited_maven_test_controls() {
    [[ -z "${MAVEN_ARGS:-}" ]] \
        || fail 'MAVEN_ARGS must be unset for an exact Gate run'

    local source_name
    local source_value
    local skipping_pattern='(-D|--define[=[:space:]])(maven[.]test[.]skip|skipTests|skipITs|failsafe[.]skip|surefire[.]skip)(=|[[:space:]]|$)'
    local selection_pattern='(-D|--define[=[:space:]])(test|it[.]test|groups|excludedGroups|includeJUnit5Engines|excludeJUnit5Engines|junit[.]jupiter[.]tags|surefire[.](includes|excludes|includesFile|excludesFile|suiteXmlFiles)|failsafe[.](includes|excludes|includesFile|excludesFile|suiteXmlFiles))(=|[[:space:]]|$)'
    local rerun_pattern='(-D|--define[=[:space:]])((surefire|failsafe)[.])?rerunFailingTestsCount(=|[[:space:]]|$)'
    for source_name in MAVEN_OPTS JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS; do
        source_value="${!source_name:-}"
        if [[ "${source_value}" =~ ${skipping_pattern} ]]; then
            fail "${source_name} contains a test-skipping option"
        fi
        if [[ "${source_value}" =~ ${selection_pattern} ]]; then
            fail "${source_name} contains a test-selection option"
        fi
        if [[ "${source_value}" =~ ${rerun_pattern} ]]; then
            fail "${source_name} contains a test-rerun option"
        fi
    done

    local config_file
    for config_file in \
        "${REPOSITORY_ROOT}/.mvn/maven.config" \
        "${REPOSITORY_ROOT}/.mvn/jvm.config"; do
        [[ -f "${config_file}" ]] || continue
        source_value="$(<"${config_file}")"
        if [[ "${source_value}" =~ ${skipping_pattern} \
            || "${source_value}" =~ ${selection_pattern} \
            || "${source_value}" =~ ${rerun_pattern} ]]; then
            fail 'tracked Maven configuration contains a forbidden test control'
        fi
    done
    local pom_scan_status=0
    grep -R -E -q \
        --include='pom.xml' \
        --exclude-dir=.git \
        --exclude-dir=target \
        'rerunFailingTestsCount' \
        "${REPOSITORY_ROOT}" || pom_scan_status=$?
    if (( pom_scan_status == 0 )); then
        fail 'a tracked Maven POM enables or references test reruns'
    elif (( pom_scan_status != 1 )); then
        fail 'unable to audit Maven POMs for test-rerun configuration'
    fi
}

require_jdk_21() {
    require_environment JAVA_HOME
    [[ -x "${JAVA_HOME}/bin/java" ]] || fail 'JAVA_HOME/bin/java is not executable'
    [[ -x "${JAVA_HOME}/bin/javac" ]] || fail 'JAVA_HOME/bin/javac is not executable'

    local java_version
    local javac_version
    java_version="$("${JAVA_HOME}/bin/java" -version 2>&1)" \
        || fail 'java -version failed'
    javac_version="$("${JAVA_HOME}/bin/javac" -version 2>&1)" \
        || fail 'javac -version failed'
    grep -Eq 'version "21([."]|$)' <<<"${java_version}" \
        || fail 'JAVA_HOME does not point to a Java 21 runtime'
    grep -Eq '^javac 21([.]|$)' <<<"${javac_version}" \
        || fail 'JAVA_HOME does not contain a Java 21 compiler'

    export PATH="${JAVA_HOME}/bin:${PATH:-}"
    local maven_version
    maven_version="$("${MAVEN_COMMAND[@]}" --version 2>&1)" \
        || fail 'Maven Wrapper --version failed'
    grep -Eq '^Java version: 21([., ]|$)' <<<"${maven_version}" \
        || fail 'Maven Wrapper is not running with Java 21'
    printf '[gate] JDK 21 and Maven Wrapper preflight passed\n'
}

assert_all_conditional_gates_are_routed() {
    python3 - "${REPOSITORY_ROOT}/src/test/java" <<'PY'
from pathlib import Path
import re
import sys

expected = {
    ("DATABASE_GATE_ENABLED", "com.guanghe.backend.database.DatabaseGateIntegrationTests"),
    ("FORMAL_AUTH_GATE_ENABLED", "com.guanghe.backend.auth.web.FormalTestDatabaseAuthIntegrationTests"),
    ("FORMAL_TEST_DB_INIT_ENABLED", "com.guanghe.backend.database.FormalTestDatabaseInitializationIntegrationTests"),
    ("POSTGRES_CONNECTION_GATE_ENABLED", "com.guanghe.backend.database.DocumentedPostgresqlConnectionGateIntegrationTests"),
    ("PUBLIC_TEST_DB_GATE_ENABLED", "com.guanghe.backend.database.PublicTestDatabaseDevelopmentModeIntegrationTests"),
    ("RABBIT_GATE_ENABLED", "com.guanghe.backend.infrastructure.RabbitMqConnectionGateIntegrationTests"),
    ("REMOTE_DEV_GATE_ENABLED", "com.guanghe.backend.infrastructure.RemoteDevelopmentInfrastructureIntegrationTests"),
    ("REMOTE_FULL_AUTH_GATE_ENABLED", "com.guanghe.backend.auth.web.RemoteFullAuthenticationGateIntegrationTests"),
    ("REMOTE_INTERNAL_HMAC_GATE_ENABLED", "com.guanghe.backend.infrastructure.RemoteInternalHmacNonceIntegrationTests"),
    ("REMOTE_JAVA_M1_GATE_ENABLED", "com.guanghe.backend.integration.internal.RemoteJavaM1SpringContextIntegrationTests"),
    ("REMOTE_REDIS_GATE_ENABLED", "com.guanghe.backend.infrastructure.RemoteRedisLoginRateLimitIntegrationTests"),
    ("TEST_ACCOUNT_INIT_ENABLED", "com.guanghe.backend.database.FixedTestAccountInitializationIntegrationTests"),
}
actual = set()
for source in Path(sys.argv[1]).rglob("*.java"):
    text = source.read_text()
    flags = re.findall(
        r'EnabledIfEnvironmentVariable\s*\(\s*named\s*=\s*"([A-Z0-9_]+)"',
        text,
    )
    if not flags:
        continue
    package = re.search(r'(?m)^package\s+([\w.]+);', text)
    class_name = re.search(r'(?m)^(?:public\s+)?class\s+(\w+)', text)
    if package is None or class_name is None:
        raise SystemExit("[gate] ERROR: unable to identify a conditional Gate class")
    fqcn = package.group(1) + "." + class_name.group(1)
    actual.update((flag, fqcn) for flag in flags)

if actual != expected:
    raise SystemExit(
        "[gate] ERROR: the conditional Gate flag/class inventory changed; update this runner"
    )
print(f"[gate] conditional Gate inventory verified: {len(expected)} flag/class pairs")
PY
}

unset_gate_flags() {
    unset DATABASE_GATE_ENABLED
    unset FORMAL_AUTH_GATE_ENABLED
    unset FORMAL_TEST_DB_INIT_ENABLED
    unset POSTGRES_CONNECTION_GATE_ENABLED
    unset PUBLIC_TEST_DB_GATE_ENABLED
    unset RABBIT_GATE_ENABLED
    unset REMOTE_DEV_GATE_ENABLED
    unset REMOTE_FULL_AUTH_GATE_ENABLED
    unset REMOTE_INTERNAL_HMAC_GATE_ENABLED
    unset REMOTE_JAVA_M1_GATE_ENABLED
    unset REMOTE_REDIS_GATE_ENABLED
    unset TEST_ACCOUNT_INIT_ENABLED
    unset M1_TRACE_EVIDENCE_FILE
}

map_gate_environment() {
    # Canonical service and migration variables are required once, then mapped to
    # the narrowly named variables consumed by the existing Gate tests.
    require_environment \
        APP_DB_URL APP_DB_USERNAME APP_DB_PASSWORD \
        FLYWAY_DB_URL FLYWAY_DB_USERNAME FLYWAY_DB_PASSWORD \
        GATE_EXPECTED_DATABASE \
        GATE_MIGRATION_DB_URL GATE_MIGRATION_DB_USERNAME GATE_MIGRATION_DB_PASSWORD \
        GATE_RUNTIME_DB_URL GATE_RUNTIME_DB_USERNAME GATE_RUNTIME_DB_PASSWORD \
        REDIS_HOST REDIS_PORT REDIS_USERNAME REDIS_PASSWORD REDIS_SSL_ENABLED REDIS_KEY_PREFIX \
        RABBITMQ_HOST RABBITMQ_PORT RABBITMQ_VHOST RABBITMQ_USERNAME RABBITMQ_PASSWORD \
        RABBITMQ_SSL_ENABLED JWT_SECRET_BASE64 \
        PYTHON_CONTRACT_ROOT EXPECTED_AGENT_COMMIT GATE_EVIDENCE_ROOT \
        INTERNAL_HMAC_KEY_ID INTERNAL_HMAC_SECRET \
        AGENT_BASE_URL AGENT_REDIS_FAILURE_BASE_URL \
        REMOTE_FULL_AUTH_GATE_USERNAME REMOTE_FULL_AUTH_GATE_PASSWORD \
        REMOTE_FULL_AUTH_GATE_EXPECTED_ROLE

    require_exact_value REDIS_SSL_ENABLED true
    require_exact_value RABBITMQ_SSL_ENABLED true
    require_exact_value RABBITMQ_VHOST /zhixiaohe-test
    [[ "${REDIS_KEY_PREFIX}" == zhixiaohe:test:* && "${REDIS_KEY_PREFIX}" == *: ]] \
        || fail 'REDIS_KEY_PREFIX must stay inside the zhixiaohe:test: namespace and end with a colon'
    [[ "${GATE_EXPECTED_DATABASE}" =~ ^zhixiaohe_gate_[a-z0-9_]+$ ]] \
        || fail 'GATE_EXPECTED_DATABASE must be a dedicated zhixiaohe_gate_* database'
    [[ "${REMOTE_FULL_AUTH_GATE_EXPECTED_ROLE}" == ADMIN \
        || "${REMOTE_FULL_AUTH_GATE_EXPECTED_ROLE}" == USER ]] \
        || fail 'REMOTE_FULL_AUTH_GATE_EXPECTED_ROLE must be ADMIN or USER'
    [[ -d "${PYTHON_CONTRACT_ROOT}" ]] \
        || fail 'PYTHON_CONTRACT_ROOT must be an existing checked-out Python contracts directory'
    [[ "${EXPECTED_AGENT_COMMIT}" =~ ^[0-9a-f]{40}$ ]] \
        || fail 'EXPECTED_AGENT_COMMIT must be an explicit lowercase 40-character Git commit'
    [[ "${GATE_EVIDENCE_ROOT}" == /* && "${GATE_EVIDENCE_ROOT}" != / ]] \
        || fail 'GATE_EVIDENCE_ROOT must be an existing absolute directory other than /'
    [[ -d "${GATE_EVIDENCE_ROOT}" ]] \
        || fail 'GATE_EVIDENCE_ROOT must already exist'
    [[ "${AGENT_BASE_URL}" =~ ^http://(127[.]0[.]0[.]1|\[::1\]):[0-9]+/?$ ]] \
        || fail 'AGENT_BASE_URL must target the loopback-only Python Agent API'
    [[ "${AGENT_REDIS_FAILURE_BASE_URL}" =~ ^http://(127[.]0[.]0[.]1|\[::1\]):[0-9]+/?$ ]] \
        || fail 'AGENT_REDIS_FAILURE_BASE_URL must target a loopback-only isolated Python failure instance'
    [[ "${AGENT_REDIS_FAILURE_BASE_URL%/}" != "${AGENT_BASE_URL%/}" ]] \
        || fail 'AGENT_REDIS_FAILURE_BASE_URL must differ from AGENT_BASE_URL'

    export FORMAL_TEST_EXPECTED_DATABASE="${EXPECTED_DATABASE}"
    export FORMAL_TEST_MIGRATION_DB_URL="${FLYWAY_DB_URL}"
    export FORMAL_TEST_MIGRATION_DB_USERNAME="${FLYWAY_DB_USERNAME}"
    export FORMAL_TEST_MIGRATION_DB_PASSWORD="${FLYWAY_DB_PASSWORD}"
    export FORMAL_TEST_RUNTIME_DB_URL="${APP_DB_URL}"
    export FORMAL_TEST_RUNTIME_DB_USERNAME="${APP_DB_USERNAME}"
    export FORMAL_TEST_RUNTIME_DB_PASSWORD="${APP_DB_PASSWORD}"

    export TEST_ACCOUNT_EXPECTED_DATABASE="${EXPECTED_DATABASE}"
    export TEST_ACCOUNT_DB_URL="${FLYWAY_DB_URL}"
    export TEST_ACCOUNT_DB_USERNAME="${FLYWAY_DB_USERNAME}"
    export TEST_ACCOUNT_DB_PASSWORD="${FLYWAY_DB_PASSWORD}"

    export POSTGRES_GATE_EXPECTED_DATABASE="${EXPECTED_DATABASE}"
    export POSTGRES_GATE_DB_URL="${APP_DB_URL}"
    export POSTGRES_GATE_DB_USERNAME="${APP_DB_USERNAME}"
    export POSTGRES_GATE_DB_PASSWORD="${APP_DB_PASSWORD}"
    export FORMAL_AUTH_EXPECTED_DATABASE="${EXPECTED_DATABASE}"
    export REMOTE_FULL_AUTH_EXPECTED_DATABASE="${EXPECTED_DATABASE}"

    export REDIS_GATE_HOST="${REDIS_HOST}"
    export REDIS_GATE_PORT="${REDIS_PORT}"
    export REDIS_GATE_USERNAME="${REDIS_USERNAME}"
    export REDIS_GATE_PASSWORD="${REDIS_PASSWORD}"
    export REDIS_GATE_SSL_ENABLED="${REDIS_SSL_ENABLED}"
    export REDIS_GATE_KEY_PREFIX="${REDIS_KEY_PREFIX}"

    export RABBIT_GATE_HOST="${RABBITMQ_HOST}"
    export RABBIT_GATE_PORT="${RABBITMQ_PORT}"
    export RABBIT_GATE_VHOST="${RABBITMQ_VHOST}"
    export RABBIT_GATE_USERNAME="${RABBITMQ_USERNAME}"
    export RABBIT_GATE_PASSWORD="${RABBITMQ_PASSWORD}"
    export RABBIT_GATE_SSL_ENABLED="${RABBITMQ_SSL_ENABLED}"

    # All Spring runtime phases must use the DML identity and keep Flyway off.
    export SPRING_FLYWAY_ENABLED=false
}

run_maven_without_console_output() {
    local phase="$1"
    shift
    local protected_log
    protected_log="$(mktemp)"
    TEMPORARY_LOGS+=("${protected_log}")
    chmod 600 "${protected_log}"
    if ! "${MAVEN_COMMAND[@]}" "$@" >"${protected_log}" 2>&1; then
        rm -f -- "${protected_log}"
        fail "Maven failed in phase ${phase}; console output was suppressed to prevent credential or endpoint disclosure"
    fi
    rm -f -- "${protected_log}"
}

verify_surefire_reports() {
    local phase="$1"
    local report_directory="$2"
    local expected_tests="$3"
    local expected_suites="$4"
    python3 - "${phase}" "${report_directory}" "${expected_tests}" "${expected_suites}" <<'PY'
from pathlib import Path
from decimal import Decimal, InvalidOperation
import sys
import xml.etree.ElementTree as ET

phase = sys.argv[1]
report_directory = Path(sys.argv[2])
expected_tests = int(sys.argv[3])
expected_suites = sorted(sys.argv[4].split(","))
reports = sorted(report_directory.glob("TEST-*.xml"))
if not reports:
    raise SystemExit(f"[gate] ERROR: phase {phase} produced no Surefire XML")

totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
actual_suites = []

def assert_no_rerun_or_flaky_metadata(suite):
    for node in suite.iter():
        node_name = node.tag.rsplit("}", 1)[-1].lower()
        if node_name in {"flakyfailure", "flakyerror", "rerunfailure", "rerunerror"}:
            raise SystemExit(f"[gate] ERROR: phase {phase} contains a retry or flaky result")
        for attribute_name, raw_value in node.attrib.items():
            normalized_name = attribute_name.rsplit("}", 1)[-1].lower()
            if not any(marker in normalized_name for marker in ("flake", "flaky", "rerun")):
                continue
            normalized_value = raw_value.strip().lower()
            if normalized_value in {"", "false"}:
                continue
            try:
                is_non_zero = Decimal(normalized_value) != 0
            except InvalidOperation:
                is_non_zero = True
            if is_non_zero:
                raise SystemExit(f"[gate] ERROR: phase {phase} contains non-zero retry/flaky metadata")

for report in reports:
    try:
        root = ET.parse(report).getroot()
    except (ET.ParseError, OSError) as exc:
        raise SystemExit(f"[gate] ERROR: phase {phase} produced invalid Surefire XML") from exc
    suites = [root] if root.tag.rsplit("}", 1)[-1] == "testsuite" else [
        child for child in root
        if child.tag.rsplit("}", 1)[-1] == "testsuite"
    ]
    if not suites:
        raise SystemExit(f"[gate] ERROR: phase {phase} report has no testsuite")
    for suite in suites:
        actual_suites.append(suite.attrib.get("name", ""))
        for name in totals:
            try:
                totals[name] += int(suite.attrib.get(name, "0"))
            except ValueError as exc:
                raise SystemExit(f"[gate] ERROR: phase {phase} report has invalid counters") from exc
        assert_no_rerun_or_flaky_metadata(suite)

if sorted(actual_suites) != expected_suites:
    raise SystemExit(f"[gate] ERROR: phase {phase} did not execute the exact expected test suites")
if totals["tests"] != expected_tests:
    raise SystemExit(
        f"[gate] ERROR: phase {phase} expected {expected_tests} tests but found {totals['tests']}"
    )
if totals["failures"] or totals["errors"] or totals["skipped"]:
    raise SystemExit(
        "[gate] ERROR: phase " + phase
        + f" tests={totals['tests']} failures={totals['failures']}"
        + f" errors={totals['errors']} skipped={totals['skipped']}"
    )
print(
    "[gate] " + phase
    + f" reports={len(reports)} tests={totals['tests']}"
    + f" failures={totals['failures']} errors={totals['errors']}"
    + f" skipped={totals['skipped']} flaky=0 reruns=0"
)
PY
}

reset_default_report_directory() {
    local report_directory="$1"
    case "${report_directory}" in
        "${DEFAULT_SUREFIRE_DIRECTORY}"|"${DEFAULT_FAILSAFE_DIRECTORY}") ;;
        *) fail 'refusing to reset an unexpected Maven report directory' ;;
    esac
    mkdir -p -- "${report_directory}"
    rm -f -- "${report_directory}"/TEST-*.xml "${report_directory}"/*.txt
}

copy_report_evidence() {
    local source_directory="$1"
    local evidence_directory="$2"
    case "${evidence_directory}" in
        "${REPORT_ROOT}"/*) ;;
        *) fail 'refusing to copy reports outside the Gate evidence directory' ;;
    esac
    mkdir -p -- "${evidence_directory}"
    local reports=("${source_directory}"/TEST-*.xml)
    [[ -f "${reports[0]}" ]] || fail 'no Maven XML reports are available to preserve'
    cp -- "${reports[@]}" "${evidence_directory}/"
}

run_gate_phase() {
    local phase="$1"
    local expected_tests="$2"
    local test_selector="$3"
    local report_directory="${REPORT_ROOT}/${phase}"
    mkdir -p -- "${report_directory}"
    printf '[gate] starting phase: %s\n' "${phase}"
    reset_default_report_directory "${DEFAULT_SUREFIRE_DIRECTORY}"
    run_maven_without_console_output "${phase}" \
        -Dtest="${test_selector}" \
        test
    verify_surefire_reports \
        "${phase}" "${DEFAULT_SUREFIRE_DIRECTORY}" "${expected_tests}" "${test_selector}"
    copy_report_evidence "${DEFAULT_SUREFIRE_DIRECTORY}" "${report_directory}"
}

verify_contract_trace_evidence() {
    local trace_input="$1"
    local java_journal
    local python_journal
    java_journal="$(mktemp)"
    python_journal="$(mktemp)"
    TEMPORARY_LOGS+=("${java_journal}" "${python_journal}")
    chmod 600 "${java_journal}" "${python_journal}"

    if ! journalctl \
        --unit "${JAVA_GATE_SYSTEMD_UNIT}" \
        --lines 2000 \
        --no-pager \
        --output cat \
        >"${java_journal}" 2>&1; then
        fail 'unable to read the bounded Java journal for trace evidence'
    fi
    if ! journalctl \
        --unit "${PYTHON_GATE_SYSTEMD_UNIT}" \
        --lines 2000 \
        --no-pager \
        --output cat \
        >"${python_journal}" 2>&1; then
        fail 'unable to read the bounded Python journal for trace evidence'
    fi

    python3 - \
        "${trace_input}" \
        "${java_journal}" \
        "${python_journal}" \
        >"${REPORT_ROOT}/TRACE-EVIDENCE.txt" <<'PY'
from pathlib import Path
import os
import re
import sys

input_path = Path(sys.argv[1])
if input_path.stat().st_mode & 0o777 != 0o600:
    raise SystemExit("[gate] ERROR: trace evidence input permissions are not 0600")
java_log = Path(sys.argv[2]).read_text(encoding="utf-8", errors="replace")
python_log = Path(sys.argv[3]).read_text(encoding="utf-8", errors="replace")
properties = {}
for line in input_path.read_text(encoding="utf-8").splitlines():
    if not line or "=" not in line:
        raise SystemExit("[gate] ERROR: trace evidence input has an invalid line")
    key, value = line.split("=", 1)
    if key in properties:
        raise SystemExit("[gate] ERROR: trace evidence input contains a duplicate key")
    properties[key] = value

if set(properties) != {
    "traceId",
    "javaOutboundSpanId",
    "javaInboundSpanId",
    "probeBodyMarker",
    "signedRawJsonBodyMarker",
    "signedRawJsonSignature",
    "capabilitiesSignature",
    "bodyTamperBodyMarker",
    "bodyTamperSignature",
    "queryTamperSignature",
    "replaySignature",
    "expiredTimestampSignature",
    "expiredFreshSignature",
    "uppercaseNonceSignature",
    "isolatedRedisFailureSignature",
    "javaInboundBodyMarker",
    "javaInboundSignature",
    "crossTraceSignature",
}:
    raise SystemExit("[gate] ERROR: trace evidence input has an unexpected key inventory")
trace_id = properties["traceId"]
outbound_span_id = properties["javaOutboundSpanId"]
expected_inbound_span_id = properties["javaInboundSpanId"]
body_marker = properties["probeBodyMarker"]
if not re.fullmatch(r"[0-9a-f]{32}", trace_id) or int(trace_id, 16) == 0:
    raise SystemExit("[gate] ERROR: trace evidence contains an invalid trace ID")
if not re.fullmatch(r"[0-9a-f]{16}", outbound_span_id) or int(outbound_span_id, 16) == 0:
    raise SystemExit("[gate] ERROR: trace evidence contains an invalid outbound span ID")
if not re.fullmatch(r"[0-9a-f]{16}", expected_inbound_span_id) \
        or int(expected_inbound_span_id, 16) == 0:
    raise SystemExit("[gate] ERROR: trace evidence contains an invalid inbound span ID")
if not re.fullmatch(r"trace-log-[0-9a-f]{12}", body_marker):
    raise SystemExit("[gate] ERROR: trace evidence contains an invalid body marker")
marker_patterns = {
    "signedRawJsonBodyMarker": r"raw-post-[0-9a-f]{12}",
    "bodyTamperBodyMarker": r"body-tamper-[0-9a-f]{12}",
    "javaInboundBodyMarker": r"java-inbound-[0-9a-f]{12}",
}
for key, pattern in marker_patterns.items():
    if re.fullmatch(pattern, properties[key]) is None:
        raise SystemExit("[gate] ERROR: trace evidence contains an invalid private body marker")
signature_keys = {
    "signedRawJsonSignature",
    "capabilitiesSignature",
    "bodyTamperSignature",
    "queryTamperSignature",
    "replaySignature",
    "expiredTimestampSignature",
    "expiredFreshSignature",
    "uppercaseNonceSignature",
    "isolatedRedisFailureSignature",
    "javaInboundSignature",
    "crossTraceSignature",
}
for key in signature_keys:
    if re.fullmatch(r"[A-Za-z0-9+/]{43}=", properties[key]) is None:
        raise SystemExit("[gate] ERROR: trace evidence contains an invalid private signature canary")

if trace_id not in java_log or trace_id not in python_log:
    raise SystemExit("[gate] ERROR: the trace ID is not correlated in both service journals")
python_lines = python_log.splitlines()
if not any(trace_id in line and "internal_request_authenticated" in line for line in python_lines):
    raise SystemExit("[gate] ERROR: the Python journal lacks authenticated trace evidence")
if not any(trace_id in line and "java_reverse_call_completed" in line for line in python_lines):
    raise SystemExit("[gate] ERROR: the Python journal lacks reverse-call trace evidence")
span_match = re.search(
    r"internal_reverse_probe_completed[^\n]*traceId="
    + re.escape(trace_id)
    + r"[^\n]*parentSpanId=([0-9a-f]{16})",
    java_log,
)
if span_match is None:
    raise SystemExit("[gate] ERROR: the Java journal has no inbound span evidence")
inbound_span_id = span_match.group(1)
if inbound_span_id != expected_inbound_span_id:
    raise SystemExit("[gate] ERROR: Java journal span evidence differs from the live test evidence")
if int(inbound_span_id, 16) == 0 or inbound_span_id == outbound_span_id:
    raise SystemExit("[gate] ERROR: the cross-service span ID did not change")

combined_logs = java_log + "\n" + python_log
secret = os.environ.get("INTERNAL_HMAC_SECRET", "")
if not secret or secret in combined_logs:
    raise SystemExit("[gate] ERROR: service journal secret redaction failed")
for key in {"probeBodyMarker", *marker_patterns}:
    if properties[key] in combined_logs:
        raise SystemExit("[gate] ERROR: service journal request-body redaction failed")
for key in signature_keys:
    if properties[key] in combined_logs:
        raise SystemExit("[gate] ERROR: service journal signature canary redaction failed")
if re.search(r'''(?is)\{\s*["']probe["']\s*:\s*["'][^"'\r\n]*["']\s*\}''', combined_logs):
    raise SystemExit("[gate] ERROR: a full probe body appeared in a service journal")
if re.search(r'''(?i)["']?(?:x-internal-)?signature["']?\s*[:=]\s*["']?[^\s,;}]+''', combined_logs):
    raise SystemExit("[gate] ERROR: service journal signature redaction failed")
print("traceEvidenceVersion=1")
print(f"traceId={trace_id}")
print(f"javaOutboundSpanId={outbound_span_id}")
print(f"javaInboundSpanId={inbound_span_id}")
print("sameTrace=true")
print("spanChanged=true")
print("javaJournalCorrelated=true")
print("pythonJournalCorrelated=true")
print("secretRedaction=PASS")
print("signatureRedaction=PASS")
print("requestBodyRedaction=PASS")
PY

    rm -f -- "${trace_input}" "${java_journal}" "${python_journal}"
}

run_contract_gate_phase() {
    local report_directory="${REPORT_ROOT}/m1-contract/failsafe"
    local trace_input="${REPORT_ROOT}/.trace-evidence-input"
    mkdir -p -- "${report_directory}"
    printf '[gate] starting phase: m1-contract\n'
    [[ ! -e "${trace_input}" ]] || fail 'refusing to reuse stale trace evidence input'
    TEMPORARY_LOGS+=("${trace_input}")
    export M1_TRACE_EVIDENCE_FILE="${trace_input}"
    reset_default_report_directory "${DEFAULT_FAILSAFE_DIRECTORY}"
    run_maven_without_console_output m1-contract \
        -Pm1-contract-remote-gate \
        -Dit.test=com.guanghe.backend.integration.contract.PythonContractRemoteGateIT \
        test-compile failsafe:integration-test failsafe:verify
    verify_surefire_reports \
        m1-contract "${DEFAULT_FAILSAFE_DIRECTORY}" "${EXPECTED_CONTRACT_TESTS}" \
        com.guanghe.backend.integration.contract.PythonContractRemoteGateIT
    copy_report_evidence "${DEFAULT_FAILSAFE_DIRECTORY}" "${report_directory}"
    [[ -f "${trace_input}" ]] \
        || fail 'the M1 Contract Gate did not produce trace evidence input'
    verify_contract_trace_evidence "${trace_input}"
    unset M1_TRACE_EVIDENCE_FILE
    printf '[gate] m1-contract trace correlation and journal redaction passed\n'
}

verify_all_reports() {
    python3 - \
        "${REPORT_ROOT}" \
        "${EXPECTED_SUREFIRE_REPORTS}" \
        "${EXPECTED_FAILSAFE_REPORTS}" \
        "${EXPECTED_TOTAL_SUITES}" \
        "${EXPECTED_TOTAL_TESTS}" <<'PY'
from pathlib import Path
from decimal import Decimal, InvalidOperation
import sys
import xml.etree.ElementTree as ET

root_directory = Path(sys.argv[1])
expected_surefire_reports = int(sys.argv[2])
expected_failsafe_reports = int(sys.argv[3])
expected_total_suites = int(sys.argv[4])
expected_total_tests = int(sys.argv[5])
reports = sorted(root_directory.rglob("TEST-*.xml"))
totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
suite_names = []

def assert_no_rerun_or_flaky_metadata(suite):
    for node in suite.iter():
        node_name = node.tag.rsplit("}", 1)[-1].lower()
        if node_name in {"flakyfailure", "flakyerror", "rerunfailure", "rerunerror"}:
            raise SystemExit("[gate] ERROR: aggregate evidence contains a retry or flaky result")
        for attribute_name, raw_value in node.attrib.items():
            normalized_name = attribute_name.rsplit("}", 1)[-1].lower()
            if not any(marker in normalized_name for marker in ("flake", "flaky", "rerun")):
                continue
            normalized_value = raw_value.strip().lower()
            if normalized_value in {"", "false"}:
                continue
            try:
                is_non_zero = Decimal(normalized_value) != 0
            except InvalidOperation:
                is_non_zero = True
            if is_non_zero:
                raise SystemExit("[gate] ERROR: aggregate evidence has non-zero retry/flaky metadata")

for report in reports:
    root = ET.parse(report).getroot()
    suites = [root] if root.tag.rsplit("}", 1)[-1] == "testsuite" else [
        child for child in root
        if child.tag.rsplit("}", 1)[-1] == "testsuite"
    ]
    for suite in suites:
        suite_names.append(suite.attrib.get("name", ""))
        for name in totals:
            totals[name] += int(suite.attrib.get(name, "0"))
        assert_no_rerun_or_flaky_metadata(suite)

expected_suites = {
    "com.guanghe.backend.database.DatabaseGateIntegrationTests",
    "com.guanghe.backend.database.FormalTestDatabaseInitializationIntegrationTests",
    "com.guanghe.backend.database.FixedTestAccountInitializationIntegrationTests",
    "com.guanghe.backend.database.PublicTestDatabaseDevelopmentModeIntegrationTests",
    "com.guanghe.backend.database.DocumentedPostgresqlConnectionGateIntegrationTests",
    "com.guanghe.backend.infrastructure.RemoteRedisLoginRateLimitIntegrationTests",
    "com.guanghe.backend.infrastructure.RemoteInternalHmacNonceIntegrationTests",
    "com.guanghe.backend.infrastructure.RabbitMqConnectionGateIntegrationTests",
    "com.guanghe.backend.infrastructure.RemoteDevelopmentInfrastructureIntegrationTests",
    "com.guanghe.backend.auth.web.FormalTestDatabaseAuthIntegrationTests",
    "com.guanghe.backend.auth.web.RemoteFullAuthenticationGateIntegrationTests",
    "com.guanghe.backend.integration.internal.RemoteJavaM1SpringContextIntegrationTests",
    "com.guanghe.backend.integration.internal.security.InternalHmacVerificationIntegrationTests",
    "com.guanghe.backend.integration.contract.PythonContractRemoteGateIT",
}

surefire_reports = [report for report in reports if "m1-contract" not in report.parts]
failsafe_reports = [report for report in reports if "m1-contract" in report.parts]
if (
    len(surefire_reports) != expected_surefire_reports
    or len(failsafe_reports) != expected_failsafe_reports
    or len(expected_suites) != expected_total_suites
    or set(suite_names) != expected_suites
    or len(suite_names) != expected_total_suites
    or totals != {
        "tests": expected_total_tests,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
    }
):
    raise SystemExit(
        "[gate] ERROR: aggregate Surefire/Failsafe evidence is incomplete: "
        + f"surefire_reports={len(surefire_reports)} failsafe_reports={len(failsafe_reports)} "
        + f"tests={totals['tests']} failures={totals['failures']} "
        + f"errors={totals['errors']} skipped={totals['skipped']}"
    )
print(
    f"[gate] COMPLETE surefire_reports={expected_surefire_reports} "
    f"failsafe_reports={expected_failsafe_reports} suites={expected_total_suites} "
    f"tests={expected_total_tests} "
    "failures=0 errors=0 skipped=0 flaky=0 reruns=0"
)
PY
}

assert_repository_state() {
    local repository="$1"
    local expected_commit="$2"
    local label="$3"
    [[ "$(git -C "${repository}" rev-parse HEAD)" == "${expected_commit}" ]] \
        || fail "${label} Git commit changed during the Gate run"
    [[ -z "$(git -C "${repository}" status --porcelain --untracked-files=normal)" ]] \
        || fail "${label} Git worktree is not clean"
}

validate_java_runtime_snapshot_inputs() {
    local expected_repository_commit="$1"
    local checkpoint="$2"
    local unit="$3"
    local systemd_properties_file="$4"
    local listener_file="$5"
    local transport_file="$6"
    local http_metadata_file="$7"
    local response_body_file="$8"
    local snapshot_file="$9"

    python3 - \
        "${expected_repository_commit}" \
        "${checkpoint}" \
        "${unit}" \
        "${systemd_properties_file}" \
        "${listener_file}" \
        "${transport_file}" \
        "${http_metadata_file}" \
        "${response_body_file}" \
        "${snapshot_file}" <<'PY'
import json
import os
from pathlib import Path
import re
import sys

(
    expected_repository_commit,
    checkpoint,
    unit,
    systemd_properties_path,
    listener_path,
    transport_path,
    http_metadata_path,
    response_body_path,
    snapshot_path,
) = sys.argv[1:]

if re.fullmatch(r"[0-9a-f]{40}", expected_repository_commit) is None:
    raise SystemExit("[gate] ERROR: runtime snapshot expected Commit is invalid")
if checkpoint not in {"pre", "post"}:
    raise SystemExit("[gate] ERROR: runtime snapshot checkpoint is invalid")
if unit != "zhixiaohe-java-test.service":
    raise SystemExit("[gate] ERROR: runtime snapshot systemd unit is not approved")


def read_exact_properties(path, expected_keys, label):
    properties = {}
    try:
        lines = Path(path).read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise SystemExit(f"[gate] ERROR: unable to read {label}") from exc
    for line in lines:
        if not line or "=" not in line:
            raise SystemExit(f"[gate] ERROR: {label} contains an invalid line")
        key, value = line.split("=", 1)
        if key in properties:
            raise SystemExit(f"[gate] ERROR: {label} contains a duplicate key")
        properties[key] = value
    if set(properties) != set(expected_keys):
        raise SystemExit(f"[gate] ERROR: {label} field inventory is incomplete")
    return properties


systemd = read_exact_properties(
    systemd_properties_path,
    {"ActiveState", "SubState", "UnitFileState", "MainPID"},
    "Java systemd properties",
)
if systemd["ActiveState"] != "active":
    raise SystemExit("[gate] ERROR: Java systemd unit is not active")
if systemd["SubState"] != "running":
    raise SystemExit("[gate] ERROR: Java systemd unit is not running")
if systemd["UnitFileState"] != "enabled":
    raise SystemExit("[gate] ERROR: Java systemd unit is not enabled")
if re.fullmatch(r"[1-9][0-9]*", systemd["MainPID"]) is None:
    raise SystemExit("[gate] ERROR: Java systemd MainPID is invalid")
main_pid = int(systemd["MainPID"])
if main_pid <= 1 or not Path(f"/proc/{main_pid}").is_dir():
    raise SystemExit("[gate] ERROR: Java systemd MainPID is not running")
try:
    os.kill(main_pid, 0)
except OSError as exc:
    raise SystemExit("[gate] ERROR: Java systemd MainPID is not signalable") from exc

try:
    listener_lines = [
        line
        for line in Path(listener_path).read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
except OSError as exc:
    raise SystemExit("[gate] ERROR: unable to read Java listener evidence") from exc
if len(listener_lines) != 1:
    raise SystemExit("[gate] ERROR: port 8080 must have exactly one TCP listener")
listener_tokens = listener_lines[0].split()
if len(listener_tokens) < 5 or listener_tokens[0] != "LISTEN":
    raise SystemExit("[gate] ERROR: port 8080 listener evidence is malformed")
allowed_loopback_endpoints = {
    "127.0.0.1:8080",
    "[::ffff:127.0.0.1]:8080",
}
if listener_tokens[3] not in allowed_loopback_endpoints:
    raise SystemExit("[gate] ERROR: port 8080 is not bound only to IPv4 loopback")
listener_pids = {
    int(value)
    for value in re.findall(r"(?:^|[,])pid=([0-9]+)(?:[,)]|$)", listener_lines[0])
}
if listener_pids != {main_pid}:
    raise SystemExit("[gate] ERROR: port 8080 listener is not owned by systemd MainPID")

transport = read_exact_properties(
    transport_path,
    {"transportExit"},
    "Java Actuator transport evidence",
)
if transport["transportExit"] != "0":
    raise SystemExit("[gate] ERROR: Java Actuator info is unreachable")
http_metadata = read_exact_properties(
    http_metadata_path,
    {"httpStatus", "httpVersion"},
    "Java Actuator HTTP evidence",
)
if http_metadata["httpStatus"] != "200":
    raise SystemExit("[gate] ERROR: Java Actuator info did not return HTTP 200")
if http_metadata["httpVersion"] != "1.1":
    raise SystemExit("[gate] ERROR: Java Actuator info did not use HTTP/1.1")


def reject_duplicate_json_keys(pairs):
    document = {}
    for key, value in pairs:
        if key in document:
            raise ValueError("duplicate JSON key")
        document[key] = value
    return document


try:
    info = json.loads(
        Path(response_body_path).read_text(encoding="utf-8"),
        object_pairs_hook=reject_duplicate_json_keys,
    )
except (OSError, UnicodeError, ValueError, json.JSONDecodeError) as exc:
    raise SystemExit("[gate] ERROR: Java Actuator info is not valid unique-key JSON") from exc
if not isinstance(info, dict):
    raise SystemExit("[gate] ERROR: Java Actuator info root is not an object")
required_info_keys = {
    "gitCommit",
    "codeCapabilitiesReady",
    "testServerDeployed",
    "m0GateStatus",
}
if not required_info_keys.issubset(info):
    raise SystemExit("[gate] ERROR: Java Actuator info is missing a required field")
runtime_commit = info["gitCommit"]
if not isinstance(runtime_commit, str) or re.fullmatch(r"[0-9a-f]{40}", runtime_commit) is None:
    raise SystemExit("[gate] ERROR: Java Actuator runtime Commit is invalid")
if runtime_commit != expected_repository_commit:
    raise SystemExit("[gate] ERROR: Java Actuator runtime Commit does not match repository HEAD")
if info["codeCapabilitiesReady"] is not True:
    raise SystemExit("[gate] ERROR: Java code capabilities are not ready")
if info["testServerDeployed"] is not True:
    raise SystemExit("[gate] ERROR: Java test server is not marked deployed")
if info["m0GateStatus"] != "PASS":
    raise SystemExit("[gate] ERROR: Java M0 Gate status is not PASS")

snapshot_lines = [
    "runtimeSnapshotVersion=1",
    f"checkpoint={checkpoint}",
    f"unit={unit}",
    f"ActiveState={systemd['ActiveState']}",
    f"SubState={systemd['SubState']}",
    f"UnitFileState={systemd['UnitFileState']}",
    f"mainPid={main_pid}",
    "mainPidValid=true",
    "listener=loopback-only:8080",
    "listenerOwnedByMainPid=true",
    "httpProtocol=HTTP/1.1",
    f"httpStatus={http_metadata['httpStatus']}",
    f"repositoryCommit={expected_repository_commit}",
    f"runtimeCommit={runtime_commit}",
    "commitMatch=true",
    "codeCapabilitiesReady=true",
    "testServerDeployed=true",
    "m0GateStatus=PASS",
]
Path(snapshot_path).write_text("\n".join(snapshot_lines) + "\n", encoding="utf-8")
PY
}

verify_java_runtime_validator_fail_closed() {
    local expected_commit="1111111111111111111111111111111111111111"
    local different_commit="2222222222222222222222222222222222222222"
    local fixture_pid="$$"
    local systemd_properties_file
    local listener_file
    local transport_file
    local http_metadata_file
    local response_body_file
    local snapshot_file
    local validation_error_file
    local scenarios=0
    systemd_properties_file="$(mktemp)"
    listener_file="$(mktemp)"
    transport_file="$(mktemp)"
    http_metadata_file="$(mktemp)"
    response_body_file="$(mktemp)"
    snapshot_file="$(mktemp)"
    validation_error_file="$(mktemp)"
    TEMPORARY_LOGS+=(
        "${systemd_properties_file}"
        "${listener_file}"
        "${transport_file}"
        "${http_metadata_file}"
        "${response_body_file}"
        "${snapshot_file}"
        "${validation_error_file}"
    )
    chmod 600 \
        "${systemd_properties_file}" \
        "${listener_file}" \
        "${transport_file}" \
        "${http_metadata_file}" \
        "${response_body_file}" \
        "${snapshot_file}" \
        "${validation_error_file}"

    write_valid_runtime_fixture() {
        printf 'ActiveState=active\nSubState=running\nUnitFileState=enabled\nMainPID=%s\n' \
            "${fixture_pid}" >"${systemd_properties_file}"
        printf 'LISTEN 0 128 127.0.0.1:8080 0.0.0.0:* users:(("fixture",pid=%s,fd=1))\n' \
            "${fixture_pid}" >"${listener_file}"
        printf 'transportExit=0\n' >"${transport_file}"
        printf 'httpStatus=200\nhttpVersion=1.1\n' >"${http_metadata_file}"
        printf '{"gitCommit":"%s","codeCapabilitiesReady":true,"testServerDeployed":true,"m0GateStatus":"PASS"}\n' \
            "${expected_commit}" >"${response_body_file}"
    }

    expect_runtime_fixture_failure() {
        local scenario="$1"
        rm -f -- "${snapshot_file}"
        if validate_java_runtime_snapshot_inputs \
            "${expected_commit}" pre "${JAVA_GATE_SYSTEMD_UNIT}" \
            "${systemd_properties_file}" "${listener_file}" \
            "${transport_file}" "${http_metadata_file}" \
            "${response_body_file}" "${snapshot_file}" \
            >"${validation_error_file}" 2>&1; then
            fail "Java runtime validator accepted unsafe fixture: ${scenario}"
        fi
        [[ ! -e "${snapshot_file}" ]] \
            || fail "Java runtime validator emitted evidence for unsafe fixture: ${scenario}"
        ((scenarios += 1))
    }

    write_valid_runtime_fixture
    validate_java_runtime_snapshot_inputs \
        "${expected_commit}" pre "${JAVA_GATE_SYSTEMD_UNIT}" \
        "${systemd_properties_file}" "${listener_file}" \
        "${transport_file}" "${http_metadata_file}" \
        "${response_body_file}" "${snapshot_file}" \
        >"${validation_error_file}" 2>&1 \
        || fail 'Java runtime validator rejected its valid control fixture'
    [[ -s "${snapshot_file}" ]] \
        || fail 'Java runtime validator did not emit its valid control snapshot'
    ((scenarios += 1))

    printf '{"gitCommit":"%s","codeCapabilitiesReady":true,"testServerDeployed":true,"m0GateStatus":"PASS"}\n' \
        "${different_commit}" >"${response_body_file}"
    expect_runtime_fixture_failure runtime-commit-mismatch

    write_valid_runtime_fixture
    printf 'transportExit=7\n' >"${transport_file}"
    expect_runtime_fixture_failure actuator-unreachable

    write_valid_runtime_fixture
    printf 'httpStatus=503\nhttpVersion=1.1\n' >"${http_metadata_file}"
    expect_runtime_fixture_failure actuator-non-200

    write_valid_runtime_fixture
    printf '{"codeCapabilitiesReady":true,"testServerDeployed":true,"m0GateStatus":"PASS"}\n' \
        >"${response_body_file}"
    expect_runtime_fixture_failure missing-git-commit

    write_valid_runtime_fixture
    printf '{"gitCommit":"%s","testServerDeployed":true,"m0GateStatus":"PASS"}\n' \
        "${expected_commit}" >"${response_body_file}"
    expect_runtime_fixture_failure missing-code-capabilities-ready

    write_valid_runtime_fixture
    printf '{"gitCommit":"%s","codeCapabilitiesReady":true,"m0GateStatus":"PASS"}\n' \
        "${expected_commit}" >"${response_body_file}"
    expect_runtime_fixture_failure missing-test-server-deployed

    write_valid_runtime_fixture
    printf '{"gitCommit":"%s","codeCapabilitiesReady":true,"testServerDeployed":true}\n' \
        "${expected_commit}" >"${response_body_file}"
    expect_runtime_fixture_failure missing-m0-gate-status

    write_valid_runtime_fixture
    printf 'ActiveState=inactive\nSubState=running\nUnitFileState=enabled\nMainPID=%s\n' \
        "${fixture_pid}" >"${systemd_properties_file}"
    expect_runtime_fixture_failure unit-inactive

    write_valid_runtime_fixture
    printf 'ActiveState=active\nSubState=dead\nUnitFileState=enabled\nMainPID=%s\n' \
        "${fixture_pid}" >"${systemd_properties_file}"
    expect_runtime_fixture_failure unit-not-running

    write_valid_runtime_fixture
    printf 'ActiveState=active\nSubState=running\nUnitFileState=disabled\nMainPID=%s\n' \
        "${fixture_pid}" >"${systemd_properties_file}"
    expect_runtime_fixture_failure unit-not-enabled

    write_valid_runtime_fixture
    printf 'ActiveState=active\nSubState=running\nUnitFileState=enabled\nMainPID=0\n' \
        >"${systemd_properties_file}"
    expect_runtime_fixture_failure invalid-main-pid

    write_valid_runtime_fixture
    printf 'ActiveState=active\nSubState=running\nUnitFileState=enabled\nMainPID=999999999\n' \
        >"${systemd_properties_file}"
    expect_runtime_fixture_failure non-running-main-pid

    write_valid_runtime_fixture
    : >"${listener_file}"
    expect_runtime_fixture_failure missing-listener

    write_valid_runtime_fixture
    printf 'LISTEN 0 128 0.0.0.0:8080 0.0.0.0:* users:(("fixture",pid=%s,fd=1))\n' \
        "${fixture_pid}" >"${listener_file}"
    expect_runtime_fixture_failure wildcard-listener

    write_valid_runtime_fixture
    printf 'LISTEN 0 128 127.0.0.1:8080 0.0.0.0:* users:(("fixture",pid=%s,fd=1))\n' \
        "$((fixture_pid + 1))" >"${listener_file}"
    expect_runtime_fixture_failure listener-pid-mismatch

    write_valid_runtime_fixture
    rm -f -- "${snapshot_file}"
    if validate_java_runtime_snapshot_inputs \
        "${expected_commit}" pre unexpected.service \
        "${systemd_properties_file}" "${listener_file}" \
        "${transport_file}" "${http_metadata_file}" \
        "${response_body_file}" "${snapshot_file}" \
        >"${validation_error_file}" 2>&1; then
        fail 'Java runtime validator accepted an unapproved systemd unit'
    fi
    [[ ! -e "${snapshot_file}" ]] \
        || fail 'Java runtime validator emitted evidence for an unapproved systemd unit'
    ((scenarios += 1))

    printf '[gate] Java runtime fail-closed validator fixtures passed: %s scenarios\n' \
        "${scenarios}"
}

capture_java_runtime_snapshot() {
    local expected_repository_commit="$1"
    local checkpoint="$2"
    local snapshot_file="$3"
    local repository_commit
    local systemd_properties_file
    local listener_file
    local transport_file
    local http_metadata_file
    local response_body_file
    local curl_error_file
    local curl_exit=0
    local snapshot_main_pid
    local stable_main_pid

    repository_commit="$(git -C "${REPOSITORY_ROOT}" rev-parse HEAD)" \
        || fail 'unable to resolve Java repository HEAD during runtime capture'
    [[ "${repository_commit}" == "${expected_repository_commit}" ]] \
        || fail 'Java repository Commit changed before runtime capture'

    systemd_properties_file="$(mktemp)"
    listener_file="$(mktemp)"
    transport_file="$(mktemp)"
    http_metadata_file="$(mktemp)"
    response_body_file="$(mktemp)"
    curl_error_file="$(mktemp)"
    TEMPORARY_LOGS+=(
        "${systemd_properties_file}"
        "${listener_file}"
        "${transport_file}"
        "${http_metadata_file}"
        "${response_body_file}"
        "${curl_error_file}"
        "${snapshot_file}"
    )
    chmod 600 \
        "${systemd_properties_file}" \
        "${listener_file}" \
        "${transport_file}" \
        "${http_metadata_file}" \
        "${response_body_file}" \
        "${curl_error_file}" \
        "${snapshot_file}"

    systemctl is-active --quiet "${JAVA_GATE_SYSTEMD_UNIT}" \
        || fail 'Java systemd unit is not active at runtime checkpoint'
    systemctl is-enabled --quiet "${JAVA_GATE_SYSTEMD_UNIT}" \
        || fail 'Java systemd unit is not enabled at runtime checkpoint'
    systemctl show \
        --no-pager \
        --property=ActiveState \
        --property=SubState \
        --property=UnitFileState \
        --property=MainPID \
        "${JAVA_GATE_SYSTEMD_UNIT}" \
        >"${systemd_properties_file}" 2>/dev/null \
        || fail 'unable to read Java systemd runtime properties'
    ss -HOnltp 'sport = :8080' >"${listener_file}" 2>/dev/null \
        || fail 'unable to inspect the Java TCP listener'
    curl -q \
        --silent \
        --show-error \
        --http1.1 \
        --proxy '' \
        --noproxy '*' \
        --proto '=http' \
        --connect-timeout 3 \
        --max-time 10 \
        --max-redirs 0 \
        --output "${response_body_file}" \
        --write-out 'httpStatus=%{http_code}\nhttpVersion=%{http_version}\n' \
        'http://127.0.0.1:8080/actuator/info' \
        >"${http_metadata_file}" 2>"${curl_error_file}" \
        || curl_exit=$?
    printf 'transportExit=%s\n' "${curl_exit}" >"${transport_file}"

    validate_java_runtime_snapshot_inputs \
        "${repository_commit}" "${checkpoint}" "${JAVA_GATE_SYSTEMD_UNIT}" \
        "${systemd_properties_file}" "${listener_file}" \
        "${transport_file}" "${http_metadata_file}" \
        "${response_body_file}" "${snapshot_file}" \
        || fail "Java runtime binding validation failed at ${checkpoint} checkpoint"
    chmod 600 "${snapshot_file}"

    snapshot_main_pid="$(python3 - "${snapshot_file}" <<'PY'
from pathlib import Path
import sys

values = {}
for line in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
    key, value = line.split("=", 1)
    values[key] = value
print(values["mainPid"])
PY
    )" || fail 'unable to read the validated Java MainPID snapshot'
    kill -0 "${snapshot_main_pid}" 2>/dev/null \
        || fail 'Java systemd MainPID stopped during runtime capture'
    stable_main_pid="$(systemctl show \
        --no-pager --property=MainPID --value "${JAVA_GATE_SYSTEMD_UNIT}")" \
        || fail 'unable to re-read Java systemd MainPID'
    [[ "${stable_main_pid}" == "${snapshot_main_pid}" ]] \
        || fail 'Java systemd MainPID changed during runtime capture'
    printf '[gate] Java runtime binding checkpoint passed: %s\n' "${checkpoint}"
}

build_java_runtime_evidence() {
    local pre_snapshot="$1"
    local post_snapshot="$2"
    local expected_repository_commit="$3"
    local evidence_file="$4"
    python3 - \
        "${pre_snapshot}" \
        "${post_snapshot}" \
        "${expected_repository_commit}" \
        "${evidence_file}" <<'PY'
from pathlib import Path
import re
import sys

pre_path, post_path, expected_repository_commit, evidence_path = sys.argv[1:]


def read_snapshot(path):
    properties = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line or "=" not in line:
            raise SystemExit("[gate] ERROR: Java runtime snapshot contains an invalid line")
        key, value = line.split("=", 1)
        if key in properties:
            raise SystemExit("[gate] ERROR: Java runtime snapshot contains a duplicate key")
        properties[key] = value
    expected_keys = {
        "runtimeSnapshotVersion",
        "checkpoint",
        "unit",
        "ActiveState",
        "SubState",
        "UnitFileState",
        "mainPid",
        "mainPidValid",
        "listener",
        "listenerOwnedByMainPid",
        "httpProtocol",
        "httpStatus",
        "repositoryCommit",
        "runtimeCommit",
        "commitMatch",
        "codeCapabilitiesReady",
        "testServerDeployed",
        "m0GateStatus",
    }
    if set(properties) != expected_keys:
        raise SystemExit("[gate] ERROR: Java runtime snapshot inventory is incomplete")
    return properties


if re.fullmatch(r"[0-9a-f]{40}", expected_repository_commit) is None:
    raise SystemExit("[gate] ERROR: Java runtime evidence expected Commit is invalid")
pre = read_snapshot(pre_path)
post = read_snapshot(post_path)
if pre["checkpoint"] != "pre" or post["checkpoint"] != "post":
    raise SystemExit("[gate] ERROR: Java runtime checkpoints are not ordered pre/post")
fixed_expected = {
    "runtimeSnapshotVersion": "1",
    "unit": "zhixiaohe-java-test.service",
    "ActiveState": "active",
    "SubState": "running",
    "UnitFileState": "enabled",
    "mainPidValid": "true",
    "listener": "loopback-only:8080",
    "listenerOwnedByMainPid": "true",
    "httpProtocol": "HTTP/1.1",
    "httpStatus": "200",
    "commitMatch": "true",
    "codeCapabilitiesReady": "true",
    "testServerDeployed": "true",
    "m0GateStatus": "PASS",
}
for key, expected_value in fixed_expected.items():
    if pre[key] != expected_value or post[key] != expected_value:
        raise SystemExit("[gate] ERROR: Java runtime checkpoint lost a required invariant")
if pre["repositoryCommit"] != expected_repository_commit \
        or post["repositoryCommit"] != expected_repository_commit:
    raise SystemExit("[gate] ERROR: Java repository Commit changed between checkpoints")
if pre["runtimeCommit"] != expected_repository_commit \
        or post["runtimeCommit"] != expected_repository_commit:
    raise SystemExit("[gate] ERROR: Java runtime Commit is not bound to repository HEAD")
if pre["repositoryCommit"] != post["repositoryCommit"]:
    raise SystemExit("[gate] ERROR: Java repository Commit changed during Gate")
if pre["runtimeCommit"] != post["runtimeCommit"]:
    raise SystemExit("[gate] ERROR: Java runtime Commit changed during Gate")
if pre["mainPid"] != post["mainPid"]:
    raise SystemExit("[gate] ERROR: Java systemd MainPID changed during Gate")

runtime_commit = pre["runtimeCommit"]
evidence_lines = [
    "javaRuntimeEvidenceVersion=1",
    f"unit={pre['unit']}",
    f"ActiveState={pre['ActiveState']}",
    f"SubState={pre['SubState']}",
    f"UnitFileState={pre['UnitFileState']}",
    "mainPidValid=true",
    f"listener={pre['listener']}",
    "listenerOwnedByMainPid=true",
    "httpProtocol=HTTP/1.1",
    "httpStatus=200",
    f"repositoryCommit={pre['repositoryCommit']}",
    f"runtimeCommit={runtime_commit}",
    "commitMatch=true",
    "codeCapabilitiesReady=true",
    "testServerDeployed=true",
    "m0GateStatus=PASS",
    "prePostRepositoryStable=true",
    "prePostRuntimeStable=true",
    "prePostPidStable=true",
    "runtimeValidatorSelfTests=PASS",
]
Path(evidence_path).write_text("\n".join(evidence_lines) + "\n", encoding="utf-8")
print(runtime_commit)
PY
}

finalize_evidence_bundle() {
    local java_commit="$1"
    local agent_commit="$2"
    local python_repository="$3"
    local pre_runtime_snapshot="$4"
    local post_runtime_snapshot
    local java_runtime_commit
    local completed_at
    local commit_evidence_directory
    local staging_directory
    completed_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    commit_evidence_directory="${EVIDENCE_ROOT}/${java_commit}"
    staging_directory="${commit_evidence_directory}/.incomplete-${RUN_ID}"

    if [[ -e "${commit_evidence_directory}" || -L "${commit_evidence_directory}" ]]; then
        [[ -d "${commit_evidence_directory}" && ! -L "${commit_evidence_directory}" ]] \
            || fail 'the Commit evidence parent must be a regular directory'
    else
        mkdir -- "${commit_evidence_directory}"
    fi
    [[ "$(cd -- "${commit_evidence_directory}" && pwd -P)" == "${commit_evidence_directory}" ]] \
        || fail 'the Commit evidence parent resolves outside its expected path'
    [[ ! -e "${EVIDENCE_DIRECTORY}" && ! -L "${EVIDENCE_DIRECTORY}" ]] \
        || fail 'refusing to overwrite an existing Gate evidence directory'
    [[ ! -e "${staging_directory}" && ! -L "${staging_directory}" ]] \
        || fail 'refusing to overwrite an existing temporary Gate evidence directory'
    mkdir -- "${staging_directory}"
    TEMPORARY_EVIDENCE_DIRECTORY="${staging_directory}"

    python3 - \
        "${REPORT_ROOT}" \
        "${staging_directory}" \
        "${EXPECTED_TOTAL_SUITES}" \
        "${EXPECTED_TOTAL_TESTS}" \
        "${agent_commit}" \
        "${EXPECTED_CONTRACT_TESTS}" <<'PY'
from decimal import Decimal, InvalidOperation
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

source_root = Path(sys.argv[1])
destination_root = Path(sys.argv[2]) / "sanitized-reports"
expected_suites = int(sys.argv[3])
expected_tests = int(sys.argv[4])
expected_agent_commit = sys.argv[5]
expected_contract_tests = int(sys.argv[6])
identifier = re.compile(r"^[A-Za-z_$][A-Za-z0-9_.$]*$")
method_name = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*(?:\([A-Za-z0-9_.$, ]*\))?$")
safe_path_part = re.compile(r"^[A-Za-z0-9_.-]+$")
reports = sorted(source_root.rglob("TEST-*.xml"))
suite_count = 0
test_count = 0
contract_report_count = 0
contract_evidence_lines = []

def local_name(tag):
    return tag.rsplit("}", 1)[-1]

def counter(suite, name):
    try:
        value = int(suite.attrib[name])
    except (KeyError, ValueError) as exc:
        raise SystemExit("[gate] ERROR: raw evidence has an invalid counter") from exc
    if value < 0:
        raise SystemExit("[gate] ERROR: raw evidence has a negative counter")
    return value

def duration(node):
    raw_value = node.attrib.get("time", "0")
    try:
        value = Decimal(raw_value)
    except InvalidOperation as exc:
        raise SystemExit("[gate] ERROR: raw evidence has an invalid duration") from exc
    if not value.is_finite() or value < 0:
        raise SystemExit("[gate] ERROR: raw evidence has an invalid duration")
    return raw_value

for report in reports:
    if report.is_symlink() or not report.is_file():
        raise SystemExit("[gate] ERROR: raw evidence report must be a regular file")
    relative_path = report.relative_to(source_root)
    if (
        not all(safe_path_part.fullmatch(part) for part in relative_path.parts)
        or not re.fullmatch(r"TEST-[A-Za-z0-9_.$-]+[.]xml", relative_path.name)
    ):
        raise SystemExit("[gate] ERROR: raw evidence has an unsafe report path")
    document = ET.parse(report).getroot()
    suites = [document] if local_name(document.tag) == "testsuite" else [
        child for child in document if local_name(child.tag) == "testsuite"
    ]
    if len(suites) != 1:
        raise SystemExit("[gate] ERROR: each raw report must contain exactly one suite")
    suite = suites[0]
    suite_name = suite.attrib.get("name", "")
    if identifier.fullmatch(suite_name) is None:
        raise SystemExit("[gate] ERROR: raw evidence has an unsafe suite name")
    counters = {name: counter(suite, name) for name in ("tests", "failures", "errors", "skipped")}
    if counters["failures"] or counters["errors"] or counters["skipped"]:
        raise SystemExit("[gate] ERROR: refusing to sanitize a non-passing report")
    if suite_name == "com.guanghe.backend.integration.contract.PythonContractRemoteGateIT":
        contract_report_count += 1
        if counters["tests"] != expected_contract_tests:
            raise SystemExit("[gate] ERROR: the Contract suite testcase count changed")
        for node in suite.iter():
            if local_name(node.tag) != "system-out" or node.text is None:
                continue
            contract_evidence_lines.extend(
                line.strip()
                for line in node.text.splitlines()
                if line.strip().startswith("M1_GATE_A ")
            )
    for node in suite.iter():
        for attribute_name, raw_value in node.attrib.items():
            normalized_name = attribute_name.rsplit("}", 1)[-1].lower()
            if not any(marker in normalized_name for marker in ("flake", "flaky", "rerun")):
                continue
            normalized_value = raw_value.strip().lower()
            if normalized_value in {"", "false"}:
                continue
            try:
                is_non_zero = Decimal(normalized_value) != 0
            except InvalidOperation:
                is_non_zero = True
            if is_non_zero:
                raise SystemExit("[gate] ERROR: refusing to sanitize retry/flaky metadata")
    forbidden_nodes = {
        "failure", "error", "skipped", "flakyFailure", "flakyError",
        "rerunFailure", "rerunError",
    }
    if any(local_name(node.tag) in forbidden_nodes for node in suite.iter()):
        raise SystemExit("[gate] ERROR: refusing to sanitize a report with retry/failure nodes")
    testcases = [node for node in suite.iter() if local_name(node.tag) == "testcase"]
    if len(testcases) != counters["tests"]:
        raise SystemExit("[gate] ERROR: raw evidence testcase count disagrees with its suite counter")

    sanitized_suite = ET.Element("testsuite", {
        "name": suite_name,
        "tests": str(counters["tests"]),
        "failures": "0",
        "errors": "0",
        "skipped": "0",
        "time": duration(suite),
    })
    for testcase in testcases:
        class_name = testcase.attrib.get("classname", "")
        name = testcase.attrib.get("name", "")
        if identifier.fullmatch(class_name) is None or method_name.fullmatch(name) is None:
            raise SystemExit("[gate] ERROR: raw evidence has an unsafe testcase identifier")
        ET.SubElement(sanitized_suite, "testcase", {
            "classname": class_name,
            "name": name,
            "time": duration(testcase),
        })

    destination = destination_root / relative_path
    destination.parent.mkdir(parents=True, exist_ok=True)
    ET.indent(sanitized_suite, space="  ")
    ET.ElementTree(sanitized_suite).write(
        destination,
        encoding="utf-8",
        xml_declaration=True,
        short_empty_elements=True,
    )
    suite_count += 1
    test_count += counters["tests"]

if suite_count != expected_suites or test_count != expected_tests:
    raise SystemExit("[gate] ERROR: sanitized evidence inventory is incomplete")
if contract_report_count != 1:
    raise SystemExit("[gate] ERROR: exactly one Contract Failsafe report is required")
if re.fullmatch(r"[0-9a-f]{40}", expected_agent_commit) is None:
    raise SystemExit("[gate] ERROR: expected Agent commit is invalid during evidence extraction")

evidence_order = [
    "python_repository",
    "signed_raw_json_post",
    "readiness",
    "capabilities",
    "body_tamper",
    "query_tamper",
    "nonce_replay",
    "expired_timestamp",
    "uppercase_nonce",
    "isolated_redis_failure",
    "cross_service_trace",
]
patterns = {
    "python_repository": re.compile(
        r"commit=" + re.escape(expected_agent_commit) + r" clean=true mirrors=exact"
    ),
    "signed_raw_json_post": re.compile(r"httpStatus=200 traceId=([0-9a-f]{32})"),
    "readiness": re.compile(r"java=200/UP python=200/READY"),
    "capabilities": re.compile(
        r"httpStatus=200 gitCommit=" + re.escape(expected_agent_commit) + r" schema=valid"
    ),
    "body_tamper": re.compile(
        r"httpStatus=401 code=INTERNAL_SIGNATURE_INVALID nonceUnclaimed=true"
    ),
    "query_tamper": re.compile(
        r"httpStatus=401 code=INTERNAL_SIGNATURE_INVALID nonceUnclaimed=true"
    ),
    "nonce_replay": re.compile(r"first=200 second=401 code=INTERNAL_REPLAY_DETECTED"),
    "expired_timestamp": re.compile(
        r"httpStatus=401 code=INTERNAL_SIGNATURE_INVALID nonceUnclaimed=true"
    ),
    "uppercase_nonce": re.compile(
        r"httpStatus=401 code=INTERNAL_SIGNATURE_INVALID nonceStoreBypassed=true"
    ),
    "isolated_redis_failure": re.compile(
        r"isolatedAgent=NOT_READY[(]INTERNAL_NONCE_STORE_UNAVAILABLE[)] "
        r"hmac=503/INTERNAL_AUTHENTICATION_UNAVAILABLE sharedAgent=READY"
    ),
    "cross_service_trace": re.compile(
        r"traceId=([0-9a-f]{32}) javaOutboundSpanId=([0-9a-f]{16}) "
        r"javaInboundSpanId=([0-9a-f]{16}) sameTrace=true childSpan=true "
        r"query=verified logs=java,python redaction=PASS"
    ),
}
policy_regression_cases = [
    (
        "isolated Redis failure accepts the approved dependency-unavailable status",
        "isolated_redis_failure",
        "isolatedAgent=NOT_READY(INTERNAL_NONCE_STORE_UNAVAILABLE) "
        "hmac=503/INTERNAL_AUTHENTICATION_UNAVAILABLE sharedAgent=READY",
        True,
    ),
    (
        "isolated Redis failure rejects an authentication-failure status",
        "isolated_redis_failure",
        "isolatedAgent=NOT_READY(INTERNAL_NONCE_STORE_UNAVAILABLE) "
        "hmac=401/INTERNAL_AUTHENTICATION_UNAVAILABLE sharedAgent=READY",
        False,
    ),
    (
        "body tamper remains unauthorized",
        "body_tamper",
        "httpStatus=401 code=INTERNAL_SIGNATURE_INVALID nonceUnclaimed=true",
        True,
    ),
    (
        "query tamper remains unauthorized",
        "query_tamper",
        "httpStatus=401 code=INTERNAL_SIGNATURE_INVALID nonceUnclaimed=true",
        True,
    ),
    (
        "expired timestamp remains unauthorized",
        "expired_timestamp",
        "httpStatus=401 code=INTERNAL_SIGNATURE_INVALID nonceUnclaimed=true",
        True,
    ),
    (
        "uppercase nonce remains unauthorized",
        "uppercase_nonce",
        "httpStatus=401 code=INTERNAL_SIGNATURE_INVALID nonceStoreBypassed=true",
        True,
    ),
    (
        "nonce replay remains unauthorized",
        "nonce_replay",
        "first=200 second=401 code=INTERNAL_REPLAY_DETECTED",
        True,
    ),
]
for description, key, value, expected_match in policy_regression_cases:
    actual_match = patterns[key].fullmatch(value) is not None
    if actual_match != expected_match:
        raise SystemExit("[gate] ERROR: Contract evidence policy regression: " + description)

evidence = {}
for line in contract_evidence_lines:
    match = re.fullmatch(r"M1_GATE_A ([a-z_]+) (.+)", line)
    if match is None or match.group(1) not in patterns:
        raise SystemExit("[gate] ERROR: Contract stdout contains an unknown evidence line")
    key, value = match.groups()
    if key in evidence:
        raise SystemExit("[gate] ERROR: Contract stdout contains duplicate evidence")
    value_match = patterns[key].fullmatch(value)
    if value_match is None:
        raise SystemExit("[gate] ERROR: Contract stdout evidence failed strict validation")
    evidence[key] = (value, value_match)
if set(evidence) != set(evidence_order) or len(contract_evidence_lines) != len(evidence_order):
    raise SystemExit("[gate] ERROR: Contract stdout evidence inventory is incomplete")

raw_post_trace = evidence["signed_raw_json_post"][1].group(1)
if int(raw_post_trace, 16) == 0:
    raise SystemExit("[gate] ERROR: signed POST evidence contains an all-zero trace ID")
cross_match = evidence["cross_service_trace"][1]
cross_trace, outbound_span, inbound_span = cross_match.groups()
if int(cross_trace, 16) == 0 or int(outbound_span, 16) == 0 or int(inbound_span, 16) == 0:
    raise SystemExit("[gate] ERROR: cross-service evidence contains an all-zero trace/span ID")
if outbound_span == inbound_span:
    raise SystemExit("[gate] ERROR: cross-service evidence did not change span ID")

trace_path = source_root / "TRACE-EVIDENCE.txt"
if trace_path.is_symlink() or not trace_path.is_file():
    raise SystemExit("[gate] ERROR: safe trace evidence is missing during Contract extraction")
trace_properties = {}
for line in trace_path.read_text(encoding="utf-8").splitlines():
    if "=" not in line:
        raise SystemExit("[gate] ERROR: safe trace evidence has an invalid line")
    key, value = line.split("=", 1)
    if key in trace_properties:
        raise SystemExit("[gate] ERROR: safe trace evidence contains a duplicate key")
    trace_properties[key] = value
expected_trace_properties = {
    "traceEvidenceVersion": "1",
    "traceId": cross_trace,
    "javaOutboundSpanId": outbound_span,
    "javaInboundSpanId": inbound_span,
    "sameTrace": "true",
    "spanChanged": "true",
    "javaJournalCorrelated": "true",
    "pythonJournalCorrelated": "true",
    "secretRedaction": "PASS",
    "signatureRedaction": "PASS",
    "requestBodyRedaction": "PASS",
}
if trace_properties != expected_trace_properties:
    raise SystemExit("[gate] ERROR: Contract trace evidence disagrees with journal verification")

normalized_evidence = [
    "contractEvidenceVersion=1",
    f"testcases={expected_contract_tests}",
]
normalized_evidence.extend(
    f"M1_GATE_A {key} {evidence[key][0]}"
    for key in evidence_order
)
(destination_root.parent / "M1-CONTRACT-EVIDENCE.txt").write_text(
    "\n".join(normalized_evidence) + "\n",
    encoding="utf-8",
)
PY

    [[ -f "${REPORT_ROOT}/TRACE-EVIDENCE.txt" \
        && ! -L "${REPORT_ROOT}/TRACE-EVIDENCE.txt" ]] \
        || fail 'the safe cross-service trace evidence is missing'
    cp -- "${REPORT_ROOT}/TRACE-EVIDENCE.txt" "${staging_directory}/TRACE-EVIDENCE.txt"

    assert_repository_state "${REPOSITORY_ROOT}" "${java_commit}" Java
    assert_repository_state "${python_repository}" "${agent_commit}" Python
    post_runtime_snapshot="$(mktemp)"
    capture_java_runtime_snapshot \
        "${java_commit}" post "${post_runtime_snapshot}"
    java_runtime_commit="$(build_java_runtime_evidence \
        "${pre_runtime_snapshot}" \
        "${post_runtime_snapshot}" \
        "${java_commit}" \
        "${REPORT_ROOT}/JAVA-RUNTIME-EVIDENCE.txt")" \
        || fail 'unable to build Java runtime binding evidence'
    chmod 600 "${REPORT_ROOT}/JAVA-RUNTIME-EVIDENCE.txt"
    [[ "${java_runtime_commit}" == "${java_commit}" ]] \
        || fail 'Java runtime evidence Commit is not bound to Gate repository Commit'
    assert_repository_state "${REPOSITORY_ROOT}" "${java_commit}" Java
    assert_repository_state "${python_repository}" "${agent_commit}" Python

    [[ -f "${REPORT_ROOT}/JAVA-RUNTIME-EVIDENCE.txt" \
        && ! -L "${REPORT_ROOT}/JAVA-RUNTIME-EVIDENCE.txt" ]] \
        || fail 'the Java runtime binding evidence is missing'
    python3 - \
        "${REPORT_ROOT}/JAVA-RUNTIME-EVIDENCE.txt" \
        "${java_commit}" \
        "${java_runtime_commit}" <<'PY'
from pathlib import Path
import re
import sys

evidence_path, expected_repository_commit, expected_runtime_commit = sys.argv[1:]
properties = {}
for line in Path(evidence_path).read_text(encoding="utf-8").splitlines():
    if not line or "=" not in line:
        raise SystemExit("[gate] ERROR: Java runtime evidence contains an invalid line")
    key, value = line.split("=", 1)
    if key in properties:
        raise SystemExit("[gate] ERROR: Java runtime evidence contains a duplicate key")
    properties[key] = value
expected_properties = {
    "javaRuntimeEvidenceVersion": "1",
    "unit": "zhixiaohe-java-test.service",
    "ActiveState": "active",
    "SubState": "running",
    "UnitFileState": "enabled",
    "mainPidValid": "true",
    "listener": "loopback-only:8080",
    "listenerOwnedByMainPid": "true",
    "httpProtocol": "HTTP/1.1",
    "httpStatus": "200",
    "repositoryCommit": expected_repository_commit,
    "runtimeCommit": expected_runtime_commit,
    "commitMatch": "true",
    "codeCapabilitiesReady": "true",
    "testServerDeployed": "true",
    "m0GateStatus": "PASS",
    "prePostRepositoryStable": "true",
    "prePostRuntimeStable": "true",
    "prePostPidStable": "true",
    "runtimeValidatorSelfTests": "PASS",
}
if properties != expected_properties:
    raise SystemExit("[gate] ERROR: Java runtime evidence inventory or values are invalid")
if re.fullmatch(r"[0-9a-f]{40}", expected_runtime_commit) is None:
    raise SystemExit("[gate] ERROR: Java runtime evidence Commit is invalid")
if expected_runtime_commit != expected_repository_commit:
    raise SystemExit("[gate] ERROR: Java runtime evidence is not bound to repository HEAD")
PY
    cp -- \
        "${REPORT_ROOT}/JAVA-RUNTIME-EVIDENCE.txt" \
        "${staging_directory}/JAVA-RUNTIME-EVIDENCE.txt"

    python3 - \
        "${staging_directory}" \
        "${EXPECTED_TOTAL_SUITES}" \
        "${EXPECTED_TOTAL_TESTS}" \
        "${java_commit}" \
        "${java_runtime_commit}" \
        "${agent_commit}" \
        >"${staging_directory}/MANIFEST.txt" <<'PY'
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

root_directory = Path(sys.argv[1])
expected_suites = int(sys.argv[2])
expected_tests = int(sys.argv[3])
java_repository_commit = sys.argv[4]
java_runtime_commit = sys.argv[5]
python_commit = sys.argv[6]
reports = sorted(root_directory.rglob("TEST-*.xml"))
entries = []
suite_count = 0
test_count = 0

for label, commit in {
    "Java repository": java_repository_commit,
    "Java runtime": java_runtime_commit,
    "Python": python_commit,
}.items():
    if re.fullmatch(r"[0-9a-f]{40}", commit) is None:
        raise SystemExit(f"[gate] ERROR: {label} Commit is invalid in evidence manifest")
if java_runtime_commit != java_repository_commit:
    raise SystemExit("[gate] ERROR: evidence manifest Java runtime is not bound to repository")

def local_name(tag):
    return tag.rsplit("}", 1)[-1]

def safe(value):
    return "".join(character if character >= " " else "?" for character in value)

for report in reports:
    document = ET.parse(report).getroot()
    suites = [document] if local_name(document.tag) == "testsuite" else [
        child for child in document if local_name(child.tag) == "testsuite"
    ]
    for suite in suites:
        suite_count += 1
        suite_name = safe(suite.attrib.get("name", ""))
        testcases = [node for node in suite.iter() if local_name(node.tag) == "testcase"]
        test_count += len(testcases)
        for testcase in testcases:
            class_name = safe(testcase.attrib.get("classname", suite_name))
            test_name = safe(testcase.attrib.get("name", ""))
            entries.append((class_name, test_name))

if suite_count != expected_suites or test_count != expected_tests:
    raise SystemExit("[gate] ERROR: unable to build an exact sanitized evidence manifest")

print("manifestVersion=1")
print(f"javaRepositoryCommit={java_repository_commit}")
print(f"javaRuntimeCommit={java_runtime_commit}")
print("javaRuntimeBound=true")
print("javaRuntimeEvidence=JAVA-RUNTIME-EVIDENCE.txt")
print(f"pythonCommit={python_commit}")
print(f"reports={len(reports)}")
print(f"suites={suite_count}")
print(f"testcases={test_count}")
for class_name, test_name in sorted(entries):
    print(f"testcase={class_name}#{test_name}")
PY

    cat >"${staging_directory}/GATE-SUMMARY.txt" <<EOF
evidenceFormatVersion=1
gate=M0_M1_GATE_A
result=PASS
reportFormat=sanitized-junit-xml-v1
rawMavenReportsIncluded=false
runId=${RUN_ID}
startedAt=${GATE_STARTED_AT}
completedAt=${completed_at}
javaCommit=${java_commit}
javaRepositoryCommit=${java_commit}
javaRuntimeCommit=${java_runtime_commit}
javaRuntimeBound=true
javaRuntimeStable=true
javaMainPidStable=true
javaRuntimeEvidence=PASS
pythonCommit=${agent_commit}
surefireReports=${EXPECTED_SUREFIRE_REPORTS}
failsafeReports=${EXPECTED_FAILSAFE_REPORTS}
suites=${EXPECTED_TOTAL_SUITES}
testcases=${EXPECTED_TOTAL_TESTS}
phaseTestcases=1/1/1/1/12/12/11
serverGateTestcases=${EXPECTED_TOTAL_TESTS}
contractGateTestcases=${EXPECTED_CONTRACT_TESTS}
failures=0
errors=0
skipped=0
flaky=0
reruns=0
contractEvidenceKeys=${EXPECTED_CONTRACT_TESTS}
contractEvidence=PASS
crossServiceTrace=PASS
journalRedaction=PASS
javaHeadUnchanged=true
javaWorktreeClean=true
pythonHeadUnchanged=true
pythonWorktreeClean=true
EOF

    (
        cd -- "${staging_directory}"
        find . -type f ! -name SHA256SUMS -print0 \
            | LC_ALL=C sort -z \
            | xargs -0 sha256sum >SHA256SUMS
    )
    [[ -s "${staging_directory}/SHA256SUMS" ]] \
        || fail 'the Gate evidence SHA256SUMS manifest is empty'
    (
        cd -- "${staging_directory}"
        sha256sum --check --strict SHA256SUMS >/dev/null
    ) || fail 'the Gate evidence SHA256SUMS verification failed'

    find "${staging_directory}" -type f -exec chmod 0400 {} +
    find "${staging_directory}" -type d -exec chmod 0500 {} +
    if find "${staging_directory}" -perm /222 -print -quit | grep -q .; then
        fail 'the finalized Gate evidence bundle is still writable'
    fi
    assert_repository_state "${REPOSITORY_ROOT}" "${java_commit}" Java
    assert_repository_state "${python_repository}" "${agent_commit}" Python
    mv -T -- "${staging_directory}" "${EVIDENCE_DIRECTORY}"
    TEMPORARY_EVIDENCE_DIRECTORY=""
}

main() {
    (( $# == 0 )) || fail 'the Gate runner accepts no command-line arguments'
    require_commands bash grep python3 mktemp chmod rm mkdir cp mv date git flock find sort xargs sha256sum cat journalctl systemctl ss curl kill
    [[ -f "${MAVEN_WRAPPER}" ]] || fail 'the Maven Wrapper is missing'
    [[ -d "${REPOSITORY_ROOT}/.git" ]] || fail 'the Gate runner requires a Git worktree'
    exec 9>"${REPOSITORY_ROOT}/.git/m0-m1-gate.lock"
    flock -n 9 || fail 'another M0/M1 Gate run is already active for this worktree'
    assert_no_inherited_maven_test_controls

    local gate_commit
    local repository_root_canonical
    local evidence_root_candidate
    local python_repository_root
    local python_contract_root_canonical
    local python_expected_contract_root
    local python_gate_commit
    local manifest_agent_commit
    local pre_runtime_snapshot
    gate_commit="$(git -C "${REPOSITORY_ROOT}" rev-parse HEAD)" \
        || fail 'unable to resolve the Gate Git commit'
    [[ "${gate_commit}" =~ ^[0-9a-f]{40}$ ]] \
        || fail 'the Java Gate Git commit is not a lowercase 40-character commit'
    require_jdk_21
    assert_all_conditional_gates_are_routed
    verify_java_runtime_validator_fail_closed
    unset_gate_flags
    map_gate_environment
    manifest_agent_commit="$(python3 - "${AGENT_SOURCE_MANIFEST}" <<'PY'
import json
from pathlib import Path
import sys

try:
    document = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    commit = document["gitCommit"]
except (OSError, KeyError, TypeError, json.JSONDecodeError) as exc:
    raise SystemExit(1) from exc
if not isinstance(commit, str):
    raise SystemExit(1)
print(commit)
PY
    )" || fail 'unable to read the Python commit from the Java contract source manifest'
    [[ "${manifest_agent_commit}" =~ ^[0-9a-f]{40}$ ]] \
        || fail 'the Java contract source manifest contains an invalid Python commit'
    [[ "${manifest_agent_commit}" == "${EXPECTED_AGENT_COMMIT}" ]] \
        || fail 'EXPECTED_AGENT_COMMIT does not match the Java contract source manifest'
    repository_root_canonical="$(cd -- "${REPOSITORY_ROOT}" && pwd -P)"
    evidence_root_candidate="$(cd -- "${GATE_EVIDENCE_ROOT}" && pwd -P)" \
        || fail 'unable to resolve GATE_EVIDENCE_ROOT'
    case "${evidence_root_candidate}/" in
        "${repository_root_canonical}/"*)
            fail 'GATE_EVIDENCE_ROOT must be outside the Java repository'
            ;;
    esac
    readonly EVIDENCE_ROOT="${evidence_root_candidate}"
    python_repository_root="$(git -C "${PYTHON_CONTRACT_ROOT}" rev-parse --show-toplevel)" \
        || fail 'PYTHON_CONTRACT_ROOT must belong to a Git worktree'
    python_repository_root="$(cd -- "${python_repository_root}" && pwd -P)"
    python_contract_root_canonical="$(cd -- "${PYTHON_CONTRACT_ROOT}" && pwd -P)" \
        || fail 'unable to resolve PYTHON_CONTRACT_ROOT'
    python_expected_contract_root="$(cd -- "${python_repository_root}/contracts" && pwd -P)" \
        || fail 'the Python repository has no canonical contracts directory'
    [[ "${python_contract_root_canonical}" == "${python_expected_contract_root}" ]] \
        || fail 'PYTHON_CONTRACT_ROOT must be the checked-out Python repository contracts directory'
    case "${EVIDENCE_ROOT}/" in
        "${python_repository_root}/"*)
            fail 'GATE_EVIDENCE_ROOT must be outside the Python repository'
            ;;
    esac
    python_gate_commit="$(git -C "${python_repository_root}" rev-parse HEAD)" \
        || fail 'unable to resolve the Python Gate Git commit'
    [[ "${python_gate_commit}" == "${EXPECTED_AGENT_COMMIT}" ]] \
        || fail 'the checked-out Python HEAD does not equal EXPECTED_AGENT_COMMIT'
    assert_repository_state "${REPOSITORY_ROOT}" "${gate_commit}" Java
    assert_repository_state "${python_repository_root}" "${python_gate_commit}" Python

    readonly RUN_ID="$(date -u +'%Y%m%dT%H%M%SZ')-$$"
    readonly GATE_STARTED_AT="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    pre_runtime_snapshot="$(mktemp)"
    capture_java_runtime_snapshot \
        "${gate_commit}" pre "${pre_runtime_snapshot}"
    cd -- "${REPOSITORY_ROOT}"
    run_maven_without_console_output clean clean
    readonly REPORT_ROOT="${REPOSITORY_ROOT}/target/m0-m1-gate-reports/${RUN_ID}"
    readonly EVIDENCE_DIRECTORY="${EVIDENCE_ROOT}/${gate_commit}/${RUN_ID}"
    mkdir -p -- "${REPORT_ROOT}"

    (
        export DATABASE_GATE_ENABLED=true
        run_gate_phase isolated-flyway 1 \
            com.guanghe.backend.database.DatabaseGateIntegrationTests
    )
    (
        export FORMAL_TEST_DB_INIT_ENABLED=true
        run_gate_phase formal-database 1 \
            com.guanghe.backend.database.FormalTestDatabaseInitializationIntegrationTests
    )
    (
        export TEST_ACCOUNT_INIT_ENABLED=true
        run_gate_phase fixed-accounts 1 \
            com.guanghe.backend.database.FixedTestAccountInitializationIntegrationTests
    )
    (
        # This test requires the Flyway variables to be absent, not merely empty.
        unset FLYWAY_DB_URL FLYWAY_DB_USERNAME FLYWAY_DB_PASSWORD
        export PUBLIC_TEST_DB_GATE_ENABLED=true
        run_gate_phase public-runtime-database 1 \
            com.guanghe.backend.database.PublicTestDatabaseDevelopmentModeIntegrationTests
    )
    (
        export POSTGRES_CONNECTION_GATE_ENABLED=true
        export REMOTE_REDIS_GATE_ENABLED=true
        export REMOTE_INTERNAL_HMAC_GATE_ENABLED=true
        export RABBIT_GATE_ENABLED=true
        export REMOTE_DEV_GATE_ENABLED=true
        export FORMAL_AUTH_GATE_ENABLED=true
        export REMOTE_FULL_AUTH_GATE_ENABLED=true
        export REMOTE_JAVA_M1_GATE_ENABLED=true
        run_gate_phase m0-m1-runtime 12 \
            com.guanghe.backend.database.DocumentedPostgresqlConnectionGateIntegrationTests,com.guanghe.backend.infrastructure.RemoteRedisLoginRateLimitIntegrationTests,com.guanghe.backend.infrastructure.RemoteInternalHmacNonceIntegrationTests,com.guanghe.backend.infrastructure.RabbitMqConnectionGateIntegrationTests,com.guanghe.backend.infrastructure.RemoteDevelopmentInfrastructureIntegrationTests,com.guanghe.backend.auth.web.FormalTestDatabaseAuthIntegrationTests,com.guanghe.backend.auth.web.RemoteFullAuthenticationGateIntegrationTests,com.guanghe.backend.integration.internal.RemoteJavaM1SpringContextIntegrationTests
    )
    run_gate_phase hmac-http-integrity "${EXPECTED_HMAC_TESTS}" \
        com.guanghe.backend.integration.internal.security.InternalHmacVerificationIntegrationTests
    run_contract_gate_phase

    verify_all_reports
    assert_repository_state "${REPOSITORY_ROOT}" "${gate_commit}" Java
    assert_repository_state "${python_repository_root}" "${python_gate_commit}" Python
    finalize_evidence_bundle \
        "${gate_commit}" "${python_gate_commit}" "${python_repository_root}" \
        "${pre_runtime_snapshot}"
    printf '[gate] Git commit=%s\n' "${gate_commit}"
    printf '[gate] Python commit=%s\n' "${python_gate_commit}"
    printf '[gate] read-only evidence directory: %s\n' \
        "${EVIDENCE_DIRECTORY}"
    printf '[gate] SHA256SUMS: %s\n' \
        "${EVIDENCE_DIRECTORY}/SHA256SUMS"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
