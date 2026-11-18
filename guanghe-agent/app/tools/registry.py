"""Closed, bounded tool registry, policy, execution, and safe observations."""

import asyncio
import hashlib
import json
import logging
from collections.abc import Awaitable, Callable, Iterable, Mapping
from dataclasses import dataclass
from enum import StrEnum
from time import monotonic
from uuid import UUID, uuid4

from pydantic import BaseModel, ConfigDict, Field, JsonValue, ValidationError, model_validator
from pydantic.alias_generators import to_camel

from app.agent.identity import require_preparation_capability, tool_available
from app.agent.models import DraftOperation, Intent, TaskDraftProposal
from app.agent.workflow_contract import WorkflowPlan, WorkflowPlanProposalRequest
from app.creative.models import CreativeSourceMode
from app.harness.runtime import HarnessViolation, RuntimeUsageCounters
from app.observability.metrics import record_tool_policy_denial
from app.observability.tracing import add_current_span_event, safe_ref, start_span
from app.prompts.models import AgentBundle
from app.providers.resources import (
    ResourceReadRequest,
    ResourceReadResponse,
    ResourceSearchRequest,
    ResourceSearchResponse,
)
from app.tools.readonly import (
    CatalogInspectInput,
    CatalogInspectOutput,
    CompetitorCompareInput,
    CompetitorCompareOutput,
    EvidenceAggregateInput,
    EvidenceAggregateOutput,
    MemorySearchInput,
    MemorySearchOutput,
    TaskWorkspaceInspectInput,
    TaskWorkspaceInspectOutput,
)

logger = logging.getLogger(__name__)

_SAFE_HANDLER_ERROR_CODES = frozenset(
    (
        "TASK_DRAFT_INTENT_UNSUPPORTED",
        "GEO_PARAMETERS_INVALID",
        "IMAGE_PARAMETERS_INVALID",
        "VIDEO_PARAMETERS_INVALID",
        "ARTICLE_PARAMETERS_INVALID",
        "WORKFLOW_PLAN_JAVA_UNAVAILABLE",
        "WORKFLOW_PLAN_JAVA_TIMEOUT",
        "WORKFLOW_PLAN_AUTHENTICATION_REJECTED",
        "WORKFLOW_PLAN_REJECTED",
        "WORKFLOW_PLAN_RESPONSE_INVALID",
        "WORKFLOW_PLAN_RESPONSE_MISMATCH",
        "WORKFLOW_PLAN_REQUEST_INVALID",
        "RESOURCE_LOOKUP_UNAVAILABLE",
        "RESOURCE_RESPONSE_INVALID",
        "RESOURCE_AUTHORIZATION_REJECTED",
    )
)


class ToolType(StrEnum):
    JAVA_PROXY = "JAVA_PROXY"
    MODEL_PROVIDER = "MODEL_PROVIDER"
    PURE_FUNCTION = "PURE_FUNCTION"


class RiskLevel(StrEnum):
    QUERY = "QUERY"
    ANALYZE = "ANALYZE"
    PREPARE = "PREPARE"
    COMMIT = "COMMIT"


class ExecutionMode(StrEnum):
    INLINE = "INLINE"
    ASYNC_TASK = "ASYNC_TASK"


class ConfirmationPolicy(StrEnum):
    NONE = "NONE"
    JAVA_REQUIRED = "JAVA_REQUIRED"


class ToolGroup(StrEnum):
    TASK_PREPARATION = "TASK_PREPARATION"


class ToolInvocationStatus(StrEnum):
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


class ToolRuntimeModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
        allow_inf_nan=False,
    )


