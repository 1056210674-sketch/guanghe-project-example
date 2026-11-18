package com.guanghe.backend.geo;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Browser contract for an explicitly requested, evidence-grounded visual plan. */
public final class GeoCreativePlanDtos {
    private GeoCreativePlanDtos() { }

    public record Request(
            @NotBlank @Pattern(regexp = "IMAGE|VIDEO") String taskType,
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String productId,
            @Pattern(regexp = "^[1-9][0-9]{0,18}:recommendation:[1-9][0-9]{0,2}$") String recommendationId,
            @Size(max = 500) String userDirection) {
        @JsonAnySetter public void rejectUnknown(String name, JsonNode value) {
            throw new IllegalArgumentException("Unknown GEO creative plan request field");
        }
    }

    public record Evidence(String key, String questionText, String platformCode, String answerExcerpt) { }

    public record Response(String reportId, String recommendationId, JsonNode plan,
                           List<Evidence> evidence) { }
}
