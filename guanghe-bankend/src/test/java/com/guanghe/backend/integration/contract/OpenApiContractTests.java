package com.guanghe.backend.integration.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.auth.web.AuthUserResponse;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiContractTests {

    private static final String BROWSER = "/contracts/openapi/browser-openapi.yaml";
    private static final String JAVA_INTERNAL = "/contracts/openapi/java-internal-openapi.yaml";
    private static final String PYTHON_AGENT = "/contracts/openapi/agent-openapi.yaml";
    private static final String PYTHON_SOURCE = "/contracts/openapi/agent-openapi-source.json";
    private static final String PYTHON_COMMIT = "92e5d95875bfdd2801f92111ccd41d9633fb9311";
    private static final String RABBIT_SCHEMA_SHA256 =
            "e13ea99e873c34a9ccad011148b1c53d6d606109b206b27b875aa20f150e2928";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void brandIntakeMemoryUsesExplicitRestoreAndNarrowAppendWithoutNewRequiredFields() throws Exception {
        var browser = parse(BROWSER).getComponents().getSchemas().get("UpdateMemoryEntryRequest");
        assertThat(browser.getRequired()).containsExactlyInAnyOrder("version", "label", "value");
        var restore = (Schema<?>) browser.getProperties().get("restore");
        assertThat(restore.getDefault()).isEqualTo(false);
        var internal = parse(JAVA_INTERNAL).getComponents().getSchemas().get("RunMemoryUpsertArguments");
        assertThat(internal.getRequired()).doesNotContain("mode").contains("sourceQuote", "expectedVersion");
        var mode = (Schema<?>) internal.getProperties().get("mode");
        assertThat(mode.getEnum().stream().map(Object::toString)).containsExactly("REPLACE", "APPEND");
        assertThat(mode.getDefault()).isEqualTo("REPLACE");
        assertThat(internal.getDescription()).contains("brand.categories", "Missing or deleted entries require REPLACE");
    }

    @Test
    void allFrozenContractsAreValidOpenApi31DocumentsWithUniqueOperationIds() throws Exception {
        for (String resource : List.of(BROWSER, JAVA_INTERNAL, PYTHON_AGENT)) {
            OpenAPI contract = parse(resource);
            assertThat(contract.getOpenapi()).as(resource).startsWith("3.1.");

            Set<String> operationIds = new HashSet<>();
            for (Operation operation : contract.getPaths().values().stream()
                    .flatMap(path -> path.readOperations().stream())
                    .toList()) {
                assertThat(operation.getOperationId()).as(resource).isNotBlank();
                assertThat(operationIds.add(operation.getOperationId()))
                        .as("duplicate operationId %s in %s", operation.getOperationId(), resource)
                        .isTrue();
            }
        }
    }

    @Test
    void firstLoginPasswordContractMatchesSixCharacterMinimumWithoutRestrictingExistingLogins() throws Exception {
        OpenAPI browser = parse(BROWSER);
        Schema<?> password = (Schema<?>) browser.getComponents().getSchemas()
                .get("CompleteFirstLoginRequest").getProperties().get("newPassword");
        assertThat(password.getMinLength()).isEqualTo(6);
        assertThat(password.getMaxLength()).isEqualTo(128);
        assertThat(password.getWriteOnly()).isTrue();
        Schema<?> loginPassword = (Schema<?>) browser.getComponents().getSchemas()
                .get("LoginRequest").getProperties().get("password");
        assertThat(loginPassword.getMaxLength()).isEqualTo(128);
        assertThat(loginPassword.getMinLength()).isEqualTo(1);
    }

    @Test
    void usernameProvisioningContractRequiresOneIdentifierAndExposesNullablePhone() throws Exception {
        OpenAPI browser = parse(BROWSER);
        Schema<?> request = browser.getComponents().getSchemas().get("AdminCreateUserRequest");
        assertThat(request.getRequired()).containsExactly("displayName");
        assertThat(request.getOneOf()).hasSize(2);
        assertThat(request.getOneOf().get(0).getRequired()).containsExactly("phone");
        assertThat(request.getOneOf().get(0).getNot().getRequired()).containsExactly("username");
        assertThat(request.getOneOf().get(1).getRequired()).containsExactly("username");
        assertThat(request.getOneOf().get(1).getNot().getRequired()).containsExactly("phone");
        Schema<?> username = (Schema<?>) request.getProperties().get("username");
        assertThat(username.getMinLength()).isEqualTo(3);
        assertThat(username.getMaxLength()).isEqualTo(32);
        assertThat(username.getPattern()).isEqualTo("^[A-Za-z][A-Za-z0-9_]{2,31}$");
        assertThat(request.getProperties()).doesNotContainKeys("role", "password", "tokenVersion", "userId");
        Schema<?> response = browser.getComponents().getSchemas().get("AdminUser");
        assertThat(response.getRequired()).contains("username", "phone");
        assertThat(((Schema<?>) response.getProperties().get("phone")).getTypes()).containsExactlyInAnyOrder("string", "null");
    }

    @Test
    void browserContractPreservesExistingSurfacesAndExposesPermanentAssets() throws Exception {
        OpenAPI browser = parse(BROWSER);

        assertThat(browser.getPaths().keySet()).containsExactlyInAnyOrder(
                "/api/v1/execution-budget-policy",
                "/api/v1/geo/diagnostics",
                "/api/v1/geo/diagnostics/{id}",
                "/api/v1/geo/diagnostics/{id}/generate",
                "/api/v1/geo/diagnostics/{id}/quote",
                "/api/v1/geo/diagnostics/{id}/submit",
                "/api/v1/geo/diagnostics/{id}/report",
                "/api/v1/geo/diagnostics/{id}/report/retry",
                "/api/v1/geo/diagnostics/{id}/report/download-url",
                "/api/v1/geo/diagnostics/{id}/report/observations",
                "/api/v1/geo/period-reports",
                "/api/v1/geo/period-reports/{reportId}",
                "/api/v1/geo/period-reports/{reportId}/retry-summary",
                "/api/v1/geo/period-reports/{reportId}/export",
                "/api/v1/memory/entries",
                "/api/v1/memory/entries/{id}",
                "/api/v1/agent-installations/{id}/creative-storage",
                "/api/v1/article-full-preparations/{preparationId}/preview",
                "/api/v1/articles/{articleId}/asset-collection",
                "/api/v1/conversations/{conversationId}/geo-monitor-action-preview",
                "/api/v1/conversations/{conversationId}/geo-monitor-authorizations",
                "/api/v1/conversations/{conversationId}/geo-monitor-authorizations/{grantId}",
                "/api/v1/conversations/{conversationId}/geo-monitor-authorizations/{grantId}/revoke",
                "/api/v1/conversations/{conversationId}/runs/{runId}/geo-monitor-admission",
                "/api/v1/agents", "/api/v1/agent-installations", "/api/v1/agent-installations/{id}",
                "/api/v1/projects",
                "/api/v1/projects/{projectId}",
                "/api/v1/projects/{projectId}/geo-target",
                "/api/v1/projects/{projectId}/knowledge-documents",
                "/api/v1/projects/{projectId}/knowledge-documents/{documentId}",
                "/api/v1/projects/{projectId}/knowledge-documents/upload",
                "/api/v1/projects/{projectId}/business-history",
                "/api/v1/memory/preferences",
                "/api/v1/memory/preferences/{id}",
                "/api/v1/conversations/{conversationId}/article-authorizations",
                "/api/v1/conversations/{conversationId}/article-authorizations/{grantId}",
                "/api/v1/conversations/{conversationId}/article-full-preparations",
                "/api/v1/article-full-preparations/{preparationId}",
                "/api/v1/articles/{articleId}/preparation-media-recommendations",
                "/api/v1/geo/reports/{reportId}/export",
                "/api/v1/admin/agent-definitions",
                "/api/v1/admin/agent-definitions/{agentKey}/versions/{version}",
                "/api/v1/admin/geo-plans",
                "/api/v1/admin/geo-plans/{id}/slots",
                "/api/v1/admin/geo-plans/{id}/pause",
                "/api/v1/admin/geo-plans/{id}/resume",
                "/api/v1/admin/knowledge-documents",
                "/api/v1/admin/memory-preferences",
                "/api/v1/admin/platform-metadata",
                "/api/v1/public/registration-config",
                "/api/v1/auth/login",
                "/api/v1/auth/me",
                "/api/v1/auth/logout",
                "/api/v1/auth/complete-first-login",
                "/api/v1/spaces",
                "/api/v1/spaces/{spaceId}",
                "/api/v1/spaces/{spaceId}/products",
                "/api/v1/products/{productId}",
                "/api/v1/products/{productId}/aliases",
                "/api/v1/products/{productId}/identity-assets",
                "/api/v1/spaces/{spaceId}/competitors",
                "/api/v1/competitors/{competitorId}",
                "/api/v1/competitors/{competitorId}/aliases",
                "/api/v1/geo/topics",
                "/api/v1/geo/topics/{topicId}",
                "/api/v1/geo/topics/{topicId}/visibility-trend",
                "/api/v1/geo/reports/{reportId}/competitive-insights",
                "/api/v1/geo/reports/{reportId}/competitive-insights/analyze",
                "/api/v1/geo/topics/{topicId}/competitor-candidates",
                "/api/v1/geo/competitor-candidates/{candidateId}/confirm",
                "/api/v1/geo/competitor-candidates/{candidateId}/ignore",
                "/api/v1/geo/topics/{topicId}/keywords",
                "/api/v1/geo/topics/{topicId}/questions",
                "/api/v1/geo/topics/{topicId}/competitors",
                "/api/v1/geo/platforms",
                "/api/v1/geo/plan-drafts",
                "/api/v1/geo/plans",
                "/api/v1/geo/plans/{planId}",
                "/api/v1/geo/plans/{planId}/pause",
                "/api/v1/geo/plans/{planId}/resume",
                "/api/v1/geo/runs",
                "/api/v1/geo/runs/{runId}",
                "/api/v1/geo/runs/{runId}/progress",
                "/api/v1/geo/reports",
                "/api/v1/geo/reports/{reportId}",
                "/api/v1/geo/reports/{reportId}/creative-plans",
                "/api/v1/geo/reports/{reportId}/archive",
                "/api/v1/geo/reports/{reportId}/restore",
                "/api/v1/geo/reports/{reportId}/reinterpretation",
                "/api/v1/geo/reports/{reportId}/article-workflows",
                "/api/v1/conversations",
                "/api/v1/conversations/{conversationId}",
                "/api/v1/conversations/{conversationId}/messages",
                "/api/v1/conversations/{conversationId}/snapshot",
                "/api/v1/conversations/{conversationId}/message-operations/{clientMessageId}",
                "/api/v1/conversations/{conversationId}/events",
                "/api/v1/conversations/{conversationId}/stream-actions/stop",
                "/api/v1/conversations/{conversationId}/actions/confirm-active-task-draft",
                "/api/v1/articles",
                "/api/v1/articles/{articleId}",
                "/api/v1/articles/{articleId}/outline",
                "/api/v1/articles/{articleId}/workflow/title",
                "/api/v1/articles/{articleId}/workflow/creative-plan",
                "/api/v1/articles/{articleId}/workflow/creative-plan/confirm",
                "/api/v1/articles/{articleId}/workflow/body/confirm",
                "/api/v1/articles/{articleId}/assets",
                "/api/v1/articles/{articleId}/submission-snapshots",
                "/api/v1/articles/{articleId}/submission-snapshots/{snapshotId}",
                "/api/v1/media/resources",
                "/api/v1/media/resources/{resourceId}",
                "/api/v1/media/recommendations",
                "/api/v1/media/quotes",
                "/api/v1/media/submissions",
                "/api/v1/media/submissions/{submissionId}",
                "/api/v1/task-drafts",
                "/api/v1/task-drafts/{draftId}",
                "/api/v1/task-drafts/{draftId}/revisions",
                "/api/v1/task-drafts/{draftId}/abandon",
                "/api/v1/task-drafts/{draftId}/confirm",
                "/api/v1/tasks",
                "/api/v1/tasks/{taskId}",
                "/api/v1/tasks/{taskId}/events",
                "/api/v1/assets",
                "/api/v1/assets/images",
                "/api/v1/assets/{assetId}",
                "/api/v1/assets/{assetId}/download-url",
                "/api/v1/billing/point-account",
                "/api/v1/billing/point-ledger",
                "/api/v1/billing/recharge-capability",
                "/api/v1/billing/recharge-orders",
                "/api/v1/billing/recharge-orders/{orderId}",
                "/api/v1/billing/recharge-orders/{orderId}/query",
                "/api/v1/billing/recharge-orders/{orderId}/close",
                "/api/v1/billing/pricing/active",
                "/api/v1/billing/invoice-capability",
                "/api/v1/billing/invoices",
                "/api/v1/billing/invoices/{applicationId}",
                "/api/v1/billing/invoices/{applicationId}/cancel",
                "/api/v1/billing/notification-capability",
                "/api/v1/billing/notifications",
                "/api/v1/billing/notifications/unread-count",
                "/api/v1/billing/notifications/{notificationId}/read",
                "/api/v1/billing/notifications/read-all",
                "/api/v1/billing/feedback",
                "/api/v1/billing/feedback/{feedbackId}",
                "/api/v1/callbacks/payment/notify",
                "/api/v1/admin/billing/recharge-orders",
                "/api/v1/admin/billing/recharge-orders/{orderId}",
                "/api/v1/admin/billing/recharge-orders/{orderId}/reconcile",
                "/api/v1/admin/billing/point-adjustments",
                "/api/v1/admin/billing/point-adjustments/{adjustmentId}/confirm",
                "/api/v1/admin/billing/audit-events",
                "/api/v1/admin/billing/bookkeeping/summary",
                "/api/v1/admin/billing/point-ledger",
                "/api/v1/admin/billing/point-ledger/summary",
                "/api/v1/admin/billing/pricing-versions",
                "/api/v1/admin/billing/pricing-versions/{versionId}",
                "/api/v1/admin/billing/pricing-versions/{versionId}/publish",
                "/api/v1/admin/billing/pricing-versions/{versionId}/retire",
                "/api/v1/admin/billing/rate-limits",
                "/api/v1/admin/billing/rate-limits/{action}",
                "/api/v1/admin/billing/invoices",
                "/api/v1/admin/billing/invoices/{applicationId}",
                "/api/v1/admin/billing/invoices/{applicationId}/accept",
                "/api/v1/admin/billing/invoices/{applicationId}/approve",
                "/api/v1/admin/billing/invoices/{applicationId}/reject",
                "/api/v1/admin/billing/invoices/{applicationId}/deliver",
                "/api/v1/admin/billing/invoices/{applicationId}/webhook/retry",
                "/api/v1/admin/billing/feedback",
                "/api/v1/admin/billing/feedback/{feedbackId}/handle",
                "/api/v1/admin/users",
                "/api/v1/admin/users/{userId}",
                "/api/v1/admin/users/{userId}/password-reset",
                "/api/v1/admin/users/{userId}/disable",
                "/api/v1/admin/users/{userId}/restore",
                "/api/v1/admin/diagnostics/tasks",
                "/api/v1/admin/diagnostics/tasks/{taskId}",
                "/api/v1/admin/diagnostics/runs",
                "/api/v1/admin/diagnostics/runs/{runId}",
                "/api/v1/admin/media/overview",
                "/api/v1/admin/media/resources",
                "/api/v1/admin/media/resources/{resourceId}",
                "/api/v1/admin/media/submissions",
                "/api/v1/admin/media/submissions/{submissionId}",
                "/api/v1/admin/media/callbacks",
                "/api/v1/admin/media/callbacks/{callbackId}",
                "/api/v1/admin/media/sync-states",
                "/api/v1/admin/agent-versions",
                "/api/v1/admin/agent-versions/{bundleVersion}",
                "/api/v1/admin/agent-versions/{bundleVersion}/runs",
                "/api/v1/admin/agent-versions/{bundleVersion}/evaluations",
                "/api/v1/workflow-plans",
                "/api/v1/workflow-plans/{planId}",
                "/api/v1/workflow-plans/{planId}/abandon",
                "/api/v1/workflow-plans/{planId}/confirmation-preview",
                "/api/v1/workflow-plans/{planId}/confirm",
                "/api/v1/workflow-plans/{planId}/start"
        );
        assertThat(browser.getPaths()).doesNotContainKey("/api/v1/points/account");
        assertThat(browser.getComponents().getSchemas().get("ErrorResponse").getProperties())
                .containsKey("traceId");
        assertThat(browser.getPaths().get("/api/v1/auth/login").getPost().getSecurity()).isEmpty();
        assertThat(browser.getPaths().get("/api/v1/auth/me").getGet().getSecurity()).isNotEmpty();
        assertThat(browser.getPaths().get("/api/v1/auth/logout").getPost().getSecurity()).isNotEmpty();

        Operation registrationConfig = browser.getPaths()
                .get("/api/v1/public/registration-config").getGet();
        assertThat(registrationConfig.getOperationId()).isEqualTo("getRegistrationConfig");
        assertThat(registrationConfig.getSecurity()).isEmpty();
        assertResponseCodes(registrationConfig, "200");
        Schema<?> registrationConfigSchema = browser.getComponents().getSchemas().get("RegistrationConfig");
        assertAdditionalPropertiesFalse(registrationConfigSchema);
        assertThat(registrationConfigSchema.getRequired()).containsExactlyInAnyOrder(
                "registrationEnabled", "terms", "privacy", "smsCooldownSeconds");
        assertThat(registrationConfigSchema.getProperties()).containsOnlyKeys(
                "registrationEnabled", "terms", "privacy", "smsCooldownSeconds");
        assertThat(((Schema<?>) registrationConfigSchema.getProperties().get("registrationEnabled")).getConst())
                .isEqualTo(false);
        assertThat(((Schema<?>) registrationConfigSchema.getProperties().get("smsCooldownSeconds")).getMinimum())
                .isEqualByComparingTo("1");

        Operation activation = browser.getPaths().get("/api/v1/auth/complete-first-login").getPost();
        assertThat(activation.getSecurity()).isEmpty();
        assertThat(activation.getResponses().keySet()).contains("200", "401", "409", "422");
        Schema<?> loginResponse = browser.getComponents().getSchemas().get("LoginResponse");
        assertThat(loginResponse.getOneOf()).hasSize(2);
        assertThat(loginResponse.getOneOf())
                .anySatisfy(schema -> assertThat(schema.getRequired()).contains("accessToken", "tokenType", "user"))
                .anySatisfy(schema -> assertThat(schema.getRequired()).contains(
                        "activationToken", "nextAction", "terms", "privacy"));
        Schema<?> createUser = browser.getComponents().getSchemas().get("AdminCreateUserRequest");
        assertThat(createUser.getProperties()).containsOnlyKeys("phone", "username", "displayName", "initialPointGrant");
        Schema<?> temporaryPassword = (Schema<?>) browser.getComponents().getSchemas().get("AdminProvisionedUser")
                .getProperties().get("temporaryPassword");
        assertThat(temporaryPassword.getWriteOnly()).isTrue();
        for (String path : List.of("/api/v1/admin/users", "/api/v1/admin/users/{userId}",
                "/api/v1/admin/users/{userId}/password-reset", "/api/v1/admin/users/{userId}/disable",
                "/api/v1/admin/users/{userId}/restore")) {
            assertThat(browser.getPaths().get(path).readOperations())
                    .allSatisfy(operation -> assertThat(operation.getSecurity()).isNotEmpty());
        }

        Schema<?> authUserSchema = browser.getComponents().getSchemas().get("AuthUser");
        Schema<?> displayNameSchema = (Schema<?>) authUserSchema.getProperties().get("displayName");
        assertThat(displayNameSchema.getTypes())
                .containsExactlyInAnyOrder("string", "null");

        JsonNode nullableFixedAccount = objectMapper.valueToTree(new AuthUserResponse(
                "1900012345678901000",
                "fixed-user",
                null,
                null,
                null,
                "USER",
                "ACTIVE"
        ));
        List<String> serializedFields = new ArrayList<>();
        nullableFixedAccount.fieldNames().forEachRemaining(serializedFields::add);
        assertThat(serializedFields).containsAll(authUserSchema.getRequired());
        assertThat(authUserSchema.getProperties().keySet()).containsAll(serializedFields);
        assertThat(nullableFixedAccount.path("displayName").isNull()).isTrue();
        Operation confirm = browser.getPaths().get("/api/v1/task-drafts/{draftId}/confirm").getPost();
        assertThat(browser.getPaths().get("/api/v1/task-drafts/{draftId}/confirm").getParameters())
                .extracting(parameter -> parameter.getName()).contains("draftId", "Idempotency-Key");
        assertResponseCodes(confirm, "202", "409", "410", "422");
        Operation confirmActive = browser.getPaths()
                .get("/api/v1/conversations/{conversationId}/actions/confirm-active-task-draft")
                .getPost();
        assertThat(confirmActive.getOperationId()).isEqualTo("confirmActiveConversationTaskDraft");
        assertThat(browser.getPaths()
                .get("/api/v1/conversations/{conversationId}/actions/confirm-active-task-draft")
                .getParameters()).extracting(parameter -> parameter.getName())
                .contains("conversationId", "Idempotency-Key");
        assertResponseCodes(confirmActive, "409", "410", "422");
        assertThat(confirmActive.getDeprecated()).isTrue();
        assertThat(confirmActive.getResponses()).doesNotContainKey("202");
        assertThat(browser.getComponents().getSchemas().get("StopConversationRequest").getRequired())
                .containsExactly("expectedRunId");
        assertThat(browser.getComponents().getSchemas().get("ConversationSnapshot").getRequired())
                .containsExactlyInAnyOrder("messages", "nextBeforeSequence", "highWatermark", "activeRun");
        Operation createDraft = browser.getPaths().get("/api/v1/task-drafts").getPost();
        Operation reviseDraft = browser.getPaths().get("/api/v1/task-drafts/{draftId}/revisions").getPost();
        assertResponseCodes(createDraft, "201", "404", "409", "422");
        assertResponseCodes(reviseDraft, "201", "404", "409", "410", "422");
        assertThat(createDraft.getParameters()).extracting(parameter -> parameter.getName())
                .contains("Idempotency-Key");
        assertThat(browser.getPaths().get("/api/v1/task-drafts/{draftId}/revisions").getParameters())
                .extracting(parameter -> parameter.getName())
                .contains("draftId", "Idempotency-Key");
        assertThat(browser.getComponents().getSchemas().get("CreateTaskDraftRequest").getProperties())
                .containsKeys("conversationId", "taskType", "spaceId", "productId", "sourceMessageId", "parameters")
                .doesNotContainKeys("userId", "role", "price", "quotedPoints", "provider", "model");
        assertThat(browser.getComponents().getSchemas().get("TaskDraft").getProperties())
                .containsKeys("previousDraftId", "draftSource", "parameterSchemaVersion",
                        "sourceMode", "referenceAssetIds");
        assertThat(((Schema<?>) browser.getComponents().getSchemas().get("TaskDraft")
                .getProperties().get("sourceMode")).getEnum().toString())
                .contains("PREVIOUS_IMAGE_RESULT", "PREVIOUS_USER_ATTACHMENT");
        assertResponseCodes(browser.getPaths().get("/api/v1/tasks").getGet(), "200", "422");
        assertThat(browser.getPaths().get("/api/v1/tasks").getGet().getParameters())
                .extracting(parameter -> parameter.getName()).contains("conversationId");
        assertResponseCodes(browser.getPaths().get("/api/v1/tasks/{taskId}/events").getGet(),
                "200", "404", "422");
        assertThat(browser.getComponents().getSchemas().get("PaidTaskEvent").getProperties())
                .containsKeys("sequence", "eventType", "status", "stageCode", "errorCode")
                .doesNotContainKeys("payload", "details", "providerRequestId", "providerTaskId",
                        "executionToken", "objectKey", "providerUrl");
        assertThat(browser.getPaths().get("/api/v1/tasks/{taskId}").getPost()).isNull();
        assertThat(browser.getComponents().getSchemas().get("ImageTaskParameters").getProperties())
                .doesNotContainKeys("price", "quotedPoints", "pricingVersionId");
        Schema<?> paidTask = browser.getComponents().getSchemas().get("PaidTask");
        assertThat(paidTask.getProperties()).containsKeys("status", "stageCode", "errorCode", "result");
        assertThat(((Schema<?>) paidTask.getProperties().get("stageCode")).getDescription())
                .contains("PROVIDER_RESULT_UNKNOWN", "PROVIDER_RESULT_RECOVERY",
                        "PROVIDER_RESULT_CONFIRMATION_FAILED");
        Schema<?> paidResult = browser.getComponents().getSchemas().get("PaidTaskResult");
        assertThat(paidResult.getOneOf()).hasSize(7);
        assertProviderIdentity(browser.getComponents().getSchemas().get("MockImageTaskResult"),
                "LOCAL_TASK_PROVIDER", "deterministic-provider-mock-v1", true);
        Schema<?> generated = browser.getComponents().getSchemas().get("GeneratedImageAssetTaskResult");
        assertProviderIdentity((Schema<?>) generated.getAllOf().get(1).getOneOf().get(0), "QWEN_IMAGE", "qwen-image-3.0", null);
        assertProviderIdentity((Schema<?>) generated.getAllOf().get(1).getOneOf().get(1), "WAN_IMAGE_EDIT", "wanx2.1-imageedit", null);
        assertThat(generated.getRequired()).contains("providerCostReported");
        var imageResultVariants = generated.getAllOf().getFirst().getAnyOf();
        assertThat(imageResultVariants).hasSize(2);
        assertThat(((Schema<?>) imageResultVariants.get(0)).getRequired()).containsExactly("asset");
        assertThat(((Schema<?>) imageResultVariants.get(1)).getRequired())
                .containsExactlyInAnyOrder("assets", "requestedCount", "succeededCount", "failedCount");
        assertThat(((Schema<?>) generated.getProperties().get("assets")).getMaxItems()).isEqualTo(4);
        Schema<?> imageCount = (Schema<?>) browser.getComponents().getSchemas()
                .get("ImageTaskParameters").getProperties().get("count");
        assertThat(imageCount.getMinimum()).isEqualByComparingTo("1");
        assertThat(imageCount.getMaximum()).isEqualByComparingTo("4");
        assertThat(generated.getProperties()).doesNotContainKeys("providerUrl", "artifacts", "objectKey");
        Schema<?> generatedVideo = browser.getComponents().getSchemas().get("GeneratedVideoAssetTaskResult");
        assertProviderIdentity(generatedVideo, "WAN_VIDEO", "wan3.0-video", null);
        assertThat(generatedVideo.getProperties()).doesNotContainKeys(
                "providerUrl", "providerRequestId", "providerTaskId", "objectKey");
        Schema<?> videoParameters=browser.getComponents().getSchemas().get("VideoTaskParameters");
        assertThat(videoParameters.getRequired()).contains("scene","durationSeconds","aspectRatio",
                "resolution","audio","seed","promptExtend","watermark");
        assertThat(videoParameters.getProperties()).doesNotContainKeys(
                "negativePrompt","referenceAssetId","provider","model","quotedPoints");
        assertThat(((Schema<?>) browser.getComponents().getSchemas().get("ImageTaskParameters")
                .getProperties().get("sourceMode")).getEnum().toString())
                .contains("PREVIOUS_IMAGE_RESULT", "PREVIOUS_USER_ATTACHMENT");
        Schema<?> imageParameters = browser.getComponents().getSchemas().get("ImageTaskParameters");
        List<Schema> imageSourceVariants = imageParameters.getAllOf().getFirst().getOneOf();
        Schema<?> previousImageVariant = imageSourceVariants.stream()
                .filter(variant -> "PREVIOUS_IMAGE_RESULT".equals(((Schema<?>) variant.getProperties()
                        .get("sourceMode")).getConst()))
                .findFirst().orElseThrow();
        Schema<?> previousImageReferences = (Schema<?>) previousImageVariant.getProperties()
                .get("referenceAssetIds");
        assertThat(previousImageReferences.getMinItems()).isEqualTo(1);
        assertThat(previousImageReferences.getMaxItems()).isEqualTo(1);
        Schema<?> previousAttachmentVariant = imageSourceVariants.stream()
                .filter(variant -> "PREVIOUS_USER_ATTACHMENT".equals(((Schema<?>) variant.getProperties()
                        .get("sourceMode")).getConst()))
                .findFirst().orElseThrow();
        Schema<?> previousAttachmentReferences = (Schema<?>) previousAttachmentVariant.getProperties()
                .get("referenceAssetIds");
        assertThat(previousAttachmentReferences.getMinItems()).isEqualTo(1);
        assertThat(previousAttachmentReferences.getMaxItems()).isEqualTo(1);
        Schema<?> ordinaryReferenceVariant = imageSourceVariants.stream()
                .filter(variant -> {
                    Schema<?> sourceMode = (Schema<?>) variant.getProperties().get("sourceMode");
                    return sourceMode != null && sourceMode.getEnum() != null
                            && sourceMode.getEnum().toString().contains("CATALOG_PRODUCT");
                })
                .findFirst().orElseThrow();
        assertThat(((Schema<?>) ordinaryReferenceVariant.getProperties().get("sourceMode")).getEnum().toString())
                .doesNotContain("PREVIOUS_IMAGE_RESULT", "PREVIOUS_USER_ATTACHMENT");
        Schema<?> ordinaryReferences = (Schema<?>) ordinaryReferenceVariant.getProperties()
                .get("referenceAssetIds");
        assertThat(ordinaryReferences.getMinItems()).isEqualTo(1);
        assertThat(ordinaryReferences.getMaxItems()).isEqualTo(3);
        for (String requestSchemaName : List.of(
                "ImageTaskRequestParameters", "VideoTaskRequestParameters")) {
            Schema<?> requestSchema = browser.getComponents().getSchemas().get(requestSchemaName);
            assertThat(requestSchema.getDescription())
                    .contains("FORM", "PREVIOUS_IMAGE_RESULT", "PREVIOUS_USER_ATTACHMENT");
            Schema<?> requestSourceMode = (Schema<?>) requestSchema.getAllOf().get(1)
                    .getProperties().get("sourceMode");
            assertThat(requestSourceMode.getEnum()).extracting(Object::toString)
                    .doesNotContain("PREVIOUS_IMAGE_RESULT", "PREVIOUS_USER_ATTACHMENT");
        }
        for (String requestSchemaName : List.of("CreateTaskDraftRequest", "ReviseTaskDraftRequest")) {
            Schema<?> requestParameters = (Schema<?>) browser.getComponents().getSchemas()
                    .get(requestSchemaName).getProperties().get("parameters");
            assertThat(requestParameters.getOneOf().subList(0, 2))
                    .allSatisfy(variant -> assertThat(variant.getDescription())
                            .contains("FORM", "PREVIOUS_IMAGE_RESULT", "PREVIOUS_USER_ATTACHMENT"));
        }
        Schema<?> responseParameters = (Schema<?>) browser.getComponents().getSchemas().get("TaskDraft")
                .getProperties().get("parameters");
        assertThat(responseParameters.getOneOf().subList(0, 2))
                .allSatisfy(variant -> assertThat(((Schema<?>) variant.getProperties().get("sourceMode"))
                        .getEnum().toString()).contains("PREVIOUS_IMAGE_RESULT", "PREVIOUS_USER_ATTACHMENT"));
        assertThat(((Schema<?>) videoParameters.getProperties().get("sourceMode")).getEnum().toString())
                .contains("PREVIOUS_IMAGE_RESULT", "PREVIOUS_USER_ATTACHMENT");
        Schema<?> assetReference = browser.getComponents().getSchemas().get("PaidTaskAssetReference");
        assertThat(assetReference.getRequired()).contains("assetId", "status", "contentType", "sha256");
        assertThat(assetReference.getProperties()).doesNotContainKeys("providerUrl", "objectKey");
        assertThat(((Schema<?>) assetReference.getProperties().get("safetyStatus")).getEnum())
                .extracting(Object::toString)
                .containsExactly("APPROVED", "TEST_BYPASS", "NOT_REVIEWED");
        for (String schemaName : List.of("PaidVideoTaskAssetReference", "AssetSummary",
                "UploadedImageAsset", "Asset")) {
            Schema<?> status = (Schema<?>) browser.getComponents().getSchemas().get(schemaName)
                    .getProperties().get("safetyStatus");
            assertThat(status.getEnum()).as(schemaName + ".safetyStatus")
                    .extracting(Object::toString).contains("NOT_REVIEWED");
        }
        for (String path : List.of("/api/v1/assets", "/api/v1/assets/{assetId}",
                "/api/v1/assets/{assetId}/download-url")) {
            Operation operation = browser.getPaths().get(path).getGet();
            assertThat(operation.getSecurity()).isNotEmpty();
            assertResponseCodes(operation, "200", "401", "403", "422");
        }
        Schema<?> asset = browser.getComponents().getSchemas().get("AssetSummary");
        assertThat(asset.getProperties()).containsKeys("id", "spaceId", "sourceTaskId", "safetyStatus");
        assertThat(asset.getProperties()).doesNotContainKeys("objectKey", "providerUrl");
        Schema<?> assetDetail = browser.getComponents().getSchemas().get("Asset");
        assertThat(assetDetail.getRequired()).contains("productIds", "contentType", "sha256");
        assertThat(assetDetail.getProperties()).containsKeys("productIds", "primaryProductId");
        assertThat(assetDetail.getProperties()).doesNotContainKeys("objectKey", "providerUrl");

        for (String path : List.of(
                "/api/v1/geo/topics/{topicId}/keywords",
                "/api/v1/geo/topics/{topicId}/questions",
                "/api/v1/geo/topics/{topicId}/competitors")) {
            Operation operation = browser.getPaths().get(path).getPut();
            assertThat(operation.getSecurity()).isNotEmpty();
            assertResponseCodes(operation, "200", "401", "403", "404", "409", "422");
        }
        Operation createGeoTopic = browser.getPaths().get("/api/v1/geo/topics").getPost();
        assertResponseCodes(createGeoTopic, "201", "401", "403", "404", "409", "422");
        Operation listGeoTopics = browser.getPaths().get("/api/v1/geo/topics").getGet();
        assertResponseCodes(listGeoTopics, "200", "401", "403", "404", "422");
        assertThat(listGeoTopics.getParameters()).extracting(parameter -> parameter.getName())
                .containsExactly("spaceId", "productId", "keyword", "page", "size");
        Schema<?> createGeo = browser.getComponents().getSchemas().get("CreateGeoTopicRequest");
        assertThat(createGeo.getRequired()).containsExactlyInAnyOrder("spaceId", "name");
        assertThat(createGeo.getProperties()).doesNotContainKeys("userId", "role", "provider", "model");
        Schema<?> keywordInput = browser.getComponents().getSchemas().get("GeoKeywordInput");
        assertThat(((Schema<?>) keywordInput.getProperties().get("type")).getEnum())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("CORE", "DISTILLED", "BRAND", "SCENE");
        Schema<?> questionInput = browser.getComponents().getSchemas().get("GeoQuestionInput");
        assertThat(((Schema<?>) questionInput.getProperties().get("category")).getConst())
                .isEqualTo("BRAND_DISCOVERY");
        assertThat(((Schema<?>) questionInput.getProperties().get("source")).getConst())
                .isEqualTo("MANUAL");
        Schema<?> geoTopic = browser.getComponents().getSchemas().get("GeoTopic");
        assertThat(geoTopic.getRequired()).contains("keywords", "questions", "competitorIds",
                "competitorSetVersion", "version");
        assertThat(geoTopic.getProperties()).doesNotContainKeys(
                "userId", "normalizedName", "provider", "platformStatus");
        for (String path : List.of("/api/v1/geo/platforms", "/api/v1/geo/runs",
                "/api/v1/geo/runs/{runId}", "/api/v1/geo/reports",
                "/api/v1/geo/reports/{reportId}")) {
            Operation operation = browser.getPaths().get(path).getGet();
            assertThat(operation.getSecurity()).isNotEmpty();
            assertResponseCodes(operation, "200", "401", "403");
        }
        Operation runProgress = browser.getPaths().get("/api/v1/geo/runs/{runId}/progress").getGet();
        assertThat(runProgress.getSecurity()).isNotEmpty();
        assertResponseCodes(runProgress, "200", "401", "403", "404", "422");
        Schema<?> geoRunProgress = browser.getComponents().getSchemas().get("GeoRunProgress");
        assertThat(geoRunProgress.getRequired()).containsExactlyInAnyOrder(
                "runId", "taskId", "status", "stageCode", "percent", "completedUnits",
                "totalUnits", "succeededUnits", "failedUnits", "missingUnits", "reportId",
                "terminal", "retryable", "updatedAt", "finishedAt");
        Schema<?> geoRecommendation = browser.getComponents().getSchemas().get("GeoRecommendation");
        assertThat(geoRecommendation.getRequired()).containsExactlyInAnyOrder(
                "id", "priority", "title", "rationale");
        Operation articleWorkflow = browser.getPaths().get("/api/v1/geo/reports/{reportId}/article-workflows").getPost();
        assertThat(articleWorkflow.getSecurity()).isNotEmpty();
        assertResponseCodes(articleWorkflow, "200", "201", "401", "403", "404", "409", "422");
        for (String action : List.of("archive", "restore")) {
            Operation updateArchive = browser.getPaths()
                    .get("/api/v1/geo/reports/{reportId}/" + action).getPost();
            assertThat(updateArchive.getSecurity()).isNotEmpty();
            assertResponseCodes(updateArchive, "200", "401", "403", "404", "422");
        }
        Schema<?> geoParameters = browser.getComponents().getSchemas().get("GeoTaskParameters");
        assertThat(geoParameters.getRequired()).containsExactlyInAnyOrder(
                "mode", "topicId", "questionIds", "platformCodes", "sampleMode", "region");
        assertThat(geoParameters.getProperties()).doesNotContainKeys(
                "userId", "quotedPoints", "unitPoints", "provider", "model");
        Schema<?> geoReport = browser.getComponents().getSchemas().get("GeoReport");
        assertThat(geoReport).isNotNull();
        assertThat(geoReport.getAllOf().get(1).getRequired()).contains("visibility", "interpretation");
        Operation reinterpretation = browser.getPaths().get("/api/v1/geo/reports/{reportId}/reinterpretation").getPost();
        assertThat(reinterpretation.getSecurity()).isNotEmpty();
        assertThat(reinterpretation.getRequestBody()).isNull();
        assertResponseCodes(reinterpretation, "200", "401", "403", "404", "409", "422", "429", "503");
        Schema<?> interpretation = browser.getComponents().getSchemas().get("GeoReportInterpretation");
        assertAdditionalPropertiesFalse(interpretation);
        assertThat(interpretation.getRequired()).containsExactlyInAnyOrder("status", "canRetry", "attempts",
                "maxAttempts", "requestedAt", "finishedAt", "failureCode", "unavailableReason", "retryAfter");
        assertThat(interpretation.getProperties()).doesNotContainKeys("traceId", "executionToken", "userId");
        Schema<?> visibility = browser.getComponents().getSchemas().get("GeoVisibility");
        assertThat(visibility.getRequired()).containsExactlyInAnyOrder(
                "methodVersion", "assessmentStatus", "unavailableReason", "target", "platforms", "samples");
        assertThat(visibility.getProperties()).doesNotContainKeys("score", "recommendationRate", "marketShare");
        assertThat(browser.getComponents().getSchemas().get("GeoVisibilityPlatform").getRequired())
                .containsExactlyInAnyOrder("platformCode", "expectedSamples", "validSamples", "mentionedSamples",
                        "failedSamples", "missingSamples", "mentionRate");
        assertAdditionalPropertiesFalse(browser.getComponents().getSchemas().get("CommerceImageVisualBrief"));
        assertThat(browser.getComponents().getSchemas().get("CommerceImageVisualBrief").getRequired())
                .containsExactlyInAnyOrder("version", "platform", "placement", "visualFocus", "composition",
                        "background", "lighting", "textPolicy", "copyLines");
        assertThat(browser.getComponents().getSchemas().get("GeoObservation").getProperties())
                .doesNotContainKeys("userId", "executionToken", "authorization", "cookie");
        Operation createPlanDraft = browser.getPaths().get("/api/v1/geo/plan-drafts").getPost();
        Operation confirmPlan = browser.getPaths().get("/api/v1/geo/plans").getPost();
        assertResponseCodes(createPlanDraft, "201", "401", "403", "404", "409", "422", "503");
        assertResponseCodes(confirmPlan, "201", "401", "403", "404", "409", "410", "422", "503");
        assertThat(createPlanDraft.getParameters()).extracting(parameter -> parameter.getName())
                .contains("Idempotency-Key");
        assertThat(confirmPlan.getParameters()).extracting(parameter -> parameter.getName())
                .contains("Idempotency-Key");
        Schema<?> createPlan = browser.getComponents().getSchemas().get("CreateGeoPlanDraftRequest");
        assertThat(createPlan.getRequired()).containsExactlyInAnyOrder(
                "topicId", "name", "schedule", "questionIds", "platformCodes",
                "sampleMode", "maxPointsPerRun");
        assertThat(createPlan.getProperties()).doesNotContainKeys(
                "userId", "quotedPoints", "unitPoints", "provider", "model", "cron");
        Schema<?> planSchedule = browser.getComponents().getSchemas().get("GeoPlanSchedule");
        assertThat(planSchedule.getOneOf()).hasSize(4);
        assertThat(planSchedule.getOneOf()).allSatisfy(this::assertAdditionalPropertiesFalse);
        assertThat(planSchedule.getOneOf()).extracting(schedule ->
                ((Schema<?>) schedule.getProperties().get("type")).getConst().toString())
                .containsExactlyInAnyOrder("HOURLY", "DAILY", "WEEKLY", "INTERVAL_DAYS");
        Schema<?> hourly = planSchedule.getOneOf().stream().filter(schedule ->
                "HOURLY".equals(((Schema<?>) schedule.getProperties().get("type")).getConst()))
                .findFirst().orElseThrow();
        assertThat(hourly.getRequired()).containsExactlyInAnyOrder("type", "timeZone", "localTime");
        assertThat(((Schema<?>) hourly.getProperties().get("localTime")).getPattern()).isEqualTo("^00:[0-5][0-9]$");
        Schema<?> updatePlan = browser.getComponents().getSchemas().get("UpdateGeoPlanRequest");
        assertThat(updatePlan.getOneOf()).hasSize(2);
        assertThat(updatePlan.getOneOf().get(1).getRequired()).contains(
                "version", "draftId", "inputHash", "pricingVersionId", "confirmationToken");
        for (String path : List.of("/api/v1/geo/plans/{planId}/pause",
                "/api/v1/geo/plans/{planId}/resume")) {
            Operation operation = browser.getPaths().get(path).getPost();
            assertThat(operation.getSecurity()).isNotEmpty();
            assertResponseCodes(operation, "200", "401", "403", "404", "409", "422");
        }

        Operation mediaResources = browser.getPaths().get("/api/v1/media/resources").getGet();
        Operation mediaResource = browser.getPaths().get("/api/v1/media/resources/{resourceId}").getGet();
        Operation mediaRecommendations = browser.getPaths().get("/api/v1/media/recommendations").getGet();
        Operation mediaQuote = browser.getPaths().get("/api/v1/media/quotes").getPost();
        Operation mediaSubmissions = browser.getPaths().get("/api/v1/media/submissions").getGet();
        Operation mediaSubmission = browser.getPaths().get("/api/v1/media/submissions/{submissionId}").getGet();
        assertResponseCodes(mediaResources, "200", "401", "403", "422");
        assertResponseCodes(mediaResource, "200", "401", "403", "404", "422");
        assertResponseCodes(mediaRecommendations, "200", "401", "403", "404", "409", "422");
        assertResponseCodes(mediaQuote, "201", "401", "403", "404", "409", "422", "503");
        assertResponseCodes(mediaSubmissions, "200", "401", "403", "422");
        assertThat(mediaSubmissions.getParameters()).extracting(parameter -> parameter.getName())
                .contains("taskId");
        assertResponseCodes(mediaSubmission, "200", "401", "403", "404", "422");
        assertThat(mediaQuote.getParameters()).extracting(parameter -> parameter.getName())
                .containsExactly("Idempotency-Key");
        assertThat(mediaQuote.getParameters().getFirst().getRequired()).isTrue();
        Schema<?> mediaQuoteRequest = browser.getComponents().getSchemas().get("CreateMediaQuoteRequest");
        assertThat(mediaQuoteRequest.getRequired()).containsExactlyInAnyOrder(
                "articleId", "articleSnapshotId", "items");
        assertAdditionalPropertiesFalse(mediaQuoteRequest);
        assertThat(mediaQuoteRequest.getProperties()).doesNotContainKeys(
                "userId", "role", "price", "upstreamPrice", "markupRate", "quotedPoints",
                "provider", "model");
        Schema<?> mediaResourceSchema = browser.getComponents().getSchemas().get("MediaResource");
        assertBusinessId((Schema<?>) mediaResourceSchema.getProperties().get("id"));
        assertThat(mediaResourceSchema.getProperties()).containsKeys(
                "saleAmountCents", "chargedPoints", "quoteExpiresAt");
        assertThat(mediaResourceSchema.getProperties()).doesNotContainKeys("upstreamPrice", "markupRate");
        Schema<?> mediaRecommendation = browser.getComponents().getSchemas().get("MediaRecommendation");
        assertThat(mediaRecommendation.getRequired()).containsExactlyInAnyOrder(
                "rank", "mediaResourceId", "reasonCode", "reason", "score", "evidence", "resource");
        assertBusinessId((Schema<?>) mediaRecommendation.getProperties().get("mediaResourceId"));
        Schema<?> mediaSubmissionSchema = browser.getComponents().getSchemas().get("MediaSubmissionSummary");
        for (String id : List.of("id", "batchId", "taskId", "articleId", "articleSnapshotId",
                "mediaResourceId")) {
            assertBusinessId((Schema<?>) mediaSubmissionSchema.getProperties().get(id));
        }
        assertThat(mediaSubmissionSchema.getRequired()).contains("publishedUrls");
        assertThat(mediaSubmissionSchema.getProperties()).containsKeys("publishedUrl", "publishedUrls");
        assertThat(mediaSubmissionSchema.getProperties()).doesNotContainKeys(
                "customerOrderId", "upstreamOrderId", "upstreamOrderNo", "upstreamPrice",
                "providerPayload", "requestPayload");
        Schema<?> genericDraft = browser.getComponents().getSchemas().get("CreateTaskDraftRequest");
        assertThat(((Schema<?>) genericDraft.getProperties().get("taskType")).getEnum())
                .extracting(Object::toString).doesNotContain("MEDIA");

        // The operator media console. Read-only in this version, so no route under it may mutate —
        // and the tenant-facing MEDIA_ENABLED switch does not govern it, because reconciling the
        // submissions already in flight is exactly what an operator needs once tenant submission is
        // switched off.
        for (String path : browser.getPaths().keySet().stream()
                .filter(path -> path.startsWith("/api/v1/admin/media/")).toList()) {
            assertThat(browser.getPaths().get(path).readOperations()).as(path).allSatisfy(operation ->
                    assertThat(operation.getSecurity()).isNotEmpty());
            assertThat(browser.getPaths().get(path).getPost()).as(path).isNull();
            assertThat(browser.getPaths().get(path).getPut()).as(path).isNull();
            assertThat(browser.getPaths().get(path).getPatch()).as(path).isNull();
            assertThat(browser.getPaths().get(path).getDelete()).as(path).isNull();
        }
        Operation adminMediaOverview = browser.getPaths().get("/api/v1/admin/media/overview").getGet();
        assertResponseCodes(adminMediaOverview, "200", "401", "403");
        assertResponseCodes(browser.getPaths().get("/api/v1/admin/media/sync-states").getGet(),
                "200", "401", "403");
        assertResponseCodes(browser.getPaths().get("/api/v1/admin/media/resources").getGet(),
                "200", "401", "403", "422");
        assertResponseCodes(browser.getPaths().get("/api/v1/admin/media/resources/{resourceId}").getGet(),
                "200", "401", "403", "404", "422");
        assertResponseCodes(browser.getPaths().get("/api/v1/admin/media/callbacks").getGet(),
                "200", "401", "403", "422");
        assertResponseCodes(browser.getPaths().get("/api/v1/admin/media/callbacks/{callbackId}").getGet(),
                "200", "401", "403", "404", "422");
        Schema<?> adminMediaOverviewSchema = browser.getComponents().getSchemas().get("AdminMediaOverview");
        assertAdditionalPropertiesFalse(adminMediaOverviewSchema);
        assertThat(adminMediaOverviewSchema.getProperties()).containsOnlyKeys(
                "activeResources", "pendingSubmissions", "exceptionSubmissions", "walletBalanceCents");
        // Null until a provider sync reports a balance: this surface never calls the upstream just
        // because a page was opened, and the schema must not let a screen imply it did.
        assertThat(((Schema<?>) adminMediaOverviewSchema.getProperties().get("walletBalanceCents"))
                .getTypes()).contains("null");

        Operation adminMediaSubmissions = browser.getPaths().get("/api/v1/admin/media/submissions").getGet();
        assertResponseCodes(adminMediaSubmissions, "200", "401", "403", "422");
        assertThat(adminMediaSubmissions.getParameters()).extracting(parameter -> parameter.getName())
                .containsExactly("status", "group", "page", "size");
        assertThat(((Schema<?>) adminMediaSubmissions.getParameters().stream()
                .filter(parameter -> "group".equals(parameter.getName()))
                .findFirst().orElseThrow().getSchema()).getEnum())
                .extracting(Object::toString).containsExactly("PENDING", "EXCEPTION");
        assertResponseCodes(browser.getPaths().get("/api/v1/admin/media/submissions/{submissionId}").getGet(),
                "200", "401", "403", "404", "422");
        // All eight local statuses reach the browser; none of them is collapsed into another. The
        // console filter would otherwise be unable to tell ACCEPTED from SUBMIT_UNKNOWN.
        assertThat(browser.getComponents().getSchemas().get("MediaSubmissionStatus").getEnum())
                .extracting(Object::toString).containsExactly("QUEUED", "SUBMITTING", "SUBMIT_UNKNOWN",
                        "ACCEPTED", "PUBLISHED", "REJECTED", "RECONCILING", "FAILED");

        Schema<?> adminResource = browser.getComponents().getSchemas().get("AdminMediaResource");
        assertAdditionalPropertiesFalse(adminResource);
        for (String id : List.of("id")) {
            assertBusinessId((Schema<?>) adminResource.getProperties().get(id));
        }
        assertThat(adminResource.getProperties()).containsKeys("upstreamPrice", "markupRate",
                "referenceSaleAmountCents", "referenceChargedPoints");
        // The purchase price is numeric(12,4) yuan, not cents: it stays an exact decimal string rather
        // than being rounded into a number on the way to the browser.
        assertThat(((Schema<?>) adminResource.getProperties().get("upstreamPrice")).getTypes())
                .containsExactly("string");

        Schema<?> adminSubmission = browser.getComponents().getSchemas().get("AdminMediaSubmissionSummary");
        assertAdditionalPropertiesFalse(adminSubmission);
        for (String id : List.of("id", "batchId", "taskId", "userId", "mediaResourceId")) {
            assertBusinessId((Schema<?>) adminSubmission.getProperties().get(id));
        }
        assertThat(adminSubmission.getRequired()).contains("publishedUrls");
        // The operator view adds exactly what the tenant view hides — the account, the upstream
        // handles and the frozen price — and still carries no credential and no raw body.
        assertThat(adminSubmission.getProperties()).containsKeys("providerCode", "upstreamModelId",
                "upstreamResourceId", "customerOrderId", "upstreamOrderId", "upstreamOrderNo",
                "upstreamStatus", "upstreamPrice", "markupRate", "submissionState", "status");
        assertThat(adminSubmission.getProperties()).doesNotContainKeys("rawJson", "payloadHash", "payload",
                "providerPayload", "requestPayload", "responsePayload", "rawBody", "appKey", "role");
        assertThat(((Schema<?>) adminSubmission.getProperties().get("submissionState")).getEnum())
                .extracting(Object::toString).containsExactly("NOT_SUBMITTED", "CONFIRMED", "UNKNOWN");
        Schema<?> adminSubmissionDetail = browser.getComponents().getSchemas().get("AdminMediaSubmission");
        assertThat(adminSubmissionDetail.getAllOf().get(1).getProperties()).containsOnlyKeys("events");

        Schema<?> adminCallback = browser.getComponents().getSchemas().get("AdminMediaCallback");
        assertAdditionalPropertiesFalse(adminCallback);
        assertThat(adminCallback.getProperties()).containsKeys("id", "eventType", "status", "attempts",
                "receivedAt", "processedAt");
        // The digest is derived from an unredacted upstream body and is not published: the receipt id
        // is the handle operators get instead.
        assertThat(adminCallback.getProperties()).doesNotContainKeys("payloadHash", "payload", "body",
                "rawBody", "requestPayload");
        assertThat(((Schema<?>) adminCallback.getProperties().get("status")).getEnum())
                .extracting(Object::toString).containsExactly("RECEIVED", "PROCESSED", "FAILED");

        Schema<?> adminSyncState = browser.getComponents().getSchemas().get("AdminMediaSyncState");
        assertAdditionalPropertiesFalse(adminSyncState);
        // No batch, operator or per-run counters: nothing records them, so the schema cannot offer
        // them and the console cannot render work that never happened.
        assertThat(adminSyncState.getProperties()).containsOnlyKeys("providerCode", "upstreamModelId",
                "status", "lastAttemptedAt", "lastSucceededAt", "lastErrorCode", "lastResourceCount",
                "version", "updatedAt");
        assertThat(((Schema<?>) adminSyncState.getProperties().get("status")).getEnum())
                .extracting(Object::toString).containsExactly("IDLE", "RUNNING", "SUCCEEDED", "FAILED");

        // Agent versions are evidence, not a deployment control plane. The current value is proven
        // only by signed runtime capabilities; database history is labelled OBSERVED, and missing
        // evaluation publication is a first-class state instead of a fabricated zero score.
        for (String path : browser.getPaths().keySet().stream()
                .filter(path -> path.startsWith("/api/v1/admin/agent-versions")).toList()) {
            assertThat(browser.getPaths().get(path).readOperations()).as(path).allSatisfy(operation ->
                    assertThat(operation.getSecurity()).isNotEmpty());
            assertThat(browser.getPaths().get(path).getPost()).as(path).isNull();
            assertThat(browser.getPaths().get(path).getPut()).as(path).isNull();
            assertThat(browser.getPaths().get(path).getPatch()).as(path).isNull();
            assertThat(browser.getPaths().get(path).getDelete()).as(path).isNull();
        }
        assertResponseCodes(browser.getPaths().get("/api/v1/admin/agent-versions").getGet(),
                "200", "401", "403", "422");
        for (String path : List.of("/api/v1/admin/agent-versions/{bundleVersion}",
                "/api/v1/admin/agent-versions/{bundleVersion}/runs",
                "/api/v1/admin/agent-versions/{bundleVersion}/evaluations")) {
            assertResponseCodes(browser.getPaths().get(path).getGet(),
                    "200", "401", "403", "404", "422");
        }
        assertThat(browser.getComponents().getSchemas().get("AgentRuntimeAvailability").getEnum())
                .extracting(Object::toString)
                .containsExactly("AVAILABLE", "UNAVAILABLE", "NOT_CONFIGURED");
        assertThat(browser.getComponents().getSchemas().get("AgentVersionStatus").getEnum())
                .extracting(Object::toString).containsExactly("CURRENT", "OBSERVED");
        assertThat(browser.getComponents().getSchemas().get("AgentEvaluationAvailability").getEnum())
                .extracting(Object::toString).containsExactly("NOT_REGISTERED");
        Schema<?> agentRuntime = browser.getComponents().getSchemas().get("AdminAgentRuntimeSnapshot");
        assertAdditionalPropertiesFalse(agentRuntime);
        assertThat(agentRuntime.getProperties()).containsOnlyKeys("availability", "reasonCode",
                "serviceVersion", "bundleVersion", "bundleHash", "promptHash", "gitCommit",
                "modelGateway", "profiles", "providers");
        assertThat(agentRuntime.getProperties()).doesNotContainKeys("prompt", "promptBody", "apiKey",
                "credential", "secret", "token");
        Schema<?> agentVersion = browser.getComponents().getSchemas().get("AdminAgentVersion");
        assertAdditionalPropertiesFalse(agentVersion);
        assertThat(agentVersion.getProperties()).containsOnlyKeys("version", "currentRuntime",
                "evaluationAvailability", "dataGaps");
        Schema<?> agentRun = browser.getComponents().getSchemas().get("AdminAgentVersionRun");
        assertAdditionalPropertiesFalse(agentRun);
        assertThat(agentRun.getProperties()).containsOnlyKeys("executionId", "source", "parentId",
                "taskType", "status", "stage", "provider", "model", "stableErrorCode",
                "startedAt", "finishedAt", "durationMs");
        assertThat(agentRun.getProperties()).doesNotContainKeys("userId", "executionTokenHash",
                "providerRequestId", "contextSnapshot", "payload");

        Operation diagnosticList = browser.getPaths().get("/api/v1/admin/diagnostics/tasks").getGet();
        Operation diagnosticDetail = browser.getPaths().get("/api/v1/admin/diagnostics/tasks/{taskId}").getGet();
        assertThat(diagnosticList.getSecurity()).isNotEmpty();
        assertThat(diagnosticDetail.getSecurity()).isNotEmpty();
        assertResponseCodes(diagnosticList, "200", "401", "403", "422");
        assertResponseCodes(diagnosticDetail, "200", "401", "403", "404", "422");
        assertThat(diagnosticList.getParameters()).extracting(parameter -> parameter.getName())
                .containsExactly("taskId", "taskType", "taskStatus", "stableErrorCode",
                        "traceId", "from", "to", "page", "size");
        assertThat(browser.getPaths().get("/api/v1/admin/diagnostics/tasks/{taskId}").getParameters())
                .extracting(parameter -> parameter.getName())
                .containsExactly("taskId");
        assertThat(browser.getComponents().getSchemas().get("DiagnosticFailureCategory").getEnum())
                .extracting(Object::toString).containsExactly(
                        "MODEL", "CONTEXT", "TOOL_POLICY", "WORKER", "MESSAGING", "PROVIDER",
                        "ASSET_INGESTION", "BILLING", "MEDIA_SUBMISSION", "UNKNOWN");
        assertThat(browser.getComponents().getSchemas().get("DiagnosticRetryability").getEnum())
                .extracting(Object::toString).containsExactly(
                        "NEVER", "SAFE_RETRY", "IDEMPOTENT_RETRY", "RECOVERY_ONLY",
                        "MANUAL_RECONCILIATION", "UNKNOWN");
        for (String schemaName : List.of("TaskDiagnosticSummary", "TaskDiagnostic",
                "TaskDiagnosticExecution", "TaskDiagnosticOutbox", "TaskDiagnosticInbox",
                "TaskDiagnosticAssetIngestion", "TaskDiagnosticMediaSubmission")) {
            Schema<?> schema = browser.getComponents().getSchemas().get(schemaName);
            assertAdditionalPropertiesFalse(schema);
            assertThat(schema.getProperties()).as(schemaName).doesNotContainKeys(
                    "userId", "body", "prompt", "context", "jwt", "authorization",
                    "hmacSecret", "providerRequestId", "providerTaskId", "providerUrl",
                    "objectKey", "executionToken", "sql", "exception", "payload");
        }
        assertBusinessId((Schema<?>) browser.getComponents().getSchemas()
                .get("TaskDiagnostic").getProperties().get("taskId"));

        Operation runList = browser.getPaths().get("/api/v1/admin/diagnostics/runs").getGet();
        Operation runDetail = browser.getPaths().get("/api/v1/admin/diagnostics/runs/{runId}").getGet();
        assertThat(runList.getSecurity()).isNotEmpty();
        assertThat(runDetail.getSecurity()).isNotEmpty();
        assertResponseCodes(runList, "200", "401", "403", "422");
        assertResponseCodes(runDetail, "200", "401", "403", "404", "422");
        assertThat(runList.getParameters()).extracting(parameter -> parameter.getName())
                .containsExactly("runId", "conversationId", "status", "stableErrorCode",
                        "traceId", "from", "to", "page", "size");
        assertThat(browser.getPaths().get("/api/v1/admin/diagnostics/runs/{runId}").getParameters())
                .extracting(parameter -> parameter.getName()).containsExactly("runId");
        Schema<?> runIdSchema = browser.getComponents().getSchemas().get("AgentRunId");
        assertThat(runIdSchema.getTypes()).containsExactly("string");
        assertThat(runIdSchema.getFormat()).isEqualTo("uuid");
        assertThat(runIdSchema.getPattern()).isEqualTo(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
        for (String schemaName : List.of("RunDiagnosticSummary", "RunDiagnosticUsage",
                "RunDiagnosticContextBudget", "RunDiagnosticTimelineEntry", "RunDiagnostic")) {
            Schema<?> schema = browser.getComponents().getSchemas().get(schemaName);
            assertAdditionalPropertiesFalse(schema);
            assertThat(schema.getProperties()).as(schemaName).doesNotContainKeys(
                    "userId", "messageContent", "body", "prompt", "contextSnapshot", "contextRef",
                    "jwt", "hmacSecret", "providerRequestId", "providerTaskId", "providerUrl",
                    "objectKey", "executionToken", "payload", "exception", "toolArguments", "toolOutput");
        }
        assertBusinessId((Schema<?>) browser.getComponents().getSchemas()
                .get("RunDiagnostic").getProperties().get("conversationId"));
        Operation plans = browser.getPaths().get("/api/v1/workflow-plans").getGet();
        Operation abandon = browser.getPaths().get("/api/v1/workflow-plans/{planId}/abandon").getPost();
        Operation confirmationPreview = browser.getPaths()
                .get("/api/v1/workflow-plans/{planId}/confirmation-preview").getPost();
        Operation workflowConfirm = browser.getPaths().get("/api/v1/workflow-plans/{planId}/confirm").getPost();
        assertResponseCodes(plans, "200", "401", "403", "422");
        assertResponseCodes(abandon, "200", "401", "403", "404", "409", "422");
        assertResponseCodes(confirmationPreview, "200", "401", "403", "404", "409", "410", "422");
        assertResponseCodes(workflowConfirm, "200", "401", "403", "404", "409", "410", "422");
        assertThat(browser.getPaths().get("/api/v1/workflow-plans/{planId}/confirm").getParameters())
                .extracting(parameter -> parameter.getName())
                .containsExactlyInAnyOrder("planId", "Idempotency-Key");
        Schema<?> workflow = browser.getComponents().getSchemas().get("WorkflowPlan");
        assertThat(workflow.toString()).doesNotContain("tokenHash", "contextRef", "parameters",
                "providerRequestId", "providerTaskId", "providerUrl", "objectKey", "prompt");
        Schema<?> preview = browser.getComponents().getSchemas().get("WorkflowConfirmationPreview");
        assertThat(((Schema<?>) preview.getProperties().get("consumptionEnabled")).getConst()).isEqualTo(false);
        Schema<?> authorization = browser.getComponents().getSchemas()
                .get("WorkflowConfirmationAuthorization");
        assertThat(authorization.getRequired()).contains("confirmationToken", "executionEnabled");
        assertThat(((Schema<?>) authorization.getProperties().get("executionEnabled")).getConst())
                .isEqualTo(false);
        Schema<?> confirmed = browser.getComponents().getSchemas().get("WorkflowPlanConfirmResponse");
        assertThat(((Schema<?>) confirmed.getProperties().get("status")).getConst()).isEqualTo("CONFIRMED");
        assertThat(((Schema<?>) confirmed.getProperties().get("executionEnabled")).getConst()).isEqualTo(false);
        assertThat(((Schema<?>) confirmed.getProperties().get("executionStarted")).getConst()).isEqualTo(false);
        assertThat(browser.getComponents().getSchemas().get("WorkflowPlanConfirmRequest").getProperties())
                .doesNotContainKeys("userId", "role", "price", "provider", "model");
        Operation workflowStart = browser.getPaths().get("/api/v1/workflow-plans/{planId}/start").getPost();
        assertResponseCodes(workflowStart, "200", "401", "403", "404", "409", "422", "503");
        assertThat(browser.getPaths().get("/api/v1/workflow-plans/{planId}/start").getParameters())
                .extracting(parameter -> parameter.getName()).containsExactlyInAnyOrder("planId", "Idempotency-Key");
        assertThat(browser.getComponents().getSchemas().get("WorkflowStartRequest").getProperties())
                .containsOnlyKeys("planVersion");
        assertThat(workflow.getAllOf().get(1).getProperties())
                .containsKeys("executionEnabled", "executionStarted", "lastStableError");
        Schema<?> workflowStep = browser.getComponents().getSchemas().get("WorkflowPlanStep");
        assertAdditionalPropertiesFalse(workflowStep);
        assertThat(workflowStep.getRequired()).contains("taskId", "taskStatus", "taskResultRef");
        assertThat(workflowStep.toString()).contains("asset", "geo-report", "article")
                .doesNotContain("providerUrl", "providerTaskId", "objectKey", "executionToken");
    }

    @Test
    void javaInternalContractPreservesHmacEnvelopeAndAddsWorkerControlPlane() throws Exception {
        OpenAPI internal = parse(JAVA_INTERNAL);
        Schema<?> geoAuthorized = internal.getComponents().getSchemas().get("GeoAuthorizedContext");
        assertThat(geoAuthorized.getRequired()).doesNotContain("monitoringTarget");
        Schema<?> monitoringTarget = (Schema<?>) geoAuthorized.getProperties().get("monitoringTarget");
        assertAdditionalPropertiesFalse(monitoringTarget);
        assertThat(monitoringTarget.getRequired()).containsExactlyInAnyOrder("kind", "name", "aliases");
        assertThat(monitoringTarget.getProperties()).doesNotContainKeys("userId", "productFacts", "topicName");

        assertThat(internal.getPaths().keySet()).containsExactlyInAnyOrder(
                "/internal/v1/health/ready",
                "/internal/v1/test/reverse-call",
                "/internal/v1/runs/{runId}/context",
                "/internal/v1/runs/{runId}/compaction/source",
                "/internal/v1/runs/{runId}/compaction/checkpoint",
                "/internal/v1/runs/{runId}/actions",
                "/internal/v1/runs/{runId}/resources/search",
                "/internal/v1/runs/{runId}/resources/read",
                "/internal/v1/runs/{runId}/workflow-plans",
                "/internal/v1/workflow-plans/{planId}/claim",
                "/internal/v1/workflow-plans/{planId}/execution-context",
                "/internal/v1/workflow-plans/{planId}/heartbeat",
                "/internal/v1/workflow-plans/{planId}/observations",
                "/internal/v1/workflow-plans/{planId}/steps/{stepId}/state",
                "/internal/v1/workflow-plans/{planId}/steps/{stepId}/task",
                "/internal/v1/workflow-plans/{planId}/replans",
                "/internal/v1/workflow-plans/{planId}/completion",
                "/internal/v1/tasks/{taskId}/claim",
                "/internal/v1/tasks/{taskId}/execution-context",
                "/internal/v1/tasks/{taskId}/heartbeat",
                "/internal/v1/tasks/{taskId}/execution-events",
                "/internal/v1/tasks/{taskId}/result-artifacts",
                "/internal/v1/tasks/{taskId}/geo-observations",
                "/internal/v1/tasks/{taskId}/geo-completion"
        );
        assertThat(internal.getPaths().get("/internal/v1/health/ready").getGet().getSecurity()).isEmpty();
        Operation reverseProbe = internal.getPaths().get("/internal/v1/test/reverse-call").getPost();
        assertThat(reverseProbe.getSecurity()).isNotEmpty();
        assertThat(reverseProbe.getRequestBody()).isNotNull();
        assertThat(reverseProbe.getParameters())
                .extracting(parameter -> parameter.getName())
                .contains(
                        "mode",
                        "X-Internal-Key-Id",
                        "X-Internal-Timestamp",
                        "X-Internal-Nonce",
                        "X-Content-SHA256",
                        "X-Internal-Signature",
                        "traceparent"
                );
        assertThat(reverseProbe.getParameters().stream()
                .filter(parameter -> "mode".equals(parameter.getName()))
                .findFirst()
                .orElseThrow()
                .getRequired()).isTrue();
        var internalNonce = internal.getComponents().getParameters().get("InternalNonce");
        assertThat(internalNonce.getDescription()).contains("lowercase UUID");
        assertThat(internalNonce.getSchema().getPattern())
                .isEqualTo("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        assertThat(internal.getComponents().getSecuritySchemes().get("InternalHmac").getDescription())
                .contains("exactly once", "repeated", "before nonce claim");
        assertThat(internal.getComponents().getSchemas().get("JavaReadiness").getProperties().keySet())
                .contains("codeCapabilitiesReady", "testServerDeployed", "m0GateStatus");
        Operation workflowProposal = internal.getPaths()
                .get("/internal/v1/runs/{runId}/workflow-plans").getPost();
        assertResponseCodes(workflowProposal, "201", "401", "404", "409", "422", "503");
        assertThat(workflowProposal.getParameters()).extracting(parameter -> parameter.getName())
                .contains("runId", "X-User-Context-Ref", "X-Internal-Key-Id",
                        "X-Internal-Timestamp", "X-Internal-Nonce", "X-Content-SHA256",
                        "X-Internal-Signature", "traceparent");
        Schema<?> stepProposal = internal.getComponents().getSchemas().get("WorkflowPlanStepProposal");
        assertThat(((Schema<?>) stepProposal.getProperties().get("risk")).getEnum())
                .extracting(Object::toString).containsExactlyInAnyOrder("QUERY", "ANALYZE", "PREPARE");
        assertThat(stepProposal.toString()).doesNotContain("COMMIT");
        assertThat(internal.getComponents().getSchemas().get("WorkflowPlanProposalRequest").getProperties())
                .doesNotContainKeys("userId", "role", "price", "provider", "model", "confirmationToken");
        Operation workflowClaim = internal.getPaths()
                .get("/internal/v1/workflow-plans/{planId}/claim").getPost();
        assertResponseCodes(workflowClaim, "200", "401", "404", "409", "422", "503");
        String bundleVersionPattern = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$";
        Schema<?> workflowClaimRequest = internal.getComponents().getSchemas()
                .get("WorkflowExecutionClaimRequest");
        assertThat(((Schema<?>) workflowClaimRequest.getProperties()
                .get("supportedBundleVersion")).getPattern()).isEqualTo(bundleVersionPattern);
        Schema<?> workflowContext = internal.getComponents().getSchemas().get("WorkflowExecutionContext");
        assertThat(workflowContext.getRequired()).contains("plan", "steps", "observations", "budget", "resumeOnly",
                "authorizedContext", "authorizedContextSchemaVersion", "authorizedContextHash",
                "authorizedContextSource");
        assertAdditionalPropertiesFalse(workflowContext);
        Schema<?> authorizedRunContext = internal.getComponents().getSchemas().get("AuthorizedRunContext");
        assertAdditionalPropertiesFalse(authorizedRunContext);
        assertThat(authorizedRunContext.getRequired()).contains("space", "product", "competitors",
                "conversationMemory", "taskWorkspace", "contextBudget");
        assertThat(workflowContext.toString()).doesNotContain("userId", "executionToken", "contextRef", "prompt");
        Schema<?> workflowObservation = internal.getComponents().getSchemas().get("WorkflowObservationRequest");
        assertThat(workflowObservation.getAdditionalProperties()).isEqualTo(false);
        assertThat(workflowObservation.getRequired()).contains("eventId", "toolCallId", "stepAttempt", "facts");
        Schema<?> workflowReplan = internal.getComponents().getSchemas().get("WorkflowReplanRequest");
        assertThat(workflowReplan.getRequired()).contains("expectedPlanVersion", "newPlanVersion", "steps");
        Operation taskActivation = internal.getPaths()
                .get("/internal/v1/workflow-plans/{planId}/steps/{stepId}/task").getPost();
        assertResponseCodes(taskActivation, "200", "401", "404", "409", "422", "503");
        assertThat(taskActivation.getParameters()).extracting(parameter -> parameter.getName())
                .contains("planId", "stepId", "X-Internal-Key-Id", "X-Internal-Timestamp",
                        "X-Internal-Nonce", "X-Content-SHA256", "X-Internal-Signature", "traceparent");
        Schema<?> activationRequest = internal.getComponents().getSchemas().get("WorkflowTaskActivationRequest");
        assertAdditionalPropertiesFalse(activationRequest);
        assertThat(activationRequest.getRequired()).containsExactlyInAnyOrder(
                "eventId", "executionNo", "executionToken", "planVersion", "taskDraftId");
        assertThat(activationRequest.getProperties()).doesNotContainKeys(
                "price", "points", "provider", "model", "userId", "role", "confirmationToken");
        Schema<?> activationResponse = internal.getComponents().getSchemas().get("WorkflowTaskActivationResponse");
        assertAdditionalPropertiesFalse(activationResponse);
        assertThat(activationResponse.getRequired()).contains(
                "accepted", "disposition", "planId", "stepId", "taskId", "taskStatus",
                "stepStatus", "waitingForTask", "executionNo");
        Schema<?> executionContext = internal.getComponents().getSchemas().get("TaskExecutionContext");
        assertThat(executionContext.getRequired()).contains("resumeOnly", "providerContinuation",
                "referenceAssets").doesNotContain("productCreativeBrief");
        assertThat(executionContext.getOneOf()).hasSize(6);
        Schema<?> imageContext = executionContext.getOneOf().getFirst();
        assertThat(imageContext.getRequired()).containsExactlyInAnyOrder(
                "provider", "model", "productCreativeBrief");
        assertProviderIdentity(imageContext, "QWEN_IMAGE", "qwen-image-3.0", null);
        Schema<?> maskContext = executionContext.getOneOf().get(1);
        assertProviderIdentity(maskContext, "WAN_IMAGE_EDIT", "wanx2.1-imageedit", null);
        assertThat(maskContext.getRequired()).contains("controlledMaskAsset");
        Schema<?> videoContext = executionContext.getOneOf().get(2);
        assertThat(videoContext.getRequired()).containsExactlyInAnyOrder(
                "provider", "model", "productCreativeBrief");
        assertProviderIdentity(videoContext, "WAN_VIDEO", "wan3.0-video", null);
        Schema<?> geoContext = executionContext.getOneOf().get(3);
        assertThat(geoContext.getNot()).isNotNull();
        assertThat(geoContext.getNot().getAnyOf())
                .extracting(schema -> ((Schema<?>) schema).getRequired().getFirst())
                .containsExactlyInAnyOrder("provider", "model", "productCreativeBrief");
        Schema<?> diagnosticContext = executionContext.getOneOf().get(4);
        assertThat(((Schema<?>) diagnosticContext.getProperties().get("taskType")).getConst()).isEqualTo("GEO");
        Schema<?> diagnosticInput = (Schema<?>) diagnosticContext.getProperties().get("inputSnapshot");
        Schema<?> diagnosticParameters = (Schema<?>) diagnosticInput.getProperties().get("parameters");
        assertAdditionalPropertiesFalse(diagnosticParameters);
        assertThat(((Schema<?>) diagnosticParameters.getProperties().get("mode")).getConst()).isEqualTo("DIAGNOSTIC_RUN");
        assertThat(((Schema<?>) diagnosticParameters.getProperties().get("sampleMode")).getConst()).isEqualTo("STANDARD");
        Schema<?> diagnosticBudget = (Schema<?>) diagnosticContext.getProperties().get("budget");
        assertThat(((Schema<?>) diagnosticBudget.getProperties().get("samplesPerCombination")).getConst()).isEqualTo(1);
        assertThat(((Schema<?>) diagnosticBudget.getProperties().get("maxProviderCalls")).getMaximum()).isEqualByComparingTo("1000");
        assertThat(diagnosticContext.getNot().getAnyOf())
                .extracting(schema -> ((Schema<?>) schema).getRequired().getFirst())
                .containsExactlyInAnyOrder("provider", "model", "productCreativeBrief");
        Schema<?> continuation = internal.getComponents().getSchemas()
                .get("QwenImageProviderContinuation");
        assertThat(continuation.getRequired()).containsExactlyInAnyOrder(
                "resumeOnly", "provider", "model", "providerRequestId", "providerTaskId",
                "submissionState");
        assertProviderIdentity(continuation, "QWEN_IMAGE", "qwen-image-3.0", null);
        assertThat(((Schema<?>) continuation.getProperties().get("resumeOnly")).getConst()).isEqualTo(true);
        assertThat(((Schema<?>) continuation.getProperties().get("submissionState")).getConst())
                .isEqualTo("UNKNOWN");
        assertThat(((Schema<?>) continuation.getProperties().get("providerRequestId")).getFormat())
                .isEqualTo("uuid");
        Schema<?> videoContinuation=internal.getComponents().getSchemas()
                .get("WanVideoProviderContinuation");
        assertProviderIdentity(videoContinuation,"WAN_VIDEO","wan3.0-video",null);
        Schema<?> taskEvent = internal.getComponents().getSchemas().get("TaskExecutionEventRequest");
        assertThat(taskEvent.getOneOf()).hasSize(3);
        Schema<?> unknownFailure = internal.getComponents().getSchemas()
                .get("QwenImageUnknownFailureDetails");
        assertThat(unknownFailure.getRequired()).containsExactlyInAnyOrder(
                "code", "provider", "model", "providerRequestId", "providerTaskId",
                "submissionState");
        assertProviderIdentity(unknownFailure, "QWEN_IMAGE", "qwen-image-3.0", null);
        assertThat(((Schema<?>) unknownFailure.getProperties().get("submissionState")).getConst())
                .isEqualTo("UNKNOWN");
        Schema<?> articleUnknown = internal.getComponents().getSchemas().get("ArticleUnknownFailureDetails");
        assertThat(articleUnknown.getAdditionalProperties()).isEqualTo(false);
        assertThat(articleUnknown.getRequired()).containsExactlyInAnyOrder(
                "code", "provider", "model", "providerRequestId", "providerTaskId", "submissionState");
        assertThat(articleUnknown.getProperties().keySet()).containsExactlyInAnyOrderElementsOf(
                articleUnknown.getRequired());
        assertThat(((Schema<?>) articleUnknown.getProperties().get("provider")).getConst()).isEqualTo("DEEPSEEK");
        assertThat(((Schema<?>) articleUnknown.getProperties().get("model")).getPattern())
                .isEqualTo("^deepseek-[A-Za-z0-9._-]{1,112}$");
        assertThat(((Schema<?>) articleUnknown.getProperties().get("code")).getEnum())
                .extracting(Object::toString).containsExactlyInAnyOrder(
                        "ARTICLE_DEADLINE_EXCEEDED", "DEEPSEEK_SUBMISSION_UNKNOWN");
        for (String field : List.of("providerRequestId", "providerTaskId")) {
            assertThat(((Schema<?>) articleUnknown.getProperties().get(field)).getTypes())
                    .containsExactly("null");
        }
        assertThat(((Schema<?>) articleUnknown.getProperties().get("submissionState")).getConst())
                .isEqualTo("UNKNOWN");
        Schema<?> unknownDetails = (Schema<?>) internal.getComponents().getSchemas()
                .get("TaskUnknownFailureEventRequest").getProperties().get("details");
        // The parser resolves refs; assert the actual closed variants, not discarded $ref text.
        assertThat(unknownDetails.getOneOf()).hasSize(4)
                .extracting(branch -> ((Schema<?>) ((Schema<?>) branch).getProperties()
                        .get("provider")).getConst().toString())
                .containsExactlyInAnyOrder("QWEN_IMAGE", "WAN_IMAGE_EDIT", "WAN_VIDEO", "DEEPSEEK");
        Schema<?> terminalFailure = internal.getComponents().getSchemas()
                .get("TaskTerminalFailureDetails");
        assertThat(((Schema<?>) terminalFailure.getProperties().get("submissionState")).getEnum())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("NOT_SUBMITTED", "CONFIRMED");
        Schema<?> acceptedStatus = (Schema<?>) internal.getComponents().getSchemas()
                .get("InternalTaskAccepted").getProperties().get("taskStatus");
        assertThat(acceptedStatus.getEnum()).extracting(Object::toString)
                .contains("SYSTEM_TERMINATED");
        for (String path : List.of("/internal/v1/tasks/{taskId}/claim",
                "/internal/v1/tasks/{taskId}/heartbeat", "/internal/v1/tasks/{taskId}/execution-events",
                "/internal/v1/tasks/{taskId}/result-artifacts")) {
            Operation operation = internal.getPaths().get(path).getPost();
            assertThat(operation.getSecurity()).isNotEmpty();
            assertThat(operation.getParameters()).extracting(parameter -> parameter.getName())
                    .contains("X-Internal-Key-Id", "X-Internal-Timestamp", "X-Internal-Nonce",
                            "X-Content-SHA256", "X-Internal-Signature", "traceparent");
        }
        Schema<?> taskClaimRequest = internal.getComponents().getSchemas().get("TaskClaimRequest");
        assertThat(((Schema<?>) taskClaimRequest.getProperties()
                .get("supportedBundleVersion")).getPattern()).isEqualTo(bundleVersionPattern);
        Operation resultOperation = internal.getPaths()
                .get("/internal/v1/tasks/{taskId}/result-artifacts").getPost();
        assertThat(resultOperation.getOperationId()).isEqualTo("reportTaskResult");
        assertResponseCodes(resultOperation, "200", "401", "403", "409", "422", "503");
        Schema<?> result = internal.getComponents().getSchemas().get("TaskResultRequest");
        assertThat(result.getOneOf()).hasSize(5);
        Schema<?> regionResult = internal.getComponents().getSchemas().get("WanImageEditResultRequest");
        assertProviderIdentity(regionResult, "WAN_IMAGE_EDIT", "wanx2.1-imageedit", null);
        assertThat(((Schema<?>) regionResult.getProperties().get("artifacts")).getMaxItems()).isEqualTo(4);
        Schema<?> mock = internal.getComponents().getSchemas().get("ImageMockResultRequest");
        Schema<?> qwen = internal.getComponents().getSchemas().get("QwenImageResultRequest");
        assertThat(((Schema<?>) qwen.getProperties().get("artifacts")).getMaxItems()).isEqualTo(4);
        assertThat(((Schema<?>) mock.getProperties().get("artifacts")).getMaxItems()).isEqualTo(4);
        Schema<?> wan = internal.getComponents().getSchemas().get("WanVideoResultRequest");
        Schema<?> article = internal.getComponents().getSchemas().get("ArticleContentResultRequest");
        assertThat(mock.getRequired()).contains("requestId", "providerTaskId", "submissionState",
                "artifacts", "usage", "latencyMs");
        assertThat(qwen.getRequired()).contains("requestId", "providerTaskId", "submissionState",
                "artifacts", "usage", "latencyMs");
        assertThat(((Schema<?>) mock.getProperties().get("submissionState")).getConst())
                .isEqualTo("CONFIRMED");
        assertProviderIdentity(mock, "LOCAL_TASK_PROVIDER", "deterministic-provider-mock-v1", null);
        assertProviderIdentity(qwen, "QWEN_IMAGE", "qwen-image-3.0", null);
        assertProviderIdentity(wan, "WAN_VIDEO", "wan3.0-video", null);
        assertProviderIdentity(article, "DEEPSEEK", null, null);
        assertThat(((Schema<?>) article.getProperties().get("model")).getPattern())
                .isEqualTo("^deepseek-[A-Za-z0-9._-]{1,112}$");
        assertThat(article.getRequired()).contains("executionNo", "executionToken", "requestId",
                "submissionState", "artifacts", "usage", "latencyMs");
        Schema<?> articleArtifact = artifactItem(article);
        assertThat(((Schema<?>) articleArtifact.getProperties().get("artifactType")).getConst())
                .isEqualTo("ARTICLE_CONTENT");
        assertThat(((Schema<?>) articleArtifact.getProperties().get("schemaVersion")).getConst())
                .isEqualTo(2);
        assertThat(((Schema<?>) articleArtifact.getProperties().get("unitKey")).getEnum())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("article:title", "article:outline", "article:body",
                        "article:rewrite");
        Schema<?> articleParameters = internal.getComponents().getSchemas().get("ArticleTaskParameters");
        assertThat(articleParameters.getRequired()).containsExactlyInAnyOrder(
                "mode", "articleId", "articleVersion", "brief", "selectedTitle",
                "confirmedOutline", "confirmedPlan", "rewriteInstruction", "rewriteBlockIds", "targetLength",
                "tone", "targetPlatform", "sourceAssetIds", "geoReportId");
        assertAdditionalPropertiesFalse(articleParameters);
        Schema<?> articleResult = internal.getComponents().getSchemas().get("ArticleContentResult");
        assertThat(((Schema<?>) articleResult.getProperties().get("promptVersion")).getEnum())
                .extracting(Object::toString)
                .containsExactlyInAnyOrderElementsOf(new com.guanghe.backend.agentplatform.AgentRuntimeCatalog()
                        .acceptedArticlePromptVersions());
        assertThat(articleResult.getRequired()).contains("mode", "articleVersion", "plan", "provider",
                "model", "usage", "metadata");
        assertThat(articleResult.getOneOf()).hasSize(4);
        assertAdditionalPropertiesFalse(articleResult);
        Schema<?> articleMetadata = internal.getComponents().getSchemas().get("ArticleResultMetadata");
        assertThat(((Schema<?>) articleMetadata.getProperties().get("proposalOnly")).getConst())
                .isEqualTo(true);
        assertThat(((Schema<?>) articleMetadata.getProperties().get("persisted")).getConst())
                .isEqualTo(false);
        assertThat(((Schema<?>) articleMetadata.getProperties().get("published")).getConst())
                .isEqualTo(false);
        assertThat(internal.getComponents().getSchemas().get("ArticleTopLevelUsage").getOneOf()).hasSize(2);
        assertThat(internal.getComponents().getSchemas().get("ArticleResultUsage").getOneOf()).hasSize(2);
        Schema<?> qwenArtifact = artifactItem(qwen);
        assertThat(qwenArtifact.getRequired()).contains("artifactType", "schemaVersion", "providerUrl",
                "contentType", "expiresAt", "metadata");
        assertThat(((Schema<?>) qwenArtifact.getProperties().get("providerUrl")).getPattern())
                .isEqualTo("^https://");
        assertThat(((Schema<?>) qwenArtifact.getProperties().get("contentType")).getConst())
                .isEqualTo("image/png");
        Schema<?> qwenMetadata = (Schema<?>) qwenArtifact.getProperties().get("metadata");
        assertThat(qwenMetadata.getRequired()).contains("index", "width", "height", "mock",
                "providerRequestId");
        assertThat(((Schema<?>) qwenMetadata.getProperties().get("mock")).getConst()).isEqualTo(false);
        Schema<?> wanArtifact=artifactItem(wan);
        assertThat(((Schema<?>) wanArtifact.getProperties().get("contentType")).getConst())
                .isEqualTo("video/mp4");
        assertThat(wanArtifact.getRequired()).doesNotContain("expiresAt");
        Schema<?> wanMetadata=(Schema<?>)wanArtifact.getProperties().get("metadata");
        assertThat(wanMetadata.getRequired()).contains("durationSeconds","requestedDurationSeconds",
                "requestedAspectRatio","requestedResolution","mock","providerRequestId");
        assertThat(((Schema<?>)wanMetadata.getProperties().get("mock")).getConst()).isEqualTo(false);
        Operation postObservation = internal.getPaths()
                .get("/internal/v1/tasks/{taskId}/geo-observations").getPost();
        Operation getObservations = internal.getPaths()
                .get("/internal/v1/tasks/{taskId}/geo-observations").getGet();
        Operation completeGeo = internal.getPaths()
                .get("/internal/v1/tasks/{taskId}/geo-completion").getPost();
        for (Operation operation : List.of(postObservation, getObservations, completeGeo)) {
            assertThat(operation.getSecurity()).isNotEmpty();
            assertResponseCodes(operation, "200", "401", "403", "404", "409", "422", "503");
            assertThat(operation.getParameters()).extracting(parameter -> parameter.getName())
                    .contains("X-Internal-Key-Id", "X-Internal-Timestamp", "X-Internal-Nonce",
                            "X-Content-SHA256", "X-Internal-Signature", "traceparent");
        }
        Schema<?> geoObservation = internal.getComponents().getSchemas().get("GeoObservationInput");
        assertThat(geoObservation.getOneOf()).hasSize(2);
        assertThat(geoObservation.getProperties()).doesNotContainKeys(
                "userId", "quotedPoints", "executionToken", "authorization", "cookie");
        Schema<?> geoCompletion = internal.getComponents().getSchemas().get("GeoCompletionRequest");
        assertThat(geoCompletion.getRequired()).containsExactlyInAnyOrder(
                "eventId", "executionNo", "executionToken", "synthesis", "synthesisUsage");
        Schema<?> synthesisUsage = (Schema<?>) geoCompletion.getProperties().get("synthesisUsage");
        assertThat(((Schema<?>) synthesisUsage.getProperties().get("provider")).getEnum())
                .extracting(Object::toString)
                .containsExactly("DEEPSEEK", "SYSTEM");
        assertThat(((Schema<?>) synthesisUsage.getProperties().get("model")).getEnum())
                .extracting(Object::toString)
                .containsExactly("deepseek-v4-flash", "deepseek-flash", "deterministic-geo-fallback-v1", "deterministic-geo-diagnostic-sampling-v1");
    }

    @Test
    void articleContinuationReviewIsInternalBoundedAndCannotReplaceTheCurrentBrief() throws Exception {
        OpenAPI internal = parse(JAVA_INTERNAL);
        Schema<?> input = internal.getComponents().getSchemas().get("RunArticleGenerateArguments");
        assertAdditionalPropertiesFalse(input);
        assertThat(input.getProperties().keySet()).containsExactlyInAnyOrder(
                "tone", "targetLength", "research", "continuationReview");
        Schema<?> review = internal.getComponents().getSchemas().get("RunArticleContinuationReview");
        assertThat(input.getProperties().get("continuationReview")).isEqualTo(review);
        assertAdditionalPropertiesFalse(review);
        assertThat(review.getRequired()).containsExactlyInAnyOrder(
                "currentMessageId", "sourceMessageId", "offerMessageId", "decision");
        assertThat(review.getProperties().keySet()).containsExactlyInAnyOrder(
                "currentMessageId", "sourceMessageId", "offerMessageId", "decision");
        assertThat(((Schema<?>) review.getProperties().get("decision")).getConst()).isEqualTo("NEW_DRAFT");
        for (String field : List.of("currentMessageId", "sourceMessageId", "offerMessageId")) {
            Schema<?> id = (Schema<?>) review.getProperties().get(field);
            assertThat(id.getTypes()).containsExactly("string");
            assertThat(id.getPattern()).isEqualTo("^[1-9][0-9]{0,18}$");
        }
        assertThat(input.getDescription()).contains("not exposed to the LLM", "account lock", "must omit");
    }

    @Test
    void creativeContinuationReviewAndEvidenceAreBoundedInternalSchemas() throws Exception {
        OpenAPI internal=parse(JAVA_INTERNAL);
        Schema<?> review=internal.getComponents().getSchemas().get("RunCreativeContinuationReview");
        Schema<?> evidence=internal.getComponents().getSchemas().get("RunCreativeContinuationEvidence");
        assertAdditionalPropertiesFalse(review);assertAdditionalPropertiesFalse(evidence);
        assertThat(review.getRequired()).containsExactlyInAnyOrder("currentMessageId","historyFingerprint","taskType","decision","sourceMode");
        assertThat(review.getProperties().keySet()).containsExactlyInAnyOrder("currentMessageId","historyFingerprint","taskType","decision","sourceMode","sourceImageAssetId");
        Schema<?> source=(Schema<?>)review.getProperties().get("sourceImageAssetId");
        assertThat(source.getTypes()).containsExactlyInAnyOrder("string","null");
        assertThat(source.getPattern()).isEqualTo("^[1-9][0-9]{0,18}$");
        assertThat(evidence.getRequired()).contains("sourceImageAssetId","currentAttachmentAssetIds");
        assertThat(((Schema<?>)evidence.getProperties().get("sourceImageAssetId")).getTypes())
                .containsExactlyInAnyOrder("string","null");
        assertThat(((Schema<?>)review.getProperties().get("decision")).getConst()).isEqualTo("CREATE_NEW");
        assertThat(((Schema<?>)review.getProperties().get("sourceMode")).getEnum().stream().map(String::valueOf).toList())
                .containsExactlyInAnyOrder("TEXT_ONLY","PREVIOUS_IMAGE_RESULT","CURRENT_ATTACHMENTS");
        assertThat(review.getAllOf()).hasSize(1);
        Schema<?> sourceBinding=review.getAllOf().getFirst();
        assertThat(((Schema<?>)sourceBinding.getIf().getProperties().get("sourceMode")).getConst()).isEqualTo("PREVIOUS_IMAGE_RESULT");
        assertThat(sourceBinding.getThen().getRequired()).containsExactly("sourceImageAssetId");
        assertThat(((Schema<?>)sourceBinding.getElse().getProperties().get("sourceImageAssetId")).getTypes()).containsExactly("null");
        assertThat(((Schema<?>)review.getProperties().get("historyFingerprint")).getPattern()).isEqualTo("^[a-f0-9]{64}$");
        for(String name:List.of("RunImageGenerateArguments","RunVideoGenerateArguments")) {
            Schema<?> action=internal.getComponents().getSchemas().get(name);
            assertThat(action.getProperties().get("continuationReview")).isEqualTo(review);
        }
        assertThat(((Schema<?>)evidence.getProperties().get("authorized")).getConst()).isEqualTo(false);
        assertThat(((Schema<?>)evidence.getProperties().get("reviewRequired")).getConst()).isEqualTo(true);
        Schema<?> history=(Schema<?>)evidence.getProperties().get("history");
        assertThat(history.getMinItems()).isZero();
        assertThat(history.getMaxItems()).isEqualTo(6);
        assertAdditionalPropertiesFalse(history.getItems());
        Schema<?> currentAttachments=(Schema<?>)evidence.getProperties().get("currentAttachmentAssetIds");
        assertThat(currentAttachments.getMinItems()).isZero();
        assertThat(currentAttachments.getMaxItems()).isEqualTo(8);
        assertThat(currentAttachments.getUniqueItems()).isTrue();
        assertThat(currentAttachments.getItems().getTypes()).containsExactly("string");
        assertThat(currentAttachments.getItems().getPattern()).isEqualTo("^[1-9][0-9]{0,18}$");
        assertThat(evidence.getAnyOf()).hasSize(2);
        assertThat(((Schema<?>)evidence.getAnyOf().get(0).getProperties().get("history")).getMinItems()).isEqualTo(1);
        assertThat(((Schema<?>)evidence.getAnyOf().get(1).getProperties().get("currentAttachmentAssetIds")).getMinItems()).isEqualTo(1);
        assertThat(evidence.getDescription()).contains("never clipped","latest memory history boundary","exact current USER text");
        assertThat(evidence.getDescription()).contains("Java-frozen currentAttachmentAssetIds","both collections cannot be empty");
    }

    @Test
    void nativeCreativeContractMakesTheServerOwnedExplicitIntentBoundaryVisible() throws Exception {
        OpenAPI internal = parse(JAVA_INTERNAL);
        Schema<?> image = internal.getComponents().getSchemas().get("RunImageGenerateArguments");
        Schema<?> video = internal.getComponents().getSchemas().get("RunVideoGenerateArguments");

        assertThat(image.getDescription()).contains(
                "original current USER", "Java independently enforces", "pricing inquiries",
                "conditional requests", "refusals cannot authorize", "PREVIOUS_IMAGE_RESULT",
                "PREVIOUS_USER_ATTACHMENT",
                "model never supplies its asset ID");
        assertThat(video.getDescription()).contains(
                "server-enforced original-current-USER", "pricing inquiries",
                "conditional requests", "refusals cannot authorize", "PREVIOUS_IMAGE_RESULT",
                "PREVIOUS_USER_ATTACHMENT",
                "model never supplies its asset ID");
        assertThat(((Schema<?>) image.getProperties().get("sourceMode")).getEnum().toString())
                .contains("PREVIOUS_IMAGE_RESULT", "PREVIOUS_USER_ATTACHMENT");
        assertThat(((Schema<?>) video.getProperties().get("sourceMode")).getEnum().toString())
                .contains("PREVIOUS_IMAGE_RESULT", "PREVIOUS_USER_ATTACHMENT");
        Schema<?> brief = internal.getComponents().getSchemas().get("ProductCreativeBrief");
        assertThat(((Schema<?>) brief.getProperties().get("sourceMode")).getEnum().toString())
                .contains("PREVIOUS_IMAGE_RESULT", "PREVIOUS_USER_ATTACHMENT");
        Schema<?> provenance = (Schema<?>) brief.getProperties().get("provenance");
        assertThat(provenance.getProperties()).containsKey("referenceMessageId");
        assertThat(provenance.getRequired()).doesNotContain("referenceMessageId");
    }

    @Test
    void articleWorkerContextDeclaresRequiredSourceSetAndAllBoundedSavedReportFields() throws Exception {
        OpenAPI internal = parse(JAVA_INTERNAL);
        Schema<?> context = internal.getComponents().getSchemas().get("ArticleAuthorizedContext");
        assertAdditionalPropertiesFalse(context);
        assertThat(context.getRequired()).containsExactlyInAnyOrder(
                "space", "product", "article", "sourceAssets", "geoReport");
        assertThat(context.getProperties().keySet()).containsExactlyInAnyOrder(
                "space", "product", "article", "sourceAssets", "geoReport", "knowledge", "untrustedResearch",
                "confirmedFacts");
        Schema<?> confirmedFacts = (Schema<?>) context.getProperties().get("confirmedFacts");
        assertThat(confirmedFacts.getMaxItems()).isEqualTo(3);
        Schema<?> research = internal.getComponents().getSchemas().get("RunArticleResearch");
        assertAdditionalPropertiesFalse(research);
        assertThat(research.getRequired()).containsExactlyInAnyOrder("sourceType", "fullPagesRead", "sources");
        assertThat(((Schema<?>) research.getProperties().get("fullPagesRead")).getConst()).isEqualTo(false);
        assertThat(((Schema<?>) research.getProperties().get("sources")).getMaxItems()).isEqualTo(8);
        Schema<?> sources = (Schema<?>) context.getProperties().get("sourceAssets");
        assertThat(sources.getMaxItems()).isEqualTo(10);
        Schema<?> report = ((Schema<?>) context.getProperties().get("geoReport")).getOneOf().get(1);
        assertAdditionalPropertiesFalse(report);
        assertThat(report.getRequired()).containsExactlyInAnyOrder("id", "summary", "methodVersion");
        assertThat(report.getProperties().keySet()).containsExactlyInAnyOrder(
                "id", "summary", "methodVersion", "coverageGaps", "findings", "recommendations",
                "contentBlueprint", "missingSamples");
        for (String field : List.of("coverageGaps", "findings", "contentBlueprint", "missingSamples")) {
            Schema<?> strings = (Schema<?>) report.getProperties().get(field);
            assertThat(strings.getMaxItems()).isEqualTo("coverageGaps".equals(field) ? 20 : 50);
            assertThat(strings.getItems().getMinLength()).isEqualTo(1);
            assertThat(strings.getItems().getMaxLength()).isEqualTo(1000);
        }
        Schema<?> recommendations = (Schema<?>) report.getProperties().get("recommendations");
        assertThat(recommendations.getMaxItems()).isEqualTo(30);
        Schema<?> recommendation = recommendations.getItems();
        assertAdditionalPropertiesFalse(recommendation);
        assertThat(recommendation.getRequired()).containsExactlyInAnyOrder("priority", "title", "rationale");
        assertThat(recommendation.getProperties().keySet()).containsExactlyInAnyOrder("id", "priority", "title", "rationale");
        assertThat(((Schema<?>) recommendation.getProperties().get("priority")).getEnum())
                .extracting(Object::toString)
                .containsExactly("P0", "P1", "P2");
        assertThat(((Schema<?>) recommendation.getProperties().get("id")).getPattern())
                .isEqualTo("^[1-9][0-9]{0,18}:recommendation:(?:[1-9]|[12][0-9]|30)$");
        Schema<?> parameters = internal.getComponents().getSchemas().get("ArticleTaskParameters");
        Schema<?> reference = (Schema<?>) parameters.getProperties().get("geoRecommendationId");
        assertThat(parameters.getRequired()).doesNotContain("geoRecommendationId");
        assertThat(reference.getTypes()).containsExactlyInAnyOrder("string", "null");
        assertThat(reference.getMaxLength()).isEqualTo(128);
    }

    @Test
    void internalGeoObservationPageRequiresSeparateFullMatrixVisibilityFacts() throws Exception {
        OpenAPI internal = parse(JAVA_INTERNAL);
        Schema<?> page = internal.getComponents().getSchemas().get("GeoObservationPage");
        assertAdditionalPropertiesFalse(page);
        assertThat(page.getRequired()).containsExactlyInAnyOrder(
                "items", "page", "size", "total", "totalPages", "visibilityEvidence");
        assertResolvesTo((Schema<?>) page.getProperties().get("visibilityEvidence"),
                "GeoVisibilityEvidence", "matchingVersion");
        Schema<?> evidence = internal.getComponents().getSchemas().get("GeoVisibilityEvidence");
        assertAdditionalPropertiesFalse(evidence);
        assertThat(evidence.getRequired()).containsExactlyInAnyOrder("targetStatus", "matchingVersion", "units");
        assertThat(((Schema<?>) evidence.getProperties().get("targetStatus")).getEnum())
                .extracting(Object::toString).containsExactly("AVAILABLE", "TARGET_UNRESOLVED");
        assertThat(((Schema<?>) evidence.getProperties().get("matchingVersion")).getConst())
                .isEqualTo("explicit-name-mention-v1");
        Schema<?> units = (Schema<?>) evidence.getProperties().get("units");
        assertThat(units.getMaxItems()).isEqualTo(210);
        Schema<?> unit = units.getItems();
        assertAdditionalPropertiesFalse(unit);
        assertThat(unit.getRequired()).containsExactlyInAnyOrder("questionId", "platformCode", "sampleNo", "targetMentioned");
        assertThat(((Schema<?>) unit.getProperties().get("targetMentioned")).getTypes())
                .containsExactlyInAnyOrder("boolean", "null");
        assertThat(((Schema<?>) unit.getProperties().get("sampleNo")).getMinimum()).isEqualByComparingTo("1");
        assertThat(((Schema<?>) unit.getProperties().get("sampleNo")).getMaximum()).isEqualByComparingTo("3");
    }

    @Test
    void mirroredPythonContractAndSharedFilesMatchTheirLfNormalizedSourceHashes() throws Exception {
        JsonNode source = readJson(PYTHON_SOURCE);

        assertThat(source.path("repository").asText())
                .isEqualTo("https://gitee.com/guanghe_888/guanghe-agent.git");
        assertThat(source.path("gitCommit").asText()).isEqualTo(PYTHON_COMMIT);
        assertThat(source.path("sourcePath").asText()).isEqualTo("contracts/agent-openapi.yaml");
        assertThat(normalizedSha256(PYTHON_AGENT)).isEqualTo(source.path("sha256LfNormalized").asText());
        assertThat(normalizedSha256("/contracts/hmac/hmac-v1-test-vector.json"))
                .isEqualTo(source.path("sharedContracts")
                        .path("contracts/hmac/hmac-v1-test-vector.json").asText());
        assertThat(normalizedSha256("/contracts/rabbit/task-execution-requested-v1.schema.json"))
                .isEqualTo(RABBIT_SCHEMA_SHA256);
        assertThat(source.path("sharedContracts")
                .path("contracts/rabbit/task-execution-requested-v1.schema.json").asText())
                .isEqualTo(RABBIT_SCHEMA_SHA256);

        OpenAPI agent = parse(PYTHON_AGENT);
        assertThat(agent.getPaths()).containsKeys(
                "/internal/v1/health/ready",
                "/internal/v1/capabilities",
                "/internal/v1/chat-runs",
                "/internal/v1/chat-runs/{runId}/events",
                "/internal/v1/chat-runs/{runId}/stop",
                "/internal/v1/creative/geo-plans"
        );
        assertThat(agent.getPaths()).containsKeys("/internal/v1/geo/diagnostic-preparations", "/internal/v1/geo/diagnostic-analyses");
        assertThat(agent.getPaths().get("/internal/v1/creative/geo-plans").getPost().getRequestBody())
                .isNotNull();
        assertThat(agent.getComponents().getSchemas().get("GeoVisualPlanRequest").getRequired())
                .contains("taskType", "report", "evidence", "productFacts", "prohibitedClaims");
        assertThat(agent.getPaths().get("/internal/v1/chat-runs").getPost().getRequestBody()).isNotNull();
        var agentNonce = agent.getComponents().getParameters().get("InternalNonce");
        assertThat(agentNonce.getSchema().getPattern())
                .isEqualTo("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        assertThat(agentNonce.getDescription())
                .contains("canonical lowercase UUID", "Uppercase or non-canonical forms", "before the Nonce claim");

        Schema<?> budget = agent.getComponents().getSchemas().get("RunBudget");
        assertThat(budget.getProperties()).containsOnlyKeys(
                "maxModelCalls", "maxToolCalls", "maxSteps", "maxContextLoads",
                "maxProviderCalls", "maxInputTokens", "deadlineAt"
        );
        Schema<?> maxContextLoads = (Schema<?>) budget.getProperties().get("maxContextLoads");
        assertThat(((Number) maxContextLoads.getMaximum()).intValue()).isEqualTo(1);
        assertThat(((Number) maxContextLoads.getDefault()).intValue()).isEqualTo(1);
        Schema<?> maxProviderCalls = (Schema<?>) budget.getProperties().get("maxProviderCalls");
        assertThat(((Number) maxProviderCalls.getMaximum()).intValue()).isEqualTo(6);
        assertThat(((Number) maxProviderCalls.getDefault()).intValue()).isZero();
        assertAdditionalPropertiesFalse(budget);

        Schema<?> usage = agent.getComponents().getSchemas().get("Usage");
        assertAdditionalPropertiesFalse(usage);
        assertThat(usage.getRequired()).containsExactlyInAnyOrder(
                "workflowStepCount", "modelCallCount", "contextLoadCount", "toolCallCount",
                "providerCallCount", "modelCalls", "toolCalls", "steps", "estimatedInputTokens"
        );
        assertThat(usage.getProperties()).containsOnlyKeys(
                "workflowStepCount", "modelCallCount", "contextLoadCount", "toolCallCount",
                "providerCallCount", "modelCalls", "toolCalls", "steps", "estimatedInputTokens",
                "providerInputTokens", "providerOutputTokens", "providerLatencyMs", "provider", "model"
        );
    }

    @Test
    void pythonChatRunContractKeepsSseMediaTypeAndOperationalErrorResponses() throws Exception {
        OpenAPI agent = parse(PYTHON_AGENT);

        Operation start = agent.getPaths().get("/internal/v1/chat-runs").getPost();
        assertResponseCodes(start, "401", "409", "503");

        Operation snapshot = agent.getPaths().get("/internal/v1/chat-runs/{runId}").getGet();
        assertResponseCodes(snapshot, "401", "404", "503");

        Operation events = agent.getPaths().get("/internal/v1/chat-runs/{runId}/events").getGet();
        assertThat(events.getResponses().get("200").getContent())
                .containsKey("text/event-stream")
                .doesNotContainKey("application/json");
        assertResponseCodes(events, "400", "401", "404", "503");

        Operation stop = agent.getPaths().get("/internal/v1/chat-runs/{runId}/stop").getPost();
        assertResponseCodes(stop, "401", "404", "503");
    }

    private void assertResponseCodes(Operation operation, String... expectedCodes) {
        assertThat(operation).isNotNull();
        assertThat(operation.getResponses()).containsKeys(expectedCodes);
        for (String code : expectedCodes) {
            assertThat(operation.getResponses().get(code).getDescription())
                    .as("response %s for %s", code, operation.getOperationId())
                    .isNotBlank();
        }
    }

    @Test
    void browserAgentProgressPayloadUsesOnlyTheFrozenStageContract() throws Exception {
        OpenAPI browser = parse(BROWSER);
        Schema<?> progress = browser.getComponents().getSchemas().get("AgentProgressPayload");

        assertThat(progress).isNotNull();
        assertAdditionalPropertiesFalse(progress);
        assertThat(progress.getRequired()).containsExactlyInAnyOrder(
                "runId", "messageId", "stageCode", "status", "ordinal", "elapsedMs", "current", "total"
        );
        Schema<?> stageCode = (Schema<?>) progress.getProperties().get("stageCode");
        assertThat(stageCode.getEnum().stream().map(Object::toString).toList()).containsExactlyInAnyOrder(
                "UNDERSTANDING_REQUEST", "LOADING_BUSINESS_CONTEXT", "LOADING_PRODUCT_CONTEXT",
                "LOADING_REFERENCE_ASSETS", "ROUTING_INTENT", "PLANNING_WORKFLOW",
                "RUNNING_SAFE_TOOL", "SEARCHING_WEB", "PREPARING_TASK_DRAFT", "COMPOSING_RESPONSE"
        );
    }

    @Test
    void browserAndPythonMessageDeltaContractsDeclareStrictOptionalReplacement() throws Exception {
        for (String resource : List.of(BROWSER, PYTHON_AGENT)) {
            Schema<?> delta = parse(resource).getComponents().getSchemas().get("MessageDeltaPayload");
            assertAdditionalPropertiesFalse(delta);
            assertThat(delta.getRequired()).containsExactlyInAnyOrder("messageId", "delta");
            assertThat(delta.getProperties()).containsOnlyKeys("messageId", "delta", "replace");
            assertThat(((Schema<?>) delta.getProperties().get("delta")).getMaxLength()).isEqualTo(16000);
            Schema<?> replace = (Schema<?>) delta.getProperties().get("replace");
            assertThat(replace.getTypes()).containsExactly("boolean");
            assertThat(replace.getDefault()).isEqualTo(false);
            assertThat(delta.getAllOf()).hasSize(1);
            Schema<?> conditional = delta.getAllOf().getFirst();
            assertThat(conditional.getIf().getRequired()).containsExactly("replace");
            assertThat(((Schema<?>) conditional.getIf().getProperties().get("replace")).getConst()).isEqualTo(true);
            assertThat(((Schema<?>) conditional.getElse().getProperties().get("delta")).getMinLength()).isEqualTo(1);
        }
    }

    /**
     * The commercial control plane's contract. The point of most of these assertions is that the
     * published surface cannot be read as promising a payment capability that does not exist.
     */
    @Test
    void billingContractExposesRechargeAndNeverImpliesPaymentIsAvailable() throws Exception {
        OpenAPI browser = parse(BROWSER);

        Operation capability = browser.getPaths().get("/api/v1/billing/recharge-capability").getGet();
        assertThat(capability.getSecurity()).isNotEmpty();
        assertResponseCodes(capability, "200", "401", "403");
        Schema<?> capabilitySchema = browser.getComponents().getSchemas().get("RechargeCapability");
        assertAdditionalPropertiesFalse(capabilitySchema);
        assertThat(capabilitySchema.getRequired()).containsExactlyInAnyOrder(
                "provider", "configured", "payable", "callbackVerifiable", "status",
                "missingRequirements", "minRechargeAmountCents", "maxRechargeAmountCents",
                "pointsPerYuan", "rechargeOrderTtlSeconds", "currency");
        assertThat(((Schema<?>) capabilitySchema.getProperties().get("status")).getEnum())
                .extracting(Object::toString)
                .containsExactly("PAYMENT_PROVIDER_NOT_CONFIGURED",
                        "PAYMENT_PROVIDER_NOT_IMPLEMENTED", "PAYMENT_PROVIDER_READY");
        assertThat(capabilitySchema.getProperties()).doesNotContainKeys(
                "apiV3Key", "merchantId", "privateKey", "platformCertificate", "codeUrl");

        Schema<?> order = browser.getComponents().getSchemas().get("RechargeOrder");
        assertAdditionalPropertiesFalse(order);
        assertThat(((Schema<?>) order.getProperties().get("status")).getEnum())
                .extracting(Object::toString)
                .containsExactly("CREATED", "PAYING", "SUCCEEDED", "CLOSED", "EXPIRED", "FAILED");
        assertThat(((Schema<?>) order.getProperties().get("codeUrl")).getTypes())
                .containsExactlyInAnyOrder("string", "null");
        assertThat(order.getProperties()).doesNotContainKeys(
                "providerPrepayId", "requestHash", "idempotencyKey");

        Operation createOrder = browser.getPaths().get("/api/v1/billing/recharge-orders").getPost();
        // Fail closed is part of the contract, not just the implementation, and the throttle this
        // endpoint enforces is documented so a 429 is never an unexplained refusal.
        assertResponseCodes(createOrder, "201", "401", "403", "409", "422", "429", "503");
        assertThat(createOrder.getParameters()).extracting(parameter -> parameter.getName())
                .containsExactly("Idempotency-Key");
        assertThat(createOrder.getParameters().getFirst().getRequired()).isTrue();
        Schema<?> createRequest = browser.getComponents().getSchemas().get("CreateRechargeOrderRequest");
        assertAdditionalPropertiesFalse(createRequest);
        assertThat(createRequest.getProperties()).containsOnlyKeys("amountCents", "returnContext");
        assertThat(createRequest.getProperties()).doesNotContainKeys(
                "userId", "points", "price", "quotedPoints", "provider", "status", "codeUrl");

        Operation queryOrder = browser.getPaths()
                .get("/api/v1/billing/recharge-orders/{orderId}/query").getPost();
        assertResponseCodes(queryOrder, "200", "401", "403", "404", "409", "503");
        assertThat(browser.getPaths().get("/api/v1/billing/recharge-orders/{orderId}").getPut())
                .isNull();
        assertThat(browser.getPaths().get("/api/v1/billing/recharge-orders/{orderId}").getDelete())
                .isNull();

        Operation callback = browser.getPaths().get("/api/v1/callbacks/payment/notify").getPost();
        // The only unauthenticated commercial endpoint: authentication is the provider signature.
        assertThat(callback.getSecurity()).isEmpty();
        assertThat(callback.getRequestBody()).isNotNull();
        assertResponseCodes(callback, "200", "401", "409", "422", "503");
        assertThat(((Schema<?>) browser.getComponents().getSchemas()
                .get("PaymentCallbackAck").getProperties().get("code")).getConst())
                .isEqualTo("SUCCESS");
        assertThat(((Schema<?>) browser.getComponents().getSchemas()
                .get("PaymentCallbackFailure").getProperties().get("code")).getConst())
                .isEqualTo("FAIL");

        for (String path : List.of("/api/v1/admin/billing/recharge-orders",
                "/api/v1/admin/billing/recharge-orders/{orderId}",
                "/api/v1/admin/billing/recharge-orders/{orderId}/reconcile",
                "/api/v1/admin/billing/point-adjustments",
                "/api/v1/admin/billing/point-adjustments/{adjustmentId}/confirm",
                "/api/v1/admin/billing/audit-events")) {
            assertThat(browser.getPaths().get(path).readOperations())
                    .as(path).allSatisfy(operation ->
                            assertThat(operation.getSecurity()).isNotEmpty());
        }

        Operation requestAdjustment = browser.getPaths()
                .get("/api/v1/admin/billing/point-adjustments").getPost();
        assertResponseCodes(requestAdjustment, "201", "401", "403", "409", "422");
        assertThat(requestAdjustment.getParameters()).extracting(parameter -> parameter.getName())
                .containsExactly("Idempotency-Key");
        Schema<?> adjustment = browser.getComponents().getSchemas().get("PointAdjustment");
        assertAdditionalPropertiesFalse(adjustment);
        assertThat(((Schema<?>) adjustment.getProperties().get("status")).getEnum())
                .extracting(Object::toString)
                .containsExactly("PENDING_CONFIRMATION", "APPLIED", "CANCELLED");
        assertThat(((Schema<?>) adjustment.getProperties().get("direction")).getConst())
                .isEqualTo("CREDIT");
        // The operator is recorded as who acted, never as a business-type discriminator.
        assertThat(((Schema<?>) adjustment.getProperties().get("confirmationToken")).getDescription())
                .contains("exactly once");
        Schema<?> confirmRequest = browser.getComponents().getSchemas()
                .get("ConfirmPointAdjustmentRequest");
        assertThat(confirmRequest.getProperties()).containsOnlyKeys("confirmationToken");
        Schema<?> createAdjustment = browser.getComponents().getSchemas()
                .get("CreatePointAdjustmentRequest");
        assertThat(createAdjustment.getProperties()).containsOnlyKeys("userId", "points", "reason");

        // The audit trail is readable and has no mutation route at any path.
        assertThat(browser.getPaths().get("/api/v1/admin/billing/audit-events").getPost()).isNull();
        assertThat(browser.getPaths().keySet().stream()
                .filter(path -> path.startsWith("/api/v1/admin/billing/audit-events/")).toList())
                .isEmpty();
        Schema<?> auditEvent = browser.getComponents().getSchemas().get("OperationalAuditEvent");
        assertAdditionalPropertiesFalse(auditEvent);
        assertThat(((Schema<?>) auditEvent.getProperties().get("actorType")).getEnum())
                .extracting(Object::toString)
                .containsExactly("USER", "ADMIN", "SYSTEM", "PAYMENT_PROVIDER", "MAIL_PROVIDER");
        // Exactly these fields, so no raw body, credential or prompt can ride along in the trail.
        assertThat(auditEvent.getProperties()).containsOnlyKeys("id", "actorType", "actorUserId",
                "actorRef", "targetType", "targetId", "eventType", "outcome", "traceId", "details",
                "createdAt");

        // Recharge and manual adjustment write into the one shared ledger under their own types.
        Schema<?> ledgerEntry = browser.getComponents().getSchemas().get("PointLedgerEntry");
        assertThat(((Schema<?>) ledgerEntry.getProperties().get("entryType")).getEnum())
                .extracting(Object::toString).contains("RECHARGE", "ADMIN_ADJUSTMENT");
        assertThat(((Schema<?>) ledgerEntry.getProperties().get("businessType")).getEnum())
                .extracting(Object::toString).contains("RECHARGE", "ADMIN_ADJUSTMENT");

        // The commercial writes that can be throttled must say so, or a client has no way to tell a
        // 429 from an undocumented response.
        for (String path : List.of("/api/v1/billing/recharge-orders", "/api/v1/billing/invoices",
                "/api/v1/billing/feedback")) {
            assertResponseCodes(browser.getPaths().get(path).getPost(), "429");
        }

        // ------------------------------------------------------------ stage 2: rate card

        Schema<?> rateCard = browser.getComponents().getSchemas().get("RateCard");
        assertAdditionalPropertiesFalse(rateCard);
        // A placeholder rate is not the agreed price, so the flag that says which one applies is
        // part of the contract rather than a client-side convention.
        assertThat(rateCard.getRequired()).contains("productionRate", "versionId", "tiers");
        assertThat(rateCard.getDescription()).contains("productionRate: false");
        assertThat(((Schema<?>) rateCard.getProperties().get("versionId")).getTypes())
                .containsExactlyInAnyOrder("string", "null");
        assertResolvesTo(((Schema<?>) rateCard.getProperties().get("tiers")).getItems(),
                "RechargeTier", "amountCents");
        assertThat(rateCard.getProperties()).doesNotContainKeys("apiV3Key", "privateKey", "appId");

        Schema<?> tier = browser.getComponents().getSchemas().get("RechargeTier");
        assertAdditionalPropertiesFalse(tier);
        // The rate is points per yuan, so amounts that are not whole yuan cannot be priced at all.
        assertThat(((Schema<?>) tier.getProperties().get("amountCents")).getMultipleOf())
                .isEqualByComparingTo("100");
        assertThat(((Schema<?>) tier.getProperties().get("points")).getDescription())
                .contains("one ledger entry");

        Schema<?> pricingVersion = browser.getComponents().getSchemas().get("PricingVersion");
        assertAdditionalPropertiesFalse(pricingVersion);
        assertThat(((Schema<?>) pricingVersion.getProperties().get("status")).getEnum())
                .extracting(Object::toString).containsExactly("DRAFT", "ACTIVE", "RETIRED");
        Operation updateVersion = browser.getPaths()
                .get("/api/v1/admin/billing/pricing-versions/{versionId}").getPut();
        assertResponseCodes(updateVersion, "200", "401", "403", "404", "409", "422");
        Schema<?> updateVersionRequest = browser.getComponents().getSchemas()
                .get("UpdatePricingVersionRequest");
        assertAdditionalPropertiesFalse(updateVersionRequest);
        // A draft is edited against the version the operator last read, not blind.
        assertThat(updateVersionRequest.getRequired()).contains("expectedVersion");
        Schema<?> createVersion = browser.getComponents().getSchemas()
                .get("CreatePricingVersionRequest");
        assertThat(createVersion.getProperties())
                .containsOnlyKeys("pointsPerYuan", "minRechargeAmountCents", "maxRechargeAmountCents",
                        "note", "tiers");
        assertThat(createVersion.getProperties()).doesNotContainKeys(
                "status", "versionNo", "createdBy", "publishedBy");
        Schema<?> createTier = browser.getComponents().getSchemas().get("PricingTierRequest");
        assertThat(createTier.getProperties()).containsOnlyKeys(
                "amountCents", "giftPoints", "label", "sortOrder");
        // Publishing is its own endpoint, so a draft can never go live as a side effect of an edit.
        assertResponseCodes(browser.getPaths()
                .get("/api/v1/admin/billing/pricing-versions/{versionId}/publish").getPost(), "200");
        assertResponseCodes(browser.getPaths()
                .get("/api/v1/admin/billing/pricing-versions/{versionId}/retire").getPost(), "200");

        // ------------------------------------------------------------ stage 2: rate limits

        Schema<?> rateLimitRule = browser.getComponents().getSchemas().get("RateLimitRule");
        assertAdditionalPropertiesFalse(rateLimitRule);
        assertThat(((Schema<?>) rateLimitRule.getProperties().get("action")).getEnum())
                .extracting(Object::toString)
                .containsExactly("RECHARGE_ORDER_CREATE", "INVOICE_APPLICATION_SUBMIT",
                        "FEEDBACK_SUBMIT");
        // The published bounds are the schema's own bounds, so an operator typo is not a 500.
        assertThat(((Schema<?>) rateLimitRule.getProperties().get("windowSeconds")).getMaximum())
                .isEqualByComparingTo("86400");
        assertThat(((Schema<?>) rateLimitRule.getProperties().get("maxAttempts")).getMaximum())
                .isEqualByComparingTo("100000");
        Schema<?> updateRateLimit = browser.getComponents().getSchemas().get("UpdateRateLimitRequest");
        assertThat(updateRateLimit.getProperties())
                .containsOnlyKeys("windowSeconds", "maxAttempts", "enabled", "expectedVersion");
        assertThat(((Schema<?>) updateRateLimit.getProperties().get("expectedVersion")).getDescription())
                .contains("0");
        // Defaults are reported, not hidden: an unconfigured rule is still a rule in force.
        assertThat(((Schema<?>) rateLimitRule.getProperties().get("configured")).getDescription())
                .contains("default");

        // ------------------------------------------------------------ stage 2: invoices

        Schema<?> invoiceCapability = browser.getComponents().getSchemas().get("InvoiceCapability");
        assertAdditionalPropertiesFalse(invoiceCapability);
        assertThat(((Schema<?>) invoiceCapability.getProperties().get("deliveryStatus")).getEnum())
                .extracting(Object::toString)
                .containsExactly("INVOICE_MANUAL_EMAIL");
        assertThat(((Schema<?>) invoiceCapability.getProperties().get("webhookStatus")).getEnum())
                .extracting(Object::toString)
                .containsExactly("INVOICE_WEBHOOK_READY", "INVOICE_WEBHOOK_NOT_CONFIGURED");
        assertThat(((Schema<?>) invoiceCapability.getProperties().get("applicationsStatus")).getEnum())
                .extracting(Object::toString)
                .containsExactly("INVOICE_APPLICATIONS_OPEN", "INVOICE_MODULE_DISABLED");
        // Names of what is missing, never the values themselves.
        assertThat(((Schema<?>) invoiceCapability.getProperties().get("deliveryMissing")).getDescription())
                .contains("Never values");
        assertThat(invoiceCapability.getProperties()).doesNotContainKeys(
                "smtpHost", "smtpPassword", "mailHost", "credential");

        Schema<?> invoice = browser.getComponents().getSchemas().get("InvoiceApplication");
        assertAdditionalPropertiesFalse(invoice);
        assertThat(((Schema<?>) invoice.getProperties().get("status")).getEnum())
                .extracting(Object::toString)
                .containsExactly("SUBMITTED", "UNDER_REVIEW", "APPROVED", "DELIVERED", "REJECTED",
                        "CANCELLED");
        assertBusinessId(invoice.getProperties().get("id"));
        // Approval is internal review; manual send confirmation does not prove inbox receipt.
        assertThat(((Schema<?>) invoice.getProperties().get("deliveredAt")).getDescription())
                .contains("No recipient receipt");
        assertThat(((Schema<?>) invoice.getProperties().get("approvedAt")).getDescription())
                .contains("Not an issue date");
        assertThat(((Schema<?>) invoice.getProperties().get("taxNumber")).getTypes())
                .containsExactlyInAnyOrder("string", "null");

        Schema<?> createInvoice = browser.getComponents().getSchemas()
                .get("CreateInvoiceApplicationRequest");
        assertAdditionalPropertiesFalse(createInvoice);
        assertThat(createInvoice.getProperties()).containsOnlyKeys("invoiceType", "titleType", "title",
                "taxNumber", "amountCents", "deliveryEmail", "remark");
        assertThat(createInvoice.getProperties()).doesNotContainKeys("userId", "status", "applicationNo");
        // The application cites no order: it records what the applicant states.
        assertThat(((Schema<?>) createInvoice.getProperties().get("amountCents")).getDescription())
                .contains("No order");
        assertThat(createInvoice.getRequired()).contains("invoiceType", "titleType", "title",
                "amountCents", "deliveryEmail");

        Operation deliver = browser.getPaths()
                .get("/api/v1/admin/billing/invoices/{applicationId}/deliver").getPost();
        assertResponseCodes(deliver, "200", "401", "403", "404", "409", "422");
        assertThat(deliver.getDescription()).contains("does not send mail");
        assertThat(deliver.getRequestBody().getContent().get("application/json")).isNotNull();
        assertResponseCodes(browser.getPaths()
                .get("/api/v1/admin/billing/invoices/{applicationId}/webhook/retry").getPost(),
                "200", "401", "403", "404", "409");
        assertResponseCodes(browser.getPaths()
                .get("/api/v1/admin/billing/invoices/{applicationId}/reject").getPost(), "422");
        Schema<?> rejectRequest = browser.getComponents().getSchemas()
                .get("RejectInvoiceApplicationRequest");
        assertThat(rejectRequest.getRequired()).containsExactly("reason");

        // ------------------------------------------------------------ stage 2: notifications

        Operation inbox = browser.getPaths().get("/api/v1/billing/notifications").getGet();
        assertThat(browser.getPaths().get("/api/v1/billing/point-ledger").getGet().getParameters())
                .filteredOn(parameter -> "ledgerId".equals(parameter.getName()))
                .singleElement().satisfies(parameter -> {
                    assertThat(parameter.getIn()).isEqualTo("query");
                    // The parser resolves references; assert the actual string-ID wire contract.
                    assertThat(parameter.getSchema().getTypes()).contains("string");
                    assertThat(parameter.getSchema().getPattern()).isEqualTo("^[1-9][0-9]{0,18}$");
                });
        assertThat(inbox.getParameters()).filteredOn(parameter -> "category".equals(parameter.getName()))
                .singleElement().satisfies(parameter -> {
                    assertThat(parameter.getIn()).isEqualTo("query");
                    assertThat(parameter.getSchema().getEnum()).extracting(Object::toString)
                            .containsExactly("RECHARGE", "POINTS", "INVOICE", "FEEDBACK", "SYSTEM");
                });
        assertThat(inbox.getParameters()).filteredOn(parameter -> "unreadOnly".equals(parameter.getName()))
                .singleElement().satisfies(parameter -> {
                    assertThat(parameter.getIn()).isEqualTo("query");
                    assertThat(parameter.getSchema().getTypes()).contains("boolean");
                    assertThat(parameter.getSchema().getDefault()).isEqualTo(false);
                });

        Schema<?> notification = browser.getComponents().getSchemas().get("Notification");
        assertAdditionalPropertiesFalse(notification);
        assertThat(((Schema<?>) notification.getProperties().get("category")).getEnum())
                .extracting(Object::toString)
                .containsExactly("RECHARGE", "POINTS", "INVOICE", "FEEDBACK", "SYSTEM");
        // A deep link is a kind plus an id, so the client builds the route and no URL is stored.
        assertThat(((Schema<?>) notification.getProperties().get("targetType")).getDescription())
                .contains("never a URL");
        assertThat(((Schema<?>) notification.getProperties().get("targetId")).getTypes())
                .containsExactlyInAnyOrder("string", "null");
        Schema<?> notificationCapability = browser.getComponents().getSchemas()
                .get("NotificationCapability");
        assertAdditionalPropertiesFalse(notificationCapability);
        assertThat(((Schema<?>) notificationCapability.getProperties().get("mailStatus")).getEnum())
                .extracting(Object::toString)
                .containsExactly("MAIL_DELIVERY_READY", "MAIL_DELIVERY_NOT_CONFIGURED",
                        "MAIL_DELIVERY_NOT_IMPLEMENTED");
        assertThat(((Schema<?>) notificationCapability.getProperties().get("inAppStatus")).getEnum())
                .extracting(Object::toString)
                .containsExactly("NOTIFICATION_IN_APP_AVAILABLE", "NOTIFICATION_MODULE_DISABLED");
        Schema<?> unreadCount = browser.getComponents().getSchemas().get("UnreadNotificationCount");
        assertThat(unreadCount.getProperties()).containsOnlyKeys("unread");
        // Read and read-all are the only mutations: an inbox is never edited or deleted through this API.
        assertThat(browser.getPaths().get("/api/v1/billing/notifications").getDelete()).isNull();
        assertThat(browser.getPaths().get("/api/v1/billing/notifications").getPost()).isNull();
        for (String path : browser.getPaths().keySet().stream()
                .filter(path -> path.startsWith("/api/v1/billing/notifications")).toList()) {
            assertThat(browser.getPaths().get(path).readOperations()).as(path)
                    .noneSatisfy(operation -> assertThat(operation.getOperationId())
                            .contains("Delete"));
        }
        assertResponseCodes(browser.getPaths()
                .get("/api/v1/billing/notifications/read-all").getPost(), "200");

        // ------------------------------------------------------------ stage 2: feedback

        Schema<?> feedback = browser.getComponents().getSchemas().get("Feedback");
        assertAdditionalPropertiesFalse(feedback);
        assertThat(((Schema<?>) feedback.getProperties().get("status")).getEnum())
                .extracting(Object::toString)
                .containsExactly("SUBMITTED", "ACKNOWLEDGED", "RESOLVED", "CLOSED");
        // There is no private-note channel, so the reply is what the author reads — and it is
        // nullable until someone has actually answered.
        assertThat(((Schema<?>) feedback.getProperties().get("reply")).getTypes())
                .containsExactlyInAnyOrder("string", "null");
        assertThat(feedback.getProperties()).doesNotContainKeys("internalNote", "assignee", "priority");
        Schema<?> handleFeedback = browser.getComponents().getSchemas().get("HandleFeedbackRequest");
        assertAdditionalPropertiesFalse(handleFeedback);
        assertThat(handleFeedback.getProperties()).containsOnlyKeys("status", "reply");
        assertThat(handleFeedback.getRequired()).containsExactlyInAnyOrder("status", "reply");
        assertThat(((Schema<?>) handleFeedback.getProperties().get("status")).getEnum())
                .extracting(Object::toString)
                .containsExactly("ACKNOWLEDGED", "RESOLVED", "CLOSED");

        // ------------------------------------------------------------ stage 2: bookkeeping

        Schema<?> summary = browser.getComponents().getSchemas().get("BookkeepingSummary");
        assertAdditionalPropertiesFalse(summary);
        assertThat(summary.getRequired()).contains("generatedAt", "recharge", "invoice", "feedback",
                "ledger", "ratePlan", "platformAvailablePoints", "platformFrozenPoints");
        assertResolvesTo((Schema<?>) summary.getProperties().get("ratePlan"), "RatePlan",
                "productionRate");
        assertThat(((Schema<?>) summary.getProperties().get("platformAvailablePoints")).getDescription())
                .contains("Platform-wide");
        Schema<?> invoiceSummary = browser.getComponents().getSchemas().get("InvoiceSummary");
        assertThat(((Schema<?>) invoiceSummary.getProperties().get("deliveredAmountCents"))
                .getDescription()).contains("DELIVERED");
        Schema<?> adminLedgerEntry = browser.getComponents().getSchemas().get("AdminPointLedgerEntry");
        assertAdditionalPropertiesFalse(adminLedgerEntry);
        assertBusinessId(adminLedgerEntry.getProperties().get("userId"));
        assertThat(((Schema<?>) adminLedgerEntry.getProperties().get("operatorId")).getDescription())
                .contains("Not a business-type discriminator");

        // Bookkeeping is a read surface: no path under it mutates anything.
        assertThat(browser.getPaths().keySet().stream()
                .filter(path -> path.startsWith("/api/v1/admin/billing/bookkeeping")).toList())
                .containsExactly("/api/v1/admin/billing/bookkeeping/summary");
        assertThat(browser.getPaths().get("/api/v1/admin/billing/point-ledger").getPost()).isNull();
        assertThat(browser.getPaths().get("/api/v1/admin/billing/point-ledger").getDelete()).isNull();

        // Every new commercial surface is authenticated, on both sides of the role boundary.
        for (String path : List.of("/api/v1/billing/pricing/active", "/api/v1/billing/invoice-capability",
                "/api/v1/billing/invoices", "/api/v1/billing/invoices/{applicationId}",
                "/api/v1/billing/invoices/{applicationId}/cancel",
                "/api/v1/billing/notification-capability", "/api/v1/billing/notifications",
                "/api/v1/billing/notifications/unread-count",
                "/api/v1/billing/notifications/{notificationId}/read",
                "/api/v1/billing/notifications/read-all", "/api/v1/billing/feedback",
                "/api/v1/billing/feedback/{feedbackId}",
                "/api/v1/admin/billing/bookkeeping/summary", "/api/v1/admin/billing/point-ledger",
                "/api/v1/admin/billing/point-ledger/summary",
                "/api/v1/admin/billing/pricing-versions",
                "/api/v1/admin/billing/pricing-versions/{versionId}",
                "/api/v1/admin/billing/pricing-versions/{versionId}/publish",
                "/api/v1/admin/billing/pricing-versions/{versionId}/retire",
                "/api/v1/admin/billing/rate-limits",
                "/api/v1/admin/billing/rate-limits/{action}", "/api/v1/admin/billing/invoices",
                "/api/v1/admin/billing/invoices/{applicationId}",
                "/api/v1/admin/billing/invoices/{applicationId}/accept",
                "/api/v1/admin/billing/invoices/{applicationId}/approve",
                "/api/v1/admin/billing/invoices/{applicationId}/reject",
                "/api/v1/admin/billing/invoices/{applicationId}/deliver",
                "/api/v1/admin/billing/feedback",
                "/api/v1/admin/billing/feedback/{feedbackId}/handle")) {
            assertThat(browser.getPaths().get(path).readOperations()).as(path).isNotEmpty()
                    .allSatisfy(operation -> assertThat(operation.getSecurity()).as(path).isNotEmpty());
        }
    }

    private void assertProviderIdentity(Schema<?> schema, String provider, String model, Boolean mock) {
        assertThat(((Schema<?>) schema.getProperties().get("provider")).getConst()).isEqualTo(provider);
        assertThat(((Schema<?>) schema.getProperties().get("model")).getConst()).isEqualTo(model);
        if (mock != null) {
            assertThat(((Schema<?>) schema.getProperties().get("mock")).getConst()).isEqualTo(mock);
        }
    }

    private void assertBusinessId(Schema<?> schema) {
        if (schema.get$ref() != null) {
            assertThat(schema.get$ref()).endsWith("/BusinessId");
            return;
        }
        assertThat(schema.getTypes()).contains("string");
        assertThat(schema.getPattern()).isEqualTo("^[1-9][0-9]{0,18}$");
    }

    /**
     * A schema the document reaches through {@code $ref}. The parser resolves references, so the
     * target usually arrives inlined rather than as a reference; either form is accepted, as long as
     * it really is the named schema rather than something that merely looks like it.
     */
    private void assertResolvesTo(Schema<?> schema, String expectedName, String probeProperty) {
        assertThat(schema).as(expectedName).isNotNull();
        if (schema.get$ref() != null) {
            assertThat(schema.get$ref()).endsWith("/" + expectedName);
            return;
        }
        assertThat(schema.getProperties()).as(expectedName).containsKey(probeProperty);
    }

    private Schema<?> artifactItem(Schema<?> resultSchema) {
        Schema<?> artifacts = (Schema<?>) resultSchema.getProperties().get("artifacts");
        return artifacts.getItems();
    }

    private void assertAdditionalPropertiesFalse(Schema<?> schema) {
        Object value = schema.getAdditionalProperties();
        if (value instanceof Boolean booleanValue) {
            assertThat(booleanValue).isFalse();
            return;
        }
        assertThat(value).isInstanceOf(Schema.class);
        assertThat(((Schema<?>) value).getBooleanSchemaValue()).isFalse();
    }

    private OpenAPI parse(String resource) throws Exception {
        URL location = getClass().getResource(resource);
        assertThat(location).as(resource).isNotNull();
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(location.toExternalForm(), null, options);
        assertThat(result.getOpenAPI()).as(resource + " parser result").isNotNull();
        assertThat(result.getMessages()).as(resource + " parser messages").isEmpty();
        return result.getOpenAPI();
    }

    private JsonNode readJson(String resource) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            return objectMapper.readTree(input);
        }
    }

    private String normalizedSha256(String resource) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            String normalized = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8))
            );
        }
    }
}
