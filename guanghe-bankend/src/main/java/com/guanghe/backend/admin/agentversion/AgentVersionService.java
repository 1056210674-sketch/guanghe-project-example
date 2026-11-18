package com.guanghe.backend.admin.agentversion;

import com.guanghe.backend.auth.access.AdminAccess;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.integration.internal.client.PythonCallResult;
import com.guanghe.backend.integration.internal.client.PythonCapabilitiesResponse;
import com.guanghe.backend.integration.internal.client.PythonInternalClient;
import com.guanghe.backend.integration.internal.contract.AgentBundleVersionFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class AgentVersionService {
    private static final Pattern BUNDLE_VERSION =
            Pattern.compile(AgentBundleVersionFormat.REGEX);
    private static final Pattern SAFE_VERSION =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+:-]{0,127}$");
    private static final Pattern GIT_COMMIT = Pattern.compile("^[0-9a-f]{7,64}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern PROVIDER = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern MODEL = Pattern.compile("^[A-Za-z0-9._:/-]{1,128}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private final AgentVersionMapper mapper;
    private final PythonInternalClient python;

    @Autowired
    public AgentVersionService(ObjectProvider<AgentVersionMapper> mapperProvider,
                               ObjectProvider<PythonInternalClient> pythonProvider) {
        this.mapper = mapperProvider.getIfAvailable();
        this.python = pythonProvider.getIfAvailable();
    }

    AgentVersionService(AgentVersionMapper mapper, PythonInternalClient python) {
        this.mapper = mapper;
        this.python = python;
    }

    public AgentVersionDtos.VersionPage list(AuthenticatedAccount account, int page, int size) {
        AdminAccess.requireAdmin(account);
        AgentVersionDtos.RuntimeSnapshot runtime = runtimeSnapshot();
        String currentBundle = availableBundle(runtime);
        long offset = (long) (page - 1) * size;
        List<AgentVersionDtos.VersionSummary> items = mapper().listVersions(currentBundle, offset, size)
                .stream().map(row -> summary(row, currentBundle)).toList();
        return new AgentVersionDtos.VersionPage(runtime,
                PageResponse.of(items, page, size, mapper().countVersions(currentBundle)));
    }

    public AgentVersionDtos.VersionDetail get(AuthenticatedAccount account, String rawBundleVersion) {
        AdminAccess.requireAdmin(account);
        String bundleVersion = bundleVersion(rawBundleVersion);
        AgentVersionDtos.RuntimeSnapshot runtime = runtimeSnapshot();
        String currentBundle = availableBundle(runtime);
        AgentVersionRecords.VersionAggregate aggregate = mapper().findVersion(bundleVersion).orElse(null);
        if (aggregate == null && !bundleVersion.equals(currentBundle)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (aggregate == null) {
            aggregate = new AgentVersionRecords.VersionAggregate(bundleVersion, null, null, 0);
        }
        List<String> dataGaps = new ArrayList<>();
        dataGaps.add("EVALUATIONS_NOT_REGISTERED");
        if (!bundleVersion.equals(currentBundle)) {
            dataGaps.add("HISTORICAL_RUNTIME_METADATA_NOT_PERSISTED");
        }
        if (runtime.availability() != AgentVersionDtos.RuntimeAvailability.AVAILABLE) {
            dataGaps.add("CURRENT_RUNTIME_UNAVAILABLE");
        }
        return new AgentVersionDtos.VersionDetail(summary(aggregate, currentBundle), runtime,
                AgentVersionDtos.EvaluationAvailability.NOT_REGISTERED, dataGaps);
    }

    public PageResponse<AgentVersionDtos.AssociatedRun> runs(AuthenticatedAccount account,
            String rawBundleVersion, int page, int size) {
        AdminAccess.requireAdmin(account);
        String bundleVersion = bundleVersion(rawBundleVersion);
        requireKnownVersion(bundleVersion);
        long offset = (long) (page - 1) * size;
        List<AgentVersionDtos.AssociatedRun> items = mapper().listRuns(bundleVersion, offset, size)
                .stream().map(this::run).toList();
        return PageResponse.of(items, page, size, mapper().countRuns(bundleVersion));
    }

    public AgentVersionDtos.EvaluationPage evaluations(AuthenticatedAccount account,
            String rawBundleVersion, int page, int size) {
        AdminAccess.requireAdmin(account);
        String bundleVersion = bundleVersion(rawBundleVersion);
        requireKnownVersion(bundleVersion);
        return new AgentVersionDtos.EvaluationPage(
                AgentVersionDtos.EvaluationAvailability.NOT_REGISTERED,
                PageResponse.of(List.of(), page, size, 0));
    }

    private AgentVersionDtos.RuntimeSnapshot runtimeSnapshot() {
        if (python == null) {
            return unavailable(AgentVersionDtos.RuntimeAvailability.NOT_CONFIGURED,
                    "INTERNAL_COMMUNICATION_DISABLED");
        }
        PythonCallResult<PythonCapabilitiesResponse> result;
        try {
            result = python.capabilities();
        } catch (RuntimeException exception) {
            return unavailable(AgentVersionDtos.RuntimeAvailability.UNAVAILABLE, "CLIENT_FAILURE");
        }
        if (result == null || !result.available() || result.payload() == null) {
            String reason = result == null || result.reason() == null
                    ? "INVALID_RESPONSE" : result.reason().name();
            return unavailable(AgentVersionDtos.RuntimeAvailability.UNAVAILABLE, reason);
        }
        PythonCapabilitiesResponse capabilities = result.payload();
        if (!validCapabilities(capabilities)) {
            return unavailable(AgentVersionDtos.RuntimeAvailability.UNAVAILABLE, "INVALID_RESPONSE");
        }
        List<AgentVersionDtos.Profile> profiles = capabilities.supportedProfiles().stream()
                .map(profile -> new AgentVersionDtos.Profile(profile.profileCode(),
                        profile.profileVersion(), profile.taskTypes())).toList();
        List<AgentVersionDtos.Provider> providers = capabilities.providers().stream()
                .map(provider -> new AgentVersionDtos.Provider(provider.code(),
                        provider.status().name(), provider.model())).toList();
        PythonCapabilitiesResponse.ModelGateway gateway = capabilities.modelGateway();
        AgentVersionDtos.ModelGateway gatewayDto = new AgentVersionDtos.ModelGateway(
                gateway.mode().name(), gateway.provider(), gateway.model(), gateway.status().name(),
                gateway.reasonCode());
        return new AgentVersionDtos.RuntimeSnapshot(
                AgentVersionDtos.RuntimeAvailability.AVAILABLE, null,
                capabilities.serviceVersion(), capabilities.bundleVersion(),
                capabilities.bundleHash(), capabilities.promptHash(),
                capabilities.gitCommit(), gatewayDto, profiles, providers);
    }

    /**
     * The capability endpoint is signed, but it is still a remote contract boundary. Reject the
     * entire snapshot when it cannot satisfy the Browser API contract instead of silently dropping
     * fields and presenting partial evidence as an exact current runtime.
     */
    private boolean validCapabilities(PythonCapabilitiesResponse capabilities) {
        if (safe(capabilities.bundleVersion(), BUNDLE_VERSION) == null
                || safe(capabilities.serviceVersion(), SAFE_VERSION) == null
                || safe(capabilities.bundleHash(), SHA256) == null
                || safe(capabilities.promptHash(), SHA256) == null
                || capabilities.gitCommit() != null
                && safe(capabilities.gitCommit(), GIT_COMMIT) == null
                || capabilities.supportedProfiles().size() > 50
                || capabilities.providers().size() > 50) {
            return false;
        }
        PythonCapabilitiesResponse.ModelGateway gateway = capabilities.modelGateway();
        if (gateway.provider() != null && safe(gateway.provider(), PROVIDER) == null
                || gateway.model() != null && safe(gateway.model(), MODEL) == null
                || gateway.reasonCode() != null && safe(gateway.reasonCode(), CODE) == null) {
            return false;
        }
        boolean profilesValid = capabilities.supportedProfiles().stream().allMatch(profile ->
                safeText(profile.profileCode(), 128) != null
                        && safeText(profile.profileVersion(), 128) != null
                        && profile.taskTypes().size() <= 20
                        && new HashSet<>(profile.taskTypes()).size() == profile.taskTypes().size()
                        && profile.taskTypes().stream().allMatch(type -> safe(type, CODE) != null));
        return profilesValid && capabilities.providers().stream().allMatch(provider ->
                safe(provider.code(), PROVIDER) != null
                        && (provider.model() == null || safe(provider.model(), MODEL) != null));
    }

    private AgentVersionDtos.RuntimeSnapshot unavailable(
            AgentVersionDtos.RuntimeAvailability availability, String reason) {
        return new AgentVersionDtos.RuntimeSnapshot(availability, reason, null, null, null,
                null, null, null, List.of(), List.of());
    }

    private void requireKnownVersion(String bundleVersion) {
        if (mapper().findVersion(bundleVersion).isPresent()) return;
        if (!bundleVersion.equals(availableBundle(runtimeSnapshot()))) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private AgentVersionDtos.VersionSummary summary(AgentVersionRecords.VersionAggregate row,
                                                     String currentBundle) {
        return new AgentVersionDtos.VersionSummary(row.bundleVersion(),
                Objects.equals(row.bundleVersion(), currentBundle)
                        ? AgentVersionDtos.VersionStatus.CURRENT
                        : AgentVersionDtos.VersionStatus.OBSERVED,
                row.firstObservedAt(), row.lastObservedAt(), row.runCount());
    }

    private AgentVersionDtos.AssociatedRun run(AgentVersionRecords.AssociatedRun row) {
        Long durationMs = null;
        if (row.startedAt() != null && row.finishedAt() != null
                && !row.finishedAt().isBefore(row.startedAt())) {
            durationMs = Duration.between(row.startedAt(), row.finishedAt()).toMillis();
        }
        return new AgentVersionDtos.AssociatedRun(Long.toString(row.executionId()),
                AgentVersionDtos.RunSource.valueOf(row.source()), Long.toString(row.parentId()),
                safe(row.taskType(), CODE), safe(row.status(), CODE),
                safe(row.stageCode(), CODE), safe(row.providerCode(), PROVIDER),
                safe(row.providerModel(), MODEL), safe(row.stableErrorCode(), CODE),
                row.startedAt(), row.finishedAt(), durationMs);
    }

    private AgentVersionMapper mapper() {
        if (mapper == null) throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        return mapper;
    }

    private static String availableBundle(AgentVersionDtos.RuntimeSnapshot runtime) {
        return runtime.availability() == AgentVersionDtos.RuntimeAvailability.AVAILABLE
                ? runtime.bundleVersion() : null;
    }

    private static String bundleVersion(String value) {
        if (value == null || !BUNDLE_VERSION.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }

    private static String safe(String value, Pattern pattern) {
        return value != null && pattern.matcher(value).matches() ? value : null;
    }

    private static String safeText(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) return null;
        return value.chars().noneMatch(Character::isISOControl) ? value : null;
    }
}