class TaskDraftPrepareInput(ToolRuntimeModel):
    """Strict, proposal-only input produced from one Run and its authorized context."""

    intent: Intent
    request_text: str = Field(min_length=1, max_length=16_000)
    selected_space_id: str | None = Field(default=None, max_length=128)
    selected_product_id: str | None = Field(default=None, max_length=128)
    source_mode: CreativeSourceMode | None = None
    reference_asset_ids: tuple[str, ...] = Field(default=(), max_length=3)
    source_message_id: str | None = Field(default=None, max_length=128)
    draft_operation: DraftOperation = DraftOperation.NEW
    previous_draft_id: str | None = Field(default=None, min_length=1, max_length=128)
    candidate_summary: str = Field(min_length=1, max_length=500)
    candidate_parameters: dict[str, JsonValue] = Field(default_factory=dict, max_length=16)
    candidate_warnings: tuple[str, ...] = Field(default=(), max_length=16)
    geo_max_questions: int = Field(default=10, ge=3, le=10)
    # A deterministic, Java-authorized fallback for a generic GEO request.  The
    # model may improve the question wording, but it must not turn "monitor the
    # current product" into a topic that is detached from the selected product.
    geo_default_topic: str | None = Field(default=None, min_length=1, max_length=100)
    geo_default_category: str | None = Field(default=None, min_length=1, max_length=100)
    geo_excluded_names: tuple[str, ...] = ()
    geo_allowed_platform_codes: tuple[str, ...] = ()
    geo_max_provider_calls: int = Field(default=1000, ge=0, le=1000)
    # Program-selected source only, never accepted from model candidate parameters.
    geo_report_id: str | None = Field(default=None, pattern=r"^[1-9][0-9]{0,18}$")

    @model_validator(mode="after")
    def validate_revision_reference(self) -> "TaskDraftPrepareInput":
        if (self.draft_operation is DraftOperation.REVISE) != (self.previous_draft_id is not None):
            raise ValueError("previousDraftId must be supplied only for REVISE")
        return self


class ToolInvocation(ToolRuntimeModel):
    tool_call_id: UUID
    run_id: UUID
    tool_name: str
    tool_version: str
    risk_level: RiskLevel
    input_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    attempt: int = Field(ge=1)


class ToolObservation(ToolRuntimeModel):
    tool_call_id: UUID
    run_id: UUID
    tool_name: str
    tool_version: str
    risk_level: RiskLevel
    input_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    status: ToolInvocationStatus
    attempt: int = Field(ge=1)
    latency_ms: int = Field(ge=0)
    stable_error_code: str | None = None
    output_ref: str | None = None


@dataclass(frozen=True, slots=True)
class ToolRetryPolicy:
    max_attempts: int
    backoff_ms: int


@dataclass(frozen=True, slots=True)
class ToolCost:
    units: int


@dataclass(frozen=True, slots=True)
class ToolDefinition:
    name: str
    version: str
    description: str
    input_model: type[BaseModel]
    output_model: type[BaseModel]
    tool_group: ToolGroup
    tool_type: ToolType
    risk_level: RiskLevel
    execution_mode: ExecutionMode
    timeout_seconds: float
    retry: ToolRetryPolicy
    cost: ToolCost
    confirmation_policy: ConfirmationPolicy


ToolHandler = Callable[[BaseModel], BaseModel | Awaitable[BaseModel]]
ToolInputPreparer = Callable[[BaseModel], object]
ToolObserver = Callable[[ToolObservation], None]


@dataclass(frozen=True, slots=True)
class ToolExecutionResult:
    output: BaseModel
    invocation: ToolInvocation
    observation: ToolObservation


class ToolRegistryError(ValueError):
    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


class ToolExecutionError(RuntimeError):
    """Safe runtime failure carrying observations but never raw input or output."""

    def __init__(self, code: str, observations: tuple[ToolObservation, ...] = ()) -> None:
        self.code = code
        self.observations = observations
        super().__init__(code)


_JAVA_RUN_ACTIONS = frozenset(
    {
        "brand.save",
        "geo.monitor",
        "article.generate",
        "image.generate",
        "video.generate",
        "memory.upsert",
        "memory.forget",
    }
)


