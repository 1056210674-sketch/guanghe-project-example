package com.guanghe.backend.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.conversation.AgentRunRecord;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import com.guanghe.backend.geo.GeoTaskMapper;
import com.guanghe.backend.geo.GeoTaskRecords;
import com.guanghe.backend.geo.GeoCreativePlanValidator;
import com.guanghe.backend.geo.GeoCreativePlanProof;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Resolves and freezes the authoritative reference-image source for IMAGE and VIDEO tasks. */
@Service
@ConditionalOnExpression("'${zhixiaohe.paid-task.enabled:false}' == 'true'"
        + " and '${zhixiaohe.asset.enabled:false}' == 'true'")
public class CreativeTaskSourceService {
    private final ProductIdentityAssetService productAssets;
    private final AssetMapper assets;
    private final ConversationMapper conversations;
    private final AuthorizedCatalogContextFactory catalogContexts;
    private final BusinessSpaceService spaces;
    private final ObjectStorage storage;
    private final AssetIngestionProperties assetProperties;
    private final PaidTaskProperties taskProperties;
    private final ObjectMapper json;
    private final GeoCreativePlanProof geoPlanProof;
    private ProductMapper products;
    private GeoTaskMapper geoTasks;
    private ImageRegionEditService regionEdits;

    @Autowired
    void setModeDependencies(ObjectProvider<ProductMapper> products, ObjectProvider<GeoTaskMapper> geoTasks,
                             ObjectProvider<ImageRegionEditService> regionEdits) {
        this.products = products.getIfAvailable();
        this.geoTasks = geoTasks.getIfAvailable();
        this.regionEdits = regionEdits.getIfAvailable();
    }

    public CreativeTaskSourceService(ProductIdentityAssetService productAssets, AssetMapper assets,
                                     ConversationMapper conversations,
                                     AuthorizedCatalogContextFactory catalogContexts,
                                     BusinessSpaceService spaces,
                                     ObjectStorage storage, AssetIngestionProperties assetProperties,
                                     PaidTaskProperties taskProperties, ObjectMapper json) {
        this.productAssets = productAssets;
        this.assets = assets;
        this.conversations = conversations;
        this.catalogContexts = catalogContexts;
        this.spaces = spaces;
        this.storage = storage;
        this.assetProperties = assetProperties;
        this.taskProperties = taskProperties;
        this.json = json;
        this.geoPlanProof = new GeoCreativePlanProof(taskProperties, json);
    }

    /** Native creation accepts only Java-frozen sources, never model-selected asset IDs. */
    PreparedSource prepareNative(AgentRunRecord run,long spaceId,String taskType,ObjectNode parameters) {
        return prepareNative(run,spaceId,taskType,parameters,null);
    }

