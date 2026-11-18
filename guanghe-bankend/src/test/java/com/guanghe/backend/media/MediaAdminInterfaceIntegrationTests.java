package com.guanghe.backend.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The operator media console over HTTP, deliberately with {@code zhixiaohe.media.enabled} left at its
 * default of {@code false}.
 *
 * <p>That is the point of the class, not an oversight. The console has to work exactly when tenant
 * submission is switched off — that is when in-flight submissions need reconciling — so a test that
 * ran with media enabled would not notice the console disappearing along with the tenant feature. The
 * mapper is mocked rather than the service, so the assertions below exercise the real filtering,
 * mapping and validation rather than a stubbed copy of them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class MediaAdminInterfaceIntegrationTests {
    private static final String RESOURCE_ID = "1900012345678906000";
    private static final String SUBMISSION_ID = "1900012345678906001";
    private static final String CALLBACK_ID = "1900012345678906002";
    private static final String TENANT_USER_ID = "1900012345678906003";
    private static final String CUSTOMER_ORDER_ID = "ZXH1900012345678906001";
    /** The upstream price the frozen 1.35x ceil rule turns into 49_700 points. */
    private static final BigDecimal UPSTREAM_PRICE = new BigDecimal("368.0000");
    private static final String LEAK = "leak-me";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private MediaProperties properties;
    @Autowired @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @MockitoBean private MediaMapper media;

    @BeforeEach
    void setUp() {
        when(media.countResources(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(12L);
        when(media.countSubmissionsForAdmin(MediaSubmissionService.PENDING_STATUSES)).thenReturn(3L);
        when(media.countSubmissionsForAdmin(MediaSubmissionService.EXCEPTION_STATUSES)).thenReturn(2L);
        when(media.listResources(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyLong()))
                .thenReturn(List.of(resource()));
        when(media.findResource(anyLong())).thenReturn(Optional.of(resource()));
        when(media.listSubmissionsForAdmin(any(), anyInt(), anyLong())).thenReturn(List.of(submission()));
        when(media.findSubmissionForAdmin(anyLong())).thenReturn(Optional.of(submission()));
        when(media.eventsForAdmin(anyLong())).thenReturn(List.of(new MediaRecords.Event(
                1L, 1L, 2L, 3L, 1L, "SUBMISSION_ACCEPTED", "SUBMITTING", "ACCEPTED", null, now())));
        when(media.listCallbacksForAdmin(any(), anyInt(), anyLong())).thenReturn(List.of(callback()));
        when(media.findCallbackForAdmin(anyLong())).thenReturn(Optional.of(callback()));
        when(media.listSyncStates()).thenReturn(List.of(new MediaRecords.SyncState("MEDIA_PROCUREMENT",
                "1", "SUCCEEDED", now().minusMinutes(30), now().minusMinutes(29), null, 420, 7, now())));
    }

    @Test
    void operatorSeesPricesUpstreamHandlesAndNoRawPayload() throws Exception {
        OffsetDateTime beforeRequest = OffsetDateTime.now(ZoneOffset.UTC);
        mvc.perform(get("/api/v1/admin/media/overview").with(user(AccountRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeResources").value(12))
                .andExpect(jsonPath("$.data.pendingSubmissions").value(3))
                .andExpect(jsonPath("$.data.exceptionSubmissions").value(2))
                // No upstream call was made to learn a balance, so the field is present and null
                // rather than absent: a missing key and a null key mean different things to a client.
                .andExpect(jsonPath("$.data.walletBalanceCents").value(nullValue()));

        var freshness = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(media).countResources(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq("ACTIVE"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), freshness.capture(),
                org.mockito.ArgumentMatchers.eq("MEDIA_PROCUREMENT"), org.mockito.ArgumentMatchers.eq("1"));
        assertThat(freshness.getValue()).isBetween(beforeRequest.minus(properties.getResourceMaxAge()),
                OffsetDateTime.now(ZoneOffset.UTC).minus(properties.getResourceMaxAge()));

        // The reference price is computed from the shared pricing rule, not restated here.
        assertThat(MediaSubmissionService.saleAmountCents(UPSTREAM_PRICE)).isEqualTo(49_700);
        String resource = mvc.perform(get("/api/v1/admin/media/resources/{id}", RESOURCE_ID)
                        .with(user(AccountRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(RESOURCE_ID))
                .andExpect(jsonPath("$.data.upstreamPrice").value("368.0000"))
                .andExpect(jsonPath("$.data.markupRate").value("0.3500"))
                .andExpect(jsonPath("$.data.referenceSaleAmountCents").value(49_700))
                .andExpect(jsonPath("$.data.referenceChargedPoints").value(49_700))
                .andExpect(jsonPath("$.data.capabilities.supportsLink").value(true))
                .andReturn().getResponse().getContentAsString();
        // media_resource.raw_json is stored but is never carried onto this surface.
        assertThat(resource).contains("368.0000").doesNotContain(LEAK, "rawJson", "appKey", "app_key");

        String submission = mvc.perform(get("/api/v1/admin/media/submissions/{id}", SUBMISSION_ID)
                        .with(user(AccountRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(TENANT_USER_ID))
                .andExpect(jsonPath("$.data.customerOrderId").value(CUSTOMER_ORDER_ID))
                .andExpect(jsonPath("$.data.upstreamOrderNo").value("MT-2026-0001"))
                .andExpect(jsonPath("$.data.upstreamPrice").value("368.0000"))
                .andExpect(jsonPath("$.data.events[0].sequence").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(submission).doesNotContain(LEAK, "payloadHash", "payload_hash", "rawBody");

        String callback = mvc.perform(get("/api/v1/admin/media/callbacks/{id}", CALLBACK_ID)
                        .with(user(AccountRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(CALLBACK_ID))
                .andExpect(jsonPath("$.data.status").value("RECEIVED"))
                .andExpect(jsonPath("$.data.attempts").value(2))
                .andReturn().getResponse().getContentAsString();
        // The receipt is identified by its own id; the digest of the raw body is not published.
        assertThat(callback).doesNotContain("payloadHash", "payload_hash", LEAK);

        mvc.perform(get("/api/v1/admin/media/sync-states").with(user(AccountRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].providerCode").value("MEDIA_PROCUREMENT"))
                .andExpect(jsonPath("$.data[0].lastResourceCount").value(420))
                // Nothing records a batch, an operator or per-run counters, so none is reported.
                .andExpect(jsonPath("$.data[0].batchId").doesNotExist());
    }

    @Test
    void nonOperatorsAreForbiddenOnEveryOperatorMediaRoute() throws Exception {
        for (String path : List.of("/api/v1/admin/media/overview", "/api/v1/admin/media/resources",
                "/api/v1/admin/media/resources/" + RESOURCE_ID, "/api/v1/admin/media/submissions",
                "/api/v1/admin/media/submissions/" + SUBMISSION_ID, "/api/v1/admin/media/callbacks",
                "/api/v1/admin/media/callbacks/" + CALLBACK_ID, "/api/v1/admin/media/sync-states")) {
            mvc.perform(get(path).with(user(AccountRole.USER)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void statusAndGroupFiltersAreValidatedNormalisedAndNeverMerged() throws Exception {
        // Both filters can disagree, so sending both is rejected instead of silently resolved.
        mvc.perform(get("/api/v1/admin/media/submissions").queryParam("status", "ACCEPTED")
                        .queryParam("group", "PENDING").with(user(AccountRole.ADMIN)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/admin/media/submissions").queryParam("status", "NO_SUCH_STATUS")
                        .with(user(AccountRole.ADMIN)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/admin/media/submissions").queryParam("group", "NO_SUCH_GROUP")
                        .with(user(AccountRole.ADMIN)))
                .andExpect(status().isUnprocessableEntity());

        // One exact status, in any case, reaches the mapper as the one status.
        mvc.perform(get("/api/v1/admin/media/submissions").queryParam("status", "accepted")
                        .with(user(AccountRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(SUBMISSION_ID));
        verify(media).listSubmissionsForAdmin(List.of("ACCEPTED"), 20, 0L);

        // A group is exactly the two buckets the local status vocabulary defines.
        mvc.perform(get("/api/v1/admin/media/submissions").queryParam("group", "exception")
                        .with(user(AccountRole.ADMIN)))
                .andExpect(status().isOk());
        verify(media).listSubmissionsForAdmin(MediaSubmissionService.EXCEPTION_STATUSES, 20, 0L);

        // The callback tab lists the receipts that still want a human, never the closed loops.
        mvc.perform(get("/api/v1/admin/media/callbacks").with(user(AccountRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(CALLBACK_ID));
        verify(media).listCallbacksForAdmin(List.of("RECEIVED", "FAILED"), 20, 0L);
    }

    @Test
    void aValidLargePageKeepsItsOffsetBeyondTheIntegerRange() throws Exception {
        mvc.perform(get("/api/v1/admin/media/submissions")
                        .queryParam("page", "21474838")
                        .queryParam("size", "100")
                        .with(user(AccountRole.ADMIN)))
                .andExpect(status().isOk());

        // (page - 1) * size is a valid PostgreSQL OFFSET, but exceeds Integer.MAX_VALUE.
        // Keeping the multiplication in long arithmetic prevents it wrapping negative.
        verify(media).listSubmissionsForAdmin(null, 100, 2_147_483_700L);
    }

    @Test
    void operatorMediaSurfaceIsReadOnlyAndIndependentOfTheTenantSwitch() {
        Set<String> implemented = new LinkedHashSet<>();
        handlerMapping.getHandlerMethods().forEach((mapping, method) -> {
            if (method.getBeanType() == MediaAdminController.class) {
                mapping.getMethodsCondition().getMethods().forEach(httpMethod ->
                        mapping.getPatternValues().forEach(path -> implemented.add(httpMethod.name() + " " + path)));
            }
        });
        // Read-only: the console can look, and cannot submit, replay a callback or start a sync.
        assertThat(implemented).allSatisfy(route -> assertThat(route).startsWith("GET "));

        var openApi = new OpenAPIV3Parser().read(
                getClass().getResource("/contracts/openapi/browser-openapi.yaml").toExternalForm());
        Set<String> declared = new LinkedHashSet<>();
        openApi.getPaths().forEach((path, item) -> {
            if (path.startsWith("/api/v1/admin/media")) {
                item.readOperationsMap().forEach((method, operation) -> declared.add(method.name() + " " + path));
            }
        });
        assertThat(implemented).containsExactlyInAnyOrderElementsOf(declared).hasSize(8);

        // zhixiaohe.media.enabled is false in this context: the tenant surface is gone and the
        // operator console still answers, which is exactly when an operator needs it.
        assertThat(handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .filter(path -> path.startsWith("/api/v1/media/")).toList()).isEmpty();
        assertThat(implemented).isNotEmpty();
    }

    private MediaRecords.Resource resource() {
        return new MediaRecords.Resource(Long.parseLong(RESOURCE_ID), "MEDIA_PROCUREMENT", "1", "44990",
                "媒体名称", "ACTIVE",
                "NEWS", "综合", "全国", 3, json.createObjectNode().put("supportsLink", true).toString(),
                UPSTREAM_PRICE, json.createObjectNode().put("secret", LEAK).toString(), now(), now(), 4);
    }

    private MediaRecords.Submission submission() {
        return new MediaRecords.Submission(Long.parseLong(SUBMISSION_ID), 2L, 3L,
                Long.parseLong(TENANT_USER_ID), 4L, 5L, 6L, Long.parseLong(RESOURCE_ID), 8L,
                "MEDIA_PROCUREMENT", "1", "44990", "媒体名称", UPSTREAM_PRICE,
                MediaSubmissionService.MARKUP_RATE, 49_700, 49_700, "工作日发布", CUSTOMER_ORDER_ID,
                "MT-ORDER-1", "MT-2026-0001", "ACCEPTED", "ACCEPTED", "CONFIRMED",
                "https://example.test/published", "[\"https://example.test/published\"]",
                null, now(), now(), now(), null, now(), now());
    }

    private MediaRecords.Callback callback() {
        return new MediaRecords.Callback(Long.parseLong(CALLBACK_ID), "MEDIA_PROCUREMENT",
                CUSTOMER_ORDER_ID, null, null, "PROGRESS", "RECEIVED", 2, "MEDIA_CALLBACK_APPLY_FAILED",
                now(), null, now());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor user(AccountRole role) {
        AuthenticatedAccount principal = new AuthenticatedAccount(7, "actor", role, 1,
                AccountStatus.ACTIVE, null, null, null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.of(2026, 9, 22, 0, 0, 0, 0, ZoneOffset.UTC);
    }
}
