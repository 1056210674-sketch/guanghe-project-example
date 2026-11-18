package com.guanghe.backend.article;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.asset.AssetMapper;
import com.guanghe.backend.catalog.product.ProductRecord;
import com.guanghe.backend.catalog.product.ProductService;
import com.guanghe.backend.catalog.space.BusinessSpaceService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import com.guanghe.backend.geo.GeoTaskMapper;
import com.guanghe.backend.geo.GeoTaskRecords;
import com.guanghe.backend.task.PaidTaskProperties;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

/** Pure preparation tests: no server, database, API authorization or paid execution is simulated. */
@ExtendWith(MockitoExtension.class)
class ArticleFullPreparationTests {
    private static final long USER = 7, SPACE = 21, PRODUCT = 31, REPORT = 41, RUN = 51;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-08T00:10:00Z");
    @Mock private ArticleMapper articles;
    @Mock private AssetMapper assets;
    @Mock private GeoTaskMapper reports;
    @Mock private BusinessSpaceService spaces;
    @Mock private ProductService products;
    @Mock private AuthorizedCatalogContextFactory catalog;
    @Mock private SnowflakeIdGenerator ids;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private ArticleService service;

    @BeforeEach
    void setUp() {
        service = new ArticleService(articles, assets, reports, spaces, products, catalog, ids, json,
                new ArticleHtmlSanitizer());
    }

    @AfterEach
    void everyPreparationAndRejectionRemainsReadOnlyWithoutArticleDraftTaskOrIds() {
        // Stronger than enumerating insert/update methods: no article lookup, materialization,
        // confirmation, draft, asset write or generated identity is permitted at this seam.
        verifyNoInteractions(articles, assets, ids);
    }

    @Test
    void fixedRecipeHasOneArticleThreeDifferentQuotesAndNoRewriteOrInventedExecutionState() throws Exception {
        allowCatalog();
        var result = prepare(raw(), pricing());
        JsonNode frozen = json.readTree(result.canonicalJson());
        assertThat(result.stages()).extracting(ArticleFullPreparation.Stage::mode)
                .containsExactly("TITLE", "OUTLINE", "BODY");
        assertThat(result.stages()).extracting(ArticleFullPreparation.Stage::ordinal).containsExactly(1, 2, 3);
        assertThat(result.stages()).extracting(ArticleFullPreparation.Stage::quotedPoints).containsExactly(2, 5, 11);
        assertThat(result.stages()).extracting(ArticleFullPreparation.Stage::pricingVersionId)
                .containsOnly(101L);
        assertThat(result.totalQuotedPoints()).isEqualTo(18);
        assertThat(result.maxModelCalls()).isEqualTo(6);
        assertThat(frozen.at("/brief/authorizedContext/sourceAssets")).isEqualTo(json.createArrayNode());
        assertThat(frozen.path("templateCode").asText()).isEqualTo("ARTICLE_FULL_V1");
        assertThat(frozen.path("articleCount").asInt()).isEqualTo(1);
        assertThat(frozen.path("maxReplans").asInt()).isZero();
        assertThat(frozen.path("maxModelCalls").asInt()).isEqualTo(6);
        assertThat(frozen.path("totalQuotedPoints").asInt()).isEqualTo(18);
        assertThat(result.canonicalJson()).doesNotContain("articleId", "draftId", "taskId",
                "confirmation", "CONFIRMED", "selectedTitle", "confirmedPlan", "REWRITE", "persisted");
        assertThat(result.sha256()).matches("[a-f0-9]{64}");
        verify(spaces).requireProjectSpace(USER, SPACE);
        verifyNoInteractions(products, reports);
    }

