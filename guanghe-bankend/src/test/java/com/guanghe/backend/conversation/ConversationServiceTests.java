package com.guanghe.backend.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.asset.AssetMapper;
import com.guanghe.backend.asset.AssetIngestionProperties;
import com.guanghe.backend.asset.AssetRecords;
import com.guanghe.backend.agentplatform.AgentPlatformService;
import com.guanghe.backend.agentplatform.AgentPlatformDtos;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import com.guanghe.backend.conversation.context.ConversationWorkspaceService;
import com.guanghe.backend.conversation.context.RunContextSnapshotFactory;
import com.guanghe.backend.conversation.resource.RunResourceDtos;
import com.guanghe.backend.conversation.resource.RunResourceService;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTests {

    private static final AuthenticatedAccount USER = account(41, AccountRole.USER);
    private static final AuthenticatedAccount ADMIN = account(1, AccountRole.ADMIN);
    private static final long CONVERSATION_ID = 9001;

    private ConversationMapper mapper;
    private SnowflakeIdGenerator idGenerator;
    private RunContextSnapshotFactory contextSnapshotFactory;
    private AuthorizedCatalogContextFactory catalogContextFactory;
    private ConversationWorkspaceService workspaceService;
    private ConversationService service;
    private AssetMapper assetMapper;
    private ConversationProperties properties;

    @Test
    void creationBindsOwnedInstallationAndProjectWithoutChangingTemporarySelection() {
        var platform = mock(AgentPlatformService.class);
        service.setAgentPlatformService(platform);
        var identity = new AgentPlatformDtos.AgentIdentity("5001", "geo", "1.0.0", "GEO 智能体",
                "6001", List.of("chat", "geo.monitor"));
        when(idGenerator.nextId()).thenReturn(CONVERSATION_ID);
        when(platform.identityOf(USER.id(), CONVERSATION_ID)).thenReturn(identity);
        var created = service.create(USER, new ConversationDtos.CreateRequest("品牌分析", "5001", "6001"));
        verify(platform).bindConversation(USER.id(), CONVERSATION_ID, 5001L, 6001L);
        assertThat(created.agentIdentity()).isEqualTo(identity);
        assertThat(created.selectedSpaceId()).isNull();
        assertThat(created.selectedProductId()).isNull();
    }

    @Test
    void removedInstallationDeniesNewRunBeforeMessagesOrQuotaAreWritten() {
        var platform = mock(AgentPlatformService.class);
        service.setAgentPlatformService(platform);
        when(platform.assertRunAllowed(USER.id(), CONVERSATION_ID))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        UUID client = UUID.randomUUID(), key = UUID.randomUUID();
        assertThatThrownBy(() -> service.submitMessage(USER, CONVERSATION_ID, key.toString(),
                request(client, null, null, List.of())))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).insertUserMessage(any());
        verify(mapper, never()).insertRun(any());
        verify(mapper, never()).insertAdmission(any(), anyLong(), any(), anyBoolean());
    }

    @Test
    void summaryCannotAttachGeoOrCatalogInputsEvenWithOwnedResources() {
        var platform = mock(AgentPlatformService.class);
        service.setAgentPlatformService(platform);
        when(platform.assertRunAllowed(USER.id(), CONVERSATION_ID)).thenReturn(
                new AgentPlatformDtos.AgentIdentity("5002", "summary", "1.0.0", "摘要助手",
                        null, List.of("chat", "text.summarize")));
        assertThatThrownBy(() -> service.submitMessage(USER, CONVERSATION_ID, UUID.randomUUID().toString(),
                request(UUID.randomUUID(), "2001", null, List.of())))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).insertUserMessage(any());
        verify(contextSnapshotFactory, never()).create(any(), anyLong(), anyLong(), any(), any(), any());
    }

    @BeforeEach
    void setUp() {
        mapper = mock(ConversationMapper.class);
        idGenerator = mock(SnowflakeIdGenerator.class);
        contextSnapshotFactory = mock(RunContextSnapshotFactory.class);
        catalogContextFactory = mock(AuthorizedCatalogContextFactory.class);
        workspaceService = mock(ConversationWorkspaceService.class);
        assetMapper = mock(AssetMapper.class);
        properties = new ConversationProperties();
        service = new ConversationService(
                mapper, idGenerator, new ObjectMapper(), properties, contextSnapshotFactory,
                catalogContextFactory
        );
        service.setWorkspaceService(workspaceService);
        service.setAssetMapper(assetMapper);
        service.setAssetProperties(new AssetIngestionProperties());
        when(mapper.lockAdmissionAccount(USER.id(), USER.tokenVersion())).thenReturn(USER.id());
        when(mapper.admissionUsage(eq(USER.id()), any(), any()))
                .thenReturn(new ConversationQuotaUsage(0, 0, 0));
        when(mapper.insertAdmission(any(), eq(USER.id()), any(), anyBoolean())).thenReturn(1);
        when(mapper.findConversation(CONVERSATION_ID, USER.id())).thenReturn(Optional.of(conversation()));
        when(mapper.lockConversation(CONVERSATION_ID, USER.id()))
                .thenAnswer(ignored -> mapper.findConversation(CONVERSATION_ID, USER.id()));
        when(contextSnapshotFactory.create(any(UUID.class), eq(CONVERSATION_ID), eq(USER.id()),
                any(), any(), any(ConversationMessageRecord.class)))
                .thenAnswer(invocation -> new InternalRunContextDtos.RunContext(
                        invocation.<UUID>getArgument(0).toString(), Long.toString(CONVERSATION_ID), 1,
                        null, null, List.of(), List.of()
                ));
        when(contextSnapshotFactory.create(any(UUID.class), eq(CONVERSATION_ID), eq(USER.id()),
                any(), org.mockito.ArgumentMatchers.isNull(),
                any(), any(ConversationMessageRecord.class)))
                .thenAnswer(invocation -> new InternalRunContextDtos.RunContext(
                        invocation.<UUID>getArgument(0).toString(), Long.toString(CONVERSATION_ID), 1,
                        null, null, List.of(), invocation.getArgument(5)
                ));
    }

    @Test
    void selectedResourceIsTemporaryAndItsReplayPrecedesCandidateExpiryValidation() throws Exception {
        var resources = mock(RunResourceService.class);
        service.setResourceService(resources);
        properties.setResourceLookupEnabled(true);
        UUID client = UUID.randomUUID(), key = UUID.randomUUID();
        prepareNewSubmission(client, key);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        when(mapper.findConversation(CONVERSATION_ID, USER.id())).thenReturn(Optional.of(new ConversationRecord(
                CONVERSATION_ID, USER.id(), "会话", 2001L, 3001L, 1, null, now, now)));
        var selection = new RunResourceDtos.Selection("GEO_TOPIC", 7001, "另一个专题", 2002, null, "6001", "topic-2");
        when(resources.validateSelection(USER.id(), CONVERSATION_ID, "6001", "topic-2", "INHERIT", 2001L, 3001L))
                .thenReturn(selection);
        when(contextSnapshotFactory.createWithResourceSelection(any(), eq(CONVERSATION_ID), eq(USER.id()),
                eq(2002L), org.mockito.ArgumentMatchers.isNull(), any(), eq(selection), eq(2001L), eq(3001L)))
                .thenAnswer(invocation -> new InternalRunContextDtos.RunContext(invocation.<UUID>getArgument(0).toString(),
                        Long.toString(CONVERSATION_ID), 1, null, null, List.of(), List.of()));
        var request = new ConversationDtos.SendMessageRequest(client, "看看这个", null, null, null, null, null,
                List.of(), ConversationDtos.ContextSelectionMode.INHERIT, null,
                new ConversationDtos.ResourceSelection("6001", "topic-2"));
        var accepted = service.submitMessage(USER, CONVERSATION_ID, key.toString(), request);
        var run = ArgumentCaptor.forClass(AgentRunRecord.class);
        verify(mapper).insertRun(run.capture());
        assertThat(run.getValue().selectedSpaceId()).isEqualTo(2002L);
        verify(mapper).updateConversationSelection(eq(CONVERSATION_ID), eq(USER.id()), eq(2001L), eq(3001L), any());
        var userMessage = ArgumentCaptor.forClass(ConversationMessageRecord.class);
        verify(mapper).insertUserMessage(userMessage.capture());
        assertThat(new ObjectMapper().readTree(userMessage.getValue().contentJson())
                .at("/resourceSelection/candidateSetId").asText()).isEqualTo("6001");
        when(mapper.findSubmissionByIdempotencyKey(USER.id(), CONVERSATION_ID, key)).thenReturn(Optional.of(
                new MessageSubmissionRecord(101L, UUID.fromString(accepted.response().runId()), client, key,
                        userMessage.getValue().requestFingerprint(), "COMPLETED")));
        assertThat(service.submitMessage(USER, CONVERSATION_ID, key.toString(), request).newSubmission()).isFalse();
        verify(resources).validateSelection(USER.id(), CONVERSATION_ID, "6001", "topic-2", "INHERIT", 2001L, 3001L);
        var changed = new ConversationDtos.SendMessageRequest(client, "看看这个", null, null, null, null, null,
                List.of(), ConversationDtos.ContextSelectionMode.INHERIT, null,
                new ConversationDtos.ResourceSelection("6001", "topic-1"));
        assertThatThrownBy(() -> service.submitMessage(USER, CONVERSATION_ID, key.toString(), changed))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void resourceChoicesCannotMixWithAttachmentsReportsClearOrForgedReplyTargets() {
        var resources = mock(RunResourceService.class);
        service.setResourceService(resources);
        properties.setResourceLookupEnabled(true);
        var selection = new ConversationDtos.ResourceSelection("6001", "second");
        for (var request : List.of(
                new ConversationDtos.SendMessageRequest(UUID.randomUUID(), "看这个", null, null, null, null, null,
                        List.of(), ConversationDtos.ContextSelectionMode.CLEAR, null, selection),
                new ConversationDtos.SendMessageRequest(UUID.randomUUID(), "看这个", null, null, null, null, null,
                        List.of(), ConversationDtos.ContextSelectionMode.INHERIT, "7001", selection),
                new ConversationDtos.SendMessageRequest(UUID.randomUUID(), "看这个", null, null, null, null, null,
                        List.of(new ConversationDtos.AttachmentRequest("8001")), ConversationDtos.ContextSelectionMode.INHERIT,
                        null, selection))) {
            assertCode(request, ErrorCode.VALIDATION_FAILED);
        }
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        when(mapper.findMessage(100L, USER.id(), CONVERSATION_ID)).thenReturn(Optional.of(new ConversationMessageRecord(
                100L, CONVERSATION_ID, USER.id(), "ASSISTANT", "TEXT", "{\"text\":\"旧回复\"}", null,
                UUID.randomUUID(), null, null, null, 1L, "COMPLETED", now, now)));
        assertCode(new ConversationDtos.SendMessageRequest(UUID.randomUUID(), "看这个", "100", null, null,
                null, null, List.of(), ConversationDtos.ContextSelectionMode.INHERIT, null, selection), ErrorCode.VALIDATION_FAILED);
        verify(mapper, never()).insertRun(any());
        org.mockito.Mockito.verifyNoInteractions(resources);
    }

    @Test
    void clearPersistsAResourceResetBarrierEvenWhileResourceCapabilityIsOff() throws Exception {
        UUID client = UUID.randomUUID(), key = UUID.randomUUID();
        prepareNewSubmission(client, key);
        service.submitMessage(USER, CONVERSATION_ID, key.toString(), new ConversationDtos.SendMessageRequest(
                client, "先清空", null, null, null, null, null, List.of(), ConversationDtos.ContextSelectionMode.CLEAR));
        var userMessage = ArgumentCaptor.forClass(ConversationMessageRecord.class);
        verify(mapper).insertUserMessage(userMessage.capture());
        assertThat(new ObjectMapper().readTree(userMessage.getValue().contentJson()).path("resourceContextReset").asBoolean())
                .isTrue();
    }

    @Test
    void userCanCreateListGetUpdateAndHardDeleteConversations() {
        when(idGenerator.nextId()).thenReturn(CONVERSATION_ID);
        when(mapper.findPage(USER.id(), 0, 20)).thenReturn(List.of(conversation()));
        when(mapper.countByUserId(USER.id())).thenReturn(1L);
        when(mapper.updateConversation(eq(CONVERSATION_ID), eq(USER.id()), eq(1), eq("新标题"), any()))
                .thenReturn(1);
        when(mapper.deleteConversation(CONVERSATION_ID, USER.id())).thenReturn(1);

        ConversationDtos.Response created = service.create(USER, new ConversationDtos.CreateRequest("  新会话  "));
        assertThat(created.id()).isEqualTo(Long.toString(CONVERSATION_ID));
        assertThat(created.title()).isEqualTo("新会话");
        assertThat(service.list(USER, 1, 20).items()).hasSize(1);
        assertThat(service.get(USER, CONVERSATION_ID).id()).isEqualTo(Long.toString(CONVERSATION_ID));
        assertThat(service.update(
                USER, CONVERSATION_ID, new ConversationDtos.UpdateRequest(1, "  新标题 ")
        ).version()).isEqualTo(2);
        service.delete(USER, CONVERSATION_ID);

        verify(mapper).insertConversation(any());
        verify(mapper).deleteConversation(CONVERSATION_ID, USER.id());
        verify(workspaceService).invalidateAfterCommit(USER.id(), CONVERSATION_ID);
    }

    @Test
    void identicalIdempotencyReplayReturnsSameIdsAndDoesNotCreateAnotherRun() {
        String traceId = "0123456789abcdef0123456789abcdef";
        MDC.put("traceId", traceId);
        UUID clientMessageId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        when(mapper.findSubmissionByIdempotencyKey(USER.id(), CONVERSATION_ID, idempotencyKey))
                .thenReturn(Optional.empty());
        when(mapper.findSubmissionByClientMessageId(USER.id(), CONVERSATION_ID, clientMessageId))
                .thenReturn(Optional.empty());
        when(mapper.findActiveRun(USER.id(), CONVERSATION_ID)).thenReturn(Optional.empty());
        when(mapper.nextMessageSequence(CONVERSATION_ID, USER.id())).thenReturn(1L, 2L);
        when(idGenerator.nextId()).thenReturn(101L, 102L);
        when(mapper.insertUserMessage(any())).thenReturn(1);
        when(mapper.insertRun(any())).thenReturn(1);
        when(mapper.insertAssistantMessage(any())).thenReturn(1);
        ConversationDtos.SendMessageRequest request = request(clientMessageId, null, null, List.of());

        ConversationService.MessageSubmission first = service.submitMessage(
                USER, CONVERSATION_ID, idempotencyKey.toString(), request
        );
        ArgumentCaptor<AgentRunRecord> runCaptor = ArgumentCaptor.forClass(AgentRunRecord.class);
        verify(mapper).insertRun(runCaptor.capture());
        AgentRunRecord createdRun = runCaptor.getValue();
        assertThat(createdRun.contextSnapshotJson()).contains(
                createdRun.id().toString(), Long.toString(CONVERSATION_ID)
        );
        assertThat(createdRun.traceId()).isEqualTo(traceId);
        String fingerprint = capturedFingerprint();
        when(mapper.findSubmissionByIdempotencyKey(USER.id(), CONVERSATION_ID, idempotencyKey))
                .thenReturn(Optional.of(new MessageSubmissionRecord(
                        101L, createdRun.id(), clientMessageId, idempotencyKey,
                        fingerprint, "QUEUED"
                )));

        ConversationService.MessageSubmission replay = service.submitMessage(
                USER, CONVERSATION_ID, idempotencyKey.toString(), request
        );

        assertThat(first.newSubmission()).isTrue();
        assertThat(replay.newSubmission()).isFalse();
        assertThat(replay.response().messageId()).isEqualTo(first.response().messageId());
        assertThat(replay.response().runId()).isEqualTo(first.response().runId());
        verify(mapper).insertRun(any());
        verify(mapper).insertAdmission(eq(createdRun.id()), eq(USER.id()), any(), eq(false));
        verify(mapper).admissionUsage(eq(USER.id()), any(), any());
        assertThat(createdRun.deadlineAt()).isEqualTo(createdRun.createdAt().plus(properties.getRunDeadline()));
        MDC.remove("traceId");
    }

    @Test
    void accountQuotaRejectsNewRunsWithoutCreatingMessages() {
        when(mapper.countActiveRuns(USER.id())).thenReturn(2L);
        assertCode(request(UUID.randomUUID(), null, null, List.of()), ErrorCode.CHAT_CONCURRENCY_LIMIT);
        when(mapper.countActiveRuns(USER.id())).thenReturn(0L);
        when(mapper.admissionUsage(eq(USER.id()), any(), any())).thenReturn(new ConversationQuotaUsage(10, 20, 0));
        assertCode(request(UUID.randomUUID(), null, null, List.of()), ErrorCode.CHAT_MINUTE_LIMIT);
        when(mapper.admissionUsage(eq(USER.id()), any(), any())).thenReturn(new ConversationQuotaUsage(0, 200, 0));
        assertCode(request(UUID.randomUUID(), null, null, List.of()), ErrorCode.CHAT_DAILY_LIMIT);
        verify(mapper, never()).insertRun(any());
        verify(mapper, never()).insertUserMessage(any());
        verify(mapper, never()).insertAdmission(any(), anyLong(), any(), anyBoolean());
    }

    @Test
    void explicitGeoReportIsBoundToStoredRunAndChangingItCannotReplayTheSameMessage() throws Exception {
        UUID client = UUID.randomUUID(); UUID key = UUID.randomUUID();
        prepareNewSubmission(client,key);
        when(contextSnapshotFactory.create(any(UUID.class),eq(CONVERSATION_ID),eq(USER.id()),
                eq(2001L),eq(3001L),any(ConversationMessageRecord.class),eq(7001L)))
                .thenAnswer(invocation -> {
                    var codec = new ObjectMapper();
                    var base = new InternalRunContextDtos.RunContext(invocation.<UUID>getArgument(0).toString(),
                            Long.toString(CONVERSATION_ID),1,null,null,List.of(),List.of());
                    var value = (com.fasterxml.jackson.databind.node.ObjectNode)codec.valueToTree(base);
                    value.putObject("geoInsightContext").put("status","READY").putObject("report").put("reportId","7001");
                    return codec.treeToValue(value,InternalRunContextDtos.RunContext.class);
                });
        var request = new ConversationDtos.SendMessageRequest(client,"看看报告",null,null,null,"2001","3001",
                List.of(),ConversationDtos.ContextSelectionMode.REPLACE,"7001");
        var first = service.submitMessage(USER,CONVERSATION_ID,key.toString(),request);
        var captor = ArgumentCaptor.forClass(AgentRunRecord.class); verify(mapper).insertRun(captor.capture());
        assertThat(new ObjectMapper().readTree(captor.getValue().contextSnapshotJson())
                .at("/geoInsightContext/report/reportId").asText()).isEqualTo("7001");
        String fingerprint = capturedFingerprint();
        when(mapper.findSubmissionByIdempotencyKey(USER.id(),CONVERSATION_ID,key)).thenReturn(Optional.of(
                new MessageSubmissionRecord(101L,UUID.fromString(first.response().runId()),client,key,fingerprint,"QUEUED")));
        assertThat(service.submitMessage(USER,CONVERSATION_ID,key.toString(),request).newSubmission()).isFalse();
        for (String report : java.util.Arrays.asList("7002",null)) {
            var changed = new ConversationDtos.SendMessageRequest(client,"看看报告",null,null,null,"2001","3001",
                    List.of(),ConversationDtos.ContextSelectionMode.REPLACE,report);
            assertThatThrownBy(() -> service.submitMessage(USER,CONVERSATION_ID,key.toString(),changed))
                    .isInstanceOfSatisfying(BusinessException.class,error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        }
        verify(mapper).insertRun(any());
    }

    @Test
    void mismatchedReportNeverAdmitsRunOrChangesSidebar() {
        UUID client=UUID.randomUUID(); UUID key=UUID.randomUUID(); prepareNewSubmission(client,key);
        when(contextSnapshotFactory.create(any(UUID.class),eq(CONVERSATION_ID),eq(USER.id()),
                eq(2001L),eq(3001L),any(ConversationMessageRecord.class),eq(7001L)))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        assertCode(new ConversationDtos.SendMessageRequest(client,"看看报告",null,null,null,"2001","3001",
                List.of(),ConversationDtos.ContextSelectionMode.REPLACE,"7001"),ErrorCode.RESOURCE_NOT_FOUND);
        verify(mapper,never()).insertRun(any());
        verify(mapper,never()).insertAdmission(any(),anyLong(),any(),anyBoolean());
        verify(mapper,never()).updateConversationSelection(anyLong(),anyLong(),any(),any(),any());
    }

    @Test
    void reportAndCurrentAttachmentsCannotBeMixed() {
        assertValidation(new ConversationDtos.SendMessageRequest(UUID.randomUUID(),"看看报告",null,null,null,"2001","3001",
                List.of(new ConversationDtos.AttachmentRequest("123")),ConversationDtos.ContextSelectionMode.REPLACE,"7001"));
        verify(mapper,never()).insertUserMessage(any());
        verify(mapper,never()).insertRun(any());
    }

    @Test
    void visionQuotaDoesNotPreventTextTurnsAndDayUsesShanghaiCalendar() {
        service.setClock(Clock.fixed(Instant.parse("2026-09-24T16:00:05Z"), ZoneOffset.UTC));
        when(mapper.admissionUsage(eq(USER.id()), any(), any())).thenReturn(new ConversationQuotaUsage(0, 40, 40));
        UUID clientMessageId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        prepareNewSubmission(clientMessageId, key);
        service.submitMessage(USER, CONVERSATION_ID, key.toString(), request(clientMessageId, null, null, List.of()));
        verify(mapper).admissionUsage(USER.id(), OffsetDateTime.parse("2026-09-24T15:59:05Z"),
                OffsetDateTime.parse("2026-09-25T00:00:00+08:00"));
        verify(mapper).insertAdmission(any(), eq(USER.id()), eq(OffsetDateTime.parse("2026-09-24T16:00:05Z")), eq(false));
    }

    @Test
    void visionQuotaIsChargedOnlyForAuthorizedCurrentMessageImages() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(assetMapper.findAsset(123, USER.id())).thenReturn(Optional.of(new AssetRecords.Asset(
                123, USER.id(), 4001, null, "IMAGE", "UPLOADED", null, "main.png",
                "users/41/assets/123.png", "image/png", 512, "a".repeat(64),
                64, 64, null, "READY", "APPROVED", now, now)));
        when(mapper.admissionUsage(eq(USER.id()), any(), any())).thenReturn(new ConversationQuotaUsage(0, 40, 40));
        assertCode(request(UUID.randomUUID(), null, null, List.of(new ConversationDtos.AttachmentRequest("123"))),
                ErrorCode.CHAT_VISION_DAILY_LIMIT);
        verify(mapper, never()).insertRun(any());
    }

    @Test
    void staleLoginGenerationCannotAdmitEvenAnIdempotentReplay() {
        when(mapper.lockAdmissionAccount(USER.id(), USER.tokenVersion())).thenReturn(null);
        assertCode(request(UUID.randomUUID(), null, null, List.of()), ErrorCode.UNAUTHENTICATED);
        verify(mapper, never()).lockConversation(anyLong(), anyLong());
        verify(mapper, never()).insertAdmission(any(), anyLong(), any(), anyBoolean());
    }

    @Test
    void firstUserMessageReplacesOnlyTheDefaultConversationTitle() {
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        when(mapper.findConversation(CONVERSATION_ID, USER.id())).thenReturn(Optional.of(new ConversationRecord(
                CONVERSATION_ID, USER.id(), "新会话", null, null, 1, null, createdAt, createdAt
        )));
        UUID clientMessageId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        when(mapper.findSubmissionByIdempotencyKey(USER.id(), CONVERSATION_ID, idempotencyKey))
                .thenReturn(Optional.empty());
        when(mapper.findSubmissionByClientMessageId(USER.id(), CONVERSATION_ID, clientMessageId))
                .thenReturn(Optional.empty());
        when(mapper.findActiveRun(USER.id(), CONVERSATION_ID)).thenReturn(Optional.empty());
        when(mapper.nextMessageSequence(CONVERSATION_ID, USER.id())).thenReturn(1L, 2L);
        when(idGenerator.nextId()).thenReturn(101L, 102L);
        when(mapper.insertUserMessage(any())).thenReturn(1);
        when(mapper.insertRun(any())).thenReturn(1);
        when(mapper.insertAssistantMessage(any())).thenReturn(1);
        ConversationDtos.SendMessageRequest request = new ConversationDtos.SendMessageRequest(
                clientMessageId, "  为当前商品创建一篇\n咖啡文章  ", null, null, null, null, null, List.of()
        );

        service.submitMessage(USER, CONVERSATION_ID, idempotencyKey.toString(), request);

        verify(mapper).replaceDefaultTitleFromFirstMessage(
                eq(CONVERSATION_ID), eq(USER.id()), eq("新会话"), eq("为当前商品创建一篇 咖啡文章"), any()
        );
    }

    @Test
    void reusedIdempotencyKeyWithDifferentRequestIsRejected() {
        UUID clientMessageId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        when(mapper.findSubmissionByIdempotencyKey(USER.id(), CONVERSATION_ID, idempotencyKey))
                .thenReturn(Optional.of(new MessageSubmissionRecord(
                        101L, UUID.randomUUID(), clientMessageId, idempotencyKey,
                        "0".repeat(64), "QUEUED"
                )));

        assertThatThrownBy(() -> service.submitMessage(
                USER, CONVERSATION_ID, idempotencyKey.toString(),
                request(clientMessageId, null, null, List.of())
        )).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        verify(mapper, never()).insertRun(any());
    }

    @Test
    void legacyFingerprintWithoutSelectionOrAttachmentsStillReplaysAfterUpgrade() {
        UUID clientMessageId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ConversationDtos.SendMessageRequest request = request(clientMessageId, null, null, List.of());
        when(mapper.findSubmissionByIdempotencyKey(USER.id(), CONVERSATION_ID, idempotencyKey))
                .thenReturn(Optional.of(new MessageSubmissionRecord(
                        101L, runId, clientMessageId, idempotencyKey,
                        legacyFingerprint(clientMessageId, "hello", null, null, null, List.of()),
                        "COMPLETED"
                )));

        ConversationService.MessageSubmission replay = service.submitMessage(
                USER, CONVERSATION_ID, idempotencyKey.toString(), request
        );

        assertThat(replay.newSubmission()).isFalse();
        assertThat(replay.response().runId()).isEqualTo(runId.toString());
        verify(mapper, never()).insertUserMessage(any());
        verify(mapper, never()).insertRun(any());
    }

    @Test
    void legacyFingerprintWithSelectionAndAttachmentsStillReplaysAfterUpgrade() {
        UUID clientMessageId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        List<ConversationDtos.AttachmentRequest> attachments = List.of(
                new ConversationDtos.AttachmentRequest("123"),
                new ConversationDtos.AttachmentRequest("456")
        );
        ConversationDtos.SendMessageRequest request = request(
                clientMessageId, "2001", "3001", attachments
        );
        when(mapper.findSubmissionByIdempotencyKey(USER.id(), CONVERSATION_ID, idempotencyKey))
                .thenReturn(Optional.of(new MessageSubmissionRecord(
                        101L, runId, clientMessageId, idempotencyKey,
                        legacyFingerprint(clientMessageId, "hello", null, 2001L, 3001L,
                                List.of("123", "456")),
                        "QUEUED"
                )));

        ConversationService.MessageSubmission replay = service.submitMessage(
                USER, CONVERSATION_ID, idempotencyKey.toString(), request
        );

        assertThat(replay.newSubmission()).isFalse();
        assertThat(replay.response().runId()).isEqualTo(runId.toString());
        verify(assetMapper, never()).findAsset(anyLong(), anyLong());
        verify(mapper, never()).insertUserMessage(any());
    }

    @Test
    void selectedSpaceAndProductMustBeOwnedAndMatch() {
        long spaceId = 2001;
        long productId = 3001;
        when(mapper.nextMessageSequence(CONVERSATION_ID, USER.id())).thenReturn(1L);
        when(mapper.insertUserMessage(any())).thenReturn(1);
        when(contextSnapshotFactory.create(
                any(UUID.class), eq(CONVERSATION_ID), eq(USER.id()), eq(spaceId), eq(productId), any()
        )).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        assertThatThrownBy(() -> service.submitMessage(
                USER, CONVERSATION_ID, UUID.randomUUID().toString(),
                request(UUID.randomUUID(), Long.toString(spaceId), Long.toString(productId), List.of())
        )).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));

        when(contextSnapshotFactory.create(
                any(UUID.class), eq(CONVERSATION_ID), eq(USER.id()), eq(spaceId), eq(null), any()
        )).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> service.submitMessage(
                USER, CONVERSATION_ID, UUID.randomUUID().toString(),
                request(UUID.randomUUID(), Long.toString(spaceId), null, List.of())
        )).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verify(mapper, never()).insertRun(any());
    }

    @Test
    void omittedSelectionInheritsPersistedConversationContext() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(mapper.findConversation(CONVERSATION_ID, USER.id())).thenReturn(Optional.of(new ConversationRecord(
                CONVERSATION_ID, USER.id(), "会话", 2001L, 3001L, 1, null, now, now
        )));
        UUID clientMessageId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        prepareNewSubmission(clientMessageId, idempotencyKey);

        service.submitMessage(USER, CONVERSATION_ID, idempotencyKey.toString(),
                request(clientMessageId, null, null, List.of()));

        verify(contextSnapshotFactory).create(
                any(UUID.class), eq(CONVERSATION_ID), eq(USER.id()), eq(2001L), eq(3001L), any()
        );
        verify(mapper).updateConversationSelection(
                eq(CONVERSATION_ID), eq(USER.id()), eq(2001L), eq(3001L), any()
        );
    }

    @Test
    void selectionIsClearedOnlyWhenExplicitlyRequested() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(mapper.findConversation(CONVERSATION_ID, USER.id())).thenReturn(Optional.of(new ConversationRecord(
                CONVERSATION_ID, USER.id(), "会话", 2001L, 3001L, 1, null, now, now
        )));
        UUID clientMessageId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        prepareNewSubmission(clientMessageId, idempotencyKey);
        ConversationDtos.SendMessageRequest request = new ConversationDtos.SendMessageRequest(
                clientMessageId, "清空上下文", null, null, null, null, null, List.of(),
                ConversationDtos.ContextSelectionMode.CLEAR
        );

        service.submitMessage(USER, CONVERSATION_ID, idempotencyKey.toString(), request);

        verify(mapper).updateConversationSelection(
                eq(CONVERSATION_ID), eq(USER.id()), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), any()
        );
    }

    @Test
    void explicitSelectionModeRejectsContradictoryIdsBeforePersistence() {
        ConversationDtos.SendMessageRequest inheritedWithIds = new ConversationDtos.SendMessageRequest(
                UUID.randomUUID(), "hello", null, null, null, "2001", "3001", List.of(),
                ConversationDtos.ContextSelectionMode.INHERIT
        );
        ConversationDtos.SendMessageRequest replacementWithoutSpace = new ConversationDtos.SendMessageRequest(
                UUID.randomUUID(), "hello", null, null, null, null, null, List.of(),
                ConversationDtos.ContextSelectionMode.REPLACE
        );

        assertValidation(inheritedWithIds);
        assertValidation(replacementWithoutSpace);

        verify(mapper, never()).insertUserMessage(any());
        verify(mapper, never()).insertRun(any());
    }

    @Test
    void activeConversationCannotBeHardDeleted() {
        when(mapper.findActiveRun(USER.id(), CONVERSATION_ID)).thenReturn(Optional.of(mock(AgentRunRecord.class)));

        assertThatThrownBy(() -> service.delete(USER, CONVERSATION_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_IN_USE));

        verify(mapper, never()).deleteConversation(anyLong(), anyLong());
    }

    @Test
    void conversationBoundToAConfirmedPaidTaskCannotBeDeleted() {
        when(mapper.findActiveRun(USER.id(), CONVERSATION_ID)).thenReturn(Optional.empty());
        when(mapper.countPaidTasks(CONVERSATION_ID, USER.id())).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(USER, CONVERSATION_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_IN_USE));

        verify(mapper, never()).deleteConversation(anyLong(), anyLong());
    }

    @Test
    void retainedMonitorRecordsRefuseDeletionWithoutRequiringMonitorExecutionBean() {
        when(mapper.hasMonitorExecutionRecords(CONVERSATION_ID, USER.id())).thenReturn(true);
        assertThatThrownBy(() -> service.delete(USER, CONVERSATION_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_IN_USE));
        verify(mapper, never()).deleteConversation(anyLong(), anyLong());
        verify(workspaceService, never()).invalidateAfterCommit(anyLong(), anyLong());
    }

    @Test
    void monitorDeletionGuardRetainsRevokedGrantsAndWaitInputAdmissionsWithOwnerFiltering() throws Exception {
        var statement=ConversationMapper.class.getMethod("hasMonitorExecutionRecords",long.class,long.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class);
        var sql=String.join(" ",statement.value());
        assertThat(sql).contains("business.geo_monitor_execution_grant", "business.geo_monitor_execution_admission", "UNION ALL");
        assertThat(sql).doesNotContain("status", "ACTIVE", "PENDING");
        assertThat(sql.split("user_id=",-1)).hasSize(3);
        assertThat(sql.split("conversation_id=",-1)).hasSize(3);
    }

    @Test
    void freeConversationWithNoMonitorRecordsStillDeletesNormally() {
        when(mapper.hasMonitorExecutionRecords(CONVERSATION_ID, USER.id())).thenReturn(false);
        when(mapper.deleteConversation(CONVERSATION_ID, USER.id())).thenReturn(1);
        service.delete(USER, CONVERSATION_ID);
        verify(mapper).hasMonitorExecutionRecords(CONVERSATION_ID, USER.id());
        verify(mapper).deleteConversation(CONVERSATION_ID, USER.id());
        verify(workspaceService).invalidateAfterCommit(USER.id(), CONVERSATION_ID);
    }

    @Test
    void unknownAttachmentIsRejectedAndPaidContinuationRemainsUnavailable() {
        ConversationDtos.AttachmentRequest attachment = new ConversationDtos.AttachmentRequest("123");
        assertCode(request(UUID.randomUUID(), null, null, List.of(attachment)),
                ErrorCode.REFERENCE_ASSET_NOT_OWNED);
        ConversationDtos.SendMessageRequest paid = new ConversationDtos.SendMessageRequest(
                UUID.randomUUID(), "hello", null, "draft", null, null, null, List.of()
        );
        assertValidation(paid);
        verify(mapper, never()).insertRun(any());
    }

    @Test
    void currentMessageImageIsTheRunSourceButDoesNotClearPersistedProductContext() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(mapper.findConversation(CONVERSATION_ID, USER.id())).thenReturn(Optional.of(new ConversationRecord(
                CONVERSATION_ID, USER.id(), "会话", 2001L, 3001L, 1, null, now, now
        )));
        when(assetMapper.findAsset(123, USER.id())).thenReturn(Optional.of(new AssetRecords.Asset(
                123, USER.id(), 4001, null, "IMAGE", "UPLOADED", null, "main.png",
                "users/41/assets/123.png", "image/png", 512, "a".repeat(64),
                64, 64, null, "READY", "APPROVED", now, now)));
        UUID clientMessageId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        prepareNewSubmission(clientMessageId, idempotencyKey);
        when(mapper.insertMessageAsset(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyInt(), any()))
                .thenReturn(1);

        var accepted = service.submitMessage(USER, CONVERSATION_ID, idempotencyKey.toString(),
                request(clientMessageId, null, null,
                        List.of(new ConversationDtos.AttachmentRequest("123"))));

        assertThat(accepted.selectedSpaceId()).isNull();
        assertThat(accepted.selectedProductId()).isNull();
        assertThat(accepted.attachmentAssetIds()).containsExactly("123");

        ArgumentCaptor<AgentRunRecord> run = ArgumentCaptor.forClass(AgentRunRecord.class);
        verify(mapper).insertRun(run.capture());
        assertThat(run.getValue().selectedSpaceId()).isEqualTo(4001);
        assertThat(run.getValue().selectedProductId()).isNull();
        assertThat(run.getValue().attachmentAssetIdsJson()).isEqualTo("[\"123\"]");
        assertThat(run.getValue().contextSnapshotJson()).contains("attachmentAssetIds", "123")
                .contains("\"space\":null", "\"product\":null");
        verify(mapper).insertMessageAsset(eq(101L), eq(123L), eq(USER.id()), eq(CONVERSATION_ID),
                eq(4001L), eq(1), any());
        verify(catalogContextFactory).create(USER.id(), 2001L, 3001L);
        verify(mapper).updateConversationSelection(
                eq(CONVERSATION_ID), eq(USER.id()), eq(2001L), eq(3001L), any()
        );
    }

    @Test
    void adminAndOtherUsersCannotReadConversationData() {
        assertThatThrownBy(() -> service.get(ADMIN, CONVERSATION_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        when(mapper.findConversation(CONVERSATION_ID, 99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(account(99, AccountRole.USER), CONVERSATION_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void snapshotReturnsLatestChronologicalPageAndMatchingEventWatermark() throws Exception {
        UUID runId = UUID.randomUUID();
        when(mapper.findMessagesBefore(USER.id(), CONVERSATION_ID, Long.MAX_VALUE, 3))
                .thenReturn(List.of(message(12, "STREAMING"), message(11, "COMPLETED"), message(10, "COMPLETED")));
        when(mapper.eventHighWatermark(CONVERSATION_ID, USER.id())).thenReturn(45L);
        when(mapper.findActiveRun(USER.id(), CONVERSATION_ID)).thenReturn(Optional.of(run(runId, "RUNNING")));
        ConversationDtos.Snapshot snapshot = service.snapshot(USER, CONVERSATION_ID, 2);
        assertThat(snapshot.messages()).extracting(ConversationDtos.MessageResponse::sequence).containsExactly(11L, 12L);
        assertThat(snapshot.messages().getLast().content().path("text").asText()).isEqualTo("部分回答");
        assertThat(snapshot.highWatermark()).isEqualTo(45);
        assertThat(snapshot.nextBeforeSequence()).isEqualTo("11");
        assertThat(snapshot.activeRun().runId()).isEqualTo(runId.toString());
        assertThat(ConversationService.class.getMethod("snapshot", AuthenticatedAccount.class, long.class, int.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class).isolation())
                .isEqualTo(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ);
        verify(mapper, never()).insertRun(any());
    }

    @Test
    void resourceCardAvailabilityIsRecomputedFromCurrentOwnedSearchWithoutMutatingStoredContent() {
        RunResourceService resources = mock(RunResourceService.class);
        service.setResourceService(resources);
        properties.setResourceLookupEnabled(true);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String stored = "{\"text\":\"请选择\",\"interaction\":{\"interactionType\":\"RESOURCE_SELECTION\","
                + "\"candidateSetId\":\"6001\",\"question\":\"查看哪一个？\",\"candidates\":[]}}";
        ConversationMessageRecord card = new ConversationMessageRecord(12, CONVERSATION_ID, USER.id(),
                "ASSISTANT", "INTERACTION", stored, null, null, null, null, null, 12, "COMPLETED", now, now);
        when(mapper.findMessagesBefore(USER.id(), CONVERSATION_ID, Long.MAX_VALUE, 3)).thenReturn(List.of(card));
        RunResourceDtos.SearchResponse pending = new RunResourceDtos.SearchResponse("6001", "GEO_TOPIC", "MULTIPLE",
                2, true, List.of(), now.plusHours(24));
        when(resources.pendingSearch(USER.id(), CONVERSATION_ID, null, null)).thenReturn(pending, null);
        assertThat(service.snapshot(USER, CONVERSATION_ID, 2).messages().getFirst()
                .content().at("/interaction/available").asBoolean()).isTrue();
        assertThat(service.snapshot(USER, CONVERSATION_ID, 2).messages().getFirst()
                .content().at("/interaction/available").asBoolean()).isFalse();
        assertThat(card.contentJson()).isEqualTo(stored);
        properties.setResourceLookupEnabled(false);
        assertThat(service.snapshot(USER, CONVERSATION_ID, 2).messages().getFirst()
                .content().at("/interaction/available").asBoolean()).isFalse();
    }

    @Test
    void acceptedOperationLookupIsReadOnlyAndOwnerScoped() {
        UUID operationId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(mapper.findSubmissionByClientMessageId(USER.id(), CONVERSATION_ID, operationId))
                .thenReturn(Optional.of(new MessageSubmissionRecord(101, runId, operationId,
                        UUID.randomUUID(), "a".repeat(64), "FAILED")));
        assertThat(service.messageOperation(USER, CONVERSATION_ID, operationId).status()).isEqualTo("FAILED");
        assertThatThrownBy(() -> service.messageOperation(account(99, AccountRole.USER), CONVERSATION_ID, operationId))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verify(mapper, never()).insertRun(any());
    }

    @Test
    void resourceReportActionAvailabilityIsRecheckedWithoutReplacingHistoricalReport() {
        RunResourceService resources = mock(RunResourceService.class);
        service.setResourceService(resources);
        properties.setResourceLookupEnabled(true);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String stored = "{\"text\":\"报告结果\",\"geoContext\":{\"status\":\"READY\",\"report\":{"
                + "\"reportId\":\"701\",\"observationId\":\"901\",\"canCreateArticle\":true,\"available\":true}}}";
        var card = new ConversationMessageRecord(12, CONVERSATION_ID, USER.id(), "ASSISTANT", "TEXT", stored,
                null, null, null, null, null, 12, "COMPLETED", now, now);
        when(mapper.findMessagesBefore(USER.id(), CONVERSATION_ID, Long.MAX_VALUE, 3)).thenReturn(List.of(card));
        when(resources.isOwnedObservationAvailableForAction(USER.id(), CONVERSATION_ID, "901", 701)).thenReturn(true, false);
        assertThat(service.snapshot(USER, CONVERSATION_ID, 2).messages().getFirst().content().at("/geoContext/report/available").asBoolean()).isTrue();
        var revoked = service.snapshot(USER, CONVERSATION_ID, 2).messages().getFirst().content();
        assertThat(revoked.at("/geoContext/report/available").asBoolean()).isFalse();
        assertThat(revoked.at("/geoContext/report/reportId").asText()).isEqualTo("701");
        assertThat(card.contentJson()).isEqualTo(stored);
        properties.setResourceLookupEnabled(false);
        assertThat(service.snapshot(USER, CONVERSATION_ID, 2).messages().getFirst().content().at("/geoContext/report/available").asBoolean()).isFalse();
    }

    @Test
    void stoppingAnOldTerminalRunNeverSelectsTheNewActiveRun() {
        UUID oldRun = UUID.randomUUID();
        when(mapper.lockOwnedRun(oldRun, USER.id(), CONVERSATION_ID)).thenReturn(Optional.of(run(oldRun, "COMPLETED")));
        assertThat(service.requestStop(USER, CONVERSATION_ID, oldRun).status()).isEqualTo("COMPLETED");
        verify(mapper, never()).findActiveRun(anyLong(), anyLong());
        verify(mapper, never()).requestStop(any(), anyLong(), anyLong(), any());
        assertThatThrownBy(() -> service.requestStop(USER, CONVERSATION_ID, UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void exactActiveRunCanBeStoppedButMissingTargetFailsClosed() {
        UUID runId = UUID.randomUUID();
        when(mapper.lockOwnedRun(runId, USER.id(), CONVERSATION_ID)).thenReturn(Optional.of(run(runId, "RUNNING")));
        when(mapper.requestStop(eq(runId), eq(USER.id()), eq(CONVERSATION_ID), any())).thenReturn(1);
        assertThat(service.requestStop(USER, CONVERSATION_ID, runId).status()).isEqualTo("STOP_REQUESTED");
        assertThatThrownBy(() -> service.requestStop(USER, CONVERSATION_ID, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private ConversationMessageRecord message(long sequence, String status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new ConversationMessageRecord(sequence, CONVERSATION_ID, USER.id(), "ASSISTANT", "TEXT",
                "{\"text\":\"部分回答\"}", null, null, null, null, null, sequence, status, now, now);
    }

    private AgentRunRecord run(UUID runId, String status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AgentRunRecord(runId, CONVERSATION_ID, USER.id(), 101, 102L, null, null, "[]",
                status, 1, "{}", "a".repeat(64), now.plusMinutes(5), null, 0, "{}", null, null,
                null, now, null, now, now);
    }

    private void assertValidation(ConversationDtos.SendMessageRequest request) {
        assertCode(request, ErrorCode.VALIDATION_FAILED);
    }

    private void assertCode(ConversationDtos.SendMessageRequest request, ErrorCode code) {
        assertThatThrownBy(() -> service.submitMessage(
                USER, CONVERSATION_ID, UUID.randomUUID().toString(), request
        )).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(code));
    }

    private String capturedFingerprint() {
        ArgumentCaptor<ConversationMessageRecord> messageCaptor = ArgumentCaptor.forClass(ConversationMessageRecord.class);
        verify(mapper).insertUserMessage(messageCaptor.capture());
        return messageCaptor.getValue().requestFingerprint();
    }

    private String legacyFingerprint(
            UUID clientMessageId,
            String content,
            Long replyToMessageId,
            Long selectedSpaceId,
            Long selectedProductId,
            List<String> attachmentAssetIds
    ) {
        try {
            byte[] canonical = new ObjectMapper().writeValueAsBytes(new LegacyCanonicalMessage(
                    clientMessageId, content, replyToMessageId, selectedSpaceId,
                    selectedProductId, attachmentAssetIds
            ));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (com.fasterxml.jackson.core.JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void prepareNewSubmission(UUID clientMessageId, UUID idempotencyKey) {
        when(mapper.findSubmissionByIdempotencyKey(USER.id(), CONVERSATION_ID, idempotencyKey))
                .thenReturn(Optional.empty());
        when(mapper.findSubmissionByClientMessageId(USER.id(), CONVERSATION_ID, clientMessageId))
                .thenReturn(Optional.empty());
        when(mapper.findActiveRun(USER.id(), CONVERSATION_ID)).thenReturn(Optional.empty());
        when(mapper.nextMessageSequence(CONVERSATION_ID, USER.id())).thenReturn(1L, 2L);
        when(idGenerator.nextId()).thenReturn(101L, 102L);
        when(mapper.insertUserMessage(any())).thenReturn(1);
        when(mapper.insertRun(any())).thenReturn(1);
        when(mapper.insertAssistantMessage(any())).thenReturn(1);
    }

    @Test
    void explicitMonitorIntentIsPersistedAndFrozenInFingerprintWithoutOverwritingSidebar() throws Exception {
        var monitor=mock(com.guanghe.backend.agentplatform.MonitorExecutionService.class);
        service.setMonitorExecution(monitor);
        var intent=monitorIntent(1);UUID client=UUID.randomUUID(),key=UUID.randomUUID();prepareNewSubmission(client,key);
        when(monitor.prepareMessage(USER.id(),CONVERSATION_ID,intent)).thenReturn(
                new com.guanghe.backend.agentplatform.MonitorExecutionService.Selection(2001L,null,null,
                        com.guanghe.backend.agentplatform.MonitorWaitReason.INPUT_CHANGED));
        var request=new ConversationDtos.SendMessageRequest(client,"开始这轮监测",null,null,null,null,null,List.of(),
                ConversationDtos.ContextSelectionMode.INHERIT,null,null,intent);
        service.submitMessage(USER,CONVERSATION_ID,key.toString(),request);
        var captured=ArgumentCaptor.forClass(ConversationMessageRecord.class);verify(mapper).insertUserMessage(captured.capture());
        var stored=new ObjectMapper().readTree(captured.getValue().contentJson());
        assertThat(stored.path("executionIntent").path("grantVersion").asInt()).isEqualTo(1);
        assertThat(stored.path("monitorPreparationReason").asText()).isEqualTo("INPUT_CHANGED");
        assertThat(stored.has("monitorActionContext")).isFalse();
        when(mapper.findSubmissionByIdempotencyKey(USER.id(),CONVERSATION_ID,key)).thenReturn(Optional.of(
                new MessageSubmissionRecord(captured.getValue().id(),captured.getValue().agentRunId(),client,key,
                        captured.getValue().requestFingerprint(),"COMPLETED")));
        org.mockito.Mockito.clearInvocations(monitor);
        service.submitMessage(USER,CONVERSATION_ID,key.toString(),request);
        verify(monitor,never()).prepareMessage(anyLong(),anyLong(),any());
        var changed=new ConversationDtos.SendMessageRequest(client,"开始这轮监测",null,null,null,null,null,List.of(),
                ConversationDtos.ContextSelectionMode.INHERIT,null,null,monitorIntent(2));
        assertThatThrownBy(()->service.submitMessage(USER,CONVERSATION_ID,key.toString(),changed))
                .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    private com.guanghe.backend.agentplatform.MonitorExecutionDtos.MonitorExecutionIntent monitorIntent(int version) {
        var range=new com.guanghe.backend.agentplatform.MonitorExecutionDtos.FrozenMonitorRange("61",1,List.of("71","72","73"),List.of("DOUBAO"),"STANDARD","广州",
                new com.guanghe.backend.agentplatform.MonitorExecutionDtos.InputVersions(1,null,null,List.of()),"a".repeat(64));
        return new com.guanghe.backend.agentplatform.MonitorExecutionDtos.MonitorExecutionIntent("MONITOR_RUN","101",version,range,"5001",6);
    }

    @Test
    void fullArticleModeIsCurrentUserOnlyBoundToInstalledProjectAndIdempotent() throws Exception {
        var platform = fullArticlePlatform("2001", true);
        UUID client = UUID.randomUUID(), key = UUID.randomUUID();
        prepareNewSubmission(client, key);
        var request = fullArticleRequest(client, null, null, null, List.of(), null);
        service.submitMessage(USER, CONVERSATION_ID, key.toString(), request);
        var message = ArgumentCaptor.forClass(ConversationMessageRecord.class);
        verify(mapper).insertUserMessage(message.capture());
        assertThat(new ObjectMapper().readTree(message.getValue().contentJson())
                .path("articlePreparationMode").asText()).isEqualTo("ARTICLE_FULL_V1");
        assertThat(new ObjectMapper().readTree(message.getValue().contentJson()).has("geoReportId")).isFalse();
        var run = ArgumentCaptor.forClass(AgentRunRecord.class);
        verify(mapper).insertRun(run.capture());
        assertThat(run.getValue().selectedSpaceId()).isEqualTo(2001L);
        // The installed project is the trusted initial selection for an INHERIT turn.
        verify(mapper).updateConversationSelection(eq(CONVERSATION_ID), eq(USER.id()),
                eq(2001L), org.mockito.ArgumentMatchers.isNull(), any());
        when(mapper.findSubmissionByIdempotencyKey(USER.id(), CONVERSATION_ID, key)).thenReturn(Optional.of(
                new MessageSubmissionRecord(message.getValue().id(), message.getValue().agentRunId(), client, key,
                        message.getValue().requestFingerprint(), "COMPLETED")));
        org.mockito.Mockito.clearInvocations(platform);
        service.submitMessage(USER, CONVERSATION_ID, key.toString(), request);
        verify(platform, never()).assertRunAllowed(anyLong(), anyLong());
        verify(platform, never()).canUseInstalledAgent(anyLong(), any(), any());
        var changed = new ConversationDtos.SendMessageRequest(client, "写完整文章", null, null, null, null,
                null, List.of(), ConversationDtos.ContextSelectionMode.INHERIT, null, null, null, null);
        assertThatThrownBy(() -> service.submitMessage(USER, CONVERSATION_ID, key.toString(), changed))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void originalFullArticleReportSelectionIsPersistedAndCannotBeChangedOnReplay() throws Exception {
        fullArticlePlatform("2001", true);
        UUID client = UUID.randomUUID(), key = UUID.randomUUID();
        prepareNewSubmission(client, key);
        when(contextSnapshotFactory.create(any(UUID.class), eq(CONVERSATION_ID), eq(USER.id()),
                eq(2001L), org.mockito.ArgumentMatchers.isNull(), any(ConversationMessageRecord.class), eq(7001L)))
                .thenAnswer(invocation -> new InternalRunContextDtos.RunContext(
                        invocation.<UUID>getArgument(0).toString(), Long.toString(CONVERSATION_ID), 1,
                        null, null, List.of(), List.of()));
        var request = new ConversationDtos.SendMessageRequest(client, "写完整文章", null, null, null, null,
                null, List.of(), ConversationDtos.ContextSelectionMode.INHERIT, "7001", null, null,
                "ARTICLE_FULL_V1");
        service.submitMessage(USER, CONVERSATION_ID, key.toString(), request);
        var message = ArgumentCaptor.forClass(ConversationMessageRecord.class);
        verify(mapper).insertUserMessage(message.capture());
        assertThat(new ObjectMapper().readTree(message.getValue().contentJson()).path("geoReportId").asText())
                .isEqualTo("7001");
        verify(contextSnapshotFactory).create(any(UUID.class), eq(CONVERSATION_ID), eq(USER.id()),
                eq(2001L), org.mockito.ArgumentMatchers.isNull(), eq(message.getValue()), eq(7001L));
        when(mapper.findSubmissionByIdempotencyKey(USER.id(), CONVERSATION_ID, key)).thenReturn(Optional.of(
                new MessageSubmissionRecord(message.getValue().id(), message.getValue().agentRunId(), client, key,
                        message.getValue().requestFingerprint(), "COMPLETED")));
        assertThat(service.submitMessage(USER, CONVERSATION_ID, key.toString(), request).newSubmission()).isFalse();
        var replaced = new ConversationDtos.SendMessageRequest(client, "写完整文章", null, null, null, null,
                null, List.of(), ConversationDtos.ContextSelectionMode.INHERIT, "7002", null, null,
                "ARTICLE_FULL_V1");
        assertThatThrownBy(() -> service.submitMessage(USER, CONVERSATION_ID, key.toString(), replaced))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void fullArticleModeRejectsArchivedProjectAndForeignSidebarWithoutWrites() {
        fullArticlePlatform("2001", false);
        assertCode(fullArticleRequest(UUID.randomUUID(), null, null, null, List.of(), null), ErrorCode.FORBIDDEN);
        fullArticlePlatform("2001", true);
        assertCode(fullArticleRequest(UUID.randomUUID(), "2002", null, null, List.of(), null), ErrorCode.VALIDATION_FAILED);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        when(mapper.findConversation(CONVERSATION_ID, USER.id())).thenReturn(Optional.of(new ConversationRecord(
                CONVERSATION_ID, USER.id(), "会话", 2002L, null, 1, null, now, now)));
        assertCode(fullArticleRequest(UUID.randomUUID(), null, null, null, List.of(), null), ErrorCode.VALIDATION_FAILED);
        verify(mapper, never()).insertUserMessage(any());
        verify(mapper, never()).insertRun(any());
    }

    @Test
    void fullArticleModeRequiresExplicitInstalledGeoAndActiveProject() {
        var platform = fullArticlePlatform(null, true);
        assertCode(fullArticleRequest(UUID.randomUUID(), null, null, null, List.of(), null), ErrorCode.FORBIDDEN);
        when(platform.assertRunAllowed(USER.id(), CONVERSATION_ID)).thenReturn(new AgentPlatformDtos.AgentIdentity(
                null, "geo", "1.0.0", "GEO", "2001", List.of("chat", "article.generate")));
        assertCode(fullArticleRequest(UUID.randomUUID(), null, null, null, List.of(), null), ErrorCode.FORBIDDEN);
        when(platform.assertRunAllowed(USER.id(), CONVERSATION_ID)).thenReturn(new AgentPlatformDtos.AgentIdentity(
                "5001", "summary", "1.0.0", "摘要", "2001", List.of("chat", "article.generate")));
        assertCode(fullArticleRequest(UUID.randomUUID(), null, null, null, List.of(), null), ErrorCode.FORBIDDEN);
        when(platform.assertRunAllowed(USER.id(), CONVERSATION_ID)).thenReturn(new AgentPlatformDtos.AgentIdentity(
                "5001", "geo", "1.0.0", "GEO", "2001", List.of("chat")));
        assertCode(fullArticleRequest(UUID.randomUUID(), null, null, null, List.of(), null), ErrorCode.FORBIDDEN);
        verify(mapper, never()).insertUserMessage(any());
    }

    @Test
    void fullArticleModeCannotMixMonitorResourceAttachmentsOrClear() {
        assertCode(fullArticleRequest(UUID.randomUUID(), null, null, monitorIntent(1), List.of(), null), ErrorCode.VALIDATION_FAILED);
        assertCode(fullArticleRequest(UUID.randomUUID(), null, new ConversationDtos.ResourceSelection("6001", "candidate-1"),
                null, List.of(), null), ErrorCode.VALIDATION_FAILED);
        assertCode(fullArticleRequest(UUID.randomUUID(), null, null, null,
                List.of(new ConversationDtos.AttachmentRequest("8001")), null), ErrorCode.VALIDATION_FAILED);
        assertCode(fullArticleRequest(UUID.randomUUID(), null, null, null, List.of(),
                ConversationDtos.ContextSelectionMode.CLEAR), ErrorCode.VALIDATION_FAILED);
        verify(mapper, never()).insertUserMessage(any());
        verify(mapper, never()).insertAdmission(any(), anyLong(), any(), anyBoolean());
    }

    private AgentPlatformService fullArticlePlatform(String projectId, boolean active) {
        var platform = mock(AgentPlatformService.class);
        service.setAgentPlatformService(platform);
        when(platform.assertRunAllowed(USER.id(), CONVERSATION_ID)).thenReturn(new AgentPlatformDtos.AgentIdentity(
                "5001", "geo", "1.0.0", "GEO", projectId, List.of("chat", "article.generate")));
        when(platform.canUseInstalledAgent(USER.id(), "geo", 2001L)).thenReturn(active);
        return platform;
    }

    private ConversationDtos.SendMessageRequest fullArticleRequest(UUID client, String spaceId,
            ConversationDtos.ResourceSelection resource,
            com.guanghe.backend.agentplatform.MonitorExecutionDtos.MonitorExecutionIntent monitor,
            List<ConversationDtos.AttachmentRequest> attachments, ConversationDtos.ContextSelectionMode selectionMode) {
        return new ConversationDtos.SendMessageRequest(client, "写完整文章", null, null, null, spaceId, null,
                attachments, selectionMode == null ? ConversationDtos.ContextSelectionMode.INHERIT : selectionMode,
                null, resource, monitor, "ARTICLE_FULL_V1");
    }

    private ConversationDtos.SendMessageRequest request(
            UUID clientMessageId,
            String spaceId,
            String productId,
            List<ConversationDtos.AttachmentRequest> attachments
    ) {
        return new ConversationDtos.SendMessageRequest(
                clientMessageId, " hello ", null, null, null, spaceId, productId, attachments
        );
    }

    private ConversationRecord conversation() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new ConversationRecord(CONVERSATION_ID, USER.id(), "会话", null, null, 1, null, now, now);
    }

    private static AuthenticatedAccount account(long id, AccountRole role) {
        return new AuthenticatedAccount(
                id, "account", role, 1, AccountStatus.ACTIVE, null, null, null
        );
    }

    private record LegacyCanonicalMessage(
            UUID clientMessageId,
            String content,
            Long replyToMessageId,
            Long selectedSpaceId,
            Long selectedProductId,
            List<String> attachmentAssetIds
    ) { }
}
