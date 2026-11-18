package com.guanghe.backend.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.asset.AssetIngestionProperties;
import com.guanghe.backend.asset.AssetMapper;
import com.guanghe.backend.asset.AssetRecords;
import com.guanghe.backend.asset.ObjectStorage;
import com.guanghe.backend.asset.ImageRegionEditService;
import com.guanghe.backend.catalog.product.ProductIdentityAssetRecord;
import com.guanghe.backend.catalog.product.ProductIdentityAssetService;
import com.guanghe.backend.catalog.product.ProductMapper;
import com.guanghe.backend.catalog.space.BusinessSpaceService;
import com.guanghe.backend.geo.GeoTaskMapper;
import com.guanghe.backend.geo.GeoTaskRecords;
import com.guanghe.backend.geo.GeoCreativePlanProof;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.conversation.AgentRunRecord;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class CreativeTaskSourceServiceTests {
    private ProductIdentityAssetService productAssets;
    private AssetMapper assets;
    private ConversationMapper conversations;
    private ObjectStorage storage;
    private AssetIngestionProperties assetProperties;
    private CreativeTaskSourceService service;
    private ObjectMapper json;
    private PaidTaskProperties tasks;
    private ProductMapper products;
    private GeoTaskMapper geoTasks;
    private ImageRegionEditService regionEdits;
    private AuthorizedCatalogContextFactory catalog;
    private BusinessSpaceService spaces;

    @BeforeEach
    void setUp() {
        productAssets = mock(ProductIdentityAssetService.class);
        assets = mock(AssetMapper.class);
        conversations = mock(ConversationMapper.class);
        catalog = mock(AuthorizedCatalogContextFactory.class);
        spaces = mock(BusinessSpaceService.class);
        storage = mock(ObjectStorage.class);
        assetProperties = new AssetIngestionProperties();
        assetProperties.setEnvironment("test");
        assetProperties.setSafetyMode(AssetIngestionProperties.SafetyMode.TEST_BYPASS);
        assetProperties.setTestBypassDownloadEnabled(true);
        assetProperties.setReferenceReadUrlTtl(Duration.ofMinutes(30));
        tasks = new PaidTaskProperties();
        tasks.setTokenSecret("local-geo-plan-test-secret-at-least-32-bytes");
        tasks.setReferenceImageGenerationEnabled(true);
        tasks.setImageToVideoEnabled(true);
        json = new ObjectMapper().findAndRegisterModules();
        when(catalog.create(41, 101L, 102L)).thenReturn(catalogContext());
        service = new CreativeTaskSourceService(productAssets, assets, conversations, catalog, spaces,
                storage, assetProperties, tasks, json);
        products = mock(ProductMapper.class);
        geoTasks = mock(GeoTaskMapper.class);
        regionEdits = mock(ImageRegionEditService.class);
        service.setModeDependencies(provider(products), provider(geoTasks), provider(regionEdits));
    }

    @Test
    void catalogImageReferencesAreFrozenInPrimaryWhiteThenSequenceOrder() {
        List<ProductIdentityAssetRecord> identity = List.of(
                identity(13, "DETAIL", 1), identity(12, "WHITE_BACKGROUND", 2),
                identity(11, "PRIMARY", 3), identity(14, "PACKAGE", 4));
        when(productAssets.requireReadyIdentityAssets(41, 102, 101)).thenReturn(identity);
        identity.forEach(item -> when(assets.findAsset(item.assetId(), 41))
                .thenReturn(Optional.of(asset(item.assetId()))));
        ObjectNode parameters = json.createObjectNode();
        parameters.put("sourceMode", "CATALOG_PRODUCT");
        parameters.putArray("referenceAssetIds").add("11").add("12").add("13");
        ObjectNode visualBrief = parameters.putObject("visualBrief");
        visualBrief.put("version", "commerce-image-v1").put("platform", "PINDUODUO")
                .put("placement", "MAIN_IMAGE").put("visualFocus", "咖啡商品主体")
                .put("composition", "大主体，高对比").put("background", "纯净浅色")
                .put("lighting", "柔和侧光").put("textPolicy", "NONE").putArray("copyLines");

        CreativeTaskSourceService.PreparedSource prepared = service.prepareForm(
                41, 9001, null, 101, 102L, "IMAGE", parameters);

        assertThat(prepared.parameters().path("referenceAssetIds").toString())
                .isEqualTo("[\"11\",\"12\",\"13\"]");
        assertThat(prepared.productCreativeBrief().path("productFacts").path("name").asText())
                .isEqualTo("美式咖啡");
        assertThat(prepared.productCreativeBrief().path("creativeDirection").path("visualBrief"))
                .isEqualTo(visualBrief);
        assertThat(prepared.productCreativeBrief().path("productFacts").has("visualBrief")).isFalse();
        visualBrief.put("background", "之后被修改的输入");
        assertThat(prepared.productCreativeBrief().path("creativeDirection").path("visualBrief")
                .path("background").asText()).isEqualTo("纯净浅色");
    }

    @Test
    void videoCurrentAttachmentsRequiresExactlyOneImageInsteadOfSilentlyDroppingExtras() {
        AgentRunRecord run = mock(AgentRunRecord.class);
        when(run.attachmentAssetIdsJson()).thenReturn("[\"11\",\"12\"]");

        assertThatThrownBy(() -> service.prepareChat(run, "VIDEO", json.createObjectNode()))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(error.getMessage()).contains("只能选择 1 张");
                    assertThat(error.details()).containsEntry("maximum", 1);
                });
    }

    @Test
    void controlledReferenceUrlIsMintedAtExecutionWithAtLeastTwentyMinutesRemaining() {
        when(assets.findAsset(11, 41)).thenReturn(Optional.of(asset(11)));
        when(storage.signedGetUrl(any(), any())).thenAnswer(invocation ->
                URI.create("https://controlled.example/reference.png?expires="
                        + invocation.<OffsetDateTime>getArgument(1).toEpochSecond()));
        TaskRecords.Task task = mock(TaskRecords.Task.class);
        when(task.taskType()).thenReturn("IMAGE");
        when(task.userId()).thenReturn(41L);
        when(task.spaceId()).thenReturn(101L);
        ObjectNode snapshot = json.createObjectNode();
        snapshot.putObject("productCreativeBrief").putArray("referenceAssetIds").add("11");
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

        var result = service.controlledReferences(task, snapshot);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().readUrl()).startsWith("https://");
        assertThat(Duration.between(before, result.getFirst().expiresAt()))
                .isGreaterThanOrEqualTo(Duration.ofMinutes(20));
        assertThat(Duration.between(before, result.getFirst().expiresAt()))
                .isLessThan(Duration.ofMinutes(31));
    }

    @Test
    void textOnlyIsIndependentOfReferenceFlagsAndCannotBorrowProductFacts() {
        tasks.setReferenceImageGenerationEnabled(false);
        tasks.setImageToVideoEnabled(false);
        for (String type : List.of("IMAGE", "VIDEO")) {
            var result = service.prepareForm(41, 9001, null, 101, null, type, source("TEXT_ONLY"));
            assertThat(result.productCreativeBrief().path("productFacts").isNull()).isTrue();
            assertThat(result.productCreativeBrief().path("referenceAssetIds")).isEmpty();
            assertThat(result.authorizedContext().path("product").isNull()).isTrue();
        }
        assertError(() -> service.prepareForm(41, 9001, null, 101, 102L, "IMAGE", source("TEXT_ONLY")),
                ErrorCode.VALIDATION_FAILED);
        assertError(() -> service.prepareForm(41, 9001, null, 101, null, "IMAGE", source("TEXT_ONLY", "11")),
                ErrorCode.VALIDATION_FAILED);
        verify(products, never()).findIdentityAssets(anyLong(), anyLong(), anyLong());
    }

    @Test
    void projectlessNativeTextOnlyUsesPersonalStorageWithoutReadingItAsCatalog() {
        AgentRunRecord run = mock(AgentRunRecord.class);
        when(run.userId()).thenReturn(41L);
        when(run.conversationId()).thenReturn(9001L);
        when(run.requestMessageId()).thenReturn(8001L);
        when(run.selectedSpaceId()).thenReturn(null);
        when(run.attachmentAssetIdsJson()).thenReturn("[]");

        var result = service.prepareNative(run, 501, "IMAGE", source("TEXT_ONLY"));

        assertThat(result.spaceId()).isEqualTo(501);
        assertThat(result.productId()).isNull();
        assertThat(result.authorizedContext().path("space").isNull()).isTrue();
        verify(catalog, never()).create(anyLong(), any(), any());
    }

    @Test
    void projectlessNativeAttachmentFreezesOnlyTheCurrentPersonalStorageAsset() {
        UUID runId = UUID.randomUUID();
        AgentRunRecord run = mock(AgentRunRecord.class);
        when(run.id()).thenReturn(runId);
        when(run.userId()).thenReturn(41L);
        when(run.conversationId()).thenReturn(9001L);
        when(run.requestMessageId()).thenReturn(8001L);
        when(run.selectedSpaceId()).thenReturn(null);
        when(run.attachmentAssetIdsJson()).thenReturn("[\"11\"]");
        when(conversations.findMessageAssetIds(8001, 41, 9001)).thenReturn(List.of(11L));
        when(assets.findAsset(11, 41)).thenReturn(Optional.of(asset(11, 501)));

        var result = service.prepareNative(run, 501, "IMAGE", source("CURRENT_ATTACHMENTS"));

        assertThat(result.parameters().path("referenceAssetIds").toString()).isEqualTo("[\"11\"]");
        assertThat(result.authorizedContext().path("attachmentAssetIds").toString()).isEqualTo("[\"11\"]");
        ObjectNode snapshot = json.createObjectNode();
        snapshot.set("parameters", result.parameters());
        snapshot.set("productCreativeBrief", result.productCreativeBrief());
        snapshot.putObject("nativeCreative").put("runId", runId.toString())
                .put("sourceMessageId", "8001").put("storageKind", "PERSONAL_CREATIVE");
        TaskRecords.Draft draft = mock(TaskRecords.Draft.class);
        when(draft.taskType()).thenReturn("IMAGE");
        when(draft.draftSource()).thenReturn("CHAT");
        when(draft.agentRunId()).thenReturn(runId);
        when(draft.conversationId()).thenReturn(9001L);
        when(draft.userId()).thenReturn(41L);
        when(draft.spaceId()).thenReturn(501L);
        when(draft.productId()).thenReturn(null);
        when(draft.inputSnapshotJson()).thenReturn(snapshot.toString());
        org.mockito.Mockito.clearInvocations(spaces);

        service.revalidate(draft);

        verify(spaces).requirePersonalCreativeSpace(41, 501);
        verify(catalog, never()).create(anyLong(), any(), any());
    }

    @Test
    void previousImageResultIsResolvedByJavaFrozenOnceAndRevalidatedWithoutSelectingLatestAgain() {
        UUID runId = UUID.randomUUID();
        OffsetDateTime boundary = OffsetDateTime.parse("2026-10-15T10:00:00+08:00");
        AgentRunRecord run = mock(AgentRunRecord.class);
        when(run.id()).thenReturn(runId);
        when(run.userId()).thenReturn(41L);
        when(run.conversationId()).thenReturn(9001L);
        when(run.requestMessageId()).thenReturn(8001L);
        when(run.selectedSpaceId()).thenReturn(null);
        when(run.attachmentAssetIdsJson()).thenReturn("[]");
        when(run.createdAt()).thenReturn(boundary);
        AssetRecords.Asset generated = generatedAsset(21, 501, 7001);
        when(assets.findPreviousReadyImageResult(41, 9001, 501, 8001, boundary))
                .thenReturn(Optional.of(generated));
        when(assets.findAsset(21, 41)).thenReturn(Optional.of(generated));

        ObjectNode requested = json.createObjectNode().put("sourceMode", "PREVIOUS_IMAGE_RESULT");
        var prepared = service.prepareNative(run, 501, "IMAGE", requested);

        assertThat(prepared.parameters().path("referenceAssetIds").toString()).isEqualTo("[\"21\"]");
        assertThat(prepared.productCreativeBrief().path("referenceAssetIds").toString()).isEqualTo("[\"21\"]");
        assertThat(prepared.productCreativeBrief().path("provenance").path("messageId").asText())
                .isEqualTo("8001");
        ObjectNode snapshot = json.createObjectNode();
        snapshot.set("parameters", prepared.parameters());
        snapshot.set("productCreativeBrief", prepared.productCreativeBrief());
        snapshot.putObject("nativeCreative").put("runId", runId.toString())
                .put("sourceMessageId", "8001").put("storageKind", "PERSONAL_CREATIVE");
        TaskRecords.Draft draft = chatDraft(runId, 501, snapshot);

        service.revalidate(draft);

        verify(assets).findPreviousReadyImageResult(41, 9001, 501, 8001, boundary);
        verify(spaces, org.mockito.Mockito.atLeastOnce()).requirePersonalCreativeSpace(41, 501);
    }

    @Test
    void previousImageResultCanBecomeVideoFirstFrameButModelCannotSupplyAssetIds() {
        OffsetDateTime boundary = OffsetDateTime.parse("2026-10-15T10:00:00+08:00");
        AgentRunRecord run = mock(AgentRunRecord.class);
        when(run.userId()).thenReturn(41L);
        when(run.conversationId()).thenReturn(9001L);
        when(run.requestMessageId()).thenReturn(8001L);
        when(run.selectedSpaceId()).thenReturn(101L);
        when(run.attachmentAssetIdsJson()).thenReturn("[]");
        when(run.createdAt()).thenReturn(boundary);
        AssetRecords.Asset generated = generatedAsset(21, 101, 7001);
        when(assets.findPreviousReadyImageResult(41, 9001, 101, 8001, boundary))
                .thenReturn(Optional.of(generated));
        when(assets.findAsset(21, 41)).thenReturn(Optional.of(generated));
        ObjectNode requested = json.createObjectNode().put("sourceMode", "PREVIOUS_IMAGE_RESULT");

        var prepared = service.prepareNative(run, 101, "VIDEO", requested);

        assertThat(prepared.parameters().path("referenceAssetIds").toString()).isEqualTo("[\"21\"]");
        ObjectNode forged = requested.deepCopy();
        forged.putArray("referenceAssetIds").add("21");
        assertError(() -> service.prepareNative(run, 101, "VIDEO", forged), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void reviewedContinuationFreezesTheExactImageAndNeverRunsTheLatestImageSelectorAgain() {
        AgentRunRecord run=mock(AgentRunRecord.class);
        when(run.userId()).thenReturn(41L);when(run.conversationId()).thenReturn(9001L);
        when(run.selectedSpaceId()).thenReturn(null);
        when(run.requestMessageId()).thenReturn(8001L);when(run.attachmentAssetIdsJson()).thenReturn("[]");
        when(assets.findAsset(21,41)).thenReturn(Optional.of(generatedAsset(21,501,7001)));
        ObjectNode requested=json.createObjectNode().put("sourceMode","PREVIOUS_IMAGE_RESULT");
        var result=service.prepareNative(run,501,"IMAGE",requested,21L);
        assertThat(result.parameters().path("referenceAssetIds").toString()).isEqualTo("[\"21\"]");
        verify(assets,never()).findPreviousReadyImageResult(anyLong(),anyLong(),anyLong(),anyLong(),any());
        assertError(()->service.prepareNative(run,501,"IMAGE",source("TEXT_ONLY"),21L),ErrorCode.CREATIVE_SOURCE_REQUIRED);
        when(assets.findAsset(21,41)).thenReturn(Optional.of(asset(21,501)));
        assertError(()->service.prepareNative(run,501,"IMAGE",requested,21L),ErrorCode.CREATIVE_SOURCE_REQUIRED);
    }

    @Test
    void previousImageResultRejectsWhenNoReadySameConversationResultExists() {
        OffsetDateTime boundary = OffsetDateTime.parse("2026-10-15T10:00:00+08:00");
        AgentRunRecord run = mock(AgentRunRecord.class);
        when(run.userId()).thenReturn(41L);
        when(run.conversationId()).thenReturn(9001L);
        when(run.requestMessageId()).thenReturn(8001L);
        when(run.selectedSpaceId()).thenReturn(101L);
        when(run.attachmentAssetIdsJson()).thenReturn("[]");
        when(run.createdAt()).thenReturn(boundary);
        when(assets.findPreviousReadyImageResult(41, 9001, 101, 8001, boundary))
                .thenReturn(Optional.empty());

        assertError(() -> service.prepareNative(run, 101, "IMAGE",
                json.createObjectNode().put("sourceMode", "PREVIOUS_IMAGE_RESULT")),
                ErrorCode.CREATIVE_SOURCE_REQUIRED);

        verify(assets, never()).findAsset(anyLong(), anyLong());
    }

    @Test
    void previousUserAttachmentFreezesImmediatePriorUserMessageAndRevalidatesOnlyThatPair() {
        UUID runId = UUID.randomUUID();
        AgentRunRecord run = mock(AgentRunRecord.class);
        when(run.id()).thenReturn(runId);
        when(run.userId()).thenReturn(41L);
        when(run.conversationId()).thenReturn(9001L);
        when(run.requestMessageId()).thenReturn(8003L);
        when(run.selectedSpaceId()).thenReturn(null);
        when(run.attachmentAssetIdsJson()).thenReturn("[]");
        when(assets.findPreviousUserAttachment(41, 9001, 8003))
                .thenReturn(Optional.of(new AssetRecords.PreviousUserAttachment(8001, 1, 11L)));
        when(assets.findAsset(11, 41)).thenReturn(Optional.of(asset(11, 501)));

        ObjectNode requested = json.createObjectNode().put("sourceMode", "PREVIOUS_USER_ATTACHMENT");
        var prepared = service.prepareNative(run, 501, "IMAGE", requested);

        assertThat(prepared.parameters().path("referenceAssetIds").toString()).isEqualTo("[\"11\"]");
        assertThat(prepared.productCreativeBrief().at("/provenance/messageId").asText()).isEqualTo("8003");
        assertThat(prepared.productCreativeBrief().at("/provenance/referenceMessageId").asText())
                .isEqualTo("8001");

        ObjectNode snapshot = json.createObjectNode();
        snapshot.set("parameters", prepared.parameters());
        snapshot.set("productCreativeBrief", prepared.productCreativeBrief());
        snapshot.putObject("nativeCreative").put("runId", runId.toString())
                .put("sourceMessageId", "8003").put("storageKind", "PERSONAL_CREATIVE");
        when(conversations.findMessage(8003, 41, 9001)).thenReturn(Optional.of(
                message(8003, runId, 3)));
        when(conversations.findMessage(8001, 41, 9001)).thenReturn(Optional.of(
                message(8001, runId, 1)));
        when(conversations.findMessageAssetIds(8001, 41, 9001)).thenReturn(List.of(11L));

        service.revalidate(chatDraft(runId, 501, snapshot));

        verify(assets).findPreviousUserAttachment(41, 9001, 8003);
        verify(conversations).findMessageAssetIds(8001, 41, 9001);
    }

    @Test
    void previousUserAttachmentDoesNotAcceptMissingOrMultipleAttachmentsOrModelIds() {
        AgentRunRecord run = mock(AgentRunRecord.class);
        when(run.userId()).thenReturn(41L);
        when(run.conversationId()).thenReturn(9001L);
        when(run.requestMessageId()).thenReturn(8003L);
        when(run.selectedSpaceId()).thenReturn(101L);
        when(run.attachmentAssetIdsJson()).thenReturn("[]");
        when(assets.findPreviousUserAttachment(41, 9001, 8003))
                .thenReturn(Optional.of(new AssetRecords.PreviousUserAttachment(8001, 0, null)),
                        Optional.of(new AssetRecords.PreviousUserAttachment(8001, 2, null)));
        ObjectNode requested = json.createObjectNode().put("sourceMode", "PREVIOUS_USER_ATTACHMENT");

        assertError(() -> service.prepareNative(run, 101, "IMAGE", requested),
                ErrorCode.CREATIVE_SOURCE_REQUIRED);
        assertError(() -> service.prepareNative(run, 101, "IMAGE", requested),
                ErrorCode.CREATIVE_SOURCE_REQUIRED);
        ObjectNode forged = requested.deepCopy();
        forged.putArray("referenceAssetIds").add("11");
        assertError(() -> service.prepareNative(run, 101, "IMAGE", forged), ErrorCode.VALIDATION_FAILED);

        verify(assets, never()).findAsset(anyLong(), anyLong());
    }

    @Test
    void previousUserAttachmentConfirmationRejectsAChangedFrozenMessageAssociation() {
        UUID runId = UUID.randomUUID();
        AgentRunRecord run = mock(AgentRunRecord.class);
        when(run.id()).thenReturn(runId);
        when(run.userId()).thenReturn(41L);
        when(run.conversationId()).thenReturn(9001L);
        when(run.requestMessageId()).thenReturn(8003L);
        when(run.selectedSpaceId()).thenReturn(101L);
        when(run.attachmentAssetIdsJson()).thenReturn("[]");
        when(assets.findPreviousUserAttachment(41, 9001, 8003))
                .thenReturn(Optional.of(new AssetRecords.PreviousUserAttachment(8001, 1, 11L)));
        when(assets.findAsset(11, 41)).thenReturn(Optional.of(asset(11)));
        var prepared = service.prepareNative(run, 101, "IMAGE",
                json.createObjectNode().put("sourceMode", "PREVIOUS_USER_ATTACHMENT"));
        ObjectNode snapshot = json.createObjectNode();
        snapshot.set("parameters", prepared.parameters());
        snapshot.set("productCreativeBrief", prepared.productCreativeBrief());
        snapshot.putObject("nativeCreative").put("runId", runId.toString())
                .put("sourceMessageId", "8003").put("storageKind", "PROJECT");
        when(conversations.findMessage(8003, 41, 9001)).thenReturn(Optional.of(message(8003, runId, 3)));
        when(conversations.findMessage(8001, 41, 9001)).thenReturn(Optional.of(message(8001, runId, 1)));
        when(conversations.findMessageAssetIds(8001, 41, 9001)).thenReturn(List.of(12L));

        assertError(() -> service.revalidate(chatDraft(runId, 101, snapshot)),
                ErrorCode.TASK_CONFIRMATION_MISMATCH);
    }

    @Test
    void formCurrentAttachmentsCannotUsePersonalCreativeStorage() {
        when(spaces.requireProjectSpace(41, 501))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        assertError(() -> service.prepareForm(41, 9001, 8001L, 501, null, "IMAGE",
                source("CURRENT_ATTACHMENTS", "11")), ErrorCode.RESOURCE_NOT_FOUND);

        verify(conversations, never()).findMessage(anyLong(), anyLong(), anyLong());
        verify(assets, never()).findAsset(anyLong(), anyLong());
    }

    @Test
    void ordinaryProjectChatAttachmentStillRevalidatesWithoutNativeMetadata() {
        UUID runId = UUID.randomUUID();
        AgentRunRecord run = mock(AgentRunRecord.class);
        when(run.id()).thenReturn(runId);
        when(run.userId()).thenReturn(41L);
        when(run.conversationId()).thenReturn(9001L);
        when(run.requestMessageId()).thenReturn(8001L);
        when(run.selectedSpaceId()).thenReturn(101L);
        when(run.attachmentAssetIdsJson()).thenReturn("[\"11\"]");
        when(conversations.findMessageAssetIds(8001, 41, 9001)).thenReturn(List.of(11L));
        when(assets.findAsset(11, 41)).thenReturn(Optional.of(asset(11)));
        var prepared = service.prepareChat(run, "IMAGE", source("CURRENT_ATTACHMENTS"));
        ObjectNode snapshot = json.createObjectNode();
        snapshot.set("parameters", prepared.parameters());
        snapshot.set("productCreativeBrief", prepared.productCreativeBrief());
        TaskRecords.Draft draft = chatDraft(runId, 101, snapshot);

        service.revalidate(draft);

        verify(spaces).requireProjectSpace(41, 101);
    }

    @Test
    void ordinaryChatMetadataCannotTurnPersonalStorageIntoAProject() {
        UUID runId = UUID.randomUUID();
        ObjectNode snapshot = json.createObjectNode();
        snapshot.set("parameters", source("CURRENT_ATTACHMENTS", "11"));
        ObjectNode brief = snapshot.putObject("productCreativeBrief");
        brief.put("sourceMode", "CURRENT_ATTACHMENTS");
        brief.putArray("referenceAssetIds").add("11");
        brief.putObject("provenance").put("messageId", "8001");
        TaskRecords.Draft draft = chatDraft(runId, 501, snapshot);
        when(spaces.requireProjectSpace(41, 501))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        assertError(() -> service.revalidate(draft), ErrorCode.RESOURCE_NOT_FOUND);

        verify(spaces, never()).requirePersonalCreativeSpace(anyLong(), anyLong());
        verify(assets, never()).findAsset(anyLong(), anyLong());
    }

    @Test
    void projectlessNativeTextOnlyRevalidatesItsFrozenPersonalStorageAndRunProvenance() {
        UUID runId = UUID.randomUUID();
        AgentRunRecord run = mock(AgentRunRecord.class);
        when(run.id()).thenReturn(runId);
        when(run.userId()).thenReturn(41L);
        when(run.conversationId()).thenReturn(9001L);
        when(run.requestMessageId()).thenReturn(8001L);
        when(run.selectedSpaceId()).thenReturn(null);
        when(run.attachmentAssetIdsJson()).thenReturn("[]");
        var prepared = service.prepareNative(run, 501, "IMAGE", source("TEXT_ONLY"));
        org.mockito.Mockito.clearInvocations(spaces);

        ObjectNode snapshot = json.createObjectNode();
        snapshot.set("parameters", prepared.parameters());
        snapshot.set("productCreativeBrief", prepared.productCreativeBrief());
        snapshot.putObject("nativeCreative").put("runId", runId.toString())
                .put("sourceMessageId", "8001").put("storageKind", "PERSONAL_CREATIVE");
        TaskRecords.Draft draft = mock(TaskRecords.Draft.class);
        when(draft.taskType()).thenReturn("IMAGE");
        when(draft.draftSource()).thenReturn("CHAT");
        when(draft.agentRunId()).thenReturn(runId);
        when(draft.conversationId()).thenReturn(9001L);
        when(draft.productId()).thenReturn(null);
        when(draft.userId()).thenReturn(41L);
        when(draft.spaceId()).thenReturn(501L);
        when(draft.inputSnapshotJson()).thenReturn(snapshot.toString());

        service.revalidate(draft);

        verify(spaces).requirePersonalCreativeSpace(41, 501);
        verify(catalog, never()).create(anyLong(), any(), any());
    }

    @Test
    void selectedAssetsDoNotRequireChatMessageButEnforceOwnershipAndVideoCount() {
        when(assets.findAsset(11, 41)).thenReturn(Optional.of(asset(11)));
        var result = service.prepareForm(41, 9001, null, 101, null, "IMAGE", source("SELECTED_ASSETS", "11"));
        assertThat(result.sourceMessageId()).isNull();
        assertThat(result.productCreativeBrief().path("productFacts").isNull()).isTrue();
        verify(conversations, never()).findMessageAssetIds(anyLong(), anyLong(), anyLong());
        assertError(() -> service.prepareForm(41, 9001, null, 101, null, "VIDEO",
                source("SELECTED_ASSETS", "11", "12")), ErrorCode.VALIDATION_FAILED);
        assertError(() -> service.prepareForm(41, 9001, null, 101, null, "IMAGE",
                source("SELECTED_ASSETS", "999")), ErrorCode.REFERENCE_ASSET_NOT_OWNED);
        assertError(() -> service.prepareForm(41, 9001, null, 999, null, "IMAGE",
                source("SELECTED_ASSETS", "11")), ErrorCode.REFERENCE_ASSET_NOT_OWNED);
    }

    @Test
    void productMaterialsAllowDetailAndPackageWithoutPrimaryButRejectUnboundAssets() {
        when(products.findIdentityAssets(102, 41, 101)).thenReturn(List.of(identity(11, "DETAIL", 1), identity(12, "PACKAGE", 2)));
        when(assets.findAsset(11, 41)).thenReturn(Optional.of(asset(11)));
        when(assets.findAsset(12, 41)).thenReturn(Optional.of(asset(12)));
        var result = service.prepareForm(41, 9001, null, 101, 102L, "VIDEO", source("PRODUCT_MATERIALS", "12", "11"));
        assertThat(result.parameters().path("referenceAssetIds").toString()).isEqualTo("[\"12\",\"11\"]");
        assertThat(result.productCreativeBrief().path("productFacts").path("name").asText()).isEqualTo("美式咖啡");
        verify(productAssets, never()).requireReadyIdentityAssets(anyLong(), anyLong(), anyLong());
        assertError(() -> service.prepareForm(41, 9001, null, 101, 102L, "VIDEO",
                source("PRODUCT_MATERIALS", "13")), ErrorCode.REFERENCE_ASSET_NOT_OWNED);
        assertError(() -> service.prepareForm(41, 9001, null, 101, 102L, "IMAGE",
                source("PRODUCT_MATERIALS", "11")), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void confirmationRejectsProductMaterialRemovedSinceQuote() {
        when(products.findIdentityAssets(102, 41, 101)).thenReturn(List.of());
        TaskRecords.Draft draft = draft("VIDEO", 102L, source("PRODUCT_MATERIALS", "11"), source("PRODUCT_MATERIALS", "11"));
        assertError(() -> service.revalidate(draft), ErrorCode.REFERENCE_ASSET_NOT_OWNED);
    }

    @Test
    void geoVideoRequiresSynthesizedPlanAndFreezesOnlyBoundedEvidenceKeys() {
        GeoTaskRecords.Report report = mock(GeoTaskRecords.Report.class);
        GeoTaskRecords.Run run = mock(GeoTaskRecords.Run.class);
        when(report.id()).thenReturn(301L);
        when(report.runId()).thenReturn(302L);
        when(report.userId()).thenReturn(41L);
        when(report.status()).thenReturn("PARTIAL");
        when(report.sampleSize()).thenReturn(3);
        when(report.summary()).thenReturn("有限样本中的可见度不足");
        when(report.methodVersion()).thenReturn("geo-synthesis-v1");
        when(report.synthesisJson()).thenReturn("{\"findings\":[\"真实发现\"],\"missingSamples\":[\"KIMI超时\"],\"coverageGaps\":[\"问题覆盖不足\"],\"recommendations\":[{\"priority\":\"HIGH\",\"title\":\"补足商品说明\",\"rationale\":\"依据样本\"}]}");
        when(geoTasks.findReport(301, 41)).thenReturn(Optional.of(report));
        when(geoTasks.findRun(302, 41)).thenReturn(Optional.of(run));
        when(run.status()).thenReturn("PARTIAL");
        when(run.succeededUnits()).thenReturn(3);
        when(run.spaceId()).thenReturn(101L);
        when(run.productId()).thenReturn(102L);
        when(productAssets.requireReadyIdentityAssets(41, 102, 101)).thenReturn(List.of(identity(11, "PRIMARY", 1)));
        when(assets.findAsset(11, 41)).thenReturn(Optional.of(asset(11)));
        successfulObservation();
        ObjectNode parameters = source("GEO_REPORT", "11").put("geoReportId", "301")
                .put("scene", "展示商品日常使用场景");
        parameters.set("creativePlan", plan("VIDEO", null));
        var result = service.prepareForm(41, 9001, null, 101, 102L, "VIDEO", parameters);
        var frozen = result.productCreativeBrief().path("creativeDirection").path("creativePlan");
        assertThat(frozen.path("evidenceKeys").get(0).asText()).isEqualTo("key-1");
        assertThat(frozen.path("reportId").asText()).isEqualTo("301");
        assertThat(result.productCreativeBrief().path("creativeDirection").has("geoInsight")).isFalse();
        assertThat(result.parameters().has("geoInsight")).isFalse();
        service.revalidate(draft("VIDEO", 102L, result.parameters(), result.productCreativeBrief()));
        when(report.methodVersion()).thenReturn("geo-deterministic-v1");
        assertError(() -> service.prepareForm(41, 9001, null, 101, 102L, "VIDEO", parameters),
                ErrorCode.VALIDATION_FAILED);
        when(report.methodVersion()).thenReturn("geo-synthesis-v1");
        when(run.productId()).thenReturn(999L);
        assertError(() -> service.prepareForm(41, 9001, null, 101, 102L, "VIDEO", parameters), ErrorCode.VALIDATION_FAILED);
        when(run.productId()).thenReturn(102L);
        when(report.status()).thenReturn("RUNNING");
        assertError(() -> service.prepareForm(41, 9001, null, 101, 102L, "VIDEO", parameters), ErrorCode.VALIDATION_FAILED);
        assertError(() -> service.prepareForm(41, 9001, null, 101, 102L, "VIDEO", parameters.deepCopy().put("geoReportId", "999")),
                ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void geoImageUsesOwnedReportAndFreezesSelectedRecommendationThroughConfirmation() {
        GeoTaskRecords.Report report = mock(GeoTaskRecords.Report.class);
        GeoTaskRecords.Run run = mock(GeoTaskRecords.Run.class);
        when(report.id()).thenReturn(301L);
        when(report.runId()).thenReturn(302L);
        when(report.userId()).thenReturn(41L);
        when(report.status()).thenReturn("PARTIAL");
        when(report.sampleSize()).thenReturn(3);
        when(report.summary()).thenReturn("有限样本的场景覆盖不足");
        when(report.methodVersion()).thenReturn("geo-synthesis-v1");
        when(report.synthesisJson()).thenReturn("{\"recommendations\":[{\"priority\":\"HIGH\",\"title\":\"补足场景图\",\"rationale\":\"依据监测样本\"}],\"missingSamples\":[\"KIMI超时\"]}");
        when(geoTasks.findReport(301, 41)).thenReturn(Optional.of(report));
        when(geoTasks.findRun(302, 41)).thenReturn(Optional.of(run));
        when(run.status()).thenReturn("PARTIAL");
        when(run.succeededUnits()).thenReturn(3);
        when(run.spaceId()).thenReturn(101L);
        when(run.productId()).thenReturn(102L);
        when(productAssets.requireReadyIdentityAssets(41, 102, 101))
                .thenReturn(List.of(identity(11, "PRIMARY", 1)));
        when(assets.findAsset(11, 41)).thenReturn(Optional.of(asset(11)));
        successfulObservation();
        ObjectNode parameters = source("GEO_REPORT").put("geoReportId", "301")
                .put("geoRecommendationId", "301:recommendation:1")
                .put("scene", "展示商品日常使用场景");
        parameters.set("creativePlan", plan("IMAGE", "301:recommendation:1"));

        var prepared = service.prepareForm(41, 9001, null, 101, 102L, "IMAGE", parameters);
        var frozen = prepared.productCreativeBrief().path("creativeDirection").path("creativePlan");
        assertThat(prepared.parameters().path("referenceAssetIds").toString()).isEqualTo("[\"11\"]");
        assertThat(frozen.path("recommendationId").asText()).isEqualTo("301:recommendation:1");
        assertThat(frozen.path("shotBeats").isEmpty()).isTrue();
        service.revalidate(draft("IMAGE", 102L, prepared.parameters(), prepared.productCreativeBrief()));
        ObjectNode noPlan = parameters.deepCopy();
        noPlan.remove("creativePlan");
        assertError(() -> service.prepareForm(41, 9001, null, 101, 102L, "IMAGE", noPlan),
                ErrorCode.VALIDATION_FAILED);
        ObjectNode tampered = parameters.deepCopy().put("scene", "另一个场景");
        assertError(() -> service.prepareForm(41, 9001, null, 101, 102L, "IMAGE", tampered),
                ErrorCode.VALIDATION_FAILED);
        ObjectNode forgedPlan = parameters.deepCopy();
        ((ObjectNode) forgedPlan.path("creativePlan")).put("message", "未由规划服务给出的承诺");
        assertError(() -> service.prepareForm(41, 9001, null, 101, 102L, "IMAGE", forgedPlan),
                ErrorCode.VALIDATION_FAILED);
        ObjectNode oldPlan = parameters.deepCopy();
        ((ObjectNode) oldPlan.path("creativePlan")).remove("proof");
        assertError(() -> service.prepareForm(41, 9001, null, 101, 102L, "IMAGE", oldPlan),
                ErrorCode.VALIDATION_FAILED);

        InternalRunContextDtos.Product original = catalogContext().product();
        InternalRunContextDtos.Product revised = new InternalRunContextDtos.Product(
                original.id(), original.spaceId(), original.name(), original.aliases(), original.category(),
                original.modelSpec(), original.sellingPoints(), original.parameters(), original.audience(),
                original.usageScenarios(), List.of("新的禁用承诺"), original.primaryAssetId(),
                original.identityAssetIds());
        when(catalog.create(41, 101L, 102L)).thenReturn(
                new AuthorizedCatalogContextFactory.Context(catalogContext().space(), revised, List.of()));
        assertError(() -> service.revalidate(draft("IMAGE", 102L, prepared.parameters(),
                prepared.productCreativeBrief())), ErrorCode.VALIDATION_FAILED);
        when(catalog.create(41, 101L, 102L)).thenReturn(catalogContext());

        assertError(() -> service.prepareForm(41, 9001, null, 101, 102L, "IMAGE",
                parameters.deepCopy().put("geoRecommendationId", "999:recommendation:1")),
                ErrorCode.VALIDATION_FAILED);
        when(run.productId()).thenReturn(999L);
        assertError(() -> service.prepareForm(41, 9001, null, 101, 102L, "IMAGE", parameters),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void regionEditIsDisabledByDefaultAndValidatesSeparateMaskBeforeQuoteAndConfirmation() {
        ObjectNode parameters = source("REGION_EDIT", "11").put("maskAssetId", "12").put("requestText", "改成蓝色");
        assertError(() -> service.prepareForm(41, 9001, null, 101, null, "IMAGE", parameters), ErrorCode.REFERENCE_IMAGE_MODE_UNAVAILABLE);
        tasks.setImageRegionEditEnabled(true);
        when(assets.findAsset(11, 41)).thenReturn(Optional.of(asset(11)));
        when(assets.findAsset(12, 41)).thenReturn(Optional.of(asset(12)));
        var result = service.prepareForm(41, 9001, null, 101, null, "IMAGE", parameters);
        verify(regionEdits).validateInputs(any(AssetRecords.Asset.class), any(AssetRecords.Asset.class));
        assertThat(result.parameters().path("referenceAssetIds").toString()).isEqualTo("[\"11\"]");
        assertThat(result.parameters().path("maskAssetId").asText()).isEqualTo("12");
        tasks.setImageRegionEditEnabled(false);
        assertError(() -> service.revalidate(draft("IMAGE", null, result.parameters(), result.productCreativeBrief())),
                ErrorCode.REFERENCE_IMAGE_MODE_UNAVAILABLE);
    }

    @Test
    void formModeFieldsCannotLeakIntoOtherModesOrOldChatFlow() {
        assertError(() -> service.prepareForm(41, 9001, null, 101, null, "IMAGE",
                source("TEXT_ONLY").put("maskAssetId", "12")), ErrorCode.VALIDATION_FAILED);
        assertError(() -> service.prepareChat(mock(AgentRunRecord.class), "IMAGE",
                source("REGION_EDIT", "11").put("maskAssetId", "12")), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void regionInstructionBecomesProviderSceneWhenOptionalSceneIsAbsent() {
        tasks.setImageRegionEditEnabled(true);
        when(assets.findAsset(11, 41)).thenReturn(Optional.of(asset(11)));
        when(assets.findAsset(12, 41)).thenReturn(Optional.of(asset(12)));
        ObjectNode parameters = source("REGION_EDIT", "11")
                .put("maskAssetId", "12").put("requestText", "将涂抹区域改成蓝色");

        var result = service.prepareForm(41, 9001, null, 101, null, "IMAGE", parameters);

        assertThat(result.parameters().path("scene").asText()).isEqualTo("将涂抹区域改成蓝色");
        assertThat(result.productCreativeBrief().path("creativeDirection").path("scene"))
                .isEqualTo(result.parameters().path("requestText"));
        service.revalidate(draft("IMAGE", null, result.parameters(), result.productCreativeBrief()));
        assertThat(parameters.has("scene")).isFalse();
    }

    @Test
    void regionInstructionOverridesLongSecondarySceneAndCannotDivergeAfterQuote() {
        tasks.setImageRegionEditEnabled(true);
        when(assets.findAsset(11, 41)).thenReturn(Optional.of(asset(11)));
        when(assets.findAsset(12, 41)).thenReturn(Optional.of(asset(12)));
        ObjectNode parameters = source("REGION_EDIT", "11").put("maskAssetId", "12")
                .put("requestText", "将涂抹区域改成红色").put("scene", "蓝".repeat(801));

        var result = service.prepareForm(41, 9001, null, 101, null, "IMAGE", parameters);

        assertThat(result.parameters().path("scene").asText()).isEqualTo("将涂抹区域改成红色");
        service.revalidate(draft("IMAGE", null, result.parameters(), result.productCreativeBrief()));
        ObjectNode mismatched = result.parameters().deepCopy().put("scene", "改成蓝色");
        assertError(() -> service.revalidate(draft("IMAGE", null, mismatched, result.productCreativeBrief())),
                ErrorCode.TASK_CONFIRMATION_MISMATCH);
        assertError(() -> service.prepareForm(41, 9001, null, 101, null, "IMAGE",
                parameters.deepCopy().put("requestText", "红".repeat(801))), ErrorCode.VALIDATION_FAILED);
    }

    private ObjectNode source(String mode, String... ids) {
        ObjectNode node = json.createObjectNode().put("sourceMode", mode);
        var values = node.putArray("referenceAssetIds");
        for (String id : ids) values.add(id);
        return node;
    }

    private ObjectNode plan(String taskType, String recommendationId) {
        ObjectNode plan = json.createObjectNode();
        plan.put("version", "geo-visual-plan-v1");
        plan.put("taskType", taskType);
        plan.put("reportId", "301");
        if (recommendationId == null) plan.putNull("recommendationId");
        else plan.put("recommendationId", recommendationId);
        plan.put("opportunity", "有限样本中缺少场景表达");
        plan.put("audience", "潜在购买者");
        plan.put("message", "展示真实日常使用");
        plan.putArray("evidenceKeys").add("key-1");
        plan.put("scene", "展示商品日常使用场景");
        plan.put("visualFocus", "以商品主体为中心");
        var beats = plan.putArray("shotBeats");
        if ("VIDEO".equals(taskType)) beats.add("建立商品场景").add("展示使用瞬间");
        plan.putArray("limitations").add("仅基于当前成功样本，不承诺监测外效果");
        new GeoCreativePlanProof(tasks, json).sign(plan, 41, 101, 102, catalogContext().product());
        return plan;
    }

    private void successfulObservation() {
        GeoTaskRecords.Observation observation = mock(GeoTaskRecords.Observation.class);
        when(observation.userId()).thenReturn(41L);
        when(observation.status()).thenReturn("SUCCEEDED");
        when(observation.answerText()).thenReturn("真实回答");
        when(geoTasks.findObservationByUnit(302, "key-1")).thenReturn(Optional.of(observation));
    }

    private TaskRecords.Draft draft(String type, Long productId, ObjectNode parameters, ObjectNode brief) {
        TaskRecords.Draft draft = mock(TaskRecords.Draft.class);
        when(draft.taskType()).thenReturn(type);
        when(draft.draftSource()).thenReturn("FORM");
        when(draft.productId()).thenReturn(productId);
        when(draft.userId()).thenReturn(41L);
        when(draft.spaceId()).thenReturn(101L);
        ObjectNode snapshot = json.createObjectNode();
        snapshot.set("parameters", parameters);
        snapshot.set("productCreativeBrief", brief);
        when(draft.inputSnapshotJson()).thenReturn(snapshot.toString());
        return draft;
    }

    private TaskRecords.Draft chatDraft(UUID runId, long spaceId, ObjectNode snapshot) {
        TaskRecords.Draft draft = mock(TaskRecords.Draft.class);
        when(draft.taskType()).thenReturn("IMAGE");
        when(draft.draftSource()).thenReturn("CHAT");
        when(draft.agentRunId()).thenReturn(runId);
        when(draft.conversationId()).thenReturn(9001L);
        when(draft.userId()).thenReturn(41L);
        when(draft.spaceId()).thenReturn(spaceId);
        when(draft.productId()).thenReturn(null);
        when(draft.inputSnapshotJson()).thenReturn(snapshot.toString());
        return draft;
    }

    private void assertError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(code));
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private ProductIdentityAssetRecord identity(long id, String role, int sequence) {
        return new ProductIdentityAssetRecord(id, role, sequence, "READY", "TEST_BYPASS",
                "image/png", 512, "a".repeat(64), 64, 64,
                "zhixiaohe/test/users/41/assets/" + id + "/original.png");
    }

    private AssetRecords.Asset asset(long id) {
        return asset(id, 101);
    }

    private AssetRecords.Asset asset(long id, long spaceId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AssetRecords.Asset(id, 41, spaceId, null, "IMAGE", "UPLOADED", null,
                "reference.png", "zhixiaohe/test/users/41/assets/" + id + "/original.png",
                "image/png", 512, "a".repeat(64), 64, 64, null,
                "READY", "TEST_BYPASS", now, now);
    }

    private AssetRecords.Asset generatedAsset(long id, long spaceId, long sourceTaskId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AssetRecords.Asset(id, 41, spaceId, null, "IMAGE", "GENERATED", sourceTaskId,
                "generated.png", "zhixiaohe/test/users/41/assets/" + id + "/generated.png",
                "image/png", 512, "b".repeat(64), 1024, 1024, null,
                "READY", "TEST_BYPASS", now, now);
    }

    private com.guanghe.backend.conversation.ConversationMessageRecord message(
            long id, UUID runId, long sequence) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new com.guanghe.backend.conversation.ConversationMessageRecord(
                id, 9001, 41, "USER", "TEXT", "{\"text\":\"request\"}", null,
                runId, UUID.randomUUID(), UUID.randomUUID(), "f".repeat(64), sequence,
                "COMPLETED", now, now);
    }

    private AuthorizedCatalogContextFactory.Context catalogContext() {
        InternalRunContextDtos.Space space = new InternalRunContextDtos.Space(
                "101", "抖店", new InternalRunContextDtos.Brand(
                "品牌", List.of(), null, List.of(), List.of(), List.of(), List.of()), null);
        InternalRunContextDtos.Product product = new InternalRunContextDtos.Product(
                "102", "101", "美式咖啡", List.of(), "咖啡", null,
                List.of("现磨"), Map.of(), List.of(), List.of("日常提神"),
                List.of("治疗疾病"), "11", List.of("11", "12", "13", "14"));
        return new AuthorizedCatalogContextFactory.Context(space, product, List.of());
    }
}
