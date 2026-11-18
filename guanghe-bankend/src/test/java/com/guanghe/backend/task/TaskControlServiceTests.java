package com.guanghe.backend.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.asset.AssetIngestionProperties;
import com.guanghe.backend.asset.AssetMapper;
import com.guanghe.backend.asset.AssetRecords;
import com.guanghe.backend.asset.ObjectStorage;
import com.guanghe.backend.article.ArticleService;
import com.guanghe.backend.catalog.product.ProductIdentityAssetService;
import com.guanghe.backend.catalog.space.BusinessSpaceService;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.id.SnowflakeIdGenerator;
import com.guanghe.backend.conversation.AgentRunRecord;
import com.guanghe.backend.conversation.ConversationEventRecord;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import com.guanghe.backend.geo.GeoTaskService;
import com.guanghe.backend.integration.internal.client.PythonChatRunEvent;
import com.guanghe.backend.media.MediaSubmissionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class TaskControlServiceTests {
    private TaskControlMapper mapper;
    private ConversationMapper conversations;
    private ObjectMapper json;
    private TaskControlService service;
    private AuthorizedCatalogContextFactory catalogContextFactory;
    private GeoTaskService geoTasks;
    private ArticleService articleService;
    private MediaSubmissionService mediaSubmissions;
    private CreativeTaskSourceService creativeSources;
    private final AtomicLong sequence = new AtomicLong(7000);

    @BeforeEach
    void setUp() {
        mapper = mock(TaskControlMapper.class);
        conversations = mock(ConversationMapper.class);
        SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
        json = new ObjectMapper().findAndRegisterModules();
        PaidTaskProperties properties = properties();
        catalogContextFactory = mock(AuthorizedCatalogContextFactory.class);
        geoTasks = mock(GeoTaskService.class);
        articleService = mock(ArticleService.class);
        mediaSubmissions = mock(MediaSubmissionService.class);
        creativeSources = mock(CreativeTaskSourceService.class);
        service = new TaskControlService(mapper, conversations, ids, json, properties,
                new AssetIngestionProperties(), catalogContextFactory, geoTasks, articleService, mediaSubmissions);
        @SuppressWarnings("unchecked")
        ObjectProvider<CreativeTaskSourceService> sourceProvider = mock(ObjectProvider.class);
        when(sourceProvider.getIfAvailable()).thenReturn(creativeSources);
        service.setCreativeSources(sourceProvider);
        when(ids.nextId()).thenAnswer(ignored -> sequence.incrementAndGet());
        when(mapper.insertDraft(any())).thenReturn(1);
        when(mapper.insertConfirmation(any())).thenReturn(1);
        when(conversations.updateAssistantInteraction(anyLong(), anyLong(), any(), anyString(), any())).thenReturn(1);
        when(conversations.nextEventSequence(anyLong(), anyLong())).thenReturn(1L);
        when(conversations.insertEvent(any())).thenReturn(1);
        when(catalogContextFactory.create(anyLong(), any(), any())).thenReturn(catalogContext());
        when(creativeSources.prepareChat(any(), anyString(), any(ObjectNode.class)))
                .thenAnswer(invocation -> creativePrepared(invocation.getArgument(2), 9001, 8001L));
        when(creativeSources.prepareForm(anyLong(), anyLong(), any(), anyLong(), any(), anyString(),
                any(ObjectNode.class))).thenAnswer(invocation ->
                creativePrepared(invocation.getArgument(6), invocation.getArgument(1), invocation.getArgument(2)));
        when(creativeSources.controlledReferences(any(), any())).thenReturn(List.of());
    }

    @Test
    void nonGeoFrozenIdentityCannotSmuggleAPaidImageProposal() throws Exception {
        ObjectNode sourceRun = json.valueToTree(run());
        sourceRun.put("contextSnapshotJson", "{\"agentIdentity\":{\"agentKey\":\"summary\","
                + "\"agentVersion\":\"1.0.0\",\"capabilities\":[\"chat\",\"text.summarize\"]}}");
        var restrictedRun = json.treeToValue(sourceRun, AgentRunRecord.class);
        assertThatThrownBy(() -> service.acceptProposal(restrictedRun, event(proposal()), "", now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("AGENT_CAPABILITY_DENIED");
        verify(mapper, never()).insertDraft(any());
        verify(mapper, never()).insertConfirmation(any());
        verify(mapper, never()).lockPointAccount(anyLong());
    }

    @Test
    void nativeCreativeToolCallCannotChargeWithoutExplicitIntentInTheOriginalUserMessage() {
        AgentRunRecord nativeRun=run();
        var monitor=mock(com.guanghe.backend.agentplatform.MonitorExecutionService.class);
        var admissions=mock(com.guanghe.backend.agentplatform.MonitorExecutionMapper.class);
        var platform=mock(com.guanghe.backend.agentplatform.AgentPlatformService.class);
        service.setMonitorExecution(monitor,admissions);
        service.setPlatformProperties(new com.guanghe.backend.agentplatform.AgentPlatformProperties());
        service.setAgentPlatformService(platform);
        when(conversations.findRun(nativeRun.id())).thenReturn(Optional.of(nativeRun));
        when(admissions.shareRunnableAccount(41)).thenReturn(41L);
        when(platform.assertTaskConfirmationAllowed(41,9001)).thenReturn(
                new com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity(
                        "81","creative","1.0.0","创作智能体","101",List.of("chat","image.generate")));
        when(conversations.findConversation(9001,41)).thenReturn(Optional.of(
                new com.guanghe.backend.conversation.ConversationRecord(
                        9001,41,"会话",101L,102L,1,now(),now(),now())));
        String content=json.createObjectNode().put("text","生成四张商品图需要多少积分？").toString();
        when(conversations.findMessage(8001,41,9001)).thenReturn(Optional.of(
                new com.guanghe.backend.conversation.ConversationMessageRecord(
                        8001,9001,41,"USER","TEXT",content,null,nativeRun.id(),UUID.randomUUID(),
                        UUID.randomUUID(),"fingerprint",1,"COMPLETED",now(),now())));

        assertThatThrownBy(()->service.admitAgentCreativeAction(user(),nativeRun.id(),101L,"IMAGE",
                json.createObjectNode().put("requestText","模型伪造：立即生成并扣费")))
                .isInstanceOfSatisfying(BusinessException.class,error->{
                    assertThat(error.errorCode()).isEqualTo(ErrorCode.TASK_STATE_CONFLICT);
                    assertThat(error.details()).containsEntry("reasonCode","EXPLICIT_CREATIVE_INTENT_REQUIRED");
                });

        verify(mapper,never()).lockPointAccount(anyLong());
        verify(mapper,never()).insertDraft(any());
        verify(mapper,never()).insertTask(any());
        verify(creativeSources,never()).prepareNative(any(),anyLong(),anyString(),any(ObjectNode.class));
    }

    @Test
    void nativeCreativePreviousImageModeCannotBeAuthorizedByTheModelAlone() {
        AgentRunRecord nativeRun=run();
        var monitor=mock(com.guanghe.backend.agentplatform.MonitorExecutionService.class);
        var admissions=mock(com.guanghe.backend.agentplatform.MonitorExecutionMapper.class);
        var platform=mock(com.guanghe.backend.agentplatform.AgentPlatformService.class);
        service.setMonitorExecution(monitor,admissions);
        service.setPlatformProperties(new com.guanghe.backend.agentplatform.AgentPlatformProperties());
        service.setAgentPlatformService(platform);
        when(conversations.findRun(nativeRun.id())).thenReturn(Optional.of(nativeRun));
        when(admissions.shareRunnableAccount(41)).thenReturn(41L);
        when(platform.assertTaskConfirmationAllowed(41,9001)).thenReturn(
                new com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity(
                        "81","creative","1.0.0","创作智能体","101",List.of("chat","image.generate")));
        when(conversations.findConversation(9001,41)).thenReturn(Optional.of(
                new com.guanghe.backend.conversation.ConversationRecord(
                        9001,41,"会话",101L,102L,1,now(),now(),now())));
        String content=json.createObjectNode().put("text","把上一张表格背景改白").toString();
        when(conversations.findMessage(8001,41,9001)).thenReturn(Optional.of(
                new com.guanghe.backend.conversation.ConversationMessageRecord(
                        8001,9001,41,"USER","TEXT",content,null,nativeRun.id(),UUID.randomUUID(),
                        UUID.randomUUID(),"fingerprint",1,"COMPLETED",now(),now())));
        ObjectNode parameters=(ObjectNode)proposal().path("parameters").deepCopy();
        parameters.put("sourceMode","PREVIOUS_IMAGE_RESULT");

        assertThatThrownBy(()->service.admitAgentCreativeAction(user(),nativeRun.id(),101L,"IMAGE",parameters))
                .isInstanceOfSatisfying(BusinessException.class,error->{
                    assertThat(error.errorCode()).isEqualTo(ErrorCode.TASK_STATE_CONFLICT);
                    assertThat(error.details()).containsEntry(
                            "reasonCode","EXPLICIT_PREVIOUS_IMAGE_REFERENCE_REQUIRED");
                });

        verify(mapper,never()).lockPointAccount(anyLong());
        verify(mapper,never()).insertDraft(any());
        verify(mapper,never()).insertTask(any());
        verify(creativeSources,never()).prepareNative(any(),anyLong(),anyString(),any(ObjectNode.class));
    }

    @Test
    void nativeCreativePreviousUserAttachmentModeIsBoundToTheCurrentUserWording() {
        AgentRunRecord nativeRun=run();
        var monitor=mock(com.guanghe.backend.agentplatform.MonitorExecutionService.class);
        var admissions=mock(com.guanghe.backend.agentplatform.MonitorExecutionMapper.class);
        var platform=mock(com.guanghe.backend.agentplatform.AgentPlatformService.class);
        service.setMonitorExecution(monitor,admissions);
        service.setPlatformProperties(new com.guanghe.backend.agentplatform.AgentPlatformProperties());
        service.setAgentPlatformService(platform);
        when(conversations.findRun(nativeRun.id())).thenReturn(Optional.of(nativeRun));
        when(admissions.shareRunnableAccount(41)).thenReturn(41L);
        when(platform.assertTaskConfirmationAllowed(41,9001)).thenReturn(
                new com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity(
                        "81","creative","1.0.0","创作智能体","101",List.of("chat","image.generate")));
        when(conversations.findConversation(9001,41)).thenReturn(Optional.of(
                new com.guanghe.backend.conversation.ConversationRecord(
                        9001,41,"会话",101L,102L,1,now(),now(),now())));
        when(conversations.findMessage(8001,41,9001)).thenReturn(
                Optional.of(new com.guanghe.backend.conversation.ConversationMessageRecord(
                        8001,9001,41,"USER","TEXT",
                        json.createObjectNode().put("text","分析这张图上的红痕").toString(),null,
                        nativeRun.id(),UUID.randomUUID(),UUID.randomUUID(),"fingerprint",1,
                        "COMPLETED",now(),now())),
                Optional.of(new com.guanghe.backend.conversation.ConversationMessageRecord(
                        8001,9001,41,"USER","TEXT",
                        json.createObjectNode().put("text","你按这张图作参考生成一版带红痕的").toString(),null,
                        nativeRun.id(),UUID.randomUUID(),UUID.randomUUID(),"fingerprint",1,
                        "COMPLETED",now(),now())));

        assertThatThrownBy(()->service.admitAgentCreativeAction(user(),nativeRun.id(),101L,"IMAGE",
                json.createObjectNode().put("sourceMode","PREVIOUS_USER_ATTACHMENT")))
                .isInstanceOfSatisfying(BusinessException.class,error ->
                        assertThat(error.details()).containsEntry("reasonCode",
                                "EXPLICIT_PREVIOUS_USER_ATTACHMENT_REFERENCE_REQUIRED"));
        assertThatThrownBy(()->service.admitAgentCreativeAction(user(),nativeRun.id(),101L,"IMAGE",
                json.createObjectNode().put("sourceMode","TEXT_ONLY")))
                .isInstanceOfSatisfying(BusinessException.class,error ->
                        assertThat(error.details()).containsEntry("reasonCode",
                                "PREVIOUS_USER_ATTACHMENT_SOURCE_MODE_REQUIRED"));

        verify(mapper,never()).lockPointAccount(anyLong());
        verify(creativeSources,never()).prepareNative(any(),anyLong(),anyString(),any(ObjectNode.class));
    }

    @Test
    void nativeCreativePreviousImageRequestCannotUseAProductOrCurrentAttachmentByModelMistake() throws Exception {
        AgentRunRecord productRun=run();
        ObjectNode attachedRunJson=json.valueToTree(run());
        attachedRunJson.put("id",UUID.randomUUID().toString());
        attachedRunJson.put("requestMessageId",8003L);
        attachedRunJson.put("attachmentAssetIdsJson","[\"9901\"]");
        AgentRunRecord attachedRun=json.treeToValue(attachedRunJson,AgentRunRecord.class);
        var monitor=mock(com.guanghe.backend.agentplatform.MonitorExecutionService.class);
        var admissions=mock(com.guanghe.backend.agentplatform.MonitorExecutionMapper.class);
        var platform=mock(com.guanghe.backend.agentplatform.AgentPlatformService.class);
        service.setMonitorExecution(monitor,admissions);
        service.setPlatformProperties(new com.guanghe.backend.agentplatform.AgentPlatformProperties());
        service.setAgentPlatformService(platform);
        when(conversations.findRun(productRun.id())).thenReturn(Optional.of(productRun));
        when(conversations.findRun(attachedRun.id())).thenReturn(Optional.of(attachedRun));
        when(admissions.shareRunnableAccount(41)).thenReturn(41L);
        when(platform.assertTaskConfirmationAllowed(41,9001)).thenReturn(
                new com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity(
                        "81","creative","1.0.0","创作智能体","101",
                        List.of("chat","image.generate","video.generate")));
        when(conversations.findConversation(9001,41)).thenReturn(Optional.of(
                new com.guanghe.backend.conversation.ConversationRecord(
                        9001,41,"会话",101L,102L,1,now(),now(),now())));
        when(conversations.findMessage(8001,41,9001)).thenReturn(Optional.of(
                new com.guanghe.backend.conversation.ConversationMessageRecord(
                        8001,9001,41,"USER","TEXT",
                        json.createObjectNode().put("text","把上一张图背景改红").toString(),null,
                        productRun.id(),UUID.randomUUID(),UUID.randomUUID(),"fingerprint",1,"COMPLETED",now(),now())));
        when(conversations.findMessage(8003,41,9001)).thenReturn(Optional.of(
                new com.guanghe.backend.conversation.ConversationMessageRecord(
                        8003,9001,41,"USER","TEXT",
                        json.createObjectNode().put("text","让上一张动起来").toString(),null,
                        attachedRun.id(),UUID.randomUUID(),UUID.randomUUID(),"fingerprint",2,"COMPLETED",now(),now())));
        ObjectNode productParameters=(ObjectNode)proposal().path("parameters").deepCopy();
        productParameters.put("sourceMode","CATALOG_PRODUCT");
        ObjectNode attachmentParameters=(ObjectNode)videoProposal().path("parameters").deepCopy();
        attachmentParameters.put("sourceMode","CURRENT_ATTACHMENTS");

        for (Runnable admission : List.<Runnable>of(
                ()->service.admitAgentCreativeAction(user(),productRun.id(),101L,"IMAGE",productParameters),
                ()->service.admitAgentCreativeAction(user(),attachedRun.id(),101L,"VIDEO",attachmentParameters))) {
            assertThatThrownBy(admission::run)
                    .isInstanceOfSatisfying(BusinessException.class,error->{
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.TASK_STATE_CONFLICT);
                        assertThat(error.details()).containsEntry(
                                "reasonCode","PREVIOUS_IMAGE_SOURCE_MODE_REQUIRED");
                    });
        }

        verify(mapper,never()).lockPointAccount(anyLong());
        verify(mapper,never()).insertDraft(any());
        verify(mapper,never()).insertTask(any());
        verify(creativeSources,never()).prepareNative(any(),anyLong(),anyString(),any(ObjectNode.class));
    }

    @Test
    void nativeCreativeAdmissionUsesOriginalUserTextInsteadOfModelSuppliedRequestText() {
        AgentRunRecord nativeRun=run();
        var monitor=mock(com.guanghe.backend.agentplatform.MonitorExecutionService.class);
        var admissions=mock(com.guanghe.backend.agentplatform.MonitorExecutionMapper.class);
        var platform=mock(com.guanghe.backend.agentplatform.AgentPlatformService.class);
        service.setMonitorExecution(monitor,admissions);
        service.setPlatformProperties(new com.guanghe.backend.agentplatform.AgentPlatformProperties());
        service.setAgentPlatformService(platform);
        when(conversations.findRun(nativeRun.id())).thenReturn(Optional.of(nativeRun));
        when(admissions.shareRunnableAccount(41)).thenReturn(41L);
        when(platform.assertTaskConfirmationAllowed(41,9001)).thenReturn(
                new com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity(
                        "81","creative","1.0.0","创作智能体","101",List.of("chat","image.generate")));
        when(conversations.findConversation(9001,41)).thenReturn(Optional.of(
                new com.guanghe.backend.conversation.ConversationRecord(
                        9001,41,"会话",101L,102L,1,now(),now(),now())));
        String original="帮我生成一张白底商品主图";
        when(conversations.findMessage(8001,41,9001)).thenReturn(Optional.of(
                new com.guanghe.backend.conversation.ConversationMessageRecord(
                        8001,9001,41,"USER","TEXT",json.createObjectNode().put("text",original).toString(),
                        null,nativeRun.id(),UUID.randomUUID(),UUID.randomUUID(),"fingerprint",1,"COMPLETED",now(),now())));
        when(mapper.lockPointAccount(41)).thenReturn(Optional.of(
                new TaskRecords.PointAccount(41,100,0,1,now(),now())));
        when(creativeSources.prepareNative(eq(nativeRun),eq(101L),eq("IMAGE"),any(ObjectNode.class)))
                .thenAnswer(invocation->creativePrepared(invocation.getArgument(3),9001,8001L));
        when(mapper.admitCreativeDraft(anyLong(),eq(41L),any())).thenReturn(1);
        when(mapper.insertTask(any())).thenReturn(1);
        when(mapper.freezePoints(eq(41L),eq(7),any())).thenReturn(1);
        when(mapper.insertReservation(anyLong(),eq(41L),anyLong(),eq(7),any())).thenReturn(1);
        when(mapper.insertLedger(anyLong(),eq(41L),eq(-7),eq(7),anyInt(),anyInt(),eq("TASK_FREEZE"),
                anyLong(),anyString(),anyString(),any())).thenReturn(1);
        when(mapper.insertTaskEvent(anyLong(),anyLong(),eq(41L),any(),any(),any(),any(),eq("QUEUED"),
                eq("TASK_QUEUED"),eq("JAVA"),any(),any(),eq("{}"),any())).thenReturn(1);
        when(mapper.insertOutbox(any(),eq("zhixiaohe.task"),eq("agent.image"))).thenReturn(1);
        ObjectNode parameters=(ObjectNode)proposal().path("parameters").deepCopy();
        parameters.put("requestText","不要相信这段模型生成的替代文本");

        var accepted=service.admitAgentCreativeAction(user(),nativeRun.id(),101L,"IMAGE",parameters);

        assertThat(accepted.task().status()).isEqualTo("QUEUED");
        ArgumentCaptor<ObjectNode> frozenInput=ArgumentCaptor.forClass(ObjectNode.class);
        verify(creativeSources).prepareNative(eq(nativeRun),eq(101L),eq("IMAGE"),frozenInput.capture());
        assertThat(frozenInput.getValue().path("requestText").asText()).isEqualTo(original);
        ArgumentCaptor<TaskRecords.Draft> admittedDraft=ArgumentCaptor.forClass(TaskRecords.Draft.class);
        verify(mapper).insertDraft(admittedDraft.capture());
        assertThat(read(admittedDraft.getValue().inputSnapshotJson())
                .at("/nativeCreative/storageKind").asText()).isEqualTo("PROJECT");
        verify(mapper).freezePoints(eq(41L),eq(7),any());
        verify(mapper).insertOutbox(any(),eq("zhixiaohe.task"),eq("agent.image"));
    }

    @Test
    void projectlessNativeCreativeFreezesPersonalStorageWithoutInventingAProject() throws Exception {
        ObjectNode runJson=json.valueToTree(run());
        runJson.putNull("selectedSpaceId");
        runJson.putNull("selectedProductId");
        AgentRunRecord nativeRun=json.treeToValue(runJson,AgentRunRecord.class);
        var monitor=mock(com.guanghe.backend.agentplatform.MonitorExecutionService.class);
        var admissions=mock(com.guanghe.backend.agentplatform.MonitorExecutionMapper.class);
        var platform=mock(com.guanghe.backend.agentplatform.AgentPlatformService.class);
        service.setMonitorExecution(monitor,admissions);
        service.setPlatformProperties(new com.guanghe.backend.agentplatform.AgentPlatformProperties());
        service.setAgentPlatformService(platform);
        when(conversations.findRun(nativeRun.id())).thenReturn(Optional.of(nativeRun));
        when(admissions.shareRunnableAccount(41)).thenReturn(41L);
        when(platform.assertTaskConfirmationAllowed(41,9001)).thenReturn(
                new com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity(
                        "81","creative","1.0.0","创作智能体",null,List.of("chat","image.generate")));
        when(conversations.findConversation(9001,41)).thenReturn(Optional.of(
                new com.guanghe.backend.conversation.ConversationRecord(
                        9001,41,"会话",null,null,1,now(),now(),now())));
        String original="我要一张秋日咖啡海报";
        when(conversations.findMessage(8001,41,9001)).thenReturn(Optional.of(
                new com.guanghe.backend.conversation.ConversationMessageRecord(
                        8001,9001,41,"USER","TEXT",json.createObjectNode().put("text",original).toString(),
                        null,nativeRun.id(),UUID.randomUUID(),UUID.randomUUID(),"fingerprint",1,"COMPLETED",now(),now())));
        when(mapper.lockPointAccount(41)).thenReturn(Optional.of(
                new TaskRecords.PointAccount(41,100,0,1,now(),now())));
        when(platform.ensurePersonalCreativeStorage(41)).thenReturn(501L);
        when(creativeSources.prepareNative(eq(nativeRun),eq(501L),eq("IMAGE"),any(ObjectNode.class)))
                .thenAnswer(invocation->personalCreativePrepared(invocation.getArgument(3),9001,8001L));
        when(mapper.admitCreativeDraft(anyLong(),eq(41L),any())).thenReturn(1);
        when(mapper.insertTask(any())).thenReturn(1);
        when(mapper.freezePoints(eq(41L),eq(7),any())).thenReturn(1);
        when(mapper.insertReservation(anyLong(),eq(41L),anyLong(),eq(7),any())).thenReturn(1);
        when(mapper.insertLedger(anyLong(),eq(41L),eq(-7),eq(7),anyInt(),anyInt(),eq("TASK_FREEZE"),
                anyLong(),anyString(),anyString(),any())).thenReturn(1);
        when(mapper.insertTaskEvent(anyLong(),anyLong(),eq(41L),any(),any(),any(),any(),eq("QUEUED"),
                eq("TASK_QUEUED"),eq("JAVA"),any(),any(),eq("{}"),any())).thenReturn(1);
        when(mapper.insertOutbox(any(),eq("zhixiaohe.task"),eq("agent.image"))).thenReturn(1);
        ObjectNode parameters=(ObjectNode)proposal().path("parameters").deepCopy();
        parameters.put("sourceMode","TEXT_ONLY");
        parameters.put("requestText","模型伪造文本");

        var accepted=service.admitAgentCreativeAction(user(),nativeRun.id(),null,"IMAGE",parameters);

        assertThat(accepted.task().spaceId()).isEqualTo("501");
        assertThat(accepted.task().productId()).isNull();
        verify(platform).ensurePersonalCreativeStorage(41);
        ArgumentCaptor<TaskRecords.Draft> draft=ArgumentCaptor.forClass(TaskRecords.Draft.class);
        verify(mapper).insertDraft(draft.capture());
        JsonNode frozen=read(draft.getValue().inputSnapshotJson());
        assertThat(frozen.at("/nativeCreative/storageKind").asText()).isEqualTo("PERSONAL_CREATIVE");
        assertThat(frozen.at("/parameters/requestText").asText()).isEqualTo(original);
        assertThat(frozen.at("/authorizedContext/space").isNull()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings={"再添加一个大狗，这只小狗靠着那只大狗", "背景再淡一点", "主体小一些",
            "右边留白多些", "去掉多余字", "换成暖色"})
    void nativeAttachmentEditsFreezeTheSelectedGeneratedImageAndOriginalUserTextAfterSemanticReview(String original) throws Exception {
        ObjectNode runJson=json.valueToTree(run());
        runJson.putNull("selectedSpaceId").putNull("selectedProductId");
        runJson.put("attachmentAssetIdsJson","[\"9901\"]");
        AgentRunRecord nativeRun=json.treeToValue(runJson,AgentRunRecord.class);
        var admissions=mock(com.guanghe.backend.agentplatform.MonitorExecutionMapper.class);
        var platform=mock(com.guanghe.backend.agentplatform.AgentPlatformService.class);
        service.setMonitorExecution(mock(com.guanghe.backend.agentplatform.MonitorExecutionService.class),admissions);
        service.setPlatformProperties(new com.guanghe.backend.agentplatform.AgentPlatformProperties());
        service.setAgentPlatformService(platform);
        when(conversations.findRun(nativeRun.id())).thenReturn(Optional.of(nativeRun));
        when(admissions.shareRunnableAccount(41)).thenReturn(41L);
        when(platform.assertTaskConfirmationAllowed(41,9001)).thenReturn(
                new com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity(
                        "81","creative","1.0.0","创作智能体",null,List.of("chat","image.generate")));
        when(conversations.findConversation(9001,41)).thenReturn(Optional.of(
                new com.guanghe.backend.conversation.ConversationRecord(9001,41,"会话",null,null,1,now(),now(),now())));
        var source=new com.guanghe.backend.conversation.ConversationMessageRecord(
                        8001,9001,41,"USER","TEXT",json.createObjectNode().put("text",original).toString(),
                        null,nativeRun.id(),UUID.randomUUID(),UUID.randomUUID(),"fingerprint",1,"COMPLETED",now(),now());
        when(conversations.findMessage(8001,41,9001)).thenReturn(Optional.of(source));
        when(mapper.lockPointAccount(41)).thenReturn(Optional.of(new TaskRecords.PointAccount(41,100,0,1,now(),now())));
        when(platform.ensurePersonalCreativeStorage(41)).thenReturn(501L);
        var assets=mock(AssetMapper.class);
        var spaces=mock(BusinessSpaceService.class);
        var sourceProperties=properties();sourceProperties.setReferenceImageGenerationEnabled(true);
        var currentSources=new CreativeTaskSourceService(mock(ProductIdentityAssetService.class),assets,conversations,
                catalogContextFactory,spaces,mock(ObjectStorage.class),new AssetIngestionProperties(),sourceProperties,json);
        @SuppressWarnings("unchecked")
        ObjectProvider<CreativeTaskSourceService> sourceProvider=mock(ObjectProvider.class);
        when(sourceProvider.getIfAvailable()).thenReturn(currentSources);
        service.setCreativeSources(sourceProvider);
        when(conversations.findMessageAssetIds(8001,41,9001)).thenReturn(List.of(9901L));
        when(assets.findAsset(9901,41)).thenReturn(Optional.of(new AssetRecords.Asset(
                9901,41,501,null,"IMAGE","GENERATED",9000L,"generated.png","generated.png","image/png",512,
                "b".repeat(64),1024,1024,null,"READY","APPROVED",now(),now())));
        when(mapper.admitCreativeDraft(anyLong(),eq(41L),any())).thenReturn(1);
        when(mapper.insertTask(any())).thenReturn(1);
        when(mapper.freezePoints(eq(41L),eq(7),any())).thenReturn(1);
        when(mapper.insertReservation(anyLong(),eq(41L),anyLong(),eq(7),any())).thenReturn(1);
        when(mapper.insertLedger(anyLong(),eq(41L),eq(-7),eq(7),anyInt(),anyInt(),eq("TASK_FREEZE"),
                anyLong(),anyString(),anyString(),any())).thenReturn(1);
        when(mapper.insertTaskEvent(anyLong(),anyLong(),eq(41L),any(),any(),any(),any(),eq("QUEUED"),
                eq("TASK_QUEUED"),eq("JAVA"),any(),any(),eq("{}"),any())).thenReturn(1);
        when(mapper.insertOutbox(any(),eq("zhixiaohe.task"),eq("agent.image"))).thenReturn(1);
        ObjectNode parameters=(ObjectNode)proposal().path("parameters").deepCopy();
        parameters.put("sourceMode","CURRENT_ATTACHMENTS").put("requestText","模型生成的替代文本");
        var evidence=new com.guanghe.backend.conversation.action.CreativeContinuationEvidence(conversations,json);
        service.setCreativeContinuations(evidence);
        when(conversations.findMessagesBefore(41,9001,1,7)).thenReturn(List.of());
        var candidate=evidence.candidate(nativeRun,source,null).orElseThrow();
        var review=new com.guanghe.backend.conversation.action.RunActionDtos.CreativeContinuationReview(
                "8001",candidate.path("historyFingerprint").asText(),"IMAGE","CREATE_NEW","CURRENT_ATTACHMENTS",null);
        assertThatThrownBy(()->service.admitAgentCreativeAction(user(),nativeRun.id(),null,"IMAGE",
                parameters.deepCopy().put("sourceMode","TEXT_ONLY"),review))
                .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.details())
                        .containsEntry("reasonCode","CREATIVE_CONTINUATION_SOURCE_MODE_REQUIRED"));
        verify(mapper,never()).lockPointAccount(anyLong());

        var accepted=service.admitAgentCreativeAction(user(),nativeRun.id(),null,"IMAGE",parameters,review);

        assertThat(accepted.task().status()).isEqualTo("QUEUED");
        assertThat(accepted.task().spaceId()).isEqualTo("501");
        assertThat(accepted.task().productId()).isNull();
        ArgumentCaptor<TaskRecords.Task> admitted=ArgumentCaptor.forClass(TaskRecords.Task.class);
        verify(mapper).insertTask(admitted.capture());
        JsonNode frozen=read(admitted.getValue().inputSnapshotJson());
        assertThat(frozen.at("/parameters/sourceMode").asText()).isEqualTo("CURRENT_ATTACHMENTS");
        assertThat(frozen.at("/parameters/referenceAssetIds").toString()).isEqualTo("[\"9901\"]");
        assertThat(frozen.at("/authorizedContext/attachmentAssetIds").toString()).isEqualTo("[\"9901\"]");
        assertThat(frozen.at("/parameters/requestText").asText()).isEqualTo(original);
        assertThat(frozen.at("/productCreativeBrief/provenance/messageId").asText()).isEqualTo("8001");
        assertThat(frozen.at("/nativeCreative/storageKind").asText()).isEqualTo("PERSONAL_CREATIVE");
        assertThat(frozen.at("/nativeCreative/continuationReview/sourceMode").asText()).isEqualTo("CURRENT_ATTACHMENTS");
        assertThat(frozen.at("/nativeCreative/continuationReview/sourceImageAssetId").isNull()).isTrue();
        verify(spaces).requirePersonalCreativeSpace(41,501);
        verify(assets).findAsset(9901,41);
        verify(mapper).freezePoints(eq(41L),eq(7),any());
        verify(mapper).insertOutbox(any(),eq("zhixiaohe.task"),eq("agent.image"));
    }

    @ParameterizedTest
    @CsvSource(value={"IMAGE|背景再淡一点会更好吗|DISCUSSION", "IMAGE|右边留白多些需要多少积分|DISCUSSION",
            "IMAGE|不要改背景|DO_NOT_CREATE", "IMAGE|先别动主体|DO_NOT_CREATE",
            "IMAGE|再添加一个大狗，这只小狗靠着那只大狗|MISSING", "VIDEO|让它动起来|MISSING",
            "IMAGE|再添加一个大狗，这也太搞笑了|UNCLEAR", "VIDEO|让它动起来，哈哈哈|DISCUSSION"},delimiter='|')
    void currentAttachmentsNeedACreationReviewBeforePaidAdmission(String taskType,String original,String decision) throws Exception {
        ObjectNode runJson=json.valueToTree(run());runJson.putNull("selectedSpaceId").putNull("selectedProductId");
        runJson.put("attachmentAssetIdsJson","[\"9901\"]");
        AgentRunRecord nativeRun=json.treeToValue(runJson,AgentRunRecord.class);
        var admissions=mock(com.guanghe.backend.agentplatform.MonitorExecutionMapper.class);
        var platform=mock(com.guanghe.backend.agentplatform.AgentPlatformService.class);
        service.setMonitorExecution(mock(com.guanghe.backend.agentplatform.MonitorExecutionService.class),admissions);
        service.setPlatformProperties(new com.guanghe.backend.agentplatform.AgentPlatformProperties());
        service.setAgentPlatformService(platform);
        var evidence=new com.guanghe.backend.conversation.action.CreativeContinuationEvidence(conversations,json);
        service.setCreativeContinuations(evidence);
        when(conversations.findRun(nativeRun.id())).thenReturn(Optional.of(nativeRun));
        when(admissions.shareRunnableAccount(41)).thenReturn(41L);
        when(platform.assertTaskConfirmationAllowed(41,9001)).thenReturn(
                new com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity(
                        "81","creative","1.0.0","创作智能体",null,List.of("chat","image.generate","video.generate")));
        when(conversations.findConversation(9001,41)).thenReturn(Optional.of(
                new com.guanghe.backend.conversation.ConversationRecord(9001,41,"会话",null,null,1,now(),now(),now())));
        var source=new com.guanghe.backend.conversation.ConversationMessageRecord(
                8001,9001,41,"USER","TEXT",json.createObjectNode().put("text",original).toString(),
                null,nativeRun.id(),null,null,null,1,"COMPLETED",now(),now());
        when(conversations.findMessage(8001,41,9001)).thenReturn(Optional.of(source));
        when(conversations.findMessageAssetIds(8001,41,9001)).thenReturn(List.of(9901L));
        var candidate=evidence.candidate(nativeRun,source,null).orElseThrow();
        var review="MISSING".equals(decision)?null:new com.guanghe.backend.conversation.action.RunActionDtos.CreativeContinuationReview(
                "8001",candidate.path("historyFingerprint").asText(),taskType,decision,"CURRENT_ATTACHMENTS",null);
        ObjectNode parameters=(ObjectNode)("IMAGE".equals(taskType)?proposal():videoProposal()).path("parameters").deepCopy();
        parameters.put("sourceMode","CURRENT_ATTACHMENTS");
        if("MISSING".equals(decision)) assertThat(CreativeGenerationIntentPolicy.allows(taskType,original,true)).isTrue();

        assertThatThrownBy(()->service.admitAgentCreativeAction(user(),nativeRun.id(),null,taskType,parameters,review))
                .isInstanceOf(BusinessException.class);

        verify(mapper,never()).lockPointAccount(anyLong());
        verify(mapper,never()).insertDraft(any());
        verify(mapper,never()).insertTask(any());
        verify(mapper,never()).freezePoints(anyLong(),anyInt(),any());
        verify(mapper,never()).insertOutbox(any(),anyString(),anyString());
        verify(creativeSources,never()).prepareNative(any(),anyLong(),anyString(),any(ObjectNode.class));
    }

    @ParameterizedTest
    @CsvSource({"VIDEO,做个10秒的吧,TEXT_ONLY", "IMAGE,把16:9改成正方形，内容不变,PREVIOUS_IMAGE_RESULT", "IMAGE,文字太多了，没有重点，修改字体排版,PREVIOUS_IMAGE_RESULT", "VIDEO,让它动起来,PREVIOUS_IMAGE_RESULT"})
    void reviewedProjectlessFollowupUsesFreshDialogueAndTheExactImageBeforeBudget(String taskType,String original,String sourceMode) throws Exception {
        ObjectNode sourceRun=json.valueToTree(run());sourceRun.putNull("selectedSpaceId").putNull("selectedProductId");
        AgentRunRecord nativeRun=json.treeToValue(sourceRun,AgentRunRecord.class);
        var admissions=mock(com.guanghe.backend.agentplatform.MonitorExecutionMapper.class);
        var platform=mock(com.guanghe.backend.agentplatform.AgentPlatformService.class);
        service.setMonitorExecution(mock(com.guanghe.backend.agentplatform.MonitorExecutionService.class),admissions);
        service.setPlatformProperties(new com.guanghe.backend.agentplatform.AgentPlatformProperties());
        service.setAgentPlatformService(platform);
        var evidence=new com.guanghe.backend.conversation.action.CreativeContinuationEvidence(conversations,json);
        service.setCreativeContinuations(evidence);
        when(conversations.findRun(nativeRun.id())).thenReturn(Optional.of(nativeRun));
        when(admissions.shareRunnableAccount(41)).thenReturn(41L);
        when(platform.assertTaskConfirmationAllowed(41,9001)).thenReturn(
                new com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity(
                        "81","creative","1.0.0","创作智能体",null,List.of("chat","video.generate","image.generate")));
        when(conversations.findConversation(9001,41)).thenReturn(Optional.of(
                new com.guanghe.backend.conversation.ConversationRecord(9001,41,"会话",null,null,1,now(),now(),now())));
        var currentTime=now();
        int quote="IMAGE".equals(taskType)?7:11;
        var source=new com.guanghe.backend.conversation.ConversationMessageRecord(8001,9001,41,"USER","TEXT",
                json.createObjectNode().put("text",original).toString(),null,nativeRun.id(),null,null,null,3,"COMPLETED",currentTime,currentTime);
        var previousUser=new com.guanghe.backend.conversation.ConversationMessageRecord(7999,9001,41,"USER","TEXT",
                json.createObjectNode().put("text","PREVIOUS_IMAGE_RESULT".equals(sourceMode)?"生成一张猫跳舞的图":"生成猫跳舞的视频").toString(),null,UUID.randomUUID(),null,null,null,1,"COMPLETED",currentTime.minusSeconds(60),currentTime.minusSeconds(60));
        var previousAssistant=new com.guanghe.backend.conversation.ConversationMessageRecord(8000,9001,41,"ASSISTANT","TEXT",
                "{\"text\":\"可以按分解动作重做一版。\"}",7999L,previousUser.agentRunId(),null,null,null,2,"COMPLETED",currentTime.minusSeconds(30),currentTime.minusSeconds(30));
        when(conversations.findMessage(8001,41,9001)).thenReturn(Optional.of(source));
        when(conversations.findMessagesBefore(41,9001,3,7)).thenReturn(List.of(previousAssistant,previousUser));
        var assets=mock(com.guanghe.backend.asset.AssetMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(evidence,"assets",assets);
        when(assets.findContinuationImageResults(41,9001,null,8001,nativeRun.createdAt()))
                .thenReturn(List.of(new com.guanghe.backend.asset.AssetRecords.ContinuationImage(9901,7999)));
        var candidate=evidence.candidate(nativeRun,source,null).orElseThrow();
        var review=new com.guanghe.backend.conversation.action.RunActionDtos.CreativeContinuationReview(
                "8001",candidate.path("historyFingerprint").asText(),taskType,"CREATE_NEW",sourceMode,"PREVIOUS_IMAGE_RESULT".equals(sourceMode)?"9901":null);
        ObjectNode parameters=(ObjectNode)("IMAGE".equals(taskType)?proposal():videoProposal()).path("parameters").deepCopy();
        parameters.put("sourceMode",sourceMode).put("requestText","模型生成的替代文本");
        if("VIDEO".equals(taskType)) parameters.put("durationSeconds",10);
        var wrongType=new com.guanghe.backend.conversation.action.RunActionDtos.CreativeContinuationReview(
                "8001",review.historyFingerprint(),"IMAGE".equals(taskType)?"VIDEO":"IMAGE","CREATE_NEW");
        assertThatThrownBy(()->service.admitAgentCreativeAction(user(),nativeRun.id(),null,taskType,parameters,wrongType))
                .isInstanceOf(BusinessException.class);
        ObjectNode directJson=json.valueToTree(source);directJson.put("contentJson","{\"text\":\"生成一个10秒的视频\"}");
        var direct=json.treeToValue(directJson,com.guanghe.backend.conversation.ConversationMessageRecord.class);
        when(conversations.findMessage(8001,41,9001)).thenReturn(Optional.of(direct));
        assertThatThrownBy(()->service.admitAgentCreativeAction(user(),nativeRun.id(),null,taskType,parameters,review))
                .isInstanceOf(BusinessException.class); // A direct request cannot excuse a stale supplied review.
        when(conversations.findMessage(8001,41,9001)).thenReturn(Optional.of(source));
        if("IMAGE".equals(taskType)) {
            var unbound=new com.guanghe.backend.conversation.action.RunActionDtos.CreativeContinuationReview(
                    "8001",review.historyFingerprint(),taskType,"CREATE_NEW");
            assertThatThrownBy(()->service.admitAgentCreativeAction(user(),nativeRun.id(),null,taskType,parameters,unbound))
                    .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.details())
                            .containsEntry("reasonCode","CREATIVE_CONTINUATION_SOURCE_REQUIRED"));
            var wrongMode=parameters.deepCopy().put("sourceMode","TEXT_ONLY");
            assertThatThrownBy(()->service.admitAgentCreativeAction(user(),nativeRun.id(),null,taskType,wrongMode,review))
                    .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.details())
                            .containsEntry("reasonCode","CREATIVE_CONTINUATION_SOURCE_MODE_REQUIRED"));
        }
        if("PREVIOUS_IMAGE_RESULT".equals(sourceMode) && "VIDEO".equals(taskType)) {
            var wrongMode=parameters.deepCopy().put("sourceMode","TEXT_ONLY");
            assertThatThrownBy(()->service.admitAgentCreativeAction(user(),nativeRun.id(),null,taskType,wrongMode,review))
                    .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.details())
                            .containsEntry("reasonCode","CREATIVE_CONTINUATION_SOURCE_MODE_REQUIRED"));
        }
        verify(mapper,never()).lockPointAccount(anyLong());verify(mapper,never()).insertTask(any());
        when(mapper.lockPointAccount(41)).thenReturn(Optional.of(new TaskRecords.PointAccount(41,100,0,1,now(),now())));
        when(platform.ensurePersonalCreativeStorage(41)).thenReturn(501L);
        if("PREVIOUS_IMAGE_RESULT".equals(sourceMode)) {
            when(creativeSources.prepareNative(eq(nativeRun),eq(501L),eq(taskType),any(ObjectNode.class),eq(9901L)))
                    .thenAnswer(invocation->personalCreativePrepared(invocation.getArgument(3),9001,8001L));
        } else {
            when(creativeSources.prepareNative(eq(nativeRun),eq(501L),eq(taskType),any(ObjectNode.class)))
                    .thenAnswer(invocation->personalCreativePrepared(invocation.getArgument(3),9001,8001L));
        }
        when(mapper.admitCreativeDraft(anyLong(),eq(41L),any())).thenReturn(1);
        when(mapper.insertTask(any())).thenReturn(1);
        when(mapper.freezePoints(eq(41L),eq(quote),any())).thenReturn(1);
        when(mapper.insertReservation(anyLong(),eq(41L),anyLong(),eq(quote),any())).thenReturn(1);
        when(mapper.insertLedger(anyLong(),eq(41L),eq(-quote),eq(quote),anyInt(),anyInt(),eq("TASK_FREEZE"),
                anyLong(),anyString(),anyString(),any())).thenReturn(1);
        when(mapper.insertTaskEvent(anyLong(),anyLong(),eq(41L),any(),any(),any(),any(),eq("QUEUED"),
                eq("TASK_QUEUED"),eq("JAVA"),any(),any(),eq("{}"),any())).thenReturn(1);
        when(mapper.insertOutbox(any(),eq("zhixiaohe.task"),eq("IMAGE".equals(taskType)?"agent.image":"agent.video"))).thenReturn(1);

        var accepted=service.admitAgentCreativeAction(user(),nativeRun.id(),null,taskType,parameters,review);
        assertThat(accepted.task().status()).isEqualTo("QUEUED");assertThat(accepted.task().spaceId()).isEqualTo("501");
        ArgumentCaptor<TaskRecords.Draft> frozen=ArgumentCaptor.forClass(TaskRecords.Draft.class);verify(mapper).insertDraft(frozen.capture());
        if("VIDEO".equals(taskType)) assertThat(read(frozen.getValue().inputSnapshotJson()).at("/parameters/durationSeconds").asInt()).isEqualTo(10);
        assertThat(read(frozen.getValue().inputSnapshotJson()).at("/parameters/requestText").asText()).isEqualTo(original);
        assertThat(read(frozen.getValue().parametersJson()).has("continuationReview")).isFalse();
        assertThat(read(frozen.getValue().inputSnapshotJson()).at("/nativeCreative/continuationReview/taskType").asText()).isEqualTo(taskType);
        verify(mapper).freezePoints(eq(41L),eq(quote),any());verify(mapper).insertOutbox(any(),eq("zhixiaohe.task"),eq("IMAGE".equals(taskType)?"agent.image":"agent.video"));
    }

    @Test
    void pythonProposalBecomesJavaPricedDraftAndOnlyTokenDigestIsPersisted() {
        var browserEvent = service.acceptProposal(run(), event(proposal()), "已整理参数", now());

        ArgumentCaptor<TaskRecords.Draft> draftCaptor = ArgumentCaptor.forClass(TaskRecords.Draft.class);
        ArgumentCaptor<TaskRecords.Confirmation> confirmationCaptor = ArgumentCaptor.forClass(TaskRecords.Confirmation.class);
        verify(mapper).insertDraft(draftCaptor.capture());
        verify(mapper).insertConfirmation(confirmationCaptor.capture());
        TaskRecords.Draft draft = draftCaptor.getValue();
        assertThat(draft.taskType()).isEqualTo("IMAGE");
        assertThat(draft.quotedPoints()).isEqualTo(7);
        assertThat(draft.pricingVersionId()).isEqualTo(6001);
        assertThat(draft.inputSnapshotJson()).contains("authorizedContext", "经营空间", "商品", "竞品")
                .doesNotContain("userId", "confirmationToken");
        assertThat(confirmationCaptor.getValue().tokenHash()).matches("[0-9a-f]{64}");
        JsonNode payload = read(browserEvent.payloadJson());
        assertThat(payload.path("draft").path("confirmationToken").asText()).startsWith("v1.");
        assertThat(payload.path("draft").path("quotedPoints").asInt()).isEqualTo(7);
        assertThat(payload.path("draft").has("userId")).isFalse();
    }

    @Test
    void chatRevisionLinksAndInvalidatesExactlyItsFrozenCandidateWithoutCharging() {
        DraftFixture fixture = persistDraft();
        org.mockito.Mockito.clearInvocations(mapper, conversations);
        when(mapper.lockDraft(fixture.draft.id(), 41)).thenReturn(Optional.of(fixture.draft));
        when(mapper.lockConfirmation(fixture.draft.id(), 41)).thenReturn(Optional.of(fixture.confirmation));
        when(mapper.invalidateConfirmation(fixture.draft.id(), 41)).thenReturn(1);
        when(mapper.abandonDraft(org.mockito.ArgumentMatchers.eq(fixture.draft.id()),
                org.mockito.ArgumentMatchers.eq(41L), any())).thenReturn(1);
        ObjectNode proposed = revisionProposal(fixture.draft.id());
        ((ObjectNode) proposed.path("parameters")).put("count", 2);

        var created = service.acceptProposal(runWithDraft(fixture.draft.id()), event(proposed), "已调整", now());

        ArgumentCaptor<TaskRecords.Draft> drafts = ArgumentCaptor.forClass(TaskRecords.Draft.class);
        verify(mapper).insertDraft(drafts.capture());
        assertThat(drafts.getValue().previousDraftId()).isEqualTo(fixture.draft.id());
        assertThat(drafts.getValue().quotedPoints()).isEqualTo(14);
        assertThat(read(created.payloadJson()).path("draft").path("previousDraftId").asText())
                .isEqualTo(Long.toString(fixture.draft.id()));
        var order = org.mockito.Mockito.inOrder(mapper);
        order.verify(mapper).lockDraft(fixture.draft.id(), 41);
        order.verify(mapper).lockConfirmation(fixture.draft.id(), 41);
        order.verify(mapper).insertDraft(any());
        order.verify(mapper).insertConfirmation(any());
        order.verify(mapper).invalidateConfirmation(fixture.draft.id(), 41);
        order.verify(mapper).abandonDraft(org.mockito.ArgumentMatchers.eq(fixture.draft.id()),
                org.mockito.ArgumentMatchers.eq(41L), any());
        verify(mapper, never()).lockPointAccount(anyLong());
        verify(mapper, never()).insertTask(any());

        when(mapper.lockDraft(fixture.draft.id(), 41))
                .thenReturn(Optional.of(draftWithState(fixture.draft, "ABANDONED", fixture.draft.expiresAt())));
        assertThatThrownBy(() -> service.confirm(user(), fixture.draft.id(), UUID.randomUUID().toString(),
                new TaskDtos.ConfirmRequest(fixture.token, fixture.draft.inputHash(),
                        Long.toString(fixture.draft.pricingVersionId()))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.TASK_CONFIRMATION_EXPIRED));
        verify(mapper, never()).lockPointAccount(anyLong());
    }

    @Test
    void independentNewChatDraftDoesNotInvalidateExistingPlanEvenWithActiveCandidate() {
        ObjectNode proposed = proposal();
        proposed.put("draftOperation", "NEW");
        proposed.putNull("previousDraftId");
        service.acceptProposal(runWithDraft(7999), event(proposed), "另一套独立方案", now());
        ArgumentCaptor<TaskRecords.Draft> drafts = ArgumentCaptor.forClass(TaskRecords.Draft.class);
        verify(mapper).insertDraft(drafts.capture());
        assertThat(drafts.getValue().previousDraftId()).isNull();
        verify(mapper, never()).lockDraft(anyLong(), anyLong());
        verify(mapper, never()).invalidateConfirmation(anyLong(), anyLong());
        verify(mapper, never()).abandonDraft(anyLong(), anyLong(), any());
    }

    @Test
    void chatRevisionMustMatchFrozenCandidateAndCannotGuessPreviousDraft() {
        assertThatThrownBy(() -> service.acceptProposal(runWithDraft(7999), event(revisionProposal(7998)), "", now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("TASK_DRAFT_REVISION_STALE");
        assertThatThrownBy(() -> service.acceptProposal(run(), event(revisionProposal(7999)), "", now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("TASK_DRAFT_REVISION_STALE");
        ObjectNode invalidNew = proposal().put("draftOperation", "NEW").put("previousDraftId", "7999");
        assertThatThrownBy(() -> service.acceptProposal(runWithDraft(7999), event(invalidNew), "", now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("TASK_DRAFT_SCHEMA_INVALID");
        verify(mapper, never()).lockDraft(anyLong(), anyLong());
        verify(mapper, never()).insertDraft(any());
    }

    @Test
    void confirmationOrOtherRevisionWinningTheDraftLockMakesChatRevisionFailClosed() {
        DraftFixture fixture = persistDraft();
        org.mockito.Mockito.clearInvocations(mapper, conversations);
        for (String status : List.of("CONFIRMED", "ABANDONED", "EXPIRED")) {
            when(mapper.lockDraft(fixture.draft.id(), 41))
                    .thenReturn(Optional.of(draftWithState(fixture.draft, status, fixture.draft.expiresAt())));
            assertThatThrownBy(() -> service.acceptProposal(runWithDraft(fixture.draft.id()),
                    event(revisionProposal(fixture.draft.id())), "", now()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("TASK_DRAFT_REVISION_STALE");
        }
        when(mapper.lockDraft(fixture.draft.id(), 41))
                .thenReturn(Optional.of(draftWithState(fixture.draft, "WAITING_CONFIRMATION", now().minusSeconds(1))));
        assertThatThrownBy(() -> service.acceptProposal(runWithDraft(fixture.draft.id()),
                event(revisionProposal(fixture.draft.id())), "", now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("TASK_DRAFT_REVISION_STALE");
        verify(mapper, never()).insertDraft(any());
        verify(mapper, never()).invalidateConfirmation(anyLong(), anyLong());
    }

    @Test
    void chatRevisionCannotChangeItsAuthorizedCreativeSource() {
        DraftFixture fixture = persistDraft();
        org.mockito.Mockito.clearInvocations(mapper, conversations);
        when(mapper.lockDraft(fixture.draft.id(), 41)).thenReturn(Optional.of(fixture.draft));
        when(mapper.lockConfirmation(fixture.draft.id(), 41)).thenReturn(Optional.of(fixture.confirmation));
        when(creativeSources.prepareChat(any(), anyString(), any(ObjectNode.class))).thenAnswer(invocation -> {
            var prepared = creativePrepared(invocation.getArgument(2), 9001, 8001L);
            prepared.parameters().putArray("referenceAssetIds").add("9902");
            return prepared;
        });
        assertThatThrownBy(() -> service.acceptProposal(runWithDraft(fixture.draft.id()),
                event(revisionProposal(fixture.draft.id())), "", now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("TASK_DRAFT_REVISION_SOURCE_MISMATCH");
        verify(mapper, never()).insertDraft(any());
        verify(mapper, never()).invalidateConfirmation(anyLong(), anyLong());
    }

    @Test
    void reattachedSameImageCanReviseAnAttachmentDraftWithoutCatalogParameters() throws Exception {
        ObjectNode sourceRun = json.valueToTree(run());
        sourceRun.putNull("selectedProductId");
        sourceRun.put("attachmentAssetIdsJson", "[\"9901\"]");
        AgentRunRecord initial = json.treeToValue(sourceRun, AgentRunRecord.class);
        when(creativeSources.prepareChat(any(), anyString(), any(ObjectNode.class))).thenAnswer(invocation -> {
            ObjectNode parameters = ((ObjectNode) invocation.getArgument(2)).deepCopy();
            parameters.put("sourceMode", "CURRENT_ATTACHMENTS");
            parameters.putArray("referenceAssetIds").add("9901");
            return new CreativeTaskSourceService.PreparedSource(101, null, 8001L, parameters,
                    json.createObjectNode(), json.createObjectNode());
        });
        ObjectNode proposed = proposal();
        proposed.putNull("productId");
        proposed.putNull("spaceId");
        service.acceptProposal(initial, event(proposed), "", now());
        ArgumentCaptor<TaskRecords.Draft> draftCaptor = ArgumentCaptor.forClass(TaskRecords.Draft.class);
        ArgumentCaptor<TaskRecords.Confirmation> proofCaptor = ArgumentCaptor.forClass(TaskRecords.Confirmation.class);
        verify(mapper).insertDraft(draftCaptor.capture());
        verify(mapper).insertConfirmation(proofCaptor.capture());
        TaskRecords.Draft old = draftCaptor.getValue();
        when(mapper.lockDraft(old.id(), 41)).thenReturn(Optional.of(old));
        when(mapper.lockConfirmation(old.id(), 41)).thenReturn(Optional.of(proofCaptor.getValue()));
        when(mapper.invalidateConfirmation(old.id(), 41)).thenReturn(1);
        when(mapper.abandonDraft(org.mockito.ArgumentMatchers.eq(old.id()),
                org.mockito.ArgumentMatchers.eq(41L), any())).thenReturn(1);
        org.mockito.Mockito.clearInvocations(mapper);
        sourceRun.put("id", UUID.randomUUID().toString());
        sourceRun.put("contextSnapshotJson", "{\"taskWorkspace\":{\"activeDraftId\":\"" + old.id() + "\"}}");
        proposed.put("draftOperation", "REVISE").put("previousDraftId", Long.toString(old.id()));
        ((ObjectNode) proposed.path("parameters")).put("count", 2);

        service.acceptProposal(json.treeToValue(sourceRun, AgentRunRecord.class), event(proposed), "", now());
        verify(mapper).insertDraft(draftCaptor.capture());
        assertThat(draftCaptor.getValue().previousDraftId()).isEqualTo(old.id());
        assertThat(draftCaptor.getValue().productId()).isNull();
        assertThat(read(draftCaptor.getValue().parametersJson()).path("sourceMode").asText()).isEqualTo("CURRENT_ATTACHMENTS");
        assertThat(read(draftCaptor.getValue().inputSnapshotJson()).path("authorizedContext").isEmpty()).isTrue();
        verify(mapper).invalidateConfirmation(old.id(), 41);
        verify(mapper, never()).lockPointAccount(anyLong());
    }

    @Test
    void chatProposalMutationsRequireRollbackSavepointForEveryException() throws Exception {
        var transaction = TaskControlService.class.getMethod("acceptProposal", AgentRunRecord.class,
                PythonChatRunEvent.class, String.class, OffsetDateTime.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(transaction.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.NESTED);
        assertThat(transaction.noRollbackFor()).isEmpty();
        var readonlyValidation = com.guanghe.backend.catalog.product.ProductIdentityAssetService.class
                .getMethod("requireReadyIdentityAssets", long.class, long.class, long.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(readonlyValidation.readOnly()).isTrue();
        assertThat(readonlyValidation.noRollbackFor()).containsExactly(BusinessException.class);
    }

    @Test
    void switchedScopeCannotInheritOldDraftAndRunningTasksAreNotRevisionCandidates() {
        DraftFixture fixture = persistDraft();
        when(mapper.findActiveWorkspaceDraft(org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.eq(9001L), any())).thenReturn(Optional.of(fixture.draft));
        var scoped = service.read(41, 9001, 101L, 999L);
        assertThat(scoped.activeDraftId()).isNull();
        assertThat(scoped.confirmedConstraints().isEmpty()).isTrue();
        var sameScope = service.read(41, 9001, 101L, 102L);
        assertThat(sameScope.activeDraftId()).isEqualTo(Long.toString(fixture.draft.id()));
        assertThat(sameScope.confirmedConstraints().path("count").asInt()).isEqualTo(1);
        when(mapper.findActiveWorkspaceDraft(org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.eq(9001L), any())).thenReturn(Optional.empty());
        when(mapper.findActiveWorkspaceTasks(41, 9001)).thenReturn(List.of(task(UUID.randomUUID(), "a".repeat(64))));
        var running = service.read(41, 9001, 101L, 102L);
        assertThat(running.activeDraftId()).isNull();
        assertThat(running.activeTaskId()).isNotNull();
        assertThat(running.confirmedConstraints().isEmpty()).isTrue();
    }

    @Test
    void fourCandidatesAreQuotedByJavaWithoutFreezingPoints() {
        ObjectNode proposed = proposal();
        ((ObjectNode) proposed.path("parameters")).put("count", 4);
        service.acceptProposal(run(), event(proposed), "已整理参数", now());

        ArgumentCaptor<TaskRecords.Draft> drafts = ArgumentCaptor.forClass(TaskRecords.Draft.class);
        verify(mapper).insertDraft(drafts.capture());
        assertThat(drafts.getValue().quotedPoints()).isEqualTo(28);
        assertThat(read(drafts.getValue().inputSnapshotJson()).path("parameters").path("count").asInt())
                .isEqualTo(4);
        verify(mapper, never()).lockPointAccount(anyLong());
        verify(mapper, never()).insertTask(any());
    }

    @Test
    void outOfRangeImageCountsCannotCreateDrafts() {
        for (int count : new int[] {0, 5}) {
            ObjectNode proposed = proposal();
            ((ObjectNode) proposed.path("parameters")).put("count", count);
            assertThatThrownBy(() -> service.acceptProposal(run(), event(proposed), "", now()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("TASK_DRAFT_SCHEMA_INVALID");
        }
        verify(mapper, never()).insertDraft(any());
    }

    @Test
    void imageVisualBriefIsFrozenWithoutChangingQuoteOrProductFacts() {
        ObjectNode proposed = proposal();
        ((ObjectNode) proposed.path("parameters")).set("visualBrief", visualBrief());
        service.acceptProposal(run(), event(proposed), "已规划主图", now());
        ArgumentCaptor<TaskRecords.Draft> drafts = ArgumentCaptor.forClass(TaskRecords.Draft.class);
        verify(mapper).insertDraft(drafts.capture());
        assertThat(read(drafts.getValue().parametersJson()).path("visualBrief")).isEqualTo(visualBrief());
        assertThat(read(drafts.getValue().inputSnapshotJson()).path("parameters").path("visualBrief"))
                .isEqualTo(visualBrief());
        assertThat(drafts.getValue().quotedPoints()).isEqualTo(7);
        assertThat(read(drafts.getValue().inputSnapshotJson()).path("authorizedContext").has("visualBrief")).isFalse();
        verify(mapper, never()).lockPointAccount(anyLong());
    }

    @Test
    void imageVisualBriefRejectsUnknownFieldsBoundsAndTextPolicyMismatch() {
        List<ObjectNode> invalid = new java.util.ArrayList<>();
        invalid.add(visualBrief().put("price", "9.9"));
        invalid.add(visualBrief().put("platform", "UNKNOWN"));
        invalid.add(visualBrief().put("composition", "x".repeat(241)));
        invalid.add(visualBrief().put("textPolicy", "USER_SUPPLIED"));
        ObjectNode noneWithCopy = visualBrief();
        noneWithCopy.withArray("copyLines").add("特价");
        invalid.add(noneWithCopy);
        ObjectNode extraCopies = visualBrief().put("textPolicy", "USER_SUPPLIED");
        extraCopies.withArray("copyLines").add("一").add("二").add("三").add("四");
        invalid.add(extraCopies);
        for (ObjectNode brief : invalid) {
            ObjectNode proposed = proposal();
            ((ObjectNode) proposed.path("parameters")).set("visualBrief", brief);
            assertThatThrownBy(() -> service.acceptProposal(run(), event(proposed), "", now()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("TASK_DRAFT_SCHEMA_INVALID");
        }
        ObjectNode nullBrief = proposal();
        ((ObjectNode) nullBrief.path("parameters")).putNull("visualBrief");
        assertThatThrownBy(() -> service.acceptProposal(run(), event(nullBrief), "", now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("TASK_DRAFT_SCHEMA_INVALID");
        verify(mapper, never()).insertDraft(any());
    }

    @Test
    void imageCopyMustComeFromCurrentUserMessageNotModelRequestText() {
        ObjectNode proposed = proposal();
        ObjectNode parameters = (ObjectNode) proposed.path("parameters");
        ObjectNode brief = visualBrief().put("textPolicy", "USER_SUPPLIED");
        brief.withArray("copyLines").add("全网最低价");
        parameters.set("visualBrief", brief);
        parameters.put("requestText", "请加上全网最低价");
        assertThatThrownBy(() -> service.acceptProposal(runWithUserText(run(), "做张拼多多主图"),
                event(proposed), "", now())).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TASK_DRAFT_SCHEMA_INVALID");
        verify(mapper, never()).insertDraft(any());
    }

    @Test
    void explicitlyProvidedCopyCanBeRetainedByARevisionWithoutRestatingIt() {
        ObjectNode proposed = proposal();
        ObjectNode brief = visualBrief().put("textPolicy", "USER_SUPPLIED");
        brief.withArray("copyLines").add("清爽一夏");
        ((ObjectNode) proposed.path("parameters")).set("visualBrief", brief);
        service.acceptProposal(runWithUserText(run(), "图上写清爽一夏"), event(proposed), "", now());
        ArgumentCaptor<TaskRecords.Draft> draftCaptor = ArgumentCaptor.forClass(TaskRecords.Draft.class);
        ArgumentCaptor<TaskRecords.Confirmation> confirmationCaptor = ArgumentCaptor.forClass(TaskRecords.Confirmation.class);
        verify(mapper).insertDraft(draftCaptor.capture());
        verify(mapper).insertConfirmation(confirmationCaptor.capture());
        TaskRecords.Draft previous = draftCaptor.getValue();
        org.mockito.Mockito.clearInvocations(mapper);
        when(mapper.lockDraft(previous.id(), 41)).thenReturn(Optional.of(previous));
        when(mapper.lockConfirmation(previous.id(), 41)).thenReturn(Optional.of(confirmationCaptor.getValue()));
        when(mapper.invalidateConfirmation(previous.id(), 41)).thenReturn(1);
        when(mapper.abandonDraft(org.mockito.ArgumentMatchers.eq(previous.id()),
                org.mockito.ArgumentMatchers.eq(41L), any())).thenReturn(1);
        ObjectNode revision = revisionProposal(previous.id());
        ((ObjectNode) revision.path("parameters")).set("visualBrief", brief.deepCopy().put("background", "暖色背景"));
        service.acceptProposal(runWithUserText(runWithDraft(previous.id()), "背景暖一些，其他不变"),
                event(revision), "", now());
        verify(mapper).insertDraft(draftCaptor.capture());
        assertThat(draftCaptor.getValue().previousDraftId()).isEqualTo(previous.id());
        assertThat(read(draftCaptor.getValue().parametersJson()).path("visualBrief").path("copyLines").get(0).asText())
                .isEqualTo("清爽一夏");
    }

    private ObjectNode visualBrief() {
        ObjectNode brief = json.createObjectNode();
        brief.put("version", "commerce-image-v1").put("platform", "PINDUODUO").put("placement", "MAIN_IMAGE")
                .put("visualFocus", "商品主体").put("composition", "主体占画面中心约七成")
                .put("background", "简洁高对比背景").put("lighting", "明亮柔光").put("textPolicy", "NONE");
        brief.putArray("copyLines");
        return brief;
    }

    private AgentRunRecord runWithUserText(AgentRunRecord current, String text) {
        ObjectNode context = (ObjectNode) read(current.contextSnapshotJson());
        context.putObject("conversationMemory").putArray("messages").addObject()
                .put("messageId", Long.toString(current.requestMessageId())).put("role", "USER")
                .putObject("content").put("text", text);
        return new AgentRunRecord(current.id(), current.conversationId(), current.userId(), current.requestMessageId(),
                current.responseMessageId(), current.selectedSpaceId(), current.selectedProductId(),
                current.attachmentAssetIdsJson(), current.status(), current.contextVersion(), context.toString(),
                current.contextRefHash(), current.contextExpiresAt(), current.pythonEventsUrl(), current.lastPythonSequence(),
                current.usageJson(), current.errorCode(), current.traceId(), current.stopRequestedAt(), current.startedAt(),
                current.completedAt(), current.createdAt(), current.updatedAt(), current.deadlineAt());
    }

    @Test
    void nullableCreativeSourceHintsAreTreatedAsOmittedAndRebuiltFromJavaAuthority() {
        ObjectNode proposal = proposal();
        proposal.putNull("sourceMode");
        proposal.putNull("referenceAssetIds");
        proposal.putNull("sourceMessageId");
        proposal.putNull("productCreativeBrief");

        service.acceptProposal(run(), event(proposal), "已整理参数", now());

        ArgumentCaptor<TaskRecords.Draft> draftCaptor = ArgumentCaptor.forClass(TaskRecords.Draft.class);
        verify(mapper).insertDraft(draftCaptor.capture());
        JsonNode parameters = read(draftCaptor.getValue().parametersJson());
        assertThat(parameters.path("sourceMode").asText()).isEqualTo("CATALOG_PRODUCT");
        assertThat(parameters.path("referenceAssetIds").toString()).isEqualTo("[\"9901\"]");
    }

    @Test
    void nonNullCreativeSourceHintsMustStillMatchJavaAuthority() {
        ObjectNode proposal = proposal();
        proposal.put("sourceMessageId", "9999");

        assertThatThrownBy(() -> service.acceptProposal(run(), event(proposal), "", now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TASK_DRAFT_SCHEMA_INVALID");
        verify(mapper, never()).insertDraft(any());
    }

    @Test
    void confirmationAtomicallyFreezesPointsCreatesOneTaskLedgerAndOutbox() {
        DraftFixture fixture = persistDraft(4);
        var platform = mock(com.guanghe.backend.agentplatform.AgentPlatformService.class);
        service.setAgentPlatformService(platform);
        when(platform.assertTaskConfirmationAllowed(41, 9001)).thenReturn(
                new com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity(
                        "81", "geo", "1.0.0", "GEO", null, List.of("chat", "image.generate")));
        when(mapper.findTaskByIdempotency(anyLong(), any())).thenReturn(Optional.empty());
        when(mapper.lockDraft(fixture.draft.id(), 41)).thenReturn(Optional.of(fixture.draft));
        when(mapper.findTaskByDraft(fixture.draft.id())).thenReturn(Optional.empty());
        when(mapper.lockConfirmation(fixture.draft.id(), 41)).thenReturn(Optional.of(fixture.confirmation));
        when(mapper.lockPointAccount(41)).thenReturn(Optional.of(
                new TaskRecords.PointAccount(41, 100, 3, 1, now(), now())));
        when(mapper.findTaskFunding(org.mockito.ArgumentMatchers.eq(41L), any()))
                .thenAnswer(call -> List.of(new TaskRecords.TaskFunding(
                        ((List<Long>) call.getArgument(1)).getFirst(), 28, 0, 0)));
        when(mapper.consumeConfirmation(anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.confirmDraft(anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.insertTask(any())).thenReturn(1);
        when(mapper.freezePoints(anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertReservation(anyLong(), anyLong(), anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertLedger(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), anyString(),
                anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertTaskEvent(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(),
                anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        when(mapper.insertOutbox(any(), anyString(), anyString())).thenReturn(1);

        TaskControlService.ConfirmedTask confirmed = service.confirm(user(), fixture.draft.id(),
                UUID.randomUUID().toString(), new TaskDtos.ConfirmRequest(fixture.token,
                        fixture.draft.inputHash(), Long.toString(fixture.draft.pricingVersionId())));

        assertThat(confirmed.response().status()).isEqualTo("QUEUED");
        assertThat(confirmed.response().frozenPoints()).isEqualTo(28);
        ArgumentCaptor<TaskRecords.Outbox> outbox = ArgumentCaptor.forClass(TaskRecords.Outbox.class);
        verify(mapper).insertOutbox(outbox.capture(), anyString(), anyString());
        assertThat(read(outbox.getValue().payloadJson()).path("taskType").asText()).isEqualTo("IMAGE");
        verify(mapper).freezePoints(org.mockito.ArgumentMatchers.eq(41L), org.mockito.ArgumentMatchers.eq(28), any());
        verify(mapper).insertTask(any());
        verify(mapper).insertLedger(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(),
                anyString(), anyLong(), anyString(), anyString(), any());
        verify(platform).assertTaskConfirmationAllowed(41, 9001);
        verify(platform, never()).assertRunAllowed(anyLong(), anyLong());
    }

    @Test
    void disabledInstallationRejectsNewConfirmationWithoutCharging() {
        DraftFixture fixture = persistDraft();
        var platform = mock(com.guanghe.backend.agentplatform.AgentPlatformService.class);
        service.setAgentPlatformService(platform);
        when(mapper.lockDraft(fixture.draft.id(), 41)).thenReturn(Optional.of(fixture.draft));
        when(platform.assertTaskConfirmationAllowed(41, 9001))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.confirm(user(), fixture.draft.id(), UUID.randomUUID().toString(),
                new TaskDtos.ConfirmRequest(fixture.token, fixture.draft.inputHash(),
                        Long.toString(fixture.draft.pricingVersionId()))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(mapper, never()).lockPointAccount(anyLong());
        verify(mapper, never()).insertTask(any());
        verify(platform, never()).assertRunAllowed(anyLong(), anyLong());
    }

    @Test
    void legacyConversationConfirmationCannotReconstructProofEvenWithOneLiveDraft() {
        DraftFixture fixture = persistDraft();
        when(mapper.findActiveWorkspaceDraftIds(anyLong(), anyLong(), any()))
                .thenReturn(List.of(fixture.draft.id()));
        assertThatThrownBy(() -> service.confirmActiveConversationDraft(
                user(), fixture.draft.conversationId(), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.TASK_CONFIRMATION_MISMATCH));
        verify(mapper, never()).freezePoints(anyLong(), anyInt(), any());
        verify(mapper, never()).insertTask(any());
        verify(mapper, never()).findActiveWorkspaceDraftIds(anyLong(), anyLong(), any());
    }

    @Test
    void conversationConfirmationRejectsMissingOrAmbiguousTargetsBeforeCharging() {
        when(mapper.findActiveWorkspaceDraftIds(anyLong(), anyLong(), any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.confirmActiveConversationDraft(
                user(), 9001, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.TASK_CONFIRMATION_MISMATCH));

        when(mapper.findActiveWorkspaceDraftIds(anyLong(), anyLong(), any()))
                .thenReturn(List.of(7001L, 7002L));
        assertThatThrownBy(() -> service.confirmActiveConversationDraft(
                user(), 9001, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.TASK_CONFIRMATION_MISMATCH));

        verify(mapper, never()).freezePoints(anyLong(), anyInt(), any());
        verify(mapper, never()).insertTask(any());
    }

    @Test
    void workspaceContextHidesRetryAndWorkerInternalsFromTheAgent() {
        OffsetDateTime timestamp = now();
        TaskRecords.Task retrying = new TaskRecords.Task(
                9200, 41, 101, 102L, 9001, 7501, "GEO", "RETRY_WAIT", "WORKER_LEASE_EXPIRED",
                1, "{}", "b".repeat(64), 8001, 30, 0, "{}", "WORKER_LEASE_EXPIRED",
                "TASK_CONFIRM", UUID.randomUUID(), "a".repeat(64), timestamp, timestamp,
                null, timestamp, timestamp
        );
        when(mapper.findActiveWorkspaceTasks(41, 9001)).thenReturn(List.of(retrying));

        var state = service.read(41, 9001);

        assertThat(state.runningTasks()).singleElement().satisfies(task -> {
            assertThat(task.status()).isEqualTo("RUNNING");
            assertThat(task.stageCode()).isEqualTo("COLLECTING_RESULTS");
            assertThat(task.stageCode()).doesNotContain("LEASE", "WORKER", "RETRY");
        });
    }

    @Test
    void legacyConfirmationCannotUseAnIdempotencyKeyInPlaceOfDisplayedProof() {
        UUID idempotencyKey = UUID.randomUUID();
        TaskRecords.Task task = task(idempotencyKey, "a".repeat(64));
        when(mapper.findTaskByIdempotency(41, idempotencyKey)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.confirmActiveConversationDraft(
                user(), task.conversationId(), idempotencyKey.toString()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.TASK_CONFIRMATION_MISMATCH));
        verify(mapper, never()).findActiveWorkspaceDraftIds(anyLong(), anyLong(), any());
        verify(mapper, never()).freezePoints(anyLong(), anyInt(), any());
    }

    @Test
    void mediaConfirmationRevalidatesThenUsesLocalExecutionWithoutPythonOutbox() {
        OffsetDateTime now = now();
        TaskRecords.Draft draft = new TaskRecords.Draft(8_501, 41, null, null, null,
                "FORM", "MEDIA_QUOTE_CREATE", UUID.randomUUID(), "a".repeat(64), "MEDIA", 1,
                101, null, "{\"articleId\":\"501\",\"articleSnapshotId\":\"502\",\"items\":[]}",
                "{\"taskType\":\"MEDIA\",\"articleId\":\"501\",\"articleSnapshotId\":\"502\",\"items\":[]}",
                "b".repeat(64), 8_502, 49_700, "[]", "WAITING_CONFIRMATION",
                now.plusMinutes(10), null, now, now);
        String token = service.confirmationToken(draft);
        TaskRecords.Confirmation confirmation = new TaskRecords.Confirmation(8_503, draft.id(), 41,
                sha256(token), draft.inputHash(), draft.pricingVersionId(), "AVAILABLE",
                draft.expiresAt(), null, now);
        when(mapper.findTaskByIdempotency(anyLong(), any())).thenReturn(Optional.empty());
        when(mapper.lockDraft(draft.id(), 41)).thenReturn(Optional.of(draft));
        when(mapper.findTaskByDraft(draft.id())).thenReturn(Optional.empty());
        when(mapper.lockConfirmation(draft.id(), 41)).thenReturn(Optional.of(confirmation));
        when(mapper.lockPointAccount(41)).thenReturn(Optional.of(
                new TaskRecords.PointAccount(41, 100_000, 0, 1, now, now)));
        when(mapper.consumeConfirmation(anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.confirmDraft(anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.insertTask(any())).thenReturn(1);
        when(mapper.freezePoints(anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertReservation(anyLong(), anyLong(), anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertLedger(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), anyString(),
                anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertTaskEvent(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(),
                anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(1);

        TaskControlService.ConfirmedTask confirmed = service.confirm(user(), draft.id(),
                UUID.randomUUID().toString(), new TaskDtos.ConfirmRequest(token, draft.inputHash(), "8502"));

        assertThat(confirmed.response().id()).isNotBlank();
        assertThat(confirmed.browserEvent()).isNull();
        verify(mediaSubmissions).revalidateForConfirmation(draft);
        ArgumentCaptor<TaskRecords.Task> task = ArgumentCaptor.forClass(TaskRecords.Task.class);
        verify(mapper).insertTask(task.capture());
        assertThat(task.getValue().conversationId()).isNull();
        assertThat(task.getValue().taskType()).isEqualTo("MEDIA");
        verify(mediaSubmissions).createConfirmedBatch(org.mockito.ArgumentMatchers.eq(task.getValue()),
                org.mockito.ArgumentMatchers.eq(draft), any());
        verify(mapper, never()).insertOutbox(any(), anyString(), anyString());
    }

    @Test
    void geoConfirmationUsesJavaQuoteCreatesRunAndRoutesExactlyOnce() throws Exception {
        String token = "v1.geo-confirmation-token";
        UUID draftKey = UUID.randomUUID();
        TaskRecords.Draft draft = new TaskRecords.Draft(
                7501, 41, 9001L, null, null, "FORM", "TASK_DRAFT_CREATE", draftKey,
                "a".repeat(64), "GEO", 1, 101, null,
                "{\"mode\":\"MONITOR_RUN\",\"topicId\":\"201\",\"questionIds\":[\"301\",\"302\",\"303\"],"
                        + "\"platformCodes\":[\"DOUBAO\",\"WENXIN\"],\"sampleMode\":\"STANDARD\","
                        + "\"region\":\"中国大陆\"}",
                "{\"taskType\":\"GEO\",\"parameters\":{}}", "b".repeat(64),
                8001, 6, "[]", "WAITING_CONFIRMATION", now().plusMinutes(10), null, now(), now());
        TaskRecords.Confirmation confirmation = new TaskRecords.Confirmation(
                7502, draft.id(), 41, sha256(token), draft.inputHash(), 8001,
                "AVAILABLE", now().plusMinutes(10), null, now());
        when(geoTasks.quoteDraft(anyLong(), anyLong(), any(), any())).thenReturn(6);
        when(mapper.findTaskByIdempotency(anyLong(), any())).thenReturn(Optional.empty());
        when(mapper.lockDraft(draft.id(), 41)).thenReturn(Optional.of(draft));
        when(mapper.findTaskByDraft(draft.id())).thenReturn(Optional.empty());
        when(mapper.lockConfirmation(draft.id(), 41)).thenReturn(Optional.of(confirmation));
        when(mapper.lockPointAccount(41)).thenReturn(Optional.of(
                new TaskRecords.PointAccount(41, 20, 0, 1, now(), now())));
        when(mapper.findTaskFunding(org.mockito.ArgumentMatchers.eq(41L), any()))
                .thenAnswer(call -> List.of(new TaskRecords.TaskFunding(
                        ((List<Long>) call.getArgument(1)).getFirst(), 6, 0, 0)));
        when(mapper.consumeConfirmation(anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.confirmDraft(anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.insertTask(any())).thenReturn(1);
        when(mapper.freezePoints(anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertReservation(anyLong(), anyLong(), anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertLedger(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), anyString(),
                anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertTaskEvent(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(),
                anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        when(mapper.insertOutbox(any(), anyString(), anyString())).thenReturn(1);

        TaskControlService.ConfirmedTask confirmed = service.confirm(user(), draft.id(),
                UUID.randomUUID().toString(), new TaskDtos.ConfirmRequest(token, draft.inputHash(), "8001"));

        assertThat(confirmed.response().frozenPoints()).isEqualTo(6);
        assertThat(read(confirmed.browserEvent().payloadJson()).path("deliveryKind").asText())
                .isEqualTo("BACKGROUND_TASK");
        assertThat(confirmed.browserEvent().messageId()).isNull();
        assertThat(Long.toString(confirmed.browserEvent().taskId())).isEqualTo(confirmed.response().id());
        ArgumentCaptor<TaskRecords.Task> storedTask = ArgumentCaptor.forClass(TaskRecords.Task.class);
        verify(mapper).insertTask(storedTask.capture());
        verify(geoTasks).createRun(org.mockito.ArgumentMatchers.eq(storedTask.getValue()), any());
        verify(mapper).freezePoints(41, 6, storedTask.getValue().createdAt());
        verify(mapper).insertOutbox(any(), org.mockito.ArgumentMatchers.eq("zhixiaohe.task"),
                org.mockito.ArgumentMatchers.eq("agent.geo"));
    }

    @Test
    void geoDiscoveryProposalWritesFoundationOnlyAfterTheSinglePaidConfirmation() throws Exception {
        ObjectNode proposal = geoDiscoveryProposal();
        ObjectNode normalized = (ObjectNode) proposal.path("parameters").deepCopy();
        normalized.putArray("platformCodes").add("DOUBAO").add("WENXIN");
        normalized.put("sampleMode", "STANDARD");
        ObjectNode discoverySnapshot = json.createObjectNode();
        discoverySnapshot.put("taskType", "GEO").put("parameterSchemaVersion", 1);
        discoverySnapshot.set("parameters", normalized.deepCopy());
        GeoTaskService.PreparedDraft discovery = new GeoTaskService.PreparedDraft(
                normalized, discoverySnapshot, 6);
        when(geoTasks.isQuestionDiscovery(any())).thenAnswer(invocation ->
                "QUESTION_DISCOVERY".equals(((JsonNode) invocation.getArgument(0)).path("mode").asText()));
        when(geoTasks.prepareDiscoveryDraft(anyLong(), anyLong(), any(), any())).thenReturn(discovery);
        when(geoTasks.quote(discovery)).thenReturn(6);

        ConversationEventRecord browserEvent = service.acceptProposal(
                run(), event(proposal), "已生成需求词专题和问题", now());

        ArgumentCaptor<TaskRecords.Draft> draftCaptor = ArgumentCaptor.forClass(TaskRecords.Draft.class);
        ArgumentCaptor<TaskRecords.Confirmation> confirmationCaptor =
                ArgumentCaptor.forClass(TaskRecords.Confirmation.class);
        verify(mapper).insertDraft(draftCaptor.capture());
        verify(mapper).insertConfirmation(confirmationCaptor.capture());
        TaskRecords.Draft draft = draftCaptor.getValue();
        assertThat(read(draft.parametersJson()).path("mode").asText()).isEqualTo("QUESTION_DISCOVERY");
        assertThat(draft.quotedPoints()).isEqualTo(6);
        verify(geoTasks, never()).materializeDiscoveryDraft(anyLong(), anyLong(), any(), any());

        ObjectNode executableParameters = (ObjectNode) json.readTree("""
                {"mode":"MONITOR_RUN","topicId":"9901","questionIds":["9902","9903","9904"],
                 "platformCodes":["DOUBAO","WENXIN"],"sampleMode":"STANDARD","region":"全国"}
                """);
        ObjectNode executableSnapshot = json.createObjectNode();
        executableSnapshot.put("taskType", "GEO").put("parameterSchemaVersion", 1);
        executableSnapshot.set("parameters", executableParameters);
        GeoTaskService.PreparedDraft executable = new GeoTaskService.PreparedDraft(
                executableParameters, executableSnapshot, 6);
        when(geoTasks.quoteDraft(41, 101, 102L, normalized)).thenReturn(6);
        when(geoTasks.materializeDiscoveryDraft(41, 101, 102L, normalized)).thenReturn(executable);
        when(mapper.findTaskByIdempotency(anyLong(), any())).thenReturn(Optional.empty());
        when(mapper.lockDraft(draft.id(), 41)).thenReturn(Optional.of(draft));
        when(mapper.findTaskByDraft(draft.id())).thenReturn(Optional.empty());
        when(mapper.lockConfirmation(draft.id(), 41)).thenReturn(Optional.of(confirmationCaptor.getValue()));
        when(mapper.lockPointAccount(41)).thenReturn(Optional.of(
                new TaskRecords.PointAccount(41, 20, 0, 1, now(), now())));
        when(mapper.consumeConfirmation(anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.confirmDraft(anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.insertTask(any())).thenReturn(1);
        when(mapper.freezePoints(anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertReservation(anyLong(), anyLong(), anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertLedger(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), anyString(),
                anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertTaskEvent(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(),
                anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        when(mapper.insertOutbox(any(), anyString(), anyString())).thenReturn(1);

        String token = read(browserEvent.payloadJson()).path("draft").path("confirmationToken").asText();
        service.confirm(user(), draft.id(), UUID.randomUUID().toString(),
                new TaskDtos.ConfirmRequest(token, draft.inputHash(), "8001"));

        ArgumentCaptor<TaskRecords.Task> taskCaptor = ArgumentCaptor.forClass(TaskRecords.Task.class);
        verify(mapper).insertTask(taskCaptor.capture());
        TaskRecords.Task task = taskCaptor.getValue();
        assertThat(read(task.inputSnapshotJson()).path("parameters").path("mode").asText())
                .isEqualTo("MONITOR_RUN");
        assertThat(task.inputHash()).isNotEqualTo(draft.inputHash());
        verify(geoTasks).materializeDiscoveryDraft(41, 101, 102L, normalized);
        verify(geoTasks).createRun(task, task.createdAt());
    }

    @Test
    void articleFormDraftUsesJavaSnapshotPriceAndDedicatedRouting() {
        ObjectNode parameters = json.createObjectNode();
        parameters.put("mode", "TITLE").put("articleId", "501").put("articleVersion", 1)
                .put("brief", "生成标题").putNull("selectedTitle").putNull("rewriteInstruction")
                .put("targetLength", "SHORT").put("tone", "PROFESSIONAL")
                .putNull("targetPlatform").putNull("geoReportId");
        parameters.putArray("confirmedOutline");
        parameters.putNull("confirmedPlan");
        parameters.putArray("rewriteBlockIds");
        parameters.putArray("sourceAssetIds");
        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("taskType", "ARTICLE").put("parameterSchemaVersion", 2);
        snapshot.set("parameters", parameters.deepCopy());
        snapshot.putObject("authorizedContext").putObject("article").put("id", "501");
        when(conversations.findConversation(9001, 41)).thenReturn(Optional.of(
                mock(com.guanghe.backend.conversation.ConversationRecord.class)));
        when(articleService.prepareTask(41, 101, null, parameters)).thenReturn(
                new ArticleService.PreparedTask(parameters, snapshot, "TITLE"));

        TaskDtos.DraftResponse response = service.createDraft(user(), UUID.randomUUID().toString(),
                new TaskDtos.CreateDraftRequest("9001", "ARTICLE", "101", null, 2, parameters));
        assertThat(response.taskType()).isEqualTo("ARTICLE");
        assertThat(response.draftSource()).isEqualTo("FORM");
        assertThat(response.quotedPoints()).isEqualTo(3);
        ArgumentCaptor<TaskRecords.Draft> drafts = ArgumentCaptor.forClass(TaskRecords.Draft.class);
        ArgumentCaptor<TaskRecords.Confirmation> confirmations = ArgumentCaptor.forClass(TaskRecords.Confirmation.class);
        verify(mapper).insertDraft(drafts.capture());
        verify(mapper).insertConfirmation(confirmations.capture());
        TaskRecords.Draft draft = drafts.getValue();
        assertThat(draft.agentRunId()).isNull();
        assertThat(draft.inputSnapshotJson()).contains("ARTICLE", "authorizedContext")
                .doesNotContain("confirmationToken");

        when(mapper.findTaskByIdempotency(anyLong(), any())).thenReturn(Optional.empty());
        when(mapper.lockDraft(draft.id(), 41)).thenReturn(Optional.of(draft));
        when(mapper.findTaskByDraft(draft.id())).thenReturn(Optional.empty());
        when(mapper.lockConfirmation(draft.id(), 41)).thenReturn(Optional.of(confirmations.getValue()));
        when(mapper.lockPointAccount(41)).thenReturn(Optional.of(
                new TaskRecords.PointAccount(41, 20, 0, 1, now(), now())));
        when(mapper.consumeConfirmation(anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.confirmDraft(anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.insertTask(any())).thenReturn(1);
        when(mapper.freezePoints(anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertReservation(anyLong(), anyLong(), anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertLedger(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), anyString(),
                anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertTaskEvent(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(),
                anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        when(mapper.insertOutbox(any(), anyString(), anyString())).thenReturn(1);
        service.confirm(user(), draft.id(), UUID.randomUUID().toString(),
                new TaskDtos.ConfirmRequest(response.confirmationToken(), response.inputHash(),
                        response.pricingVersionId()));
        verify(mapper).insertOutbox(any(), org.mockito.ArgumentMatchers.eq("zhixiaohe.task"),
                org.mockito.ArgumentMatchers.eq("agent.article"));
    }

    @Test
    void workflowArticleCandidateCreatesOneJavaOwnedTitleDraftAndReplaysBeforeCreatingAgain() {
        ObjectNode candidate = (ObjectNode) articleProposal().path("parameters");
        ObjectNode parameters = json.createObjectNode().put("mode", "TITLE")
                .put("articleId", "501").put("articleVersion", 1).put("brief", "咖啡好喝又健康");
        ObjectNode snapshot = json.createObjectNode().put("taskType", "ARTICLE").put("parameterSchemaVersion", 2);
        snapshot.set("parameters", parameters);
        snapshot.putObject("authorizedContext").putObject("article").put("id", "501");
        when(conversations.findConversation(9001, 41)).thenReturn(Optional.of(
                mock(com.guanghe.backend.conversation.ConversationRecord.class)));
        when(articleService.prepareChatTitleTask(41, 101, 102L, candidate))
                .thenReturn(new ArticleService.PreparedTask(parameters, snapshot, "TITLE"));
        UUID key = UUID.randomUUID();

        TaskControlService.WorkflowPreparedDraft prepared = service.prepareWorkflowDraft(
                41, 9001, "ARTICLE", 101, 102L, 2, candidate, key);

        ArgumentCaptor<TaskRecords.Draft> drafts = ArgumentCaptor.forClass(TaskRecords.Draft.class);
        verify(mapper).insertDraft(drafts.capture());
        TaskRecords.Draft draft = drafts.getValue();
        assertThat(draft.draftSource()).isEqualTo("PLAN");
        assertThat(draft.operationScope()).isEqualTo("WORKFLOW_PLAN_DRAFT_CREATE");
        assertThat(draft.parameterSchemaVersion()).isEqualTo(2);
        assertThat(draft.quotedPoints()).isEqualTo(3);
        assertThat(read(draft.parametersJson()).path("articleId").asText()).isEqualTo("501");
        assertThat(read(draft.parametersJson()).path("mode").asText()).isEqualTo("TITLE");
        assertThat(read(draft.inputSnapshotJson()).path("parameterSchemaVersion").asInt()).isEqualTo(2);
        assertThat(prepared.inputHash()).isEqualTo(draft.inputHash());
        when(mapper.findDraftByIdempotency(41, "WORKFLOW_PLAN_DRAFT_CREATE", key)).thenReturn(Optional.of(draft));

        assertThat(service.prepareWorkflowDraft(41, 9001, "ARTICLE", 101, 102L, 2, candidate, key))
                .isEqualTo(prepared);
        verify(articleService).prepareChatTitleTask(41, 101, 102L, candidate);
        verify(mapper).insertDraft(any());
        verify(articleService, never()).prepareTask(anyLong(), anyLong(), any(), any());
        verify(mapper, never()).insertTask(any());
        verify(mapper, never()).freezePoints(anyLong(), anyInt(), any());
        verify(mapper, never()).insertOutbox(any(), anyString(), anyString());
        ObjectNode changed = candidate.deepCopy().put("topic", "另一个目标");
        assertCode(() -> service.prepareWorkflowDraft(41, 9001, "ARTICLE", 101, 102L, 2, changed, key),
                ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void workflowArticleRejectsOldSchemaAuthoritativeParametersAndReportBindingWithoutWrites() {
        when(conversations.findConversation(9001, 41)).thenReturn(Optional.of(
                mock(com.guanghe.backend.conversation.ConversationRecord.class)));
        ObjectNode candidate = (ObjectNode) articleProposal().path("parameters");
        assertCode(() -> service.prepareWorkflowDraft(41, 9001, "ARTICLE", 101, 102L, 1,
                candidate, UUID.randomUUID()), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.prepareWorkflowDraft(41, 9001, "ARTICLE", 101, 102L, 2,
                candidate.deepCopy().put("mode", "TITLE").put("articleId", "999"), UUID.randomUUID()),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.prepareWorkflowDraft(41, 9001, "ARTICLE", 101, 102L, 2,
                candidate.deepCopy().put("geoReportId", "999"), UUID.randomUUID()), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.prepareWorkflowDraft(41, 9002, "ARTICLE", 101, 102L, 2,
                candidate, UUID.randomUUID()), ErrorCode.RESOURCE_NOT_FOUND);
        verify(articleService, never()).prepareChatTitleTask(anyLong(), anyLong(), any(), any());
        verify(mapper, never()).insertDraft(any());
        verify(mapper, never()).insertTask(any());
    }

    @Test
    void articleChatProposalUsesJavaCreatedWorkspaceAndShowsConfirmationDraft() {
        ObjectNode proposal = articleProposal();
        ObjectNode normalized = json.createObjectNode();
        normalized.put("mode", "TITLE").put("articleId", "501").put("articleVersion", 1)
                .put("brief", "咖啡好喝又健康").putNull("selectedTitle").putNull("rewriteInstruction")
                .put("targetLength", "MEDIUM").put("tone", "PROFESSIONAL")
                .putNull("targetPlatform").putNull("geoReportId");
        normalized.putArray("confirmedOutline");
        normalized.putNull("confirmedPlan");
        normalized.putArray("rewriteBlockIds");
        normalized.putArray("sourceAssetIds");
        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("taskType", "ARTICLE").put("parameterSchemaVersion", 2);
        snapshot.set("parameters", normalized.deepCopy());
        snapshot.putObject("authorizedContext").putObject("article").put("id", "501");
        when(articleService.prepareChatTitleTask(41, 101, 102L, proposal.path("parameters"))).thenReturn(
                new ArticleService.PreparedTask(normalized, snapshot, "TITLE"));

        ConversationEventRecord event = service.acceptProposal(run(), event(proposal), "已整理文章要求", now());

        ArgumentCaptor<TaskRecords.Draft> drafts = ArgumentCaptor.forClass(TaskRecords.Draft.class);
        verify(mapper).insertDraft(drafts.capture());
        verify(articleService).prepareChatTitleTask(41, 101, 102L, proposal.path("parameters"));
        assertThat(drafts.getValue().taskType()).isEqualTo("ARTICLE");
        assertThat(drafts.getValue().quotedPoints()).isEqualTo(3);
        assertThat(read(drafts.getValue().parametersJson()).path("articleId").textValue()).isEqualTo("501");
        assertThat(read(event.payloadJson()).path("draft").path("taskType").textValue()).isEqualTo("ARTICLE");
    }

    @Test
    void chatArticleAcceptsOnlyTheReportFrozenForThisRun() {
        ObjectNode proposal = articleProposal();
        ((ObjectNode) proposal.path("parameters")).put("geoReportId", "501");
        ObjectNode prepared = json.createObjectNode().put("mode", "TITLE").put("articleId", "601");
        when(articleService.prepareChatTitleTask(41,101,102L,proposal.path("parameters"))).thenReturn(
                new ArticleService.PreparedTask(prepared,json.createObjectNode(),"TITLE"));
        service.acceptProposal(runWithGeoReport("501","101","102"), event(proposal), "根据这份报告写文章", now());
        verify(articleService).prepareChatTitleTask(41,101,102L,proposal.path("parameters"));
        verify(mapper).insertDraft(any());
        verify(mapper,never()).insertTask(any());
    }

    @Test
    void chatArticleRejectsInventedReportAndCrossProductScopeBeforeAnyArticleWrite() {
        ObjectNode proposal = articleProposal();
        ((ObjectNode) proposal.path("parameters")).put("geoReportId", "501");
        for (AgentRunRecord current : List.of(run(), runWithGeoReport("999","101","102"),
                runWithGeoReport("501","101","999"),runWithGeoReport("501","999","102"))) {
            assertThatThrownBy(() -> service.acceptProposal(current,event(proposal),"",now()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        }
        verify(articleService,never()).prepareChatTitleTask(anyLong(),anyLong(),any(),any());
        verify(mapper,never()).insertDraft(any());
    }

    @Test
    void newlyCreatedArticleCannotSilentlyBindAnAutoSelectedNewerReport() {
        ObjectNode proposal = articleProposal();
        ((ObjectNode) proposal.path("parameters")).put("geoReportId", "999");
        assertThatThrownBy(() -> service.acceptProposal(
                runWithGeoReport("999","101","102","AUTO_SINGLE_TOPIC"),event(proposal),"根据这份报告写文章",now()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verify(articleService,never()).prepareChatTitleTask(anyLong(),anyLong(),any(),any());
        verify(mapper,never()).insertDraft(any());
    }

    @Test
    void ordinaryArticleDoesNotSilentlyBindTheSidebarReport() {
        ObjectNode proposal = articleProposal();
        ObjectNode prepared = json.createObjectNode().put("mode", "TITLE").put("articleId", "601");
        when(articleService.prepareChatTitleTask(41,101,102L,proposal.path("parameters"))).thenReturn(
                new ArticleService.PreparedTask(prepared,json.createObjectNode(),"TITLE"));
        service.acceptProposal(runWithGeoReport("501","101","102"),event(proposal),"写普通文章",now());
        ArgumentCaptor<JsonNode> parameters = ArgumentCaptor.forClass(JsonNode.class);
        verify(articleService).prepareChatTitleTask(org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.eq(101L),org.mockito.ArgumentMatchers.eq(102L),parameters.capture());
        assertThat(parameters.getValue().has("geoReportId")).isFalse();
    }

    @Test
    void revisingBoundArticleKeepsOriginalReportEvenWhenLatestReportHasChanged() {
        ObjectNode original = json.createObjectNode().put("mode", "TITLE").put("articleId", "601")
                .put("articleVersion", 1).put("geoReportId", "501");
        lockArticleRevision(original);
        ObjectNode proposal = articleProposal().put("draftOperation", "REVISE").put("previousDraftId", "8501");
        when(articleService.prepareChatTitleRevision(anyLong(), anyLong(), any(), any(), any())).thenAnswer(call -> {
            JsonNode requested = call.getArgument(3);
            assertThat(requested.path("geoReportId").asText()).isEqualTo("501");
            return new ArticleService.PreparedTask(original.deepCopy(), json.createObjectNode(), "TITLE");
        });

        service.acceptProposal(articleRevisionRun(), event(proposal), "标题短一点", now());

        ArgumentCaptor<TaskRecords.Draft> revised = ArgumentCaptor.forClass(TaskRecords.Draft.class);
        verify(mapper).insertDraft(revised.capture());
        assertThat(read(revised.getValue().parametersJson()).path("geoReportId").asText()).isEqualTo("501");
        verify(mapper).invalidateConfirmation(8501, 41);
        verify(mapper, never()).insertTask(any());
    }

    @Test
    void articleRevisionCannotReplaceOrAddReportBinding() {
        for (boolean alreadyBound : new boolean[] {true, false}) {
            ObjectNode original = json.createObjectNode().put("mode", "TITLE").put("articleId", "601");
            if (alreadyBound) original.put("geoReportId", "501"); else original.putNull("geoReportId");
            lockArticleRevision(original);
            ObjectNode proposal = articleProposal().put("draftOperation", "REVISE").put("previousDraftId", "8501");
            ((ObjectNode) proposal.path("parameters")).put("geoReportId", "999");
            assertThatThrownBy(() -> service.acceptProposal(articleRevisionRun(), event(proposal), "标题短一点", now()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        }
        verify(articleService, never()).prepareChatTitleRevision(anyLong(), anyLong(), any(), any(), any());
        verify(mapper, never()).insertDraft(any());
    }

    @Test
    void geoTitleDirectionRevisionKeepsEverySourceAndOnlyReplacesBriefWithoutCharging() {
        ObjectNode original=json.createObjectNode().put("mode","TITLE").put("articleId","601")
                .put("articleVersion",1).put("geoReportId","501").put("geoRecommendationId","501:recommendation:1")
                .put("brief","旧方向");
        lockArticleRevision(original);
        when(articleService.prepareTask(org.mockito.ArgumentMatchers.eq(41L),org.mockito.ArgumentMatchers.eq(101L),
                org.mockito.ArgumentMatchers.eq(102L),any())).thenAnswer(invocation -> {
            ObjectNode parameters=invocation.getArgument(3);
            assertThat(parameters.path("articleId").asText()).isEqualTo("601");
            assertThat(parameters.path("articleVersion").asInt()).isEqualTo(1);
            assertThat(parameters.path("geoReportId").asText()).isEqualTo("501");
            assertThat(parameters.path("geoRecommendationId").asText()).isEqualTo("501:recommendation:1");
            return new ArticleService.PreparedTask(parameters,json.createObjectNode().set("parameters",parameters.deepCopy()),"TITLE");
        });
        UUID key=UUID.randomUUID();
        var result=service.reviseGeoArticleDraft(user(),8501,key,"通勤介绍方向","d".repeat(64));
        ArgumentCaptor<TaskRecords.Draft> revisions=ArgumentCaptor.forClass(TaskRecords.Draft.class);
        verify(mapper).insertDraft(revisions.capture());
        var revision=revisions.getValue();
        assertThat(revision.previousDraftId()).isEqualTo(8501);
        assertThat(revision.operationScope()).isEqualTo("TASK_DRAFT_CREATE");
        assertThat(revision.idempotencyKey()).isEqualTo(key);
        assertThat(revision.requestHash()).isEqualTo("d".repeat(64));
        assertThat(read(revision.parametersJson()).path("brief").asText()).isEqualTo("通勤介绍方向");
        assertThat(result.confirmationToken()).isNotBlank();
        verify(mapper).invalidateConfirmation(8501,41);
        verify(mapper).abandonDraft(org.mockito.ArgumentMatchers.eq(8501L),org.mockito.ArgumentMatchers.eq(41L),any());
        verify(mapper,never()).lockPointAccount(anyLong());
        verify(mapper,never()).insertTask(any());
    }

    @Test
    void geoTitleRevisionReplaysItsStableWorkflowKeyAndRejectsReuseForAnotherDirection() {
        ObjectNode original=json.createObjectNode().put("mode","TITLE").put("articleId","601").put("geoReportId","501");
        lockArticleRevision(original);
        UUID key=UUID.randomUUID();
        var stored=new TaskRecords.Draft(8503,41,9001L,null,8501L,"FORM","TASK_DRAFT_CREATE",key,"d".repeat(64),"ARTICLE",2,
                101,102L,original.toString(),"{}","b".repeat(64),9001,3,"[]","WAITING_CONFIRMATION",now().plusMinutes(10),null,now(),now());
        when(mapper.findDraftByIdempotency(41,"TASK_DRAFT_CREATE",key)).thenReturn(Optional.of(stored));
        assertThat(service.reviseGeoArticleDraft(user(),8501,key,"相同方向","d".repeat(64)).id()).isEqualTo("8503");
        assertCode(() -> service.reviseGeoArticleDraft(user(),8501,key,"其他方向","e".repeat(64)),ErrorCode.IDEMPOTENCY_KEY_REUSED);
        verify(articleService,never()).prepareTask(anyLong(),anyLong(),any(),any());
        verify(mapper,never()).insertDraft(any());
        verify(mapper,never()).abandonDraft(anyLong(),anyLong(),any());
    }

    @Test
    void geoDirectionEntryCannotReviseBodyOrConfirmedTitle() {
        for(String mode:List.of("BODY","REWRITE")) {
            lockArticleRevision(json.createObjectNode().put("mode",mode).put("articleId","601").put("geoReportId","501"));
            assertCode(() -> service.reviseGeoArticleDraft(user(),8501,UUID.randomUUID(),"新的方向","d".repeat(64)),ErrorCode.TASK_STATE_CONFLICT);
        }
        verify(mapper,never()).insertDraft(any());
        verify(mapper,never()).insertTask(any());
    }

    private void lockArticleRevision(ObjectNode parameters) {
        TaskRecords.Draft previous = new TaskRecords.Draft(8501,41,9001L,null,null,"CHAT",null,
                null,null,"ARTICLE",2,101,102L,parameters.toString(),"{}","b".repeat(64),
                9001,3,"[]","WAITING_CONFIRMATION",now().plusMinutes(10),null,now(),now());
        when(mapper.lockDraft(8501,41)).thenReturn(Optional.of(previous));
        when(mapper.lockConfirmation(8501,41)).thenReturn(Optional.of(new TaskRecords.Confirmation(
                8502,8501,41,"token-digest","b".repeat(64),9001,"AVAILABLE",now().plusMinutes(10),null,now())));
        when(mapper.invalidateConfirmation(8501,41)).thenReturn(1);
        when(mapper.abandonDraft(org.mockito.ArgumentMatchers.eq(8501L),org.mockito.ArgumentMatchers.eq(41L),any())).thenReturn(1);
    }

    private AgentRunRecord articleRevisionRun() {
        AgentRunRecord current = runWithGeoReport("999","101","102","AUTO_SINGLE_TOPIC");
        ObjectNode context = (ObjectNode) read(current.contextSnapshotJson());
        context.putObject("taskWorkspace").put("activeDraftId","8501");
        return new AgentRunRecord(current.id(),current.conversationId(),current.userId(),current.requestMessageId(),
                current.responseMessageId(),current.selectedSpaceId(),current.selectedProductId(),current.attachmentAssetIdsJson(),
                current.status(),current.contextVersion(),context.toString(),current.contextRefHash(),current.contextExpiresAt(),
                current.pythonEventsUrl(),current.lastPythonSequence(),current.usageJson(),current.errorCode(),current.traceId(),
                current.stopRequestedAt(),current.startedAt(),current.completedAt(),current.createdAt(),current.updatedAt());
    }

    private AgentRunRecord runWithGeoReport(String reportId, String spaceId, String productId) {
        return runWithGeoReport(reportId, spaceId, productId, "EXPLICIT_REPORT");
    }

    private AgentRunRecord runWithGeoReport(String reportId, String spaceId, String productId, String source) {
        AgentRunRecord current = run();
        ObjectNode context = (ObjectNode) read(current.contextSnapshotJson());
        context.putObject("geoInsightContext").put("status","READY").put("selectionSource",source).putObject("report")
                .put("reportId",reportId).put("spaceId",spaceId).put("productId",productId);
        return new AgentRunRecord(current.id(),current.conversationId(),current.userId(),current.requestMessageId(),
                current.responseMessageId(),current.selectedSpaceId(),current.selectedProductId(),current.attachmentAssetIdsJson(),
                current.status(),current.contextVersion(),context.toString(),current.contextRefHash(),current.contextExpiresAt(),
                current.pythonEventsUrl(),current.lastPythonSequence(),current.usageJson(),current.errorCode(),current.traceId(),
                current.stopRequestedAt(),current.startedAt(),current.completedAt(),current.createdAt(),current.updatedAt());
    }

    @Test
    void discoveryDraftRevisionRequotesEditedQuestionsAndInvalidatesOldConfirmationWithoutCreatingTopic() {
        ObjectNode parameters = (ObjectNode) geoDiscoveryProposal().path("parameters");
        parameters.putArray("platformCodes").add("DOUBAO");
        parameters.put("sampleMode","STANDARD");
        TaskRecords.Draft current = new TaskRecords.Draft(8501,41,9001L,null,null,"CHAT","TASK_DRAFT_CREATE",
                UUID.randomUUID(),"a".repeat(64),"GEO",1,101,102L,parameters.toString(),"{}","b".repeat(64),
                8001,6,"[]","WAITING_CONFIRMATION",now().plusMinutes(10),null,now(),now());
        when(mapper.lockDraft(8501,41)).thenReturn(Optional.of(current));
        when(mapper.invalidateConfirmation(8501,41)).thenReturn(1);
        when(mapper.abandonDraft(org.mockito.ArgumentMatchers.eq(8501L),org.mockito.ArgumentMatchers.eq(41L),any())).thenReturn(1);
        when(geoTasks.isQuestionDiscovery(parameters)).thenReturn(true);
        var prepared = new GeoTaskService.PreparedDraft(parameters,json.createObjectNode().set("parameters",parameters),3);
        when(geoTasks.prepareDiscoveryDraft(41,101,102L,parameters)).thenReturn(prepared);
        when(geoTasks.quote(prepared)).thenReturn(3);
        var revised = service.reviseDraft(user(),8501,UUID.randomUUID().toString(),
                new TaskDtos.ReviseDraftRequest("101","102",1,parameters));
        assertThat(revised.previousDraftId()).isEqualTo("8501");
        assertThat(revised.quotedPoints()).isEqualTo(3);
        assertThat(revised.parameters().path("platformCodes")).hasSize(1);
        verify(mapper).invalidateConfirmation(8501,41);
        verify(geoTasks,never()).prepareDraft(anyLong(),anyLong(),any(),any());
        verify(geoTasks,never()).materializeDiscoveryDraft(anyLong(),anyLong(),any(),any());
        verify(mapper,never()).insertTask(any());
    }

    @Test
    void scheduledGeoTaskAtomicallyFreezesPointsCreatesRunLedgerAndOutbox() {
        OffsetDateTime slot = OffsetDateTime.parse("2026-09-09T01:00:00Z");
        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("taskType", "GEO");
        snapshot.putObject("parameters").put("mode", "MONITOR_RUN");
        when(geoTasks.quoteScheduled(snapshot)).thenReturn(6);
        when(mapper.lockPointAccount(41)).thenReturn(Optional.of(
                new TaskRecords.PointAccount(41, 20, 3, 1, now(), now())));
        when(mapper.insertTask(any())).thenReturn(1);
        when(mapper.freezePoints(anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertReservation(anyLong(), anyLong(), anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertLedger(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), anyString(),
                anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertTaskEvent(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(),
                anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        when(mapper.insertOutbox(any(), anyString(), anyString())).thenReturn(1);

        TaskControlService.ScheduledTaskResult result = service.createScheduledGeoTask(
                8801, 41, 101, 102L, slot, snapshot, "b".repeat(64), 8001, 10, now());

        assertThat(result.dispatched()).isTrue();
        assertThat(result.quotedPoints()).isEqualTo(6);
        ArgumentCaptor<TaskRecords.Task> task = ArgumentCaptor.forClass(TaskRecords.Task.class);
        verify(mapper).insertTask(task.capture());
        assertThat(task.getValue().conversationId()).isNull();
        assertThat(task.getValue().draftId()).isNull();
        assertThat(task.getValue().planId()).isEqualTo(8801);
        assertThat(task.getValue().scheduledSlot()).isEqualTo(slot);
        verify(mapper).freezePoints(41, 6, task.getValue().createdAt());
        verify(geoTasks).createRun(org.mockito.ArgumentMatchers.eq(task.getValue()), any());
        verify(mapper).insertLedger(anyLong(), org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.eq(-6), org.mockito.ArgumentMatchers.eq(6),
                org.mockito.ArgumentMatchers.eq(14), org.mockito.ArgumentMatchers.eq(9),
                org.mockito.ArgumentMatchers.eq("TASK_FREEZE"),
                org.mockito.ArgumentMatchers.eq(task.getValue().id()), anyString(), anyString(), any());
        verify(mapper).insertOutbox(any(), org.mockito.ArgumentMatchers.eq("zhixiaohe.task"),
                org.mockito.ArgumentMatchers.eq("agent.geo"));
    }

    @Test
    void scheduledGeoTaskOverPlanLimitCreatesNoTaskOrPointMutation() {
        ObjectNode snapshot = json.createObjectNode();
        when(geoTasks.quoteScheduled(snapshot)).thenReturn(11);

        TaskControlService.ScheduledTaskResult result = service.createScheduledGeoTask(
                8801, 41, 101, null, now().plusMinutes(1), snapshot,
                "b".repeat(64), 8001, 10, now());

        assertThat(result.dispatched()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PRICE_LIMIT_EXCEEDED");
        verify(mapper, never()).lockPointAccount(anyLong());
        verify(mapper, never()).insertTask(any());
        verify(mapper, never()).freezePoints(anyLong(), anyInt(), any());
        verify(mapper, never()).insertOutbox(any(), anyString(), anyString());
    }

    @Test
    void articleExecutionContextPreservesEachFrozenStageQuoteAndExistingStageBudgetWithoutWrites() {
        PaidTaskProperties settings = properties();
        settings.setArticleMaxModelCalls(3);
        settings.setArticleMaxRepairRetries(0);
        settings.setArticleMaxInputTokens(12345);
        settings.setArticleMaxOutputTokens(2345);
        settings.setArticleExecutionDeadline(Duration.ofMinutes(7));
        // Prices have changed since these tasks were accepted. Context reads must expose the
        // persisted task quote, not reprice a stage or substitute the new full-article total.
        settings.setArticleTestPricingVersionId(9901);
        settings.setArticleTitleTestPoints(101);
        settings.setArticleOutlineTestPoints(102);
        settings.setArticleBodyTestPoints(103);
        SnowflakeIdGenerator contextIds = mock(SnowflakeIdGenerator.class);
        TaskControlService executionService = new TaskControlService(mapper, conversations, contextIds,
                json, settings, new AssetIngestionProperties(), catalogContextFactory, geoTasks,
                articleService, mediaSubmissions);
        String token = "article-context-test-token";
        String[] modes = {"TITLE", "OUTLINE", "BODY"};
        int[] frozenQuotes = {3, 4, 5};
        for (int i = 0; i < modes.length; i++) {
            TaskRecords.Task task = articleExecutionTask(9400 + i, modes[i], frozenQuotes[i]);
            when(mapper.findTaskInternal(task.id())).thenReturn(Optional.of(task));
            when(mapper.findLatestExecution(task.id())).thenReturn(Optional.of(
                    articleExecution(task, token, "RUNNING", now().plusMinutes(5))));
            OffsetDateTime before = now();
            TaskDtos.ExecutionContextResponse context = executionService.executionContext(task.id(), 1, token);
            OffsetDateTime after = now();
            assertThat(context.taskId()).isEqualTo(Long.toString(task.id()));
            assertThat(context.taskType()).isEqualTo("ARTICLE");
            assertThat(context.provider()).isEqualTo("DEEPSEEK");
            assertThat(context.model()).isNull();
            assertThat(context.executionNo()).isEqualTo(1);
            assertThat(context.snapshotSchemaVersion()).isEqualTo(2);
            assertThat(context.inputSnapshot()).isEqualTo(read(task.inputSnapshotJson()).path("parameters"));
            assertThat(context.inputSnapshot().path("mode").asText()).isEqualTo(modes[i]);
            assertThat(context.authorizedContext())
                    .isEqualTo(read(task.inputSnapshotJson()).path("authorizedContext"));
            assertThat(context.pricing().pricingVersionId()).isEqualTo("9001");
            assertThat(context.pricing().quotedPoints()).isEqualTo(frozenQuotes[i]);
            assertThat(context.resumeOnly()).isFalse();
            assertThat(context.providerContinuation()).isNull();
            assertThat(context.referenceAssets()).isEmpty();
            assertThat(context.productCreativeBrief()).isNull();
            assertThat(context.controlledMaskAsset()).isNull();
            var budget = context.budget();
            assertThat(budget.maxProviderCalls()).isZero();
            assertThat(budget.maxModelCalls()).isEqualTo(3);
            assertThat(budget.maxRepairRetries()).isZero();
            assertThat(budget.maxInputTokens()).isEqualTo(12345);
            assertThat(budget.maxOutputTokens()).isEqualTo(2345);
            assertThat(budget.deadlineAt()).isBetween(before.plusMinutes(7), after.plusMinutes(7));
            assertThat(budget.maxImages()).isNull();
            assertThat(budget.maxVideos()).isNull();
            assertThat(budget.maxVideoSeconds()).isNull();
            assertThat(budget.maxGeoQuestions()).isNull();
            assertThat(budget.maxGeoPlatforms()).isNull();
            assertThat(budget.samplesPerCombination()).isNull();
            assertThat(budget.maxConcurrency()).isNull();
            verify(mapper).findTaskInternal(task.id());
            verify(mapper).findLatestExecution(task.id());
        }
        org.mockito.Mockito.verifyNoMoreInteractions(mapper);
        org.mockito.Mockito.verifyNoInteractions(contextIds, conversations, catalogContextFactory,
                geoTasks, articleService, mediaSubmissions, creativeSources);
    }

    @Test
    void articleExecutionContextStillRequiresLatestLiveLeaseAndValidStageBudgetWithoutWrites() {
        PaidTaskProperties settings = properties();
        SnowflakeIdGenerator contextIds = mock(SnowflakeIdGenerator.class);
        TaskControlService executionService = new TaskControlService(mapper, conversations, contextIds,
                json, settings, new AssetIngestionProperties(), catalogContextFactory, geoTasks,
                articleService, mediaSubmissions);
        String token = "article-context-test-token";
        TaskRecords.Task task = articleExecutionTask(9400, "BODY", 5);
        when(mapper.findTaskInternal(task.id())).thenReturn(Optional.of(task));
        when(mapper.findLatestExecution(task.id())).thenReturn(Optional.of(
                articleExecution(task, token, "RUNNING", now().plusMinutes(5))));
        assertCode(() -> executionService.executionContext(task.id(), 1, "wrong-token"), ErrorCode.FORBIDDEN);
        assertCode(() -> executionService.executionContext(task.id(), 2, token), ErrorCode.TASK_STATE_CONFLICT);
        when(mapper.findLatestExecution(task.id())).thenReturn(Optional.of(
                articleExecution(task, token, "RUNNING", now().minusMinutes(1))));
        assertCode(() -> executionService.executionContext(task.id(), 1, token), ErrorCode.FORBIDDEN);
        when(mapper.findLatestExecution(task.id())).thenReturn(Optional.of(
                articleExecution(task, token, "SUCCEEDED", now().plusMinutes(5))));
        assertCode(() -> executionService.executionContext(task.id(), 1, token), ErrorCode.TASK_STATE_CONFLICT);
        when(mapper.findLatestExecution(task.id())).thenReturn(Optional.of(
                articleExecution(task, token, "RUNNING", now().plusMinutes(5))));
        settings.setArticleMaxModelCalls(0);
        assertCode(() -> executionService.executionContext(task.id(), 1, token), ErrorCode.PROVIDER_NOT_CONFIGURED);
        verify(mapper, org.mockito.Mockito.times(5)).findTaskInternal(task.id());
        verify(mapper, org.mockito.Mockito.times(5)).findLatestExecution(task.id());
        org.mockito.Mockito.verifyNoMoreInteractions(mapper);
        org.mockito.Mockito.verifyNoInteractions(contextIds, conversations, catalogContextFactory,
                geoTasks, articleService, mediaSubmissions, creativeSources);
    }

    private TaskRecords.Task articleExecutionTask(long taskId, String mode, int frozenPoints) {
        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("taskType", "ARTICLE").put("parameterSchemaVersion", 2);
        ObjectNode parameters = snapshot.putObject("parameters");
        parameters.put("mode", mode).put("articleId", "501").put("articleVersion", 1)
                .put("brief", "介绍小行星咖啡店").put("targetLength", "SHORT")
                .put("tone", "PROFESSIONAL").putNull("targetPlatform")
                .putNull("geoReportId").putNull("rewriteInstruction").putNull("confirmedPlan");
        if ("TITLE".equals(mode)) parameters.putNull("selectedTitle");
        else parameters.put("selectedTitle", "小行星咖啡店介绍");
        var outline = parameters.putArray("confirmedOutline");
        if ("BODY".equals(mode)) outline.addObject().put("key", "section-1")
                .put("sequence", 1).put("level", 1).put("heading", "店铺介绍")
                .put("brief", "仅使用商家确认的资料");
        parameters.putArray("rewriteBlockIds");
        parameters.putArray("sourceAssetIds");
        snapshot.putObject("authorizedContext").putObject("article").put("id", "501");
        OffsetDateTime timestamp = now();
        return new TaskRecords.Task(taskId, 41, 101, null, 9001, 7501, "ARTICLE", "RUNNING",
                "ARTICLE_GENERATING", 2, snapshot.toString(), "b".repeat(64), 9001, frozenPoints,
                0, "{}", null, "TASK_CONFIRM", UUID.randomUUID(), "a".repeat(64),
                timestamp, timestamp, null, timestamp, timestamp);
    }

    @Test
    void fullArticleContextNeverRenewsDeadlineAndRejectsChangedFrozenSourcesBeforeOutbound() {
        var settings=properties();settings.setArticleMaxModelCalls(99);
        var executionService=new TaskControlService(mapper,conversations,mock(SnowflakeIdGenerator.class),json,settings,
                new AssetIngestionProperties(),catalogContextFactory,geoTasks,articleService,mediaSubmissions);
        var knowledge=mock(com.guanghe.backend.knowledge.KnowledgeService.class);
        executionService.setArticleKnowledge(knowledge);
        var frozen=new com.guanghe.backend.knowledge.KnowledgeDtos.KnowledgeContext(List.of(
                new com.guanghe.backend.knowledge.KnowledgeDtos.ContextDocument("601",1,"门店资料","d".repeat(64),"PASTED_TEXT",null,
                        List.of(new com.guanghe.backend.knowledge.KnowledgeDtos.Paragraph(1,null,"小行星咖啡店供应咖啡。")))),
                List.of(),null,false,1,12);
        var base=articleExecutionTask(9400,"BODY",5);var snapshot=(ObjectNode)read(base.inputSnapshotJson());
        snapshot.putObject("articleFull").put("templateCode","ARTICLE_FULL_V1").put("planId","201");
        OffsetDateTime deadline=now().plusMinutes(8);
        snapshot.putObject("executionBudget").put("deadlineAt",deadline.toString()).put("maxModelCalls",2)
                .put("maxRepairRetries",1).put("maxInputTokens",1234).put("maxOutputTokens",567);
        ((ObjectNode)snapshot.path("authorizedContext")).set("knowledge",json.valueToTree(frozen));
        var task=new TaskRecords.Task(base.id(),base.userId(),base.spaceId(),base.productId(),base.conversationId(),base.draftId(),base.taskType(),
                base.status(),base.stageCode(),base.snapshotSchemaVersion(),snapshot.toString(),base.inputHash(),base.pricingVersionId(),
                base.quotedPoints(),base.settledPoints(),base.resultSummaryJson(),base.errorCode(),base.operationScope(),base.idempotencyKey(),
                base.requestHash(),base.queuedAt(),base.startedAt(),base.finishedAt(),base.createdAt(),base.updatedAt(),base.planId(),base.scheduledSlot());
        String token="article-fixed-context-token";
        when(mapper.findTaskInternal(task.id())).thenReturn(Optional.of(task));
        when(mapper.findLatestExecution(task.id())).thenReturn(Optional.of(articleExecution(task,token,"RUNNING",now().plusMinutes(5))));
        when(knowledge.revalidateFrozenContext(eq(task.userId()),eq(task.spaceId()),any())).thenReturn(frozen);
        var first=executionService.executionContext(task.id(),1,token);var second=executionService.executionContext(task.id(),1,token);
        assertThat(first.budget()).isEqualTo(second.budget());assertThat(first.budget().deadlineAt()).isEqualTo(deadline);
        assertThat(first.budget().maxModelCalls()).isEqualTo(2);assertThat(first.budget().maxInputTokens()).isEqualTo(1234);
        when(knowledge.revalidateFrozenContext(eq(task.userId()),eq(task.spaceId()),any())).thenReturn(
                new com.guanghe.backend.knowledge.KnowledgeDtos.KnowledgeContext(List.of(),List.of(),null,true,0,0));
        assertCode(()->executionService.executionContext(task.id(),1,token),ErrorCode.KNOWLEDGE_SOURCE_CHANGED);
        verify(mapper,never()).freezePoints(anyLong(),anyInt(),any());verify(mapper,never()).insertOutbox(any(),anyString(),anyString());
    }

    private TaskRecords.Execution articleExecution(TaskRecords.Task task, String token,
                                                    String status, OffsetDateTime leaseExpiresAt) {
        OffsetDateTime timestamp = now();
        return new TaskRecords.Execution(9500, task.id(), task.userId(), 1, status, "article",
                "worker", "v1", sha256(token), leaseExpiresAt, "ARTICLE_GENERATING",
                null, null, null, null, null, null, "{}", null,
                timestamp, timestamp, null, timestamp, timestamp);
    }

    @Test
    void geoZeroSuccessFailureReleasesAllPointsAndClosesTheRunOnce() {
        var notifications = mock(TaskNotificationService.class);
        service.setTaskNotifications(notifications);
        String token = "geo-execution-token";
        TaskRecords.Task task = new TaskRecords.Task(
                9200, 41, 101, null, 9001, 7501, "GEO", "RUNNING", "GEO_SAMPLING",
                1, "{}", "b".repeat(64), 8001, 6, 0, "{}", null,
                "TASK_CONFIRM", UUID.randomUUID(), "a".repeat(64), now(), now(), null, now(), now());
        TaskRecords.Execution execution = new TaskRecords.Execution(
                9300, task.id(), task.userId(), 1, "RUNNING", "geo", "worker", "v1",
                sha256(token), now().plusMinutes(5), "GEO_SAMPLING", null, null, null, null,
                null, null, "{}", null, now(), now(), null, now(), now());
        when(mapper.findTaskEventRequestHash(anyLong(), any())).thenReturn(Optional.empty());
        when(mapper.findLatestExecution(task.id())).thenReturn(Optional.of(execution));
        when(mapper.lockTask(task.id())).thenReturn(Optional.of(task));
        when(mapper.insertTaskEvent(anyLong(), anyLong(), anyLong(), anyLong(), any(), anyString(),
                any(), any(), anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        when(mapper.failExecution(anyLong(), anyInt(), anyString(), anyString(), anyString(), any()))
                .thenReturn(1);
        when(mapper.failOrRetryTask(anyLong(), anyString(), anyString(), anyString(), any()))
                .thenReturn(1);
        when(mapper.releaseReservation(org.mockito.ArgumentMatchers.eq(task.id()),
                org.mockito.ArgumentMatchers.eq(6), any())).thenReturn(1);
        when(mapper.releasePoints(org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.eq(6), any())).thenReturn(1);
        when(mapper.findPointAccount(41)).thenReturn(Optional.of(
                new TaskRecords.PointAccount(41, 20, 0, 2, now(), now())));
        when(mapper.insertLedger(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(),
                anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        ObjectNode details = json.createObjectNode();
        details.put("code", "GEO_ZERO_SUCCESSFUL_OBSERVATIONS");
        details.put("submissionState", "CONFIRMED");

        TaskControlService.ReportedTask failed = service.reportEvent(task.id(),
                new TaskDtos.ExecutionEventRequest(UUID.randomUUID(), 1, token,
                        "TASK_FAILED", "FAILED", details));

        assertThat(failed.response().taskStatus()).isEqualTo("FAILED");
        verify(notifications).publishTerminal(task.id(), task.userId());
        verify(geoTasks).markFailed(org.mockito.ArgumentMatchers.eq(task),
                org.mockito.ArgumentMatchers.eq("FAILED"), any());
        verify(mapper).releasePoints(org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.eq(6), any());
    }

    @Test
    void insufficientPointsCreatesNoTaskOrOutboxAndFreezesNothing() {
        DraftFixture fixture = persistDraft();
        when(mapper.findTaskByIdempotency(anyLong(), any())).thenReturn(Optional.empty());
        when(mapper.lockDraft(fixture.draft.id(), 41)).thenReturn(Optional.of(fixture.draft));
        when(mapper.findTaskByDraft(fixture.draft.id())).thenReturn(Optional.empty());
        when(mapper.lockConfirmation(fixture.draft.id(), 41)).thenReturn(Optional.of(fixture.confirmation));
        when(mapper.lockPointAccount(41)).thenReturn(Optional.of(
                new TaskRecords.PointAccount(41, 6, 0, 1, now(), now())));

        assertThatThrownBy(() -> service.confirm(user(), fixture.draft.id(), UUID.randomUUID().toString(),
                new TaskDtos.ConfirmRequest(fixture.token, fixture.draft.inputHash(), "6001")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_POINTS));
        verify(mapper, never()).insertTask(any());
        verify(mapper, never()).freezePoints(anyLong(), anyInt(), any());
        verify(mapper, never()).insertOutbox(any(), anyString(), anyString());
    }

    @Test
    void sameConfirmationIdempotencyReturnsTheOriginalTaskAndDifferentBodyConflicts() {
        UUID key = UUID.randomUUID();
        TaskRecords.Task task = task(key, "a".repeat(64));
        when(mapper.findTaskByIdempotency(41, key)).thenReturn(Optional.of(task));
        TaskDtos.ConfirmRequest request = new TaskDtos.ConfirmRequest("different", "b".repeat(64), "6001");
        assertThatThrownBy(() -> service.confirm(user(), 7001, key.toString(), request))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        verify(mapper, never()).lockPointAccount(anyLong());
    }

    @Test
    void repeatedConfirmationWithAnotherKeyReturnsTheSingleTaskWithoutAnotherFreeze() {
        DraftFixture fixture = persistDraft();
        TaskRecords.Task original = task(UUID.randomUUID(), "a".repeat(64));
        when(mapper.findTaskByIdempotency(anyLong(), any())).thenReturn(Optional.empty());
        when(mapper.lockDraft(fixture.draft.id(), 41)).thenReturn(Optional.of(fixture.draft));
        when(mapper.findTaskByDraft(fixture.draft.id())).thenReturn(Optional.of(original));

        TaskControlService.ConfirmedTask replay = service.confirm(user(), fixture.draft.id(),
                UUID.randomUUID().toString(), new TaskDtos.ConfirmRequest(fixture.token,
                        fixture.draft.inputHash(), "6001"));

        assertThat(replay.response().id()).isEqualTo(Long.toString(original.id()));
        verify(mapper, never()).freezePoints(anyLong(), anyInt(), any());
        verify(mapper, never()).insertTask(any());
    }

    @Test
    void proposedPriceOrUnsupportedTaskTypeCanNeverEnterTheDraft() {
        ObjectNode proposal = proposal();
        proposal.put("quotedPoints", 1);
        assertThatThrownBy(() -> service.acceptProposal(run(), event(proposal), "", now()))
                .isInstanceOf(IllegalArgumentException.class);
        proposal.remove("quotedPoints"); proposal.put("taskType", "AUDIO");
        assertThatThrownBy(() -> service.acceptProposal(run(), event(proposal), "", now()))
                .isInstanceOf(IllegalArgumentException.class);
        ObjectNode video=videoProposal();
        ((ObjectNode)video.path("parameters")).put("referenceAssetId","123");
        assertThatThrownBy(() -> service.acceptProposal(run(),event(video),"",now()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).insertDraft(any());
    }

    @Test
    void videoProposalIsNormalizedPricedAndConfirmedOntoVideoRoute() {
        var browserEvent = service.acceptProposal(run(), event(videoProposal()), "", now());
        ArgumentCaptor<TaskRecords.Draft> drafts = ArgumentCaptor.forClass(TaskRecords.Draft.class);
        ArgumentCaptor<TaskRecords.Confirmation> confirmations = ArgumentCaptor.forClass(TaskRecords.Confirmation.class);
        verify(mapper).insertDraft(drafts.capture());
        verify(mapper).insertConfirmation(confirmations.capture());
        TaskRecords.Draft draft = drafts.getValue();
        assertThat(draft.taskType()).isEqualTo("VIDEO");
        assertThat(draft.pricingVersionId()).isEqualTo(7001);
        assertThat(draft.quotedPoints()).isEqualTo(11);
        JsonNode parameters = read(draft.parametersJson());
        assertThat(parameters.path("scene").asText()).isEqualTo("商品旋转展示");
        assertThat(parameters.path("durationSeconds").asInt()).isEqualTo(5);
        assertThat(parameters.path("aspectRatio").asText()).isEqualTo("adaptive");
        assertThat(parameters.path("resolution").asText()).isEqualTo("720P");
        assertThat(parameters.path("audio").asBoolean()).isFalse();
        assertThat(parameters.path("seed").asInt()).isEqualTo(-1);
        assertThat(parameters.path("promptExtend").asBoolean()).isTrue();
        assertThat(parameters.path("watermark").asBoolean()).isFalse();

        String token = read(browserEvent.payloadJson()).path("draft").path("confirmationToken").asText();
        when(mapper.findTaskByIdempotency(anyLong(), any())).thenReturn(Optional.empty());
        when(mapper.lockDraft(draft.id(), 41)).thenReturn(Optional.of(draft));
        when(mapper.findTaskByDraft(draft.id())).thenReturn(Optional.empty());
        when(mapper.lockConfirmation(draft.id(), 41)).thenReturn(Optional.of(confirmations.getValue()));
        when(mapper.lockPointAccount(41)).thenReturn(Optional.of(
                new TaskRecords.PointAccount(41, 20, 0, 1, now(), now())));
        when(mapper.consumeConfirmation(anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.confirmDraft(anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.insertTask(any())).thenReturn(1);
        when(mapper.freezePoints(anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertReservation(anyLong(), anyLong(), anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertLedger(anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), anyString(),
                anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertTaskEvent(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(),
                anyString(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        when(mapper.insertOutbox(any(), anyString(), anyString())).thenReturn(1);

        service.confirm(user(), draft.id(), UUID.randomUUID().toString(),
                new TaskDtos.ConfirmRequest(token, draft.inputHash(), "7001"));

        ArgumentCaptor<TaskRecords.Task> task = ArgumentCaptor.forClass(TaskRecords.Task.class);
        verify(mapper).insertTask(task.capture());
        assertThat(task.getValue().taskType()).isEqualTo("VIDEO");
        verify(mapper).insertOutbox(any(), org.mockito.ArgumentMatchers.eq("zhixiaohe.task"),
                org.mockito.ArgumentMatchers.eq("agent.video"));
    }

    @Test
    void formDraftIsIdempotentAndNeverForgesAnAgentRun() {
        UUID key = UUID.randomUUID();
        when(conversations.findConversation(9001, 41)).thenReturn(Optional.of(
                new com.guanghe.backend.conversation.ConversationRecord(
                        9001, 41, "会话", null, null, 1, null, now(), now())));
        when(mapper.findDraftByIdempotency(41, "TASK_DRAFT_CREATE", key))
                .thenReturn(Optional.empty());

        ObjectNode parameters = (ObjectNode) proposal().path("parameters");
        parameters.put("count", 4);
        TaskDtos.CreateDraftRequest request = new TaskDtos.CreateDraftRequest(
                "9001", "IMAGE", "101", "102", 1, parameters);
        TaskDtos.DraftResponse created = service.createDraft(user(), key.toString(), request);

        ArgumentCaptor<TaskRecords.Draft> drafts = ArgumentCaptor.forClass(TaskRecords.Draft.class);
        verify(mapper).insertDraft(drafts.capture());
        TaskRecords.Draft stored = drafts.getValue();
        assertThat(stored.agentRunId()).isNull();
        assertThat(stored.draftSource()).isEqualTo("FORM");
        assertThat(stored.operationScope()).isEqualTo("TASK_DRAFT_CREATE");
        assertThat(created.draftSource()).isEqualTo("FORM");
        assertThat(created.quotedPoints()).isEqualTo(28);
        assertThat(created.confirmationToken()).startsWith("v1.");

        when(mapper.findDraftByIdempotency(41, "TASK_DRAFT_CREATE", key))
                .thenReturn(Optional.of(stored));
        TaskDtos.DraftResponse replay = service.createDraft(user(), key.toString(), request);
        assertThat(replay.id()).isEqualTo(created.id());
        verify(mapper, org.mockito.Mockito.times(1)).insertDraft(any());
    }

    @Test
    void revisionInvalidatesOldTokenAndAbandonIsIdempotent() {
        UUID key = UUID.randomUUID();
        TaskRecords.Draft current = persistDraft().draft();
        when(mapper.lockDraft(current.id(), 41)).thenReturn(Optional.of(current));
        when(mapper.findDraftByIdempotency(41, "TASK_DRAFT_REVISION", key)).thenReturn(Optional.empty());
        when(mapper.invalidateConfirmation(current.id(), 41)).thenReturn(1);
        when(mapper.abandonDraft(org.mockito.ArgumentMatchers.eq(current.id()),
                org.mockito.ArgumentMatchers.eq(41L), any())).thenReturn(1);
        ObjectNode parameters = (ObjectNode) proposal().path("parameters");
        parameters.put("count", 3);
        TaskDtos.ReviseDraftRequest request = new TaskDtos.ReviseDraftRequest(
                "101", "102", 1, parameters);

        TaskDtos.DraftResponse revised = service.reviseDraft(user(), current.id(), key.toString(), request);
        assertThat(revised.previousDraftId()).isEqualTo(Long.toString(current.id()));
        assertThat(revised.quotedPoints()).isEqualTo(21);
        verify(mapper).invalidateConfirmation(current.id(), 41);
        verify(mapper).abandonDraft(org.mockito.ArgumentMatchers.eq(current.id()),
                org.mockito.ArgumentMatchers.eq(41L), any());

        ArgumentCaptor<TaskRecords.Draft> drafts = ArgumentCaptor.forClass(TaskRecords.Draft.class);
        verify(mapper, org.mockito.Mockito.times(2)).insertDraft(drafts.capture());
        TaskRecords.Draft revision = drafts.getAllValues().getLast();
        when(mapper.lockDraft(revision.id(), 41)).thenReturn(Optional.of(revision));
        when(mapper.invalidateConfirmation(revision.id(), 41)).thenReturn(1);
        when(mapper.abandonDraft(org.mockito.ArgumentMatchers.eq(revision.id()),
                org.mockito.ArgumentMatchers.eq(41L), any())).thenReturn(1);
        TaskDtos.DraftResponse abandoned = service.abandonDraft(user(), revision.id());
        assertThat(abandoned.status()).isEqualTo("ABANDONED");
        TaskRecords.Draft abandonedRecord = new TaskRecords.Draft(
                revision.id(), revision.userId(), revision.conversationId(), null,
                revision.previousDraftId(), revision.draftSource(), revision.operationScope(),
                revision.idempotencyKey(), revision.requestHash(), revision.taskType(),
                revision.parameterSchemaVersion(), revision.spaceId(), revision.productId(),
                revision.parametersJson(), revision.inputSnapshotJson(), revision.inputHash(),
                revision.pricingVersionId(), revision.quotedPoints(), revision.warningsJson(),
                "ABANDONED", revision.expiresAt(), null, revision.createdAt(), now());
        when(mapper.lockDraft(revision.id(), 41)).thenReturn(Optional.of(abandonedRecord));
        assertThat(service.abandonDraft(user(), revision.id()).status()).isEqualTo("ABANDONED");
        verify(mapper, org.mockito.Mockito.times(1)).abandonDraft(
                org.mockito.ArgumentMatchers.eq(revision.id()), org.mockito.ArgumentMatchers.eq(41L), any());
    }

    @Test
    void taskListScopesEveryFilterToUserAndEventTimelineDropsInternalPayload() {
        TaskRecords.Task task = task(UUID.randomUUID(), "a".repeat(64));
        when(mapper.countTasks(41, "IMAGE", "QUEUED", 9001L, 101L, 102L)).thenReturn(1L);
        when(mapper.findTaskPage(41, "IMAGE", "QUEUED", 9001L, 101L, 102L, 0, 20))
                .thenReturn(List.of(task));
        var page = service.listTasks(user(), "IMAGE", "QUEUED", "9001", "101", "102", 1, 20);
        assertThat(page.items()).extracting(TaskDtos.TaskResponse::id)
                .containsExactly(Long.toString(task.id()));

        when(mapper.findTask(task.id(), 41)).thenReturn(Optional.of(task));
        when(mapper.findTaskBrowserEvents(task.id(), 41)).thenReturn(List.of(
                new com.guanghe.backend.conversation.ConversationEventRecord(
                        9100, task.conversationId(), 41, null, null, task.id(), null,
                        "task.progress", 3,
                        "{\"taskId\":\"9002\",\"status\":\"RUNNING\",\"stageCode\":\"CLAIMED\","
                                + "\"providerTaskId\":\"must-not-leak\",\"details\":\"internal\"}",
                        now(), now()),
                new com.guanghe.backend.conversation.ConversationEventRecord(
                        9101, task.conversationId(), 41, null, null, task.id(), null,
                        "heartbeat", 4, "{\"secret\":\"must-not-leak\"}", now(), now())));
        List<TaskDtos.TaskEventResponse> timeline = service.taskEvents(user(), task.id());
        assertThat(timeline).hasSize(1);
        assertThat(timeline.getFirst().sequence()).isEqualTo(3);
        assertThat(timeline.getFirst().status()).isEqualTo("RUNNING");
        assertThat(timeline.getFirst().stageCode()).isEqualTo("CLAIMED");
    }

    @Test
    void taskFundingReadsStoredAmountsInOneBatchAndKeepsMissingHistoryUnknown() {
        TaskRecords.Task task = task(UUID.randomUUID(), "a".repeat(64));
        when(mapper.countTasks(41, null, null, null, null, null)).thenReturn(1L);
        when(mapper.findTaskPage(41, null, null, null, null, null, 0, 20)).thenReturn(List.of(task));
        when(mapper.findTaskFunding(41, List.of(task.id())))
                .thenReturn(List.of(new TaskRecords.TaskFunding(task.id(), 0, 16, 5)));
        var item = service.listTasks(user(), null, null, null, null, null, 1, 20).items().getFirst();
        assertThat(item.settledPoints()).isEqualTo(16);
        assertThat(item.frozenPoints()).isZero();
        assertThat(item.releasedPoints()).isEqualTo(5);
        verify(mapper).findTaskFunding(41, List.of(task.id()));
        when(mapper.findTask(task.id(), 41)).thenReturn(Optional.of(task));
        when(mapper.findTaskFunding(41, List.of(task.id()))).thenReturn(List.of());
        var unavailable = service.getTask(user(), task.id());
        assertThat(unavailable.quotedPoints()).isEqualTo(task.quotedPoints());
        assertThat(unavailable.frozenPoints()).isNull();
        assertThat(unavailable.settledPoints()).isNull();
        assertThat(unavailable.releasedPoints()).isNull();
        verify(mapper, never()).releaseReservation(anyLong(), anyInt(), any());
        verify(mapper, never()).releasePoints(anyLong(), anyInt(), any());
    }

    @Test
    void pointLedgerIsUserScopedPagedAndReturnsSafeBusinessLabels() {
        OffsetDateTime createdAt = now();
        TaskRecords.PointLedgerEntry entry = new TaskRecords.PointLedgerEntry(
                9_301, 41, -30, 30, 70, 30,
                "TASK_FREEZE", "TASK", 9_201, createdAt);
        when(mapper.countPointLedger(41, null, null, null)).thenReturn(1L);
        when(mapper.findPointLedgerPage(41, null, null, null, 0, 20)).thenReturn(List.of(entry));

        var page = service.listPointLedger(user(), null, null, 1, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("9301");
            assertThat(item.businessId()).isEqualTo("9201");
            assertThat(item.title()).isEqualTo("任务已受理，积分已冻结");
            assertThat(item.amountPoints()).isEqualTo(30);
            assertThat(item.availableDelta()).isEqualTo(-30);
            assertThat(item.frozenDelta()).isEqualTo(30);
            assertThat(item.createdAt()).isEqualTo(createdAt);
        });
        verify(mapper).countPointLedger(41, null, null, null);
        verify(mapper).findPointLedgerPage(41, null, null, null, 0, 20);
    }

    @Test
    void pointLedgerDeepLinkKeepsTheLedgerIdAndAccountInBothQueries() {
        when(mapper.countPointLedger(41, 9301L, null, null)).thenReturn(1L);
        when(mapper.findPointLedgerPage(41, 9301L, null, null, 0, 20)).thenReturn(List.of(
                new TaskRecords.PointLedgerEntry(9301L, 41, 100, 0, 200, 0,
                        "ADMIN_ADJUSTMENT", "ADMIN_ADJUSTMENT", 9201L, now())));
        var page = service.listPointLedger(user(), 9301L, null, null, 1, 20);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(TaskDtos.PointLedgerEntryResponse::id).containsExactly("9301");
        verify(mapper).countPointLedger(41, 9301L, null, null);
        verify(mapper).findPointLedgerPage(41, 9301L, null, null, 0, 20);
        assertThat(service.listPointLedger(user(), 9302L, null, null, 1, 20).items()).isEmpty();
        assertThatThrownBy(() -> service.listPointLedger(user(), 0L, null, null, 1, 20))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void pointLedgerRejectsUnknownFiltersBeforeQueryingStorage() {
        assertThatThrownBy(() -> service.listPointLedger(user(), "UNKNOWN", null, 1, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.listPointLedger(user(), "", null, 1, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(mapper, never()).countPointLedger(anyLong(), any(), any(), any());
    }

    @Test
    void workflowDraftCannotBypassPlanConfirmationThroughSingleDraftConfirm() {
        OffsetDateTime now = now();
        TaskRecords.Draft draft = new TaskRecords.Draft(8_901, 41, 8_801L, null, null,
                "PLAN", "WORKFLOW_PLAN_DRAFT_CREATE", UUID.randomUUID(), "a".repeat(64),
                "IMAGE", 1, 101, 102L, "{}", "{}", "b".repeat(64), 6001, 7,
                "[]", "WAITING_CONFIRMATION", now.plusMinutes(10), null, now, now);
        when(mapper.findTaskByIdempotency(anyLong(), any())).thenReturn(Optional.empty());
        when(mapper.lockDraft(draft.id(), 41)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.confirm(user(), draft.id(), UUID.randomUUID().toString(),
                new TaskDtos.ConfirmRequest("v1.unused", draft.inputHash(), "6001")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.TASK_STATE_CONFLICT));
        verify(mapper, never()).insertTask(any());
        verify(mapper, never()).freezePoints(anyLong(), anyInt(), any());
        verify(mapper, never()).insertOutbox(any(), anyString(), anyString());
    }

    @Test
    void workflowConfirmationLocksAndVerifiesExactDraftSnapshot() {
        OffsetDateTime now = now();
        TaskRecords.Draft draft = new TaskRecords.Draft(8_901, 41, 8_801L, null, null,
                "PLAN", "WORKFLOW_PLAN_DRAFT_CREATE", UUID.randomUUID(), "a".repeat(64),
                "IMAGE", 1, 101, 102L, "{}", "{}", "b".repeat(64), 6001, 7,
                "[]", "WAITING_CONFIRMATION", now.plusMinutes(10), null, now, now);
        when(mapper.lockDraft(draft.id(), 41)).thenReturn(Optional.of(draft));

        service.verifyWorkflowDrafts(41, 8_801,
                List.of(new TaskControlService.WorkflowDraftExpectation(8_901, "IMAGE", 6001, 7)), now);

        assertThatThrownBy(() -> service.verifyWorkflowDrafts(41, 8_801,
                List.of(new TaskControlService.WorkflowDraftExpectation(8_901, "VIDEO", 6001, 7)), now))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.TASK_CONFIRMATION_MISMATCH));
    }

    @Test
    void workflowActivationHonorsFundedStartTimeWithoutExtendingExpiredQuotes() {
        TaskRecords.Draft source = persistDraft().draft();
        OffsetDateTime activatedAt = now();
        OffsetDateTime expiresAt = activatedAt.minusMinutes(1);
        TaskRecords.Draft draft = new TaskRecords.Draft(source.id(), source.userId(), source.conversationId(),
                null, null, "PLAN", "WORKFLOW_PLAN_DRAFT_CREATE", source.idempotencyKey(), source.requestHash(),
                source.taskType(), source.parameterSchemaVersion(), source.spaceId(), source.productId(),
                source.parametersJson(), source.inputSnapshotJson(), source.inputHash(), source.pricingVersionId(),
                source.quotedPoints(), source.warningsJson(), "WAITING_CONFIRMATION", expiresAt,
                null, activatedAt.minusMinutes(10), activatedAt.minusMinutes(10));
        when(mapper.lockDraft(draft.id(), draft.userId())).thenReturn(Optional.of(draft));
        when(mapper.invalidateConfirmation(anyLong(), anyLong())).thenReturn(1);
        when(mapper.confirmDraft(anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.insertTask(any())).thenReturn(1);
        when(mapper.insertReservation(anyLong(), anyLong(), anyLong(), anyInt(), any())).thenReturn(1);
        when(mapper.insertOutbox(any(), anyString(), anyString())).thenReturn(1);
        UUID key = UUID.randomUUID();

        // Starting on/after expiry remains invalid, even if activation itself has a valid lease.
        assertCode(() -> service.activateWorkflowTask(new TaskControlService.WorkflowTaskActivation(
                9001, "step-3", draft.userId(), draft.conversationId(), draft.spaceId(), draft.productId(),
                draft.id(), draft.taskType(), draft.pricingVersionId(), draft.quotedPoints(), draft.inputHash(),
                expiresAt, key, "request-hash", expiresAt, activatedAt)), ErrorCode.TASK_CONFIRMATION_MISMATCH);
        verify(mapper, never()).insertTask(any());

        TaskRecords.Task task = service.activateWorkflowTask(new TaskControlService.WorkflowTaskActivation(
                9001, "step-3", draft.userId(), draft.conversationId(), draft.spaceId(), draft.productId(),
                draft.id(), draft.taskType(), draft.pricingVersionId(), draft.quotedPoints(), draft.inputHash(),
                expiresAt, key, "request-hash", expiresAt.minusSeconds(1), activatedAt));

        assertThat(task.status()).isEqualTo("QUEUED");
        assertThat(task.quotedPoints()).isEqualTo(draft.quotedPoints());
        assertThat(task.inputHash()).isEqualTo(draft.inputHash());
        assertThat(task.createdAt()).isEqualTo(activatedAt);
        verify(mapper, never()).freezePoints(anyLong(), anyInt(), any());
        verify(mapper).insertOutbox(any(), anyString(), anyString());
    }

    private DraftFixture persistDraft() {
        return persistDraft(1);
    }

    private ObjectNode revisionProposal(long id) {
        return proposal().put("draftOperation", "REVISE").put("previousDraftId", Long.toString(id));
    }

    private AgentRunRecord runWithDraft(long id) {
        AgentRunRecord current = run();
        ObjectNode context = (ObjectNode) read(current.contextSnapshotJson());
        context.putObject("taskWorkspace").put("activeDraftId", Long.toString(id));
        return new AgentRunRecord(current.id(), current.conversationId(), current.userId(), current.requestMessageId(),
                current.responseMessageId(), current.selectedSpaceId(), current.selectedProductId(),
                current.attachmentAssetIdsJson(), current.status(), current.contextVersion(), context.toString(),
                current.contextRefHash(), current.contextExpiresAt(), current.pythonEventsUrl(), current.lastPythonSequence(),
                current.usageJson(), current.errorCode(), current.traceId(), current.stopRequestedAt(), current.startedAt(),
                current.completedAt(), current.createdAt(), current.updatedAt(), current.deadlineAt());
    }

    private TaskRecords.Draft draftWithState(TaskRecords.Draft draft, String status, OffsetDateTime expires) {
        return new TaskRecords.Draft(draft.id(), draft.userId(), draft.conversationId(), draft.agentRunId(),
                draft.previousDraftId(), draft.draftSource(), draft.operationScope(), draft.idempotencyKey(), draft.requestHash(),
                draft.taskType(), draft.parameterSchemaVersion(), draft.spaceId(), draft.productId(), draft.parametersJson(),
                draft.inputSnapshotJson(), draft.inputHash(), draft.pricingVersionId(), draft.quotedPoints(), draft.warningsJson(),
                status, expires, draft.confirmedAt(), draft.createdAt(), draft.updatedAt());
    }

    private DraftFixture persistDraft(int count) {
        ObjectNode proposed = proposal();
        ((ObjectNode) proposed.path("parameters")).put("count", count);
        var browserEvent = service.acceptProposal(run(), event(proposed), "", now());
        ArgumentCaptor<TaskRecords.Draft> d = ArgumentCaptor.forClass(TaskRecords.Draft.class);
        ArgumentCaptor<TaskRecords.Confirmation> c = ArgumentCaptor.forClass(TaskRecords.Confirmation.class);
        verify(mapper).insertDraft(d.capture()); verify(mapper).insertConfirmation(c.capture());
        String token = read(browserEvent.payloadJson())
                .path("draft").path("confirmationToken").asText();
        return new DraftFixture(d.getValue(), c.getValue(), token);
    }

    private PaidTaskProperties properties() { PaidTaskProperties p=new PaidTaskProperties();p.setEnabled(true);
        p.setTokenSecret("test-only-secret-material-at-least-32-bytes");p.setConfirmationTtl(Duration.ofMinutes(10));
        p.setExecutionLease(Duration.ofMinutes(2));p.setImageMockPricingVersionId(6001);p.setImageMockPoints(7);
        p.setVideoTestPricingVersionId(7001);p.setVideoTestPoints(11);p.setGeoExecutionEnabled(true);
        p.setGeoTestPricingVersionId(8001);p.setGeoTestUnitPoints(1);p.setArticleExecutionEnabled(true);
        p.setArticleTestPricingVersionId(9001);p.setArticleTitleTestPoints(3);p.setArticleOutlineTestPoints(4);
        p.setArticleBodyTestPoints(5);p.setArticleRewriteTestPoints(2);return p; }

    @Test
    void monitorAdmissionLocksAccountBeforeDraftAndUsesClockAdvancedOnPointLockCompletion() {
        var monitor=mock(com.guanghe.backend.agentplatform.MonitorExecutionService.class);
        var admissions=mock(com.guanghe.backend.agentplatform.MonitorExecutionMapper.class);
        service.setMonitorExecution(monitor,admissions);
        var before=OffsetDateTime.parse("2026-10-08T15:59:59Z");var after=before.plusSeconds(2);
        var instant=new java.util.concurrent.atomic.AtomicReference<>(before.toInstant());
        java.time.Clock mutableClock=new java.time.Clock() {
            public java.time.ZoneId getZone(){return ZoneOffset.UTC;}
            public java.time.Clock withZone(java.time.ZoneId zone){return this;}
            public java.time.Instant instant(){return instant.get();}
        };
        when(monitor.accountingNow()).thenAnswer(call->OffsetDateTime.ofInstant(mutableClock.instant(),ZoneOffset.UTC));
        var draft=new TaskRecords.Draft(7001,41,9001L,UUID.randomUUID(),null,"CHAT",null,null,null,"GEO",1,101,102L,
                "{\"mode\":\"MONITOR_RUN\"}","{}","a".repeat(64),8001,6,"[]","WAITING_CONFIRMATION",after.plusMinutes(10),null,before,before);
        var admission=new com.guanghe.backend.agentplatform.MonitorExecutionRecords.Admission(7101,41,9001,draft.agentRunId(),8001,
                draft.id(),7201L,1,"{}","{}","b".repeat(64),draft.inputHash(),8001,6,"PENDING",null,null,null,null,before,before);
        when(admissions.lockNextAdmission()).thenReturn(Optional.of(admission));
        when(admissions.shareRunnableAccount(41)).thenReturn(41L);
        when(mapper.lockDraft(7001,41)).thenReturn(Optional.of(draft));
        when(conversations.findRun(admission.runId())).thenReturn(Optional.of(run()));
        var permit=new com.guanghe.backend.agentplatform.MonitorExecutionService.Permit(null,null);
        when(monitor.validateAdmission(eq(admission),eq(draft),any())).thenReturn(permit);
        when(mapper.lockConfirmation(7001,41)).thenReturn(Optional.of(new TaskRecords.Confirmation(7301,7001,41,"hash",draft.inputHash(),8001,"AVAILABLE",draft.expiresAt(),null,before)));
        var points=new TaskRecords.PointAccount(41,100,0,1,before,before);
        when(mapper.lockPointAccount(41)).thenAnswer(call->{instant.set(after.toInstant());return Optional.of(points);});
        when(mapper.invalidateConfirmation(7001,41)).thenReturn(1);when(mapper.admitMonitorDraft(eq(7001L),eq(41L),any())).thenReturn(1);
        when(mapper.insertTask(any())).thenReturn(1);when(mapper.freezePoints(anyLong(),anyInt(),any())).thenReturn(1);
        when(mapper.insertReservation(anyLong(),anyLong(),anyLong(),anyInt(),any())).thenReturn(1);
        when(mapper.insertOutbox(any(),anyString(),anyString())).thenReturn(1);
        when(admissions.admit(anyLong(),anyLong(),eq("EXECUTION_PREAUTH"),any())).thenReturn(1);
        assertThat(service.admitNextMonitorAction()).isPresent();
        verify(monitor).validateBudget(permit,draft,points,after);
        var ordered=org.mockito.Mockito.inOrder(admissions,mapper,monitor,conversations);
        ordered.verify(admissions).lockNextAdmission();ordered.verify(admissions).shareRunnableAccount(41);
        ordered.verify(mapper).lockDraft(7001,41);ordered.verify(monitor).validateAdmission(eq(admission),eq(draft),any());
        ordered.verify(mapper).lockPointAccount(41);ordered.verify(monitor).accountingNow();ordered.verify(monitor).validateBudget(permit,draft,points,after);
        ordered.verify(admissions).admit(eq(7101L),anyLong(),eq("EXECUTION_PREAUTH"),eq(after));
        ordered.verify(conversations).nextEventSequence(9001,41);
        var task=ArgumentCaptor.forClass(TaskRecords.Task.class);verify(mapper).insertTask(task.capture());
        assertThat(task.getValue().operationScope()).isEqualTo("EXECUTION_PREAUTH");assertThat(task.getValue().createdAt()).isEqualTo(after);
        verify(mapper,never()).consumeConfirmation(anyLong(),anyLong(),any());verify(mapper,never()).confirmDraft(anyLong(),anyLong(),any());
    }

    @Test
    void monitorManualWinnerReplayPrecedesAccountDisableAndNeverRefreezes() {
        var monitor=mock(com.guanghe.backend.agentplatform.MonitorExecutionService.class);
        var admissions=mock(com.guanghe.backend.agentplatform.MonitorExecutionMapper.class);service.setMonitorExecution(monitor,admissions);
        var admission=new com.guanghe.backend.agentplatform.MonitorExecutionRecords.Admission(7101,41,9001,UUID.randomUUID(),8001,7001,
                7201L,1,"{}","{}","b".repeat(64),"a".repeat(64),8001,6,"PENDING",null,null,null,null,now(),now());
        var existing=task(UUID.randomUUID(),"hash");when(admissions.lockNextAdmission()).thenReturn(Optional.of(admission));
        when(mapper.findTaskByDraft(7001)).thenReturn(Optional.of(existing));
        when(admissions.admit(7101,existing.id(),"USER_CONFIRM",existing.createdAt())).thenReturn(1);
        assertThat(service.admitNextMonitorAction()).isPresent();
        verify(admissions,never()).shareRunnableAccount(anyLong());verify(mapper,never()).lockDraft(anyLong(),anyLong());
        verify(mapper,never()).lockPointAccount(anyLong());verify(mapper,never()).insertTask(any());verify(mapper,never()).freezePoints(anyLong(),anyInt(),any());
    }
    private void assertCode(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(expected));
    }
    private AuthenticatedAccount user(){return new AuthenticatedAccount(41,"user", AccountRole.USER,1, AccountStatus.ACTIVE,null,null,null);}
    private AgentRunRecord run(){OffsetDateTime n=now();return new AgentRunRecord(UUID.randomUUID(),9001,41,8001,8002L,101L,102L,"[]","RUNNING",1,
            "{\"space\":{\"id\":\"101\",\"name\":\"经营空间\"},\"product\":{\"id\":\"102\",\"name\":\"商品\"},\"competitors\":[{\"id\":\"103\",\"name\":\"竞品\"}]}","c".repeat(64),n.plusMinutes(2),null,0,"{}",null,null,null,n,null,n,n);}
    private ObjectNode proposal(){ObjectNode p=json.createObjectNode();p.put("proposalOnly",true);p.put("taskType","IMAGE");p.put("parameterSchemaVersion",1);p.put("spaceId","101");p.put("productId","102");
        ObjectNode parameters=p.putObject("parameters");parameters.put("requestText","生成暖光厨房商品主图");parameters.put("mode","AGENT_ASSISTED");parameters.put("agentSummary","本地确定性建议");parameters.put("aspectRatio","1:1");parameters.put("count",1);p.putArray("warnings").add("Java需重新校验");return p;}
    private ObjectNode geoDiscoveryProposal(){ObjectNode p=json.createObjectNode();p.put("proposalOnly",true);p.put("taskType","GEO");p.put("parameterSchemaVersion",1);p.put("spaceId","101");p.put("productId","102");
        ObjectNode parameters=p.putObject("parameters");parameters.put("requestText","需求词吧");parameters.put("mode","QUESTION_DISCOVERY");parameters.put("agentSummary","围绕美式咖啡生成需求词监测问题");parameters.put("topic","美式咖啡需求词");parameters.put("region","全国");parameters.putArray("questions").add("哪些人适合喝美式咖啡？").add("什么场景适合选择美式咖啡？").add("购买美式咖啡时关注什么？");p.putArray("warnings");return p;}
    private ObjectNode videoProposal(){ObjectNode p=json.createObjectNode();p.put("proposalOnly",true);p.put("taskType","VIDEO");p.put("parameterSchemaVersion",1);p.put("spaceId","101");p.put("productId","102");
        ObjectNode parameters=p.putObject("parameters");parameters.put("requestText","生成5秒商品视频");parameters.put("mode","AGENT_ASSISTED");parameters.put("agentSummary","商品短视频参数");parameters.put("scene","  商品旋转展示  ");p.putArray("warnings");return p;}
    private ObjectNode articleProposal(){ObjectNode p=json.createObjectNode();p.put("proposalOnly",true);p.put("taskType","ARTICLE");p.put("parameterSchemaVersion",2);p.put("spaceId","101");p.put("productId","102");
        ObjectNode parameters=p.putObject("parameters");parameters.put("requestText","基于当前商品创建一篇文章，主题是咖啡好喝又健康");parameters.put("mode","AGENT_ASSISTED");parameters.put("agentSummary","围绕健康饮用场景生成文章标题");parameters.put("topic","咖啡好喝又健康");parameters.put("language","zh-CN");p.putArray("warnings");return p;}
    private AuthorizedCatalogContextFactory.Context catalogContext(){
        InternalRunContextDtos.Space space=new InternalRunContextDtos.Space("101","经营空间",
                new InternalRunContextDtos.Brand("品牌",List.of(),null,List.of(),List.of(),List.of(),List.of()),null);
        InternalRunContextDtos.Product product=new InternalRunContextDtos.Product("102","101","商品",
                List.of(),null,null,List.of(),java.util.Map.of(),List.of(),List.of(),List.of(),null);
        InternalRunContextDtos.Competitor competitor = new InternalRunContextDtos.Competitor(
                "103", "101", "竞品", List.of(),
                com.guanghe.backend.catalog.competitor.CompetitorType.DIRECT,
                null, com.guanghe.backend.catalog.competitor.CompetitorStatus.ACTIVE);
        return new AuthorizedCatalogContextFactory.Context(space,product,List.of(competitor));
    }
    private CreativeTaskSourceService.PreparedSource creativePrepared(ObjectNode raw, long conversationId,
                                                                       Long sourceMessageId) {
        ObjectNode parameters = raw.deepCopy();
        parameters.put("sourceMode", "CATALOG_PRODUCT");
        parameters.putArray("referenceAssetIds").add("9901");
        ObjectNode authorized = json.valueToTree(catalogContext());
        ObjectNode brief = json.createObjectNode();
        brief.put("sourceMode", "CATALOG_PRODUCT");
        brief.put("productId", "102");
        brief.putArray("referenceAssetIds").add("9901");
        ObjectNode provenance = brief.putObject("provenance");
        provenance.put("conversationId", Long.toString(conversationId));
        if (sourceMessageId == null) provenance.putNull("messageId");
        else provenance.put("messageId", Long.toString(sourceMessageId));
        return new CreativeTaskSourceService.PreparedSource(
                101, 102L, sourceMessageId, parameters, authorized, brief);
    }
    private CreativeTaskSourceService.PreparedSource personalCreativePrepared(ObjectNode raw,long conversationId,
                                                                                Long sourceMessageId){
        ObjectNode parameters=raw.deepCopy();parameters.put("sourceMode","TEXT_ONLY");
        parameters.putArray("referenceAssetIds");
        ObjectNode authorized=json.createObjectNode();authorized.putNull("space");authorized.putNull("product");
        authorized.putArray("competitors");authorized.putArray("attachmentAssetIds");
        ObjectNode brief=json.createObjectNode();brief.put("sourceMode","TEXT_ONLY");brief.putNull("productId");
        brief.putArray("referenceAssetIds");brief.putNull("productFacts");brief.putArray("prohibitedClaims");
        ObjectNode provenance=brief.putObject("provenance");provenance.put("conversationId",Long.toString(conversationId));
        provenance.put("messageId",Long.toString(sourceMessageId));
        return new CreativeTaskSourceService.PreparedSource(501,null,sourceMessageId,parameters,authorized,brief);
    }
    private PythonChatRunEvent event(ObjectNode payload){return new PythonChatRunEvent(1,UUID.randomUUID(),"task-draft.proposed",run().id(),now(),1,payload);}
    private TaskRecords.Task task(UUID key,String hash){OffsetDateTime n=now();return new TaskRecords.Task(9002,41,101,102L,9001,7001,"IMAGE","QUEUED","QUEUED",1,"{}","b".repeat(64),6001,7,0,"{}",null,"TASK_CONFIRM",key,hash,n,null,null,n,n);}
    private JsonNode read(String value){try{return json.readTree(value);}catch(Exception e){throw new RuntimeException(e);}}
    private OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private record DraftFixture(TaskRecords.Draft draft,TaskRecords.Confirmation confirmation,String token){}
}