    @Test
    void eachStageQuoteVersionBudgetAndBriefParticipatesInFrozenHash() {
        allowCatalog();
        var original = prepare(raw(), pricing());
        List<Consumer<PaidTaskProperties>> changes = List.of(
                p -> p.setArticleTitleTestPoints(3), p -> p.setArticleOutlineTestPoints(6),
                p -> p.setArticleBodyTestPoints(12), p -> p.setArticleTestPricingVersionId(102),
                p -> p.setArticleMaxModelCalls(3), p -> p.setArticleMaxRepairRetries(0),
                p -> p.setArticleMaxInputTokens(127999), p -> p.setArticleMaxOutputTokens(15999),
                p -> p.setArticleExecutionDeadline(Duration.ofMinutes(9)),
                p -> p.setConfirmationTtl(Duration.ofMinutes(2)));
        for (var change : changes) {
            var settings = pricing();
            change.accept(settings);
            assertThat(prepare(raw(), settings).sha256()).isNotEqualTo(original.sha256());
        }
        for (String field : List.of("requestText", "agentSummary", "topic", "language")) {
            ObjectNode changed = raw().put(field, "不同的资料");
            var candidate = prepare(changed, pricing());
            assertThat(candidate.briefHash()).isNotEqualTo(original.briefHash());
            assertThat(candidate.sha256()).isNotEqualTo(original.sha256());
        }
        var ignoredRewritePrice = pricing();
        ignoredRewritePrice.setArticleRewriteTestPoints(0);
        assertThat(prepare(raw(), ignoredRewritePrice).sha256()).isEqualTo(original.sha256());
    }

