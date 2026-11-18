package com.guanghe.backend.geo;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public final class ProjectGeoTargetDtos {
    private ProjectGeoTargetDtos() { }

    public record UpdateRequest(@NotNull @Min(1) Integer version,
            @NotBlank @Size(min = 2, max = 100) String name,
            @NotNull @Size(max = 50) List<@NotBlank @Size(min = 2, max = 100) String> aliases) {
        @JsonAnySetter public void rejectUnknown(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown project GEO target field");
        }
    }

    public record View(String projectId, int version, String name, List<String> aliases,
                       boolean configured, OffsetDateTime updatedAt) {
        public View { aliases = List.copyOf(aliases); }
    }
}