class ToolRegistry:
    """Immutable allowlist; only explicitly opted-in Java run actions may commit."""

    def __init__(
        self, definitions: tuple[ToolDefinition, ...], *, java_run_actions: bool = False
    ) -> None:
        names = tuple(definition.name for definition in definitions)
        if len(names) != len(set(names)):
            raise ToolRegistryError("TOOL_REGISTRY_DUPLICATE_NAME")
        if any(
            definition.risk_level is RiskLevel.COMMIT
            and not (
                java_run_actions
                and definition.name in _JAVA_RUN_ACTIONS
                and definition.tool_type is ToolType.JAVA_PROXY
                and definition.confirmation_policy is ConfirmationPolicy.JAVA_REQUIRED
            )
            for definition in definitions
        ):
            raise ToolRegistryError("TOOL_REGISTRY_COMMIT_FORBIDDEN")
        if any(not 1 <= definition.retry.max_attempts <= 4 for definition in definitions):
            raise ToolRegistryError("TOOL_REGISTRY_RETRY_INVALID")
        if any(not 0 <= definition.retry.backoff_ms <= 10_000 for definition in definitions):
            raise ToolRegistryError("TOOL_REGISTRY_RETRY_INVALID")
        if any(not 0 < definition.timeout_seconds <= 120 for definition in definitions):
            raise ToolRegistryError("TOOL_REGISTRY_TIMEOUT_INVALID")
        if any(definition.cost.units < 0 for definition in definitions):
            raise ToolRegistryError("TOOL_REGISTRY_COST_INVALID")
        self._definitions = {definition.name: definition for definition in definitions}

    @property
    def definitions(self) -> tuple[ToolDefinition, ...]:
        return tuple(self._definitions.values())

    def resolve(self, name: str, version: str) -> ToolDefinition:
        definition = self._definitions.get(name)
        if definition is None:
            raise ToolRegistryError("TOOL_NOT_REGISTERED")
        if definition.version != version:
            raise ToolRegistryError("TOOL_VERSION_NOT_REGISTERED")
        return definition

    def validate_bundle(self, bundle: AgentBundle) -> None:
        configured = {tool.name: tool for tool in bundle.tools}
        if configured.keys() != self._definitions.keys():
            raise ToolRegistryError("BUNDLE_TOOL_SET_MISMATCH")
        for name, definition in self._definitions.items():
            declared = configured[name]
            if (
                declared.version != definition.version
                or declared.description != definition.description
                or declared.input_model != definition.input_model.__name__
                or declared.output_model != definition.output_model.__name__
                or declared.tool_group != definition.tool_group
                or declared.tool_type != definition.tool_type
                or declared.risk_level != definition.risk_level
                or declared.execution_mode != definition.execution_mode
                or declared.timeout_seconds != definition.timeout_seconds
                or declared.retry.max_attempts != definition.retry.max_attempts
                or declared.retry.backoff_ms != definition.retry.backoff_ms
                or declared.cost.units != definition.cost.units
                or declared.confirmation_policy != definition.confirmation_policy
            ):
                raise ToolRegistryError(f"BUNDLE_TOOL_METADATA_MISMATCH_{name}")


