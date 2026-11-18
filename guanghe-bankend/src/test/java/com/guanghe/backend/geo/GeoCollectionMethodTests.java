package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validation;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoCollectionMethodTests {
    @Test
    void jdbcMappingExplicitlySelectsFullObservationConstructor() {
        var selected = java.util.Arrays.stream(GeoTaskRecords.Observation.class.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(org.apache.ibatis.annotations.AutomapConstructor.class))
                .toList();
        assertThat(selected).hasSize(1);
        assertThat(selected.getFirst().getParameterCount()).isEqualTo(24);
        assertThat(selected.getFirst().getParameterTypes()[23]).isEqualTo(String.class);
    }

    @Test
    void nullableLegacyCollectionMethodPreservesCanonicalObservationHashPayload() throws Exception {
        var json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        var old = input(null);
        var tree = json.valueToTree(old);
        assertThat(tree.has("collectionMethod")).isFalse();
        assertThat(json.treeToValue(tree, GeoTaskDtos.ObservationInput.class).collectionMethod()).isNull();
        assertThat(json.valueToTree(input("bocha-then-deepseek-v1")).path("collectionMethod").asText())
                .isEqualTo("bocha-then-deepseek-v1");
    }

    @Test
    void actualMethodAcceptsVersionedAdapterNamesAndRejectsInvalidMetadata() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(input(null))).isEmpty();
            assertThat(validator.validate(input("doubao-responses-web-search-v1"))).isEmpty();
            assertThat(validator.validate(input("CURRENT CONFIG"))).isNotEmpty();
            assertThat(validator.validate(input(""))).isNotEmpty();
        }
    }

    @Test
    void trendReadsAreOwnerScopedBoundedAndDoNotSelectSynthesisOrCitations() throws Exception {
        for (var method : GeoTrendMapper.class.getDeclaredMethods()) {
            var sql = String.join("\n", method.getAnnotation(Select.class).value());
            assertThat(sql).contains("user_id=#{userId}", "LIMIT").doesNotContain("synthesis", "geo_citation");
        }
        String evidence = String.join("\n", GeoTrendMapper.class.getMethod("evidence", long.class, List.class,
                long.class, int.class).getAnnotation(Select.class).value());
        assertThat(evidence).contains("#{byteLimit}", "collection_method", "evidenceTruncated");
        assertThat(GeoTaskMapper.OBSERVATION_COLUMNS).endsWith("o.collection_method AS collectionMethod\n");
    }

    private GeoTaskDtos.ObservationInput input(String method) {
        var now = OffsetDateTime.parse("2026-09-28T03:00:00Z");
        return new GeoTaskDtos.ObservationInput("DOUBAO", "301", 1, "SUCCEEDED", "回答", List.of(),
                "DOUBAO", "model", now, now, 1, null, new GeoTaskDtos.UsageInput(0, 0, 0, null, null), 1, method);
    }
}
