"""Bounded read-only planner over the existing Registry, Policy, and Harness."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from enum import StrEnum
from typing import Annotated, Literal, cast

from pydantic import BaseModel, ConfigDict, Field, JsonValue, ValidationError, model_validator
from pydantic.alias_generators import to_camel

from app.agent.models import ChatRunRequest, RunProgressStage, RunProgressStatus
from app.agent.progress import RunProgressHandler, notify_progress
from app.harness.runtime import RuntimeHarness, RuntimeUsageCounters
from app.model_gateway.gateway import (
    DeltaHandler,
    GatewayUsage,
    IntentRoute,
    ModelGateway,
    ModelGatewayError,
    ModelMessage,
    ModelMessages,
    RouterDecision,
    StructuredCompletionGateway,
)
from app.observability.tracing import start_span
from app.prompts.assembly import CommercePromptSet
from app.providers.java_context import (
    AuthorizedRunContext,
    geo_insight_synthesis_context,
    model_messages_with_context,
)
from app.tools.readonly import ReadOnlyToolBindings, identity_input
from app.tools.registry import (
    DEFAULT_TOOL_REGISTRY,
    RiskLevel,
    ToolExecutionError,
    ToolExecutor,
    ToolGroup,
    ToolObservation,
    ToolObserver,
    ToolPolicy,
    ToolRegistry,
)

READ_ONLY_TOOL_NAMES = frozenset(
    (
        "context.catalog.inspect",
        "context.memory.search",
        "context.task_workspace.inspect",
        "analysis.competitor.compare",
        "analysis.evidence.aggregate",
    )
)
_DANGEROUS_ARGUMENT_PATTERN = re.compile(
    r"(?:https?://|javascript:|<script|```|\b(?:select|insert|update|delete|drop|exec)\b)",
    re.IGNORECASE,
)


class PlannerModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
        allow_inf_nan=False,
    )


class PlanStepStatus(StrEnum):
    PENDING = "PENDING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    SKIPPED = "SKIPPED"


class ObservationDecision(StrEnum):
    CONTINUE = "CONTINUE"
    REPLAN = "REPLAN"
    ANSWER = "ANSWER"
    FAIL = "FAIL"


class PlanStep(PlannerModel):
    step_id: str = Field(pattern=r"^step-[1-5]$")
    ordinal: int = Field(ge=1, le=5)
    goal: str = Field(min_length=1, max_length=160)
    tool_name: str = Field(min_length=1, max_length=128, pattern=r"^[a-z][a-z0-9_.-]*$")
    tool_version: Literal["1"]
    arguments: dict[str, JsonValue] = Field(max_length=12)
    depends_on: tuple[str, ...] = Field(max_length=4)
    success_criteria: str = Field(min_length=1, max_length=200)
    status: PlanStepStatus


class ReadOnlyPlan(PlannerModel):
    schema_version: Literal["readonly-plan-v1"]
    goal_summary: str = Field(min_length=1, max_length=300)
    steps: tuple[PlanStep, ...] = Field(min_length=1, max_length=5)

    @model_validator(mode="after")
    def validate_unique_steps(self) -> ReadOnlyPlan:
        ids = tuple(step.step_id for step in self.steps)
        ordinals = tuple(step.ordinal for step in self.steps)
        if len(ids) != len(set(ids)):
            raise ValueError("plan step IDs must be unique")
        if ordinals != tuple(range(1, len(self.steps) + 1)):
            raise ValueError("plan ordinals must be consecutive and ordered")
        return self


class StepObservation(PlannerModel):
    step_id: str = Field(pattern=r"^step-[1-5]$")
    ordinal: int = Field(ge=1, le=5)
    tool_name: str
    tool_version: str
    risk_level: Literal["QUERY", "ANALYZE"]
    status: PlanStepStatus
    stable_error_code: str | None = None
    output_ref: str | None = None
    output: dict[str, JsonValue] | None = None


SynthesisLine = Annotated[str, Field(min_length=1, max_length=300)]


class SynthesisPayload(PlannerModel):
    conclusion: str = Field(min_length=1, max_length=800)
    evidence: tuple[SynthesisLine, ...] = Field(min_length=1, max_length=5)
    gaps_and_risks: tuple[SynthesisLine, ...] = Field(default=(), max_length=5)
    next_step: str = Field(min_length=1, max_length=300)

    def render(self) -> str:
        evidence = "\n".join(f"- {item}" for item in self.evidence)
        gaps = (
            "\n".join(f"- {item}" for item in self.gaps_and_risks)
            if self.gaps_and_risks
            else "- 当前授权资料未显示额外缺口。"
        )
        return (
            f"结论: {self.conclusion}\n\n"
            f"依据:\n{evidence}\n\n"
            f"信息缺口与风险:\n{gaps}\n\n"
            f"下一步: {self.next_step}"
        )


@dataclass(frozen=True, slots=True)
class MultistepResult:
    text: str
    usage: GatewayUsage
    estimated_input_tokens: int
    observations: tuple[StepObservation, ...]
    replan_count: int
    fallback_code: str | None = None


async def _complete_static_response_progress(
    on_progress: RunProgressHandler | None,
) -> None:
    await notify_progress(
        on_progress,
        RunProgressStage.COMPOSING_RESPONSE,
        RunProgressStatus.RUNNING,
    )
    await notify_progress(
        on_progress,
        RunProgressStage.COMPOSING_RESPONSE,
        RunProgressStatus.SUCCEEDED,
    )


class PlanValidationError(ValueError):
    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


class PlanValidator:
    """Validate model control data before any tool counter or handler can run."""

    def __init__(self, registry: ToolRegistry, policy: ToolPolicy) -> None:
        self._registry = registry
        self._policy = policy

    def validate(
        self,
        plan: ReadOnlyPlan,
        *,
        request: ChatRunRequest,
        counters: RuntimeUsageCounters,
        authorized_tool_groups: tuple[str, ...],
    ) -> None:
        with start_span(
            "agent.plan.validate",
            attributes={
                "agent.plan.version": plan.schema_version,
                "agent.plan.stepCount": len(plan.steps),
            },
        ) as span:
            try:
                self._validate(
                    plan,
                    request=request,
                    counters=counters,
                    authorized_tool_groups=authorized_tool_groups,
                )
            except PlanValidationError as exc:
                span.fail(exc.code)
                raise

    def _validate(
        self,
        plan: ReadOnlyPlan,
        *,
        request: ChatRunRequest,
        counters: RuntimeUsageCounters,
        authorized_tool_groups: tuple[str, ...],
    ) -> None:
        if _contains_dangerous_text(plan.goal_summary):
            raise PlanValidationError("PLAN_DYNAMIC_INSTRUCTION_FORBIDDEN")
        remaining_tool_calls = request.budget.max_tool_calls - counters.tool_call_count
        if len(plan.steps) > remaining_tool_calls:
            raise PlanValidationError("PLAN_TOOL_BUDGET_EXCEEDED")
        remaining_steps = request.budget.max_steps - counters.workflow_step_count
        if len(plan.steps) + 1 > remaining_steps:
            raise PlanValidationError("PLAN_STEP_BUDGET_EXCEEDED")

        ids = {step.step_id for step in plan.steps}
        ordinal_by_id = {step.step_id: step.ordinal for step in plan.steps}
        try:
            parsed_groups = frozenset(ToolGroup(value) for value in authorized_tool_groups)
        except ValueError:
            raise PlanValidationError("PLAN_TOOL_GROUP_UNKNOWN") from None
        for step in plan.steps:
            if step.status is not PlanStepStatus.PENDING:
                raise PlanValidationError("PLAN_STATUS_INVALID")
            if step.step_id != f"step-{step.ordinal}":
                raise PlanValidationError("PLAN_STEP_ORDER_INVALID")
            if _contains_dangerous_text(step.goal) or _contains_dangerous_text(
                step.success_criteria
            ):
                raise PlanValidationError("PLAN_DYNAMIC_INSTRUCTION_FORBIDDEN")
            if len(step.depends_on) != len(set(step.depends_on)):
                raise PlanValidationError("PLAN_DEPENDENCY_INVALID")
            if set(step.depends_on) - ids or step.step_id in step.depends_on:
                raise PlanValidationError("PLAN_DEPENDENCY_INVALID")
            if any(ordinal_by_id[item] >= step.ordinal for item in step.depends_on):
                raise PlanValidationError("PLAN_DEPENDENCY_CYCLE")
            try:
                definition = self._registry.resolve(step.tool_name, step.tool_version)
            except Exception:
                raise PlanValidationError("PLAN_TOOL_UNKNOWN") from None
            if definition.name not in READ_ONLY_TOOL_NAMES or definition.risk_level not in {
                RiskLevel.QUERY,
                RiskLevel.ANALYZE,
            }:
                raise PlanValidationError("PLAN_TOOL_RISK_FORBIDDEN")
            try:
                self._policy.authorize(definition, parsed_groups)
            except ToolExecutionError as exc:
                raise PlanValidationError(exc.code) from None
            if _contains_forbidden_argument(step.arguments):
                raise PlanValidationError("PLAN_ARGUMENT_FORBIDDEN")
            try:
                validated_arguments = definition.input_model.model_validate(step.arguments)
            except ValidationError:
                raise PlanValidationError("PLAN_ARGUMENT_INVALID") from None
            if step.tool_name == "analysis.evidence.aggregate":
                referenced = set(
                    cast(tuple[str, ...], getattr(validated_arguments, "step_ids", ()))
                )
                if not referenced or not referenced <= set(step.depends_on):
                    raise PlanValidationError("PLAN_EVIDENCE_DEPENDENCY_INVALID")


class StepSelector:
    @staticmethod
    def select(plan: ReadOnlyPlan, observations: tuple[StepObservation, ...]) -> PlanStep | None:
        completed = {
            item.step_id
            for item in observations
            if item.status
            in {PlanStepStatus.SUCCEEDED, PlanStepStatus.FAILED, PlanStepStatus.SKIPPED}
        }
        with start_span(
            "agent.step.select",
            attributes={"agent.plan.completedSteps": len(completed)},
        ) as span:
            for step in plan.steps:
                if step.step_id in completed:
                    continue
                if all(dependency in completed for dependency in step.depends_on):
                    span.set("agent.step.number", step.ordinal)
                    return step
            if len(completed) != len(plan.steps):
                span.fail("PLAN_DEPENDENCY_BLOCKED")
                raise PlanValidationError("PLAN_DEPENDENCY_BLOCKED")
            return None


class ObservationEvaluator:
    @staticmethod
    def evaluate(
        plan: ReadOnlyPlan,
        observations: tuple[StepObservation, ...],
        *,
        replan_count: int,
    ) -> ObservationDecision:
        latest = observations[-1]
        with start_span(
            "agent.observation.evaluate",
            attributes={
                "agent.step.number": latest.ordinal,
                "agent.step.status": latest.status.value,
                "agent.replan.count": replan_count,
            },
        ):
            if latest.status is PlanStepStatus.FAILED:
                succeeded = any(item.status is PlanStepStatus.SUCCEEDED for item in observations)
                return (
                    ObservationDecision.REPLAN
                    if replan_count == 0
                    else ObservationDecision.ANSWER
                    if succeeded
                    else ObservationDecision.FAIL
                )
            return (
                ObservationDecision.ANSWER
                if len(observations) == len(plan.steps)
                else ObservationDecision.CONTINUE
            )


class ReadOnlyMultistepRuntime:
    """Execute one 1-5 step analysis plan with no Provider or business side effects."""

    def __init__(
        self,
        *,
        gateway: ModelGateway,
        prompts: CommercePromptSet,
        harness: RuntimeHarness,
        tool_names: tuple[str, ...],
        tool_observer: ToolObserver | None = None,
    ) -> None:
        self._gateway = gateway
        self._prompts = prompts
        self._harness = harness
        self._tool_names = frozenset(tool_names) & READ_ONLY_TOOL_NAMES
        self._observer = tool_observer
        self._policy = ToolPolicy(
            allowed_tool_names=self._tool_names,
            allowed_tool_groups=frozenset((ToolGroup.TASK_PREPARATION,)),
            allowed_risk_levels=frozenset((RiskLevel.QUERY, RiskLevel.ANALYZE)),
        )
        self._validator = PlanValidator(DEFAULT_TOOL_REGISTRY, self._policy)

    @property
    def available(self) -> bool:
        return bool(self._tool_names) and isinstance(self._gateway, StructuredCompletionGateway)

    async def execute(
        self,
        *,
        request: ChatRunRequest,
        context: AuthorizedRunContext,
        route: IntentRoute,
        counters: RuntimeUsageCounters,
        on_delta: DeltaHandler | None,
        on_progress: RunProgressHandler | None = None,
    ) -> MultistepResult:
        if not self.available:
            await _complete_static_response_progress(on_progress)
            return MultistepResult(
                text="当前只读分析工具暂不可用，我先按现有授权信息给出普通回答。",
                usage=GatewayUsage(),
                estimated_input_tokens=counters.estimated_input_tokens,
                observations=(),
                replan_count=0,
                fallback_code="READONLY_PLANNER_UNAVAILABLE",
            )
        executor, bindings = self._executor(context)
        tool_catalog = executor.model_tool_catalog(context.loaded_tool_groups)
        await notify_progress(
            on_progress,
            RunProgressStage.PLANNING_WORKFLOW,
            RunProgressStatus.RUNNING,
        )
        try:
            plan, plan_usage, estimated_tokens = await self._create_plan(
                request=request,
                context=context,
                route=route,
                tool_catalog=tool_catalog,
                counters=counters,
            )
        except Exception:
            await notify_progress(
                on_progress,
                RunProgressStage.PLANNING_WORKFLOW,
                RunProgressStatus.FAILED,
            )
            raise
        if plan is None:
            await notify_progress(
                on_progress,
                RunProgressStage.PLANNING_WORKFLOW,
                RunProgressStatus.FAILED,
            )
            await _complete_static_response_progress(on_progress)
            return MultistepResult(
                text="这项分析暂时无法形成可靠的受控计划，请缩小到一个明确的经营问题后再试。",
                usage=plan_usage,
                estimated_input_tokens=estimated_tokens,
                observations=(),
                replan_count=0,
                fallback_code="READONLY_PLAN_INVALID",
            )
        await notify_progress(
            on_progress,
            RunProgressStage.PLANNING_WORKFLOW,
            RunProgressStatus.SUCCEEDED,
        )
        observations: tuple[StepObservation, ...] = ()
        replan_count = 0
        await notify_progress(
            on_progress,
            RunProgressStage.RUNNING_SAFE_TOOL,
            RunProgressStatus.RUNNING,
            current=0,
            total=len(plan.steps),
        )
        while True:
            step = StepSelector.select(plan, observations)
            if step is None:
                break
            counters.before_workflow_step()
            try:
                execution = await executor.execute(
                    run_id=request.run_id,
                    name=step.tool_name,
                    version=step.tool_version,
                    payload=step.arguments,
                    counters=counters,
                    authorized_tool_groups=context.loaded_tool_groups,
                    step_number=step.ordinal,
                )
            except ToolExecutionError as exc:
                observation = _failed_observation(step, exc)
            else:
                bindings.remember(step.step_id, execution.output)
                observation = StepObservation(
                    step_id=step.step_id,
                    ordinal=step.ordinal,
                    tool_name=step.tool_name,
                    tool_version=step.tool_version,
                    risk_level=cast(
                        Literal["QUERY", "ANALYZE"], execution.invocation.risk_level.value
                    ),
                    status=PlanStepStatus.SUCCEEDED,
                    output_ref=execution.observation.output_ref,
                    output=cast(
                        dict[str, JsonValue],
                        execution.output.model_dump(mode="json", by_alias=True),
                    ),
                )
            observations = (*observations, observation)
            decision = ObservationEvaluator.evaluate(
                plan,
                observations,
                replan_count=replan_count,
            )
            if decision is ObservationDecision.REPLAN:
                if counters.model_call_count + 2 > request.budget.max_model_calls:
                    decision = ObservationDecision.ANSWER
                else:
                    replanned, usage, candidate_tokens = await self._replan(
                        request=request,
                        context=context,
                        route=route,
                        tool_catalog=tool_catalog,
                        original=plan,
                        observations=observations,
                        counters=counters,
                    )
                    plan_usage += usage
                    estimated_tokens = max(estimated_tokens, candidate_tokens)
                    replan_count += 1
                    if replanned is not None:
                        plan = replanned
                        continue
                    decision = ObservationDecision.ANSWER
            if decision is ObservationDecision.CONTINUE:
                continue
            if decision is ObservationDecision.FAIL:
                await notify_progress(
                    on_progress,
                    RunProgressStage.RUNNING_SAFE_TOOL,
                    RunProgressStatus.FAILED,
                    current=sum(item.status is PlanStepStatus.SUCCEEDED for item in observations),
                    total=len(plan.steps),
                )
                await _complete_static_response_progress(on_progress)
                return MultistepResult(
                    text="当前授权资料读取失败，无法形成有依据的结论；请稍后重试。",
                    usage=plan_usage,
                    estimated_input_tokens=estimated_tokens,
                    observations=observations,
                    replan_count=replan_count,
                    fallback_code="READONLY_EVIDENCE_UNAVAILABLE",
                )
            break
        await notify_progress(
            on_progress,
            RunProgressStage.RUNNING_SAFE_TOOL,
            RunProgressStatus.FAILED
            if any(item.status is PlanStepStatus.FAILED for item in observations)
            else RunProgressStatus.SUCCEEDED,
            current=len(observations),
            total=len(plan.steps),
        )
        await notify_progress(
            on_progress,
            RunProgressStage.COMPOSING_RESPONSE,
            RunProgressStatus.RUNNING,
        )
        synthesis, synthesis_usage, synthesis_tokens, synthesis_fallback = await self._synthesize(
            request=request,
            context=context,
            route=route,
            observations=observations,
            replan_count=replan_count,
            counters=counters,
            on_delta=on_delta,
        )
        await notify_progress(
            on_progress,
            RunProgressStage.COMPOSING_RESPONSE,
            RunProgressStatus.FAILED if synthesis_fallback else RunProgressStatus.SUCCEEDED,
        )
        return MultistepResult(
            text=synthesis,
            usage=plan_usage + synthesis_usage,
            estimated_input_tokens=max(estimated_tokens, synthesis_tokens),
            observations=observations,
            replan_count=replan_count,
            fallback_code=synthesis_fallback,
        )

    def _executor(self, context: AuthorizedRunContext) -> tuple[ToolExecutor, ReadOnlyToolBindings]:
        bindings = ReadOnlyToolBindings(context)
        handlers = {
            name: handler for name, handler in bindings.handlers.items() if name in self._tool_names
        }
        preparers = {name: identity_input for name in self._tool_names}
        return (
            ToolExecutor(
                DEFAULT_TOOL_REGISTRY,
                self._policy,
                preparers,
                handlers,
                observer=self._observer,
            ),
            bindings,
        )

    async def _create_plan(
        self,
        *,
        request: ChatRunRequest,
        context: AuthorizedRunContext,
        route: IntentRoute,
        tool_catalog: str,
        counters: RuntimeUsageCounters,
    ) -> tuple[ReadOnlyPlan | None, GatewayUsage, int]:
        prompt = self._prompts.for_readonly_plan(
            tool_catalog=tool_catalog,
            plan_schema=_schema_json(ReadOnlyPlan),
        )
        messages, estimated = self._fit(request, context, prompt, counters)
        with start_span(
            "agent.plan.create",
            attributes={
                "agent.router.decision": route.decision.value,
                "agent.router.complexity": route.complexity.value,
                "agent.plan.version": "readonly-plan-v1",
            },
        ) as span:
            try:
                parsed, usage = await cast(
                    StructuredCompletionGateway, self._gateway
                ).complete_structured(
                    messages,
                    ReadOnlyPlan,
                    operation="readonly_plan",
                    max_tokens=1_200,
                    error_code="DEEPSEEK_READONLY_PLAN_INVALID",
                    repair_instruction=(
                        "Return only a readonly-plan-v1 JSON object with 3 to 5 ordered steps. "
                        "Use only the supplied tool names, version 1, strict arguments, "
                        "and earlier dependencies."
                    ),
                    repair_attempts=1,
                )
            except ModelGatewayError as exc:
                if not exc.structured_response_failure:
                    span.fail(exc.code)
                    raise
                counters.record_gateway_usage(exc.usage)
                span.fail(exc.code)
                return None, exc.usage, estimated
            try:
                plan = ReadOnlyPlan.model_validate(parsed)
                self._validator.validate(
                    plan,
                    request=request,
                    counters=counters,
                    authorized_tool_groups=context.loaded_tool_groups,
                )
            except (ValidationError, PlanValidationError) as exc:
                if counters.model_call_count + 2 > request.budget.max_model_calls:
                    span.fail(getattr(exc, "code", "READONLY_PLAN_INVALID"))
                    counters.record_gateway_usage(usage)
                    return None, usage, estimated
                repaired, repair_usage, repair_estimated = await self._repair_invalid_plan(
                    request=request,
                    context=context,
                    tool_catalog=tool_catalog,
                    candidate=parsed,
                    counters=counters,
                )
                usage += repair_usage
                estimated = max(estimated, repair_estimated)
                if repaired is None:
                    span.fail(getattr(exc, "code", "READONLY_PLAN_INVALID"))
                    counters.record_gateway_usage(usage)
                    return None, usage, estimated
                plan = repaired
            counters.record_gateway_usage(usage)
            span.set("agent.plan.stepCount", len(plan.steps))
            return plan, usage, estimated

    async def _repair_invalid_plan(
        self,
        *,
        request: ChatRunRequest,
        context: AuthorizedRunContext,
        tool_catalog: str,
        candidate: BaseModel,
        counters: RuntimeUsageCounters,
    ) -> tuple[ReadOnlyPlan | None, GatewayUsage, int]:
        prompt = self._prompts.for_readonly_replan(
            tool_catalog=tool_catalog,
            plan_schema=_schema_json(ReadOnlyPlan),
            original_plan=candidate.model_dump_json(by_alias=True),
            observation_audit="[]",
        )
        messages, estimated = self._fit(request, context, prompt, counters)
        usage = GatewayUsage()
        try:
            parsed, usage = await cast(
                StructuredCompletionGateway, self._gateway
            ).complete_structured(
                messages,
                ReadOnlyPlan,
                operation="readonly_plan_repair",
                max_tokens=1_000,
                error_code="DEEPSEEK_READONLY_PLAN_REPAIR_INVALID",
                repair_instruction="Return one corrected readonly-plan-v1 JSON object only.",
                repair_attempts=0,
            )
            plan = ReadOnlyPlan.model_validate(parsed)
            self._validator.validate(
                plan,
                request=request,
                counters=counters,
                authorized_tool_groups=context.loaded_tool_groups,
            )
        except ModelGatewayError as exc:
            if not exc.structured_response_failure:
                raise
            return None, exc.usage, estimated
        except (ValidationError, PlanValidationError):
            return None, usage, estimated
        return plan, usage, estimated

    async def _replan(
        self,
        *,
        request: ChatRunRequest,
        context: AuthorizedRunContext,
        route: IntentRoute,
        tool_catalog: str,
        original: ReadOnlyPlan,
        observations: tuple[StepObservation, ...],
        counters: RuntimeUsageCounters,
    ) -> tuple[ReadOnlyPlan | None, GatewayUsage, int]:
        audit = _observation_json(observations)
        prompt = self._prompts.for_readonly_replan(
            tool_catalog=tool_catalog,
            plan_schema=_schema_json(ReadOnlyPlan),
            original_plan=original.model_dump_json(by_alias=True),
            observation_audit=audit,
        )
        messages, estimated = self._fit(request, context, prompt, counters)
        usage = GatewayUsage()
        with start_span(
            "agent.replan",
            attributes={
                "agent.plan.version": "readonly-plan-v1",
                "agent.replan.count": 1,
            },
        ) as span:
            try:
                parsed, usage = await cast(
                    StructuredCompletionGateway, self._gateway
                ).complete_structured(
                    messages,
                    ReadOnlyPlan,
                    operation="readonly_replan",
                    max_tokens=1_000,
                    error_code="DEEPSEEK_READONLY_REPLAN_INVALID",
                    repair_instruction="Return one corrected readonly-plan-v1 JSON object only.",
                    repair_attempts=0,
                )
                plan = ReadOnlyPlan.model_validate(parsed)
                self._validator.validate(
                    plan,
                    request=request,
                    counters=counters,
                    authorized_tool_groups=context.loaded_tool_groups,
                )
                _validate_completed_steps_unchanged(original, plan, observations)
            except ModelGatewayError as exc:
                if not exc.structured_response_failure:
                    span.fail(exc.code)
                    raise
                counters.record_gateway_usage(exc.usage)
                span.fail(exc.code)
                return None, exc.usage, estimated
            except (ValidationError, PlanValidationError) as exc:
                span.fail(getattr(exc, "code", "READONLY_REPLAN_INVALID"))
                counters.record_gateway_usage(usage)
                return None, usage, estimated
            counters.record_gateway_usage(usage)
            return plan, usage, estimated

    async def _synthesize(
        self,
        *,
        request: ChatRunRequest,
        context: AuthorizedRunContext,
        route: IntentRoute,
        observations: tuple[StepObservation, ...],
        replan_count: int,
        counters: RuntimeUsageCounters,
        on_delta: DeltaHandler | None,
    ) -> tuple[str, GatewayUsage, int, str | None]:
        prompt = self._prompts.for_readonly_synthesis(
            observation_data=_observation_json(observations),
            synthesis_schema=_schema_json(SynthesisPayload),
        )
        prompt = f"{prompt}\n\n{geo_insight_synthesis_context(context)}"
        messages: ModelMessages = (
            ModelMessage(role="system", content=prompt),
            ModelMessage(role="user", content=request.message.content),
        )
        fitted, estimated = self._harness.fit_model_messages(
            request,
            messages,
            target_input_tokens=request.budget.max_input_tokens,
            available_history_tokens=1,
        )
        counters.observe_estimated_input_tokens(estimated)
        fallback_code: str | None = None
        with start_span(
            "agent.synthesis",
            attributes={
                "agent.router.decision": route.decision.value,
                "agent.plan.version": "readonly-plan-v1",
                "agent.plan.stepCount": len(observations),
                "agent.replan.count": replan_count,
                "agent.observation.count": len(observations),
            },
        ) as span:
            try:
                parsed, usage = await cast(
                    StructuredCompletionGateway, self._gateway
                ).complete_structured(
                    fitted,
                    SynthesisPayload,
                    operation="readonly_synthesis",
                    max_tokens=1_200,
                    error_code="DEEPSEEK_READONLY_SYNTHESIS_INVALID",
                    repair_instruction="Return exactly one object matching the synthesis schema.",
                    repair_attempts=0,
                )
                synthesis = SynthesisPayload.model_validate(parsed).render()
            except ModelGatewayError as exc:
                span.fail(exc.code)
                fallback_code = "READONLY_SYNTHESIS_UNAVAILABLE"
                synthesis = _deterministic_safe_synthesis(observations)
                usage = exc.usage
            counters.record_gateway_usage(usage)
        if on_delta is not None:
            await on_delta(synthesis)
        return synthesis, usage, estimated, fallback_code

    def _fit(
        self,
        request: ChatRunRequest,
        context: AuthorizedRunContext,
        prompt: str,
        counters: RuntimeUsageCounters,
    ) -> tuple[ModelMessages, int]:
        messages = model_messages_with_context(prompt, context, request)
        budget = context.context_budget
        fitted, estimated = self._harness.fit_model_messages(
            request,
            messages,
            target_input_tokens=(
                request.budget.max_input_tokens if budget is None else budget.target_input_tokens
            ),
            available_history_tokens=(
                request.budget.max_input_tokens
                if budget is None
                else budget.available_history_tokens
            ),
        )
        counters.observe_estimated_input_tokens(estimated)
        return fitted, estimated


def should_use_readonly_planner(route: IntentRoute) -> bool:
    if route.fallback_required or route.requires_clarification:
        return False
    return (
        route.decision in {RouterDecision.ANALYZE, RouterDecision.PLAN}
        and route.complexity.value == "COMPLEX"
    )


def _failed_observation(step: PlanStep, error: ToolExecutionError) -> StepObservation:
    latest: ToolObservation | None = error.observations[-1] if error.observations else None
    return StepObservation(
        step_id=step.step_id,
        ordinal=step.ordinal,
        tool_name=step.tool_name,
        tool_version=step.tool_version,
        risk_level=cast(
            Literal["QUERY", "ANALYZE"],
            DEFAULT_TOOL_REGISTRY.resolve(step.tool_name, step.tool_version).risk_level.value,
        ),
        status=PlanStepStatus.FAILED,
        stable_error_code=error.code,
        output_ref=None if latest is None else latest.output_ref,
    )


def _validate_completed_steps_unchanged(
    original: ReadOnlyPlan,
    candidate: ReadOnlyPlan,
    observations: tuple[StepObservation, ...],
) -> None:
    completed_ids = {item.step_id for item in observations}
    original_by_id = {step.step_id: step for step in original.steps}
    candidate_by_id = {step.step_id: step for step in candidate.steps}
    for step_id in completed_ids:
        if original_by_id.get(step_id) != candidate_by_id.get(step_id):
            raise PlanValidationError("REPLAN_COMPLETED_STEP_CHANGED")


def _contains_dangerous_text(value: str) -> bool:
    return bool(_DANGEROUS_ARGUMENT_PATTERN.search(value))


def _contains_forbidden_argument(value: object, *, key: str = "") -> bool:
    normalized_key = key.casefold()
    if normalized_key not in {"stepids", "dependson"} and (
        normalized_key.endswith("id") or normalized_key.endswith("ids")
    ):
        return True
    if isinstance(value, str):
        return _contains_dangerous_text(value)
    if isinstance(value, dict):
        mapping = cast(dict[object, object], value)
        return any(
            _contains_forbidden_argument(item, key=str(item_key))
            for item_key, item in mapping.items()
        )
    if isinstance(value, list):
        items = cast(list[object], value)
        return any(_contains_forbidden_argument(item, key=key) for item in items)
    return False


def _schema_json(model: type[BaseModel]) -> str:
    return json.dumps(
        model.model_json_schema(by_alias=True),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def _observation_json(observations: tuple[StepObservation, ...]) -> str:
    return (
        json.dumps(
            [item.model_dump(mode="json", by_alias=True) for item in observations],
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("&", "\\u0026")
    )


def _deterministic_safe_synthesis(observations: tuple[StepObservation, ...]) -> str:
    succeeded = sum(item.status is PlanStepStatus.SUCCEEDED for item in observations)
    failed = sum(item.status is PlanStepStatus.FAILED for item in observations)
    return (
        "结论: 已完成可验证的只读资料整理，但模型综合暂不可用。\n\n"
        f"依据:\n- 已成功读取 {succeeded} 项授权观察。\n\n"
        f"信息缺口与风险:\n- 有 {failed} 项观察失败；未提供的资料不会被推测补齐。\n\n"
        "下一步: 请缩小到一个具体问题，或稍后重新进行综合分析。"
    )


__all__ = [
    "READ_ONLY_TOOL_NAMES",
    "MultistepResult",
    "ObservationDecision",
    "ObservationEvaluator",
    "PlanStep",
    "PlanStepStatus",
    "PlanValidationError",
    "PlanValidator",
    "ReadOnlyMultistepRuntime",
    "ReadOnlyPlan",
    "StepObservation",
    "StepSelector",
    "SynthesisPayload",
    "should_use_readonly_planner",
]