@dataclass(frozen=True, slots=True)
class ToolPolicy:
    allowed_tool_names: frozenset[str]
    allowed_tool_groups: frozenset[ToolGroup]
    allowed_risk_levels: frozenset[RiskLevel] = frozenset(
        (RiskLevel.QUERY, RiskLevel.ANALYZE, RiskLevel.PREPARE)
    )
    max_cost_units: int = 0
    java_run_actions: bool = False

    def authorize(
        self,
        definition: ToolDefinition,
        authorized_tool_groups: frozenset[ToolGroup],
    ) -> None:
        server_action = (
            self.java_run_actions
            and definition.name in _JAVA_RUN_ACTIONS
            and definition.tool_type is ToolType.JAVA_PROXY
            and definition.confirmation_policy is ConfirmationPolicy.JAVA_REQUIRED
        )
        if definition.risk_level is RiskLevel.COMMIT and not server_action:
            _record_tool_denial("TOOL_COMMIT_FORBIDDEN", definition)
            raise ToolExecutionError("TOOL_COMMIT_FORBIDDEN")
        if definition.name not in self.allowed_tool_names:
            _record_tool_denial("TOOL_POLICY_DENIED", definition)
            raise ToolExecutionError("TOOL_POLICY_DENIED")
        if not tool_available(definition.name):
            _record_tool_denial("AGENT_CAPABILITY_DENIED", definition)
            raise ToolExecutionError("AGENT_CAPABILITY_DENIED")
        if definition.tool_group not in self.allowed_tool_groups:
            _record_tool_denial("TOOL_GROUP_NOT_ALLOWED", definition)
            raise ToolExecutionError("TOOL_GROUP_NOT_ALLOWED")
        if definition.tool_group not in authorized_tool_groups:
            _record_tool_denial("TOOL_GROUP_NOT_AUTHORIZED", definition)
            raise ToolExecutionError("TOOL_GROUP_NOT_AUTHORIZED")
        if definition.risk_level not in self.allowed_risk_levels:
            _record_tool_denial("TOOL_RISK_NOT_ALLOWED", definition)
            raise ToolExecutionError("TOOL_RISK_NOT_ALLOWED")
        if definition.execution_mode is not ExecutionMode.INLINE:
            _record_tool_denial("TOOL_EXECUTION_MODE_UNSUPPORTED", definition)
            raise ToolExecutionError("TOOL_EXECUTION_MODE_UNSUPPORTED")
        if definition.cost.units > self.max_cost_units:
            _record_tool_denial("TOOL_COST_BUDGET_EXCEEDED", definition)
            raise ToolExecutionError("TOOL_COST_BUDGET_EXCEEDED")
        if definition.confirmation_policy is not ConfirmationPolicy.NONE and not server_action:
            _record_tool_denial("TOOL_CONFIRMATION_REQUIRED", definition)
            raise ToolExecutionError("TOOL_CONFIRMATION_REQUIRED")