    @Test
    void rawAndPropertiesMutationCannotChangePreviouslyFrozenBriefOrBudget() {
        allowCatalog();
        ObjectNode raw = raw();
        var settings = pricing();
        var brief = service.prepareFullArticleBrief(USER, SPACE, null, raw);
        var result = ArticleFullPreparation.prepare(json, settings, brief, NOW);
        String originalJson = result.canonicalJson(), originalBrief = brief.canonicalJson();
        raw.put("requestText", "恶意替换").put("confirmed", true);
        raw.withArray("outline").add("追加新内容");
        settings.setArticleTitleTestPoints(999);
        settings.setArticleMaxInputTokens(1);
        settings.setArticleExecutionDeadline(Duration.ofSeconds(1));
        assertThat(result.canonicalJson()).isEqualTo(originalJson);
        assertThat(brief.canonicalJson()).isEqualTo(originalBrief);
        assertThat(result.stages().getFirst().quotedPoints()).isEqualTo(2);
        assertThat(result.stages().getFirst().budget().maxInputTokens()).isEqualTo(128000);
        assertThat(result.stages().getFirst().budget().executionDeadline()).isEqualTo(Duration.ofMinutes(10));
        assertThatThrownBy(() -> result.stages().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(result.toString()).doesNotContain("小行星", "requestText", "恶意");
        assertThat(brief.toString()).doesNotContain("小行星", "requestText", "恶意");
    }

    @Test
    void jsonObjectKeyOrderAndEquivalentOffsetInstantAreStableButListOrderIsNot() throws Exception {
        allowCatalog();
        ObjectNode source = raw();
        List<String> names = new ArrayList<>();
        source.fieldNames().forEachRemaining(names::add);
        Collections.reverse(names);
        ObjectNode reverse = json.createObjectNode();
        names.forEach(name -> reverse.set(name, source.get(name)));
        var a = service.prepareFullArticleBrief(USER, SPACE, null, source);
        var b = service.prepareFullArticleBrief(USER, SPACE, null, reverse);
        assertThat(b.sha256()).isEqualTo(a.sha256());
        var first = ArticleFullPreparation.prepare(json, pricing(), a, NOW);
        var otherOffset = ArticleFullPreparation.prepare(json, pricing(), b,
                NOW.withOffsetSameInstant(ZoneOffset.ofHours(8)));
        assertThat(otherOffset.canonicalJson()).isEqualTo(first.canonicalJson());
        assertThat(otherOffset.sha256()).isEqualTo(first.sha256());
        ObjectNode ordered = (ObjectNode) json.readTree("{\"z\":{\"b\":2,\"a\":1},\"a\":[1,2]}");
        ObjectNode reordered = (ObjectNode) json.readTree("{\"a\":[1,2],\"z\":{\"a\":1,\"b\":2}}");
        assertThat(ArticleFullPreparation.freezeBrief(json, ordered).sha256())
                .isEqualTo(ArticleFullPreparation.freezeBrief(json, reordered).sha256());
        reordered.putArray("a").add(2).add(1);
        assertThat(ArticleFullPreparation.freezeBrief(json, ordered).sha256())
                .isNotEqualTo(ArticleFullPreparation.freezeBrief(json, reordered).sha256());
    }

    @Test
    void boundedPreparationDeadlineAndQuoteExpiryAreNotRenewedByReading() {
        allowCatalog();
        var settings = pricing();
        settings.setConfirmationTtl(Duration.ofHours(2));
        var result = prepare(raw(), settings);
        assertThat(result.preparedAt()).isEqualTo(NOW);
        assertThat(result.deadlineAt()).isEqualTo(NOW.plusMinutes(30));
        assertThat(result.quoteExpiresAt()).isEqualTo(result.deadlineAt());
        assertThat(result.deadlineAt()).isEqualTo(NOW.plusMinutes(30));
        settings.setConfirmationTtl(Duration.ofMinutes(4));
        assertThat(prepare(raw(), settings).quoteExpiresAt()).isEqualTo(NOW.plusMinutes(4));
    }

    @Test
    void budgetContractAllowsBothExactBounds() {
        allowCatalog();
        var low = pricing();
        low.setArticleMaxModelCalls(1); low.setArticleMaxRepairRetries(0);
        low.setArticleMaxInputTokens(1); low.setArticleMaxOutputTokens(1);
        assertThat(prepare(raw(), low).maxModelCalls()).isEqualTo(3);
        var high = pricing();
        high.setArticleMaxModelCalls(10); high.setArticleMaxRepairRetries(5);
        high.setArticleMaxInputTokens(200000); high.setArticleMaxOutputTokens(64000);
        assertThat(prepare(raw(), high).maxModelCalls()).isEqualTo(30);
        assertThat(prepare(raw(), high).stages().getLast().budget().maxOutputTokens()).isEqualTo(64000);
    }

    @Test
    void invalidBudgetsTtlsAndOverflowFailClosed() {
        allowCatalog();
        var brief = service.prepareFullArticleBrief(USER, SPACE, null, raw());
        List<Consumer<PaidTaskProperties>> invalid = List.of(
                p -> p.setArticleMaxModelCalls(0), p -> p.setArticleMaxModelCalls(11),
                p -> p.setArticleMaxRepairRetries(-1), p -> p.setArticleMaxRepairRetries(6),
                p -> p.setArticleMaxInputTokens(0), p -> p.setArticleMaxInputTokens(200001),
                p -> p.setArticleMaxOutputTokens(0), p -> p.setArticleMaxOutputTokens(64001),
                p -> p.setArticleExecutionDeadline(null), p -> p.setArticleExecutionDeadline(Duration.ZERO),
                p -> p.setArticleExecutionDeadline(Duration.ofSeconds(-1)),
                p -> p.setArticleExecutionDeadline(Duration.ofSeconds(Long.MAX_VALUE)),
                p -> p.setConfirmationTtl(null), p -> p.setConfirmationTtl(Duration.ZERO),
                p -> p.setConfirmationTtl(Duration.ofSeconds(-1)),
                p -> p.setConfirmationTtl(Duration.ofSeconds(Long.MAX_VALUE)));
        for (var mutate : invalid) {
            var settings = pricing(); mutate.accept(settings);
            assertCode(() -> ArticleFullPreparation.prepare(json, settings, brief, NOW),
                    ErrorCode.PROVIDER_NOT_CONFIGURED);
        }
        assertCode(() -> ArticleFullPreparation.prepare(json, pricing(), brief, OffsetDateTime.MAX),
                ErrorCode.PROVIDER_NOT_CONFIGURED);
        assertCode(() -> ArticleFullPreparation.prepare(json, pricing(), null, NOW), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> ArticleFullPreparation.prepare(json, pricing(), brief, null), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void missingNegativeZeroStagePricesAndVersionOrTotalOverflowFailClosed() {
        allowCatalog();
        var brief = service.prepareFullArticleBrief(USER, SPACE, null, raw());
        List<Consumer<PaidTaskProperties>> invalid = List.of(
                p -> p.setArticleTestPricingVersionId(0), p -> p.setArticleTestPricingVersionId(-1),
                p -> p.setArticleTitleTestPoints(0), p -> p.setArticleTitleTestPoints(-1),
                p -> p.setArticleOutlineTestPoints(0), p -> p.setArticleOutlineTestPoints(-1),
                p -> p.setArticleBodyTestPoints(0), p -> p.setArticleBodyTestPoints(-1),
                p -> p.setArticleBodyTestPoints(Integer.MAX_VALUE));
        for (var mutate : invalid) {
            var settings = pricing(); mutate.accept(settings);
            assertCode(() -> ArticleFullPreparation.prepare(json, settings, brief, NOW),
                    ErrorCode.PROVIDER_NOT_CONFIGURED);
        }
        assertCode(() -> ArticleFullPreparation.prepare(json, new PaidTaskProperties(), brief, NOW),
                ErrorCode.PROVIDER_NOT_CONFIGURED);
    }

    @Test
    void rawExecutionIdentityConfirmationAndBudgetFieldsCannotBeInjected() {
        for (String field : List.of("articleId", "articleVersion", "draftId", "taskId", "userId",
                "spaceId", "productId", "selectedTitle", "confirmedOutline", "confirmedPlan",
                "confirmationToken", "confirmed", "sourceAssetIds", "geoRecommendationId",
                "quotedPoints", "budget", "deadlineAt", "templateCode", "articleCount")) {
            ObjectNode value = raw().put(field, "injected");
            assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null, value), ErrorCode.VALIDATION_FAILED);
        }
        for (String mode : List.of("TITLE", "OUTLINE", "BODY", "REWRITE", "ARTICLE_FULL_V1")) {
            assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null, raw().put("mode", mode)),
                    ErrorCode.VALIDATION_FAILED);
        }
        verifyNoInteractions(spaces, products, catalog, reports);
    }

    @Test
    void missingRequiredFieldsInvalidTypesAndUnboundedTextAreRejectedBeforeScopeReads() {
        for (String field : List.of("mode", "requestText", "agentSummary", "topic")) {
            ObjectNode missing = raw(); missing.remove(field);
            assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null, missing), ErrorCode.VALIDATION_FAILED);
        }
        for (String field : List.of("requestText", "agentSummary", "topic", "language")) {
            assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null, raw().put(field, " ")),
                    ErrorCode.VALIDATION_FAILED);
            assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null, raw().put(field, 7)),
                    ErrorCode.VALIDATION_FAILED);
        }
        Object[][] overflows = {{"requestText", 16001}, {"agentSummary", 1001}, {"topic", 501},
                {"language", 33}, {"tone", 129}};
        for (Object[] pair : overflows) {
            assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null,
                    raw().put((String) pair[0], "x".repeat((Integer) pair[1]))), ErrorCode.VALIDATION_FAILED);
        }
        assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null, null), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null, json.createArrayNode()),
                ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(spaces, products, catalog, reports);
    }

    @Test
    void outlineAndTargetLengthHaveBoundedCanonicalInput() throws Exception {
        for (int invalidLength : new int[]{99, 20001}) {
            assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null, raw().put("targetLength", invalidLength)),
                    ErrorCode.VALIDATION_FAILED);
        }
        for (String invalidLength : List.of("", "short", "1000")) {
            assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null, raw().put("targetLength", invalidLength)),
                    ErrorCode.VALIDATION_FAILED);
        }
        ObjectNode tooMany = raw();
        for (int i = 0; i < 51; i++) tooMany.withArray("outline").add("a");
        assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null, tooMany), ErrorCode.VALIDATION_FAILED);
        ObjectNode tooLong = raw(); tooLong.withArray("outline").add("x".repeat(501));
        assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null, tooLong), ErrorCode.VALIDATION_FAILED);
        ObjectNode wrongType = raw(); wrongType.withArray("outline").add(42);
        assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null, wrongType), ErrorCode.VALIDATION_FAILED);
        allowCatalog();
        for (int length : new int[]{100, 800, 1000, 1600, 20000}) {
            JsonNode value = json.readTree(service.prepareFullArticleBrief(USER, SPACE, null,
                    raw().put("targetLength", length)).canonicalJson());
            assertThat(value.path("editorial").path("targetLength").asText())
                    .isEqualTo(length <= 800 ? "SHORT" : length >= 1600 ? "LONG" : "MEDIUM");
        }
    }

    @Test
    void nonpositiveIdsFailBeforeAnyScopeRead() {
        assertCode(() -> service.prepareFullArticleBrief(0, SPACE, null, raw()), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.prepareFullArticleBrief(USER, -1, null, raw()), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, 0L, raw()), ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(spaces, products, reports, catalog);
    }

    @Test
    void foreignOrMissingSpaceCannotLoadCatalogOrReport() {
        when(spaces.requireProjectSpace(USER, SPACE)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null, raw().put("geoReportId", "41")),
                ErrorCode.RESOURCE_NOT_FOUND);
        verifyNoInteractions(products, reports, catalog);
    }

    @Test
    void missingAndCrossSpaceProductsAreRejectedByExistingOwnedPath() {
        when(products.requireProduct(USER, PRODUCT)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, PRODUCT, raw()), ErrorCode.RESOURCE_NOT_FOUND);
        doReturn(product(SPACE + 1)).when(products).requireProduct(USER, PRODUCT);
        assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, PRODUCT, raw()), ErrorCode.RESOURCE_NOT_FOUND);
        verifyNoInteractions(catalog, reports);
    }

    @Test
    void explicitReportMustBeOwnedAvailableNotArchivedAndNotDeterministic() {
        ObjectNode raw = raw().put("geoReportId", "41");
        when(reports.findReport(REPORT, USER)).thenReturn(Optional.empty());
        assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null, raw), ErrorCode.RESOURCE_NOT_FOUND);
        for (GeoTaskRecords.Report invalid : List.of(report("SUCCEEDED", "geo-synthesis-v1", 3, NOW),
                report("SUCCEEDED", "geo-deterministic-v1", 3, null),
                report("FAILED", "geo-synthesis-v1", 3, null),
                report("SUCCEEDED", "geo-synthesis-v1", 0, null))) {
            when(reports.findReport(REPORT, USER)).thenReturn(Optional.of(invalid));
            assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null, raw), ErrorCode.TASK_STATE_CONFLICT);
        }
        verifyNoInteractions(catalog);
    }

    @Test
    void reportScopeMustExactlyMatchSpaceAndOptionalProduct() {
        when(reports.findReport(REPORT, USER)).thenReturn(Optional.of(report("PARTIAL", "geo-synthesis-v1", 1, null)));
        when(reports.findRun(RUN, USER)).thenReturn(Optional.empty());
        assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null, raw().put("geoReportId", "41")),
                ErrorCode.RESOURCE_NOT_FOUND);
        for (GeoTaskRecords.Run run : List.of(run(SPACE + 1, null), run(SPACE, PRODUCT))) {
            when(reports.findRun(RUN, USER)).thenReturn(Optional.of(run));
            assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null, raw().put("geoReportId", "41")),
                    ErrorCode.RESOURCE_NOT_FOUND);
        }
        verifyNoInteractions(catalog);
    }

    @Test
    void validPartialReportIsCopiedWithoutBindingOrMutatingArticle() throws Exception {
        allowCatalog();
        when(reports.findReport(REPORT, USER)).thenReturn(Optional.of(report("PARTIAL", "geo-synthesis-v1", 1, null)));
        when(reports.findRun(RUN, USER)).thenReturn(Optional.of(run(SPACE, null)));
        JsonNode brief = json.readTree(service.prepareFullArticleBrief(USER, SPACE, null,
                raw().put("geoReportId", "41")).canonicalJson());
        assertThat(brief.path("authorizedContext").path("geoReport").path("id").asText()).isEqualTo("41");
        assertThat(brief.path("authorizedContext").path("geoReport").path("findings").get(0).asText())
                .isEqualTo("已保存的观察");
        verify(reports).findReport(REPORT, USER);
        verify(reports).findRun(RUN, USER);
    }

    @Test
    void malformedReportIdsAndOversizedAuthorizedSnapshotFailWithoutMaterialization() {
        for (JsonNode id : List.of(json.getNodeFactory().numberNode(REPORT),
                json.getNodeFactory().textNode("0"), json.getNodeFactory().textNode("not-an-id"))) {
            ObjectNode raw = raw(); raw.set("geoReportId", id);
            assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null, raw), ErrorCode.VALIDATION_FAILED);
        }
        when(catalog.create(USER, SPACE, null)).thenReturn(context("资".repeat(100000)));
        assertCode(() -> service.prepareFullArticleBrief(USER, SPACE, null, raw()), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void aggregateSizeLimitIsIndependentOfLegalBriefAndAcceptsTheExactUtf8Boundary() {
        int maximumBytes = 262_144;
        when(catalog.create(USER, SPACE, null)).thenReturn(context(""));
        var base = prepare(raw(), pricing());
        int baseBytes = base.canonicalJson().getBytes(StandardCharsets.UTF_8).length;
        assertThat(baseBytes).isLessThan(maximumBytes);

        // ASCII padding changes the UTF-8 size by exactly one byte, while the Chinese editorial
        // fields ensure the assertion is not accidentally measuring String.length().
        String padding = "x".repeat(maximumBytes - baseBytes);
        when(catalog.create(USER, SPACE, null)).thenReturn(context(padding));
        var exactBrief = service.prepareFullArticleBrief(USER, SPACE, null, raw());
        assertThat(exactBrief.canonicalJson().getBytes(StandardCharsets.UTF_8).length)
                .isLessThan(maximumBytes);
        var exact = ArticleFullPreparation.prepare(json, pricing(), exactBrief, NOW);
        assertThat(exact.canonicalJson().getBytes(StandardCharsets.UTF_8)).hasSize(maximumBytes);
        assertThat(exact.canonicalJson().length()).isLessThan(maximumBytes);

        when(catalog.create(USER, SPACE, null)).thenReturn(context(padding + "x"));
        var stillLegalBrief = service.prepareFullArticleBrief(USER, SPACE, null, raw());
        assertThat(stillLegalBrief.canonicalJson().getBytes(StandardCharsets.UTF_8).length)
                .isEqualTo(exactBrief.canonicalJson().getBytes(StandardCharsets.UTF_8).length + 1)
                .isLessThan(maximumBytes);
        assertCode(() -> ArticleFullPreparation.prepare(json, pricing(), stillLegalBrief, NOW),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void seamIsInternalReadOnlyAndBriefCannotBeConstructedAsAnAuthorizationDto() throws Exception {
        var method = ArticleService.class.getMethod("prepareFullArticleBrief",
                long.class, long.class, Long.class, JsonNode.class);
        assertThat(method.getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(method.isAnnotationPresent(RequestMapping.class)).isFalse();
        assertThat(ArticleFullPreparation.class.isAnnotationPresent(Service.class)).isFalse();
        assertThat(ArticleFullPreparation.Brief.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        // No USER/ADMIN API claim: caller authentication and archived-project admission remain
        // the responsibility of the future execution/admission layer, not this long-userId seam.
    }

    @Test
    void fullPreparationRejectsUnconfiguredOrGenericPublicIdentityBeforeAnyPaidMaterialization() {
        for (var candidate : List.of(
                new AuthorizedCatalogContextFactory.Context(null, null, List.of()),
                new AuthorizedCatalogContextFactory.Context(new InternalRunContextDtos.Space("21", "私密项目名",
                        new InternalRunContextDtos.Brand("咖啡", List.of(), null,
                                List.of(), List.of(), List.of(), List.of()), null), null, List.of()),
                new AuthorizedCatalogContextFactory.Context(new InternalRunContextDtos.Space("22", "其他项目",
                        new InternalRunContextDtos.Brand("小行星咖啡店", List.of(), null,
                                List.of(), List.of(), List.of(), List.of()), null), null, List.of()))) {
            when(catalog.create(USER, SPACE, null)).thenReturn(candidate);
            assertThatThrownBy(() -> service.prepareFullArticleBrief(USER, SPACE, null, raw()))
                    .isInstanceOfSatisfying(BusinessException.class, error -> {
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(error.details()).containsEntry("field", "geoTarget");
                    });
        }
        verifyNoInteractions(reports, products);
    }

    private void allowCatalog() { when(catalog.create(USER, SPACE, null)).thenReturn(context("咖啡与点心")); }
    private AuthorizedCatalogContextFactory.Context context(String description) {
        return new AuthorizedCatalogContextFactory.Context(new InternalRunContextDtos.Space("21", "小行星",
                new InternalRunContextDtos.Brand("小行星", List.of(), description, List.of(), List.of(),
                        List.of(), List.of()), null), null, List.of());
    }
    private ObjectNode raw() {
        ObjectNode value = json.createObjectNode();
        value.put("mode", "AGENT_ASSISTED").put("requestText", "帮小行星写一篇店铺介绍")
                .put("agentSummary", "依据商家提供的资料创作").put("topic", "小行星咖啡与甜点")
                .put("language", "中文").put("tone", "自然").put("targetLength", "MEDIUM");
        value.putArray("outline");
        return value;
    }
    private PaidTaskProperties pricing() {
        var result = new PaidTaskProperties();
        result.setArticleTestPricingVersionId(101);
        result.setArticleTitleTestPoints(2); result.setArticleOutlineTestPoints(5);
        result.setArticleBodyTestPoints(11); result.setArticleRewriteTestPoints(97);
        result.setConfirmationTtl(Duration.ofMinutes(5));
        return result;
    }
    private ArticleFullPreparation.Snapshot prepare(ObjectNode raw, PaidTaskProperties properties) {
        return ArticleFullPreparation.prepare(json, properties,
                service.prepareFullArticleBrief(USER, SPACE, null, raw), NOW);
    }
    private ProductRecord product(long spaceId) {
        return new ProductRecord(PRODUCT, USER, spaceId, "曲奇", "曲奇", null, null,
                "[]", "{}", "[]", "[]", "[]", null, 1, NOW, NOW, 0);
    }
    private GeoTaskRecords.Report report(String status, String method, int samples, OffsetDateTime archived) {
        return new GeoTaskRecords.Report(REPORT, RUN, 61, USER, 71, status,
                "{\"findings\":[\"已保存的观察\"],\"recommendations\":[]}", "{}", "摘要", method,
                samples, NOW, NOW, 1, 0, archived);
    }
    private GeoTaskRecords.Run run(long spaceId, Long productId) {
        return new GeoTaskRecords.Run(RUN, 61, USER, spaceId, productId, 71, "SUCCEEDED", "ONCE",
                1, 1, 0, 0, "{}", "geo-metric-v1", "geo-synthesis-v1", NOW, NOW, NOW, NOW, 1, 1, 0);
    }
    private void assertCode(Runnable operation, ErrorCode code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(code));
    }
}
