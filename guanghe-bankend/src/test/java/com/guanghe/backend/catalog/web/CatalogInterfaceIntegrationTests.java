package com.guanghe.backend.catalog.web;

import com.guanghe.backend.auth.account.AccountIdentity;
import com.guanghe.backend.auth.account.AccountMapper;
import com.guanghe.backend.auth.account.AccountRecord;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.ratelimit.LoginAttemptGuard;
import com.guanghe.backend.auth.security.FixedSaltMd5PasswordEncoder;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.catalog.competitor.CompetitorDtos;
import com.guanghe.backend.catalog.competitor.CompetitorService;
import com.guanghe.backend.catalog.competitor.CompetitorStatus;
import com.guanghe.backend.catalog.competitor.CompetitorType;
import com.guanghe.backend.catalog.product.ProductDtos;
import com.guanghe.backend.catalog.product.ProductService;
import com.guanghe.backend.catalog.space.BusinessSpaceService;
import com.guanghe.backend.catalog.space.SpaceDtos;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class CatalogInterfaceIntegrationTests {

    private static final byte[] TEST_SECRET = new byte[32];
    private static final String LARGE_ID = "1900012345678901100";

    static {
        new SecureRandom().nextBytes(TEST_SECRET);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("zhixiaohe.auth.enabled", () -> "true");
        registry.add("zhixiaohe.catalog.enabled", () -> "true");
        registry.add("zhixiaohe.auth.jwt.issuer", () -> "catalog-interface-test");
        registry.add("zhixiaohe.auth.jwt.access-token-ttl-seconds", () -> "3600");
        registry.add("zhixiaohe.auth.jwt.secret-base64", () -> Base64.getEncoder().encodeToString(TEST_SECRET));
        registry.add("zhixiaohe.auth.login-rate-limit.max-failures", () -> "5");
        registry.add("zhixiaohe.auth.login-rate-limit.window-seconds", () -> "900");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    private AccountMapper accountMapper;
    @MockitoBean
    private LoginAttemptGuard loginAttemptGuard;
    @MockitoBean
    private BusinessSpaceService spaceService;
    @MockitoBean
    private ProductService productService;
    @MockitoBean
    private CompetitorService competitorService;

    @BeforeEach
    void setUp() {
        FixedSaltMd5PasswordEncoder encoder = new FixedSaltMd5PasswordEncoder();
        when(accountMapper.findByUsernameNormalized(any())).thenAnswer(invocation -> {
            String username = invocation.getArgument(0);
            if ("user".equals(username)) {
                return Optional.of(new AccountRecord(
                        101, "user", "user", null, encoder.encode("password"),
                        "MD5_FIXED_SALT_V1", 1, AccountRole.USER, AccountStatus.ACTIVE, null, null
                ));
            }
            if ("admin".equals(username)) {
                return Optional.of(new AccountRecord(
                        1, "admin", "admin", null, encoder.encode("password"),
                        "MD5_FIXED_SALT_V1", 1, AccountRole.ADMIN, AccountStatus.ACTIVE, null, null
                ));
            }
            return Optional.empty();
        });
        when(accountMapper.findIdentityById(anyLong())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0);
            if (id == 101) {
                return Optional.of(identity(101, "user", AccountRole.USER));
            }
            if (id == 1) {
                return Optional.of(identity(1, "admin", AccountRole.ADMIN));
            }
            return Optional.empty();
        });

        SpaceDtos.Response space = space();
        ProductDtos.Response product = product();
        CompetitorDtos.Response competitor = competitor();
        when(spaceService.list(any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(new SpaceDtos.Summary(
                        LARGE_ID, "空间", "品牌", 0, 0, 1, now(), now()
                )), 1, 20, 1));
        when(spaceService.create(any(), any())).thenReturn(space);
        when(spaceService.get(any(), anyLong())).thenReturn(space);
        when(spaceService.update(any(), anyLong(), any())).thenReturn(space);
        when(productService.list(any(), anyLong(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(new ProductDtos.Summary(
                        LARGE_ID, LARGE_ID, "商品", null, null, 0, 1, now(), now()
                )), 1, 20, 1));
        when(productService.create(any(), anyLong(), any())).thenReturn(product);
        when(productService.get(any(), anyLong())).thenReturn(product);
        when(productService.update(any(), anyLong(), any())).thenReturn(product);
        when(productService.replaceAliases(any(), anyLong(), any())).thenReturn(product);
        when(competitorService.list(any(), anyLong(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(new CompetitorDtos.Summary(
                        LARGE_ID, LARGE_ID, "竞品", CompetitorType.DIRECT,
                        CompetitorStatus.ACTIVE, 0, 1, now(), now()
                )), 1, 20, 1));
        when(competitorService.create(any(), anyLong(), any())).thenReturn(competitor);
        when(competitorService.get(any(), anyLong())).thenReturn(competitor);
        when(competitorService.update(any(), anyLong(), any())).thenReturn(competitor);
        when(competitorService.replaceAliases(any(), anyLong(), any())).thenReturn(competitor);
    }

    @Test
    void userCanInvokeEveryCatalogCrudRouteAndIdsRemainJsonStrings() throws Exception {
        String token = login("user");

        mockMvc.perform(get("/api/v1/spaces").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(LARGE_ID));
        mockMvc.perform(post("/api/v1/spaces").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"空间\",\"brand\":{\"name\":\"品牌\"}}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").isString());
        mockMvc.perform(get("/api/v1/spaces/{id}", LARGE_ID).header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/spaces/{id}", LARGE_ID).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"name\":\"新空间\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/spaces/{id}", LARGE_ID).header("Authorization", token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/spaces/{id}/products", LARGE_ID).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].spaceId").isString());
        mockMvc.perform(post("/api/v1/spaces/{id}/products", LARGE_ID).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"商品\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/products/{id}", LARGE_ID).header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/products/{id}", LARGE_ID).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"name\":\"新商品\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/products/{id}/aliases", LARGE_ID).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"aliases\":[]}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/products/{id}", LARGE_ID).header("Authorization", token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/spaces/{id}/competitors", LARGE_ID).header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/spaces/{id}/competitors", LARGE_ID).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"竞品\",\"type\":\"DIRECT\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/competitors/{id}", LARGE_ID).header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/competitors/{id}", LARGE_ID).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/competitors/{id}/aliases", LARGE_ID).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"aliases\":[]}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/competitors/{id}", LARGE_ID).header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    void adminIsForbiddenFromUserCatalogAndAnonymousIsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/spaces").header("Authorization", login("admin")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/spaces"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void referencedProductDeleteReturnsSafeConflictInsteadOfServerError() throws Exception {
        String token = login("user");
        doThrow(new BusinessException(ErrorCode.RESOURCE_IN_USE,
                "商品已被任务、永久资产或审计记录引用，解除关联前不能删除",
                Map.of("reason", "PRODUCT_HAS_PROTECTED_REFERENCES")))
                .when(productService).delete(any(), anyLong());

        String response = mockMvc.perform(delete("/api/v1/products/{id}", LARGE_ID)
                        .header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"))
                .andExpect(jsonPath("$.details.reason").value("PRODUCT_HAS_PROTECTED_REFERENCES"))
                .andExpect(jsonPath("$.traceId").isString())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("constraint").doesNotContain("business_task");
    }

    @Test
    void invalidCompetitorTypeAndStatusReturn422() throws Exception {
        String token = login("user");
        mockMvc.perform(post("/api/v1/spaces/{id}/competitors", LARGE_ID).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"竞品\",\"type\":\"OTHER\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(patch("/api/v1/competitors/{id}", LARGE_ID).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"status\":\"DELETED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void malformedPathAndQueryParametersReturnSafe422Responses() throws Exception {
        String token = login("user");
        assertSafeValidationFailure(
                get("/api/v1/spaces/{id}", "9223372036854775808").header("Authorization", token),
                "9223372036854775808"
        );
        assertSafeValidationFailure(
                get("/api/v1/products/{id}", "not-a-number").header("Authorization", token),
                "not-a-number"
        );
        assertSafeValidationFailure(
                get("/api/v1/spaces").queryParam("page", "abc").header("Authorization", token),
                "abc"
        );
    }

    @Test
    void catalogRequestsRejectUserIdAndOtherUnknownPrivilegeFields() throws Exception {
        String token = login("user");
        mockMvc.perform(post("/api/v1/spaces").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"空间\",\"brand\":{\"name\":\"品牌\"},\"userId\":\"1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/spaces/{id}/products", LARGE_ID).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"商品\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void controllerMethodsAndPathsMatchTheBrowserOpenApiCatalogSurface() {
        Set<String> implemented = new LinkedHashSet<>();
        handlerMapping.getHandlerMethods().forEach((mapping, method) -> {
            if (method.getBeanType().getPackageName().startsWith("com.guanghe.backend.catalog")) {
                mapping.getMethodsCondition().getMethods().forEach(httpMethod ->
                        mapping.getPatternValues().forEach(path ->
                                implemented.add(httpMethod.name() + " " + path)));
            }
        });

        var openApi = new OpenAPIV3Parser().read(
                getClass().getResource("/contracts/openapi/browser-openapi.yaml").toExternalForm()
        );
        Set<String> declared = new LinkedHashSet<>();
        openApi.getPaths().forEach((path, item) -> {
            if (path.startsWith("/api/v1/spaces") || path.startsWith("/api/v1/products")
                    || path.startsWith("/api/v1/competitors")) {
                item.readOperationsMap().forEach((method, operation) ->
                        declared.add(method.name() + " " + path));
            }
        });

        assertThat(implemented).containsExactlyInAnyOrderElementsOf(declared);
        assertThat(implemented).hasSize(19);
    }

    private String login(String username) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
                .path("data").path("accessToken").asText();
        return "Bearer " + token;
    }

    private void assertSafeValidationFailure(
            MockHttpServletRequestBuilder request,
            String rejectedValue
    ) throws Exception {
        String response = mockMvc.perform(request)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(rejectedValue);
    }

    private AccountIdentity identity(long id, String username, AccountRole role) {
        return new AccountIdentity(id, username, null, 1, role, AccountStatus.ACTIVE, null, null);
    }

    private SpaceDtos.Response space() {
        return new SpaceDtos.Response(
                LARGE_ID, "空间", new SpaceDtos.Brand("品牌", List.of(), null,
                List.of(), List.of(), List.of(), List.of()), null, 0, 0, 1, now(), now()
        );
    }

    private ProductDtos.Response product() {
        return new ProductDtos.Response(
                LARGE_ID, LARGE_ID, "商品", List.of(), null, null, List.of(), Map.of(),
                List.of(), List.of(), List.of(), null, 1, now(), now()
        );
    }

    private CompetitorDtos.Response competitor() {
        return new CompetitorDtos.Response(
                LARGE_ID, LARGE_ID, "竞品", List.of(), CompetitorType.DIRECT, null,
                CompetitorStatus.ACTIVE, 1, now(), now()
        );
    }

    private OffsetDateTime now() {
        return OffsetDateTime.of(2026, 9, 3, 0, 0, 0, 0, ZoneOffset.UTC);
    }
}
