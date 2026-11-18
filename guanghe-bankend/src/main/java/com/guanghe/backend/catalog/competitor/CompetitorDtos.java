package com.guanghe.backend.catalog.competitor;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public final class CompetitorDtos {

    private CompetitorDtos() {
    }

    public record CreateRequest(
            @NotBlank @Size(max = 150) String name,
            @NotNull CompetitorType type,
            @Size(max = 1000) String description,
            CompetitorStatus status
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown catalog request field");
        }
    }

    public static final class UpdateRequest {
        @NotNull
        @Min(1)
        @Max(Integer.MAX_VALUE)
        private Integer version;
        private String name;
        private CompetitorType type;
        private String description;
        private CompetitorStatus status;
        private boolean namePresent;
        private boolean typePresent;
        private boolean descriptionPresent;
        private boolean statusPresent;

        public Integer getVersion() { return version; }
        public void setVersion(Integer version) { this.version = version; }
        public String getName() { return name; }
        public void setName(String name) { namePresent = true; this.name = name; }
        public CompetitorType getType() { return type; }
        public void setType(CompetitorType type) { typePresent = true; this.type = type; }
        public String getDescription() { return description; }
        public void setDescription(String description) { descriptionPresent = true; this.description = description; }
        public CompetitorStatus getStatus() { return status; }
        public void setStatus(CompetitorStatus status) { statusPresent = true; this.status = status; }
        public boolean isNamePresent() { return namePresent; }
        public boolean isTypePresent() { return typePresent; }
        public boolean isDescriptionPresent() { return descriptionPresent; }
        public boolean isStatusPresent() { return statusPresent; }

        public boolean hasChanges() {
            return namePresent || typePresent || descriptionPresent || statusPresent;
        }

        @JsonAnySetter
        public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown catalog request field");
        }
    }

    public record ReplaceAliasesRequest(
            @NotNull @Min(1) @Max(Integer.MAX_VALUE) Integer version,
            @NotNull @Size(max = 50) List<@NotBlank @Size(max = 150) String> aliases
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown catalog request field");
        }
    }

    public record Response(
            String id,
            String spaceId,
            String name,
            List<String> aliases,
            CompetitorType type,
            String description,
            CompetitorStatus status,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record Summary(
            String id,
            String spaceId,
            String name,
            CompetitorType type,
            CompetitorStatus status,
            int aliasCount,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }
}
