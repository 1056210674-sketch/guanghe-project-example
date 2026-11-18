package com.guanghe.backend.conversation;

import com.guanghe.backend.common.web.TraceIdHolder;
import com.guanghe.backend.common.observability.ObservabilitySupport;
import com.guanghe.backend.integration.internal.client.PythonCallResult;
import com.guanghe.backend.integration.internal.client.PythonChatRunAccepted;
import com.guanghe.backend.integration.internal.client.PythonChatRunEvent;
import com.guanghe.backend.integration.internal.client.PythonChatRunRequest;
import com.guanghe.backend.integration.internal.client.PythonInternalClient;
import com.guanghe.backend.integration.internal.client.PythonStopRunResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "zhixiaohe.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConversationRunCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationRunCoordinator.class);

    private final PythonInternalClient pythonClient;
    private final ConversationRunPersistenceService persistence;
    private final ConversationEventStreamService eventStream;
    private final ConversationProperties properties;
    private final Executor executor;
    private final ObservabilitySupport observability;
    private com.guanghe.backend.agentplatform.AgentRuntimeCatalog runtimeCatalog =
            new com.guanghe.backend.agentplatform.AgentRuntimeCatalog();

    @Autowired
    void setRuntimeCatalog(com.guanghe.backend.agentplatform.AgentRuntimeCatalog value) { runtimeCatalog=value; }

    @Autowired
    public ConversationRunCoordinator(
            PythonInternalClient pythonClient,
            ConversationRunPersistenceService persistence,
            ConversationEventStreamService eventStream,
            ConversationProperties properties,
            @Qualifier("conversationRunExecutor") Executor executor,
            ObservabilitySupport observability
    ) {
        this.pythonClient = pythonClient;
        this.persistence = persistence;
        this.eventStream = eventStream;
        this.properties = properties;
        this.executor = executor;
        this.observability = observability;
    }

    public ConversationRunCoordinator(
            PythonInternalClient pythonClient,
            ConversationRunPersistenceService persistence,
            ConversationEventStreamService eventStream,
            ConversationProperties properties,
            @Qualifier("conversationRunExecutor") Executor executor
    ) {
        this(pythonClient, persistence, eventStream, properties, executor, ObservabilitySupport.noop());
    }

    public void dispatch(ConversationService.MessageSubmission submission) {
        if (!submission.newSubmission()) {
            return;
        }
        String traceparent = TraceIdHolder.outboundTraceparent();
        executor.execute(() -> runWithTrace(submission, traceparent));
    }

    public ConversationDtos.StopAccepted stop(AgentRunRecord run) {
        if (java.util.Set.of("COMPLETED", "FAILED", "STOPPED").contains(run.status())) {
            return new ConversationDtos.StopAccepted(run.id().toString(), "STOPPED".equals(run.status()), run.status());
        }
        PythonCallResult<PythonStopRunResponse> result = pythonClient.stopChatRun(run.id());
        ConversationRunPersistenceService.StopResult stopped = persistence.stopRun(run.id());
        stopped.browserEvent().ifPresent(eventStream::publish);
        if (!result.available()) {
            LOGGER.info("conversation_run_stop_forward_unavailable runIdRef={} errorCode={}",
                    observability.reference(run.id()), "AGENT_STOP_UNAVAILABLE");
        }
        return new ConversationDtos.StopAccepted(
                run.id().toString(), "STOPPED".equals(stopped.status()), stopped.status()
        );
    }

    private void runWithTrace(ConversationService.MessageSubmission submission, String traceparent) {
        String[] traceparts = traceparent == null ? new String[0] : traceparent.split("-", -1);
        String traceId = traceparts.length == 4 ? traceparts[1] : null;
        if (traceId != null && traceId.matches("[0-9a-f]{32}")) {
            MDC.put(TraceIdHolder.MDC_KEY, traceId);
        }
        try (ObservabilitySupport.Operation span = observability.startClient(
                "python.chat.run", "START_AND_STREAM", traceparent)) {
            span.tag("runIdRef", observability.reference(submission.response().runId()));
            try {
                execute(submission);
            } catch (RuntimeException exception) {
                span.fail("AGENT_BRIDGE_FAILED", "START_AND_STREAM");
                publishFailure(UUID.fromString(submission.response().runId()), "AGENT_BRIDGE_FAILED", "");
                LOGGER.warn("conversation_run_bridge_failed runIdRef={} errorCode={}",
                        observability.reference(submission.response().runId()), "AGENT_BRIDGE_FAILED");
            }
        } finally {
            MDC.remove(TraceIdHolder.MDC_KEY);
        }
    }

    private void execute(ConversationService.MessageSubmission submission) {
        UUID runId = UUID.fromString(submission.response().runId());
        AgentRunRecord run = persistence.markStarting(runId);
        if (run == null || !"STARTING".equals(run.status())) {
            return;
        }
        if (!run.effectiveDeadline().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            publishFailure(runId, "AGENT_RUN_DEADLINE_EXCEEDED", "");
            return;
        }
        PythonChatRunRequest request = new PythonChatRunRequest(
                runId,
                Long.toString(run.conversationId()),
                submission.contextRef(),
                new PythonChatRunRequest.Message(
                        Long.toString(run.requestMessageId()), submission.content(),
                        submission.replyToMessageId() == null ? null : Long.toString(submission.replyToMessageId()),
                        submission.attachmentAssetIds()
                ),
                new PythonChatRunRequest.TrustedState(
                        id(submission.selectedSpaceId()), id(submission.selectedProductId()),
                        submission.pendingInteractionId(), submission.draftId(), submission.taskId()
                ),
                new PythonChatRunRequest.Budget(
                        nativeLoop(run) ? 6 : 4, nativeLoop(run) ? 12 : 6,
                        nativeLoop(run) ? 24 : 12, 1, webSearchProviderBudget(run), properties.getTargetInputTokens(),
                        run.effectiveDeadline()
                )
        );
        PythonCallResult<PythonChatRunAccepted> accepted = pythonClient.startChatRun(request);
        if (!accepted.available()) {
            publishFailure(runId, "AGENT_START_" + accepted.reason().name(), "");
            return;
        }
        persistence.markRunning(runId, accepted.payload().eventsUrl());

        StringBuilder answer = new StringBuilder();
        AtomicBoolean terminal = new AtomicBoolean();
        PythonCallResult<Void> streamResult = pythonClient.streamChatRunEvents(runId, event -> {
            observability.observe("java.sse.persist-and-publish", "PERSIST_AND_PUBLISH", span -> {
                span.tag("runIdRef", observability.reference(runId));
                span.tag("eventType", event.eventType());
            }, () -> {
                ConversationRunPersistenceService.ProcessedEvent processed = persistence.processEvent(
                        runId, event, answer.toString()
                );
                if (processed.acceptedDelta() != null) {
                    // Only an accepted, committed replacement may clear this accumulator.
                    // Otherwise the next token would resurrect an obsolete model preview.
                    if (processed.replacesText()) answer.setLength(0);
                    answer.append(processed.acceptedDelta());
                }
                processed.browserEvent().ifPresent(eventStream::publish);
                if (processed.invalid()) {
                    throw new StopConsumingEvents("AGENT_EVENT_INVALID");
                }
                if (processed.taskDraftRejected()) {
                    terminal.set(true);
                    pythonClient.stopChatRun(runId);
                    throw new StopConsumingEvents("TASK_DRAFT_NOT_SUPPORTED");
                }
                if (processed.terminal()) {
                    terminal.set(true);
                }
            });
        });
        if (!terminal.get()) {
            String code = streamResult.available()
                    ? "AGENT_STREAM_ENDED_EARLY"
                    : "AGENT_STREAM_" + streamResult.reason().name();
            publishFailure(runId, code, answer.toString());
        }
    }

    private boolean nativeLoop(AgentRunRecord run) {
        try {
            var snapshot=new com.fasterxml.jackson.databind.ObjectMapper().readTree(run.contextSnapshotJson());
            var identity = new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(snapshot.path("agentIdentity"),
                    com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity.class);
            if (identity == null) return false;
            identity=runtimeCatalog.validateFrozen(identity);
            return "native".equals(runtimeCatalog.require(identity).runtimeKind())
                    && !snapshot.hasNonNull("monitorActionContext") && !snapshot.hasNonNull("articlePreparationMode");
        } catch (com.fasterxml.jackson.core.JsonProcessingException | com.guanghe.backend.common.api.BusinessException invalid) { return false; }
    }

    private int webSearchProviderBudget(AgentRunRecord run) {
        try {
            var snapshot = new com.fasterxml.jackson.databind.ObjectMapper().readTree(run.contextSnapshotJson());
            var identity = new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(snapshot.path("agentIdentity"),
                    com.guanghe.backend.agentplatform.AgentPlatformDtos.AgentIdentity.class);
            // Only the persisted, Java-authorized identity can open this budget.
            // Explicit paid actions retain their separately frozen worker budgets.
            if (identity == null
                    || snapshot.hasNonNull("monitorActionContext") || snapshot.hasNonNull("articlePreparationMode")) {
                return 0;
            }
            identity=runtimeCatalog.validateFrozen(identity);
            return runtimeCatalog.permits(identity,"web.search") ? properties.getWebSearchMaxProviderCalls() : 0;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | com.guanghe.backend.common.api.BusinessException invalid) {
            return 0;
        }
    }

    private void publishFailure(UUID runId, String code, String answer) {
        persistence.failRun(runId, code, answer).ifPresent(eventStream::publish);
    }

    private String id(Long value) {
        return value == null ? null : Long.toString(value);
    }

    private static final class StopConsumingEvents extends RuntimeException {
        private StopConsumingEvents(String message) {
            super(message);
        }
    }
}