class ToolExecutor:
    """The only execution path for runtime tools; budgets are checked per attempt."""

    def __init__(
        self,
        registry: ToolRegistry,
        policy: ToolPolicy,
        input_preparers: Mapping[str, ToolInputPreparer],
        handlers: Mapping[str, ToolHandler],
        *,
        observer: ToolObserver | None = None,
    ) -> None:
        registered_names = {definition.name for definition in registry.definitions}
        configured_names = set(policy.allowed_tool_names)
        if not configured_names <= registered_names:
            raise ToolRegistryError("TOOL_POLICY_UNKNOWN_NAME")
        if set(input_preparers) != configured_names:
            raise ToolRegistryError("TOOL_INPUT_PREPARER_SET_MISMATCH")
        if set(handlers) != configured_names:
            raise ToolRegistryError("TOOL_HANDLER_SET_MISMATCH")
        self._registry = registry
        self._policy = policy
        self._input_preparers = dict(input_preparers)
        self._handlers = dict(handlers)
        self._observer = observer

    def model_tool_catalog(
        self,
        authorized_tool_groups: Iterable[str | ToolGroup],
    ) -> str:
        """Describe only the Bundle/Registry/Java intersection, never executable policy."""
        registered_groups = {item.tool_group for item in self._registry.definitions}
        parsed_groups: set[ToolGroup] = set()
        for value in authorized_tool_groups:
            try:
                group = ToolGroup(value)
            except ValueError:
                raise ToolExecutionError("TOOL_GROUP_UNKNOWN") from None
            if group not in registered_groups:
                raise ToolExecutionError("TOOL_GROUP_NOT_REGISTERED")
            parsed_groups.add(group)
        visible = tuple(
            definition
            for definition in self._registry.definitions
            if definition.name in self._policy.allowed_tool_names
            and definition.tool_group in self._policy.allowed_tool_groups
            and definition.tool_group in parsed_groups
            and definition.risk_level in self._policy.allowed_risk_levels
            and definition.risk_level is not RiskLevel.COMMIT
            and tool_available(definition.name)
        )
        if not visible:
            return (
                "[RUNTIME TOOL CATALOG]\n"
                "No QUERY, ANALYZE, or PREPARE tool is authorized for this Run."
            )
        entries = "\n".join(
            f"- {item.name}@{item.version}; group={item.tool_group.value}; "
            f"risk={item.risk_level.value}; purpose={item.description}; "
            "inputSchema="
            + json.dumps(
                item.input_model.model_json_schema(by_alias=True),
                ensure_ascii=False,
                allow_nan=False,
                sort_keys=True,
                separators=(",", ":"),
            )
            for item in visible
        )
        return (
            "[RUNTIME TOOL CATALOG]\n"
            "Only these tools are model-callable for this Run. This catalog is descriptive; "
            "ToolPolicy and input Schema remain authoritative.\n"
            f"{entries}"
        )

    async def execute(
        self,
        *,
        run_id: UUID,
        name: str,
        version: str,
        payload: BaseModel | Mapping[str, object],
        counters: RuntimeUsageCounters,
        authorized_tool_groups: Iterable[str | ToolGroup],
        step_number: int | None = None,
    ) -> ToolExecutionResult:
        try:
            definition = self._registry.resolve(name, version)
        except ToolRegistryError as exc:
            raise ToolExecutionError(exc.code) from None
        registered_groups = {item.tool_group for item in self._registry.definitions}
        parsed_groups: set[ToolGroup] = set()
        for value in authorized_tool_groups:
            try:
                group = ToolGroup(value)
            except ValueError:
                raise ToolExecutionError("TOOL_GROUP_UNKNOWN") from None
            if group not in registered_groups:
                raise ToolExecutionError("TOOL_GROUP_NOT_REGISTERED")
            parsed_groups.add(group)
        self._policy.authorize(definition, frozenset(parsed_groups))
        try:
            validated_input = definition.input_model.model_validate(payload)
        except ValidationError:
            _record_tool_denial("TOOL_INPUT_INVALID", definition)
            raise ToolExecutionError("TOOL_INPUT_INVALID") from None
        if name == "task_draft.propose" and isinstance(validated_input, TaskDraftPrepareInput):
            require_preparation_capability(validated_input.intent.value)
        try:
            prepared_input = self._input_preparers[name](validated_input)
        except ValidationError:
            _record_tool_denial("TOOL_INPUT_INVALID", definition)
            raise ToolExecutionError("TOOL_INPUT_INVALID") from None
        except Exception as exc:
            candidate = getattr(exc, "code", None)
            code = candidate if candidate in _SAFE_HANDLER_ERROR_CODES else "TOOL_INPUT_INVALID"
            _record_tool_denial(code, definition)
            raise ToolExecutionError(code) from None
        if not isinstance(prepared_input, BaseModel):
            _record_tool_denial("TOOL_INPUT_INVALID", definition)
            raise ToolExecutionError("TOOL_INPUT_INVALID")
        try:
            input_hash = _canonical_model_hash(prepared_input)
        except (TypeError, ValueError):
            _record_tool_denial("TOOL_INPUT_INVALID", definition)
            raise ToolExecutionError("TOOL_INPUT_INVALID") from None
        call_id = uuid4()
        observations: list[ToolObservation] = []

        for attempt in range(1, definition.retry.max_attempts + 1):
            try:
                counters.before_tool_call()
            except HarnessViolation as exc:
                _record_tool_denial(exc.code, definition)
                raise
            invocation = ToolInvocation(
                tool_call_id=call_id,
                run_id=run_id,
                tool_name=definition.name,
                tool_version=definition.version,
                risk_level=definition.risk_level,
                input_hash=input_hash,
                attempt=attempt,
            )
            started = monotonic()
            with start_span(
                f"tool.{definition.name}",
                attributes={
                    "tool.callRef": safe_ref(call_id),
                    "tool.name": definition.name,
                    "tool.version": definition.version,
                    "tool.riskLevel": definition.risk_level.value,
                    "tool.inputHash": input_hash,
                    "tool.attempt": attempt,
                    "retryNo": attempt,
                    "agent.step.number": step_number or 0,
                },
            ) as span:
                try:
                    output = await asyncio.wait_for(
                        self._invoke_handler(self._handlers[name], prepared_input),
                        timeout=definition.timeout_seconds,
                    )
                    validated_output = definition.output_model.model_validate(output)
                except TimeoutError:
                    code = "TOOL_TIMEOUT"
                except ValidationError:
                    code = "TOOL_OUTPUT_INVALID"
                except Exception as exc:
                    candidate = getattr(exc, "code", None)
                    code = (
                        candidate
                        if candidate in _SAFE_HANDLER_ERROR_CODES
                        else "TOOL_EXECUTION_FAILED"
                    )
                else:
                    span.set("tool.status", ToolInvocationStatus.SUCCEEDED.value)
                    observation = self._observation(
                        invocation,
                        status=ToolInvocationStatus.SUCCEEDED,
                        started=started,
                        output_ref=safe_ref(_canonical_model_hash(validated_output)),
                    )
                    self._record(observation)
                    return ToolExecutionResult(
                        output=validated_output,
                        invocation=invocation,
                        observation=observation,
                    )
                span.set("tool.status", ToolInvocationStatus.FAILED.value)
                span.fail(code)

            observation = self._observation(
                invocation,
                status=ToolInvocationStatus.FAILED,
                started=started,
                stable_error_code=code,
            )
            observations.append(observation)
            self._record(observation)
            if attempt < definition.retry.max_attempts:
                await asyncio.sleep(definition.retry.backoff_ms / 1_000)
                continue
            raise ToolExecutionError(code, tuple(observations))
        raise RuntimeError("unreachable")

    @staticmethod
    async def _invoke_handler(handler: ToolHandler, payload: BaseModel) -> BaseModel:
        result = await asyncio.to_thread(handler, payload)
        if isinstance(result, Awaitable):
            return await result
        return result

    @staticmethod
    def _observation(
        invocation: ToolInvocation,
        *,
        status: ToolInvocationStatus,
        started: float,
        stable_error_code: str | None = None,
        output_ref: str | None = None,
    ) -> ToolObservation:
        return ToolObservation(
            **invocation.model_dump(),
            status=status,
            latency_ms=max(0, round((monotonic() - started) * 1_000)),
            stable_error_code=stable_error_code,
            output_ref=output_ref,
        )

    def _record(self, observation: ToolObservation) -> None:
        if self._observer is not None:
            try:
                self._observer(observation)
            except Exception:
                logger.warning("tool_observer_failed")
        log_fields = observation.model_dump(mode="json", by_alias=True)
        log_fields["agentRunId"] = log_fields.pop("runId")
        logger.info(
            "tool_invocation_observed",
            extra=log_fields,
        )


