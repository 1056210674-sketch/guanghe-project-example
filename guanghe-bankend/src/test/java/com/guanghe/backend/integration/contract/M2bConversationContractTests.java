package com.guanghe.backend.integration.contract;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class M2bConversationContractTests {

    private static final Path BROWSER = Path.of(
            "src", "main", "resources", "contracts", "openapi", "browser-openapi.yaml"
    );
    private static final Path INTERNAL = Path.of(
            "src", "main", "resources", "contracts", "openapi", "java-internal-openapi.yaml"
    );
    private static final Path MIGRATION = Path.of(
            "src", "main", "resources", "db", "migration", "V003__conversations_runs_and_events.sql"
    );
    private static final Path CONVERSATION_MAPPER = Path.of(
            "src", "main", "java", "com", "guanghe", "backend", "conversation", "ConversationMapper.java"
    );

    @Test
    void browserOpenApiFreeConversationOperationsAndErrorsAreSemanticallyFrozen() {
        OpenAPI api = parse(BROWSER);
        Map<String, Set<String>> operations = Map.of(
                "/api/v1/conversations", Set.of("get", "post"),
                "/api/v1/conversations/{conversationId}", Set.of("get", "patch", "delete"),
                "/api/v1/conversations/{conversationId}/messages", Set.of("get", "post"),
                "/api/v1/conversations/{conversationId}/events", Set.of("get"),
                "/api/v1/conversations/{conversationId}/stream-actions/stop", Set.of("post")
        );
        operations.forEach((path, methods) -> {
            assertThat(api.getPaths()).containsKey(path);
            methods.forEach(method -> assertThat(api.getPaths().get(path).readOperationsMap())
                    .containsKey(io.swagger.v3.oas.models.PathItem.HttpMethod.valueOf(method.toUpperCase())));
        });

        Operation send = api.getPaths().get("/api/v1/conversations/{conversationId}/messages").getPost();
        assertThat(send.getResponses().keySet()).contains("202", "401", "403", "404", "409", "422", "429");
        assertThat(send.getDescription()).contains("rolling 60 seconds", "Asia/Shanghai", "deletion do not reset");
        assertThat(parameterNames(api, send)).contains("Idempotency-Key");
        Parameter idempotency = send.getParameters().stream()
                .filter(parameter -> "Idempotency-Key".equals(parameterName(api, parameter)))
                .findFirst().map(parameter -> resolveParameter(api, parameter)).orElseThrow();
        assertThat(idempotency.getRequired()).isTrue();
        assertThat(idempotency.getSchema().getFormat()).isEqualTo("uuid");

        Operation events = api.getPaths().get("/api/v1/conversations/{conversationId}/events").getGet();
        assertThat(events.getResponses().get("200").getContent().keySet()).containsExactly("text/event-stream");
        assertThat(events.getResponses().keySet()).contains("401", "403", "404", "409", "422");
        assertThat(parameterNames(api, events)).contains("Last-Event-ID");
        Operation delete = api.getPaths().get("/api/v1/conversations/{conversationId}").getDelete();
        assertThat(delete.getResponses().keySet()).contains("409");

        for (String path : operations.keySet()) {
            api.getPaths().get(path).readOperations().forEach(operation ->
                    assertThat(operation.getResponses()).as(path).containsKey("422"));
        }
        assertThat(schema(api, "BusinessId").getTypes()).containsExactly("string");
        assertThat(schema(api, "SendConversationMessageRequest").getProperties()).doesNotContainKey("userId");
        Schema<?> messageRequest = schema(api, "SendConversationMessageRequest");
        assertThat(((Schema<?>) messageRequest.getProperties().get("attachments")).getMaxItems()).isEqualTo(8);
        assertThat(((Schema<?>) messageRequest.getProperties().get("draftId")).getTypes())
                .containsExactly("null");
        assertThat(((Schema<?>) messageRequest.getProperties().get("taskId")).getTypes())
                .containsExactly("null");
        assertThat(((Schema<?>) messageRequest.getProperties().get("contextSelectionMode")).getEnum()
                .stream().map(Object::toString).toList())
                .containsExactly("INHERIT", "REPLACE", "CLEAR");
        assertThat(schema(api, "MessageAccepted").getProperties()).containsKeys("messageId", "runId");
        assertThat(schema(api, "AttachmentReference").getProperties()).containsOnlyKeys("assetId");
    }

    @Test
    void internalOpenApiRunContextIsHmacProtectedAndWhitelisted() {
        OpenAPI api = parse(INTERNAL);
        Operation operation = api.getPaths().get("/internal/v1/runs/{runId}/context").getGet();
        assertThat(operation.getSecurity()).isNotEmpty();
        assertThat(parameterNames(api, operation))
                .contains("runId", "X-User-Context-Ref", "X-Internal-Key-Id", "X-Internal-Nonce", "traceparent");
        assertThat(operation.getResponses().keySet()).containsExactlyInAnyOrder("200", "401", "404", "422", "503");

        Schema<?> context = schema(api, "AuthorizedRunContext");
        assertThat(context.getProperties()).containsOnlyKeys(
                "runId", "conversationId", "contextVersion", "space", "product",
                "competitors", "attachmentAssetIds", "conversationMemory", "taskWorkspace",
                "loadedToolGroups", "contextBudget", "taskPreparationConstraints", "geoInsightContext", "protocolFeatures",
                "resourceSelection", "lastReadContext", "pendingResourceContext", "lastReportReadContext", "agentIdentity",
                "monitorActionContext", "knowledgeContext", "articlePreparationMode"
        );
        assertThat(context.getProperties()).doesNotContainKeys("userId", "jwt", "password", "role", "contextRef");
        assertThat(context.getRequired()).doesNotContain("geoInsightContext", "protocolFeatures", "resourceSelection",
                "lastReadContext", "pendingResourceContext", "lastReportReadContext", "agentIdentity", "monitorActionContext",
                "knowledgeContext", "articlePreparationMode");
        assertThat(((Schema<?>) context.getProperties().get("articlePreparationMode")).getConst())
                .isEqualTo("ARTICLE_FULL_V1");
        Schema<?> knowledge = schema(api, "KnowledgeContext");
        assertThat(knowledge.getProperties()).containsOnlyKeys("documents", "preferences", "businessHistory",
                "truncated", "sourceCount", "returnedCharacters", "memory");
        assertThat(knowledge.getRequired()).containsExactlyInAnyOrder("documents", "preferences", "businessHistory",
                "truncated", "sourceCount", "returnedCharacters");
        assertThat(((Schema<?>) knowledge.getProperties().get("memory")).getAnyOf())
                .anySatisfy(value -> {
                    Schema<?> memory = (Schema<?>) value;
                    assertThat(memory.getProperties()).containsOnlyKeys("schemaVersion", "projectId", "entries",
                            "revision", "historyCutoffAt");
                    assertThat(memory.getRequired()).containsExactlyInAnyOrder("schemaVersion", "projectId", "entries",
                            "revision", "historyCutoffAt");
                    assertThat(((Schema<?>) memory.getProperties().get("entries")).getMaxItems()).isEqualTo(200);
                });
        assertThat(((Schema<?>) knowledge.getProperties().get("documents")).getMaxItems()).isEqualTo(12);
        assertThat(((Schema<?>) knowledge.getProperties().get("preferences")).getMaxItems()).isEqualTo(100);
        assertThat(((Schema<?>) knowledge.getProperties().get("returnedCharacters")).getMaximum())
                .isEqualByComparingTo("12000");
        Schema<?> monitorAction = schema(api, "MonitorActionContext");
        assertThat(monitorAction.getProperties()).containsOnlyKeys("action", "spaceId", "productId", "parameters", "questions");
        assertThat(monitorAction.getRequired()).containsExactlyInAnyOrder("action", "spaceId", "productId", "parameters", "questions");
        assertThat(((Schema<?>) monitorAction.getProperties().get("action")).getConst()).isEqualTo("MONITOR_RUN");
        assertThat(((Schema<?>) monitorAction.getProperties().get("questions")).getMinItems()).isEqualTo(3);
        assertThat(((Schema<?>) monitorAction.getProperties().get("questions")).getMaxItems()).isEqualTo(10);
        Schema<?> identity = schema(api, "AuthorizedAgentIdentity");
        assertThat(identity.getProperties()).containsOnlyKeys("installationId", "agentKey", "agentVersion",
                "name", "projectId", "capabilities", "executionRelease", "catalogHash");
        assertThat(identity.getRequired()).containsExactlyInAnyOrder("installationId", "agentKey", "agentVersion",
                "name", "projectId", "capabilities");
        assertThat(((Schema<?>) identity.getProperties().get("executionRelease")).getTypes()).containsExactly("string");
        assertThat(((Schema<?>) identity.getProperties().get("catalogHash")).getPattern()).isEqualTo("^[0-9a-f]{64}$");
        assertThat(((Schema<?>) identity.getProperties().get("capabilities")).getMaxItems()).isEqualTo(32);
        Schema<?> protocolFeatures = (Schema<?>) context.getProperties().get("protocolFeatures");
        assertThat(protocolFeatures.getProperties()).containsOnlyKeys("businessOutcome", "resourceLookup");
        assertThat(protocolFeatures.getRequired()).containsExactlyInAnyOrder("businessOutcome", "resourceLookup");
        Object additional = schema(api,"GeoInsightContext").getAdditionalProperties();
        if (additional instanceof Boolean) assertThat(additional).isEqualTo(false);
        else assertThat(((Schema<?>) additional).getBooleanSchemaValue()).isFalse();
        assertThat(schema(api,"GeoInsightContext").getProperties()).doesNotContainKeys("userId","token","confirmToken");
        assertThat(((Schema<?>)schema(api,"GeoInsightContext").getProperties().get("topicChoices")).getMaxItems()).isEqualTo(6);
        assertThat(((Schema<?>)schema(api,"GeoChatEntity").getProperties().get("recognitionStatus")).getTypes())
                .containsExactlyInAnyOrder("string","null");
        assertThat(((Schema<?>)schema(api,"GeoChatTrendPlatform").getProperties().get("currentReportId")).getOneOf())
                .anySatisfy(value -> assertThat(((Schema<?>)value).getTypes()).contains("null"));
        assertThat(((Schema<?>)schema(api,"GeoChatReport").getProperties().get("metricScope")).getConst()).isEqualTo("COMPETITIVE_DISCOVERY");
        assertThat(((Schema<?>)schema(api,"GeoChatTrend").getProperties().get("metricScope")).getConst()).isEqualTo("SELF_DISCOVERY_COMMON_BASELINE");
        assertThat(schema(api, "AuthorizedSpace").getProperties())
                .containsOnlyKeys("id", "name", "brand", "logoAssetId");
        assertThat(schema(api, "AuthorizedProduct").getProperties())
                .containsOnlyKeys("id", "spaceId", "name", "aliases", "category", "modelSpec",
                        "sellingPoints", "parameters", "audience", "usageScenarios",
                        "prohibitedClaims", "primaryAssetId", "identityAssetIds");
        assertThat(schema(api, "AuthorizedCompetitor").getProperties())
                .containsOnlyKeys("id", "spaceId", "name", "aliases", "type", "description", "status");
        assertThat(schema(api, "ConversationMemory").getProperties()).containsOnlyKeys(
                "messages", "summary", "lastMessageSequence", "summaryThroughSequence", "contextSource"
        );
        assertThat(schema(api, "ConversationMemoryMessage").getProperties()).containsOnlyKeys(
                "messageId", "sequence", "role", "content", "createdAt", "replyToMessageId"
        );
        assertThat(schema(api, "TaskWorkspace").getProperties()).containsOnlyKeys(
                "currentGoal", "currentIntent", "activeScope", "confirmedConstraints",
                "unresolvedQuestions", "pendingInteraction", "activeDraftId", "activeTaskId",
                "referenceAssetIds", "runningTasks", "contextVersion"
        );
        assertThat(schema(api, "ContextBudget").getProperties()).containsOnlyKeys(
                "targetInputTokens", "reservedOutputTokens", "reservedToolTokens", "reservedSafetyTokens",
                "availableHistoryTokens", "estimatedHistoryTokens", "truncated", "selectionStrategy"
        );
        assertThat(schema(api, "TaskPreparationConstraints").getProperties()).containsOnlyKeys("geo");
        assertThat(schema(api, "GeoPreparationConstraints").getProperties()).containsOnlyKeys(
                "enabled", "minQuestions", "maxQuestions", "platformCodes", "maxProviderCalls"
        );
        Schema<?> toolGroups = (Schema<?>) context.getProperties().get("loadedToolGroups");
        assertThat(toolGroups.getMinItems()).isZero();
        assertThat(toolGroups.getMaxItems()).isEqualTo(1);
        assertThat(toolGroups.getItems().getConst()).isEqualTo("TASK_PREPARATION");
        assertThat(context.getProperties()).doesNotContainKeys(
                "providerResponse", "hmacSecret", "authorization", "accessToken"
        );
    }

    @Test
    void resourceReadContractsAreBoundedHmacOperationsAndServerReferencesStayStrings() {
        OpenAPI api = parse(INTERNAL);
        for (String operation : List.of("search", "read")) {
            Operation post = api.getPaths().get("/internal/v1/runs/{runId}/resources/" + operation).getPost();
            assertThat(post.getSecurity()).isNotEmpty();
            assertThat(parameterNames(api, post)).contains("runId", "X-User-Context-Ref", "X-Internal-Key-Id",
                    "X-Internal-Timestamp", "X-Internal-Nonce", "X-Content-SHA256", "X-Internal-Signature", "traceparent");
            assertThat(post.getResponses().keySet()).containsExactlyInAnyOrder("200", "401", "404", "409", "422", "503");
            assertThat(post.getResponses().get("200").getContent()).containsOnlyKeys("application/json");
        }
        assertThat(schema(api, "RunResourceSearchRequest").getProperties()).containsOnlyKeys("toolCallId", "kind", "keyword", "scopeRef");
        assertThat(((Schema<?>) schema(api, "RunResourceSearchResponse").getProperties().get("candidates")).getMaxItems()).isEqualTo(6);
        assertThat(((Schema<?>) schema(api, "RunResourceReadRequest").getProperties().get("sampleRefs")).getMaxItems()).isEqualTo(3);
        assertThat(schema(api, "RunResourceReadResponse").getRequired()).doesNotContain("reportId", "reportAttempt", "productId");
        assertThat(schema(api, "RunResourceReadResponse").getProperties()).doesNotContainKeys("userId", "contextRef", "authorization");
        assertThat(schema(api, "RunResourceReadRequest").getOneOf()).hasSize(2);
        OpenAPI browser = parse(BROWSER);
        assertThat(schema(browser, "SendConversationMessageRequest").getProperties()).containsKey("resourceSelection");
        assertThat(schema(browser, "ConversationResourceSelectionRequest").getProperties()).containsOnlyKeys("candidateSetId", "choiceId");
        assertThat(schema(browser, "ConversationResourceInteraction").getRequired()).contains("available", "candidateSetId", "candidates");
    }

    @Test
    void frozenGeoMessageContextAndEditableDiscoveryDraftHaveExplicitBrowserSchemas() {
        OpenAPI api = parse(BROWSER);
        Schema<?> content = (Schema<?>) schema(api,"ConversationMessage").getProperties().get("content");
        assertThat(((Schema<?>)content.getProperties().get("geoContext")).getProperties().keySet())
                .containsExactlyInAnyOrderElementsOf(schema(api,"ConversationMessageGeoContext").getProperties().keySet());
        assertThat(schema(api,"ConversationMessageGeoContext").getProperties()).containsOnlyKeys(
                "status","selectionSource","report","topicChoices","topicsTruncated");
        assertThat(schema(api,"ConversationMessageGeoReport").getRequired()).containsExactlyInAnyOrder(
                "reportId","topicId","title","spaceId","productId","generatedAt","canCreateArticle","articleUnavailableReason");
        assertThat(schema(api,"ConversationMessageGeoReport").getProperties()).doesNotContainKeys(
                "userId","summary","findings","confirmationToken","quotedPoints");
        assertThat(schema(api,"ConversationMessageGeoReport").getProperties()).containsKeys("observationId", "available");
        assertThat(schema(api,"CreateGeoArticleWorkflowRequest").getProperties()).containsOnlyKeys(
                "suggestionId", "conversationId", "observationId", "direction");
        for (String input : List.of("CreateTaskDraftRequest","ReviseTaskDraftRequest")) {
            Schema<?> parameters = (Schema<?>)schema(api,input).getProperties().get("parameters");
            assertThat(parameters.getOneOf())
                    .filteredOn(item -> item.getProperties() != null)
                    .anySatisfy(item -> {
                Schema<?> mode = (Schema<?>) ((Schema<?>)item).getProperties().get("mode");
                assertThat(mode.getConst()).isEqualTo("QUESTION_DISCOVERY");
                assertThat(((Schema<?>)item).getProperties().keySet())
                        .containsExactlyInAnyOrderElementsOf(schema(api,"GeoDiscoveryTaskParameters").getProperties().keySet());
            });
        }
        OpenAPI internal = parse(INTERNAL);
        assertThat(schema(internal,"GeoChatReport").getProperties()).containsKeys(
                "spaceId","productId","canCreateArticle","articleUnavailableReason");
        assertThat(schema(internal,"GeoChatReport").getRequired()).doesNotContain(
                "spaceId","productId","canCreateArticle","articleUnavailableReason");
    }

    @Test
    void geoContextPersistenceMergesExistingTextAndIsFencedByOwnerRunRoleTypeAndCompletion() throws Exception {
        var method = com.guanghe.backend.conversation.ConversationMapper.class.getMethod("setAssistantGeoContext",
                long.class,long.class,java.util.UUID.class,String.class,java.time.OffsetDateTime.class);
        String sql = String.join(" ",method.getAnnotation(org.apache.ibatis.annotations.Update.class).value());
        assertThat(sql).contains("jsonb_set(content, '{geoContext}', CAST(#{geoContextJson} AS jsonb))",
                "id = #{messageId}","user_id = #{userId}","agent_run_id = #{runId}",
                "role = 'ASSISTANT'","content_type = 'TEXT'","status = 'COMPLETED'");
        assertThat(sql).doesNotContain("jsonb_build_object('text'", "UPDATE business.agent_run");
    }

    @Test
    void v003CreatesOnlyTheFourM2bTablesAndRequiredOwnershipAndIdempotencyRules() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).toLowerCase();
        assertThat(sql).contains(
                "create table business.conversation (",
                "create table business.conversation_message (",
                "create table business.agent_run (",
                "create table business.conversation_event (",
                "unique (user_id, conversation_id, client_message_id)",
                "unique (user_id, conversation_id, idempotency_key)",
                "unique (conversation_id, sequence_no)",
                "context_snapshot jsonb not null",
                "jsonb_typeof(context_snapshot) = 'object'",
                "octet_length(context_snapshot::text) <= 262144",
                "create unique index uk_conversation_event_terminal_run",
                "on delete cascade"
        );
        assertThat(sql).containsPattern("foreign key \\(\\s*reply_to_message_id, user_id, conversation_id\\s*\\)");
        assertThat(sql).containsPattern("foreign key \\(\\s*response_message_id, user_id, conversation_id\\s*\\)");
        assertThat(sql).containsPattern("foreign key \\(\\s*agent_run_id, user_id, conversation_id\\s*\\)");
        assertThat(sql).containsPattern("foreign key \\(\\s*message_id, user_id, conversation_id\\s*\\)");
        assertThat(count(sql, "create table ")).isEqualTo(4);
        assertThat(sql).doesNotContain(
                "create table agent.", "create table business.point_", "create table business.asset",
                "create table business.provider_", "create table business.outbox",
                "create table business.inbox", "create table business.rabbit"
        );
    }

    @Test
    void agentMirrorMatchesItsManifestAndRabbitContractRemainsUnchanged() throws Exception {
        assertThat(lfSha256(Path.of(
                "src", "main", "resources", "contracts", "rabbit", "task-execution-requested-v1.schema.json"
        ))).isEqualTo("e13ea99e873c34a9ccad011148b1c53d6d606109b206b27b875aa20f150e2928");
        String manifest = Files.readString(Path.of(
                "src", "main", "resources", "contracts", "openapi", "agent-openapi-source.json"
        ), StandardCharsets.UTF_8);
        String declared = new com.fasterxml.jackson.databind.ObjectMapper().readTree(manifest)
                .path("sha256LfNormalized").asText();
        assertThat(lfSha256(Path.of(
                "src", "main", "resources", "contracts", "openapi", "agent-openapi.yaml"
        ))).isEqualTo(declared);
    }

    @Test
    void runCompletionSqlLinearizesACommittedStopRequestAndReturnsTheDatabaseStatus() throws Exception {
        String mapper = Files.readString(CONVERSATION_MAPPER, StandardCharsets.UTF_8)
                .toLowerCase().replaceAll("\\s+", " ");

        assertThat(mapper).contains(
                "when status = 'stop_requested' or #{reportedstatus} = 'stopped' then 'stopped'",
                "returning status"
        );
    }

    private OpenAPI parse(Path path) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(path.toString(), null, options);
        assertThat(result.getMessages()).as(path.toString()).isEmpty();
        assertThat(result.getOpenAPI()).isNotNull();
        return result.getOpenAPI();
    }

    private Schema<?> schema(OpenAPI api, String name) {
        return api.getComponents().getSchemas().get(name);
    }

    private List<String> parameterNames(OpenAPI api, Operation operation) {
        return operation.getParameters().stream().map(parameter -> parameterName(api, parameter)).toList();
    }

    private String parameterName(OpenAPI api, Parameter parameter) {
        return resolveParameter(api, parameter).getName();
    }

    private Parameter resolveParameter(OpenAPI api, Parameter parameter) {
        if (parameter.getName() != null || parameter.get$ref() == null) {
            return parameter;
        }
        String name = parameter.get$ref().substring(parameter.get$ref().lastIndexOf('/') + 1);
        return api.getComponents().getParameters().get(name);
    }

    private long count(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }

    private String lfSha256(Path path) throws Exception {
        String normalized = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(normalized.getBytes(StandardCharsets.UTF_8)));
    }
}
