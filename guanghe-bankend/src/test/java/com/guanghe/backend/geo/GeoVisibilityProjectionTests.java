package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoVisibilityProjectionTests {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-28T03:00:00Z");

    @Test
    void countsActualNameOncePerValidAnswerAndKeepsFailuresOutOfDenominator() throws Exception {
        ObjectNode snapshot = snapshot();
        var result = GeoVisibilityProjection.calculate(run(102L), snapshot, List.of(
                observation("301", "DOUBAO", "SUCCEEDED", "星河 T7 保温杯和星河T7值得了解。星河T7。"),
                observation("302", "DOUBAO", "FAILED", null),
                observation("303", "DOUBAO", "SUCCEEDED", "这里没有目标商品。")));
        var doubao = result.platforms().getFirst();
        assertThat(result.assessmentStatus()).isEqualTo("AVAILABLE");
        assertThat(result.target().name()).isEqualTo("星河 T7 保温杯");
        assertThat(doubao.expectedSamples()).isEqualTo(3);
        assertThat(doubao.validSamples()).isEqualTo(2);
        assertThat(doubao.mentionedSamples()).isEqualTo(1);
        assertThat(doubao.failedSamples()).isEqualTo(1);
        assertThat(doubao.missingSamples()).isZero();
        assertThat(doubao.mentionRate()).isEqualTo(50.0);
        assertThat(result.samples().getFirst().matchedTerms()).containsExactly("星河 T7 保温杯", "星河T7");
        assertThat(result.samples().get(1).mentioned()).isNull();
        assertThat(result.platforms().get(1).mentionRate()).isNull();
        assertThat(result.platforms().get(1).missingSamples()).isEqualTo(3);
    }

    @Test
    void normalizesFullWidthAndCaseButDoesNotMatchWithinLatinNames() throws Exception {
        ObjectNode snapshot = snapshot();
        ((ObjectNode) snapshot.path("catalogSnapshot").path("product")).put("name", "ACME").putArray("aliases");
        var result = GeoVisibilityProjection.calculate(run(102L), snapshot, List.of(
                observation("301", "DOUBAO", "SUCCEEDED", "ＡＣＭＥ很好，acme也被提及。"),
                observation("302", "DOUBAO", "SUCCEEDED", "MegaACME ACMEplus ACME123")));
        assertThat(result.platforms().getFirst().mentionRate()).isEqualTo(50.0);
        assertThat(result.samples().get(1).mentioned()).isFalse();
    }

    @Test
    void topicTitleAndCitationTextDoNotCountAsProductMention() throws Exception {
        var result = GeoVisibilityProjection.calculate(run(102L), snapshot(), List.of(
                observation("301", "DOUBAO", "SUCCEEDED", "保温杯品牌发现-20260909 星河保温杯")));
        assertThat(result.platforms().getFirst().mentionRate()).isEqualTo(0.0);
    }

    @Test
    void missingProductSnapshotCannotFallBackToBrandOrTopic() throws Exception {
        ObjectNode snapshot = snapshot();
        ((ObjectNode) snapshot.path("catalogSnapshot")).remove("product");
        var result = GeoVisibilityProjection.calculate(run(102L), snapshot,
                List.of(observation("301", "DOUBAO", "SUCCEEDED", "星河")));
        assertThat(result.assessmentStatus()).isEqualTo("UNAVAILABLE_TARGET");
        assertThat(result.unavailableReason()).isEqualTo("MISSING_TARGET_SNAPSHOT");
        assertThat(result.target()).isNull();
        assertThat(result.platforms().getFirst().mentionRate()).isNull();
        assertThat(result.samples().getFirst().mentioned()).isNull();
    }

    @Test
    void mismatchedFrozenIdentityFailsClosed() throws Exception {
        ObjectNode snapshot = snapshot();
        ((ObjectNode) snapshot.path("catalogSnapshot").path("product")).put("id", "999");
        assertThat(GeoVisibilityProjection.target(run(102L), snapshot).unavailableReason())
                .isEqualTo("MISSING_TARGET_SNAPSHOT");
    }

    @Test
    void productAssessmentAlsoRequiresMatchingFrozenSpace() throws Exception {
        ObjectNode snapshot = snapshot();
        ((ObjectNode) snapshot.path("catalogSnapshot").path("space")).put("id", "999");
        assertThat(GeoVisibilityProjection.target(run(102L), snapshot).unavailableReason())
                .isEqualTo("MISSING_TARGET_SNAPSHOT");
    }

    @Test
    void linkDestinationsAndReferenceDefinitionsDoNotCountButVisibleLabelsDo() throws Exception {
        ObjectNode snapshot = snapshot();
        ((ObjectNode) snapshot.path("catalogSnapshot").path("product")).put("name", "LightCup").putArray("aliases");
        String hiddenOnly = "[点击](https://example.com/product/LightCup?q=LightCup \"LightCup\") "
                + "[查看](https://example.com/item_(LightCup)) [详情][LightCup]\n"
                + "[LightCup]: https://example.com/item \"LightCup\"\n"
                + "https://example.com/LightCup <a href='https://example.com/LightCup'>查看</a>";
        var result = GeoVisibilityProjection.calculate(run(102L), snapshot, List.of(
                observation("301", "DOUBAO", "SUCCEEDED", hiddenOnly),
                observation("302", "DOUBAO", "SUCCEEDED", "可选[LightCup](https://example.com/item)"),
                observation("303", "DOUBAO", "SUCCEEDED", "<a href='https://example.com/item'>LightCup</a>")));
        assertThat(result.samples().getFirst().mentioned()).isFalse();
        assertThat(result.samples().get(1).mentioned()).isTrue();
        assertThat(result.samples().get(2).mentioned()).isTrue();
        assertThat(result.platforms().getFirst().mentionedSamples()).isEqualTo(2);
        assertThat(result.platforms().getFirst().mentionRate()).isEqualTo(66.67);
    }

    @Test
    void genericAndBrandOnlyProductNamesCannotClaimProductVisibility() throws Exception {
        ObjectNode snapshot = snapshot();
        ObjectNode product = (ObjectNode) snapshot.path("catalogSnapshot").path("product");
        product.put("name", "保温杯");
        product.putArray("aliases").add("杯子").add("星河").add("A");
        var result = GeoVisibilityProjection.calculate(run(102L), snapshot,
                List.of(observation("301", "DOUBAO", "SUCCEEDED", "星河保温杯和A")));
        assertThat(result.unavailableReason()).isEqualTo("AMBIGUOUS_TARGET_NAMES");
        assertThat(result.target().name()).isEqualTo("保温杯");
        assertThat(result.platforms().getFirst().mentionedSamples()).isNull();
    }

    @Test
    void registeredSpecificAliasCanIdentifyGenericProductName() throws Exception {
        ObjectNode snapshot = snapshot();
        ((ObjectNode) snapshot.path("catalogSnapshot").path("product")).put("name", "保温杯");
        var target = GeoVisibilityProjection.target(run(102L), snapshot);
        assertThat(target.unavailableReason()).isNull();
        assertThat(target.terms()).containsExactly("星河T7");
    }

    @Test
    void brandAssessmentOnlyWhenNoProductWasSelected() throws Exception {
        var result = GeoVisibilityProjection.calculate(run(null), snapshot(),
                List.of(observation("301", "DOUBAO", "SUCCEEDED", "银河牌可以关注")));
        assertThat(result.target().kind()).isEqualTo("BRAND");
        assertThat(result.target().id()).isEqualTo("101");
        assertThat(result.platforms().getFirst().mentionRate()).isEqualTo(100.0);
    }

    @Test
    void noValidAnswerIsUnknownNotZeroAndBlankSuccessIsMissing() throws Exception {
        var result = GeoVisibilityProjection.calculate(run(102L), snapshot(), List.of(
                observation("301", "DOUBAO", "FAILED", null),
                observation("302", "DOUBAO", "SUCCEEDED", "   ")));
        assertThat(result.assessmentStatus()).isEqualTo("NO_VALID_SAMPLES");
        assertThat(result.unavailableReason()).isEqualTo("NO_VALID_ANSWERS");
        assertThat(result.platforms().getFirst().mentionRate()).isNull();
        assertThat(result.platforms().getFirst().missingSamples()).isEqualTo(2);
    }

    @Test
    void ignoresDuplicateAndOutOfMatrixEvidence() throws Exception {
        var observation = observation("301", "DOUBAO", "SUCCEEDED", "星河T7");
        var result = GeoVisibilityProjection.calculate(run(102L), snapshot(), List.of(observation, observation,
                observation("999", "DOUBAO", "SUCCEEDED", "星河T7"),
                observation("302", "KIMI", "SUCCEEDED", "星河T7")));
        assertThat(result.samples()).hasSize(1);
        assertThat(result.platforms().getFirst().validSamples()).isEqualTo(1);
        assertThat(result.platforms().getFirst().missingSamples()).isEqualTo(2);
    }

    @Test
    void deepSamplingUsesFrozenThreeRoundsNotCurrentConfiguration() throws Exception {
        ObjectNode snapshot = snapshot();
        ((ObjectNode) snapshot.path("parameters")).put("sampleMode", "DEEP");
        assertThat(GeoVisibilityProjection.calculate(run(102L), snapshot, List.of()).platforms().getFirst()
                .expectedSamples()).isEqualTo(9);
    }

    private ObjectNode snapshot() throws Exception {
        return (ObjectNode) JsonMapper.builder().build().readTree("""
                {"parameters":{"sampleMode":"STANDARD","platformCodes":["DOUBAO","DEEPSEEK"]},
                 "authorizedContext":{"topic":{"name":"保温杯品牌发现-20260909"},
                   "questions":[{"id":"301"},{"id":"302"},{"id":"303"}]},
                 "catalogSnapshot":{"space":{"id":"101","brand":{"name":"星河","aliases":["银河牌"]}},
                   "product":{"id":"102","spaceId":"101","name":"星河 T7 保温杯",
                     "category":"保温杯","aliases":["星河T7","星河T7","保温杯"]}}}
                """);
    }

    private GeoTaskRecords.Run run(Long productId) {
        return new GeoTaskRecords.Run(501, 401, 41, 101, productId, 201, "PARTIAL", "STANDARD", 6,
                4, 2, 0, "{}", "geo-observation-v1", "geo-synthesis-v1", NOW, NOW, NOW, NOW, 6, 4, 2);
    }

    private GeoTaskDtos.ObservationResponse observation(String question, String platform, String status, String answer) {
        return new GeoTaskDtos.ObservationResponse(question + ":" + platform + ":1", question, "问题", platform,
                1, status, answer, "FAILED".equals(status) ? "GEO_PROVIDER_TIMEOUT" : null, platform,
                "model", NOW, NOW, 1, new GeoTaskDtos.UsageResponse(0, 0, 0, null, null), 1, List.of());
    }
}