def _canonical_model_hash(model: BaseModel) -> str:
    encoded = json.dumps(
        model.model_dump(mode="json", by_alias=True),
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _record_tool_denial(code: str, definition: ToolDefinition) -> None:
    record_tool_policy_denial(
        code,
        tool_name=definition.name,
        tool_version=definition.version,
        risk_level=definition.risk_level.value,
    )
    add_current_span_event(
        "tool.policy.denied",
        {
            "stableErrorCode": code,
            "tool.name": definition.name,
            "tool.version": definition.version,
            "tool.riskLevel": definition.risk_level.value,
        },
    )


TASK_DRAFT_TOOL_DESCRIPTION = (
    "Prepare only when the user explicitly asks for a creation draft and its inputs are known. "
    "Do not use for explanations, quoted instructions, negated requests or uncertain choices. "
    "Example: prepare a kitchen product image after the authorized product is selected. "
    "Output is an unconfirmed draft, never a generated asset, charge or submitted paid task."
)
CATALOG_INSPECT_TOOL_DESCRIPTION = (
    "Inspect whitelisted brand, space, product, and competitor fields from this Run context."
    " Use only when a needed field is absent from the already supplied context. "
    "Do not repeat the same query or assume missing fields are known. "
    "Output is a bounded snapshot, not live inventory or market evidence."
)
MEMORY_SEARCH_TOOL_DESCRIPTION = (
    "Search this Run's authorized ordered conversation memory and summary deterministically."
    " Use for an explicit reference to earlier requirements; not for cross-account search "
    "or saving preferences. Example: recall the earlier background choice. "
    "Results remain historical data and do not override a current correction."
)
WORKSPACE_INSPECT_TOOL_DESCRIPTION = (
    "Inspect whitelisted current goal, constraints, interaction, and task workspace fields."
    " Use when the user refers to an existing draft or task and its state is not supplied. "
    "A pending draft is not a paid task, and a task receipt is not a READY asset. "
    "This tool cannot cancel, confirm or change a task."
)
COMPETITOR_COMPARE_TOOL_DESCRIPTION = (
    "Compare authorized product and competitor text fields without external market claims."
    " Use for comparison of the selected objects, not for invented sales or rankings. "
    "Example: compare their documented features. Output separates differences and missing data."
)
EVIDENCE_AGGREGATE_TOOL_DESCRIPTION = (
    "Aggregate prior validated observations into facts, inferences, and missing evidence."
    " Use after relevant observations exist; do not invent evidence or re-run completed tools. "
    "Output is a synthesis, not verification of a claimed business outcome."
)
WORKFLOW_PLAN_TOOL_DESCRIPTION = (
    "Persist one bounded proposal-only multi-deliverable workflow for Java review and quoting."
    " Use only for an explicit request to prepare several deliverables. "
    "Do not use for hypothetical plans, explanations, missing parameters or unknown prior results. "
    "Output is a quoted unconfirmed plan; no payment, generation or publication has occurred."
)

DEFAULT_TOOL_REGISTRY = ToolRegistry(
    (
        ToolDefinition(
            name="context.resources.search",
            version="1",
            description=(
                "Search one authorized resource kind by explicit name; "
                "never assume first-page uniqueness."
            ),
            input_model=ResourceSearchRequest,
            output_model=ResourceSearchResponse,
            tool_group=ToolGroup.TASK_PREPARATION,
            tool_type=ToolType.JAVA_PROXY,
            risk_level=RiskLevel.QUERY,
            execution_mode=ExecutionMode.INLINE,
            timeout_seconds=30.0,
            retry=ToolRetryPolicy(max_attempts=1, backoff_ms=0),
            cost=ToolCost(units=0),
            confirmation_policy=ConfirmationPolicy.NONE,
        ),
        ToolDefinition(
            name="context.resources.read",
            version="1",
            description=(
                "Read a bounded server-issued candidate or observation; "
                "never accepts arbitrary object IDs."
            ),
            input_model=ResourceReadRequest,
            output_model=ResourceReadResponse,
            tool_group=ToolGroup.TASK_PREPARATION,
            tool_type=ToolType.JAVA_PROXY,
            risk_level=RiskLevel.QUERY,
            execution_mode=ExecutionMode.INLINE,
            timeout_seconds=30.0,
            retry=ToolRetryPolicy(max_attempts=1, backoff_ms=0),
            cost=ToolCost(units=0),
            confirmation_policy=ConfirmationPolicy.NONE,
        ),
        ToolDefinition(
            name="task_draft.propose",
            version="1",
            description=TASK_DRAFT_TOOL_DESCRIPTION,
            input_model=TaskDraftPrepareInput,
            output_model=TaskDraftProposal,
            tool_group=ToolGroup.TASK_PREPARATION,
            tool_type=ToolType.PURE_FUNCTION,
            risk_level=RiskLevel.PREPARE,
            execution_mode=ExecutionMode.INLINE,
            timeout_seconds=1.0,
            retry=ToolRetryPolicy(max_attempts=1, backoff_ms=0),
            cost=ToolCost(units=0),
            confirmation_policy=ConfirmationPolicy.NONE,
        ),
        ToolDefinition(
            name="context.catalog.inspect",
            version="1",
            description=CATALOG_INSPECT_TOOL_DESCRIPTION,
            input_model=CatalogInspectInput,
            output_model=CatalogInspectOutput,
            tool_group=ToolGroup.TASK_PREPARATION,
            tool_type=ToolType.PURE_FUNCTION,
            risk_level=RiskLevel.QUERY,
            execution_mode=ExecutionMode.INLINE,
            timeout_seconds=1.0,
            retry=ToolRetryPolicy(max_attempts=1, backoff_ms=0),
            cost=ToolCost(units=0),
            confirmation_policy=ConfirmationPolicy.NONE,
        ),
        ToolDefinition(
            name="context.memory.search",
            version="1",
            description=MEMORY_SEARCH_TOOL_DESCRIPTION,
            input_model=MemorySearchInput,
            output_model=MemorySearchOutput,
            tool_group=ToolGroup.TASK_PREPARATION,
            tool_type=ToolType.PURE_FUNCTION,
            risk_level=RiskLevel.QUERY,
            execution_mode=ExecutionMode.INLINE,
            timeout_seconds=1.0,
            retry=ToolRetryPolicy(max_attempts=1, backoff_ms=0),
            cost=ToolCost(units=0),
            confirmation_policy=ConfirmationPolicy.NONE,
        ),
        ToolDefinition(
            name="context.task_workspace.inspect",
            version="1",
            description=WORKSPACE_INSPECT_TOOL_DESCRIPTION,
            input_model=TaskWorkspaceInspectInput,
            output_model=TaskWorkspaceInspectOutput,
            tool_group=ToolGroup.TASK_PREPARATION,
            tool_type=ToolType.PURE_FUNCTION,
            risk_level=RiskLevel.QUERY,
            execution_mode=ExecutionMode.INLINE,
            timeout_seconds=1.0,
            retry=ToolRetryPolicy(max_attempts=1, backoff_ms=0),
            cost=ToolCost(units=0),
            confirmation_policy=ConfirmationPolicy.NONE,
        ),
        ToolDefinition(
            name="analysis.competitor.compare",
            version="1",
            description=COMPETITOR_COMPARE_TOOL_DESCRIPTION,
            input_model=CompetitorCompareInput,
            output_model=CompetitorCompareOutput,
            tool_group=ToolGroup.TASK_PREPARATION,
            tool_type=ToolType.PURE_FUNCTION,
            risk_level=RiskLevel.ANALYZE,
            execution_mode=ExecutionMode.INLINE,
            timeout_seconds=1.0,
            retry=ToolRetryPolicy(max_attempts=1, backoff_ms=0),
            cost=ToolCost(units=0),
            confirmation_policy=ConfirmationPolicy.NONE,
        ),
        ToolDefinition(
            name="analysis.evidence.aggregate",
            version="1",
            description=EVIDENCE_AGGREGATE_TOOL_DESCRIPTION,
            input_model=EvidenceAggregateInput,
            output_model=EvidenceAggregateOutput,
            tool_group=ToolGroup.TASK_PREPARATION,
            tool_type=ToolType.PURE_FUNCTION,
            risk_level=RiskLevel.ANALYZE,
            execution_mode=ExecutionMode.INLINE,
            timeout_seconds=1.0,
            retry=ToolRetryPolicy(max_attempts=1, backoff_ms=0),
            cost=ToolCost(units=0),
            confirmation_policy=ConfirmationPolicy.NONE,
        ),
        ToolDefinition(
            name="workflow_plan.propose",
            version="1",
            description=WORKFLOW_PLAN_TOOL_DESCRIPTION,
            input_model=WorkflowPlanProposalRequest,
            output_model=WorkflowPlan,
            tool_group=ToolGroup.TASK_PREPARATION,
            tool_type=ToolType.JAVA_PROXY,
            risk_level=RiskLevel.PREPARE,
            execution_mode=ExecutionMode.INLINE,
            timeout_seconds=3.0,
            retry=ToolRetryPolicy(max_attempts=1, backoff_ms=0),
            cost=ToolCost(units=0),
            confirmation_policy=ConfirmationPolicy.NONE,
        ),
    )
)
