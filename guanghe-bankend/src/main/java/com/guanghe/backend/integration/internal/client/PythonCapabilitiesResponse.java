package com.guanghe.backend.integration.internal.client;

import com.guanghe.backend.integration.internal.contract.AgentBundleVersionFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record PythonCapabilitiesResponse(
        String serviceVersion,
        String bundleVersion,
        String bundleHash,
        String promptHash,
        String gitCommit,
        ModelGateway modelGateway,
        List<Profile> supportedProfiles,
        List<Provider> providers
) {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public PythonCapabilitiesResponse {
        requireText(serviceVersion, "serviceVersion");
        if (!AgentBundleVersionFormat.isValid(bundleVersion)) {
            throw new IllegalArgumentException("bundleVersion has an invalid format");
        }
        requireSha256(bundleHash, "bundleHash");
        requireSha256(promptHash, "promptHash");
        Objects.requireNonNull(modelGateway, "modelGateway");
        supportedProfiles = List.copyOf(Objects.requireNonNull(supportedProfiles, "supportedProfiles"));
        providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
    }

    public enum ProviderStatus {
        AVAILABLE,
        CONFIGURED,
        UNAVAILABLE,
        NOT_CONFIGURED,
        MODEL_NOT_CONFIGURED,
        DISABLED
    }

    public enum GatewayMode {
        MOCK,
        REAL,
        UNAVAILABLE
    }

    public record ModelGateway(
            GatewayMode mode,
            String provider,
            String model,
            ProviderStatus status,
            String reasonCode
    ) {

        public ModelGateway {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(status, "status");
        }
    }

    public record Profile(String profileCode, String profileVersion, List<String> taskTypes) {

        public Profile {
            requireText(profileCode, "profileCode");
            requireText(profileVersion, "profileVersion");
            taskTypes = List.copyOf(Objects.requireNonNull(taskTypes, "taskTypes"));
        }
    }

    public record Provider(String code, ProviderStatus status, String model) {

        public Provider {
            requireText(code, "code");
            Objects.requireNonNull(status, "status");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
    }
}