    PreparedSource prepareNative(AgentRunRecord run,long spaceId,String taskType,ObjectNode parameters,Long reviewedImageAssetId) {
        String mode=parameters.path("sourceMode").asText();
        if (reviewedImageAssetId != null && !"PREVIOUS_IMAGE_RESULT".equals(mode)) {
            throw new BusinessException(ErrorCode.CREATIVE_SOURCE_REQUIRED);
        }
        requireCapability(taskType,mode);
        if(run.selectedSpaceId()==null) spaces.requirePersonalCreativeSpace(run.userId(),spaceId);
        else {
            if(!java.util.Objects.equals(run.selectedSpaceId(),spaceId))
                throw new BusinessException(ErrorCode.REFERENCE_ASSET_NOT_OWNED);
            spaces.requireProjectSpace(run.userId(),spaceId);
        }
        List<Long> frozen=parseOptionalIdArray(read(run.attachmentAssetIdsJson()),8);
        if("TEXT_ONLY".equals(mode)) {
            return prepared(mode,run.userId(),run.conversationId(),run.requestMessageId(),spaceId,null,
                    parameters,List.of(),null);
        }
        // A null-project creative Run retains null identity; TaskControl selected its server-owned personal storage.
        if("CURRENT_ATTACHMENTS".equals(mode)) {
            requireAttachmentCount(taskType,frozen.size());
            requireExact(frozen,conversations.findMessageAssetIds(run.requestMessageId(),run.userId(),run.conversationId()));
            return prepared(mode,run.userId(),run.conversationId(),run.requestMessageId(),spaceId,null,
                    parameters,validateAssets(run.userId(),spaceId,frozen),null);
        }
        if("PREVIOUS_IMAGE_RESULT".equals(mode)) {
            // The model may choose this semantic mode, but it must never select an asset ID.
            if(parameters.has("referenceAssetIds")) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            if (reviewedImageAssetId != null) {
                var references = validateAssets(run.userId(),spaceId,List.of(reviewedImageAssetId));
                if (!"GENERATED".equals(references.getFirst().sourceType())
                        || references.getFirst().sourceTaskId() == null) {
                    throw new BusinessException(ErrorCode.CREATIVE_SOURCE_REQUIRED);
                }
                return prepared(mode,run.userId(),run.conversationId(),run.requestMessageId(),spaceId,null,
                        parameters,references,null);
            }
            AssetRecords.Asset previous=assets.findPreviousReadyImageResult(run.userId(),run.conversationId(),spaceId,
                            run.requestMessageId(),run.createdAt())
                    .orElseThrow(()->new BusinessException(ErrorCode.CREATIVE_SOURCE_REQUIRED,
                            "当前会话没有可继续创作的上一张已就绪图片",java.util.Map.of()));
            return prepared(mode,run.userId(),run.conversationId(),run.requestMessageId(),spaceId,null,
                    parameters,validateAssets(run.userId(),spaceId,List.of(previous.id())),null);
        }
        if("PREVIOUS_USER_ATTACHMENT".equals(mode)) {
            // The model chooses only this semantic source mode. Java resolves the immediately
            // preceding USER message and freezes its sole attachment and message provenance.
            if(parameters.has("referenceAssetIds") || parameters.has("referenceMessageId")) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            AssetRecords.PreviousUserAttachment previous = assets.findPreviousUserAttachment(
                            run.userId(), run.conversationId(), run.requestMessageId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.CREATIVE_SOURCE_REQUIRED,
                            "上一条用户消息没有可继续创作的图片，请重新上传图片", java.util.Map.of()));
            if (previous.attachmentCount() != 1 || previous.assetId() == null) {
                throw new BusinessException(ErrorCode.CREATIVE_SOURCE_REQUIRED,
                        "上一条用户消息必须且只能包含一张可继续创作的图片，请重新上传图片", java.util.Map.of());
            }
            return prepared(mode, run.userId(), run.conversationId(), run.requestMessageId(),
                    previous.messageId(), spaceId, null, parameters,
                    validateAssets(run.userId(), spaceId, List.of(previous.assetId())), null);
        }
        if("PRODUCT_MATERIALS".equals(mode) && "VIDEO".equals(taskType) && run.selectedProductId()!=null) {
            requireExact(frozen,conversations.findMessageAssetIds(run.requestMessageId(),run.userId(),run.conversationId()));
            requireProductMaterials(run.userId(),spaceId,run.selectedProductId(),frozen);
            return prepared(mode,run.userId(),run.conversationId(),run.requestMessageId(),spaceId,run.selectedProductId(),
                    parameters,validateAssets(run.userId(),spaceId,frozen),
                    catalogContexts.create(run.userId(),spaceId,run.selectedProductId()));
        }
        if(!"CATALOG_PRODUCT".equals(mode)||run.selectedProductId()==null) throw new BusinessException(ErrorCode.CREATIVE_SOURCE_REQUIRED);
        var identity=productAssets.requireReadyIdentityAssets(run.userId(),run.selectedProductId(),spaceId);
        return prepared(mode,run.userId(),run.conversationId(),run.requestMessageId(),spaceId,run.selectedProductId(),
                parameters,validateAssets(run.userId(),spaceId,selectProductReferences(taskType,identity)),
                catalogContexts.create(run.userId(),spaceId,run.selectedProductId()));
    }

    PreparedSource prepareChat(AgentRunRecord run, String taskType, ObjectNode parameters) {
        requireCapability(taskType);
        if (parameters.has("maskAssetId") || parameters.has("geoReportId")
                || parameters.has("creativePlan")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (parameters.hasNonNull("sourceMode") && !Set.of("CATALOG_PRODUCT", "CURRENT_ATTACHMENTS")
                .contains(parameters.path("sourceMode").asText())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        List<Long> messageAssetIds = parseOptionalIdArray(read(run.attachmentAssetIdsJson()), 8);
        if (!messageAssetIds.isEmpty()) {
            requireAttachmentCount(taskType, messageAssetIds.size());
            List<Long> persisted = conversations.findMessageAssetIds(
                    run.requestMessageId(), run.userId(), run.conversationId());
            requireExact(messageAssetIds, persisted);
            List<AssetRecords.Asset> references = validateAssets(run.userId(), run.selectedSpaceId(), persisted);
            return prepared("CURRENT_ATTACHMENTS", run.userId(), run.conversationId(),
                    run.requestMessageId(), run.selectedSpaceId(), null, parameters,
                    references, null);
        }
        if (run.selectedProductId() == null || run.selectedSpaceId() == null) {
            throw new BusinessException(ErrorCode.CREATIVE_SOURCE_REQUIRED);
        }
        AuthorizedCatalogContextFactory.Context context = catalogContexts.create(
                run.userId(), run.selectedSpaceId(), run.selectedProductId());
        List<ProductIdentityAssetRecord> identity = productAssets.requireReadyIdentityAssets(
                run.userId(), run.selectedProductId(), run.selectedSpaceId());
        List<Long> selected = selectProductReferences(taskType, identity);
        List<AssetRecords.Asset> references = validateAssets(run.userId(), run.selectedSpaceId(), selected);
        return prepared("CATALOG_PRODUCT", run.userId(), run.conversationId(),
                run.requestMessageId(), run.selectedSpaceId(), run.selectedProductId(), parameters,
                references, context);
    }

    PreparedSource prepareForm(long userId, long conversationId, Long sourceMessageId,
                               long spaceId, Long productId, String taskType, ObjectNode parameters) {
        String sourceMode = parameters.path("sourceMode").textValue();
        if (parameters.hasNonNull("sourceMode") && !parameters.path("sourceMode").isTextual()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (sourceMode == null && productId != null) sourceMode = "CATALOG_PRODUCT";
        requireCapability(taskType, sourceMode);
        List<Long> requested = parseOptionalIdArray(parameters.get("referenceAssetIds"), 8);
        if ((!"GEO_REPORT".equals(sourceMode) && (parameters.has("geoReportId")
                || parameters.has("geoRecommendationId") || parameters.has("creativePlan")))
                || !"REGION_EDIT".equals(sourceMode) && parameters.has("maskAssetId")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (Set.of("TEXT_ONLY", "SELECTED_ASSETS", "REGION_EDIT").contains(sourceMode == null ? "" : sourceMode)) {
            if (productId != null || sourceMessageId != null) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            // Validate the selected storage scope without adding its marketing facts to a text/asset-only prompt.
            spaces.requireProjectSpace(userId, spaceId);
            if ("TEXT_ONLY".equals(sourceMode)) {
                if (!requested.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
                return prepared(sourceMode, userId, conversationId, null, spaceId, null,
                        parameters, List.of(), null);
            }
            if ("REGION_EDIT".equals(sourceMode)) {
                JsonNode instruction = parameters.path("requestText");
                if (!"IMAGE".equals(taskType) || requested.size() != 1
                        || !instruction.isTextual() || instruction.textValue().isBlank()
                        || instruction.textValue().length() > 800) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED);
                }
                List<AssetRecords.Asset> references = validateAssets(userId, spaceId, requested);
                validateRegionInputs(userId, spaceId, references.getFirst(), parameters.get("maskAssetId"));
                // The provider consumes scene; freeze the exact instruction the user confirmed,
                // not an optional or independently editable secondary scene field.
                ObjectNode normalized = parameters.deepCopy();
                normalized.put("scene", instruction.textValue());
                return prepared(sourceMode, userId, conversationId, null, spaceId, null,
                        normalized, references, null);
            }
            requireAttachmentCount(taskType, requested.size());
            return prepared(sourceMode, userId, conversationId, null, spaceId, null,
                    parameters, validateAssets(userId, spaceId, requested), null);
        }
        if ("CURRENT_ATTACHMENTS".equals(sourceMode)) {
            if (productId != null || sourceMessageId == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            spaces.requireProjectSpace(userId, spaceId);
            conversations.findMessage(sourceMessageId, userId, conversationId)
                    .filter(message -> "USER".equals(message.role()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.REFERENCE_ASSET_NOT_OWNED));
            List<Long> persisted = conversations.findMessageAssetIds(sourceMessageId, userId, conversationId);
            requireAttachmentCount(taskType, persisted.size());
            requireExact(requested, persisted);
            return prepared(sourceMode, userId, conversationId, sourceMessageId, spaceId, null,
                    parameters, validateAssets(userId, spaceId, persisted), null);
        }
        if (!Set.of("CATALOG_PRODUCT", "PRODUCT_MATERIALS", "GEO_REPORT")
                .contains(sourceMode == null ? "" : sourceMode) || productId == null) {
            throw new BusinessException(ErrorCode.CREATIVE_SOURCE_REQUIRED);
        }
        if (!"CATALOG_PRODUCT".equals(sourceMode) && (sourceMessageId != null
                || "PRODUCT_MATERIALS".equals(sourceMode) && !"VIDEO".equals(taskType))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        AuthorizedCatalogContextFactory.Context context = catalogContexts.create(userId, spaceId, productId);
        if ("PRODUCT_MATERIALS".equals(sourceMode)) {
            requireProductMaterials(userId, spaceId, productId, requested);
            return prepared(sourceMode, userId, conversationId, null, spaceId, productId,
                    parameters, validateAssets(userId, spaceId, requested), context);
        }
        GeoTaskRecords.Report report = "GEO_REPORT".equals(sourceMode)
                ? requireGeoReport(userId, spaceId, productId, parameters.get("geoReportId")) : null;
        if (report != null) {
            selectedGeoRecommendation(report, parameters.get("geoRecommendationId"));
            requireCreativePlan(report, taskType, parameters, userId, spaceId, productId,
                    context.product());
        }
        List<ProductIdentityAssetRecord> identity = productAssets.requireReadyIdentityAssets(userId, productId, spaceId);
        List<Long> expected = selectProductReferences(taskType, identity);
        if (requested.isEmpty()) requested = expected;
        requireExact(requested, expected);
        return prepared(sourceMode, userId, conversationId, sourceMessageId, spaceId, productId,
                parameters, validateAssets(userId, spaceId, expected), context);
    }

    void revalidate(TaskRecords.Draft draft) {
        if (!isCreative(draft.taskType())) return;
        JsonNode snapshot = read(draft.inputSnapshotJson());
        JsonNode brief = snapshot.path("productCreativeBrief");
        String sourceMode = brief.path("sourceMode").textValue();
        List<Long> frozenIds = "TEXT_ONLY".equals(sourceMode)
                ? parseOptionalIdArray(brief.get("referenceAssetIds"), 3)
                : parseIdArray(brief.path("referenceAssetIds"), 3);
        Long messageId = optionalId(brief.path("provenance").get("messageId"));
        Long referenceMessageId = optionalId(brief.path("provenance").get("referenceMessageId"));
        if (Set.of("TEXT_ONLY", "SELECTED_ASSETS", "REGION_EDIT").contains(sourceMode == null ? "" : sourceMode)) {
            boolean formSource = "FORM".equals(draft.draftSource())
                    && draft.productId() == null && messageId == null;
            boolean nativeTextOnly = "TEXT_ONLY".equals(sourceMode)
                    && isNativeCreativeDraft(draft, snapshot, messageId);
            if (!formSource && !nativeTextOnly) {
                throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
            }
            if (formSource) spaces.requireProjectSpace(draft.userId(), draft.spaceId());
            else requireNativeCreativeStorage(draft, snapshot);
            if ("TEXT_ONLY".equals(sourceMode)) {
                if (!frozenIds.isEmpty()) throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
            } else if ("REGION_EDIT".equals(sourceMode)) {
                JsonNode parameters = snapshot.path("parameters");
                JsonNode instruction = parameters.path("requestText");
                if (!"IMAGE".equals(draft.taskType()) || frozenIds.size() != 1
                        || !instruction.isTextual() || instruction.textValue().isBlank()
                        || instruction.textValue().length() > 800
                        || !instruction.equals(parameters.path("scene"))
                        || !instruction.equals(brief.path("creativeDirection").path("scene"))) {
                    throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
                }
                validateRegionInputs(draft.userId(), draft.spaceId(),
                        validateAssets(draft.userId(), draft.spaceId(), frozenIds).getFirst(),
                        snapshot.path("parameters").get("maskAssetId"));
            } else requireAttachmentCount(draft.taskType(), frozenIds.size());
        } else if ("PREVIOUS_IMAGE_RESULT".equals(sourceMode)) {
            if (draft.productId() != null || draft.conversationId() == null || messageId == null
                    || frozenIds.size() != 1 || !isNativeCreativeDraft(draft, snapshot, messageId)) {
                throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
            }
            // "Previous" was resolved once at admission. Confirmation only revalidates the
            // frozen immutable reference, never re-runs a latest-result query.
            requireNativeCreativeStorage(draft, snapshot);
        } else if ("PREVIOUS_USER_ATTACHMENT".equals(sourceMode)) {
            if (draft.productId() != null || draft.conversationId() == null || messageId == null
                    || referenceMessageId == null || frozenIds.size() != 1
                    || !isNativeCreativeDraft(draft, snapshot, messageId)) {
                throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
            }
            requireNativeCreativeStorage(draft, snapshot);
            var requestMessage = conversations.findMessage(messageId, draft.userId(), draft.conversationId())
                    .filter(message -> "USER".equals(message.role()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH));
            var referenceMessage = conversations.findMessage(
                            referenceMessageId, draft.userId(), draft.conversationId())
                    .filter(message -> "USER".equals(message.role())
                            && message.sequenceNo() < requestMessage.sequenceNo())
                    .orElseThrow(() -> new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH));
            List<Long> frozenMessageAssets = conversations.findMessageAssetIds(
                    referenceMessage.id(), draft.userId(), draft.conversationId());
            requireExact(frozenIds, frozenMessageAssets);
        } else if ("CURRENT_ATTACHMENTS".equals(sourceMode)) {
            if (draft.productId() != null || draft.conversationId() == null || messageId == null) {
                throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
            }
            if ("FORM".equals(draft.draftSource())) spaces.requireProjectSpace(draft.userId(), draft.spaceId());
            else if (isNativeCreativeDraft(draft, snapshot, messageId))
                requireNativeCreativeStorage(draft, snapshot);
            else if (isOrdinaryChatCreativeDraft(draft, snapshot, messageId))
                spaces.requireProjectSpace(draft.userId(), draft.spaceId());
            else throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
            List<Long> current = conversations.findMessageAssetIds(
                    messageId, draft.userId(), draft.conversationId());
            requireAttachmentCount(draft.taskType(), current.size());
            requireExact(frozenIds, current);
        } else if ("PRODUCT_MATERIALS".equals(sourceMode) && draft.productId() != null) {
            if (!"FORM".equals(draft.draftSource()) || !"VIDEO".equals(draft.taskType()) || messageId != null) {
                throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
            }
            catalogContexts.create(draft.userId(), draft.spaceId(), draft.productId());
            requireProductMaterials(draft.userId(), draft.spaceId(), draft.productId(), frozenIds);
        } else if (Set.of("CATALOG_PRODUCT", "GEO_REPORT").contains(sourceMode == null ? "" : sourceMode)
                && draft.productId() != null) {
            if ("GEO_REPORT".equals(sourceMode)) {
                if (!"FORM".equals(draft.draftSource()) || messageId != null) {
                    throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
                }
                GeoTaskRecords.Report report = requireGeoReport(draft.userId(), draft.spaceId(), draft.productId(),
                        snapshot.path("parameters").get("geoReportId"));
                selectedGeoRecommendation(report, snapshot.path("parameters").get("geoRecommendationId"));
                InternalRunContextDtos.Product currentProduct = catalogContexts.create(
                        draft.userId(), draft.spaceId(), draft.productId()).product();
                ObjectNode plan = requireCreativePlan(report, draft.taskType(), snapshot.path("parameters"),
                        draft.userId(), draft.spaceId(), draft.productId(), currentProduct);
                if (!plan.equals(brief.path("creativeDirection").path("creativePlan"))) {
                    throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
                }
            }
            List<Long> current = selectProductReferences(draft.taskType(),
                    productAssets.requireReadyIdentityAssets(
                            draft.userId(), draft.productId(), draft.spaceId()));
            requireExact(frozenIds, current);
        } else {
            throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
        }
        if (!frozenIds.isEmpty()) validateAssets(draft.userId(), draft.spaceId(), frozenIds);
        requireCapability(draft.taskType(), sourceMode);
    }

    private boolean isNativeCreativeDraft(TaskRecords.Draft draft, JsonNode snapshot, Long messageId) {
        if (!"CHAT".equals(draft.draftSource()) || draft.agentRunId() == null
                || draft.conversationId() == null || draft.productId() != null || messageId == null) return false;
        JsonNode nativeCreative = snapshot.path("nativeCreative");
        return draft.agentRunId().toString().equals(nativeCreative.path("runId").asText())
                && Long.toString(messageId).equals(nativeCreative.path("sourceMessageId").asText());
    }

    private boolean isOrdinaryChatCreativeDraft(TaskRecords.Draft draft, JsonNode snapshot, Long messageId) {
        return "CHAT".equals(draft.draftSource()) && draft.agentRunId() != null
                && draft.conversationId() != null && draft.productId() == null && messageId != null
                && !snapshot.has("nativeCreative");
    }

    private void requireNativeCreativeStorage(TaskRecords.Draft draft, JsonNode snapshot) {
        String storageKind = snapshot.path("nativeCreative").path("storageKind").asText("");
        if ("PROJECT".equals(storageKind)) spaces.requireProjectSpace(draft.userId(), draft.spaceId());
        else if ("PERSONAL_CREATIVE".equals(storageKind))
            spaces.requirePersonalCreativeSpace(draft.userId(), draft.spaceId());
        else throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
    }

    List<TaskDtos.ExecutionContextResponse.ControlledReferenceAsset> controlledReferences(
            TaskRecords.Task task, JsonNode snapshot) {
        if (!isCreative(task.taskType())) return List.of();
        if ("TEXT_ONLY".equals(snapshot.path("parameters").path("sourceMode").asText())) return List.of();
        List<Long> ids = parseIdArray(snapshot.path("productCreativeBrief").path("referenceAssetIds"), 20);
        List<AssetRecords.Asset> references = validateAssets(task.userId(), task.spaceId(), ids);
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC)
                .plus(assetProperties.getReferenceReadUrlTtl());
        ArrayList<TaskDtos.ExecutionContextResponse.ControlledReferenceAsset> result = new ArrayList<>();
        for (AssetRecords.Asset asset : references) {
            if (asset.width() == null || asset.height() == null) {
                throw new BusinessException(ErrorCode.REFERENCE_ASSET_NOT_READY);
            }
            URI url = storage.signedGetUrl(asset.objectKey(), expiresAt);
            if (!"https".equalsIgnoreCase(url.getScheme())) {
                throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
            }
            result.add(new TaskDtos.ExecutionContextResponse.ControlledReferenceAsset(
                    Long.toString(asset.id()), asset.contentType(), asset.sizeBytes(), asset.sha256(),
                    asset.width(), asset.height(), url.toString(), expiresAt));
        }
        return List.copyOf(result);
    }

    TaskDtos.ExecutionContextResponse.ControlledReferenceAsset controlledMask(TaskRecords.Task task, JsonNode snapshot) {
        if (!"REGION_EDIT".equals(snapshot.path("parameters").path("sourceMode").asText())) return null;
        Long id = optionalId(snapshot.path("parameters").get("maskAssetId"));
        if (id == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        AssetRecords.Asset mask = validateAssets(task.userId(), task.spaceId(), List.of(id)).getFirst();
        OffsetDateTime expiry = OffsetDateTime.now(ZoneOffset.UTC).plus(assetProperties.getReferenceReadUrlTtl());
        URI url = storage.signedGetUrl(mask.objectKey(), expiry);
        if (!"https".equalsIgnoreCase(url.getScheme())) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        return new TaskDtos.ExecutionContextResponse.ControlledReferenceAsset(Long.toString(id), mask.contentType(),
                mask.sizeBytes(), mask.sha256(), mask.width(), mask.height(), url.toString(), expiry);
    }

    private void validateRegionInputs(long userId, long spaceId, AssetRecords.Asset base, JsonNode maskId) {
        Long id = optionalId(maskId);
        if (id == null || id == base.id()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        AssetRecords.Asset mask = validateAssets(userId, spaceId, List.of(id)).getFirst();
        if (regionEdits == null) throw new BusinessException(ErrorCode.REFERENCE_IMAGE_MODE_UNAVAILABLE);
        regionEdits.validateInputs(base, mask);
    }

    private void requireProductMaterials(long userId, long spaceId, long productId, List<Long> requested) {
        if (requested.isEmpty() || requested.size() > 3) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        if (products == null) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        Set<Long> bound = new HashSet<>();
        products.findIdentityAssets(productId, userId, spaceId).forEach(item -> bound.add(item.assetId()));
        if (!bound.containsAll(requested)) throw new BusinessException(ErrorCode.REFERENCE_ASSET_NOT_OWNED);
    }

    private GeoTaskRecords.Report requireGeoReport(long userId, long spaceId, long productId, JsonNode reportId) {
        Long id = optionalId(reportId);
        if (id == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        if (geoTasks == null) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        GeoTaskRecords.Report report = geoTasks.findReport(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        GeoTaskRecords.Run run = geoTasks.findRun(report.runId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!Set.of("SUCCEEDED", "PARTIAL").contains(report.status())
                || !Set.of("SUCCEEDED", "PARTIAL").contains(run.status())
                || report.sampleSize() < 1
                || report.sampleSize() > 210 || report.archivedAt() != null
                || !"geo-synthesis-v1".equals(report.methodVersion())
                || run.succeededUnits() < 1
                || run.spaceId() != spaceId || !Long.valueOf(productId).equals(run.productId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "请选择当前商品在当前经营空间下已完成的 GEO 报告", java.util.Map.of());
        }
        return report;
    }

    private JsonNode selectedGeoRecommendation(GeoTaskRecords.Report report, JsonNode requested) {
        if (requested == null || requested.isNull()) return null;
        if (!requested.isTextual()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        String prefix = report.id() + ":recommendation:";
        String value = requested.textValue();
        if (!value.startsWith(prefix)) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        try {
            int ordinal = Integer.parseInt(value.substring(prefix.length()));
            JsonNode recommendations = read(report.synthesisJson()).path("recommendations");
            if (ordinal > 0 && recommendations.isArray() && ordinal <= recommendations.size()) {
                JsonNode selected = recommendations.path(ordinal - 1);
                if (selected.isObject()) return selected;
            }
        } catch (NumberFormatException ignored) {
            // Unknown recommendation IDs are rejected before any paid draft exists.
        }
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private ObjectNode requireCreativePlan(GeoTaskRecords.Report report, String taskType, JsonNode parameters,
                                           long userId, long spaceId, long productId,
                                           InternalRunContextDtos.Product currentProduct) {
        JsonNode plan = parameters.path("creativePlan");
        JsonNode keys = plan.path("evidenceKeys");
        if (geoTasks == null || !keys.isArray() || keys.isEmpty() || keys.size() > 8) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Set<String> successful = new HashSet<>();
        for (JsonNode key : keys) {
            if (!key.isTextual() || key.textValue().isBlank() || key.textValue().length() > 80) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            GeoTaskRecords.Observation observation = geoTasks.findObservationByUnit(report.runId(), key.textValue())
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
            if (observation.userId() != report.userId() || !"SUCCEEDED".equals(observation.status())
                    || observation.answerText() == null || observation.answerText().isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            successful.add(key.textValue());
        }
        String recommendationId = parameters.path("geoRecommendationId").textValue();
        ObjectNode checked = GeoCreativePlanValidator.require(plan, taskType, report.id(), recommendationId,
                parameters.path("scene").textValue(), successful);
        geoPlanProof.verify(checked, userId, spaceId, productId, currentProduct);
        return checked;
    }

    private PreparedSource prepared(String sourceMode, long userId, long conversationId, Long messageId,
                                    long spaceId, Long productId, ObjectNode rawParameters,
                                    List<AssetRecords.Asset> references,
                                    AuthorizedCatalogContextFactory.Context catalog) {
        return prepared(sourceMode, userId, conversationId, messageId, null, spaceId, productId,
                rawParameters, references, catalog);
    }

    private PreparedSource prepared(String sourceMode, long userId, long conversationId, Long messageId,
                                    Long referenceMessageId, long spaceId, Long productId,
                                    ObjectNode rawParameters, List<AssetRecords.Asset> references,
                                    AuthorizedCatalogContextFactory.Context catalog) {
        ObjectNode parameters = rawParameters.deepCopy();
        parameters.put("sourceMode", sourceMode);
        ArrayNode referenceIds = parameters.putArray("referenceAssetIds");
        references.forEach(asset -> referenceIds.add(Long.toString(asset.id())));

        ObjectNode authorized = json.createObjectNode();
        if (catalog == null) {
            authorized.putNull("space");
            authorized.putNull("product");
            authorized.set("competitors", json.createArrayNode());
        } else {
            authorized.set("space", json.valueToTree(catalog.space()));
            authorized.set("product", json.valueToTree(catalog.product()));
            authorized.set("competitors", json.valueToTree(catalog.competitors()));
        }
        authorized.set("attachmentAssetIds", "CURRENT_ATTACHMENTS".equals(sourceMode)
                ? referenceIds.deepCopy() : json.createArrayNode());

        ObjectNode brief = json.createObjectNode();
        brief.put("sourceMode", sourceMode);
        if (productId == null) brief.putNull("productId");
        else brief.put("productId", Long.toString(productId));
        brief.set("referenceAssetIds", referenceIds.deepCopy());
        InternalRunContextDtos.Product product = catalog == null ? null : catalog.product();
        if (product == null) brief.putNull("productFacts");
        else {
            ObjectNode facts = json.createObjectNode();
            facts.put("name", product.name());
            if (product.category() == null) facts.putNull("category"); else facts.put("category", product.category());
            if (product.modelSpec() == null) facts.putNull("modelSpec"); else facts.put("modelSpec", product.modelSpec());
            facts.set("sellingPoints", json.valueToTree(product.sellingPoints()));
            facts.set("parameters", json.valueToTree(product.parameters()));
            facts.set("audience", json.valueToTree(product.audience()));
            facts.set("usageScenarios", json.valueToTree(product.usageScenarios()));
            brief.set("productFacts", facts);
        }
        ObjectNode direction = parameters.deepCopy();
        direction.remove(List.of("sourceMode", "referenceAssetIds", "mode", "agentSummary"));
        brief.set("creativeDirection", direction);
        brief.set("prohibitedClaims", product == null
                ? json.createArrayNode() : json.valueToTree(product.prohibitedClaims()));
        ObjectNode provenance = brief.putObject("provenance");
        provenance.put("conversationId", Long.toString(conversationId));
        if (messageId == null) provenance.putNull("messageId");
        else provenance.put("messageId", Long.toString(messageId));
        if (referenceMessageId != null) {
            provenance.put("referenceMessageId", Long.toString(referenceMessageId));
        }
        return new PreparedSource(spaceId, productId, messageId, parameters, authorized, brief);
    }

    private List<AssetRecords.Asset> validateAssets(long userId, Long spaceId, List<Long> ids) {
        if (ids.isEmpty()) throw new BusinessException(ErrorCode.CREATIVE_SOURCE_REQUIRED);
        ArrayList<AssetRecords.Asset> result = new ArrayList<>();
        for (long id : ids) {
            AssetRecords.Asset asset = assets.findAsset(id, userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.REFERENCE_ASSET_NOT_OWNED));
            if (spaceId == null || asset.spaceId() != spaceId || !"IMAGE".equals(asset.assetType())) {
                throw new BusinessException(ErrorCode.REFERENCE_ASSET_NOT_OWNED);
            }
            if (!"READY".equals(asset.status()) || !downloadAllowed(asset.safetyStatus())) {
                throw new BusinessException(ErrorCode.REFERENCE_ASSET_NOT_READY);
            }
            result.add(asset);
        }
        return List.copyOf(result);
    }

    private List<Long> parseIdArray(JsonNode value, int max) {
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > max) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        HashSet<Long> unique = new HashSet<>();
        ArrayList<Long> result = new ArrayList<>();
        for (JsonNode item : value) {
            Long id = optionalId(item);
            if (id == null || !unique.add(id)) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            result.add(id);
        }
        return List.copyOf(result);
    }

    private List<Long> parseOptionalIdArray(JsonNode value, int max) {
        if (value == null || value.isNull() || (value.isArray() && value.isEmpty())) return List.of();
        return parseIdArray(value, max);
    }

    private Long optionalId(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || !value.textValue().matches("^[1-9][0-9]{0,18}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            return Long.parseLong(value.textValue());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private void requireExact(List<Long> submitted, List<Long> authoritative) {
        if (!submitted.equals(authoritative)) {
            throw new BusinessException(ErrorCode.TASK_CONFIRMATION_MISMATCH);
        }
    }

    private List<Long> selectProductReferences(String taskType,
                                                List<ProductIdentityAssetRecord> identity) {
        int limit = "VIDEO".equals(taskType) ? 1 : 3;
        ProductIdentityAssetRecord preferred = identity.stream()
                .filter(item -> "PRIMARY".equals(item.role()))
                .findFirst()
                .orElseGet(() -> identity.stream()
                        .filter(item -> "WHITE_BACKGROUND".equals(item.role()))
                        .findFirst()
                        .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_IDENTITY_ASSET_REQUIRED)));
        ArrayList<Long> result = new ArrayList<>();
        result.add(preferred.assetId());
        if (result.size() < limit) {
            identity.stream().filter(item -> "WHITE_BACKGROUND".equals(item.role()))
                    .map(ProductIdentityAssetRecord::assetId)
                    .filter(id -> !result.contains(id))
                    .findFirst().ifPresent(result::add);
        }
        for (ProductIdentityAssetRecord item : identity) {
            if (result.size() >= limit) break;
            if (!result.contains(item.assetId())) result.add(item.assetId());
        }
        return List.copyOf(result);
    }

    private void requireAttachmentCount(String taskType, int count) {
        int maximum = "VIDEO".equals(taskType) ? 1 : 3;
        if (count < 1 || count > maximum) {
            String message = "VIDEO".equals(taskType)
                    ? "视频创作需要且只能选择 1 张本次附件作为首帧"
                    : "图片创作最多可使用 3 张本次附件，请减少后重试";
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, message,
                    java.util.Map.of("field", "referenceAssetIds", "minimum", 1, "maximum", maximum));
        }
    }

    private void requireCapability(String taskType) {
        requireCapability(taskType, "CATALOG_PRODUCT");
    }

    private void requireCapability(String taskType, String sourceMode) {
        if ("TEXT_ONLY".equals(sourceMode)) return;
        if ("REGION_EDIT".equals(sourceMode)) {
            if (!"IMAGE".equals(taskType) || !taskProperties.isImageRegionEditEnabled()) {
                throw new BusinessException(ErrorCode.REFERENCE_IMAGE_MODE_UNAVAILABLE);
            }
            return;
        }
        if ("IMAGE".equals(taskType) && !taskProperties.isReferenceImageGenerationEnabled()) {
            throw new BusinessException(ErrorCode.REFERENCE_IMAGE_MODE_UNAVAILABLE);
        }
        if ("VIDEO".equals(taskType) && !taskProperties.isImageToVideoEnabled()) {
            throw new BusinessException(ErrorCode.IMAGE_TO_VIDEO_MODE_UNAVAILABLE);
        }
    }

    private boolean downloadAllowed(String status) {
        return assetProperties.allowsAssetUse(status);
    }

    private boolean isCreative(String taskType) {
        return "IMAGE".equals(taskType) || "VIDEO".equals(taskType);
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read creative source JSON", exception);
        }
    }

    record PreparedSource(long spaceId, Long productId, Long sourceMessageId,
                          ObjectNode parameters, ObjectNode authorizedContext,
                          ObjectNode productCreativeBrief) { }
}
