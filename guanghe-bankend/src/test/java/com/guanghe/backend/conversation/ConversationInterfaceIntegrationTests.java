package com.guanghe.backend.conversation;

import com.guanghe.backend.auth.account.AccountIdentity;
import com.guanghe.backend.auth.account.AccountMapper;
import com.guanghe.backend.auth.account.AccountRecord;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.ratelimit.LoginAttemptGuard;
import com.guanghe.backend.auth.security.FixedSaltMd5PasswordEncoder;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import com.guanghe.backend.conversation.context.RunContextService;
import com.guanghe.backend.conversation.context.RunContextSnapshotFactory;
import com.guanghe.backend.conversation.context.AuthorizedCatalogContextFactory;
import com.guanghe.backend.conversation.resource.RunResourceService;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class ConversationInterfaceIntegrationTests {

    private static final byte[] SECRET = new byte[32];
    private static final String CONVERSATION_ID = "1900012345678901400";
    private static final String MESSAGE_ID = "1900012345678901450";
    private static final UUID RUN_ID = UUID.randomUUID();

    static {
        new SecureRandom().nextBytes(SECRET);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("zhixiaohe.auth.enabled", () -> "true");
        registry.add("zhixiaohe.catalog.enabled", () -> "false");
        registry.add("zhixiaohe.conversation.enabled", () -> "true");
        registry.add("zhixiaohe.internal.enabled", () -> "false");
        registry.add("zhixiaohe.auth.jwt.issuer", () -> "conversation-interface-test");
        registry.add("zhixiaohe.auth.jwt.access-token-ttl-seconds", () -> "3600");
        registry.add("zhixiaohe.auth.jwt.secret-base64", () -> Base64.getEncoder().encodeToString(SECRET));
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountMapper accountMapper;
    @MockitoBean
    private LoginAttemptGuard loginAttemptGuard;
    @MockitoBean
    private ConversationService conversationService;
    @MockitoBean
    private ConversationMapper conversationMapper;
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
    @MockitoBean
    private RunResourceService resourceService;

    @BeforeEach
    void setUp() throws Exception {
        FixedSaltMd5PasswordEncoder encoder = new FixedSaltMd5PasswordEncoder();
        when(accountMapper.findByUsernameNormalized(any())).thenAnswer(invocation -> {
            String username = invocation.getArgument(0);
            if ("user".equals(username)) {
                return Optional.of(account(101, "user", AccountRole.USER, encoder));
            }
            if ("admin".equals(username)) {
                return Optional.of(account(1, "admin", AccountRole.ADMIN, encoder));
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
        ConversationDtos.Response conversation = conversation();
        when(conversationService.list(any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(conversation), 1, 20, 1));
        when(conversationService.create(any(), any())).thenReturn(conversation);
        when(conversationService.get(any(), anyLong())).thenReturn(conversation);
        when(conversationService.update(any(), anyLong(), any())).thenReturn(conversation);
        when(conversationService.messages(any(), anyLong(), anyLong(), anyInt()))
                .thenReturn(new ConversationDtos.CursorPage<>(List.of(message()), null));
        ConversationService.MessageSubmission submission = new ConversationService.MessageSubmission(
                new ConversationDtos.MessageAccepted(MESSAGE_ID, RUN_ID.toString(), "QUEUED"),
                true, "not-exposed", "hello", null, null, null
        );
        when(conversationService.submitMessage(any(), anyLong(), anyString(), any())).thenReturn(submission);
        AgentRunRecord run = run();
        when(conversationService.requestStop(any(), anyLong(), any())).thenReturn(run);
        when(coordinator.stop(run)).thenReturn(new ConversationDtos.StopAccepted(
                RUN_ID.toString(), true, "STOPPED"
        ));
        SseEmitter emitter = new SseEmitter();
        emitter.send(SseEmitter.event().id("8").name("message.delta").data("{}"));
        emitter.complete();
        when(eventStreamService.open(any(), anyLong(), any())).thenReturn(emitter);
    }

    @Test
    void userCanInvokeEveryConversationRouteAndIdsRemainStrings() throws Exception {
        String token = login("user");
        mockMvc.perform(get("/api/v1/conversations").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").isString());
        mockMvc.perform(post("/api/v1/conversations").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"会话\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").isString());
        mockMvc.perform(get("/api/v1/conversations/{id}", CONVERSATION_ID).header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/conversations/{id}", CONVERSATION_ID).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"title\":\"新会话\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/conversations/{id}/messages", CONVERSATION_ID)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").isString())
                .andExpect(jsonPath("$.data.items[0].runId").value(RUN_ID.toString()));
        mockMvc.perform(post("/api/v1/conversations/{id}/messages", CONVERSATION_ID)
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"content\":\"hello\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.messageId").isString())
                .andExpect(jsonPath("$.data.runId").value(RUN_ID.toString()));
        mockMvc.perform(post("/api/v1/conversations/{id}/stream-actions/stop", CONVERSATION_ID)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRunId\":\"" + RUN_ID + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.accepted").value(true));
        mockMvc.perform(get("/api/v1/conversations/{id}/events", CONVERSATION_ID)
                        .header("Authorization", token)
                        .header("Last-Event-ID", "7")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/conversations/{id}", CONVERSATION_ID)
                        .header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    void stopRequiresExactRunProofAndSnapshotDoesNotDispatch() throws Exception {
        String token = login("user");
        mockMvc.perform(post("/api/v1/conversations/{id}/stream-actions/stop", CONVERSATION_ID)
                        .header("Authorization", token))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/v1/conversations/{id}/stream-actions/stop", CONVERSATION_ID)
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
        when(conversationService.snapshot(any(), anyLong(), anyInt())).thenReturn(
                new ConversationDtos.Snapshot(List.of(message()), null, 8, null));
        mockMvc.perform(get("/api/v1/conversations/{id}/snapshot", CONVERSATION_ID).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.highWatermark").value(8))
                .andExpect(jsonPath("$.data.messages[0].id").value(MESSAGE_ID));
        org.mockito.Mockito.verify(coordinator, org.mockito.Mockito.never()).dispatch(any());
    }

    @Test
    void adminAnonymousAndMalformedIdsAreRejectedSafely() throws Exception {
        mockMvc.perform(get("/api/v1/conversations").header("Authorization", login("admin")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/conversations"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        String token = login("user");
        String malicious = "not-a-number";
        String response = mockMvc.perform(get("/api/v1/conversations/{id}", malicious)
                        .header("Authorization", token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(malicious);
    }

    @Test
    void unavailableAttachmentsAreReturnedAs422() throws Exception {
        when(conversationService.submitMessage(any(), anyLong(), anyString(), any()))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED));
        mockMvc.perform(post("/api/v1/conversations/{id}/messages", CONVERSATION_ID)
                        .header("Authorization", login("user"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"" + UUID.randomUUID() + "\","
                                + "\"content\":\"hello\",\"attachments\":[{\"assetId\":\"123\"}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void activeConversationDeleteReturns409() throws Exception {
        doThrow(new BusinessException(ErrorCode.RESOURCE_IN_USE))
                .when(conversationService).delete(any(), anyLong());

        mockMvc.perform(delete("/api/v1/conversations/{id}", CONVERSATION_ID)
                        .header("Authorization", login("user")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"));
    }

    @Test
    void authenticatedSseCompletionPermitsTheContainerAsyncDispatch() throws Exception {
        MvcResult stream = mockMvc.perform(get("/api/v1/conversations/{id}/events", CONVERSATION_ID)
                        .header("Authorization", login("user"))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(asyncDispatch(stream))
                .andExpect(status().isOk());
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

    private AccountRecord account(long id, String username, AccountRole role, FixedSaltMd5PasswordEncoder encoder) {
        return new AccountRecord(
                id, username, username, null, encoder.encode("password"), "MD5_FIXED_SALT_V1",
                1, role, AccountStatus.ACTIVE, null, null
        );
    }

    private AccountIdentity identity(long id, String username, AccountRole role) {
        return new AccountIdentity(id, username, null, 1, role, AccountStatus.ACTIVE, null, null);
    }

    private ConversationDtos.Response conversation() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new ConversationDtos.Response(CONVERSATION_ID, "会话", null, null, 1, null, now, now);
    }

    private ConversationDtos.MessageResponse message() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new ConversationDtos.MessageResponse(
                MESSAGE_ID, CONVERSATION_ID, "ASSISTANT", "TEXT",
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("text", "你好"),
                null, RUN_ID.toString(), 2, "COMPLETED", now, now
        );
    }

    private AgentRunRecord run() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AgentRunRecord(
                RUN_ID, Long.parseLong(CONVERSATION_ID), 101, Long.parseLong(MESSAGE_ID), 2L,
                null, null, "[]", "STOP_REQUESTED", 1, "{}", "a".repeat(64), now.plusMinutes(2),
                null, 0, "{}", null, null, now, now, null, now, now
        );
    }
}
