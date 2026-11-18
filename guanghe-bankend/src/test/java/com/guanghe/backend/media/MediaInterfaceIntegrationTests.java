package com.guanghe.backend.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.task.TaskDtos;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "zhixiaohe.media.enabled=true",
        "zhixiaohe.media.base-url=https://media.example.test",
        "zhixiaohe.media.app-key=0123456789abcdef0123456789abcdef"
})
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class MediaInterfaceIntegrationTests {
    private static final String RESOURCE_ID = "1900012345678905000";
    private static final String SUBMISSION_ID = "1900012345678905001";
    private static final String ARTICLE_ID = "1900012345678905002";
    private static final String SNAPSHOT_ID = "1900012345678905003";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @MockitoBean private MediaSubmissionService service;
    @MockitoBean private MediaArticleImages articleImages;

    @BeforeEach
    void setUp() {
        MediaDtos.ResourceSummary resource = new MediaDtos.ResourceSummary(RESOURCE_ID,
                "MEDIA_PROCUREMENT", "1", "44990", "媒体名称", "ACTIVE", "NEWS",
                "综合", "全国", 3, json.createObjectNode().put("supportsLink", true),
                49_700, 49_700, now().plusMinutes(10), now());
        when(service.resources(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(resource), 1, 20, 1));
        when(service.resource(any(), anyLong())).thenReturn(resource);
        when(service.recommendations(any(), anyLong(), anyInt())).thenReturn(
                new MediaDtos.RecommendationSet(ARTICLE_ID, 3, now(), false,
                        new MediaDtos.RecommendationStrategy("BALANCED", "全国", null, false,
                                List.of("食品餐饮"), 10020, List.of("暂无历史过稿率数据")), List.of(
                        new MediaDtos.Recommendation(1, RESOURCE_ID, "INDUSTRY_CATEGORY_MATCH",
                                "与已确认创作方案的目标发布平台匹配，且媒体当前可用。",
                                80, List.of("频道匹配"), resource))));
        when(service.quote(any(), any(), any())).thenReturn(new TaskDtos.DraftResponse(
                "1900012345678905004", null, "FORM", "MEDIA", 1,
                "WAITING_CONFIRMATION", "1900012345678901100", null,
                json.createObjectNode(), json.createArrayNode(), "a".repeat(64),
                "1900012345678905005", 49_700, "opaque-token", now().plusMinutes(10), now()));
        MediaDtos.SubmissionSummary summary = new MediaDtos.SubmissionSummary(SUBMISSION_ID,
                "1900012345678905006", "1900012345678905007", ARTICLE_ID, SNAPSHOT_ID,
                RESOURCE_ID, "媒体名称", "ACCEPTED", 49_700, 49_700,
                null, List.of(), null, now(), now());
        when(service.submissions(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(summary), 1, 20, 1));
        when(service.submission(any(), anyLong())).thenReturn(new MediaDtos.SubmissionDetail(
                summary.id(), summary.batchId(), summary.taskId(), summary.articleId(),
                summary.articleSnapshotId(), summary.mediaResourceId(), summary.mediaName(),
                summary.status(), summary.saleAmountCents(), summary.chargedPoints(), "工作日发布",
                null, List.of(), null, List.of(new MediaDtos.SubmissionEvent("1900012345678905008", 1,
                "SUBMISSION_ACCEPTED", "SUBMITTING", "ACCEPTED", null, now())), now(), now()));
        when(service.decodeCallback(any(), any(), any())).thenReturn(json.createObjectNode()
                .put("customer_order_id", "ZXH1900012345678905001"));
    }

    @Test
    void userCanUseCompleteMediaSurfaceAndIdsRemainStrings() throws Exception {
        mvc.perform(get("/api/v1/media/resources").with(user(AccountRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(RESOURCE_ID))
                .andExpect(jsonPath("$.data.items[0].saleAmountCents").value(49_700))
                .andExpect(jsonPath("$.data.items[0].upstreamPrice").doesNotExist());
        mvc.perform(get("/api/v1/media/resources/{id}", RESOURCE_ID).with(user(AccountRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(RESOURCE_ID));
        mvc.perform(get("/api/v1/media/recommendations").queryParam("articleId", ARTICLE_ID)
                        .with(user(AccountRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].mediaResourceId").value(RESOURCE_ID))
                .andExpect(jsonPath("$.data.items[0].resource.chargedPoints").value(49_700));
        mvc.perform(post("/api/v1/media/quotes").header("Idempotency-Key",
                        "00000000-0000-0000-0000-000000000013").with(user(AccountRole.USER))
                        .contentType("application/json").content("""
                                {"articleId":"%s","articleSnapshotId":"%s","items":[
                                  {"mediaResourceId":"%s","remark":"工作日发布"}]}
                                """.formatted(ARTICLE_ID, SNAPSHOT_ID, RESOURCE_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.taskType").value("MEDIA"))
                .andExpect(jsonPath("$.data.quotedPoints").value(49_700));
        mvc.perform(get("/api/v1/media/submissions").with(user(AccountRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(SUBMISSION_ID));
        mvc.perform(get("/api/v1/media/submissions").queryParam("taskId", "1900012345678905007")
                        .with(user(AccountRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].taskId").value("1900012345678905007"));
        verify(service).submissions(any(), any(), eq(1900012345678905007L), eq(1), eq(20));
        mvc.perform(get("/api/v1/media/submissions/{id}", SUBMISSION_ID).with(user(AccountRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.events[0].sequence").value(1));
    }

    @Test
    void malformedAndUnknownInputIsSafe422AndAdminIsForbidden() throws Exception {
        String malicious = "not-a-resource";
        String response = mvc.perform(get("/api/v1/media/resources/{id}", malicious)
                        .with(user(AccountRole.USER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").isString()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(malicious);
        mvc.perform(get("/api/v1/media/resources").queryParam("page", "abc")
                        .with(user(AccountRole.USER)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/media/submissions").queryParam("taskId", "0")
                        .with(user(AccountRole.USER)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/media/quotes").header("Idempotency-Key",
                        "00000000-0000-0000-0000-000000000013").with(user(AccountRole.USER))
                        .contentType("application/json").content("""
                                {"articleId":"%s","articleSnapshotId":"%s","items":[
                                  {"mediaResourceId":"%s"}],"price":1}
                                """.formatted(ARTICLE_ID, SNAPSHOT_ID, RESOURCE_ID)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/media/resources").with(user(AccountRole.ADMIN)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void onlyFrozenCallbackPathsArePublic() throws Exception {
        List<String> paths = List.of(
                "/api/v1/callbacks/media/order-progress",
                "/api/v1/callbacks/media/order-receipt",
                "/api/v1/callbacks/media/order-rejection",
                "/api/v1/callbacks/media/resource-update",
                "/api/callback/mt-order-progress",
                "/api/callback/mt-order-receipt",
                "/api/callback/mt-order-refund",
                "/api/callback/mt-resource-update");
        for (String path : paths) {
            mvc.perform(post(path)
                            .contentType("application/x-www-form-urlencoded")
                            .param("response", "opaque").param("sign", "digest").param("app_key", "key"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errorCode").value(""))
                    .andExpect(jsonPath("$.t").isString());
        }
        Set<String> callbackPaths = new LinkedHashSet<>();
        handlerMapping.getHandlerMethods().forEach((mapping, method) -> {
            if (method.getBeanType() == MediaCallbackController.class) {
                mapping.getPatternValues().forEach(callbackPaths::add);
            }
        });
        assertThat(callbackPaths).containsExactlyInAnyOrderElementsOf(paths);
    }

    @Test
    void invalidCallbackSignatureHasStableSafeResponseInsteadOf500() throws Exception {
        doThrow(new MediaProviderException("MEDIA_CALLBACK_SIGNATURE_INVALID", false))
                .when(service).processCallback(any(), any());

        String response = mvc.perform(post("/api/v1/callbacks/media/order-progress")
                        .contentType("application/x-www-form-urlencoded")
                        .param("response", "untrusted-envelope")
                        .param("sign", "untrusted-signature")
                        .param("app_key", "untrusted-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("MEDIA_CALLBACK_SIGNATURE_INVALID"))
                .andExpect(jsonPath("$.message").value("callback rejected"))
                .andExpect(jsonPath("$.t").isString())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("untrusted-envelope", "untrusted-signature", "untrusted-key");

        mvc.perform(post("/api/v1/callbacks/media/resource-update")
                        .contentType("application/json")
                        .content("{not-json"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("MEDIA_CALLBACK_PAYLOAD_INVALID"))
                .andExpect(jsonPath("$.message").value("callback rejected"));
    }

    @Test
    void controllerAndBrowserContractDeclareExactlyTheSameMediaOperations() {
        Set<String> implemented = new LinkedHashSet<>();
        handlerMapping.getHandlerMethods().forEach((mapping, method) -> {
            if (method.getBeanType() == MediaController.class) {
                mapping.getMethodsCondition().getMethods().forEach(httpMethod ->
                        mapping.getPatternValues().forEach(path -> implemented.add(httpMethod.name() + " " + path)));
            }
        });
        var openApi = new OpenAPIV3Parser().read(
                getClass().getResource("/contracts/openapi/browser-openapi.yaml").toExternalForm());
        Set<String> declared = new LinkedHashSet<>();
        openApi.getPaths().forEach((path, item) -> {
            if (path.startsWith("/api/v1/media")) {
                item.readOperationsMap().forEach((method, operation) ->
                        declared.add(method.name() + " " + path));
            }
        });
        assertThat(implemented).containsExactlyInAnyOrderElementsOf(declared).hasSize(6);
        assertThat(openApi.getComponents().getSchemas().get("CreateMediaQuoteRequest").getProperties())
                .doesNotContainKeys("userId", "price", "upstreamPrice", "provider", "model");
        assertThat(openApi.getComponents().getSchemas().get("MediaResource").getProperties())
                .doesNotContainKeys("upstreamPrice", "markupRate");
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor user(AccountRole role) {
        AuthenticatedAccount principal = new AuthenticatedAccount(7, "actor", role, 1,
                AccountStatus.ACTIVE, null, null, null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.of(2026, 9, 9, 0, 0, 0, 0, ZoneOffset.UTC);
    }
}
