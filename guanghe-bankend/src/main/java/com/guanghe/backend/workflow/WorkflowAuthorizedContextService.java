package com.guanghe.backend.workflow;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.conversation.context.InternalRunContextDtos;
import com.guanghe.backend.agentplatform.AgentRuntimeCatalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/** Freezes and verifies the safe, immutable context owned by a workflow plan. */
@Service
public class WorkflowAuthorizedContextService {
    static final int SCHEMA_VERSION = 1;
    static final int MAX_AUTHORIZED_CONTEXT_BYTES = 196_608;
    static final int MAX_EXECUTION_CONTEXT_BYTES = 262_144;
    private static final int JSONB_TEXT_SAFETY_MARGIN_BYTES = 4_096;
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "userid", "permissions", "jwt", "authorization", "password", "secret",
            "signature", "contextref", "confirmationtoken", "executiontoken", "providertaskid",
            "providerrequestid", "providerurl", "objectkey", "accesskey", "accesskeyid"
    );
    private static final Pattern JWT = Pattern.compile("(?i)eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~-]{16,}");
    private static final Pattern SIGNED_URL = Pattern.compile("(?i)https://\\S+\\?\\S+");

    private final ObjectMapper json;
    private final ObjectReader reader;
    private final ObservabilitySupport observability;
    private AgentRuntimeCatalog runtimeCatalog = new AgentRuntimeCatalog();

    @Autowired
    void setRuntimeCatalog(AgentRuntimeCatalog value) {
        runtimeCatalog = java.util.Objects.requireNonNull(value);
    }

    @Autowired
    public WorkflowAuthorizedContextService(ObjectMapper json, ObservabilitySupport observability) {
        this.json = json;
        this.observability = observability;
        ObjectMapper strict = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.reader = strict.readerFor(InternalRunContextDtos.RunContext.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    WorkflowAuthorizedContextService(ObjectMapper json) {
        this(json, ObservabilitySupport.noop());
    }

    public FrozenContext freeze(InternalRunContextDtos.RunContext source, UUID runId,
                                long conversationId, long spaceId, Long productId) {
        return observability.observe("workflow.context.freeze", "FREEZE", span -> {
            span.tag("runIdRef", observability.reference(runId));
            span.tag("conversationIdRef", observability.reference(conversationId));
            span.tag("contextSource", "PLAN_SNAPSHOT");
        }, () -> freezeInternal(source, runId, conversationId, spaceId, productId));
    }

    public InternalRunContextDtos.RunContext load(String snapshotJson, String expectedHash,
                                                   Integer schemaVersion, boolean dataGap) {
        return observability.observe("workflow.context.load", "LOAD", span -> {
            span.tag("contextSource", "PLAN_SNAPSHOT");
            span.tag("dataGap", dataGap);
        }, () -> loadInternal(snapshotJson, expectedHash, schemaVersion, dataGap));
    }

    public void requireExecutable(String snapshotJson, String expectedHash,
                                  Integer schemaVersion, boolean dataGap) {
        load(snapshotJson, expectedHash, schemaVersion, dataGap);
    }

    public void requireExecutionSize(JsonNode executionContext) {
        if (projectedJsonbTextBytes(executionContext) > MAX_EXECUTION_CONTEXT_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private FrozenContext freezeInternal(InternalRunContextDtos.RunContext source, UUID runId,
                                         long conversationId, long spaceId, Long productId) {
        validateBinding(source, runId, conversationId, spaceId, productId);
        validateRuntimeIdentity(source);
        InternalRunContextDtos.RunContext candidate = sanitize(source);
        boolean trimmed = false;
        while (projectedJsonbTextBytes(json.valueToTree(candidate)) > MAX_AUTHORIZED_CONTEXT_BYTES) {
            InternalRunContextDtos.RunContext reduced = trimOne(candidate);
            if (reduced == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            candidate = reduced;
            trimmed = true;
        }
        if (trimmed) {
            InternalRunContextDtos.RunContext finalCandidate = candidate;
            observability.observe("workflow.context.trim", "TRIM", span -> {
                span.tag("contextSource", "PLAN_SNAPSHOT");
                span.tag("contextItems", finalCandidate.conversationMemory().messages().size()
                        + finalCandidate.competitors().size());
                span.tag("estimatedTokens", finalCandidate.contextBudget().estimatedHistoryTokens());
                span.tag("contextBytes", projectedJsonbTextBytes(json.valueToTree(finalCandidate)));
            }, () -> { });
        }
        String canonical = canonical(json.valueToTree(candidate));
        int projectedBytes = projectedJsonbTextBytes(json.valueToTree(candidate));
        return new FrozenContext(canonical, sha256(canonical), SCHEMA_VERSION, projectedBytes);
    }

    private InternalRunContextDtos.RunContext loadInternal(String snapshotJson, String expectedHash,
                                                           Integer schemaVersion, boolean dataGap) {
        if (dataGap || snapshotJson == null || expectedHash == null || schemaVersion == null
                || schemaVersion != SCHEMA_VERSION || !expectedHash.matches("^[0-9a-f]{64}$")) {
            throw new BusinessException(ErrorCode.WORKFLOW_CONTEXT_UNAVAILABLE);
        }
        try {
            InternalRunContextDtos.RunContext context = reader.readValue(snapshotJson);
            String canonical = canonical(json.valueToTree(context));
            if (!constantEquals(expectedHash, sha256(canonical))
                    || projectedJsonbTextBytes(json.valueToTree(context)) > MAX_AUTHORIZED_CONTEXT_BYTES) {
                throw new BusinessException(ErrorCode.WORKFLOW_CONTEXT_UNAVAILABLE);
            }
            validateRuntimeIdentity(context);
            return context;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.WORKFLOW_CONTEXT_UNAVAILABLE);
        }
    }

    private void validateRuntimeIdentity(InternalRunContextDtos.RunContext context) {
        // Old immutable workflow snapshots predate agent bindings. New proposals are
        // admitted by WorkflowPlanService with a frozen identity before this boundary.
        if (context.agentIdentity() != null) runtimeCatalog.validateFrozen(context.agentIdentity());
    }

    private void validateBinding(InternalRunContextDtos.RunContext context, UUID runId,
                                 long conversationId, long spaceId, Long productId) {
        String expectedProduct = productId == null ? null : Long.toString(productId);
        if (context == null || context.contextVersion() != SCHEMA_VERSION
                || !runId.toString().equals(context.runId())
                || !Long.toString(conversationId).equals(context.conversationId())
                || context.space() == null || !Long.toString(spaceId).equals(context.space().id())
                || (productId == null) != (context.product() == null)
                || (context.product() != null && (!expectedProduct.equals(context.product().id())
                    || !Long.toString(spaceId).equals(context.product().spaceId())))
                || context.taskWorkspace() == null || context.taskWorkspace().activeScope() == null
                || !Long.toString(spaceId).equals(context.taskWorkspace().activeScope().spaceId())
                || !java.util.Objects.equals(expectedProduct,
                    context.taskWorkspace().activeScope().productId())
                || !List.of("TASK_PREPARATION").equals(context.loadedToolGroups())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        List<String> competitors = context.competitors().stream()
                .map(InternalRunContextDtos.Competitor::id).toList();
        if (!competitors.equals(context.taskWorkspace().activeScope().competitorIds())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private InternalRunContextDtos.RunContext sanitize(InternalRunContextDtos.RunContext source) {
        try {
            JsonNode sanitized = sanitizeNode(json.valueToTree(source));
            return reader.readValue(json.writeValueAsBytes(sanitized));
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private JsonNode sanitizeNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode();
            node.properties().forEach(field -> {
                String normalized = field.getKey().replace("_", "").replace("-", "")
                        .toLowerCase(Locale.ROOT);
                if (!FORBIDDEN_FIELDS.contains(normalized)) {
                    result.set(field.getKey(), sanitizeNode(field.getValue()));
                }
            });
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode();
            node.forEach(value -> result.add(sanitizeNode(value)));
            return result;
        }
        if (node.isTextual()) {
            String value = node.textValue();
            if (JWT.matcher(value).find() || BEARER.matcher(value).find() || SIGNED_URL.matcher(value).find()) {
                return TextNode.valueOf("[REDACTED]");
            }
        }
        return node.deepCopy();
    }

    private InternalRunContextDtos.RunContext trimOne(InternalRunContextDtos.RunContext context) {
        List<InternalRunContextDtos.MemoryMessage> messages = new ArrayList<>(
                context.conversationMemory().messages());
        int removable = removableMessage(messages);
        if (removable >= 0) {
            messages.remove(removable);
            return rebuild(context, messages, context.space(), context.product(), context.competitors(), true);
        }

        List<InternalRunContextDtos.Competitor> competitors = new ArrayList<>(context.competitors());
        for (int index = competitors.size() - 1; index >= 0; index--) {
            InternalRunContextDtos.Competitor competitor = competitors.get(index);
            if (!competitor.aliases().isEmpty()) {
                List<String> aliases = new ArrayList<>(competitor.aliases());
                aliases.removeLast();
                competitors.set(index, new InternalRunContextDtos.Competitor(
                        competitor.id(), competitor.spaceId(), competitor.name(), aliases,
                        competitor.type(), competitor.description(), competitor.status()));
                return rebuild(context, messages, context.space(), context.product(), competitors, true);
            }
        }
        if (competitors.size() > 1) {
            competitors.removeLast();
            return rebuild(context, messages, context.space(), context.product(), competitors, true);
        }
        if (context.product() != null && !context.product().aliases().isEmpty()) {
            var product = context.product();
            List<String> aliases = new ArrayList<>(product.aliases());
            aliases.removeLast();
            var trimmedProduct = new InternalRunContextDtos.Product(product.id(), product.spaceId(),
                    product.name(), aliases, product.category(), product.modelSpec(), product.sellingPoints(),
                    product.parameters(), product.audience(), product.usageScenarios(),
                    product.prohibitedClaims(), product.primaryAssetId(), product.identityAssetIds());
            return rebuild(context, messages, context.space(), trimmedProduct, competitors, true);
        }
        if (context.space() != null && !context.space().brand().aliases().isEmpty()) {
            var space = context.space();
            var brand = space.brand();
            List<String> aliases = new ArrayList<>(brand.aliases());
            aliases.removeLast();
            var trimmedBrand = new InternalRunContextDtos.Brand(brand.name(), aliases,
                    brand.description(), brand.tone(), brand.audience(), brand.sellingPoints(),
                    brand.prohibitedClaims());
            var trimmedSpace = new InternalRunContextDtos.Space(space.id(), space.name(), trimmedBrand,
                    space.logoAssetId());
            return rebuild(context, messages, trimmedSpace, context.product(), competitors, true);
        }
        return null;
    }

    private int removableMessage(List<InternalRunContextDtos.MemoryMessage> messages) {
        if (messages.size() <= 1) return -1;
        String currentId = messages.getLast().messageId();
        Map<String, InternalRunContextDtos.MemoryMessage> byId = new HashMap<>();
        messages.forEach(message -> byId.put(message.messageId(), message));
        Set<String> replyChain = new HashSet<>();
        String cursor = currentId;
        while (cursor != null && replyChain.add(cursor)) {
            InternalRunContextDtos.MemoryMessage message = byId.get(cursor);
            cursor = message == null ? null : message.replyToMessageId();
        }
        for (int index = 0; index < messages.size(); index++) {
            if (!replyChain.contains(messages.get(index).messageId())) return index;
        }
        for (int index = 0; index < messages.size() - 1; index++) return index;
        return -1;
    }

    private InternalRunContextDtos.RunContext rebuild(
            InternalRunContextDtos.RunContext source,
            List<InternalRunContextDtos.MemoryMessage> messages,
            InternalRunContextDtos.Space space,
            InternalRunContextDtos.Product product,
            List<InternalRunContextDtos.Competitor> competitors,
            boolean truncated
    ) {
        var memory = source.conversationMemory();
        var budget = source.contextBudget();
        var workspace = source.taskWorkspace();
        List<String> competitorIds = competitors.stream().map(InternalRunContextDtos.Competitor::id).toList();
        var activeScope = new InternalRunContextDtos.ActiveScope(
                workspace.activeScope().spaceId(), workspace.activeScope().productId(), competitorIds);
        var rebuiltWorkspace = new InternalRunContextDtos.TaskWorkspace(
                workspace.currentGoal(), workspace.currentIntent(), activeScope,
                workspace.confirmedConstraints(), workspace.unresolvedQuestions(),
                workspace.pendingInteraction(), workspace.activeDraftId(), workspace.activeTaskId(),
                workspace.referenceAssetIds(), workspace.runningTasks(), workspace.contextVersion());
        var rebuiltMemory = new InternalRunContextDtos.ConversationMemory(
                List.copyOf(messages), memory.summary(), memory.lastMessageSequence(),
                memory.summaryThroughSequence(), memory.contextSource());
        var rebuiltBudget = new InternalRunContextDtos.ContextBudget(
                budget.targetInputTokens(), budget.reservedOutputTokens(), budget.reservedToolTokens(),
                budget.reservedSafetyTokens(), budget.availableHistoryTokens(), estimateTokens(messages),
                truncated || budget.truncated(), budget.selectionStrategy());
        return new InternalRunContextDtos.RunContext(source.runId(), source.conversationId(),
                source.contextVersion(), space, product, List.copyOf(competitors),
                source.attachmentAssetIds(), rebuiltMemory, rebuiltWorkspace,
                source.loadedToolGroups(), rebuiltBudget, source.taskPreparationConstraints(),
                source.geoInsightContext(), source.protocolFeatures(), source.resourceSelection(),
                source.lastReadContext(), source.pendingResourceContext(), source.lastReportReadContext(),
                source.agentIdentity(), source.monitorActionContext(), source.knowledgeContext(),
                source.articlePreparationMode());
    }

    int projectedJsonbTextBytes(JsonNode value) {
        try {
            int compact = json.writeValueAsBytes(value).length;
            return Math.addExact(Math.addExact(compact, jsonbTextSpacing(value)), JSONB_TEXT_SAFETY_MARGIN_BYTES);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to size workflow context", exception);
        }
    }

    private int jsonbTextSpacing(JsonNode value) {
        int spacing = 0;
        if (value.isObject()) {
            spacing = value.size() + Math.max(0, value.size() - 1);
            for (var field : value.properties()) spacing = Math.addExact(spacing, jsonbTextSpacing(field.getValue()));
        } else if (value.isArray()) {
            spacing = Math.max(0, value.size() - 1);
            for (JsonNode item : value) spacing = Math.addExact(spacing, jsonbTextSpacing(item));
        }
        return spacing;
    }

    private int estimateTokens(Object value) {
        try {
            String text = json.writeValueAsString(value);
            int bytes = text.getBytes(StandardCharsets.UTF_8).length;
            return Math.max(text.codePointCount(0, text.length()), (bytes + 3) / 4);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to estimate workflow context", exception);
        }
    }

    private String canonical(JsonNode node) {
        try {
            return json.writeValueAsString(sort(node));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encode workflow context", exception);
        }
    }

    private JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> result.set(name, sort(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode();
            node.forEach(value -> result.add(sort(value)));
            return result;
        }
        return node;
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    public record FrozenContext(String json, String hash, int schemaVersion, int projectedBytes) { }
}
