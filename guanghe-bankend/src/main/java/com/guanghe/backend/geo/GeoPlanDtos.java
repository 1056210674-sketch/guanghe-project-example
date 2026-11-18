package com.guanghe.backend.geo;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

public final class GeoPlanDtos {
    private GeoPlanDtos() { }

    public record ScheduleRequest(
            @NotBlank @Pattern(regexp = "^(HOURLY|DAILY|WEEKLY|INTERVAL_DAYS)$") String type,
            @NotBlank @Size(max = 80) String timeZone,
            @NotNull LocalTime localTime,
            @Size(max = 7) List<@Min(1) @Max(7) Integer> daysOfWeek,
            @Min(1) Integer intervalDays
    ) {
        @JsonAnySetter public void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO plan schedule field");
        }
    }

    public record CreateDraftRequest(
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String topicId,
            @NotBlank @Size(max = 100) String name,
            @NotNull @Valid ScheduleRequest schedule,
            @NotNull @Size(min = 3, max = 10)
            List<@NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String> questionIds,
            @NotNull @Size(min = 1, max = 7)
            List<@NotBlank @Pattern(regexp = "^(DOUBAO|DEEPSEEK|QWEN|WENXIN|KIMI|YUANBAO|ZHIPU)$") String> platformCodes,
            @NotBlank @Pattern(regexp = "^(STANDARD|DEEP)$") String sampleMode,
            @NotNull @Min(1) Integer maxPointsPerRun
    ) {
        @JsonAnySetter public void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO plan draft field");
        }
    }

    public record DraftResponse(String draftId, String inputHash, String pricingVersionId,
                                int estimatedPointsPerRun, String confirmationToken,
                                OffsetDateTime expiresAt) { }

    public record ConfirmPlanRequest(
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String draftId,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String inputHash,
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String pricingVersionId,
            @NotBlank @Size(max = 512) String confirmationToken
    ) {
        @JsonAnySetter public void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO plan confirmation field");
        }
    }

    public static final class UpdatePlanRequest {
        @NotNull @Min(1) @Max(Integer.MAX_VALUE)
        private Integer version;
        private String name;
        private String draftId;
        private String inputHash;
        private String pricingVersionId;
        private String confirmationToken;
        private boolean namePresent;

        public Integer getVersion() { return version; }
        public void setVersion(Integer value) { version = value; }
        @Size(max = 100) public String getName() { return name; }
        public void setName(String value) { namePresent = true; name = value; }
        @Pattern(regexp = "^[1-9][0-9]{0,18}$") public String getDraftId() { return draftId; }
        public void setDraftId(String value) { draftId = value; }
        @Pattern(regexp = "^[0-9a-f]{64}$") public String getInputHash() { return inputHash; }
        public void setInputHash(String value) { inputHash = value; }
        @Pattern(regexp = "^[1-9][0-9]{0,18}$") public String getPricingVersionId() { return pricingVersionId; }
        public void setPricingVersionId(String value) { pricingVersionId = value; }
        @Size(max = 512) public String getConfirmationToken() { return confirmationToken; }
        public void setConfirmationToken(String value) { confirmationToken = value; }
        public boolean isNamePresent() { return namePresent; }
        public boolean hasAnyConfirmation() {
            return draftId != null || inputHash != null || pricingVersionId != null || confirmationToken != null;
        }
        public boolean hasCompleteConfirmation() {
            return draftId != null && inputHash != null && pricingVersionId != null
                    && confirmationToken != null;
        }
        @JsonAnySetter public void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO plan update field");
        }
    }

    public record VersionRequest(@NotNull @Min(1) @Max(Integer.MAX_VALUE) Integer version) {
        @JsonAnySetter public void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO plan version field");
        }
    }

    public record Schedule(String type, String timeZone, String localTime,
                           List<Integer> daysOfWeek, Integer intervalDays) { }

    public record PlanResponse(
            String id, String topicId, String spaceId, String productId,
            String name, String status, Schedule schedule,
            List<String> questionIds, List<String> platformCodes, String sampleMode,
            int estimatedPointsPerRun, int maxPointsPerRun, String pricingVersionId,
            OffsetDateTime nextRunAt, OffsetDateTime lastScheduledSlot,
            String pauseReason, int version, OffsetDateTime createdAt, OffsetDateTime updatedAt
    ) { }
}
