package com.guanghe.backend.agentplatform;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Explicit USER selection; none of these client fields grant business permissions. */
public final class MonitorExecutionDtos {
    private MonitorExecutionDtos() { }
    public static final String ID = "^[1-9][0-9]{0,18}$";
    public static final String PLATFORM = "^(DOUBAO|DEEPSEEK|QWEN|WENXIN|KIMI|YUANBAO|ZHIPU)$";
    public record MonitorRangeInput(@NotBlank @Pattern(regexp=ID) String topicId,
            @NotNull @Size(min=3,max=10) List<@NotBlank @Pattern(regexp=ID) String> questionIds,
            @NotNull @Size(min=1,max=7) List<@NotBlank @Pattern(regexp=PLATFORM) String> platformCodes,
            @NotBlank @Pattern(regexp="^(STANDARD|DEEP)$") String sampleMode) {
        @JsonAnySetter public void unknown(String name, JsonNode value) { throw new IllegalArgumentException("Unknown monitor range field"); }
    }
    public record CompetitorVersion(@NotBlank @Pattern(regexp=ID) String competitorId,
            @NotNull @Min(1) Integer version) {
        @JsonAnySetter public void unknown(String name, JsonNode value) { throw new IllegalArgumentException("Unknown competitor version field"); }
    }
    public record InputVersions(@NotNull @Min(1) Integer spaceVersion, @Min(1) Integer productVersion,
            @Min(1) Integer competitorSetVersion, @NotNull @Size(max=20) List<@NotNull @Valid CompetitorVersion> competitorVersions) {
        @JsonAnySetter public void unknown(String name, JsonNode value) { throw new IllegalArgumentException("Unknown input version field"); }
    }
    public record FrozenMonitorRange(@NotBlank @Pattern(regexp=ID) String topicId,
            @NotNull @Min(1) Integer topicVersion,
            @NotNull @Size(min=3,max=10) List<@NotBlank @Pattern(regexp=ID) String> questionIds,
            @NotNull @Size(min=1,max=7) List<@NotBlank @Pattern(regexp=PLATFORM) String> platformCodes,
            @NotBlank @Pattern(regexp="^(STANDARD|DEEP)$") String sampleMode,
            @NotBlank @Size(max=100) String region, @NotNull @Valid InputVersions inputVersions,
            @NotBlank @Pattern(regexp="^[0-9a-f]{64}$") String inputHash) {
        public MonitorRangeInput input() { return new MonitorRangeInput(topicId,questionIds,platformCodes,sampleMode); }
        @JsonAnySetter public void unknown(String name, JsonNode value) { throw new IllegalArgumentException("Unknown frozen range field"); }
    }
    public record BudgetPolicyPut(@NotNull @Min(0) Integer version, @NotNull Boolean enabled,
            @NotNull @Min(1) Integer maxPointsPerAction, @NotNull @Min(1) Integer maxPointsPerDay) {
        @JsonAnySetter public void unknown(String name, JsonNode value) { throw new IllegalArgumentException("Unknown budget policy field"); }
    }
    public record BudgetPolicyView(boolean configured, int version, boolean enabled,
            Integer maxPointsPerAction, Integer maxPointsPerDay, String timezone,
            LocalDate earliestTrustedShanghaiDate, LocalDate effectiveShanghaiDate) { }
    public record MonitorActionPreview(String action, String status, String reasonCode,
            FrozenMonitorRange range, String pricingVersionId, Integer unitPoints,
            Integer expectedUnits, Integer quotedPoints) { }
    public record MonitorAuthorizationCreate(@NotNull @Min(0) @Max(0) Integer expectedVersion,
            @NotNull @Valid FrozenMonitorRange range, @NotNull OffsetDateTime expiresAt) {
        @JsonAnySetter public void unknown(String name, JsonNode value) { throw new IllegalArgumentException("Unknown authorization field"); }
    }
    public record MonitorAuthorizationPut(@NotNull @Min(1) Integer version,
            @NotNull @Valid FrozenMonitorRange range, @NotNull OffsetDateTime expiresAt) {
        @JsonAnySetter public void unknown(String name, JsonNode value) { throw new IllegalArgumentException("Unknown authorization field"); }
    }
    public record VersionRequest(@NotNull @Min(1) Integer version) {
        @JsonAnySetter public void unknown(String name, JsonNode value) { throw new IllegalArgumentException("Unknown version field"); }
    }
    public record MonitorAuthorizationView(String id, String action, int version, String status,
            FrozenMonitorRange range, OffsetDateTime expiresAt, OffsetDateTime revokedAt, OffsetDateTime createdAt) { }
    public record MonitorExecutionIntent(@NotBlank @Pattern(regexp="^MONITOR_RUN$") String action,
            @NotBlank @Pattern(regexp=ID) String grantId, @NotNull @Min(1) Integer grantVersion,
            @NotNull @Valid FrozenMonitorRange range,
            @NotBlank @Pattern(regexp=ID) String pricingVersionId, @NotNull @Min(1) Integer quotedPoints) {
        @JsonAnySetter public void unknown(String name, JsonNode value) { throw new IllegalArgumentException("Unknown execution intent field"); }
    }
    public record MonitorAdmissionView(UUID runId, String status, String reasonCode, String draftId,
            String taskId, String executionSource, Integer quotedPoints, OffsetDateTime admittedAt) { }
    public record MonitorActionContext(String action, String spaceId, String productId,
            JsonNode parameters, JsonNode questions) { }
}
