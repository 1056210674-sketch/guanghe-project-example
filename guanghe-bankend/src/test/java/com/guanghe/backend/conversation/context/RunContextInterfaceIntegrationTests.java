package com.guanghe.backend.conversation.context;

import com.guanghe.backend.auth.account.AccountMapper;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.conversation.ConversationEventStreamService;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.conversation.ConversationRunCoordinator;
import com.guanghe.backend.conversation.ConversationService;
import com.guanghe.backend.conversation.resource.RunResourceService;
import com.guanghe.backend.integration.internal.security.InternalHmacSigner;
import com.guanghe.backend.integration.internal.security.InternalHmacVerificationFilter;
import com.guanghe.backend.integration.internal.security.InternalNonceStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
@ExtendWith(OutputCaptureExtension.class)
class RunContextInterfaceIntegrationTests {
    @MockitoBean
    private RunResourceService resourceService;

    private static final String KEY_ID = "agent-service-v1";
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final String PATH = "/internal/v1/runs/" + RUN_ID + "/context";
    private static final String CONTEXT_REF = "opaque-test-context-reference";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("zhixiaohe.auth.enabled", () -> "false");
        registry.add("zhixiaohe.catalog.enabled", () -> "false");
        registry.add("zhixiaohe.conversation.enabled", () -> "true");
        registry.add("zhixiaohe.internal.enabled", () -> "true");
        registry.add("zhixiaohe.internal.key-id", () -> KEY_ID);
        registry.add("zhixiaohe.internal.secret", () -> SECRET);
        registry.add("zhixiaohe.internal.python.base-url", () -> "http://127.0.0.1:1");
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InternalNonceStore nonceStore;
    @MockitoBean
    private ConversationService conversationService;
    @MockitoBean
    private ConversationMapper conversationMapper;
    @MockitoBean
    private AccountMapper accountMapper;
    @MockitoBean
    private ConversationRunCoordinator coordinator;
    @MockitoBean
    private ConversationEventStreamService eventStreamService;
    @MockitoBean
    private RunContextService runContextService;
    @MockitoBean
    private RunContextSnapshotFactory runContextSnapshotFactory;
    @MockitoBean
    private AuthorizedCatalogContextFactory authorizedCatalogContextFactory;

    @BeforeEach
    void setUp() {
        when(nonceStore.claim(anyString(), anyString(), any())).thenReturn(true);
        when(runContextService.get(RUN_ID, CONTEXT_REF)).thenReturn(new InternalRunContextDtos.RunContext(
                RUN_ID.toString(), "1900012345678901400", 1, null, null, List.of(), List.of()
        ));
    }

    @Test
    void signedRequestReturnsOnlyTheFrozenWhitelistAndDoesNotLogTheContextReference(
            CapturedOutput output
    ) throws Exception {
        mockMvc.perform(get(PATH)
                        .header(RunContextController.CONTEXT_REF_HEADER, CONTEXT_REF)
                        .headers(signedHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.conversationId").isString())
                .andExpect(jsonPath("$.contextVersion").value(1))
                .andExpect(jsonPath("$.space").isEmpty())
                .andExpect(jsonPath("$.product").isEmpty())
                .andExpect(jsonPath("$.competitors").isArray())
                .andExpect(jsonPath("$.attachmentAssetIds").isArray())
                .andExpect(jsonPath("$.conversationMemory.messages").isArray())
                .andExpect(jsonPath("$.taskWorkspace.contextVersion").value(1))
                .andExpect(jsonPath("$.loadedToolGroups[0]").value("TASK_PREPARATION"))
                .andExpect(jsonPath("$.contextBudget.targetInputTokens").value(128000))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.contextRef").doesNotExist());

        assertThat(output.getAll()).doesNotContain(CONTEXT_REF);
    }

    @Test
    void contextReferenceMismatchAndMissingHmacFailClosed() throws Exception {
        when(runContextService.get(RUN_ID, "wrong-reference"))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        mockMvc.perform(get(PATH)
                        .header(RunContextController.CONTEXT_REF_HEADER, "wrong-reference")
                        .headers(signedHeaders()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get(PATH).header(RunContextController.CONTEXT_REF_HEADER, CONTEXT_REF))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INTERNAL_SIGNATURE_INVALID"));
        verify(runContextService, never()).get(RUN_ID, null);
    }

    @Test
    void signedRequestWithoutContextReferenceIsRejectedWithoutInvokingTheContextService() throws Exception {
        mockMvc.perform(get(PATH).headers(signedHeaders()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").isString());
        verify(runContextService, never()).get(RUN_ID, null);
    }

    private HttpHeaders signedHeaders() {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        InternalHmacSigner.SignedContent signed = InternalHmacSigner.sign(
                timestamp, nonce, "GET", PATH, new byte[0], SECRET.getBytes(StandardCharsets.UTF_8)
        );
        HttpHeaders headers = new HttpHeaders();
        headers.set(InternalHmacVerificationFilter.KEY_ID_HEADER, KEY_ID);
        headers.set(InternalHmacVerificationFilter.TIMESTAMP_HEADER, timestamp);
        headers.set(InternalHmacVerificationFilter.NONCE_HEADER, nonce);
        headers.set(InternalHmacVerificationFilter.CONTENT_SHA256_HEADER, signed.bodySha256());
        headers.set(InternalHmacVerificationFilter.SIGNATURE_HEADER, signed.signature());
        return headers;
    }
}
