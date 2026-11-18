package com.guanghe.backend.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import com.guanghe.backend.task.PaidTaskProperties;
import com.guanghe.backend.task.TaskControlMapper;
import com.guanghe.backend.task.TaskDtos;
import com.guanghe.backend.task.TaskRecords;
import java.time.Duration;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeoTaskServiceTests {

    @Test
    void archiveOnlyChangesOwnedReportLibraryVisibility() {
        AuthenticatedAccount account = new AuthenticatedAccount(
                USER, "user", AccountRole.USER, 1, AccountStatus.ACTIVE, null, null, null);
        GeoTaskRecords.Report archived = new GeoTaskRecords.Report(
                701, 501, TASK, USER, TOPIC, "PARTIAL", "{}", "{}", "摘要",
                "geo-synthesis-v1", 4, NOW, NOW, 4, 0, NOW.plusHours(1));
        when(mapper.archiveReport(eq(701L), eq(USER), any())).thenReturn(1);
        when(mapper.findReport(701L, USER)).thenReturn(Optional.of(archived));

        GeoTaskDtos.ReportSummary result = service.setReportArchived(account, 701L, true);

        assertThat(result.archivedAt()).isEqualTo(NOW.plusHours(1));
        verify(mapper).archiveReport(eq(701L), eq(USER), any());
        verify(mapper, never()).restoreReport(anyLong(), anyLong());
        when(mapper.countReports(USER, null, null, true)).thenReturn(1L);
        when(mapper.findReportPage(USER, null, null, true, 0L, 20)).thenReturn(List.of(archived));
        assertThat(service.reports(account, null, true, 1, 20).items()).hasSize(1);

        when(mapper.restoreReport(701L, USER)).thenReturn(1);
        when(mapper.findReport(701L, USER)).thenReturn(Optional.of(new GeoTaskRecords.Report(
                701, 501, TASK, USER, TOPIC, "PARTIAL", "{}", "{}", "摘要",
                "geo-synthesis-v1", 4, NOW, NOW, 4, 0)));
        assertThat(service.setReportArchived(account, 701L, false).archivedAt()).isNull();
    }

    @Test
    void reportsDefaultToActiveLibraryForLegacyCallers() {
        AuthenticatedAccount account = new AuthenticatedAccount(
                USER, "user", AccountRole.USER, 1, AccountStatus.ACTIVE, null, null, null);

        service.reports(account, null, 1, 20);
        service.reports(account, null, null, 1, 20);

        verify(mapper, org.mockito.Mockito.times(2)).countReports(USER, null, null, false);
        verify(mapper, org.mockito.Mockito.times(2)).findReportPage(USER, null, null, false, 0L, 20);
    }

    @Test
    void derivesDiscoveryQuestionCapacityFromConfiguredPlatformsAndProviderBudget() {
        PaidTaskProperties configured = new PaidTaskProperties();
        configured.setGeoExecutionEnabled(true);
        configured.setGeoMaxProviderCalls(60);
        configured.setGeoMaxAttemptsPerUnit(2);
        configured.setGeoConfiguredPlatforms(Set.of(
                "DOUBAO", "QWEN", "WENXIN", "KIMI", "YUANBAO", "ZHIPU"));

        assertThat(configured.getGeoMaxDiscoveryQuestions()).isEqualTo(3);
        assertThat(configured.geoRequiredProviderCalls(1, List.of("YUANBAO"), 1)).isEqualTo(4);
        assertThat(configured.geoRequiredProviderCalls(3, List.of(
                "DOUBAO", "DEEPSEEK", "QWEN", "WENXIN", "KIMI", "YUANBAO", "ZHIPU"), 1))
                .isEqualTo(60);
        assertThat(configured.geoRequiredProviderCalls(3, List.of("YUANBAO"), 3)).isEqualTo(36);
    }

    private static final long USER = 41;
    private static final long SPACE = 101;
    private static final long PRODUCT = 102;
    private static final long TOPIC = 201;
    private static final long TASK = 401;
    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 9, 8, 2, 0, 0, 0, ZoneOffset.UTC);

    private final GeoTaskMapper mapper = mock(GeoTaskMapper.class);
    private final GeoMapper geo = mock(GeoMapper.class);
    private final TaskControlMapper tasks = mock(TaskControlMapper.class);
    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final AuthorizedCatalogContextFactory catalog = mock(AuthorizedCatalogContextFactory.class);
    private final SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final PaidTaskProperties properties = new PaidTaskProperties();
    private GeoTaskService service;

    @BeforeEach
    void setUp() {
        properties.setGeoExecutionEnabled(true);
        properties.setGeoTestPricingVersionId(9001);
        properties.setGeoTestUnitPoints(1);
        properties.setGeoConfiguredPlatforms(Set.of("DOUBAO", "WENXIN"));
        properties.setGeoMaxProviderCalls(20);
        properties.setGeoMaxConcurrency(3);
        properties.setGeoExecutionDeadline(Duration.ofMinutes(30));
        AtomicLong sequence = new AtomicLong(10_000);
        when(ids.nextId()).thenAnswer(ignored -> sequence.incrementAndGet());
        service = new GeoTaskService(mapper, geo, tasks, conversations, catalog, ids, json, properties);
    }

    @Test
    void preparesAuthoritativeFrozenMatrixAndComputesPerUnitTestQuote() throws Exception {
        arrangeFoundation();

        GeoTaskService.PreparedDraft prepared = service.prepareDraft(
                USER, SPACE, PRODUCT, parameters("STANDARD"));

        assertThat(prepared.quotedUnits()).isEqualTo(6);
        assertThat(service.quote(prepared)).isEqualTo(6);
        assertThat(prepared.parameters().path("questionIds")).extracting(node -> node.textValue())
                .containsExactly("301", "302", "303");
        assertThat(prepared.snapshot().path("authorizedContext").path("topic").path("id").asText())
                .isEqualTo("201");
        assertThat(prepared.snapshot().toString()).doesNotContain("userId", "pricingVersionId", "unitPoints");
    }

    @Test
    void discoveryDraftIsPricedWithoutWritesThenMaterializedIntoExecutableFoundation() throws Exception {
        ObjectNode discoveryParameters = (ObjectNode) json.readTree("""
                {"requestText":"监测需求词","mode":"QUESTION_DISCOVERY","agentSummary":"需求词专题",
                 "topic":"美式咖啡需求词","region":"全国","questions":[
                   "哪些人适合喝美式咖啡？","什么场景适合选择美式咖啡？","购买美式咖啡时关注什么？"]}
                """);
        when(catalog.create(USER, SPACE, PRODUCT)).thenReturn(
                monitoringCatalog());

        GeoTaskService.PreparedDraft discovery = service.prepareDiscoveryDraft(
                USER, SPACE, PRODUCT, discoveryParameters);

        assertThat(discovery.quotedUnits()).isEqualTo(6);
        assertThat(discovery.parameters().path("platformCodes"))
                .extracting(JsonNode::textValue).containsExactly("DOUBAO", "WENXIN");
        verify(geo, never()).insertTopic(any());
        verify(geo, never()).insertQuestion(any());

        GeoRecords.Topic storedTopic = new GeoRecords.Topic(10_001, USER, SPACE, PRODUCT,
                "美式咖啡需求词", "美式咖啡需求词", "全国", 1, NOW, NOW);
        List<GeoRecords.Question> storedQuestions = List.of(
                question(10_002, 10_001, 1, "哪些人适合喝美式咖啡？"),
                question(10_003, 10_001, 2, "什么场景适合选择美式咖啡？"),
                question(10_004, 10_001, 3, "购买美式咖啡时关注什么？"));
        when(geo.topicNameExists(USER, SPACE, "美式咖啡需求词", 0L)).thenReturn(false);
        when(geo.insertTopic(any())).thenReturn(1);
        when(geo.insertQuestion(any())).thenReturn(1);
        when(geo.findTopic(10_001, USER)).thenReturn(Optional.of(storedTopic));
        when(geo.findQuestions(10_001, USER)).thenReturn(storedQuestions);
        when(geo.findLatestCompetitorSet(10_001, USER)).thenReturn(Optional.empty());

        GeoTaskService.PreparedDraft executable = service.materializeDiscoveryDraft(
                USER, SPACE, PRODUCT, discovery.parameters());

        assertThat(executable.parameters().path("mode").asText()).isEqualTo("MONITOR_RUN");
        assertThat(executable.parameters().path("topicId").asText()).isEqualTo("10001");
        assertThat(executable.parameters().path("questionIds"))
                .extracting(JsonNode::textValue).containsExactly("10002", "10003", "10004");
        verify(geo).insertTopic(any());
        verify(geo, org.mockito.Mockito.times(3)).insertQuestion(any());
    }

    @Test
    void rejectsAMatrixThatExceedsTheConfiguredProviderCallBudget() throws Exception {
        arrangeFoundation();
        properties.setGeoMaxProviderCalls(5);

        assertThatThrownBy(() -> service.prepareDraft(USER, SPACE, PRODUCT, parameters("STANDARD")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(com.guanghe.backend.common.api.ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void searchBackedPlatformsReserveSearchAnswerAndRetriesAtQuoteTime() throws Exception {
        arrangeFoundation();
        properties.setGeoConfiguredPlatforms(Set.of("DEEPSEEK", "KIMI"));
        ObjectNode parameters = parameters("STANDARD");
        parameters.withArray("platformCodes").removeAll().add("DEEPSEEK").add("KIMI");
        properties.setGeoMaxProviderCalls(23);
        assertThatThrownBy(() -> service.prepareDraft(USER, SPACE, PRODUCT, parameters))
                .isInstanceOf(BusinessException.class).hasMessageContaining("超过可执行额度");
        assertThatThrownBy(() -> service.quoteNormalized(parameters))
                .isInstanceOf(BusinessException.class);
        properties.setGeoMaxProviderCalls(24);
        assertThat(service.prepareDraft(USER, SPACE, PRODUCT, parameters).quotedUnits()).isEqualTo(6);
        assertThat(service.quoteNormalized(parameters)).isEqualTo(6);
    }

    @Test
    void disabledPlatformRejectsOldNormalizedQuoteAndScheduledSnapshot() throws Exception {
        arrangeFoundation();
        ObjectNode oldParameters = parameters("STANDARD");
        assertThat(service.quoteNormalized(oldParameters)).isEqualTo(6);
        properties.setGeoConfiguredPlatforms(Set.of("DOUBAO"));
        assertThatThrownBy(() -> service.quoteNormalized(oldParameters))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode().name()).isEqualTo("PROVIDER_NOT_CONFIGURED"));
        ObjectNode snapshot = json.createObjectNode();
        snapshot.set("parameters", oldParameters);
        assertThatThrownBy(() -> service.quoteScheduled(snapshot))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode().name()).isEqualTo("PROVIDER_NOT_CONFIGURED"));
        verify(mapper, never()).insertRun(any());
    }

    @Test
    void rejectsTopicProductMismatchAndUnconfiguredPlatformBeforeDraftPersistence() throws Exception {
        arrangeFoundation();
        assertThatThrownBy(() -> service.prepareDraft(USER, SPACE, null, parameters("STANDARD")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode().name())
                                .isEqualTo("RESOURCE_NOT_FOUND"));
        ObjectNode invalid = parameters("STANDARD");
        invalid.withArray("platformCodes").removeAll().add("QWEN");
        assertThatThrownBy(() -> service.prepareDraft(USER, SPACE, PRODUCT, invalid))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode().name())
                                .isEqualTo("PROVIDER_NOT_CONFIGURED"));
    }

    @Test
    void executionContextUsesStoredSnapshotCompletedUnitsAndStableDeadline() throws Exception {
        ObjectNode snapshot = snapshot("STANDARD");
        GeoTaskRecords.Run run = run(snapshot, "RUNNING", 6);
        TaskRecords.Task task = task(6);
        TaskRecords.Execution execution = execution(2, "RUNNING", NOW.plusMinutes(10));
        when(mapper.findRunByTask(TASK)).thenReturn(Optional.of(run));
        when(mapper.findObservationPage(run.id(), 0, 210)).thenReturn(List.of(
                observation(UUID.randomUUID(), "301:DOUBAO:1", "SUCCEEDED", "回答", null)));
        org.mockito.Mockito.clearInvocations(catalog);

        TaskDtos.ExecutionContextResponse context = service.executionContext(task, execution);

        assertThat(context.taskType()).isEqualTo("GEO");
        assertThat(context.provider()).isNull();
        assertThat(context.inputSnapshot().path("parameters").path("topicId").asText()).isEqualTo("201");
        assertThat(context.authorizedContext().path("completedUnitKeys").get(0).asText())
                .isEqualTo("301:DOUBAO:1");
        assertThat(context.budget().maxProviderCalls()).isEqualTo(20);
        assertThat(context.budget().deadlineAt())
                .isEqualTo(run.startedAt().plus(Duration.ofMinutes(30)))
                .isBefore(execution.startedAt().plus(Duration.ofMinutes(30)));
        assertThat(context.authorizedContext().path("monitoringTarget").path("kind").asText())
                .isEqualTo("PRODUCT");
        assertThat(context.authorizedContext().path("monitoringTarget").path("name").asText())
                .isEqualTo("小行星经典美式");
        assertThat(context.authorizedContext().path("monitoringTarget").path("aliases"))
                .isEqualTo(json.createArrayNode());
        org.mockito.Mockito.verifyNoInteractions(catalog);
    }

    @Test
    void executionAndReportUseFrozenCatalogIdentityWithoutReadingCurrentProduct() throws Exception {
        ObjectNode snapshot = snapshot("STANDARD");
        snapshot.withObject("catalogSnapshot").putObject("space").put("id", "101");
        snapshot.withObject("catalogSnapshot").putObject("product")
                .put("id", "102").put("spaceId", "101").put("name", "轻行保温杯-20260909020516")
                .put("category", "饮具").putArray("aliases").add("轻行 T7");
        GeoTaskRecords.Run run = run(snapshot, "PARTIAL", 6);
        when(mapper.findRunByTask(TASK)).thenReturn(Optional.of(run));
        when(mapper.findObservationPage(run.id(), 0, 210)).thenReturn(List.of(
                observation(UUID.randomUUID(), "301:DOUBAO:1", "SUCCEEDED", "轻行 T7", null)));
        org.mockito.Mockito.clearInvocations(catalog);
        TaskDtos.ExecutionContextResponse executionContext = service.executionContext(task(6), execution(1, "RUNNING"));
        assertThat(executionContext.authorizedContext().path("monitoringTarget").path("name").asText())
                .isEqualTo("轻行保温杯-20260909020516");
        assertThat(executionContext.authorizedContext().path("monitoringTarget").path("aliases").get(0).asText())
                .isEqualTo("轻行 T7");
        when(mapper.findReport(701L, USER)).thenReturn(Optional.of(new GeoTaskRecords.Report(
                701, run.id(), TASK, USER, TOPIC, "PARTIAL", "{}", "{}", "系统记录",
                "geo-deterministic-v1", 1, NOW, NOW, 1, 5)));
        when(mapper.findRun(run.id(), USER)).thenReturn(Optional.of(run));
        var result = service.report(new AuthenticatedAccount(USER, "user", AccountRole.USER, 1,
                AccountStatus.ACTIVE, null, null, null), 701L);
        assertThat(result.visibility().target().name()).isEqualTo("轻行保温杯-20260909020516");
        assertThat(result.visibility().platforms().getFirst().mentionRate()).isEqualTo(100.0);
        org.mockito.Mockito.verifyNoInteractions(catalog);
    }

    @Test
    void initialObservationPageHasEmptyAuthoritativeEvidenceWithoutAnyCurrentCatalogRead() throws Exception {
        GeoTaskRecords.Run run = arrangeObservationRead(snapshot("STANDARD"));
        when(mapper.countObservations(run.id())).thenReturn(0L);
        when(mapper.findObservationPage(run.id(), 0, 210)).thenReturn(List.of());
        when(mapper.findObservationPage(run.id(), 0, 20)).thenReturn(List.of());

        GeoTaskDtos.InternalObservationPage page = service.observations(TASK, 1, "token", 1, 20);

        assertThat(page.total()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(page.visibilityEvidence().targetStatus()).isEqualTo("AVAILABLE");
        assertThat(page.visibilityEvidence().matchingVersion()).isEqualTo(GeoVisibilityProjection.METHOD);
        assertThat(page.visibilityEvidence().units()).isEmpty();
        assertObservationReadHasNoBusinessWrites();
    }

    @Test
    void observationEvidenceCoversWholeSavedMatrixAndFullAnswersIndependentOfRequestedPage() throws Exception {
        GeoTaskRecords.Run run = arrangeObservationRead(snapshot("STANDARD"));
        List<GeoTaskRecords.Observation> saved = List.of(
                evidenceObservation(301, "DOUBAO", 1, "SUCCEEDED", "尚未提到目标"),
                evidenceObservation(302, "DEEPSEEK", 1, "SUCCEEDED", "背景".repeat(6000) + "小行星经典美式"),
                evidenceObservation(303, "WENXIN", 1, "FAILED", null),
                evidenceObservation(301, "ZHIPU", 2, "UNKNOWN", "小行星经典美式"),
                evidenceObservation(302, "QWEN", 3, "SUCCEEDED", "   "));
        when(mapper.countObservations(run.id())).thenReturn(5L);
        when(mapper.findObservationPage(run.id(), 0, 210)).thenReturn(saved);
        when(mapper.findObservationPage(run.id(), 1, 1)).thenReturn(List.of(saved.get(1)));

        GeoTaskDtos.InternalObservationPage page = service.observations(TASK, 1, "token", 2, 1);

        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(1);
        assertThat(page.total()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(5);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.platformCode()).isEqualTo("DEEPSEEK");
            assertThat(item.questionId()).isEqualTo("302");
        });
        assertThat(page.visibilityEvidence().units()).containsExactly(
                new GeoTaskDtos.VisibilityEvidenceUnit("301", "DOUBAO", 1, false),
                new GeoTaskDtos.VisibilityEvidenceUnit("302", "DEEPSEEK", 1, true),
                new GeoTaskDtos.VisibilityEvidenceUnit("303", "WENXIN", 1, null),
                new GeoTaskDtos.VisibilityEvidenceUnit("301", "ZHIPU", 2, null),
                new GeoTaskDtos.VisibilityEvidenceUnit("302", "QWEN", 3, null));
        JsonNode serialized = json.valueToTree(page);
        assertThat(serialized.path("visibilityEvidence").path("units").get(2).has("targetMentioned")).isTrue();
        assertThat(serialized.path("visibilityEvidence").path("units").get(2).path("targetMentioned").isNull()).isTrue();
        assertObservationReadHasNoBusinessWrites();
    }

    @Test
    void unknownFrozenTargetDoesNotBecomeUnmentionedOrUseCurrentCatalog() throws Exception {
        ObjectNode snapshot = snapshot("STANDARD");
        snapshot.withObject("catalogSnapshot").putNull("space");
        GeoTaskRecords.Run run = arrangeObservationRead(snapshot);
        List<GeoTaskRecords.Observation> saved = List.of(
                evidenceObservation(301, "DEEPSEEK", 1, "SUCCEEDED", "小行星经典美式"));
        when(mapper.countObservations(run.id())).thenReturn(1L);
        when(mapper.findObservationPage(run.id(), 0, 210)).thenReturn(saved);
        when(mapper.findObservationPage(run.id(), 0, 1)).thenReturn(saved);

        GeoTaskDtos.InternalObservationPage page = service.observations(TASK, 1, "token", 1, 1);

        assertThat(page.visibilityEvidence().targetStatus()).isEqualTo("TARGET_UNRESOLVED");
        assertThat(page.visibilityEvidence().units()).containsExactly(
                new GeoTaskDtos.VisibilityEvidenceUnit("301", "DEEPSEEK", 1, null));
        assertObservationReadHasNoBusinessWrites();
    }

    @ParameterizedTest
    @CsvSource({
            "Starbucks,今天喝 Starbucks 咖啡,true",
            "Starbucks,StarbucksReserve 不是目标名称,false",
            "Starbucks,[门店](https://example.invalid/Starbucks),false",
            "小行星经典美式,今天喝小行星经典美式。,true",
            "小行星经典美式,今天喝美式咖啡。,false"
    })
    void observationEvidenceUsesExistingVisibleNameAndLatinBoundaryRules(
            String name, String answer, boolean expected) throws Exception {
        ObjectNode snapshot = snapshot("STANDARD");
        snapshot.withObject("catalogSnapshot").withObject("product").put("name", name).putArray("aliases");
        GeoTaskRecords.Run run = arrangeObservationRead(snapshot);
        List<GeoTaskRecords.Observation> saved = List.of(
                evidenceObservation(301, "DEEPSEEK", 1, "SUCCEEDED", answer));
        when(mapper.countObservations(run.id())).thenReturn(1L);
        when(mapper.findObservationPage(run.id(), 0, 210)).thenReturn(saved);
        when(mapper.findObservationPage(run.id(), 0, 1)).thenReturn(saved);

        GeoTaskDtos.InternalObservationPage page = service.observations(TASK, 1, "token", 1, 1);

        assertThat(page.visibilityEvidence().units()).singleElement()
                .extracting(GeoTaskDtos.VisibilityEvidenceUnit::targetMentioned).isEqualTo(expected);
        assertObservationReadHasNoBusinessWrites();
    }

    @Test
    void oversizedStoredMatrixFailsClosedInsteadOfSilentlyTruncatingEvidence() throws Exception {
        GeoTaskRecords.Run run = arrangeObservationRead(snapshot("STANDARD"));
        when(mapper.countObservations(run.id())).thenReturn(211L);

        assertThatThrownBy(() -> service.observations(TASK, 1, "token", 1, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode().name()).isEqualTo("TASK_STATE_CONFLICT"));

        verify(mapper, never()).findObservationPage(anyLong(), anyLong(), anyInt());
        assertObservationReadHasNoBusinessWrites();
    }

    @Test
    void newEvidenceRemainsBehindExistingExecutionTokenAndActiveLeaseGuards() throws Exception {
        arrangeObservationRead(snapshot("STANDARD"));
        assertThatThrownBy(() -> service.observations(TASK, 1, "wrong-token", 1, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode().name()).isEqualTo("FORBIDDEN"));
        when(tasks.findLatestExecution(TASK)).thenReturn(Optional.of(execution(1, "FAILED")));
        assertThatThrownBy(() -> service.observations(TASK, 1, "token", 1, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode().name()).isEqualTo("TASK_STATE_CONFLICT"));
        verify(mapper, never()).countObservations(anyLong());
        assertObservationReadHasNoBusinessWrites();
    }

    private GeoTaskRecords.Run arrangeObservationRead(ObjectNode snapshot) {
        GeoTaskRecords.Run run = run(snapshot, "RUNNING", 6);
        when(tasks.findLatestExecution(TASK)).thenReturn(Optional.of(execution(1, "RUNNING")));
        when(tasks.findTaskInternal(TASK)).thenReturn(Optional.of(task(6)));
        when(mapper.findRunByTask(TASK)).thenReturn(Optional.of(run));
        org.mockito.Mockito.clearInvocations(catalog, geo, ids);
        return run;
    }

    private void assertObservationReadHasNoBusinessWrites() {
        org.mockito.Mockito.verifyNoInteractions(catalog, geo, ids);
        verify(mapper, never()).insertRun(any());
        verify(mapper, never()).insertObservation(any());
        verify(mapper, never()).insertCitation(any());
        verify(tasks, never()).freezePoints(anyLong(), anyInt(), any());
    }

    private GeoTaskRecords.Observation evidenceObservation(long questionId, String platform, int sample,
                                                           String status, String answer) {
        String unit = questionId + ":" + platform + ":" + sample;
        return new GeoTaskRecords.Observation(901 + unit.hashCode(), 501, TASK, USER, UUID.randomUUID(),
                "d".repeat(64), unit, sample, questionId, "问题", platform, sample, status, answer,
                "FAILED".equals(status) ? "PROVIDER_TIMEOUT" : null, platform, "model", NOW,
                NOW.plusSeconds(1), 100,
                "{\"inputTokens\":1,\"outputTokens\":1,\"searchRequests\":0,\"monetaryCost\":null,\"currency\":null}",
                1, NOW);
    }

    @Test
    void observationIsIdempotentByUnitAcrossDifferentEventIds() throws Exception {
        ObjectNode snapshot = snapshot("STANDARD");
        GeoTaskRecords.Run run = run(snapshot, "RUNNING", 6);
        TaskRecords.Task task = task(6);
        TaskRecords.Execution execution = execution(1, "RUNNING");
        arrangeActive(run, task, execution);
        when(mapper.insertObservation(any())).thenReturn(1);
        when(mapper.refreshRunCounts(anyLong(), any())).thenReturn(1);
        when(tasks.updateGeoSamplingProgress(eq(TASK), any())).thenReturn(1);
        when(tasks.insertTaskEvent(anyLong(), anyLong(), anyLong(), anyLong(), any(), anyString(),
                any(), any(), anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        when(conversations.nextEventSequence(anyLong(), anyLong())).thenReturn(7L);
        when(conversations.insertEvent(any())).thenReturn(1);
        when(mapper.findRunByTask(TASK)).thenReturn(Optional.of(new GeoTaskRecords.Run(
                run.id(), run.taskId(), run.userId(), run.spaceId(), run.productId(), run.topicId(),
                run.status(), run.sampleMode(), run.expectedUnits(), 1, 0, 5,
                run.contextSnapshotJson(), run.metricVersion(), run.methodVersion(), run.startedAt(),
                run.finishedAt(), run.createdAt(), run.updatedAt(), 6, 0, 0)));
        GeoTaskDtos.ObservationRequest first = observationRequest(UUID.randomUUID());

        assertThat(service.reportObservation(TASK, first).response().disposition()).isEqualTo("CREATED");
        ArgumentCaptor<GeoTaskRecords.Observation> captured = ArgumentCaptor.forClass(
                GeoTaskRecords.Observation.class);
        verify(mapper).insertObservation(captured.capture());
        assertThat(captured.getValue().unitSequence()).isEqualTo(1);
        when(mapper.findObservationByUnit(run.id(), "301:DOUBAO:1"))
                .thenReturn(Optional.of(captured.getValue()));
        GeoTaskDtos.ObservationRequest replay = new GeoTaskDtos.ObservationRequest(
                UUID.randomUUID(), 1, "token", first.observation());

        assertThat(service.reportObservation(TASK, replay).response().disposition()).isEqualTo("DUPLICATE");
        verify(mapper).insertObservation(any());
        verify(tasks).updateGeoSamplingProgress(eq(TASK), any());
        verify(tasks, never()).freezePoints(anyLong(), anyInt(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1,1", "1,2", "3,1", "3,5"})
    void partialCompletionUsesConfirmedUnitPriceDespiteConfigurationChange(
            int confirmedUnitPoints, int currentUnitPoints) throws Exception {
        OffsetDateTime beforeMidnight = OffsetDateTime.parse("2026-09-24T15:59:59Z");
        OffsetDateTime afterMidnight = beforeMidnight.plusSeconds(2);
        AtomicReference<OffsetDateTime> clockTime = new AtomicReference<>(beforeMidnight);
        Clock accountingClock = mock(Clock.class);
        when(accountingClock.instant()).thenAnswer(ignored -> clockTime.get().toInstant());
        service.setAccountingClock(accountingClock);
        var notifications = mock(com.guanghe.backend.task.TaskNotificationService.class);
        service.setTaskNotifications(notifications);
        ObjectNode snapshot = snapshot("STANDARD");
        GeoTaskRecords.Run run = run(snapshot, "RUNNING", 3, 3 * confirmedUnitPoints);
        TaskRecords.Task task = task(3 * confirmedUnitPoints);
        when(tasks.lockPointAccount(USER)).thenAnswer(ignored -> {
            clockTime.set(afterMidnight);
            return Optional.of(new TaskRecords.PointAccount(USER, 99, task.quotedPoints(), 1, NOW, NOW));
        });
        properties.setGeoTestUnitPoints(currentUnitPoints);
        TaskRecords.Execution execution = execution(1, "RUNNING");
        when(tasks.findLatestExecution(TASK)).thenReturn(Optional.of(execution));
        when(tasks.findTaskEventRequestHash(anyLong(), any())).thenReturn(Optional.empty());
        when(tasks.lockTask(TASK)).thenReturn(Optional.of(task));
        when(mapper.lockRunByTask(TASK)).thenReturn(Optional.of(run));
        when(mapper.findObservationPage(run.id(), 0, 210)).thenReturn(List.of(
                observation(UUID.randomUUID(), "301:DOUBAO:1", "SUCCEEDED", "回答", null),
                observation(UUID.randomUUID(), "302:DOUBAO:1", "FAILED", null, "UPSTREAM_TIMEOUT")));
        when(mapper.insertReport(any())).thenReturn(1);
        when(tasks.insertTaskEvent(anyLong(), anyLong(), anyLong(), anyLong(), any(), anyString(),
                any(), any(), anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        when(tasks.completeGeoExecution(anyLong(), anyInt(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString(), any())).thenReturn(1);
        when(tasks.completeGeoTask(anyLong(), anyString(), anyString(), anyInt(), anyString(), any()))
                .thenReturn(1);
        when(mapper.finishRun(anyLong(), anyString(), any())).thenReturn(1);
        when(tasks.settlePoints(eq(USER), eq(confirmedUnitPoints), any())).thenReturn(1);
        when(tasks.releasePoints(eq(USER), eq(2 * confirmedUnitPoints), any())).thenReturn(1);
        when(tasks.insertLedger(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(),
                anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(tasks.finalizeGeoReservation(eq(TASK), eq(confirmedUnitPoints),
                eq(2 * confirmedUnitPoints), eq("PARTIAL"), any()))
                .thenReturn(1);
        when(tasks.findPointAccount(USER)).thenReturn(Optional.of(
                new TaskRecords.PointAccount(USER, 99, 0, 2, NOW, NOW)));
        when(conversations.nextEventSequence(anyLong(), anyLong())).thenReturn(8L);
        when(conversations.insertEvent(any())).thenReturn(1);

        GeoTaskService.CompletionResult result = service.complete(TASK, deterministicCompletionRequest());

        assertThat(result.response().taskStatus()).isEqualTo("PARTIAL");
        assertThat(result.response().settledPoints()).isEqualTo(confirmedUnitPoints);
        assertThat(result.response().releasedPoints()).isEqualTo(2 * confirmedUnitPoints);
        verify(tasks).lockPointAccount(USER);
        verify(accountingClock).instant();
        verify(tasks).settlePoints(USER, confirmedUnitPoints, afterMidnight);
        verify(tasks).releasePoints(USER, 2 * confirmedUnitPoints, afterMidnight);
        verify(tasks).insertLedger(anyLong(), eq(USER), eq(0), eq(-confirmedUnitPoints), anyInt(), anyInt(),
                eq("TASK_SETTLE"), eq(TASK), eq("task-settle:" + TASK), anyString(), eq(afterMidnight));
        verify(tasks).finalizeGeoReservation(eq(TASK), eq(confirmedUnitPoints),
                eq(2 * confirmedUnitPoints), eq("PARTIAL"), any());
        verify(notifications).publishTerminal(TASK, USER);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void successfulSamplesSettleOnceButOnlyModelAnalysisCompletesTheReport(boolean fallback) throws Exception {
        var competitive=mock(GeoCompetitiveService.class);
        service.setCompetitive(new org.springframework.beans.factory.support.StaticListableBeanFactory(java.util.Map.of("competitive",competitive)).getBeanProvider(GeoCompetitiveService.class));
        var notifications = mock(com.guanghe.backend.task.TaskNotificationService.class);
        service.setTaskNotifications(notifications);
        ObjectNode snapshot = snapshot("STANDARD");
        GeoTaskRecords.Run run = run(snapshot, "RUNNING", 2);
        TaskRecords.Task task = task(2);
        when(tasks.lockPointAccount(USER)).thenReturn(Optional.of(
                new TaskRecords.PointAccount(USER, 98, 2, 1, NOW, NOW)));
        TaskRecords.Execution execution = execution(1, "RUNNING");
        GeoTaskDtos.CompletionRequest request = fallback ? deterministicCompletionRequest() : completionRequest();
        String expectedStatus = fallback ? "PARTIAL" : "SUCCEEDED";
        when(tasks.findLatestExecution(TASK)).thenReturn(Optional.of(execution));
        when(tasks.findTaskEventRequestHash(TASK, request.eventId())).thenReturn(Optional.empty());
        when(tasks.lockTask(TASK)).thenReturn(Optional.of(task));
        when(mapper.lockRunByTask(TASK)).thenReturn(Optional.of(run));
        when(mapper.findObservationPage(run.id(), 0, 210)).thenReturn(List.of(
                observation(UUID.randomUUID(), "301:DOUBAO:1", "SUCCEEDED", "回答一", null),
                observation(UUID.randomUUID(), "302:DOUBAO:1", "SUCCEEDED", "回答二", null)));
        when(mapper.insertReport(any())).thenReturn(1);
        when(tasks.insertTaskEvent(anyLong(), anyLong(), anyLong(), anyLong(), any(), anyString(),
                any(), any(), anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        when(tasks.completeGeoExecution(anyLong(), anyInt(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString(), any())).thenReturn(1);
        when(tasks.completeGeoTask(anyLong(), anyString(), anyString(), anyInt(), anyString(), any()))
                .thenReturn(1);
        when(mapper.finishRun(anyLong(), anyString(), any())).thenReturn(1);
        when(tasks.settlePoints(eq(USER), eq(2), any())).thenReturn(1);
        when(tasks.insertLedger(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(),
                anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(tasks.finalizeGeoReservation(eq(TASK), eq(2), eq(0), eq("SETTLED"), any()))
                .thenReturn(1);
        when(tasks.findPointAccount(USER)).thenReturn(Optional.of(
                new TaskRecords.PointAccount(USER, 98, 0, 2, NOW, NOW)));
        when(conversations.nextEventSequence(anyLong(), anyLong())).thenReturn(8L);
        when(conversations.insertEvent(any())).thenReturn(1);

        properties.setGeoTestUnitPoints(7);
        GeoTaskService.CompletionResult result = service.complete(TASK, request);
        verify(competitive).ingest(any(),eq(run),any(),eq(request.synthesis()));

        assertThat(result.response().taskStatus()).isEqualTo(expectedStatus);
        JsonNode browserPayload = json.readTree(result.browserEvent().payloadJson());
        assertThat(browserPayload.path("deliveryKind").asText()).isEqualTo("BACKGROUND_TASK");
        assertThat(browserPayload.path("resultRef").asText())
                .isEqualTo("geo-report:" + result.response().reportId());
        ArgumentCaptor<String> requestHash = ArgumentCaptor.forClass(String.class);
        verify(tasks).insertTaskEvent(anyLong(), anyLong(), anyLong(), anyLong(), eq(request.eventId()),
                requestHash.capture(), any(), any(), anyString(), anyString(), any(), any(),
                anyString(), any());
        GeoTaskRecords.Run completedRun = new GeoTaskRecords.Run(
                run.id(), run.taskId(), run.userId(), run.spaceId(), run.productId(), run.topicId(),
                expectedStatus, run.sampleMode(), run.expectedUnits(), 2, 0, 0,
                run.contextSnapshotJson(), run.metricVersion(), run.methodVersion(), run.startedAt(),
                NOW, run.createdAt(), NOW, run.quotedPoints(), 2, 0);
        GeoTaskRecords.Report report = new GeoTaskRecords.Report(
                Long.parseLong(result.response().reportId()), run.id(), TASK, USER, TOPIC,
                expectedStatus, "{}", "{}", "摘要", request.synthesis().methodVersion(), 2,
                NOW, NOW, 2, 0);
        when(tasks.findTaskEventRequestHash(TASK, request.eventId()))
                .thenReturn(Optional.of(requestHash.getValue()));
        when(mapper.findRunByTask(TASK)).thenReturn(Optional.of(completedRun));
        when(mapper.findReportByRun(run.id())).thenReturn(Optional.of(report));

        properties.setGeoTestUnitPoints(99);
        assertThat(service.complete(TASK, request).response().reportId()).isEqualTo(report.id() + "");
        verify(tasks).settlePoints(eq(USER), eq(2), any());
        verify(tasks).lockPointAccount(USER);
        verify(tasks, never()).releasePoints(anyLong(), anyInt(), any());
        verify(notifications).publishTerminal(TASK, USER);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,0", "5,5", "6,3"})
    void inconsistentFrozenPriceFailsBeforeReportOrLedgerWrite(int taskQuote, int runQuote)
            throws Exception {
        GeoTaskRecords.Run run = run(snapshot("STANDARD"), "RUNNING", 3, runQuote);
        when(tasks.findLatestExecution(TASK)).thenReturn(Optional.of(execution(1, "RUNNING")));
        when(tasks.findTaskEventRequestHash(anyLong(), any())).thenReturn(Optional.empty());
        when(tasks.lockTask(TASK)).thenReturn(Optional.of(task(taskQuote)));
        when(mapper.lockRunByTask(TASK)).thenReturn(Optional.of(run));
        when(mapper.findObservationPage(run.id(), 0, 210)).thenReturn(List.of(
                observation(UUID.randomUUID(), "301:DOUBAO:1", "SUCCEEDED", "回答", null)));

        assertThatThrownBy(() -> service.complete(TASK, deterministicCompletionRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode().name()).isEqualTo("TASK_STATE_CONFLICT"));
        verify(mapper, never()).insertReport(any());
        verify(tasks, never()).settlePoints(anyLong(), anyInt(), any());
        verify(tasks, never()).releasePoints(anyLong(), anyInt(), any());
    }

    @Test
    void staleExecutionTokenCannotWriteObservation() throws Exception {
        GeoTaskRecords.Run run = run(snapshot("STANDARD"), "RUNNING", 6);
        arrangeActive(run, task(6), execution(1, "RUNNING"));
        GeoTaskDtos.ObservationRequest original = observationRequest(UUID.randomUUID());
        GeoTaskDtos.ObservationRequest request = new GeoTaskDtos.ObservationRequest(
                original.eventId(), original.executionNo(), "stale-token", original.observation());

        assertThatThrownBy(() -> service.reportObservation(TASK, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(com.guanghe.backend.common.api.ErrorCode.FORBIDDEN));
        verify(mapper, never()).insertObservation(any());
    }

    @Test
    void invalidObservationNeverPersistsEvidenceOrChangesPoints() throws Exception {
        ObjectNode snapshot = snapshot("STANDARD");
        GeoTaskRecords.Run run = run(snapshot, "RUNNING", 6);
        TaskRecords.Task task = task(6);
        TaskRecords.Execution execution = execution(1, "RUNNING");
        arrangeActive(run, task, execution);
        GeoTaskDtos.ObservationInput invalid = new GeoTaskDtos.ObservationInput(
                "WENXIN", "301", 1, "SUCCEEDED", "回答", List.of(), "DOUBAO", "model",
                NOW, NOW.plusSeconds(1), 100, null,
                new GeoTaskDtos.UsageInput(1, 1, 0, null, null), 1);

        assertThatThrownBy(() -> service.reportObservation(TASK,
                new GeoTaskDtos.ObservationRequest(UUID.randomUUID(), 1, "token", invalid)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode().name())
                                .isEqualTo("VALIDATION_FAILED"));
        verify(mapper, never()).insertObservation(any());
        verify(tasks, never()).settlePoints(anyLong(), anyInt(), any());
    }

    @Test
    void taskProgressUsesPersistedObservationCounts() throws Exception {
        GeoTaskRecords.Run base = run(snapshot("STANDARD"), "RUNNING", 10);
        GeoTaskRecords.Run progress = new GeoTaskRecords.Run(
                base.id(), base.taskId(), base.userId(), base.spaceId(), base.productId(),
                base.topicId(), base.status(), base.sampleMode(), 10, 3, 2, 5,
                base.contextSnapshotJson(), base.metricVersion(), base.methodVersion(),
                base.startedAt(), base.finishedAt(), base.createdAt(), base.updatedAt(),
                base.quotedPoints(), base.settledPoints(), base.releasedPoints());
        when(mapper.findRunByTask(TASK)).thenReturn(Optional.of(progress));

        TaskDtos.TaskProgressResponse result = service.taskProgress(task(10));

        assertThat(result.percent()).isEqualTo(45);
        assertThat(result.completedUnits()).isEqualTo(5);
        assertThat(result.totalUnits()).isEqualTo(10);
    }

    @Test
    void terminalRunProgressDoesNotDependOnStaleTaskStatus() throws Exception {
        GeoTaskRecords.Run completed = new GeoTaskRecords.Run(
                501, TASK, USER, SPACE, PRODUCT, TOPIC, "PARTIAL", "STANDARD",
                10, 8, 2, 0, "{}", "geo-observation-v1", "geo-synthesis-v1",
                NOW, NOW, NOW, NOW, 10, 8, 0);
        when(mapper.findRunByTask(TASK)).thenReturn(Optional.of(completed));

        TaskDtos.TaskProgressResponse result = service.taskProgress(task(10));

        assertThat(result.percent()).isEqualTo(100);
        assertThat(result.completedUnits()).isEqualTo(10);
    }

    @Test
    void runProgressCombinesPersistedCountersWithTaskStageAndReportReference() {
        GeoTaskRecords.Run running = new GeoTaskRecords.Run(
                501, TASK, USER, SPACE, PRODUCT, TOPIC, "RUNNING", "STANDARD",
                10, 3, 1, 6, "{}", "geo-observation-v1", "geo-synthesis-v1",
                NOW, null, NOW, NOW, 10, 0, 0);
        GeoTaskRecords.Report report = new GeoTaskRecords.Report(
                701, running.id(), TASK, USER, TOPIC, "PARTIAL", "{}", "{}", "摘要",
                "geo-synthesis-v1", 4, NOW, NOW, 0, 0);
        AuthenticatedAccount account = new AuthenticatedAccount(
                USER, "user", AccountRole.USER, 1, AccountStatus.ACTIVE, null, null, null);
        when(mapper.findRun(running.id(), USER)).thenReturn(Optional.of(running));
        when(tasks.findTask(TASK, USER)).thenReturn(Optional.of(task(10)));
        when(mapper.findReportByRun(running.id())).thenReturn(Optional.of(report));

        GeoTaskDtos.RunProgressResponse result = service.runProgress(account, running.id());

        assertThat(result.runId()).isEqualTo("501");
        assertThat(result.status()).isEqualTo("RUNNING");
        assertThat(result.stageCode()).isEqualTo("GEO_SAMPLING");
        assertThat(result.percent()).isEqualTo(36);
        assertThat(result.completedUnits()).isEqualTo(4);
        assertThat(result.totalUnits()).isEqualTo(10);
        assertThat(result.reportId()).isEqualTo("701");
        assertThat(result.terminal()).isFalse();
        assertThat(result.retryable()).isFalse();
    }

    private void arrangeFoundation() {
        GeoRecords.Topic topic = new GeoRecords.Topic(TOPIC, USER, SPACE, PRODUCT,
                "品牌诊断", "品牌诊断", "中国大陆", 2, NOW, NOW);
        when(geo.findTopic(TOPIC, USER)).thenReturn(Optional.of(topic));
        when(geo.findQuestions(TOPIC, USER)).thenReturn(List.of(
                question(301, 1), question(302, 2), question(303, 3)));
        when(geo.findLatestCompetitorSet(TOPIC, USER)).thenReturn(Optional.empty());
        when(catalog.create(USER, SPACE, PRODUCT)).thenReturn(
                monitoringCatalog());
    }

    private AuthorizedCatalogContextFactory.Context monitoringCatalog() {
        return new AuthorizedCatalogContextFactory.Context(
                new InternalRunContextDtos.Space(Long.toString(SPACE), "私密测试项目",
                        new InternalRunContextDtos.Brand("小行星咖啡店", List.of("小行星"), null,
                                List.of(), List.of(), List.of(), List.of()), null),
                new InternalRunContextDtos.Product(Long.toString(PRODUCT), Long.toString(SPACE),
                        "小行星经典美式", List.of(), "咖啡", null, List.of(), java.util.Map.of(),
                        List.of(), List.of(), List.of(), null), List.of());
    }

    @Test
    void missingPublicTargetRejectsDiscoveryPreparationBeforeAnyFoundationOrBillingWrites() throws Exception {
        when(catalog.create(USER, SPACE, null)).thenReturn(
                new AuthorizedCatalogContextFactory.Context(null, null, List.of()));
        ObjectNode parameters = (ObjectNode) json.readTree("""
                {"mode":"QUESTION_DISCOVERY","requestText":"看看AI知不知道我","agentSummary":"咖啡店发现",
                 "topic":"咖啡店发现","region":"广州","questions":["广州有哪些咖啡店？","广州哪里适合喝咖啡？","广州有哪些咖啡店适合聊天？"],
                 "platformCodes":["DOUBAO"],"sampleMode":"STANDARD"}
                """);
        assertThatThrownBy(() -> service.prepareDiscoveryDraft(USER, SPACE, null, parameters))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode().name()).isEqualTo("VALIDATION_FAILED");
                    assertThat(exception.details()).containsEntry("field", "geoTarget");
                });
        assertThatThrownBy(() -> service.materializeDiscoveryDraft(USER, SPACE, null, parameters))
                .isInstanceOf(BusinessException.class);
        verify(geo, never()).insertTopic(any());
        verify(geo, never()).insertQuestion(any());
        verify(mapper, never()).insertRun(any());
        verify(tasks, never()).freezePoints(anyLong(), anyInt(), any());
    }

    @Test
    void missingPublicTargetRejectsSingleQuoteAndScheduledQuoteWithoutBackfillingHistoricalEvidence() throws Exception {
        arrangeFoundation();
        ObjectNode saved = service.prepareDraft(USER, SPACE, PRODUCT, parameters("STANDARD")).snapshot();
        when(catalog.create(USER, SPACE, PRODUCT)).thenReturn(
                new AuthorizedCatalogContextFactory.Context(null, null, List.of()));
        assertThatThrownBy(() -> service.prepareDraft(USER, SPACE, PRODUCT, parameters("STANDARD")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.details()).containsEntry("field", "geoTarget"));
        assertThatThrownBy(() -> service.quoteDraft(USER, SPACE, PRODUCT, parameters("STANDARD")))
                .isInstanceOf(BusinessException.class);
        // An old unresolved snapshot is rejected as-is, not repaired using today's catalog.
        saved.withObject("catalogSnapshot").putNull("space");
        assertThatThrownBy(() -> service.quoteScheduled(saved)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.details()).containsEntry("field", "geoTarget"));
        assertThat(saved.path("catalogSnapshot").path("space").isNull()).isTrue();
        verify(mapper, never()).insertRun(any());
    }

    private GeoRecords.Question question(long id, int sequence) {
        return new GeoRecords.Question(id, USER, SPACE, TOPIC, sequence,
                GeoDtos.QuestionCategory.BRAND_DISCOVERY, "问题" + sequence, "问题" + sequence,
                GeoDtos.QuestionSource.MANUAL, true, NOW, NOW);
    }

    private GeoRecords.Question question(long id, long topicId, int sequence, String text) {
        return new GeoRecords.Question(id, USER, SPACE, topicId, sequence,
                GeoDtos.QuestionCategory.BRAND_DISCOVERY, text, text,
                GeoDtos.QuestionSource.MANUAL, true, NOW, NOW);
    }

    private ObjectNode parameters(String mode) throws Exception {
        return (ObjectNode) json.readTree("""
                {"mode":"MONITOR_RUN","topicId":"201","questionIds":["301","302","303"],
                 "platformCodes":["DOUBAO","WENXIN"],"sampleMode":"%s","region":"中国大陆"}
                """.formatted(mode));
    }

    private ObjectNode snapshot(String mode) throws Exception {
        arrangeFoundation();
        return service.prepareDraft(USER, SPACE, PRODUCT, parameters(mode)).snapshot();
    }

    private GeoTaskRecords.Run run(ObjectNode snapshot, String status, int expected) {
        return run(snapshot, status, expected, expected);
    }

    private GeoTaskRecords.Run run(ObjectNode snapshot, String status, int expected, int quote) {
        return new GeoTaskRecords.Run(501, TASK, USER, SPACE, PRODUCT, TOPIC, status, "STANDARD",
                expected, 0, 0, expected, snapshot.toString(), "geo-observation-v1",
                "geo-synthesis-v1", NOW, null, NOW, NOW, quote, 0, 0);
    }

    private TaskRecords.Task task(int quote) {
        return new TaskRecords.Task(TASK, USER, SPACE, PRODUCT, 601, 701, "GEO", "RUNNING",
                "GEO_SAMPLING", 1, "{}", "a".repeat(64), 9001, quote, 0, "{}", null,
                "TASK_CONFIRM", UUID.randomUUID(), "b".repeat(64), NOW, NOW, null, NOW, NOW);
    }

    private TaskRecords.Execution execution(int number, String status) {
        return execution(number, status, NOW);
    }

    private TaskRecords.Execution execution(int number, String status, OffsetDateTime startedAt) {
        return new TaskRecords.Execution(801, TASK, USER, number, status, "geo", "worker", "v1",
                sha256("token"), OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5), "GEO_SAMPLING",
                null, null, null, null, null, null, "{}", null, NOW, startedAt, null, NOW, NOW);
    }

    private GeoTaskRecords.Observation observation(UUID eventId, String unit, String status,
                                                    String answer, String error) {
        return new GeoTaskRecords.Observation(901 + unit.hashCode(), 501, TASK, USER, eventId,
                "d".repeat(64), unit, 1, Long.parseLong(unit.substring(0, 3)), "问题",
                "DOUBAO", 1, status, answer, error, "DOUBAO", "model", NOW,
                NOW.plusSeconds(1), 100,
                "{\"inputTokens\":1,\"outputTokens\":1,\"searchRequests\":0,"
                        + "\"monetaryCost\":null,\"currency\":null}", 1, NOW);
    }

    private void arrangeActive(GeoTaskRecords.Run run, TaskRecords.Task task,
                               TaskRecords.Execution execution) {
        when(tasks.findLatestExecution(TASK)).thenReturn(Optional.of(execution));
        when(tasks.findTaskInternal(TASK)).thenReturn(Optional.of(task));
        when(mapper.lockRunByTask(TASK)).thenReturn(Optional.of(run));
        when(mapper.findObservationByEvent(anyLong(), any())).thenReturn(Optional.empty());
        when(mapper.findObservationByUnit(anyLong(), anyString())).thenReturn(Optional.empty());
    }

    private GeoTaskDtos.ObservationRequest observationRequest(UUID eventId) {
        return new GeoTaskDtos.ObservationRequest(eventId, 1, "token",
                new GeoTaskDtos.ObservationInput("DOUBAO", "301", 1, "SUCCEEDED", "回答",
                        List.of(), "DOUBAO", "model", NOW, NOW.plusSeconds(1), 100, null,
                        new GeoTaskDtos.UsageInput(1, 1, 0, null, null), 1));
    }

    private GeoTaskDtos.CompletionRequest completionRequest() {
        return new GeoTaskDtos.CompletionRequest(UUID.randomUUID(), 1, "token",
                new GeoTaskDtos.SynthesisInput("摘要", List.of(), List.of(), List.of(), List.of("发现"),
                        List.of(new GeoTaskDtos.RecommendationInput("P1", "建议", "依据")),
                        List.of("选题"), List.of("303:DOUBAO:1"), "geo-synthesis-v1"),
                new GeoTaskDtos.SynthesisUsageInput("DEEPSEEK", "deepseek-flash",
                        10, 5, 1, 100));
    }

    private GeoTaskDtos.CompletionRequest deterministicCompletionRequest() {
        return new GeoTaskDtos.CompletionRequest(UUID.randomUUID(), 1, "token",
                new GeoTaskDtos.SynthesisInput("规则兜底摘要", List.of(), List.of(), List.of(),
                        List.of("发现"),
                        List.of(new GeoTaskDtos.RecommendationInput("P1", "建议", "依据")),
                        List.of("选题"), List.of("303:DOUBAO:1"), "geo-deterministic-v1"),
                new GeoTaskDtos.SynthesisUsageInput("SYSTEM", "deterministic-geo-fallback-v1",
                        0, 0, 0, 0));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
