package com.guanghe.backend.integration.contract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GateRunnerEvidencePolicyTests {

    private static final Path GATE_RUNNER = Path.of("scripts", "run-m0-m1-gates.sh");
    private static final Pattern POLICY_ENTRY = Pattern.compile(
            "\\\"(?<key>[a-z_]+)\\\"\\s*:\\s*re\\.compile\\((?<body>.*?)\\)\\s*,",
            Pattern.DOTALL
    );
    private static final Pattern PYTHON_RAW_STRING = Pattern.compile("r\\\"(?<value>(?:\\\\.|[^\\\"])*)\\\"");

    @Test
    void gateEvidencePolicyAcceptsOnlyApprovedStatuses() throws Exception {
        String runner = Files.readString(GATE_RUNNER);

        Pattern isolatedRedisFailure = evidencePattern(runner, "isolated_redis_failure");
        assertThat(matches(isolatedRedisFailure,
                "isolatedAgent=NOT_READY(INTERNAL_NONCE_STORE_UNAVAILABLE) "
                        + "hmac=503/INTERNAL_AUTHENTICATION_UNAVAILABLE sharedAgent=READY"))
                .as("dependency unavailability is a service-availability failure")
                .isTrue();
        assertThat(matches(isolatedRedisFailure,
                "isolatedAgent=NOT_READY(INTERNAL_NONCE_STORE_UNAVAILABLE) "
                        + "hmac=401/INTERNAL_AUTHENTICATION_UNAVAILABLE sharedAgent=READY"))
                .as("dependency unavailability must not be classified as an authentication failure")
                .isFalse();

        assertUnauthorizedOnly(runner, "body_tamper",
                "code=INTERNAL_SIGNATURE_INVALID nonceUnclaimed=true");
        assertUnauthorizedOnly(runner, "query_tamper",
                "code=INTERNAL_SIGNATURE_INVALID nonceUnclaimed=true");
        assertUnauthorizedOnly(runner, "expired_timestamp",
                "code=INTERNAL_SIGNATURE_INVALID nonceUnclaimed=true");
        assertUnauthorizedOnly(runner, "uppercase_nonce",
                "code=INTERNAL_SIGNATURE_INVALID nonceStoreBypassed=true");

        Pattern replay = evidencePattern(runner, "nonce_replay");
        assertThat(matches(replay, "first=200 second=401 code=INTERNAL_REPLAY_DETECTED")).isTrue();
        assertThat(matches(replay, "first=200 second=503 code=INTERNAL_REPLAY_DETECTED")).isFalse();
    }

    private void assertUnauthorizedOnly(String runner, String key, String evidenceSuffix) {
        Pattern policy = evidencePattern(runner, key);
        assertThat(matches(policy, "httpStatus=401 " + evidenceSuffix)).as(key).isTrue();
        assertThat(matches(policy, "httpStatus=503 " + evidenceSuffix)).as(key).isFalse();
    }

    private Pattern evidencePattern(String runner, String key) {
        Matcher entries = POLICY_ENTRY.matcher(runner);
        while (entries.find()) {
            if (!key.equals(entries.group("key"))) {
                continue;
            }
            Matcher fragments = PYTHON_RAW_STRING.matcher(entries.group("body"));
            StringBuilder expression = new StringBuilder();
            while (fragments.find()) {
                expression.append(fragments.group("value"));
            }
            assertThat(expression).as("regex fragments for %s", key).isNotEmpty();
            return Pattern.compile(expression.toString());
        }
        throw new AssertionError("Gate evidence policy is missing: " + key);
    }

    private boolean matches(Pattern pattern, String evidence) {
        return pattern.matcher(evidence).matches();
    }
}
