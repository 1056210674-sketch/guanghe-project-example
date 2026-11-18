package com.guanghe.backend.geo;

import com.guanghe.backend.auth.account.AccountIdentity;
import com.guanghe.backend.auth.account.AccountMapper;
import com.guanghe.backend.auth.account.AccountRecord;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.ratelimit.LoginAttemptGuard;
import com.guanghe.backend.auth.security.FixedSaltMd5PasswordEncoder;
import com.guanghe.backend.catalog.PageResponse;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
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
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
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
class GeoInterfaceIntegrationTests {
    private static final byte[] TEST_SECRET = new byte[32];
    private static final String TOPIC_ID = "1900012345678902200";
    private static final String SPACE_ID = "1900012345678901100";
    private static final String PRODUCT_ID = "1900012345678901200";
    private static final String COMPETITOR_ID = "1900012345678902100";

    static { new SecureRandom().nextBytes(TEST_SECRET); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("zhixiaohe.auth.enabled", () -> "true");
        registry.add("zhixiaohe.geo.enabled", () -> "true");
        registry.add("zhixiaohe.auth.jwt.issuer", () -> "geo-interface-test");
        registry.add("zhixiaohe.auth.jwt.access-token-ttl-seconds", () -> "3600");
        registry.add("zhixiaohe.auth.jwt.secret-base64", () -> Base64.getEncoder().encodeToString(TEST_SECRET));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @MockitoBean private AccountMapper accountMapper;
    @MockitoBean private LoginAttemptGuard loginAttemptGuard;
    @MockitoBean private GeoService geoService;
    @MockitoBean private GeoTrendService geoTrendService;

    @BeforeEach
    void setUp() {
        FixedSaltMd5PasswordEncoder encoder = new FixedSaltMd5PasswordEncoder();
        when(accountMapper.findByUsernameNormalized(any())).thenAnswer(invocation -> {
            String username = invocation.getArgument(0);
            AccountRole role = "admin".equals(username) ? AccountRole.ADMIN : AccountRole.USER;
            long id = role == AccountRole.ADMIN ? 1 : 101;
            return Optional.of(new AccountRecord(id, username, username, null,
                    encoder.encode("password"), "MD5_FIXED_SALT_V1", 1, role,
                    AccountStatus.ACTIVE, null, null));
        });
        when(accountMapper.findIdentityById(anyLong())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0);
            AccountRole role = id == 1 ? AccountRole.ADMIN : AccountRole.USER;
            return Optional.of(new AccountIdentity(id, role == AccountRole.ADMIN ? "admin" : "user",
                    null, 1, role, AccountStatus.ACTIVE, null, null));
        });
        GeoDtos.TopicResponse topic = topic();
        when(geoService.list(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(
                PageResponse.of(List.of(new GeoDtos.TopicSummary(
                        TOPIC_ID, SPACE_ID, PRODUCT_ID, "空气炸锅", "中国大陆", 4, now(), now()
                )), 1, 20, 1)
        );
        when(geoService.create(any(), any())).thenReturn(topic);
        when(geoService.get(any(), anyLong())).thenReturn(topic);
        when(geoService.update(any(), anyLong(), any())).thenReturn(topic);
        when(geoService.replaceKeywords(any(), anyLong(), any())).thenReturn(topic);
        when(geoService.replaceQuestions(any(), anyLong(), any())).thenReturn(topic);
        when(geoService.replaceCompetitors(any(), anyLong(), any())).thenReturn(topic);
        when(geoTrendService.trend(any(), anyLong(), anyInt(), any())).thenReturn(new GeoTrendDtos.Response(
                TOPIC_ID, 30, "DISCOVERY", "explicit-name-mention-v1", now().minusDays(30), now(),
                false, 100, 100, List.of(), List.of()));
    }

    @Test
    void userCanInvokeCompleteGeoFoundationSurfaceAndIdsAreStrings() throws Exception {
        String token = login("user");
        mockMvc.perform(get("/api/v1/geo/topics/{id}/visibility-trend", TOPIC_ID).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.topicId").isString())
                .andExpect(jsonPath("$.data.questionScope").value("DISCOVERY"))
                .andExpect(jsonPath("$.data.history").isArray());
        mockMvc.perform(get("/api/v1/geo/topics")
                        .queryParam("spaceId", SPACE_ID).queryParam("productId", PRODUCT_ID)
                        .queryParam("keyword", "空气").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(TOPIC_ID))
                .andExpect(jsonPath("$.data.items[0].spaceId").isString());
        mockMvc.perform(post("/api/v1/geo/topics").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":\"" + SPACE_ID + "\",\"productId\":\"" + PRODUCT_ID
                                + "\",\"name\":\"空气炸锅\",\"region\":\"中国大陆\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").isString());
        mockMvc.perform(get("/api/v1/geo/topics/{id}", TOPIC_ID).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.keywords[0].id").isString());
        mockMvc.perform(patch("/api/v1/geo/topics/{id}", TOPIC_ID).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4,\"region\":\"华南\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/geo/topics/{id}/keywords", TOPIC_ID)
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4,\"keywords\":[{\"type\":\"CORE\",\"text\":\"空气炸锅\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/geo/topics/{id}/questions", TOPIC_ID)
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4,\"questions\":[{\"category\":\"BRAND_DISCOVERY\","
                                + "\"text\":\"哪些品牌值得考虑？\",\"source\":\"MANUAL\",\"enabled\":true}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/geo/topics/{id}/competitors", TOPIC_ID)
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4,\"competitorIds\":[\"" + COMPETITOR_ID + "\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.competitorIds[0]").isString());
        mockMvc.perform(delete("/api/v1/geo/topics/{id}", TOPIC_ID).header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    void invalidEnumsIdsAndPagesReturnSafe422() throws Exception {
        String token = login("user");
        mockMvc.perform(put("/api/v1/geo/topics/{id}/questions", TOPIC_ID)
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"questions\":[{\"category\":\"OTHER\","
                                + "\"text\":\"question\",\"source\":\"MODEL\",\"enabled\":true}]}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        String badId = "not-a-topic";
        String response = mockMvc.perform(get("/api/v1/geo/topics/{id}", badId)
                        .header("Authorization", token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(badId);
        mockMvc.perform(get("/api/v1/geo/topics").queryParam("page", "abc")
                        .header("Authorization", token))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void adminIsForbiddenAndAnonymousIsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/geo/topics").header("Authorization", login("admin")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/geo/topics"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void controllerAndBrowserContractDeclareExactlyTheSameGeoPaths() {
        Set<String> implemented = new LinkedHashSet<>();
        handlerMapping.getHandlerMethods().forEach((mapping, method) -> {
            if (method.getBeanType() == GeoController.class) {
                mapping.getMethodsCondition().getMethods().forEach(httpMethod ->
                        mapping.getPatternValues().forEach(path -> implemented.add(httpMethod.name() + " " + path)));
            }
        });
        var openApi = new OpenAPIV3Parser().read(
                getClass().getResource("/contracts/openapi/browser-openapi.yaml").toExternalForm()
        );
        Set<String> declared = new LinkedHashSet<>();
        openApi.getPaths().forEach((path, item) -> {
            if (path.startsWith("/api/v1/geo/topics") && !path.endsWith("/competitor-candidates")) {
                item.readOperationsMap().forEach((method, operation) ->
                        declared.add(method.name() + " " + path));
            }
        });
        assertThat(implemented).containsExactlyInAnyOrderElementsOf(declared).hasSize(9);
    }

    @Test
    void trendContractDeclaresBoundedInputsNullableReportsAndComparisonSemantics() {
        var api = new OpenAPIV3Parser().read(getClass().getResource("/contracts/openapi/browser-openapi.yaml").toExternalForm());
        var operation = api.getPaths().get("/api/v1/geo/topics/{topicId}/visibility-trend").getGet();
        assertThat(operation.getResponses()).containsKeys("200", "401", "403", "404", "422");
        assertThat(operation.getResponses().get("200").getContent()).containsKey("application/json");
        assertThat(operation.getParameters().stream().filter(p -> p.getName().equals("days")).findFirst().orElseThrow()
                .getSchema().getEnum()).containsExactly(7, 30, 90);
        assertThat(operation.getParameters().stream().filter(p -> p.getName().equals("questionScope")).findFirst().orElseThrow()
                .getSchema().getEnum()).containsExactly("DISCOVERY", "BRAND_VALIDATION");
        var point = api.getComponents().getSchemas().get("GeoVisibilityTrendPoint");
        assertThat(point.getRequired()).contains("baselineExpectedSamples", "baselineMentionRate", "connectToPrevious", "comparison", "evidenceTruncated");
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) point.getProperties().get("reportId")).getTypes()).contains("string", "null");
        assertThat(((io.swagger.v3.oas.models.media.Schema) point.getProperties().get("comparisonStatus")).getEnum()).containsExactly("FIRST_OBSERVATION", "COMPARABLE", "NOT_COMPARABLE");
    }

    private String login(String username) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
                .path("data").path("accessToken").asText();
        return "Bearer " + token;
    }

    private GeoDtos.TopicResponse topic() {
        return new GeoDtos.TopicResponse(
                TOPIC_ID, SPACE_ID, PRODUCT_ID, "空气炸锅", "中国大陆",
                List.of(new GeoDtos.KeywordResponse("1900012345678902201", 1,
                        GeoDtos.KeywordType.CORE, "空气炸锅")),
                List.of(new GeoDtos.QuestionResponse("1900012345678902202", 1,
                        GeoDtos.QuestionCategory.BRAND_DISCOVERY, "哪些品牌值得考虑？",
                        GeoDtos.QuestionSource.MANUAL, true, now(), now())),
                List.of(COMPETITOR_ID), 2, 4, now(), now()
        );
    }

    private OffsetDateTime now() {
        return OffsetDateTime.of(2026, 9, 8, 0, 0, 0, 0, ZoneOffset.UTC);
    }
}
