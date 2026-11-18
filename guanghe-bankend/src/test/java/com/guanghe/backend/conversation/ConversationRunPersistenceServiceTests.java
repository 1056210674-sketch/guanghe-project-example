package com.guanghe.backend.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.integration.internal.client.PythonChatRunEvent;
import com.guanghe.backend.task.TaskControlService;
import com.guanghe.backend.conversation.context.ConversationWorkspaceService;
import com.guanghe.backend.conversation.resource.RunResourceService;
import com.guanghe.backend.conversation.resource.RunResourceDtos;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationRunPersistenceServiceTests {

    private final UUID runId = UUID.randomUUID();
    private ConversationMapper mapper;
    private ObjectMapper objectMapper;
    private ConversationRunPersistenceService service;
    private ConversationWorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        mapper = mock(ConversationMapper.class);
        SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new ConversationRunPersistenceService(mapper, idGenerator, objectMapper);
        workspaceService = mock(ConversationWorkspaceService.class);
        service.setWorkspaceService(workspaceService);
        when(mapper.findRun(runId)).thenReturn(Optional.of(run()));
        when(mapper.lockRun(runId)).thenAnswer(ignored -> mapper.findRun(runId));
        when(mapper.advancePythonSequence(eq(runId), anyLong(), any())).thenReturn(1);
        when(mapper.completeRun(eq(runId), anyString(), nullable(String.class), any())).thenReturn(1);
        when(mapper.completeRunFromPython(eq(runId), eq("COMPLETED"), any())).thenReturn("COMPLETED");
        when(mapper.updateAssistantMessage(anyLong(), anyLong(), eq(runId), anyString(), anyString(), any()))
                .thenReturn(1);
        when(mapper.updateAssistantInteraction(anyLong(), anyLong(), eq(runId), anyString(), any()))
                .thenReturn(1);
        when(mapper.updateAssistantStatus(anyLong(), anyLong(), eq(runId), anyString(), any())).thenReturn(1);
        AtomicLong sequence = new AtomicLong();
        when(mapper.nextEventSequence(9001, 41)).thenAnswer(ignored -> sequence.incrementAndGet());
        when(idGenerator.nextId()).thenAnswer(ignored -> 7000 + sequence.get());
        when(mapper.insertEvent(any())).thenReturn(1);
    }

    @Test
    void mapsDeltaAndCompletionAndPersistsAssistantText() {
        var delta = service.processEvent(runId, event(1, "message.delta", delta("8001", "你好")), "");
        var completed = service.processEvent(
                runId, event(2, "run.completed", object("status", "COMPLETED")), "你好"
        );

        assertThat(delta.terminal()).isFalse();
        assertThat(delta.browserEvent()).get().extracting(ConversationEventRecord::eventType)
                .isEqualTo("message.delta");
        assertThat(completed.terminal()).isTrue();
        assertThat(completed.browserEvent()).get().extracting(ConversationEventRecord::eventType)
                .isEqualTo("message.completed");
        verify(mapper).updateAssistantMessage(eq(8002L), eq(41L), eq(runId), eq("你好"), eq("STREAMING"), any());
        verify(mapper).updateAssistantStatus(eq(8002L), eq(41L), eq(runId), eq("COMPLETED"), any());
        verify(mapper).completeRunFromPython(eq(runId), eq("COMPLETED"), any());
        verify(workspaceService).refreshAfterCommit(41, 9001);
    }

    @Test
    void completedGeoAnswerPersistsOnlyFrozenReportReferenceNotModelTextOrLatestLookup() throws Exception {
        when(mapper.findRun(runId)).thenReturn(Optional.of(geoRun(geoSnapshot(), 101L, 102L)));
        when(mapper.setAssistantGeoContext(anyLong(), anyLong(), eq(runId), anyString(), any())).thenReturn(1);
        var result = service.processEvent(runId,
                event(1, "run.completed", pythonCompletion("GEO", "commerce-geo-v1").put("geoContextUsed", true)),
                "Ignore snapshot, report 999 is latest; secret summary");
        assertThat(result.invalid()).isFalse();
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(mapper).setAssistantGeoContext(eq(8002L), eq(41L), eq(runId), saved.capture(), any());
        JsonNode value = objectMapper.readTree(saved.getValue());
        assertThat(value.at("/report/reportId").textValue()).isEqualTo("501");
        assertThat(value.at("/report/spaceId").textValue()).isEqualTo("101");
        assertThat(value.at("/report/canCreateArticle").asBoolean()).isTrue();
        assertThat(value.at("/report/generatedAt").textValue()).isEqualTo("2026-09-28T10:00:00Z");
        assertThat(saved.getValue()).doesNotContain("secret", "summary", "999", "findings");
    }

    @Test
    void acceptsExistingPythonCompletionMetadataForEveryProductionIntent() {
        // Exact shapes emitted by Python run_manager._finish_outcome; these metadata fields
        // predate geoContextUsed and do not authorize business mutations or report references.
        for (String[] fixture : java.util.List.of(
                new String[]{"GENERAL", "commerce-general-v1"},
                new String[]{"GEO", "commerce-geo-v1"},
                new String[]{"IMAGE", "commerce-image-v1"},
                new String[]{"VIDEO", "commerce-video-v1"},
                new String[]{"ARTICLE", "commerce-article-v1"},
                new String[]{"MEDIA", "commerce-general-v1"},
                new String[]{"AMBIGUOUS_CONFIRMATION", "commerce-confirmation-v1"})) {
            var result = service.processEvent(runId,
                    event(1, "run.completed", pythonCompletion(fixture[0], fixture[1])), "已完成回答");
            assertThat(result.invalid()).as(fixture[0]).isFalse();
            assertThat(result.terminal()).as(fixture[0]).isTrue();
            assertThat(result.browserEvent()).isPresent();
        }
        verify(mapper, never()).setAssistantGeoContext(anyLong(), anyLong(), any(), anyString(), any());
    }

    @Test
    void acceptsExistingPythonUserStoppedCompletionWithoutGeoActions() {
        when(mapper.findRun(runId)).thenReturn(Optional.of(geoRun(geoSnapshot(), 101L, 102L)));
        when(mapper.completeRunFromPython(eq(runId), eq("STOPPED"), any())).thenReturn("STOPPED");
        var result = service.processEvent(runId, event(1, "run.completed",
                object("status", "STOPPED").put("reason", "USER_REQUESTED")), "部分回答");
        assertThat(result.invalid()).isFalse();
        assertThat(result.terminal()).isTrue();
        verify(mapper).updateAssistantStatus(eq(8002L), eq(41L), eq(runId), eq("STOPPED"), any());
        verify(mapper, never()).setAssistantGeoContext(anyLong(), anyLong(), any(), anyString(), any());
    }

    @Test
    void malformedCompletionMetadataRemainsRejectedBeforeCompletingTheRun() {
        for (String field : java.util.List.of("intent", "domainGraph", "reason")) {
            for (JsonNode bad : java.util.List.of(objectMapper.nullNode(), objectMapper.createObjectNode(),
                    objectMapper.getNodeFactory().booleanNode(true), objectMapper.getNodeFactory().textNode(""),
                    objectMapper.getNodeFactory().textNode("x".repeat(129)))) {
                ObjectNode payload = pythonCompletion("GENERAL", "commerce-general-v1");
                payload.set(field, bad);
                assertThat(service.processEvent(runId, event(1, "run.completed", payload), "").invalid())
                        .as(field + " / " + bad.getNodeType()).isTrue();
            }
        }
        verify(mapper, never()).completeRunFromPython(any(), anyString(), any());
        verify(mapper, never()).setAssistantGeoContext(anyLong(), anyLong(), any(), anyString(), any());
    }

    private ObjectNode pythonCompletion(String intent, String graph) {
        return object("status", "COMPLETED").put("intent", intent).put("domainGraph", graph);
    }

    @Test
    void planningFailureHasTheSameBusinessOutcomeInStoredMessageAndTerminalEvent() throws Exception {
        enableBusinessOutcome();
        ObjectNode outcome = objectMapper.createObjectNode().put("schemaVersion", 1)
                .put("status", "FAILED").put("reasonCode", "READONLY_PLAN_INVALID").put("failureStage", "PLAN");
        ObjectNode completion = pythonCompletion("GENERAL", "commerce-general-v1");
        completion.set("businessOutcome", outcome);

        var result = service.processEvent(runId, event(1, "run.completed", completion), "资料读取没有完成");

        assertThat(result.invalid()).isFalse();
        JsonNode eventPayload = objectMapper.readTree(result.browserEvent().orElseThrow().payloadJson());
        assertThat(eventPayload.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(eventPayload.get("businessOutcome")).isEqualTo(outcome);
        verify(mapper).setAssistantBusinessOutcome(eq(8002L), eq(41L), eq(runId), eq(outcome.toString()), any());
    }

    @Test
    void runtimeDraftProposalBecomesReadyOnlyAfterJavaStoredAValidDraft() throws Exception {
        enableBusinessOutcome();
        when(mapper.hasReadyRunDraft(eq(runId), eq(41L), eq(9001L), any())).thenReturn(true);
        ObjectNode completion = pythonCompletion("IMAGE", "commerce-image-v1");
        completion.set("businessOutcome", objectMapper.createObjectNode().put("schemaVersion", 1)
                .put("status", "NEEDS_INPUT").put("reasonCode", "TASK_DRAFT_PROPOSED"));

        var result = service.processEvent(runId, event(1, "run.completed", completion), "请确认方案");

        assertThat(objectMapper.readTree(result.browserEvent().orElseThrow().payloadJson())
                .at("/businessOutcome/status").asText()).isEqualTo("DRAFT_READY");
    }

    @Test
    void requiredInputPreservesTheControlledParameterFailureReason() throws Exception {
        enableBusinessOutcome();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(mapper.findMessage(8002L, 41L, 9001L)).thenReturn(Optional.of(new ConversationMessageRecord(
                8002L, 9001L, 41L, "ASSISTANT", "INTERACTION", "{}", null, runId,
                null, null, null, 2L, "COMPLETED", now, now)));
        ObjectNode outcome = objectMapper.createObjectNode().put("schemaVersion", 1)
                .put("status", "NEEDS_INPUT").put("reasonCode", "TASK_PARAMETERS_INVALID")
                .put("failureStage", "TOOL");
        ObjectNode completion = pythonCompletion("IMAGE", "commerce-image-v1");
        completion.set("businessOutcome", outcome);
        var result = service.processEvent(runId, event(1, "run.completed", completion), "请确认创作内容");
        assertThat(objectMapper.readTree(result.browserEvent().orElseThrow().payloadJson())
                .path("businessOutcome")).isEqualTo(outcome);
    }

    @Test
    void declaredReadyCannotCreateSuccessWithoutAStoredJavaDraft() throws Exception {
        enableBusinessOutcome();
        ObjectNode completion = pythonCompletion("IMAGE", "commerce-image-v1");
        completion.set("businessOutcome", objectMapper.createObjectNode().put("schemaVersion", 1)
                .put("status", "DRAFT_READY"));

        var result = service.processEvent(runId, event(1, "run.completed", completion), "fake ready");

        assertThat(objectMapper.readTree(result.browserEvent().orElseThrow().payloadJson())
                .at("/businessOutcome/status").asText()).isEqualTo("FAILED");
    }

    @Test
    void unsupportedBusinessOutcomeFieldsAndReasonCodesRemainProtocolErrors() {
        for (ObjectNode outcome : java.util.List.of(
                objectMapper.createObjectNode().put("schemaVersion", 1).put("status", "ANSWERED")
                        .put("userId", "99"),
                objectMapper.createObjectNode().put("schemaVersion", 1).put("status", "FAILED")
                        .put("reasonCode", "arbitrary upstream text"),
                objectMapper.createObjectNode().put("schemaVersion", 4_294_967_297L).put("status", "ANSWERED"))) {
            ObjectNode completion = pythonCompletion("GENERAL", "commerce-general-v1");
            completion.set("businessOutcome", outcome);
            assertThat(service.processEvent(runId, event(1, "run.completed", completion), "").invalid()).isTrue();
        }
        verify(mapper, never()).completeRunFromPython(any(), anyString(), any());
    }

    @Test
    void legacyRunsDoNotGainInventedBusinessOutcomeWhenConsumerIsUpgraded() {
        service.processEvent(runId, event(1, "run.completed", pythonCompletion("GENERAL", "commerce-general-v1")), "ok");
        verify(mapper, never()).setAssistantBusinessOutcome(anyLong(), anyLong(), any(), anyString(), any());
    }

    @Test
    void javaDraftRejectionAlsoPersistsBusinessFailureBeforeRefreshingWorkspace() throws Exception {
        enableBusinessOutcome();
        var result = service.failRun(runId, "AGENT_RUN_PROTOCOL_ERROR", "");
        assertThat(objectMapper.readTree(result.orElseThrow().payloadJson())
                .at("/businessOutcome/status").asText()).isEqualTo("FAILED");
        InOrder order = inOrder(mapper, workspaceService);
        order.verify(mapper).setAssistantBusinessOutcome(eq(8002L), eq(41L), eq(runId), anyString(), any());
        order.verify(workspaceService).refreshAfterCommit(41, 9001);
    }

    private void enableBusinessOutcome() {
        AgentRunRecord base = run();
        when(mapper.findRun(runId)).thenReturn(Optional.of(new AgentRunRecord(base.id(), base.conversationId(),
                base.userId(), base.requestMessageId(), base.responseMessageId(), base.selectedSpaceId(),
                base.selectedProductId(), base.attachmentAssetIdsJson(), base.status(), base.contextVersion(),
                "{\"protocolFeatures\":{\"businessOutcome\":true,\"resourceLookup\":false}}",
                base.contextRefHash(), base.contextExpiresAt(), base.pythonEventsUrl(), base.lastPythonSequence(),
                base.usageJson(), base.errorCode(), base.traceId(), base.stopRequestedAt(), base.startedAt(),
                base.completedAt(), base.createdAt(), base.updatedAt(), base.deadlineAt())));
        when(mapper.setAssistantBusinessOutcome(anyLong(), anyLong(), eq(runId), anyString(), any())).thenReturn(1);
    }

    @Test
    void resourceInteractionUsesOnlyServerCandidatesAndDoesNotPersistBrowserAvailability() throws Exception {
        RunResourceService resources = resourceRun(true, false);
        ObjectNode server = objectMapper.createObjectNode().put("interactionType", "RESOURCE_SELECTION")
                .put("intent", "GENERAL").put("question", "你要查看哪一个？").put("candidateSetId", "701");
        server.putArray("missingFields");
        server.putArray("choices").add("服务器商品");
        server.putArray("candidates").addObject().put("choiceId", "choice-1").put("name", "服务器商品")
                .put("kind", "PRODUCT").put("resourceId", "102").put("spaceId", "101")
                .put("hasReport", true).put("exact", false);
        AgentRunRecord current = mapper.findRun(runId).orElseThrow();
        when(resources.selectionInteraction(eq(current), eq("701"), eq("你要查看哪一个？"))).thenReturn(server);
        ObjectNode incoming = objectMapper.createObjectNode().put("interactionType", "RESOURCE_SELECTION")
                .put("candidateSetId", "701").put("question", "你要查看哪一个？");

        var result = service.processEvent(runId, event(1, "interaction.required", incoming), "找到多个对象");

        assertThat(result.invalid()).isFalse();
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateAssistantInteraction(eq(8002L), eq(41L), eq(runId), saved.capture(), any());
        JsonNode stored = objectMapper.readTree(saved.getValue());
        assertThat(stored.path("interaction")).isEqualTo(server);
        assertThat(stored.at("/interaction/available").isMissingNode()).isTrue();
        JsonNode browser = objectMapper.readTree(result.browserEvent().orElseThrow().payloadJson());
        assertThat(browser.path("available").asBoolean()).isTrue();
        assertThat(browser.path("messageId").asText()).isEqualTo("8002");
        assertThat(browser.path("candidates")).isEqualTo(server.path("candidates"));
    }

    @Test
    void forgedResourceCandidatesAndFrozenDisabledFeatureAreRejectedBeforePersistence() {
        RunResourceService resources = resourceRun(true, false);
        ObjectNode incoming = objectMapper.createObjectNode().put("interactionType", "RESOURCE_SELECTION")
                .put("candidateSetId", "701").put("question", "选择对象");
        ObjectNode forged = incoming.deepCopy();
        forged.putArray("candidates").addObject().put("resourceId", "999");
        assertThat(service.processEvent(runId, event(1, "interaction.required", forged), "").invalid()).isTrue();
        verifyNoInteractions(resources);
        resourceRun(false, false);
        assertThat(service.processEvent(runId, event(2, "interaction.required", incoming), "").invalid()).isTrue();
        verify(mapper, never()).updateAssistantInteraction(anyLong(), anyLong(), any(), anyString(), any());
    }

    @Test
    void explicitSelectionRequiresSuccessfulReadForThisRunBeforeClaimingAnswered() throws Exception {
        RunResourceService resources = resourceRun(true, true);
        ObjectNode completion = pythonCompletion("GENERAL", "commerce-general-v1");
        completion.set("businessOutcome", objectMapper.createObjectNode().put("schemaVersion", 1)
                .put("status", "ANSWERED"));
        when(resources.hasSuccessfulRead(41L, 9001L, runId, "701", "choice-1")).thenReturn(false, true);

        var rejected = service.processEvent(runId, event(1, "run.completed", completion), "已查看商品");
        JsonNode failed = objectMapper.readTree(rejected.browserEvent().orElseThrow().payloadJson())
                .path("businessOutcome");
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("reasonCode").asText()).isEqualTo("READONLY_EVIDENCE_UNAVAILABLE");
        var accepted = service.processEvent(runId, event(2, "run.completed", completion), "已查看商品");
        assertThat(objectMapper.readTree(accepted.browserEvent().orElseThrow().payloadJson())
                .at("/businessOutcome/status").asText()).isEqualTo("ANSWERED");
        verify(resources, times(2)).hasSuccessfulRead(41L, 9001L, runId, "701", "choice-1");
    }

    private RunResourceService resourceRun(boolean enabled, boolean selected) {
        RunResourceService resources = mock(RunResourceService.class);
        service.setResourceService(resources);
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.putObject("protocolFeatures").put("businessOutcome", true).put("resourceLookup", enabled);
        if (selected) snapshot.putObject("resourceSelection").put("candidateSetId", "701")
                .put("choiceId", "choice-1").put("kind", "PRODUCT").put("resourceId", "102")
                .put("name", "服务器商品").put("spaceId", "101").put("productId", "102");
        when(mapper.findRun(runId)).thenReturn(Optional.of(geoRun(snapshot, 101L, 102L)));
        when(mapper.setAssistantBusinessOutcome(anyLong(), anyLong(), eq(runId), anyString(), any())).thenReturn(1);
        return resources;
    }

    @Test
    void ordinaryChatWithFrozenReportDoesNotReceiveGeoActions() {
        when(mapper.findRun(runId)).thenReturn(Optional.of(geoRun(geoSnapshot(), 101L, 102L)));
        service.processEvent(runId, event(1, "run.completed", object("status", "COMPLETED")), "聊聊咖啡");
        verify(mapper, never()).setAssistantGeoContext(anyLong(), anyLong(), any(), anyString(), any());
    }

    @Test
    void clearOrMismatchedScopeAndLegacyReportCannotYieldActionableMetadata() {
        for (AgentRunRecord current : java.util.List.of(geoRun(geoSnapshot(), null, null),
                geoRun(geoSnapshot(), 101L, 999L))) {
            when(mapper.findRun(runId)).thenReturn(Optional.of(current));
            service.processEvent(runId, event(1, "run.completed",
                    object("status", "COMPLETED").put("geoContextUsed", true)), "");
        }
        ObjectNode legacy = geoSnapshot();
        ((ObjectNode) legacy.at("/geoInsightContext/report")).remove("canCreateArticle");
        when(mapper.findRun(runId)).thenReturn(Optional.of(geoRun(legacy, 101L, 102L)));
        service.processEvent(runId, event(1, "run.completed",
                object("status", "COMPLETED").put("geoContextUsed", true)), "");
        verify(mapper, never()).setAssistantGeoContext(anyLong(), anyLong(), any(), anyString(), any());
    }

    @Test
    void noReportKeepsExistingTopicChoiceWithoutPretendingItIsAMonitoringResult() throws Exception {
        ObjectNode snapshot = geoSnapshot();
        ObjectNode context = (ObjectNode) snapshot.path("geoInsightContext");
        context.put("status", "NO_REPORT").put("selectionSource", "NONE").putNull("report");
        context.withArray("topicChoices").addObject().put("topicId", "502").put("spaceId", "101")
                .put("productId", "102").put("name", "已有专题").put("href", "https://untrusted.invalid");
        when(mapper.findRun(runId)).thenReturn(Optional.of(geoRun(snapshot, 101L, 102L)));
        when(mapper.setAssistantGeoContext(anyLong(), anyLong(), eq(runId), anyString(), any())).thenReturn(1);
        service.processEvent(runId, event(1, "run.completed",
                object("status", "COMPLETED").put("geoContextUsed", true)), "尚无报告");
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(mapper).setAssistantGeoContext(anyLong(), anyLong(), any(), saved.capture(), any());
        JsonNode value = objectMapper.readTree(saved.getValue());
        assertThat(value.path("status").textValue()).isEqualTo("NO_REPORT");
        assertThat(value.path("report").isNull()).isTrue();
        assertThat(value.path("selectionSource").isNull()).isTrue();
        assertThat(value.at("/topicChoices/0/href").textValue()).isEqualTo("/app/geo?topicId=502");
        assertThat(saved.getValue()).doesNotContain("untrusted.invalid");
    }

    @Test
    void stoppedRunAndForgedCompletionFieldsDoNotPersistGeoActions() {
        when(mapper.findRun(runId)).thenReturn(Optional.of(geoRun(geoSnapshot(), 101L, 102L)));
        assertThat(service.processEvent(runId, event(1, "run.completed", object("status", "COMPLETED")
                .put("geoContextUsed", true).put("reportId", "999")), "").invalid()).isTrue();
        assertThat(service.processEvent(runId, event(2, "run.completed", object("status", "COMPLETED")
                .put("geoContextUsed", "true")), "").invalid()).isTrue();
        when(mapper.completeRunFromPython(eq(runId), eq("COMPLETED"), any())).thenReturn("STOPPED");
        service.processEvent(runId, event(3, "run.completed", object("status", "COMPLETED")
                .put("geoContextUsed", true)), "");
        verify(mapper, never()).setAssistantGeoContext(anyLong(), anyLong(), any(), anyString(), any());
    }

    private ObjectNode geoSnapshot() {
        ObjectNode snapshot = objectMapper.createObjectNode();
        ObjectNode context = snapshot.putObject("geoInsightContext");
        context.put("status", "READY").put("selectionSource", "AUTO_SINGLE_TOPIC").put("topicsTruncated", false);
        context.putArray("topicChoices");
        context.putObject("report").put("reportId", "501").put("topicId", "502").put("spaceId", "101")
                .put("productId", "102").put("title", "保温杯").put("generatedAt", "2026-09-28T10:00:00Z")
                .put("canCreateArticle", true).putNull("articleUnavailableReason").put("summary", "secret summary");
        return snapshot;
    }

    @Test
    void actualResourceReadOwnsReportMetadataEvenWhenSidebarRemainsOnAnotherProduct() throws Exception {
        RunResourceService resources = resourceGeoCompletion();
        ObjectNode header = resourceReportHeader();
        var read = resourceRead("901", "701");
        when(resources.currentRunLatestReportReference(any())).thenReturn(new RunResourceService.ActionReportReference(read, header));
        when(resources.isOwnedObservationAvailableForAction(41, 9001, "901", 701)).thenReturn(true);
        service.processEvent(runId, event(1, "run.completed", object("status", "COMPLETED").put("geoContextUsed", true)), "报告事实");
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(mapper).setAssistantGeoContext(anyLong(), anyLong(), any(), saved.capture(), any());
        JsonNode content = objectMapper.readTree(saved.getValue());
        assertThat(content.at("/report/spaceId").asText()).isEqualTo("201");
        assertThat(content.at("/report/reportId").asText()).isEqualTo("701");
        assertThat(content.at("/report/observationId").asText()).isEqualTo("901");
        assertThat(content.at("/report/available").asBoolean()).isTrue();
        assertThat(saved.getValue()).doesNotContain("sourceHash", "private report summary", "501");
    }

    @Test
    void noReportResourceReferencePreventsLegacySnapshotFromRevivingAReport() throws Exception {
        RunResourceService resources = resourceGeoCompletion();
        when(resources.currentRunLatestReportReference(any())).thenReturn(new RunResourceService.ActionReportReference(resourceRead("901", null), null));
        service.processEvent(runId, event(1, "run.completed", object("status", "COMPLETED").put("geoContextUsed", true)), "没有报告");
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(mapper).setAssistantGeoContext(anyLong(), anyLong(), any(), saved.capture(), any());
        assertThat(objectMapper.readTree(saved.getValue()).path("status").asText()).isEqualTo("NO_REPORT");
        assertThat(objectMapper.readTree(saved.getValue()).path("report").isNull()).isTrue();
        verify(resources, never()).isOwnedObservationAvailableForAction(anyLong(), anyLong(), anyString(), anyLong());
    }

    @Test
    void revokedResourceReferenceOmitsActionWithoutRevivingLegacyOrFailingTheReply() {
        RunResourceService resources = resourceGeoCompletion();
        when(resources.currentRunLatestReportReference(any())).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        assertThat(service.processEvent(runId, event(1, "run.completed", object("status", "COMPLETED").put("geoContextUsed", true)), "已保存回答").terminal()).isTrue();
        verify(mapper, never()).setAssistantGeoContext(anyLong(), anyLong(), any(), anyString(), any());
    }

    @Test
    void mismatchedResourceReportHeaderCannotCreateAnAction() {
        RunResourceService resources = resourceGeoCompletion();
        ObjectNode header = resourceReportHeader().put("topicId", "999");
        when(resources.currentRunLatestReportReference(any())).thenReturn(new RunResourceService.ActionReportReference(resourceRead("901", "701"), header));
        service.processEvent(runId, event(1, "run.completed", object("status", "COMPLETED").put("geoContextUsed", true)), "报告事实");
        verify(mapper, never()).setAssistantGeoContext(anyLong(), anyLong(), any(), anyString(), any());
    }

    private RunResourceService resourceGeoCompletion() {
        RunResourceService resources = mock(RunResourceService.class);
        service.setResourceService(resources);
        ObjectNode snapshot = geoSnapshot();
        snapshot.putObject("protocolFeatures").put("resourceLookup", true);
        when(mapper.findRun(runId)).thenReturn(Optional.of(geoRun(snapshot, 101L, 102L)));
        when(mapper.setAssistantGeoContext(anyLong(), anyLong(), eq(runId), anyString(), any())).thenReturn(1);
        return resources;
    }

    private ObjectNode resourceReportHeader() {
        return objectMapper.createObjectNode().put("reportId", "701").put("topicId", "702")
                .put("spaceId", "201").putNull("productId").put("title", "品牌报告")
                .put("generatedAt", "2026-10-05T10:00:00Z").put("canCreateArticle", true)
                .putNull("articleUnavailableReason").put("summary", "private report summary");
    }

    private RunResourceDtos.ReadResponse resourceRead(String observationId, String reportId) {
        return new RunResourceDtos.ReadResponse(observationId, "GEO_TOPIC", "LATEST_GEO_REPORT", "702",
                "品牌专题", "201", null, OffsetDateTime.now(ZoneOffset.UTC), "a".repeat(64), reportId,
                null, objectMapper.createObjectNode(), false);
    }

    private AgentRunRecord geoRun(ObjectNode snapshot, Long space, Long product) {
        AgentRunRecord original = run();
        return new AgentRunRecord(original.id(), original.conversationId(), original.userId(), original.requestMessageId(),
                original.responseMessageId(), space, product, "[]", original.status(), 1, snapshot.toString(),
                original.contextRefHash(), original.contextExpiresAt(), null, 0, "{}", null, null, null,
                original.startedAt(), null, original.createdAt(), original.updatedAt());
    }

    @Test
    void validatesAndPersistsAuthoritativeAgentProgress() throws Exception {
        OffsetDateTime untrustedOccurredAt = OffsetDateTime.parse("2020-01-01T00:00:00Z");
        ObjectNode payload = progress("LOADING_PRODUCT_CONTEXT", "RUNNING", 2, 824);
        payload.put("current", 1);
        payload.put("total", 3);
        PythonChatRunEvent source = new PythonChatRunEvent(
                1, UUID.randomUUID(), "run.progress", runId, untrustedOccurredAt, 1, payload
        );

        var result = service.processEvent(runId, source, "");

        assertThat(result.invalid()).isFalse();
        assertThat(result.terminal()).isFalse();
        ConversationEventRecord stored = result.browserEvent().orElseThrow();
        assertThat(stored.eventType()).isEqualTo("agent.progress");
        assertThat(stored.messageId()).isEqualTo(8002L);
        assertThat(stored.occurredAt()).isNotEqualTo(untrustedOccurredAt);
        JsonNode persisted = objectMapper.readTree(stored.payloadJson());
        assertThat(persisted.path("runId").textValue()).isEqualTo(runId.toString());
        assertThat(persisted.path("messageId").textValue()).isEqualTo("8002");
        assertThat(persisted.path("stageCode").textValue()).isEqualTo("LOADING_PRODUCT_CONTEXT");
        assertThat(persisted.path("current").longValue()).isEqualTo(1);
        assertThat(persisted.path("total").longValue()).isEqualTo(3);
    }

    @Test
    void duplicateAndOverLimitProgressAreDroppedWithoutFailingTheRun() {
        ObjectNode payload = progress("ROUTING_INTENT", "SUCCEEDED", 3, 900);
        ConversationEventRecord existing = progressEvent(payload);
        when(mapper.findAgentProgressByIdentity(runId, "ROUTING_INTENT", "SUCCEEDED", 3))
                .thenReturn(Optional.of(existing));

        var duplicate = service.processEvent(runId, event(1, "run.progress", payload), "");

        assertThat(duplicate.invalid()).isFalse();
        assertThat(duplicate.browserEvent()).isEmpty();
        verify(mapper, never()).countAgentProgress(runId);

        ObjectNode cappedPayload = progress("COMPOSING_RESPONSE", "RUNNING", 4, 1000);
        when(mapper.findAgentProgressByIdentity(runId, "COMPOSING_RESPONSE", "RUNNING", 4))
                .thenReturn(Optional.empty());
        when(mapper.countAgentProgress(runId)).thenReturn(24L);

        var capped = service.processEvent(runId, event(2, "run.progress", cappedPayload), "");

        assertThat(capped.invalid()).isFalse();
        assertThat(capped.browserEvent()).isEmpty();
        verify(mapper, never()).nextEventSequence(9001, 41);
    }

    @Test
    void malformedOrRegressingProgressIsDroppedWithoutBecomingAnInvalidRunEvent() {
        ObjectNode unknown = progress("MODEL_PRIVATE_REASONING", "RUNNING", 1, 20);
        var malformed = service.processEvent(runId, event(1, "run.progress", unknown), "");
        assertThat(malformed.invalid()).isFalse();
        assertThat(malformed.browserEvent()).isEmpty();

        ObjectNode latestPayload = objectMapper.createObjectNode()
                .put("ordinal", 3)
                .put("elapsedMs", 500);
        when(mapper.findLatestAgentProgress(runId)).thenReturn(Optional.of(progressEvent(latestPayload)));
        ObjectNode regressedPayload = progress("COMPOSING_RESPONSE", "RUNNING", 2, 600);
        var regressed = service.processEvent(runId, event(2, "run.progress", regressedPayload), "");

        assertThat(regressed.invalid()).isFalse();
        assertThat(regressed.browserEvent()).isEmpty();
        verify(mapper, never()).insertEvent(any());
    }

    @Test
    void mapsClarificationToInteractionWithoutExposingRunInternals() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("interactionType", "CLARIFICATION");
        payload.put("intent", "IMAGE");
        payload.put("question", "请选择商品");
        payload.putArray("missingFields").add("selectedProductId");
        payload.putArray("choices");

        var result = service.processEvent(runId, event(1, "interaction.required", payload), "需要更多信息");

        assertThat(result.invalid()).isFalse();
        assertThat(result.browserEvent()).get().extracting(ConversationEventRecord::eventType)
                .isEqualTo("interaction.required");
        verify(mapper).updateAssistantInteraction(eq(8002L), eq(41L), eq(runId), any(), any());
    }

    @Test
    void completionAfterClarificationPreservesThePersistedInteractionContent() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("interactionType", "CLARIFICATION");
        payload.put("intent", "IMAGE");
        payload.put("question", "请选择商品");
        payload.putArray("missingFields").add("selectedProductId");
        payload.putArray("choices");
        service.processEvent(runId, event(1, "interaction.required", payload), "需要更多信息");

        service.processEvent(runId, event(2, "run.completed", object("status", "COMPLETED")), "需要更多信息");

        verify(mapper).updateAssistantStatus(eq(8002L), eq(41L), eq(runId), eq("COMPLETED"), any());
        verify(mapper, never()).updateAssistantMessage(
                eq(8002L), eq(41L), eq(runId), eq("需要更多信息"), eq("COMPLETED"), any()
        );
    }

    @Test
    void mapsPythonFailureAndStopToTerminalBrowserEvents() {
        var failed = service.processEvent(
                runId, event(1, "run.failed", failure("MODEL_UNAVAILABLE")), "草稿已经准备好了"
        );
        assertThat(failed.terminal()).isTrue();
        verify(mapper).completeRun(eq(runId), eq("FAILED"), eq("MODEL_UNAVAILABLE"), any());
        verify(mapper).updateAssistantMessage(
                eq(8002L), eq(41L), eq(runId),
                eq("抱歉，本次处理未能完成。请稍后重试，或调整需求后重新发送。"), eq("FAILED"), any()
        );
        assertThat(failed.browserEvent()).get().extracting(ConversationEventRecord::payloadJson)
                .asString().doesNotContain("MODEL_UNAVAILABLE");

        ConversationMapper stoppedMapper = mock(ConversationMapper.class);
        SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
        when(stoppedMapper.lockRun(runId)).thenReturn(Optional.of(run()));
        when(stoppedMapper.advancePythonSequence(eq(runId), eq(1L), any())).thenReturn(1);
        when(stoppedMapper.completeRunFromPython(eq(runId), eq("STOPPED"), any())).thenReturn("STOPPED");
        when(stoppedMapper.updateAssistantStatus(anyLong(), anyLong(), eq(runId), eq("STOPPED"), any()))
                .thenReturn(1);
        when(stoppedMapper.nextEventSequence(9001, 41)).thenReturn(1L);
        when(stoppedMapper.insertEvent(any())).thenReturn(1);
        when(ids.nextId()).thenReturn(7001L);
        ConversationRunPersistenceService stoppedService = new ConversationRunPersistenceService(
                stoppedMapper, ids, objectMapper
        );
        var stopped = stoppedService.processEvent(
                runId, event(1, "run.completed", object("status", "STOPPED")), "部分回答"
        );
        assertThat(stopped.terminal()).isTrue();
        verify(stoppedMapper).completeRunFromPython(eq(runId), eq("STOPPED"), any());
        verify(stoppedMapper).updateAssistantStatus(eq(8002L), eq(41L), eq(runId), eq("STOPPED"), any());
    }

    @Test
    void taskParameterGenerationFailureIsFailedWithSafeMessageNotClarificationOrDraft() {
        TaskControlService taskControl = mock(TaskControlService.class);
        service.setTaskControlService(taskControl);
        var result = service.processEvent(runId,
                event(1, "run.failed", failure("DEEPSEEK_TASK_RESPONSE_INVALID")), "");

        assertThat(result.terminal()).isTrue();
        assertThat(result.invalid()).isFalse();
        assertThat(result.taskDraftRejected()).isFalse();
        verify(mapper).completeRun(eq(runId), eq("FAILED"), eq("DEEPSEEK_TASK_RESPONSE_INVALID"), any());
        ArgumentCaptor<String> displayed = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateAssistantMessage(eq(8002L), eq(41L), eq(runId), displayed.capture(),
                eq("FAILED"), any());
        assertThat(displayed.getValue()).isEqualTo(
                "刚才整理创作方案时出了点问题，尚未创建草稿，也未开始生成。你的要求已保留，可以稍后重试。")
                .doesNotContain("DEEPSEEK", "RESPONSE_INVALID", "traceId");
        ConversationEventRecord event = result.browserEvent().orElseThrow();
        assertThat(event.eventType()).isEqualTo("message.completed");
        assertThat(event.payloadJson()).contains("\"status\":\"FAILED\"")
                .doesNotContain("DEEPSEEK", "RESPONSE_INVALID", "traceId", "interaction", "taskDraft");
        verify(mapper).insertEvent(event);
        verify(mapper, never()).updateAssistantInteraction(anyLong(), anyLong(), any(), anyString(), any());
        verify(mapper, never()).completeRunFromPython(any(), anyString(), any());
        verifyNoInteractions(taskControl);
    }

    @Test
    void truncatedResponseWithoutCommittedTextExplainsLengthLimitAndRemainsFailed() {
        TaskControlService taskControl = mock(TaskControlService.class);
        service.setTaskControlService(taskControl);
        var result = service.processEvent(runId,
                event(1, "run.failed", failure("DEEPSEEK_RESPONSE_TRUNCATED")), "uncommitted preview");

        assertThat(result.terminal()).isTrue();
        assertThat(result.invalid()).isFalse();
        verify(mapper).completeRun(eq(runId), eq("FAILED"), eq("DEEPSEEK_RESPONSE_TRUNCATED"), any());
        verify(mapper).updateAssistantMessage(eq(8002L), eq(41L), eq(runId),
                eq("本次回复达到长度上限，未能完整生成。已有内容和任务记录仍保留。"), eq("FAILED"), any());
        assertThat(result.browserEvent().orElseThrow().payloadJson())
                .contains("\"status\":\"FAILED\"")
                .doesNotContain("DEEPSEEK", "TRUNCATED", "uncommitted preview", "调整需求");
        verify(mapper, never()).completeRunFromPython(any(), anyString(), any());
        verify(mapper, never()).updateAssistantInteraction(anyLong(), anyLong(), any(), anyString(), any());
        verifyNoInteractions(taskControl);
    }

    @Test
    void truncatedResponseWithOnlyWhitespaceAlsoExplainsLengthLimit() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String content = objectMapper.writeValueAsString(object("text", " \n\t"));
        when(mapper.findMessage(8002L, 41L, 9001L)).thenReturn(Optional.of(new ConversationMessageRecord(
                8002, 9001, 41, "ASSISTANT", "TEXT", content, null,
                runId, null, null, null, 2, "STREAMING", now, now)));

        service.processEvent(runId,
                event(1, "run.failed", failure("DEEPSEEK_RESPONSE_TRUNCATED")), "");

        verify(mapper).updateAssistantMessage(eq(8002L), eq(41L), eq(runId),
                eq("本次回复达到长度上限，未能完整生成。已有内容和任务记录仍保留。"), eq("FAILED"), any());
    }

    @Test
    void truncatedResponseKeepsCommittedPartialAnswerAndExistingArticleReference() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ObjectNode content = object("text", "## 已保存的分析\n\n第一部分内容。");
        content.putObject("articlePreparation").put("preparationId", "501").put("articleId", "502");
        when(mapper.findMessage(8002L, 41L, 9001L)).thenReturn(Optional.of(new ConversationMessageRecord(
                8002, 9001, 41, "ASSISTANT", "TEXT", objectMapper.writeValueAsString(content), null,
                runId, null, null, null, 2, "STREAMING", now, now)));

        var result = service.processEvent(runId,
                event(1, "run.failed", failure("DEEPSEEK_RESPONSE_TRUNCATED")), "未提交的正文不应替换已有内容");

        verify(mapper).updateAssistantMessage(eq(8002L), eq(41L), eq(runId),
                eq("## 已保存的分析\n\n第一部分内容。"), eq("FAILED"), any());
        JsonNode payload = objectMapper.readTree(result.browserEvent().orElseThrow().payloadJson());
        assertThat(payload.path("status").textValue()).isEqualTo("FAILED");
        assertThat(payload.path("articlePreparation")).isEqualTo(content.path("articlePreparation"));
        verify(mapper, never()).completeRunFromPython(any(), anyString(), any());
    }

    @Test
    void coordinatorTruncationWithoutCommittedAnswerUsesSameLengthLimitMessage() {
        var event = service.failRun(runId, "DEEPSEEK_RESPONSE_TRUNCATED", "uncommitted preview");

        assertThat(event).isPresent();
        verify(mapper).completeRun(eq(runId), eq("FAILED"), eq("DEEPSEEK_RESPONSE_TRUNCATED"), any());
        verify(mapper).updateAssistantMessage(eq(8002L), eq(41L), eq(runId),
                eq("本次回复达到长度上限，未能完整生成。已有内容和任务记录仍保留。"), eq("FAILED"), any());
        assertThat(event.orElseThrow().payloadJson()).contains("\"status\":\"FAILED\"");
    }

    @Test
    void coordinatorTruncationPreservesCommittedAnswer() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(mapper.findMessage(8002L, 41L, 9001L)).thenReturn(Optional.of(new ConversationMessageRecord(
                8002, 9001, 41, "ASSISTANT", "TEXT", "{\"text\":\"已保存的部分回答\"}", null,
                runId, null, null, null, 2, "STREAMING", now, now)));

        var event = service.failRun(runId, "DEEPSEEK_RESPONSE_TRUNCATED", "");

        assertThat(event).isPresent();
        verify(mapper).updateAssistantMessage(eq(8002L), eq(41L), eq(runId),
                eq("已保存的部分回答"), eq("FAILED"), any());
        assertThat(event.orElseThrow().payloadJson()).contains("\"status\":\"FAILED\"");
    }

    @Test
    void taskDraftProposalIsSchemaCheckedAndExplicitlyRejectedWithoutCreatingATask() {
        ObjectNode proposal = objectMapper.createObjectNode();
        proposal.put("proposalOnly", true);
        proposal.put("taskType", "IMAGE");
        proposal.put("parameterSchemaVersion", 1);
        proposal.putNull("spaceId");
        proposal.putNull("productId");
        proposal.putObject("parameters").put("prompt", "示例");
        proposal.putArray("warnings");

        var result = service.processEvent(
                runId, event(1, "task-draft.proposed", proposal), "任务草稿已经准备好了"
        );

        assertThat(result.terminal()).isTrue();
        assertThat(result.taskDraftRejected()).isTrue();
        verify(mapper).completeRun(eq(runId), eq("FAILED"), eq("TASK_DRAFT_NOT_SUPPORTED"), any());
        verify(mapper).updateAssistantMessage(
                eq(8002L), eq(41L), eq(runId),
                eq("抱歉，本次任务没有创建成功。请检查当前经营空间、商品和需求后重试。"), eq("FAILED"), any()
        );
        assertThat(result.browserEvent()).get()
                .extracting(ConversationEventRecord::payloadJson)
                .asString().doesNotContain("TASK_DRAFT_NOT_SUPPORTED");
    }

    @Test
    void coordinatorFailurePreservesCommittedPartialAnswerInsteadOfInMemoryText() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(mapper.findMessage(8002L, 41L, 9001L)).thenReturn(Optional.of(new ConversationMessageRecord(
                8002, 9001, 41, "ASSISTANT", "TEXT", "{\"text\":\"已保存的部分回答\"}", null,
                runId, null, null, null, 2, "STREAMING", now, now)));
        Optional<ConversationEventRecord> event = service.failRun(
                runId, "AGENT_STREAM_INVALID_RESPONSE", "我已整理好一份待确认草稿"
        );

        assertThat(event).isPresent();
        verify(mapper).completeRun(eq(runId), eq("FAILED"), eq("AGENT_STREAM_INVALID_RESPONSE"), any());
        verify(mapper).updateAssistantMessage(
                eq(8002L), eq(41L), eq(runId),
                eq("已保存的部分回答"), eq("FAILED"), any()
        );
        verify(mapper, never()).updateAssistantStatus(
                eq(8002L), eq(41L), eq(runId), eq("FAILED"), any()
        );
        assertThat(event.orElseThrow().payloadJson())
                .contains("\"status\":\"FAILED\"")
                .doesNotContain("AGENT_STREAM_INVALID_RESPONSE")
                .doesNotContain("待确认草稿");
    }

    @Test
    void enabledPaidTaskControlPlanePersistsDraftWithoutTerminatingTheFreeRun() {
        TaskControlService taskControl = mock(TaskControlService.class);
        service.setTaskControlService(taskControl);
        ConversationEventRecord draftEvent = new ConversationEventRecord(
                7100, 9001, 41, runId, 8002L, null, UUID.randomUUID(), "draft.created", 1,
                "{}", OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC)
        );
        when(taskControl.acceptProposal(any(AgentRunRecord.class), any(), eq("已整理参数"), any())).thenReturn(draftEvent);
        ObjectNode proposal = objectMapper.createObjectNode();
        proposal.put("proposalOnly", true);
        proposal.put("taskType", "IMAGE");
        proposal.put("parameterSchemaVersion", 1);
        proposal.putNull("spaceId");
        proposal.putNull("productId");
        proposal.putObject("parameters");
        proposal.putArray("warnings");

        var result = service.processEvent(runId, event(1, "task-draft.proposed", proposal), "已整理参数");

        assertThat(result.terminal()).isFalse();
        assertThat(result.taskDraftRejected()).isFalse();
        assertThat(result.browserEvent()).contains(draftEvent);
        verify(mapper, never()).completeRun(eq(runId), anyString(), any(), any());
    }

    @Test
    void malformedTaskDraftIsRejectedWithAnExplicitSchemaError() {
        ObjectNode proposal = objectMapper.createObjectNode();
        proposal.put("proposalOnly", true);
        proposal.put("taskType", "IMAGE");
        proposal.put("parameterSchemaVersion", 1);
        proposal.putNull("spaceId");
        proposal.putNull("productId");
        proposal.putObject("parameters");
        proposal.putArray("warnings");
        proposal.put("unexpected", "must-not-pass");

        var result = service.processEvent(runId, event(1, "task-draft.proposed", proposal), "");

        assertThat(result.terminal()).isTrue();
        verify(mapper).completeRun(eq(runId), eq("FAILED"), eq("TASK_DRAFT_SCHEMA_INVALID"), any());
    }

    @Test
    void taskDraftBooleanAndSchemaVersionDoNotAcceptJsonCoercion() {
        ObjectNode proposal = objectMapper.createObjectNode();
        proposal.put("proposalOnly", "true");
        proposal.put("taskType", "IMAGE");
        proposal.put("parameterSchemaVersion", "1");
        proposal.putNull("spaceId");
        proposal.putNull("productId");
        proposal.putObject("parameters");
        proposal.putArray("warnings");

        service.processEvent(runId, event(1, "task-draft.proposed", proposal), "");

        verify(mapper).completeRun(eq(runId), eq("FAILED"), eq("TASK_DRAFT_SCHEMA_INVALID"), any());
    }

    @Test
    void deltaIsPersistedBeforeItCanBePublishedAndRejectsWrongMessageOrOverflow() {
        String nearLimit = "a".repeat(63_999);
        var accepted = service.processEvent(runId, event(1, "message.delta", delta("8001", "b")), nearLimit);

        assertThat(accepted.invalid()).isFalse();
        assertThat(accepted.acceptedDelta()).isEqualTo("b");
        verify(mapper).updateAssistantMessage(
                eq(8002L), eq(41L), eq(runId), eq(nearLimit + "b"), eq("STREAMING"), any()
        );
        assertThat(accepted.browserEvent()).isPresent();
        InOrder persistenceOrder = inOrder(mapper);
        persistenceOrder.verify(mapper).updateAssistantMessage(
                eq(8002L), eq(41L), eq(runId), eq(nearLimit + "b"), eq("STREAMING"), any()
        );
        persistenceOrder.verify(mapper).insertEvent(any());

        var overflow = service.processEvent(runId, event(2, "message.delta", delta("8001", "bc")), nearLimit);
        var wrongMessage = service.processEvent(runId, event(3, "message.delta", delta("9999", "x")), "");

        assertThat(overflow.invalid()).isTrue();
        assertThat(overflow.browserEvent()).isEmpty();
        assertThat(wrongMessage.invalid()).isTrue();
        assertThat(wrongMessage.browserEvent()).isEmpty();
        verify(mapper, times(1)).insertEvent(any());
        verify(mapper, times(1)).updateAssistantMessage(anyLong(), anyLong(), eq(runId), anyString(), anyString(), any());
    }

    @Test
    void replacementClearsOrReplacesOnlyTheOwningResponseAndIsPersistedBeforeReplay() throws Exception {
        var cleared = service.processEvent(runId,
                event(1, "message.delta", delta("8001", "").put("replace", true)), "obsolete preview");
        var replaced = service.processEvent(runId,
                event(2, "message.delta", delta("8001", "new answer").put("replace", true)), "a".repeat(64_000));
        var appended = service.processEvent(runId,
                event(3, "message.delta", delta("8001", "!").put("replace", false)), "new answer");

        assertThat(cleared.invalid()).isFalse();
        assertThat(cleared.acceptedDelta()).isEmpty();
        assertThat(cleared.replacesText()).isTrue();
        assertThat(replaced.invalid()).isFalse();
        assertThat(replaced.replacesText()).isTrue();
        assertThat(appended.invalid()).isFalse();
        assertThat(appended.replacesText()).isFalse();
        JsonNode payload = objectMapper.readTree(cleared.browserEvent().orElseThrow().payloadJson());
        assertThat(payload.path("messageId").textValue()).isEqualTo("8002");
        assertThat(payload.path("delta").textValue()).isEmpty();
        assertThat(payload.path("replace").booleanValue()).isTrue();
        InOrder order = inOrder(mapper);
        order.verify(mapper).updateAssistantMessage(eq(8002L), eq(41L), eq(runId), eq(""), eq("STREAMING"), any());
        order.verify(mapper).insertEvent(any());
        order.verify(mapper).updateAssistantMessage(eq(8002L), eq(41L), eq(runId), eq("new answer"), eq("STREAMING"), any());
        order.verify(mapper).insertEvent(any());
        order.verify(mapper).updateAssistantMessage(eq(8002L), eq(41L), eq(runId), eq("new answer!"), eq("STREAMING"), any());
        order.verify(mapper).insertEvent(any());
    }

    @Test
    void replacementRejectsCoercionUnknownFieldsEmptyAppendOverflowAndOtherMessages() {
        java.util.List<ObjectNode> invalid = java.util.List.of(
                delta("8001", "x").put("replace", "true"),
                delta("8001", "x").put("replace", 1),
                delta("8001", "x").putNull("replace"),
                delta("8001", "x").put("replace", true).put("unknown", true),
                delta("8001", ""),
                delta("8001", "").put("replace", false),
                delta("8001", "x".repeat(16_001)).put("replace", true),
                delta("9999", "").put("replace", true),
                delta("8002", "").put("replace", true),
                objectMapper.createObjectNode().put("replace", true).put("delta", ""),
                objectMapper.createObjectNode().put("replace", true).put("messageId", "8001")
        );
        for (int index = 0; index < invalid.size(); index++) {
            var result = service.processEvent(runId,
                    event(index + 1, "message.delta", invalid.get(index)), "old answer");
            assertThat(result.invalid()).as("payload %s", index).isTrue();
            assertThat(result.browserEvent()).isEmpty();
            assertThat(result.acceptedDelta()).isNull();
        }
        verify(mapper, never()).updateAssistantMessage(anyLong(), anyLong(), any(), anyString(), anyString(), any());
        verify(mapper, never()).insertEvent(any());
    }

    @Test
    void duplicateOrTerminalReplacementCannotClearPersistedText() {
        when(mapper.advancePythonSequence(eq(runId), eq(1L), any())).thenReturn(0);
        var duplicate = service.processEvent(runId,
                event(1, "message.delta", delta("8001", "").put("replace", true)), "retained");
        assertThat(duplicate.invalid()).isTrue();
        when(mapper.findRun(runId)).thenReturn(Optional.of(run("COMPLETED")));
        var late = service.processEvent(runId,
                event(2, "message.delta", delta("8001", "").put("replace", true)), "retained");
        assertThat(late.terminal()).isTrue();
        verify(mapper, never()).updateAssistantMessage(anyLong(), anyLong(), any(), anyString(), anyString(), any());
        verify(mapper, never()).insertEvent(any());
    }

    @Test
    void invalidCompletedStatusAndUnstableFailureCodeAreRejected() {
        var completed = service.processEvent(
                runId, event(1, "run.completed", object("status", "FAILED")), ""
        );
        var failed = service.processEvent(
                runId, event(2, "run.failed", failure("unsafe-code")), ""
        );

        assertThat(completed.invalid()).isTrue();
        assertThat(failed.invalid()).isTrue();
        verify(mapper, never()).completeRunFromPython(any(), anyString(), any());
        verify(mapper, never()).insertEvent(any());
    }

    @Test
    void completionAfterStopRequestLinearizesToStoppedAndCreatesOneTerminalEvent() {
        when(mapper.findRun(runId)).thenReturn(
                Optional.of(run("STOP_REQUESTED")),
                Optional.of(run("STOPPED"))
        );
        when(mapper.completeRunFromPython(eq(runId), eq("COMPLETED"), any())).thenReturn("STOPPED");

        var completed = service.processEvent(
                runId, event(1, "run.completed", object("status", "COMPLETED")), ""
        );
        ConversationRunPersistenceService.StopResult stoppedAgain = service.stopRun(runId);

        assertThat(completed.browserEvent()).get().extracting(ConversationEventRecord::payloadJson)
                .asString().contains("STOPPED");
        assertThat(stoppedAgain.status()).isEqualTo("STOPPED");
        assertThat(stoppedAgain.browserEvent()).isEmpty();
        verify(mapper, times(1)).insertEvent(any());
    }

    @Test
    void skippedOrRepeatedPythonSequenceFailsClosed() {
        when(mapper.advancePythonSequence(eq(runId), eq(3L), any())).thenReturn(0);
        var result = service.processEvent(runId, event(3, "heartbeat", object("status", "RUNNING")), "");
        assertThat(result.invalid()).isTrue();
        assertThat(result.browserEvent()).isEmpty();
    }

    @Test
    void validatesNormalizesAndPersistsReportedUsage() throws Exception {
        ObjectNode usage = validUsage();
        usage.put("providerInputTokens", 120);
        usage.put("providerOutputTokens", 45);
        usage.put("providerLatencyMs", 321);
        usage.put("provider", "LOCAL");
        usage.put("model", "deterministic-test");
        when(mapper.updateRunUsage(eq(runId), anyString(), any())).thenReturn(1);

        var result = service.processEvent(runId, event(1, "usage.reported", usage), "");

        assertThat(result.invalid()).isFalse();
        assertThat(result.browserEvent()).isEmpty();
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateRunUsage(eq(runId), stored.capture(), any());
        var persisted = objectMapper.readTree(stored.getValue());
        assertThat(persisted.size()).isEqualTo(14);
        assertThat(persisted.path("workflowStepCount").intValue()).isEqualTo(3);
        assertThat(persisted.path("steps").intValue()).isEqualTo(3);
        assertThat(persisted.path("modelCallCount").intValue()).isEqualTo(2);
        assertThat(persisted.path("modelCalls").intValue()).isEqualTo(2);
        assertThat(persisted.path("toolCallCount").intValue()).isEqualTo(1);
        assertThat(persisted.path("toolCalls").intValue()).isEqualTo(1);
        assertThat(persisted.path("contextLoadCount").intValue()).isEqualTo(1);
        assertThat(persisted.path("providerCallCount").intValue()).isZero();
        assertThat(persisted.path("estimatedInputTokens").intValue()).isEqualTo(800);
        assertThat(persisted.path("providerInputTokens").intValue()).isEqualTo(120);
        assertThat(persisted.path("providerOutputTokens").intValue()).isEqualTo(45);
        assertThat(persisted.path("providerLatencyMs").intValue()).isEqualTo(321);
        assertThat(persisted.path("provider").textValue()).isEqualTo("LOCAL");
        assertThat(persisted.path("model").textValue()).isEqualTo("deterministic-test");
    }

    @Test
    void usageDefaultsOptionalMetricsAndNullableProviderFields() throws Exception {
        when(mapper.updateRunUsage(eq(runId), anyString(), any())).thenReturn(1);

        var result = service.processEvent(runId, event(1, "usage.reported", validUsage()), "");

        assertThat(result.invalid()).isFalse();
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateRunUsage(eq(runId), stored.capture(), any());
        var persisted = objectMapper.readTree(stored.getValue());
        assertThat(persisted.path("providerInputTokens").intValue()).isZero();
        assertThat(persisted.path("providerOutputTokens").intValue()).isZero();
        assertThat(persisted.path("providerLatencyMs").intValue()).isZero();
        assertThat(persisted.path("provider").isNull()).isTrue();
        assertThat(persisted.path("model").isNull()).isTrue();
    }

    @Test
    void rejectsUnknownNegativeWrongTypeAndInconsistentUsageWithoutPersistingIt() {
        ObjectNode unknown = validUsage().put("unexpected", 1);
        ObjectNode negative = validUsage().put("contextLoadCount", -1);
        ObjectNode wrongType = validUsage().put("providerLatencyMs", "12");
        ObjectNode inconsistent = validUsage().put("steps", 4);

        assertThat(service.processEvent(runId, event(1, "usage.reported", unknown), "").invalid()).isTrue();
        assertThat(service.processEvent(runId, event(2, "usage.reported", negative), "").invalid()).isTrue();
        assertThat(service.processEvent(runId, event(3, "usage.reported", wrongType), "").invalid()).isTrue();
        assertThat(service.processEvent(runId, event(4, "usage.reported", inconsistent), "").invalid()).isTrue();
        verify(mapper, never()).updateRunUsage(any(), anyString(), any());
    }

    @Test
    void explicitFreeRunStopPersistsTheTerminalStateAndBrowserEvent() {
        when(mapper.completeRun(eq(runId), eq("STOPPED"), eq(null), any())).thenReturn(1);

        ConversationRunPersistenceService.StopResult result = service.stopRun(runId);

        assertThat(result.browserEvent()).get().extracting(ConversationEventRecord::eventType)
                .isEqualTo("message.completed");
        assertThat(result.status()).isEqualTo("STOPPED");
        verify(mapper).updateAssistantStatus(eq(8002L), eq(41L), eq(runId), eq("STOPPED"), any());
    }

    @Test
    void staleAndChangedSourceRevisionsExplainSafeRecoveryWithoutExposingCodes() {
        TaskControlService taskControl = mock(TaskControlService.class);
        service.setTaskControlService(taskControl);
        for (String code : java.util.List.of("TASK_DRAFT_REVISION_STALE", "TASK_DRAFT_REVISION_SOURCE_MISMATCH")) {
            org.mockito.Mockito.clearInvocations(mapper);
            org.mockito.Mockito.doThrow(new IllegalArgumentException(code)).when(taskControl)
                    .acceptProposal(any(), any(), anyString(), any());
            var result = service.processEvent(runId,
                    event(1, "task-draft.proposed", objectMapper.createObjectNode()), "");
            assertThat(result.terminal()).isTrue();
            assertThat(result.taskDraftRejected()).isTrue();
            verify(mapper).completeRun(eq(runId), eq("FAILED"), eq(code), any());
            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            verify(mapper).updateAssistantMessage(eq(8002L), eq(41L), eq(runId), message.capture(), eq("FAILED"), any());
            assertThat(message.getValue()).contains("未替换旧方案", "未开始生成")
                    .doesNotContain("TASK_DRAFT", "traceId", "缺少参数");
        }
    }

    @Test
    void stoppedRunAcceptsLateKnownUsageOnceWithoutResurrectingOrPublishing() {
        when(mapper.findRun(runId)).thenReturn(Optional.of(run("STOPPED")));
        when(mapper.advanceTerminalUsageSequence(eq(runId), eq(5L), any())).thenReturn(1, 0);
        when(mapper.updateRunUsage(eq(runId), anyString(), any())).thenReturn(1);
        ObjectNode usage = validUsage().put("providerInputTokens", 120).put("providerOutputTokens", 45);

        var first = service.processEvent(runId, event(5, "usage.reported", usage), "");
        var replay = service.processEvent(runId, event(5, "usage.reported", usage), "");
        var lateDelta = service.processEvent(runId, event(6, "message.delta", delta("8001", "too late")), "");
        assertThat(first.terminal()).isTrue();
        assertThat(first.browserEvent()).isEmpty();
        assertThat(replay.terminal()).isTrue();
        assertThat(lateDelta.terminal()).isTrue();
        verify(mapper).updateRunUsage(eq(runId), anyString(), any());
        verify(mapper, never()).advancePythonSequence(any(), anyLong(), any());
        verify(mapper, never()).completeRun(any(), anyString(), nullable(String.class), any());
        verify(mapper, never()).insertEvent(any());
        verify(mapper, never()).updateAssistantMessage(anyLong(), anyLong(), any(), anyString(), anyString(), any());
    }

    @Test
    void lateInvalidOrForeignUsageCannotAdvanceTerminalSequence() {
        when(mapper.findRun(runId)).thenReturn(Optional.of(run("FAILED")));
        var foreign = new PythonChatRunEvent(1, UUID.randomUUID(), "usage.reported", UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC), 1, validUsage());
        assertThat(service.processEvent(runId, foreign, "").invalid()).isTrue();
        assertThat(service.processEvent(runId, event(2, "usage.reported", validUsage().put("steps", -1)), "")
                .invalid()).isTrue();
        verify(mapper, never()).advanceTerminalUsageSequence(any(), anyLong(), any());
        verify(mapper, never()).updateRunUsage(any(), anyString(), any());
    }

    @Test
    void expiredRunBecomesFailedWithoutLosingCommittedPartialAnswer() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC);
        when(mapper.lockExpiredRun(cutoff)).thenReturn(Optional.of(run()));
        when(mapper.findMessage(8002, 41, 9001)).thenReturn(Optional.of(new ConversationMessageRecord(
                8002, 9001, 41, "ASSISTANT", "TEXT", "{\"text\":\"已完成的部分\"}",
                null, runId, null, null, null, 2, "STREAMING", cutoff, cutoff)));
        assertThat(service.expireOneRun(cutoff)).isPresent();
        verify(mapper).completeRun(eq(runId), eq("FAILED"), eq("AGENT_RUN_DEADLINE_EXCEEDED"), any());
        verify(mapper).updateAssistantMessage(eq(8002L), eq(41L), eq(runId), eq("已完成的部分"), eq("FAILED"), any());
    }

    @Test
    void expiredStopRequestFinalizesStoppedInsteadOfFailed() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC);
        when(mapper.lockExpiredRun(cutoff)).thenReturn(Optional.of(run("STOP_REQUESTED")));
        when(mapper.findRun(runId)).thenReturn(Optional.of(run("STOP_REQUESTED")));
        assertThat(service.expireOneRun(cutoff)).isPresent();
        verify(mapper).completeRun(eq(runId), eq("STOPPED"), eq(null), any());
    }

    private PythonChatRunEvent event(long sequence, String type, ObjectNode payload) {
        return new PythonChatRunEvent(
                1, UUID.randomUUID(), type, runId, OffsetDateTime.now(ZoneOffset.UTC), sequence, payload
        );
    }

    private ObjectNode object(String name, String value) {
        return objectMapper.createObjectNode().put(name, value);
    }

    private ObjectNode delta(String messageId, String value) {
        return objectMapper.createObjectNode().put("messageId", messageId).put("delta", value);
    }

    private ObjectNode failure(String code) {
        return objectMapper.createObjectNode().put("status", "FAILED").put("code", code);
    }

    private ObjectNode progress(String stageCode, String status, int ordinal, long elapsedMs) {
        return objectMapper.createObjectNode()
                .put("stageCode", stageCode)
                .put("status", status)
                .put("ordinal", ordinal)
                .put("elapsedMs", elapsedMs)
                .putNull("current")
                .putNull("total");
    }

    private ConversationEventRecord progressEvent(JsonNode payload) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new ConversationEventRecord(
                7000, 9001, 41, runId, 8002L, null, UUID.randomUUID(), "agent.progress", 1,
                payload.toString(), now, now
        );
    }

    private ObjectNode validUsage() {
        ObjectNode usage = objectMapper.createObjectNode();
        usage.put("workflowStepCount", 3);
        usage.put("modelCallCount", 2);
        usage.put("contextLoadCount", 1);
        usage.put("toolCallCount", 1);
        usage.put("providerCallCount", 0);
        usage.put("modelCalls", 2);
        usage.put("toolCalls", 1);
        usage.put("steps", 3);
        usage.put("estimatedInputTokens", 800);
        return usage;
    }

    private AgentRunRecord run() {
        return run("RUNNING");
    }

    private AgentRunRecord run(String status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AgentRunRecord(
                runId, 9001, 41, 8001, 8002L, null, null, "[]", status, 1,
                "{}", "a".repeat(64), now.plusMinutes(2), null, 0, "{}", null, null, null,
                now, null, now, now
        );
    }
}
