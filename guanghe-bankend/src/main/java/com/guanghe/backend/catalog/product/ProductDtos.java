package com.guanghe.backend.catalog.product;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class ProductDtos {

    private ProductDtos() {
    }

    public record CreateRequest(
            @NotBlank @Size(max = 150) String name,
            @Size(max = 100) String category,
            @Size(max = 150) String modelSpec,
            @Size(max = 50) List<@NotBlank @Size(max = 500) String> sellingPoints,
            @Size(max = 100) Map<@NotBlank @Size(max = 100) String, @NotNull @Size(max = 1000) String> parameters,
            @Size(max = 50) List<@NotBlank @Size(max = 200) String> audience,
            @Size(max = 50) List<@NotBlank @Size(max = 200) String> usageScenarios,
            @Size(max = 50) List<@NotBlank @Size(max = 500) String> prohibitedClaims,
            JsonNode primaryAssetId
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
        private String category;
        private String modelSpec;
        private List<String> sellingPoints;
        private Map<String, String> parameters;
        private List<String> audience;
        private List<String> usageScenarios;
        private List<String> prohibitedClaims;
        private JsonNode primaryAssetId;
        private boolean namePresent;
        private boolean categoryPresent;
        private boolean modelSpecPresent;
        private boolean sellingPointsPresent;
        private boolean parametersPresent;
        private boolean audiencePresent;
        private boolean usageScenariosPresent;
        private boolean prohibitedClaimsPresent;
        private boolean primaryAssetIdPresent;

        public Integer getVersion() { return version; }
        public void setVersion(Integer version) { this.version = version; }
        public String getName() { return name; }
        public void setName(String name) { namePresent = true; this.name = name; }
        public String getCategory() { return category; }
        public void setCategory(String category) { categoryPresent = true; this.category = category; }
        public String getModelSpec() { return modelSpec; }
        public void setModelSpec(String modelSpec) { modelSpecPresent = true; this.modelSpec = modelSpec; }
        public List<String> getSellingPoints() { return sellingPoints; }
        public void setSellingPoints(List<String> sellingPoints) {
            sellingPointsPresent = true; this.sellingPoints = sellingPoints;
        }
        public Map<String, String> getParameters() { return parameters; }
        public void setParameters(Map<String, String> parameters) {
            parametersPresent = true; this.parameters = parameters;
        }
        public List<String> getAudience() { return audience; }
        public void setAudience(List<String> audience) { audiencePresent = true; this.audience = audience; }
        public List<String> getUsageScenarios() { return usageScenarios; }
        public void setUsageScenarios(List<String> usageScenarios) {
            usageScenariosPresent = true; this.usageScenarios = usageScenarios;
        }
        public List<String> getProhibitedClaims() { return prohibitedClaims; }
        public void setProhibitedClaims(List<String> prohibitedClaims) {
            prohibitedClaimsPresent = true; this.prohibitedClaims = prohibitedClaims;
        }
        public JsonNode getPrimaryAssetId() { return primaryAssetId; }
        public void setPrimaryAssetId(JsonNode primaryAssetId) {
            primaryAssetIdPresent = true; this.primaryAssetId = primaryAssetId;
        }
        public boolean isNamePresent() { return namePresent; }
        public boolean isCategoryPresent() { return categoryPresent; }
        public boolean isModelSpecPresent() { return modelSpecPresent; }
        public boolean isSellingPointsPresent() { return sellingPointsPresent; }
        public boolean isParametersPresent() { return parametersPresent; }
        public boolean isAudiencePresent() { return audiencePresent; }
        public boolean isUsageScenariosPresent() { return usageScenariosPresent; }
        public boolean isProhibitedClaimsPresent() { return prohibitedClaimsPresent; }
        public boolean isPrimaryAssetIdPresent() { return primaryAssetIdPresent; }

        public boolean hasChanges() {
            return namePresent || categoryPresent || modelSpecPresent || sellingPointsPresent
                    || parametersPresent || audiencePresent || usageScenariosPresent
                    || prohibitedClaimsPresent || primaryAssetIdPresent;
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

    public record IdentityAssetInput(
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "^[1-9][0-9]{0,18}$") String assetId,
            @NotBlank @jakarta.validation.constraints.Pattern(
                    regexp = "^(PRIMARY|WHITE_BACKGROUND|DETAIL|PACKAGE)$") String role,
            @NotNull @Min(1) @Max(20) Integer sequence
    ) { }

    public record ReplaceIdentityAssetsRequest(
            @NotNull @Min(1) @Max(Integer.MAX_VALUE) Integer version,
            @NotNull @Size(max = 20) List<@Valid IdentityAssetInput> items
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown product identity asset field");
        }
    }

    public record IdentityAssetResponse(
            String assetId,
            String role,
            int sequence,
            String status,
            String contentType,
            Integer width,
            Integer height
    ) { }

    public record IdentityAssetsResponse(
            String productId,
            int version,
            boolean creativeReady,
            List<IdentityAssetResponse> items
    ) {
        public IdentityAssetsResponse { items = List.copyOf(items); }
    }

    public record Response(
            String id,
            String spaceId,
            String name,
            List<String> aliases,
            String category,
            String modelSpec,
            List<String> sellingPoints,
            Map<String, String> parameters,
            List<String> audience,
            List<String> usageScenarios,
            List<String> prohibitedClaims,
            String primaryAssetId,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record Summary(
            String id,
            String spaceId,
            String name,
            String category,
            String modelSpec,
            int aliasCount,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }
}
