package com.guanghe.backend.conversation;

import com.guanghe.backend.integration.internal.client.PythonCallResult;
import com.guanghe.backend.integration.internal.client.PythonChatRunAccepted;
import com.guanghe.backend.integration.internal.client.PythonChatRunEvent;
import com.guanghe.backend.integration.internal.client.PythonChatRunRequest;
import com.guanghe.backend.integration.internal.client.PythonInternalClient;
import com.guanghe.backend.integration.internal.client.PythonStopRunResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationRunCoordinatorTests {

    private final UUID runId = UUID.randomUUID();
    private PythonInternalClient pythonClient;
    private ConversationRunPersistenceService persistence;
    private ConversationEventStreamService eventStream;
    private ConversationRunCoordinator coordinator;

    @BeforeEach
    void setUp() {
        pythonClient = mock(PythonInternalClient.class);
        persistence = mock(ConversationRunPersistenceService.class);
        eventStream = mock(ConversationEventStreamService.class);
        coordinator = new ConversationRunCoordinator(
                pythonClient, persistence, eventStream, new ConversationProperties(), Runnable::run
        );
    }

    @Test
    void pythonUnavailableFailsThePersistedRunOnceWithoutEscapingToTheHttpRequest() {
        AgentRunRecord run = run("STARTING");
        ConversationEventRecord failure = event("message.completed");
        when(persistence.markStarting(runId)).thenReturn(run);
        when(pythonClient.startChatRun(any(PythonChatRunRequest.class))).thenReturn(
                PythonCallResult.unavailable(null, PythonCallResult.Reason.CONNECTION_FAILED)
        );
        when(persistence.failRun(runId, "AGENT_START_CONNECTION_FAILED", ""))
                .thenReturn(Optional.of(failure));

        assertThatCode(() -> coordinator.dispatch(submission())).doesNotThrowAnyException();

        ArgumentCaptor<PythonChatRunRequest> request = ArgumentCaptor.forClass(PythonChatRunRequest.class);
        verify(pythonClient, times(1)).startChatRun(request.capture());
        assertThat(request.getValue().budget().maxInputTokens()).isEqualTo(128000);
        assertThat(request.getValue().budget().maxModelCalls()).isEqualTo(4);
        assertThat(request.getValue().budget().maxToolCalls()).isEqualTo(6);
        assertThat(request.getValue().budget().maxSteps()).isEqualTo(12);
        assertThat(request.getValue().budget().maxContextLoads()).isEqualTo(1);
        assertThat(request.getValue().budget().maxProviderCalls()).isZero();
        assertThat(request.getValue().budget().deadlineAt()).isEqualTo(run.effectiveDeadline());
        assertThat(request.getValue().trustedState().pendingInteractionId()).isEqualTo("7001");
        assertThat(request.getValue().trustedState().draftId()).isEqualTo("6001");
        assertThat(request.getValue().trustedState().taskId()).isEqualTo("5001");
        verify(persistence, times(1)).failRun(runId, "AGENT_START_CONNECTION_FAILED", "");
        verify(eventStream).publish(failure);
    }

    @Test
    void forwardsOnlyAuthorizedCurrentAttachmentsAndCatalogScopeToPython() {
        when(persistence.markStarting(runId)).thenReturn(run("STARTING"));
        when(pythonClient.startChatRun(any())).thenReturn(
                PythonCallResult.unavailable(null, PythonCallResult.Reason.CONNECTION_FAILED));
        when(persistence.failRun(runId, "AGENT_START_CONNECTION_FAILED", "")).thenReturn(Optional.empty());
        var accepted = new ConversationService.MessageSubmission(
                new ConversationDtos.MessageAccepted("8001", runId.toString(), "QUEUED"),
                true, "opaque-context-reference", "revise attached image", null, null, null,
                null, "6001", null, java.util.List.of("998"));
        coordinator.dispatch(accepted);
        ArgumentCaptor<PythonChatRunRequest> captured = ArgumentCaptor.forClass(PythonChatRunRequest.class);
        verify(pythonClient).startChatRun(captured.capture());
        assertThat(captured.getValue().message().attachmentAssetIds()).containsExactly("998");
        assertThat(captured.getValue().trustedState().selectedSpaceId()).isNull();
        assertThat(captured.getValue().trustedState().selectedProductId()).isNull();
        assertThat(captured.getValue().trustedState().draftId()).isEqualTo("6001");
    }

    @Test
    void stopIsForwardedOnceAndJavaFinalizesFreeRunWhenPythonIsUnavailable() {
        ConversationEventRecord stoppedEvent = event("message.completed");
        when(pythonClient.stopChatRun(runId)).thenReturn(
                PythonCallResult.<PythonStopRunResponse>unavailable(
                        null, PythonCallResult.Reason.CONNECTION_FAILED
                )
        );
        when(persistence.stopRun(runId)).thenReturn(new ConversationRunPersistenceService.StopResult(
                "STOPPED", Optional.of(stoppedEvent)
        ));

        ConversationDtos.StopAccepted result = coordinator.stop(run("STOP_REQUESTED"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.status()).isEqualTo("STOPPED");
        verify(pythonClient, times(1)).stopChatRun(runId);
        verify(persistence).stopRun(runId);
        verify(eventStream).publish(stoppedEvent);
    }

    @Test
    void stopResponseUsesTheDatabaseFinalStatusWhenCompletionWonTheRace() {
        when(pythonClient.stopChatRun(runId)).thenReturn(PythonCallResult.available(
                202, new PythonStopRunResponse(runId, true, "STOPPED")
        ));
        when(persistence.stopRun(runId)).thenReturn(new ConversationRunPersistenceService.StopResult(
                "COMPLETED", Optional.empty()
        ));

        ConversationDtos.StopAccepted result = coordinator.stop(run("STOP_REQUESTED"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.accepted()).isFalse();
        verify(eventStream, times(0)).publish(any());
    }

    @Test
    void retryingStopForATerminalRunDoesNotForwardOrTouchAnotherRun() {
        ConversationDtos.StopAccepted result = coordinator.stop(run("STOPPED"));
        assertThat(result.runId()).isEqualTo(runId.toString());
        assertThat(result.status()).isEqualTo("STOPPED");
        assertThat(result.accepted()).isTrue();
        verify(pythonClient, never()).stopChatRun(any());
        verify(persistence, never()).stopRun(any());
    }

    @Test
    void invalidPythonEventFailsTheRunWithoutPublishingTheRejectedEvent() {
        AgentRunRecord run = run("STARTING");
        PythonChatRunEvent invalid = mock(PythonChatRunEvent.class);
        ConversationEventRecord failure = event("message.completed");
        when(persistence.markStarting(runId)).thenReturn(run);
        when(pythonClient.startChatRun(any(PythonChatRunRequest.class))).thenReturn(PythonCallResult.available(
                202, new PythonChatRunAccepted(
                        runId, "RUNNING", "/internal/v1/chat-runs/" + runId + "/events"
                )
        ));
        when(persistence.processEvent(runId, invalid, ""))
                .thenReturn(ConversationRunPersistenceService.ProcessedEvent.invalidEvent());
        when(pythonClient.streamChatRunEvents(any(), any())).thenAnswer(invocation -> {
            Consumer<PythonChatRunEvent> consumer = invocation.getArgument(1);
            try {
                consumer.accept(invalid);
            } catch (RuntimeException ignored) {
                // The real client converts the consumer stop signal into an unavailable stream result.
            }
            return PythonCallResult.<Void>unavailable(200, PythonCallResult.Reason.INVALID_RESPONSE);
        });
        when(persistence.failRun(runId, "AGENT_STREAM_INVALID_RESPONSE", ""))
                .thenReturn(Optional.of(failure));

        coordinator.dispatch(submission());

        verify(persistence).failRun(runId, "AGENT_STREAM_INVALID_RESPONSE", "");
        verify(eventStream, times(1)).publish(failure);
    }

    @Test
    void droppedProgressDoesNotStopTheRunOrHideItsCompletion() {
        AgentRunRecord run = run("STARTING");
        PythonChatRunEvent progress = mock(PythonChatRunEvent.class);
        PythonChatRunEvent completed = mock(PythonChatRunEvent.class);
        ConversationEventRecord completion = event("message.completed");
        when(persistence.markStarting(runId)).thenReturn(run);
        when(pythonClient.startChatRun(any(PythonChatRunRequest.class))).thenReturn(PythonCallResult.available(
                202, new PythonChatRunAccepted(
                        runId, "RUNNING", "/internal/v1/chat-runs/" + runId + "/events"
                )
        ));
        when(persistence.processEvent(runId, progress, ""))
                .thenReturn(ConversationRunPersistenceService.ProcessedEvent.withoutBrowserEvent());
        when(persistence.processEvent(runId, completed, ""))
                .thenReturn(new ConversationRunPersistenceService.ProcessedEvent(
                        Optional.of(completion), true, false, false, null
                ));
        when(pythonClient.streamChatRunEvents(any(), any())).thenAnswer(invocation -> {
            Consumer<PythonChatRunEvent> consumer = invocation.getArgument(1);
            consumer.accept(progress);
            consumer.accept(completed);
            return PythonCallResult.<Void>available(200, null);
        });

        coordinator.dispatch(submission());

        verify(eventStream).publish(completion);
        verify(persistence, never()).failRun(any(), any(), any());
    }

    @Test
    void committedReplacementClearsAccumulatorBeforeTheNextTokenAndCompletion() {
        var preview = mock(PythonChatRunEvent.class);
        var reset = mock(PythonChatRunEvent.class);
        var finalDelta = mock(PythonChatRunEvent.class);
        var revised = mock(PythonChatRunEvent.class);
        var suffix = mock(PythonChatRunEvent.class);
        var completed = mock(PythonChatRunEvent.class);
        when(persistence.markStarting(runId)).thenReturn(run("STARTING"));
        when(pythonClient.startChatRun(any())).thenReturn(PythonCallResult.available(
                202, new PythonChatRunAccepted(runId, "RUNNING", "/internal/v1/chat-runs/" + runId + "/events")));
        when(persistence.processEvent(runId, preview, "")).thenReturn(
                ConversationRunPersistenceService.ProcessedEvent.withoutBrowserEvent().withAcceptedDelta("preview", false));
        when(persistence.processEvent(runId, reset, "preview")).thenReturn(
                ConversationRunPersistenceService.ProcessedEvent.withoutBrowserEvent().withAcceptedDelta("", true));
        when(persistence.processEvent(runId, finalDelta, "")).thenReturn(
                ConversationRunPersistenceService.ProcessedEvent.withoutBrowserEvent().withAcceptedDelta("new answer", false));
        when(persistence.processEvent(runId, revised, "new answer")).thenReturn(
                ConversationRunPersistenceService.ProcessedEvent.withoutBrowserEvent().withAcceptedDelta("revised", true));
        when(persistence.processEvent(runId, suffix, "revised")).thenReturn(
                ConversationRunPersistenceService.ProcessedEvent.withoutBrowserEvent().withAcceptedDelta("!", false));
        when(persistence.processEvent(runId, completed, "revised!")).thenReturn(
                ConversationRunPersistenceService.ProcessedEvent.terminalWithoutEvent());
        when(pythonClient.streamChatRunEvents(any(), any())).thenAnswer(invocation -> {
            Consumer<PythonChatRunEvent> consumer = invocation.getArgument(1);
            for (var event : java.util.List.of(preview, reset, finalDelta, revised, suffix, completed)) {
                consumer.accept(event);
            }
            return PythonCallResult.<Void>available(200, null);
        });

        coordinator.dispatch(submission());

        verify(persistence).processEvent(runId, reset, "preview");
        verify(persistence).processEvent(runId, finalDelta, "");
        verify(persistence).processEvent(runId, suffix, "revised");
        verify(persistence).processEvent(runId, completed, "revised!");
        verify(persistence, never()).failRun(any(), any(), any());
    }

    private ConversationService.MessageSubmission submission() {
        return new ConversationService.MessageSubmission(
                new ConversationDtos.MessageAccepted("8001", runId.toString(), "QUEUED"),
                true, "opaque-context-reference", "hello", null, null, null,
                "7001", "6001", "5001"
        );
    }

    @Test
    void anExpiredPersistedDeadlineDoesNotStartAnotherProviderRequest() {
        AgentRunRecord expired = mock(AgentRunRecord.class);
        when(expired.status()).thenReturn("STARTING");
        when(expired.effectiveDeadline()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        when(persistence.markStarting(runId)).thenReturn(expired);
        when(persistence.failRun(runId, "AGENT_RUN_DEADLINE_EXCEEDED", "")).thenReturn(Optional.of(event("message.completed")));
        coordinator.dispatch(submission());
        verify(pythonClient, never()).startChatRun(any());
        verify(persistence).failRun(runId, "AGENT_RUN_DEADLINE_EXCEEDED", "");
    }

    private AgentRunRecord run(String status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AgentRunRecord(
                runId, 9001, 41, 8001, 8002L, null, null, "[]", status, 1,
                "{}", "a".repeat(64), now.plusMinutes(2), null, 0, "{}", null, null,
                "STOP_REQUESTED".equals(status) ? now : null, now, null, now, now
        );
    }

    private ConversationEventRecord event(String type) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new ConversationEventRecord(
                7001, 9001, 41, runId, 8002L, null, type, 1,
                "{\"status\":\"FAILED\"}", now, now
        );
    }
}
