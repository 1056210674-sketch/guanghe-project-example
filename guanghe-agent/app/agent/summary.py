"""Text-only summary adapter; deliberately has no router, tools or task proposer."""

import asyncio
from collections.abc import Callable

from app.agent.identity import require_capability
from app.agent.models import (
    BusinessOutcome,
    BusinessOutcomeStatus,
    ChatRunRequest,
    Intent,
    RunOutcome,
    RunProgressStage,
    RunProgressStatus,
)
from app.agent.progress import RunProgressHandler, notify_progress
from app.harness.runtime import (
    HarnessViolation,
    RuntimeHarness,
    RuntimeUsageCounters,
    bind_runtime_usage,
)
from app.model_gateway.gateway import DeltaHandler, ModelGateway, ModelGatewayError, ModelMessage
from app.observability.tracing import start_span
from app.providers.java_context import AuthorizedRunContext


class SummaryTextAdapter:
    def __init__(self, gateway: ModelGateway, system_prompt: str) -> None:
        self._gateway = gateway
        self._system_prompt = system_prompt
        self._harness = RuntimeHarness()

    @staticmethod
    def graph_for_intent(intent: Intent) -> str:
        del intent
        return "summary-text-v1"

    async def ainvoke(
        self,
        request: ChatRunRequest,
        *,
        authorized_context: AuthorizedRunContext,
        usage_counters: RuntimeUsageCounters,
        on_delta: DeltaHandler | None = None,
        on_progress: RunProgressHandler | None = None,
        on_context: Callable[[AuthorizedRunContext], None] | None = None,
    ) -> RunOutcome:
        require_capability("text.summarize")
        counters = usage_counters
        if on_context is not None:
            on_context(authorized_context)
        try:
            with bind_runtime_usage(counters):
                async with self._harness.enforce_deadline(request):
                    counters.before_workflow_step()
                    self._harness.validate_request(request)
                    # Only this turn's literal text is input. No catalog, memory, file,
                    # report, pending draft or Java resource client can reach this adapter.
                    messages, tokens = self._harness.fit_model_messages(
                        request,
                        (
                            ModelMessage(role="system", content=self._system_prompt),
                            ModelMessage(role="user", content=request.message.content),
                        ),
                        target_input_tokens=request.budget.max_input_tokens,
                        available_history_tokens=0,
                    )
                    counters.observe_estimated_input_tokens(tokens)
                    await notify_progress(
                        on_progress,
                        RunProgressStage.COMPOSING_RESPONSE,
                        RunProgressStatus.RUNNING,
                    )
                    chars = 0

                    async def bounded_delta(delta: str) -> None:
                        nonlocal chars
                        chars += len(delta)
                        if chars > 16_000:
                            raise HarnessViolation("SUMMARY_OUTPUT_LIMIT", "Summary is too long")
                        if on_delta is not None:
                            await on_delta(delta)

                    with start_span("agent.summary.text"):
                        answer = await self._gateway.answer_general(
                            messages, on_delta=bounded_delta
                        )
                    counters.record_gateway_usage(answer.usage)
                    if not answer.text.strip() or len(answer.text) > 16_000:
                        raise HarnessViolation("SUMMARY_RESPONSE_INVALID", "Invalid summary")
                    counters.before_workflow_step()
                    await notify_progress(
                        on_progress,
                        RunProgressStage.COMPOSING_RESPONSE,
                        RunProgressStatus.SUCCEEDED,
                    )
                    outcome = RunOutcome(
                        kind="MESSAGE",
                        intent=Intent.GENERAL,
                        message=answer.text,
                        usage=counters.usage(
                            provider=self._gateway.identity.provider,
                            model=self._gateway.identity.model,
                        ),
                        business_outcome=BusinessOutcome(status=BusinessOutcomeStatus.ANSWERED),
                    )
                    self._harness.validate_outcome(request, outcome)
                    return outcome
        except (Exception, asyncio.CancelledError) as exc:
            if isinstance(exc, ModelGatewayError):
                counters.record_gateway_usage(exc.usage)
            exc.usage = counters.usage(  # type: ignore[attr-defined]
                provider=self._gateway.identity.provider, model=self._gateway.identity.model
            )
            raise
