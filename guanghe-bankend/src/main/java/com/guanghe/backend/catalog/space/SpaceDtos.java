package com.guanghe.backend.catalog.space;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public final class SpaceDtos {

    private SpaceDtos() {
    }

    public record BrandRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 50) List<@NotBlank @Size(max = 100) String> aliases,
            @Size(max = 2000) String description,
            @Size(max = 10) List<@NotBlank @Size(max = 50) String> tone,
            @Size(max = 20) List<@NotBlank @Size(max = 200) String> audience,
            @Size(max = 20) List<@NotBlank @Size(max = 500) String> sellingPoints,
            @Size(max = 20) List<@NotBlank @Size(max = 500) String> prohibitedClaims
    ) {
        @JsonAnySetter
        public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown catalog request field");
        }
    }

    public record CreateRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Valid BrandRequest brand,
            JsonNode logoAssetId
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
        private BrandRequest brand;
        private JsonNode logoAssetId;
        private boolean namePresent;
        private boolean brandPresent;
        private boolean logoAssetIdPresent;

        public Integer getVersion() {
            return version;
        }

        public void setVersion(Integer version) {
            this.version = version;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.namePresent = true;
            this.name = name;
        }

        @Valid
        public BrandRequest getBrand() {
            return brand;
        }

        public void setBrand(BrandRequest brand) {
            this.brandPresent = true;
            this.brand = brand;
        }

        public JsonNode getLogoAssetId() {
            return logoAssetId;
        }

        public void setLogoAssetId(JsonNode logoAssetId) {
            this.logoAssetIdPresent = true;
            this.logoAssetId = logoAssetId;
        }

        public boolean isNamePresent() {
            return namePresent;
        }

        public boolean isBrandPresent() {
            return brandPresent;
        }

        public boolean isLogoAssetIdPresent() {
            return logoAssetIdPresent;
        }

        public boolean hasChanges() {
            return namePresent || brandPresent || logoAssetIdPresent;
        }

        @JsonAnySetter
        public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown catalog request field");
        }
    }

    public record Brand(
            String name,
            List<String> aliases,
            String description,
            List<String> tone,
            List<String> audience,
            List<String> sellingPoints,
            List<String> prohibitedClaims
    ) {
    }

    public record Response(
            String id,
            String name,
            Brand brand,
            String logoAssetId,
            long productCount,
            long competitorCount,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record Summary(
            String id,
            String name,
            String brandName,
            long productCount,
            long competitorCount,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record StoredBrandProfile(
            String description,
            List<String> tone,
            List<String> audience,
            List<String> sellingPoints,
            List<String> prohibitedClaims
    ) {
    }
}
