package com.guanghe.backend.admin.agentversion;

import com.guanghe.backend.catalog.PageResponse;
import java.time.OffsetDateTime;
import java.util.List;

public final class AgentVersionDtos {
    private AgentVersionDtos() { }

    public enum RuntimeAvailability { AVAILABLE, UNAVAILABLE, NOT_CONFIGURED }
    public enum VersionStatus { CURRENT, OBSERVED }
    public enum RunSource { TASK, WORKFLOW }
    public enum EvaluationAvailability { NOT_REGISTERED }

    public record ModelGateway(String mode, String provider, String model,
                               String status, String reasonCode) { }

    public record Profile(String profileCode, String profileVersion,
                          List<String> taskTypes) {
        public Profile { taskTypes = List.copyOf(taskTypes); }
    }

    public record Provider(String code, String status, String model) { }

    /**
     * Evidence returned by the running Python service. An unavailable snapshot deliberately has
     * null version fields: callers must not infer the current version from database recency.
     */
    public record RuntimeSnapshot(RuntimeAvailability availability, String reasonCode,
                                  String serviceVersion, String bundleVersion,
                                  String bundleHash, String promptHash, String gitCommit,
                                  ModelGateway modelGateway, List<Profile> profiles,
                                  List<Provider> providers) {
        public RuntimeSnapshot {
            profiles = List.copyOf(profiles);
            providers = List.copyOf(providers);
        }
    }

    public record VersionSummary(String bundleVersion, VersionStatus status,
                                 OffsetDateTime firstObservedAt,
                                 OffsetDateTime lastObservedAt, long runCount) { }

    public record VersionPage(RuntimeSnapshot currentRuntime,
                              PageResponse<VersionSummary> versions) { }

    public record VersionDetail(VersionSummary version, RuntimeSnapshot currentRuntime,
                                EvaluationAvailability evaluationAvailability,
                                List<String> dataGaps) {
        public VersionDetail { dataGaps = List.copyOf(dataGaps); }
    }

    public record AssociatedRun(String executionId, RunSource source, String parentId,
                                String taskType, String status, String stage,
                                String provider, String model, String stableErrorCode,
                                OffsetDateTime startedAt, OffsetDateTime finishedAt,
                                Long durationMs) { }

    /** Reserved contract for a future, registered evaluation source. */
    public record EvaluationResult(String evaluationId, String name, int sampleCount,
                                   String result, OffsetDateTime evaluatedAt) { }

    public record EvaluationPage(EvaluationAvailability availability,
                                 PageResponse<EvaluationResult> results) { }
}
