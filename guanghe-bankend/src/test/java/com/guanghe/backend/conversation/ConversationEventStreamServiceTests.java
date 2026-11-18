package com.guanghe.backend.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.common.api.BusinessException;
import com.guanghe.backend.common.api.ErrorCode;
import java.time.OffsetDateTime;
import java.io.IOException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationEventStreamServiceTests {

    @Test
    void onlyFrameworkCompletedEmitterStateIsRoutineDisconnect() {
        assertThat(ConversationEventStreamService.alreadyCompletedEmitter(
                new IllegalStateException("ResponseBodyEmitter has already completed"))).isTrue();
        assertThat(ConversationEventStreamService.alreadyCompletedEmitter(
                new IllegalStateException("persistence failed"))).isFalse();
        assertThat(ConversationEventStreamService.alreadyCompletedEmitter(
                new IllegalStateException("ResponseBodyEmitter has already completed", new IOException("failure"))))
                .isFalse();
    }

    private static final long USER_ID = 41;
    private static final long CONVERSATION_ID = 9001;

    private ConversationMapper mapper;
    private ConversationService conversationService;
    private ConversationEventStreamService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ConversationMapper.class);
        conversationService = mock(ConversationService.class);
        service = new ConversationEventStreamService(
                mapper, conversationService, new ConversationProperties(), new ObjectMapper()
        );
    }

    @Test
    void resourceReplayKeepsSequenceButDoesNotExposeStoredInputEvidenceOrAuthorization() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var runId = UUID.randomUUID();
        var stored = new ConversationEventRecord(7099, CONVERSATION_ID, USER_ID, runId, null, UUID.randomUUID(),
                "context.resource", 99, """
                {"kind":"READ","success":true,"candidateSetId":"6001","choiceId":"second",
                 "inputHash":"private-hash","scopeFingerprint":"private-scope","input":{"keyword":"private-input"},
                 "result":{"observationId":"7001","purpose":"LATEST_GEO_REPORT","reportId":"8001",
                 "resourceId":"5001","name":"品牌报告","spaceId":"2001","projection":{"answer":"private-evidence"}}}
                """, now, now);
        ConversationDtos.EventResponse projected = ReflectionTestUtils.invokeMethod(service, "response", stored);
        assertThat(projected).isNotNull();
        assertThat(projected.sequence()).isEqualTo(99);
        assertThat(projected.payload().path("runId").asText()).isEqualTo(runId.toString());
        assertThat(projected.payload().path("reportId").asText()).isEqualTo("8001");
        assertThat(projected.payload().toString()).doesNotContain("private-", "projection", "inputHash", "scopeFingerprint");
    }

    @Test
    void lastEventIdReplaysOnlyPersistedEventsAfterTheConversationSequence() {
        AuthenticatedAccount account = account();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ConversationEventRecord persisted = new ConversationEventRecord(
                7008, CONVERSATION_ID, USER_ID, UUID.randomUUID(), 8002L, UUID.randomUUID(),
                "message.delta", 8, "{\"messageId\":\"8002\",\"delta\":\"好\"}", now, now
        );
        when(mapper.findEventsAfter(USER_ID, CONVERSATION_ID, 7, 500)).thenReturn(List.of(persisted));
        when(mapper.findActiveRun(USER_ID, CONVERSATION_ID)).thenReturn(Optional.empty());

        service.open(account, CONVERSATION_ID, "7");

        verify(conversationService).requireConversation(USER_ID, CONVERSATION_ID);
        verify(mapper).findEventsAfter(USER_ID, CONVERSATION_ID, 7, 500);
    }

    @Test
    void malformedLastEventIdFailsWithValidationErrorBeforeReadingEvents() {
        assertThatThrownBy(() -> service.open(account(), CONVERSATION_ID, "not-a-sequence"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void streamStaysOpenAfterChatCompletionWhileAPaidTaskIsStillActive() {
        when(mapper.findEventsAfter(USER_ID, CONVERSATION_ID, 0, 500)).thenReturn(List.of());
        when(mapper.findActiveRun(USER_ID, CONVERSATION_ID)).thenReturn(Optional.empty());
        when(mapper.hasActivePaidTask(CONVERSATION_ID, USER_ID)).thenReturn(true);
        service.open(account(), CONVERSATION_ID, null);

        service.publish(event("message.completed", null, 1));

        org.assertj.core.api.Assertions.assertThat(subscriberCount()).isEqualTo(1);
    }

    @Test
    void terminalTaskEventClosesStreamAfterTheLastConversationWorkFinishes() {
        when(mapper.findEventsAfter(USER_ID, CONVERSATION_ID, 0, 500)).thenReturn(List.of());
        when(mapper.findActiveRun(USER_ID, CONVERSATION_ID)).thenReturn(Optional.empty());
        when(mapper.hasActivePaidTask(CONVERSATION_ID, USER_ID)).thenReturn(true, false);
        service.open(account(), CONVERSATION_ID, null);

        service.publish(event("task.completed", 7001L, 2));

        org.assertj.core.api.Assertions.assertThat(subscriberCount()).isZero();
    }

    private ConversationEventRecord event(String eventType, Long taskId, long sequence) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new ConversationEventRecord(7000 + sequence, CONVERSATION_ID, USER_ID,
                null, taskId == null ? 8002L : null, taskId, null, eventType, sequence,
                taskId == null ? "{\"messageId\":\"8002\"}"
                        : "{\"taskId\":\"7001\",\"deliveryKind\":\"BACKGROUND_TASK\",\"status\":\"SUCCEEDED\"}",
                now, now);
    }

    @Test
    @SuppressWarnings("unchecked")
    void outOfOrderAfterCommitPublicationReplaysTheMissingPrefixBeforeAdvancingCursor() {
        when(mapper.findEventsAfter(USER_ID, CONVERSATION_ID, 0, 500)).thenReturn(List.of());
        when(mapper.hasActivePaidTask(CONVERSATION_ID, USER_ID)).thenReturn(true);
        service.open(account(), CONVERSATION_ID, null);
        ConversationEventRecord first = event("message.delta", null, 1);
        ConversationEventRecord second = event("message.delta", null, 2);
        when(mapper.findEventsAfter(USER_ID, CONVERSATION_ID, 0, 500)).thenReturn(List.of(first, second));
        service.publish(second);
        service.publish(first);
        Map<Long, Set<?>> subscribers = (Map<Long, Set<?>>) ReflectionTestUtils.getField(service, "subscribers");
        Object subscriber = subscribers.get(CONVERSATION_ID).iterator().next();
        org.assertj.core.api.Assertions.assertThat(ReflectionTestUtils.getField(subscriber, "lastSequence")).isEqualTo(2L);
        verify(mapper, org.mockito.Mockito.times(2)).findEventsAfter(USER_ID, CONVERSATION_ID, 0, 500);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedClientWriteRemovesOnlyItsSubscriptionWithoutCompletingTheEmitterTwice() throws Exception {
        when(mapper.findEventsAfter(USER_ID, CONVERSATION_ID, 0, 500)).thenReturn(List.of());
        when(mapper.hasActivePaidTask(CONVERSATION_ID, USER_ID)).thenReturn(true);
        service.open(account(), CONVERSATION_ID, null);
        service.open(account(), CONVERSATION_ID, null);
        Map<Long, Set<?>> subscribers = (Map<Long, Set<?>>) ReflectionTestUtils.getField(service, "subscribers");
        Object brokenSubscriber = subscribers.get(CONVERSATION_ID).iterator().next();
        SseEmitter failedEmitter = mock(SseEmitter.class);
        org.mockito.Mockito.doThrow(new IOException("Broken pipe"))
                .when(failedEmitter).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        ReflectionTestUtils.setField(brokenSubscriber, "emitter", failedEmitter);

        service.publish(event("message.delta", null, 1));

        org.assertj.core.api.Assertions.assertThat(subscriberCount()).isEqualTo(1);
        verify(failedEmitter, org.mockito.Mockito.never()).completeWithError(org.mockito.ArgumentMatchers.any());
        Object remaining = subscribers.get(CONVERSATION_ID).iterator().next();
        org.assertj.core.api.Assertions.assertThat(ReflectionTestUtils.getField(remaining, "lastSequence")).isEqualTo(1L);
        verify(mapper, org.mockito.Mockito.never()).completeRun(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @SuppressWarnings("unchecked")
    private int subscriberCount() {
        Map<Long, Set<?>> subscribers = (Map<Long, Set<?>>) ReflectionTestUtils.getField(service, "subscribers");
        Set<?> values = subscribers == null ? null : subscribers.get(CONVERSATION_ID);
        return values == null ? 0 : values.size();
    }

    private AuthenticatedAccount account() {
        return new AuthenticatedAccount(
                USER_ID, "test001", AccountRole.USER, 0, AccountStatus.ACTIVE,
                null, null, null
        );
    }
}
