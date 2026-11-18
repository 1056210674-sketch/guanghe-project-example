package com.guanghe.backend.article;

import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.media.MediaSubmissionService;
import com.guanghe.backend.media.MediaAdminService;
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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"zhixiaohe.article.enabled=true", "zhixiaohe.media.enabled=true",
        "zhixiaohe.media.base-url=https://media.example.test",
        "zhixiaohe.media.app-key=0123456789abcdef0123456789abcdef"})
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class ArticleInterfaceIntegrationTests {
    private static final String ARTICLE_ID = "1900012345678903100";
    private static final String SNAPSHOT_ID = "1900012345678903101";
    private static final String SPACE_ID = "1900012345678901100";

    @Autowired private MockMvc mvc;
    @Autowired @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @MockitoBean private ArticleService service;
    @MockitoBean private MediaSubmissionService media;
    @MockitoBean private MediaAdminService mediaAdmin;
    @MockitoBean private com.guanghe.backend.media.MediaArticleImages articleImages;

    @BeforeEach
    void setUp() {
        ArticleDtos.Response article = article();
        when(service.list(any(), any(), any(), any(), any(), anyInt(), anyInt(), any())).thenReturn(
                PageResponse.of(List.of(new ArticleDtos.Summary(ARTICLE_ID, SPACE_ID, null, "标题",
                        ArticleDtos.Status.DRAFT, 2, now(), now(), false, null, null)), 1, 20, 1));
        when(service.create(any(), any())).thenReturn(article);
        when(service.get(any(), anyLong())).thenReturn(article);
        when(service.update(any(), anyLong(), any(), any())).thenReturn(article);
        when(service.replaceOutline(any(), anyLong(), any())).thenReturn(article);
        when(service.replaceAssets(any(), anyLong(), any())).thenReturn(article);
        when(service.confirmTitle(any(), anyLong(), any())).thenReturn(article);
        when(service.saveCreativePlan(any(), anyLong(), any())).thenReturn(article);
        when(service.confirmCreativePlan(any(), anyLong(), any())).thenReturn(article);
        when(service.confirmBody(any(), anyLong(), any())).thenReturn(article);
        when(service.createSnapshot(any(), anyLong(), any())).thenReturn(snapshot());
        when(service.getSnapshot(any(), anyLong(), anyLong())).thenReturn(snapshot());
    }

    @Test
    void userCanInvokeCompleteArticleSurfaceAndIdsRemainStrings() throws Exception {
        mvc.perform(get("/api/v1/articles").with(user(AccountRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").isString());
        mvc.perform(post("/api/v1/articles").with(user(AccountRole.USER)).contentType("application/json")
                        .content("{\"spaceId\":\"" + SPACE_ID + "\",\"title\":\"标题\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").isString());
        mvc.perform(get("/api/v1/articles/{id}", ARTICLE_ID).with(user(AccountRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(patch("/api/v1/articles/{id}", ARTICLE_ID).with(user(AccountRole.USER))
                        .header("If-Match", "2").contentType("application/json")
                        .content("{\"version\":2,\"bodyHtml\":\"<p>正文</p>\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/articles/{id}/outline", ARTICLE_ID).with(user(AccountRole.USER))
                        .contentType("application/json")
                        .content("{\"version\":2,\"items\":[{\"key\":\"intro\",\"level\":2,"
                                + "\"heading\":\"开场\",\"brief\":null}]}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/articles/{id}/assets", ARTICLE_ID).with(user(AccountRole.USER))
                        .contentType("application/json").content("{\"version\":2,\"items\":[]}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/articles/{id}/workflow/title", ARTICLE_ID).with(user(AccountRole.USER))
                        .contentType("application/json").content("{\"version\":2,\"titleBatchVersion\":1,"
                                + "\"title\":\"标题\",\"source\":\"SELECTED\",\"candidateIndex\":1}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/articles/{id}/workflow/creative-plan", ARTICLE_ID)
                        .with(user(AccountRole.USER)).contentType("application/json")
                        .content("{\"version\":2,\"basePlanVersion\":1,\"plan\":{"
                                + "\"summary\":\"摘要\",\"coreMessage\":\"核心观点\","
                                + "\"targetAudience\":\"目标读者\",\"sections\":["
                                + "{\"key\":\"intro\",\"level\":2,\"heading\":\"开场\",\"brief\":\"写作要点\"},"
                                + "{\"key\":\"body\",\"level\":2,\"heading\":\"正文\",\"brief\":\"论证要点\"}],"
                                + "\"tone\":\"PROFESSIONAL\",\"targetLength\":\"MEDIUM\","
                                + "\"targetPlatform\":null,\"riskNotes\":[]}}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/articles/{id}/workflow/creative-plan/confirm", ARTICLE_ID)
                        .with(user(AccountRole.USER)).contentType("application/json")
                        .content("{\"version\":2,\"planVersion\":1}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/articles/{id}/workflow/body/confirm", ARTICLE_ID)
                        .with(user(AccountRole.USER)).contentType("application/json")
                        .content("{\"version\":2,\"goal\":\"INDUSTRY_MATCH\","
                                + "\"preferredRegion\":\"全国\",\"maxTotalPoints\":3000,"
                                + "\"requiresLink\":true}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/articles/{id}/submission-snapshots", ARTICLE_ID)
                        .with(user(AccountRole.USER)).contentType("application/json")
                        .content("{\"articleVersion\":2}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").isString());
        mvc.perform(get("/api/v1/articles/{id}/submission-snapshots/{snapshot}", ARTICLE_ID, SNAPSHOT_ID)
                        .with(user(AccountRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.articleId").isString());
        mvc.perform(delete("/api/v1/articles/{id}", ARTICLE_ID).with(user(AccountRole.USER)))
                .andExpect(status().isOk());
    }

    @Test
    void invalidIdentifiersAndPagesAreSafe422AndAdminIsForbidden() throws Exception {
        String malicious = "not-an-article";
        String body = mvc.perform(get("/api/v1/articles/{id}", malicious).with(user(AccountRole.USER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").isString()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(malicious);
        mvc.perform(get("/api/v1/articles").queryParam("page", "abc").with(user(AccountRole.USER)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/articles").with(user(AccountRole.ADMIN)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void controllerAndBrowserContractDeclareExactlyTheSameArticleOperations() {
        Set<String> implemented = new LinkedHashSet<>();
        handlerMapping.getHandlerMethods().forEach((mapping, method) -> {
            if (method.getBeanType() == ArticleController.class
                    || method.getBeanType() == ArticlePreparationMediaController.class) {
                mapping.getMethodsCondition().getMethods().forEach(httpMethod ->
                        mapping.getPatternValues().forEach(path -> implemented.add(httpMethod.name() + " " + path)));
            }
        });
        var openApi = new OpenAPIV3Parser().read(
                getClass().getResource("/contracts/openapi/browser-openapi.yaml").toExternalForm());
        Set<String> declared = new LinkedHashSet<>();
        openApi.getPaths().forEach((path, item) -> {
            if (path.startsWith("/api/v1/articles")) {
                item.readOperationsMap().forEach((method, operation) ->
                        declared.add(method.name() + " " + path));
            }
        });
        assertThat(implemented).containsExactlyInAnyOrderElementsOf(declared).hasSize(15)
                .contains("PUT /api/v1/articles/{articleId}/asset-collection");
    }

    private ArticleDtos.Response article() {
        return new ArticleDtos.Response(ARTICLE_ID, SPACE_ID, null, "标题", "<p>正文</p>", "正文",
                List.of(new ArticleDtos.OutlineResponse("intro", 1, 2, "开场", null)), List.of(),
                ArticleDtos.Status.DRAFT, null, 2, now(), now());
    }

    private ArticleDtos.SubmissionSnapshotResponse snapshot() {
        return new ArticleDtos.SubmissionSnapshotResponse(SNAPSHOT_ID, ARTICLE_ID, SPACE_ID, null, 2,
                "标题", "<p>正文</p>", "正文", "a".repeat(64), article().outline(), List.of(), now());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor user(AccountRole role) {
        AuthenticatedAccount principal = new AuthenticatedAccount(7, "actor", role, 1,
                AccountStatus.ACTIVE, null, null, null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    private OffsetDateTime now() { return OffsetDateTime.of(2026, 9, 9, 0, 0, 0, 0, ZoneOffset.UTC); }
}
