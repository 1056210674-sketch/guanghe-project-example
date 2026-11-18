"""Budget and boundary enforcement around the Agent graph."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncGenerator, Generator
from contextlib import asynccontextmanager, contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import TYPE_CHECKING

from app.agent.models import ChatRunRequest, RunOutcome, Usage
from app.common.enums import TaskType

if TYPE_CHECKING:
    from app.model_gateway.gateway import GatewayUsage, ModelMessage, ModelMessages


class HarnessViolation(ValueError):
    def __init__(self, code: str, message: str) -> None:
        self.code = code
        super().__init__(message)


class ProviderCallBoundaryError(RuntimeError):
    """A Worker Provider HTTP action would exceed its Java-authorized boundary."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


class RuntimeHarness:
    """Enforce technical budgets without taking over Java business rules."""

    _forbidden_output_terms = (
        "confirmationToken",
        "quotedPoints",
        "pricingVersionId",
        "chargeSucceeded",
    )

    def validate_request(self, request: ChatRunRequest) -> int:
        if request.budget.deadline_at <= datetime.now(UTC):
            raise HarnessViolation("RUN_DEADLINE_EXCEEDED", "Run deadline has already passed")
        estimated_tokens = max(1, (len(request.message.content) + 3) // 4)
        if estimated_tokens > request.budget.max_input_tokens:
            raise HarnessViolation("INPUT_TOKEN_BUDGET_EXCEEDED", "Input exceeds token budget")
        return estimated_tokens

    @staticmethod
    def include_authorized_context(
        request: ChatRunRequest,
        estimated_tokens: int,
        serialized_context: str,
    ) -> int:
        """Account for one Run's authorized snapshot before any model can receive it."""
        context_tokens = max(1, (len(serialized_context) + 3) // 4)
        total = estimated_tokens + context_tokens
        if total > request.budget.max_input_tokens:
            raise HarnessViolation(
                "INPUT_TOKEN_BUDGET_EXCEEDED",
                "Input and authorized context exceed token budget",
            )
        return total

    @staticmethod
    def fit_model_messages(
        request: ChatRunRequest,
        messages: ModelMessages,
        *,
        target_input_tokens: int,
        available_history_tokens: int,
    ) -> tuple[ModelMessages, int]:
        """Trim only oldest history while retaining policy, task state and current turn."""
        if len(messages) < 2 or messages[0].role != "system" or messages[-1].role != "user":
            raise HarnessViolation("MODEL_MESSAGES_INVALID", "Model input boundary is invalid")
        effective_limit = min(request.budget.max_input_tokens, target_input_tokens)
        fixed = (messages[0], messages[-1])
        fixed_tokens = sum(_estimate_message_tokens(message) for message in fixed)
        if fixed_tokens > effective_limit:
            raise HarnessViolation(
                "INPUT_TOKEN_BUDGET_EXCEEDED",
                "System task state and current message exceed token budget",
            )
        history_limit = min(available_history_tokens, effective_limit - fixed_tokens)
        selected_reversed: list[ModelMessage] = []
        history_tokens = 0
        for message in reversed(messages[1:-1]):
            tokens = _estimate_message_tokens(message)
            if history_tokens + tokens > history_limit:
                break
            selected_reversed.append(message)
            history_tokens += tokens
        selected = tuple(reversed(selected_reversed))
        fitted = (messages[0], *selected, messages[-1])
        counters = _CURRENT_USAGE_COUNTERS.get()
        if counters is not None and counters.request.run_id == request.run_id:
            counters.model_input_token_limit = effective_limit
        return fitted, fixed_tokens + history_tokens

    @asynccontextmanager
    async def enforce_deadline(self, request: ChatRunRequest) -> AsyncGenerator[None]:
        """Cancel the whole graph when the authoritative Run deadline is reached."""
        remaining_seconds = (request.budget.deadline_at - datetime.now(UTC)).total_seconds()
        if remaining_seconds <= 0:
            raise HarnessViolation("RUN_DEADLINE_EXCEEDED", "Run deadline has already passed")

        deadline = asyncio.timeout(remaining_seconds)
        try:
            async with deadline:
                yield
        except TimeoutError as exc:
            if not deadline.expired():
                raise
            raise HarnessViolation(
                "RUN_DEADLINE_EXCEEDED",
                "Run deadline was exceeded during graph execution",
            ) from exc
        if deadline.expired():
            raise HarnessViolation(
                "RUN_DEADLINE_EXCEEDED",
                "Run deadline was exceeded during graph execution",
            )

    def validate_outcome(self, request: ChatRunRequest, outcome: RunOutcome) -> None:
        usage = outcome.usage
        if usage.workflow_step_count > request.budget.max_steps:
            raise HarnessViolation("STEP_BUDGET_EXCEEDED", "Graph exceeded step budget")
        if usage.model_call_count > request.budget.max_model_calls:
            raise HarnessViolation("MODEL_CALL_BUDGET_EXCEEDED", "Graph exceeded model budget")
        if usage.tool_call_count > request.budget.max_tool_calls:
            raise HarnessViolation("TOOL_CALL_BUDGET_EXCEEDED", "Graph exceeded tool budget")
        if usage.context_load_count > request.budget.max_context_loads:
            raise HarnessViolation(
                "CONTEXT_LOAD_BUDGET_EXCEEDED",
                "Run exceeded context load budget",
            )
        if usage.provider_call_count > request.budget.max_provider_calls:
            raise HarnessViolation(
                "PROVIDER_CALL_BUDGET_EXCEEDED",
                "Run exceeded Provider call budget",
            )
        if outcome.task_draft is not None and outcome.task_draft.task_type is TaskType.MEDIA:
            raise HarnessViolation("MEDIA_NOT_AGENT_OWNED", "MEDIA is not owned by Python")
        serialized = outcome.model_dump_json(by_alias=True)
        if any(term in serialized for term in self._forbidden_output_terms):
            raise HarnessViolation("BUSINESS_COMMIT_OUTPUT_FORBIDDEN", "Forbidden business output")

    @staticmethod
    def validate_next_model_call(request: ChatRunRequest, next_count: int) -> None:
        if next_count > request.budget.max_model_calls:
            raise HarnessViolation(
                "MODEL_CALL_BUDGET_EXCEEDED",
                "Graph would exceed model budget",
            )

    @staticmethod
    def validate_next_tool_call(request: ChatRunRequest, next_count: int) -> None:
        if next_count > request.budget.max_tool_calls:
            raise HarnessViolation(
                "TOOL_CALL_BUDGET_EXCEEDED",
                "Graph would exceed tool budget",
            )

    @staticmethod
    def validate_next_step(request: ChatRunRequest, next_count: int) -> None:
        if next_count > request.budget.max_steps:
            raise HarnessViolation(
                "STEP_BUDGET_EXCEEDED",
                "Graph would exceed step budget",
            )

    @staticmethod
    def validate_action_deadline(request: ChatRunRequest) -> None:
        if request.budget.deadline_at <= datetime.now(UTC):
            raise HarnessViolation("RUN_DEADLINE_EXCEEDED", "Run deadline has already passed")

    def new_usage_counters(self, request: ChatRunRequest) -> RuntimeUsageCounters:
        return RuntimeUsageCounters(request=request, harness=self)


@dataclass(slots=True)
class RuntimeUsageCounters:
    """Per-Run mutable counters; never store this object on a shared runtime singleton."""

    request: ChatRunRequest
    harness: RuntimeHarness
    workflow_step_count: int = 0
    model_call_count: int = 0
    context_load_count: int = 0
    tool_call_count: int = 0
    resource_tool_call_count: int = 0
    provider_call_count: int = 0
    estimated_input_tokens: int = 0
    model_input_token_limit: int | None = None
    provider_input_tokens: int = 0
    provider_output_tokens: int = 0
    provider_latency_ms: int = 0

    def before_workflow_step(self) -> None:
        self.harness.validate_action_deadline(self.request)
        next_count = self.workflow_step_count + 1
        self.harness.validate_next_step(self.request, next_count)
        self.workflow_step_count = next_count

    def before_model_call(self) -> None:
        self.harness.validate_action_deadline(self.request)
        next_count = self.model_call_count + 1
        self.harness.validate_next_model_call(self.request, next_count)
        self.model_call_count = next_count

    def before_context_load(self) -> None:
        self.harness.validate_action_deadline(self.request)
        next_count = self.context_load_count + 1
        if next_count > self.request.budget.max_context_loads:
            raise HarnessViolation(
                "CONTEXT_LOAD_BUDGET_EXCEEDED",
                "Run would exceed context load budget",
            )
        self.context_load_count = next_count

    def before_tool_call(self) -> None:
        self.harness.validate_action_deadline(self.request)
        next_count = self.tool_call_count + 1
        self.harness.validate_next_tool_call(self.request, next_count)
        self.tool_call_count = next_count

    def before_resource_tool_call(self) -> None:
        """Fixed per-Run ceiling, including failures; tighter tool budget still applies."""
        self.harness.validate_action_deadline(self.request)
        if self.resource_tool_call_count >= 6:
            raise HarnessViolation(
                "RESOURCE_TOOL_BUDGET_EXCEEDED", "Resource query budget exhausted"
            )
        self.harness.validate_next_tool_call(self.request, self.tool_call_count + 1)
        self.resource_tool_call_count += 1

    def before_provider_call(self) -> None:
        self.harness.validate_action_deadline(self.request)
        next_count = self.provider_call_count + 1
        if next_count > self.request.budget.max_provider_calls:
            raise HarnessViolation(
                "PROVIDER_CALL_BUDGET_EXCEEDED",
                "Run would exceed Provider call budget",
            )
        self.provider_call_count = next_count

    def observe_estimated_input_tokens(self, value: int) -> None:
        self.estimated_input_tokens = max(self.estimated_input_tokens, value)

    def record_gateway_usage(self, usage: GatewayUsage) -> None:
        """Retain billable response telemetry even when later parsing fails."""
        self.provider_input_tokens += usage.input_tokens
        self.provider_output_tokens += usage.output_tokens
        self.provider_latency_ms += usage.latency_ms

    def usage(
        self,
        *,
        provider: str | None,
        model: str | None,
    ) -> Usage:
        return Usage(
            workflow_step_count=self.workflow_step_count,
            model_call_count=self.model_call_count,
            context_load_count=self.context_load_count,
            tool_call_count=self.tool_call_count,
            provider_call_count=self.provider_call_count,
            model_calls=self.model_call_count,
            tool_calls=self.tool_call_count,
            steps=self.workflow_step_count,
            estimated_input_tokens=self.estimated_input_tokens,
            provider_input_tokens=self.provider_input_tokens,
            provider_output_tokens=self.provider_output_tokens,
            provider_latency_ms=self.provider_latency_ms,
            provider=provider,
            model=model,
        )


_CURRENT_USAGE_COUNTERS: ContextVar[RuntimeUsageCounters | None] = ContextVar(
    "guanghe_agent_runtime_usage_counters",
    default=None,
)


@dataclass(slots=True)
class _ProviderCallBoundary:
    max_calls: int
    deadline_at: datetime | None
    call_count: int = 0

    def before_call(self) -> None:
        if self.deadline_at is not None and datetime.now(UTC) >= self.deadline_at:
            raise ProviderCallBoundaryError("PROVIDER_DEADLINE_EXCEEDED")
        if self.call_count + 1 > self.max_calls:
            raise ProviderCallBoundaryError("PROVIDER_CALL_BUDGET_EXCEEDED")
        self.call_count += 1


_CURRENT_PROVIDER_CALL_BOUNDARY: ContextVar[_ProviderCallBoundary | None] = ContextVar(
    "guanghe_agent_provider_call_boundary",
    default=None,
)


@dataclass(slots=True)
class _ModelCallBoundary:
    max_calls: int
    deadline_at: datetime
    max_input_tokens: int | None = None
    call_count: int = 0
    provider_input_tokens: int = 0
    provider_output_tokens: int = 0
    provider_latency_ms: int = 0

    def before_call(self, input_messages: ModelMessages | None = None) -> None:
        if datetime.now(UTC) >= self.deadline_at:
            raise HarnessViolation("RUN_DEADLINE_EXCEEDED", "Model deadline was exceeded")
        if self.call_count + 1 > self.max_calls:
            raise HarnessViolation("MODEL_CALL_BUDGET_EXCEEDED", "Model budget was exceeded")
        if self.max_input_tokens is not None and (
            input_messages is None
            or estimate_model_messages(input_messages) > self.max_input_tokens
        ):
            raise HarnessViolation(
                "INPUT_TOKEN_BUDGET_EXCEEDED", "Model request would exceed its input budget"
            )
        self.call_count += 1

    def record_gateway_usage(self, usage: GatewayUsage) -> None:
        """Keep only returned telemetry; in-flight tokens remain unknown."""
        self.provider_input_tokens += usage.input_tokens
        self.provider_output_tokens += usage.output_tokens
        self.provider_latency_ms += usage.latency_ms

    @property
    def observed_usage(self) -> GatewayUsage:
        from app.model_gateway.gateway import GatewayUsage

        return GatewayUsage(
            input_tokens=self.provider_input_tokens,
            output_tokens=self.provider_output_tokens,
            latency_ms=self.provider_latency_ms,
            request_count=self.call_count,
        )


_CURRENT_MODEL_CALL_BOUNDARY: ContextVar[_ModelCallBoundary | None] = ContextVar(
    "guanghe_agent_model_call_boundary",
    default=None,
)


@contextmanager
def bind_provider_call_boundary(
    *,
    max_calls: int,
    deadline_at: datetime | None,
) -> Generator[None]:
    """Bind one Worker's Java-authorized Provider HTTP budget to the current task."""
    if max_calls < 1:
        raise ProviderCallBoundaryError("PROVIDER_CALL_BUDGET_EXCEEDED")
    if deadline_at is not None and (deadline_at.tzinfo is None or deadline_at.utcoffset() is None):
        raise ProviderCallBoundaryError("PROVIDER_DEADLINE_INVALID")
    boundary = _ProviderCallBoundary(max_calls=max_calls, deadline_at=deadline_at)
    token = _CURRENT_PROVIDER_CALL_BOUNDARY.set(boundary)
    try:
        yield
    finally:
        _CURRENT_PROVIDER_CALL_BOUNDARY.reset(token)


@contextmanager
def bind_model_call_boundary(
    *,
    max_calls: int,
    deadline_at: datetime,
    max_input_tokens: int | None = None,
) -> Generator[_ModelCallBoundary]:
    """Bind a non-ChatRun domain kernel to the shared model call/deadline boundary."""
    if max_calls < 1:
        raise HarnessViolation("MODEL_CALL_BUDGET_EXCEEDED", "Model budget is empty")
    if deadline_at.tzinfo is None or deadline_at.utcoffset() is None:
        raise HarnessViolation("RUN_DEADLINE_INVALID", "Model deadline must include timezone")
    if max_input_tokens is not None and max_input_tokens < 1:
        raise HarnessViolation("INPUT_TOKEN_BUDGET_EXCEEDED", "Model input budget is empty")
    # Optional per-request ceiling, not a cumulative token allowance. Existing
    # non-ARTICLE callers keep their original call/deadline-only boundary.
    boundary = _ModelCallBoundary(
        max_calls=max_calls, deadline_at=deadline_at, max_input_tokens=max_input_tokens
    )
    token = _CURRENT_MODEL_CALL_BOUNDARY.set(boundary)
    try:
        yield boundary
    finally:
        _CURRENT_MODEL_CALL_BOUNDARY.reset(token)


@contextmanager
def bind_runtime_usage(counters: RuntimeUsageCounters) -> Generator[None]:
    """Bind one Run's counters across async calls without sharing them across Runs."""
    token = _CURRENT_USAGE_COUNTERS.set(counters)
    try:
        yield
    finally:
        _CURRENT_USAGE_COUNTERS.reset(token)


def record_model_call(*, input_messages: ModelMessages | None = None) -> None:
    """Count one real model HTTP request, including each retry, before it is sent."""
    boundary = _CURRENT_MODEL_CALL_BOUNDARY.get()
    if boundary is not None:
        boundary.before_call(input_messages)
    counters = _CURRENT_USAGE_COUNTERS.get()
    if counters is not None:
        counters.before_model_call()


def record_model_gateway_usage(usage: GatewayUsage) -> None:
    """Preserve returned non-Chat telemetry if a later request is cancelled."""
    boundary = _CURRENT_MODEL_CALL_BOUNDARY.get()
    if boundary is not None:
        boundary.record_gateway_usage(usage)


def validate_repaired_model_input(messages: ModelMessages) -> None:
    """Retain the current Run's fitted input ceiling when a gateway adds repair text."""
    counters = _CURRENT_USAGE_COUNTERS.get()
    if counters is None:
        return
    limit = min(
        counters.request.budget.max_input_tokens,
        counters.model_input_token_limit or counters.request.budget.max_input_tokens,
    )
    estimated_tokens = sum(_estimate_message_tokens(message) for message in messages)
    if estimated_tokens > limit:
        raise HarnessViolation(
            "INPUT_TOKEN_BUDGET_EXCEEDED", "Repair would exceed the fitted model input budget"
        )
    counters.observe_estimated_input_tokens(estimated_tokens)


def record_context_load() -> None:
    """Count one authoritative Java Context request before it is sent."""
    counters = _CURRENT_USAGE_COUNTERS.get()
    if counters is not None:
        counters.before_context_load()


def record_provider_call() -> None:
    """Count one external media/GEO Provider request before it is sent."""
    boundary = _CURRENT_PROVIDER_CALL_BOUNDARY.get()
    if boundary is not None:
        boundary.before_call()
    counters = _CURRENT_USAGE_COUNTERS.get()
    if counters is not None:
        counters.before_provider_call()
    from app.observability.metrics import record_provider_request

    record_provider_request()


def _estimate_message_tokens(message: ModelMessage) -> int:
    content = message.content
    return max(1, len(content), (len(content.encode("utf-8")) + 3) // 4) + 1


def estimate_model_messages(messages: ModelMessages) -> int:
    """Conservatively estimate the ordered messages before any model action."""
    return sum(_estimate_message_tokens(message) for message in messages)
