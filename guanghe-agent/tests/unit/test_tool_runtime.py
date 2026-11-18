"""Bounded Tool Registry, Policy, Executor, and observation tests."""

import asyncio
import hashlib
import json
import logging
import time
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from uuid import UUID

import pytest
from pydantic import BaseModel, ConfigDict, Field

from app.agent.models import ChatMessage, ChatRunRequest, RunBudget, TrustedState
from app.harness.runtime import HarnessViolation, RuntimeHarness
from app.observability.logging import JsonFormatter
from app.tools.registry import (
    DEFAULT_TOOL_REGISTRY,
    ConfirmationPolicy,
    ExecutionMode,
    RiskLevel,
    ToolCost,
    ToolDefinition,
    ToolExecutionError,
    ToolExecutor,
    ToolGroup,
    ToolHandler,
    ToolInvocationStatus,
    ToolObservation,
    ToolPolicy,
    ToolRegistry,
    ToolRegistryError,
    ToolRetryPolicy,
    ToolType,
)

_RUN_ID = UUID("11111111-1111-4111-8111-111111111111")


class _Input(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    value: str = Field(min_length=1, max_length=100)


class _Output(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    value: str


def _request(*, max_tool_calls: int = 6) -> ChatRunRequest:
    return ChatRunRequest(
        run_id=_RUN_ID,
        conversation_id="900000000000000001",
        user_context_ref="opaque-test-context",
        message=ChatMessage(message_id="message-1", content="测试工具"),
        trusted_state=TrustedState(),
        budget=RunBudget(
            max_tool_calls=max_tool_calls,
            deadline_at=datetime.now(UTC) + timedelta(minutes=1),
        ),
    )


def _definition(**changes: object) -> ToolDefinition:
    base = ToolDefinition(
        name="draft.prepare",
        version="1",
        description="Prepare a bounded draft.",
        input_model=_Input,
        output_model=_Output,
        tool_group=ToolGroup.TASK_PREPARATION,
        tool_type=ToolType.PURE_FUNCTION,
        risk_level=RiskLevel.PREPARE,
        execution_mode=ExecutionMode.INLINE,
        timeout_seconds=0.1,
        retry=ToolRetryPolicy(max_attempts=1, backoff_ms=0),
        cost=ToolCost(units=0),
        confirmation_policy=ConfirmationPolicy.NONE,
    )
    return replace(base, **changes)


def _executor(
    definition: ToolDefinition,
    handler: ToolHandler,
    *,
    observations: list[ToolObservation] | None = None,
) -> ToolExecutor:
    observer = None if observations is None else observations.append
    registry = ToolRegistry((definition,))
    return ToolExecutor(
        registry,
        ToolPolicy(
            allowed_tool_names=frozenset((definition.name,)),
            allowed_tool_groups=frozenset((definition.tool_group,)),
        ),
        {definition.name: lambda payload: payload},
        {definition.name: handler},
        observer=observer,
    )


def test_default_registry_contains_proposal_and_bounded_readonly_tools() -> None:
    definitions = DEFAULT_TOOL_REGISTRY.definitions

    assert tuple(item.name for item in definitions) == (
        "context.resources.search",
        "context.resources.read",
        "task_draft.propose",
        "context.catalog.inspect",
        "context.memory.search",
        "context.task_workspace.inspect",
        "analysis.competitor.compare",
        "analysis.evidence.aggregate",
        "workflow_plan.propose",
    )
    definition = DEFAULT_TOOL_REGISTRY.resolve("task_draft.propose", "1")
    assert definition.risk_level is RiskLevel.PREPARE
    assert definition.tool_group is ToolGroup.TASK_PREPARATION
    assert definition.execution_mode is ExecutionMode.INLINE
    assert definition.retry == ToolRetryPolicy(max_attempts=1, backoff_ms=0)
    assert definition.cost == ToolCost(units=0)
    assert definition.confirmation_policy is ConfirmationPolicy.NONE
    assert all(
        item.risk_level in {RiskLevel.QUERY, RiskLevel.ANALYZE}
        and item.tool_group is ToolGroup.TASK_PREPARATION
        and item.execution_mode is ExecutionMode.INLINE
        and item.confirmation_policy is ConfirmationPolicy.NONE
        for item in definitions
        if item.name not in {"task_draft.propose", "workflow_plan.propose"}
    )
    workflow = definitions[-1]
    assert workflow.name == "workflow_plan.propose"
    assert workflow.risk_level is RiskLevel.PREPARE
    assert workflow.tool_type is ToolType.JAVA_PROXY
    assert workflow.retry.max_attempts == 1


def test_model_tool_catalog_uses_registry_policy_and_java_group_intersection() -> None:
    definition = _definition()
    executor = _executor(definition, lambda _payload: _Output(value="safe"))

    catalog = executor.model_tool_catalog((ToolGroup.TASK_PREPARATION,))

    assert "draft.prepare@1" in catalog
    assert "group=TASK_PREPARATION" in catalog
    assert "risk=PREPARE" in catalog
    assert 'inputSchema={"additionalProperties":false' in catalog
    assert '"properties":{"value":{' in catalog
    assert '"maxLength":100' in catalog

    with pytest.raises(ToolExecutionError) as unknown:
        executor.model_tool_catalog(("UNKNOWN_GROUP",))
    assert unknown.value.code == "TOOL_GROUP_UNKNOWN"


def test_registry_rejects_commit_tools_before_they_can_be_resolved() -> None:
    with pytest.raises(ToolRegistryError) as captured:
        ToolRegistry((_definition(risk_level=RiskLevel.COMMIT),))

    assert captured.value.code == "TOOL_REGISTRY_COMMIT_FORBIDDEN"


def test_executor_requires_exact_preparer_and_handler_sets() -> None:
    definition = _definition()
    registry = ToolRegistry((definition,))
    policy = ToolPolicy(
        allowed_tool_names=frozenset((definition.name,)),
        allowed_tool_groups=frozenset((definition.tool_group,)),
    )

    with pytest.raises(ToolRegistryError) as missing:
        ToolExecutor(
            registry,
            policy,
            {},
            {definition.name: lambda _payload: _Output(value="safe")},
        )

    assert missing.value.args == ("TOOL_INPUT_PREPARER_SET_MISMATCH",)


@pytest.mark.parametrize(
    ("definition", "expected"),
    (
        (_definition(cost=ToolCost(units=1)), "TOOL_COST_BUDGET_EXCEEDED"),
        (
            _definition(confirmation_policy=ConfirmationPolicy.JAVA_REQUIRED),
            "TOOL_CONFIRMATION_REQUIRED",
        ),
    ),
)
def test_policy_rejects_unbudgeted_cost_or_confirmation_before_execution(
    definition: ToolDefinition,
    expected: str,
) -> None:
    calls = 0

    def handler(_payload: BaseModel) -> BaseModel:
        nonlocal calls
        calls += 1
        return _Output(value="unexpected")

    counters = RuntimeHarness().new_usage_counters(_request())
    with pytest.raises(ToolExecutionError) as captured:
        asyncio.run(
            _executor(definition, handler).execute(
                run_id=_RUN_ID,
                name=definition.name,
                version=definition.version,
                payload={"value": "safe"},
                counters=counters,
                authorized_tool_groups=(definition.tool_group,),
            )
        )

    assert captured.value.code == expected
    assert calls == 0
    assert counters.tool_call_count == 0


def test_policy_schema_and_version_fail_before_handler_execution() -> None:
    calls = 0

    def handler(payload: BaseModel) -> BaseModel:
        nonlocal calls
        calls += 1
        return _Output(value=payload.model_dump()["value"])

    definition = _definition()
    counters = RuntimeHarness().new_usage_counters(_request())
    denied = ToolExecutor(
        ToolRegistry((definition,)),
        ToolPolicy(frozenset(), frozenset((ToolGroup.TASK_PREPARATION,))),
        {},
        {},
    )

    async def scenario() -> None:
        for executor, name, version, payload, expected in (
            (denied, definition.name, "1", {"value": "ok"}, "TOOL_POLICY_DENIED"),
            (
                _executor(definition, handler),
                "missing.tool",
                "1",
                {"value": "ok"},
                "TOOL_NOT_REGISTERED",
            ),
            (
                _executor(definition, handler),
                definition.name,
                "2",
                {"value": "ok"},
                "TOOL_VERSION_NOT_REGISTERED",
            ),
            (
                _executor(definition, handler),
                definition.name,
                "1",
                {"value": "ok", "admin": "commit"},
                "TOOL_INPUT_INVALID",
            ),
        ):
            with pytest.raises(ToolExecutionError) as captured:
                await executor.execute(
                    run_id=_RUN_ID,
                    name=name,
                    version=version,
                    payload=payload,
                    counters=counters,
                    authorized_tool_groups=(definition.tool_group,),
                )
            assert captured.value.code == expected

    asyncio.run(scenario())
    assert calls == 0
    assert counters.tool_call_count == 0


@pytest.mark.parametrize(
    ("authorized_groups", "expected"),
    (
        ((), "TOOL_GROUP_NOT_AUTHORIZED"),
        (("IMAGE",), "TOOL_GROUP_UNKNOWN"),
    ),
)
def test_runtime_groups_must_be_registered_and_authorized(
    authorized_groups: tuple[str, ...],
    expected: str,
) -> None:
    calls = 0

    def handler(payload: BaseModel) -> BaseModel:
        nonlocal calls
        calls += 1
        return _Output(value=payload.model_dump()["value"])

    definition = _definition()
    counters = RuntimeHarness().new_usage_counters(_request())
    with pytest.raises(ToolExecutionError) as captured:
        asyncio.run(
            _executor(definition, handler).execute(
                run_id=_RUN_ID,
                name=definition.name,
                version="1",
                payload={"value": "safe"},
                counters=counters,
                authorized_tool_groups=authorized_groups,
            )
        )

    assert captured.value.code == expected
    assert calls == 0
    assert counters.tool_call_count == 0


def test_executor_records_only_safe_metadata_and_a_canonical_input_hash(
    caplog: pytest.LogCaptureFixture,
) -> None:
    secret_body = "private-user-body"
    observations: list[ToolObservation] = []
    definition = _definition()

    def echo(payload: BaseModel) -> BaseModel:
        return _Output(value=str(payload.model_dump()["value"]))

    executor = _executor(
        definition,
        echo,
        observations=observations,
    )
    counters = RuntimeHarness().new_usage_counters(_request())

    with caplog.at_level(logging.INFO, logger="app.tools.registry"):
        result = asyncio.run(
            executor.execute(
                run_id=_RUN_ID,
                name=definition.name,
                version="1",
                payload={"value": secret_body},
                counters=counters,
                authorized_tool_groups=(definition.tool_group,),
            )
        )

    canonical = json.dumps(
        {"value": secret_body},
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode()
    assert result.observation.status is ToolInvocationStatus.SUCCEEDED
    assert result.observation.input_hash == hashlib.sha256(canonical).hexdigest()
    assert result.observation.tool_call_id == result.invocation.tool_call_id
    assert result.observation.attempt == 1
    assert result.observation.stable_error_code is None
    assert result.observation.output_ref is not None
    assert result.observation.output_ref.startswith("sha256:")
    assert counters.tool_call_count == 1
    assert observations == [result.observation]
    assert secret_body not in caplog.text
    record = next(
        record for record in caplog.records if record.message == "tool_invocation_observed"
    )
    formatted = json.loads(JsonFormatter().format(record))
    assert formatted["agentRunId"] == str(_RUN_ID)
    assert formatted["toolCallId"] == str(result.observation.tool_call_id)
    assert formatted["toolName"] == definition.name
    assert formatted["toolVersion"] == definition.version
    assert formatted["riskLevel"] == "PREPARE"
    assert formatted["inputHash"] == result.observation.input_hash
    assert formatted["status"] == "SUCCEEDED"
    assert formatted["attempt"] == 1
    assert secret_body not in json.dumps(formatted)


def test_tool_retry_checks_budget_before_each_real_handler_attempt() -> None:
    attempts = 0

    class RetryableFailure(RuntimeError):
        code = "SAFE_TOOL_FAILURE"

    def handler(_payload: BaseModel) -> BaseModel:
        nonlocal attempts
        attempts += 1
        raise RetryableFailure("private upstream body")

    definition = _definition(retry=ToolRetryPolicy(max_attempts=2, backoff_ms=0))
    executor = _executor(definition, handler)
    counters = RuntimeHarness().new_usage_counters(_request(max_tool_calls=1))

    with pytest.raises(HarnessViolation) as captured:
        asyncio.run(
            executor.execute(
                run_id=_RUN_ID,
                name=definition.name,
                version="1",
                payload={"value": "safe"},
                counters=counters,
                authorized_tool_groups=(definition.tool_group,),
            )
        )

    assert captured.value.code == "TOOL_CALL_BUDGET_EXCEEDED"
    assert attempts == 1
    assert counters.tool_call_count == 1


def test_timeout_and_invalid_output_expose_stable_codes() -> None:
    def slow(_payload: BaseModel) -> BaseModel:
        time.sleep(0.05)
        return _Output(value="late")

    timeout_definition = _definition(timeout_seconds=0.001)
    invalid_definition = _definition()

    def invalid_output(_payload: BaseModel) -> BaseModel:
        return _Input(value="wrong")

    async def scenario() -> None:
        for definition, handler, expected in (
            (timeout_definition, slow, "TOOL_TIMEOUT"),
            (invalid_definition, invalid_output, "TOOL_OUTPUT_INVALID"),
        ):
            counters = RuntimeHarness().new_usage_counters(_request())
            with pytest.raises(ToolExecutionError) as captured:
                await _executor(definition, handler).execute(
                    run_id=_RUN_ID,
                    name=definition.name,
                    version="1",
                    payload={"value": "safe"},
                    counters=counters,
                    authorized_tool_groups=(definition.tool_group,),
                )
            assert captured.value.code == expected
            assert captured.value.observations[-1].stable_error_code == expected
            assert captured.value.observations[-1].status is ToolInvocationStatus.FAILED

    asyncio.run(scenario())


def test_observer_failure_cannot_turn_a_successful_tool_into_a_run_failure() -> None:
    definition = _definition()

    def observer(_observation: ToolObservation) -> None:
        raise RuntimeError("private audit sink failure")

    executor = ToolExecutor(
        ToolRegistry((definition,)),
        ToolPolicy(
            allowed_tool_names=frozenset((definition.name,)),
            allowed_tool_groups=frozenset((definition.tool_group,)),
        ),
        {definition.name: lambda payload: payload},
        {definition.name: lambda payload: _Output(value=payload.model_dump()["value"])},
        observer=observer,
    )
    counters = RuntimeHarness().new_usage_counters(_request())

    result = asyncio.run(
        executor.execute(
            run_id=_RUN_ID,
            name=definition.name,
            version="1",
            payload={"value": "safe"},
            counters=counters,
            authorized_tool_groups=(definition.tool_group,),
        )
    )

    assert result.observation.status is ToolInvocationStatus.SUCCEEDED
    assert counters.tool_call_count == 1


def test_handler_cannot_expose_an_unregistered_error_code() -> None:
    class UnsafeFailure(RuntimeError):
        code = "PRIVATE_PROVIDER_RESPONSE_BODY"

    def handler(_payload: BaseModel) -> BaseModel:
        raise UnsafeFailure("credential and upstream response")

    definition = _definition()
    counters = RuntimeHarness().new_usage_counters(_request())
    with pytest.raises(ToolExecutionError) as captured:
        asyncio.run(
            _executor(definition, handler).execute(
                run_id=_RUN_ID,
                name=definition.name,
                version="1",
                payload={"value": "safe"},
                counters=counters,
                authorized_tool_groups=(definition.tool_group,),
            )
        )

    assert captured.value.code == "TOOL_EXECUTION_FAILED"
    assert captured.value.observations[-1].stable_error_code == "TOOL_EXECUTION_FAILED"
