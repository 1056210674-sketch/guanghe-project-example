package com.guanghe.backend.task;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.conversation.ConversationEventStreamService;
import com.guanghe.backend.article.ArticleFullService;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.geo.GeoTaskDtos;
import com.guanghe.backend.geo.GeoArticleWorkflowService;
import com.guanghe.backend.geo.GeoTaskService;
import com.guanghe.backend.workflow.WorkflowPlanService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class TaskInterfaceIntegrationTests {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("zhixiaohe.paid-task.enabled", () -> "true");
        // This MVC contract test stubs TaskControlService. A concurrent watchdog call on that
        // same mock can race Mockito's when(...).thenReturn(...); recovery has its own tests.
        registry.add("zhixiaohe.paid-task.execution-watchdog-enabled", () -> "false");
        registry.add("zhixiaohe.paid-task.token-secret", () -> "test-only-secret-material-at-least-32-bytes");
        registry.add("zhixiaohe.paid-task.image-mock-pricing-version-id", () -> "6001");
        registry.add("zhixiaohe.paid-task.image-mock-points", () -> "7");
        registry.add("zhixiaohe.paid-task.video-test-pricing-version-id", () -> "7001");
        registry.add("zhixiaohe.paid-task.video-test-points", () -> "11");
    }

    @Autowired MockMvc mvc;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;
    @MockitoBean TaskControlService service;
    @MockitoBean GeoArticleWorkflowService geoArticleWorkflowService;
    @MockitoBean GeoTaskService geoTaskService;
    @MockitoBean ConversationEventStreamService events;
    @MockitoBean WorkflowPlanService workflowPlanService;
    @MockitoBean ArticleFullService articleFullService;
    @MockitoBean com.guanghe.backend.geo.GeoPeriodReportService periodReports;

    @Test
    void notificationLedgerDeepLinkAcceptsAStringIdAndRejectsNonPositiveIds() throws Exception {
        when(service.listPointLedger(any(), eq(9_007_199_254_740_993L), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(PageResponse.of(java.util.List.of(), 1, 20, 0));
        mvc.perform(get("/api/v1/billing/point-ledger").with(user(AccountRole.USER))
                        .param("ledgerId", "9007199254740993"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(get("/api/v1/billing/point-ledger").with(user(AccountRole.USER))
                        .param("ledgerId", "0"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void userCanReadStringTaskIdAndPointAccount() throws Exception {
        OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
        com.fasterxml.jackson.databind.node.ObjectNode result =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        result.put("provider","QWEN_IMAGE");
        result.put("model","qwen-image-3.0");
        result.put("providerCostReported",false);
        com.fasterxml.jackson.databind.node.ObjectNode artifact=result.putObject("asset");
        artifact.put("assetId","1900012345678901800");
        artifact.put("assetType","IMAGE");
        artifact.put("status","READY");
        artifact.put("contentType","image/png");
        artifact.put("sizeBytes",1024);
        artifact.put("sha256","a".repeat(64));
        artifact.put("width",1024);
        artifact.put("height",1024);
        artifact.put("safetyStatus","TEST_BYPASS");
        when(service.getTask(any(), anyLong())).thenReturn(new TaskDtos.TaskResponse(
                "1900012345678901700","IMAGE","SUCCEEDED","COMPLETED","已完成",
                "1900012345678901100","1900012345678901200",7,7,0,0,
                null,result,null,now,now));
        when(service.getPointAccount(any())).thenReturn(new TaskDtos.PointAccountResponse(
                "1900012345678901000",93,7,2,now));
        when(service.listPointLedger(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(PageResponse.of(
                java.util.List.of(new TaskDtos.PointLedgerEntryResponse(
                        "1900012345678901900", "TASK_FREEZE", "任务确认，积分已冻结",
                        "TASK", "1900012345678901700", 7, -7, 7, 93, 7, now)),
                1, 20, 1));

        mvc.perform(get("/api/v1/tasks/1900012345678901700").with(user(AccountRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.frozenPoints").value(0))
                .andExpect(jsonPath("$.data.result.provider").value("QWEN_IMAGE"))
                .andExpect(jsonPath("$.data.result.model").value("qwen-image-3.0"))
                .andExpect(jsonPath("$.data.result.asset.assetId").isString())
                .andExpect(jsonPath("$.data.result.asset.providerUrl").doesNotExist());
        mvc.perform(get("/api/v1/billing/point-account").with(user(AccountRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").isString());
        mvc.perform(get("/api/v1/billing/point-ledger").with(user(AccountRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").isString())
                .andExpect(jsonPath("$.data.items[0].businessId").isString())
                .andExpect(jsonPath("$.data.items[0].title").value("任务确认，积分已冻结"))
                .andExpect(jsonPath("$.data.items[0].amountPoints").value(7));
        Set<String> paths = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .collect(Collectors.toSet());
        assertThat(paths)
                .contains("/api/v1/billing/point-account")
                .contains("/api/v1/billing/point-ledger")
                .doesNotContain("/api/v1/points/account");
    }

    @Test
    void adminIsDeniedAndBrowserCannotSubmitAPrice() throws Exception {
        mvc.perform(get("/api/v1/tasks/1900012345678901700").with(user(AccountRole.ADMIN)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/billing/point-account").with(user(AccountRole.ADMIN)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/billing/point-ledger").with(user(AccountRole.ADMIN)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/task-drafts/1900012345678901600/confirm")
                        .with(user(AccountRole.USER)).header("Idempotency-Key", java.util.UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"confirmationToken":"opaque","inputHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","pricingVersionId":"6001","quotedPoints":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void legacyConversationConfirmationFailsClosedForOldClients() throws Exception {
        when(service.confirmActiveConversationDraft(any(), anyLong(), any()))
                .thenThrow(new com.guanghe.backend.common.api.BusinessException(
                        com.guanghe.backend.common.api.ErrorCode.TASK_CONFIRMATION_MISMATCH));

        mvc.perform(post("/api/v1/conversations/{conversationId}/actions/confirm-active-task-draft",
                        "1900012345678901400")
                        .with(user(AccountRole.USER))
                        .header("Idempotency-Key", java.util.UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CONFIRMATION_MISMATCH"));

        mvc.perform(post("/api/v1/conversations/{conversationId}/actions/confirm-active-task-draft",
                        "1900012345678901400")
                        .with(user(AccountRole.ADMIN))
                        .header("Idempotency-Key", java.util.UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    void malformedTaskIdIsValidationFailureNotServerError() throws Exception {
        mvc.perform(get("/api/v1/tasks/not-a-number").with(user(AccountRole.USER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void formDraftRevisionAbandonTaskListAndSafeEventsAreMapped() throws Exception {
        OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
        TaskDtos.DraftResponse draft=new TaskDtos.DraftResponse(
                "1900012345678901600",null,"FORM","IMAGE",1,"WAITING_CONFIRMATION",
                "1900012345678901100","1900012345678901200",
                json.readTree("{\"requestText\":\"主图\",\"mode\":\"AGENT_ASSISTED\",\"agentSummary\":\"摘要\",\"aspectRatio\":\"1:1\",\"count\":1}"),
                json.createArrayNode(),"a".repeat(64),"6001",7,"v1.token",now.plusMinutes(10),now);
        TaskDtos.DraftResponse revision=new TaskDtos.DraftResponse(
                "1900012345678901601",draft.id(),"FORM","IMAGE",1,"WAITING_CONFIRMATION",
                draft.spaceId(),draft.productId(),draft.parameters(),draft.warnings(),"b".repeat(64),
                "6001",7,"v1.revised",now.plusMinutes(10),now);
        TaskDtos.DraftResponse abandoned=new TaskDtos.DraftResponse(
                revision.id(),revision.previousDraftId(),"FORM","IMAGE",1,"ABANDONED",
                revision.spaceId(),revision.productId(),revision.parameters(),revision.warnings(),
                revision.inputHash(),"6001",7,null,revision.expiresAt(),now);
        TaskDtos.TaskResponse task=new TaskDtos.TaskResponse(
                "1900012345678901700","IMAGE","QUEUED","QUEUED","等待执行",
                draft.spaceId(),draft.productId(),7,0,7,0,null,json.createObjectNode(),null,now,now);
        when(service.createDraft(any(),any(),any())).thenReturn(draft);
        when(service.reviseDraft(any(),anyLong(),any(),any())).thenReturn(revision);
        when(service.abandonDraft(any(),anyLong())).thenReturn(abandoned);
        when(service.listTasks(any(),any(),any(),any(),any(),any(),anyInt(),anyInt()))
                .thenReturn(PageResponse.of(java.util.List.of(task),1,20,1));
        when(service.taskEvents(any(),anyLong())).thenReturn(java.util.List.of(
                new TaskDtos.TaskEventResponse("1900012345678901800",4,"task.queued",
                        "QUEUED","QUEUED",null,now)));
        String body="""
                {"conversationId":"1900012345678901400","taskType":"IMAGE",
                 "spaceId":"1900012345678901100","productId":"1900012345678901200",
                 "parameterSchemaVersion":1,"parameters":{"requestText":"主图",
                 "mode":"AGENT_ASSISTED","agentSummary":"摘要","aspectRatio":"1:1","count":1}}
                """;

        mvc.perform(post("/api/v1/task-drafts").with(user(AccountRole.USER))
                        .header("Idempotency-Key",java.util.UUID.randomUUID())
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.draftSource").value("FORM"));
        mvc.perform(post("/api/v1/task-drafts/{id}/revisions",draft.id()).with(user(AccountRole.USER))
                        .header("Idempotency-Key",java.util.UUID.randomUUID())
                        .contentType("application/json").content("""
                                {"spaceId":"1900012345678901100","productId":"1900012345678901200",
                                "parameterSchemaVersion":1,"parameters":{"requestText":"主图",
                                "mode":"AGENT_ASSISTED","agentSummary":"摘要","aspectRatio":"1:1","count":1}}
                                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.previousDraftId").value(draft.id()));
        mvc.perform(post("/api/v1/task-drafts/{id}/abandon",revision.id()).with(user(AccountRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ABANDONED"))
                .andExpect(jsonPath("$.data.confirmationToken").doesNotExist());
        mvc.perform(get("/api/v1/tasks").with(user(AccountRole.USER))
                        .param("taskType","IMAGE").param("status","QUEUED")
                        .param("conversationId","1900012345678901400")
                        .param("spaceId",draft.spaceId()).param("productId",draft.productId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").isString());
        mvc.perform(get("/api/v1/tasks/{id}/events",task.id()).with(user(AccountRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].sequence").value(4))
                .andExpect(jsonPath("$.data[0].eventType").value("task.queued"))
                .andExpect(jsonPath("$.data[0].payload").doesNotExist());
    }

    @Test
    void adminIsDeniedFromAllNewTaskUserRoutes() throws Exception {
        mvc.perform(get("/api/v1/tasks").with(user(AccountRole.ADMIN))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tasks/1/events").with(user(AccountRole.ADMIN))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/task-drafts").with(user(AccountRole.ADMIN))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void geoRunAndReportRoutesAreUserOnlyAndUseStringIds() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        GeoTaskDtos.RunSummary run = new GeoTaskDtos.RunSummary(
                "1900012345678901900", "1900012345678901700", "1900012345678902200",
                "PARTIAL", "STANDARD", 6, 5, 1, 0, now, now);
        when(geoTaskService.platforms(any())).thenReturn(java.util.List.of(
                new GeoTaskDtos.PlatformResponse("DOUBAO", "豆包", true, false, null)));
        when(geoTaskService.runs(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(java.util.List.of(run), 1, 20, 1));
        when(geoTaskService.run(any(), anyLong())).thenReturn(new GeoTaskDtos.RunDetail(
                run.id(), run.taskId(), run.topicId(), "1900012345678901100", null,
                run.status(), run.sampleMode(), run.expectedUnits(), run.succeededUnits(),
                run.failedUnits(), run.missingUnits(),
                new GeoTaskDtos.TopicSnapshot(run.topicId(), "1900012345678901100", null,
                        "品牌诊断", "中国大陆", 2),
                java.util.List.of(), java.util.List.of("DOUBAO"), null, java.util.List.of(),
                "geo-observation-v1", "geo-synthesis-v1", now, now, now));
        when(geoTaskService.runProgress(any(), anyLong())).thenReturn(
                new GeoTaskDtos.RunProgressResponse(run.id(), run.taskId(), run.status(),
                        "GEO_SYNTHESIZING", 100, 6, 6, 5, 1, 0,
                        "1900012345678902300", true, false, now, now));

        mvc.perform(get("/api/v1/geo/platforms").with(user(AccountRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].liveStatus").doesNotExist());
        mvc.perform(get("/api/v1/geo/runs").with(user(AccountRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").isString())
                .andExpect(jsonPath("$.data.items[0].status").value("PARTIAL"));
        mvc.perform(get("/api/v1/geo/runs/{id}", run.id()).with(user(AccountRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.taskId").isString());
        mvc.perform(get("/api/v1/geo/runs/{id}/progress", run.id()).with(user(AccountRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value(run.id()))
                .andExpect(jsonPath("$.data.percent").value(100))
                .andExpect(jsonPath("$.data.terminal").value(true))
                .andExpect(jsonPath("$.data.reportId").value("1900012345678902300"));
        mvc.perform(get("/api/v1/geo/runs").with(user(AccountRole.ADMIN)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/geo/runs/{id}/progress", run.id()).with(user(AccountRole.ADMIN)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/geo/reports").with(user(AccountRole.ADMIN)))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor user(AccountRole role) {
        AuthenticatedAccount principal=new AuthenticatedAccount(41,"actor",role,1, AccountStatus.ACTIVE,null,null,null);
        return authentication(new UsernamePasswordAuthenticationToken(principal,null,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_"+role.name()))));
    }
}
