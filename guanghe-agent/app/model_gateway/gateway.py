"""Narrow model gateway contract plus the deterministic offline implementation."""

import json
import re
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Literal, Protocol, TypeVar, cast, runtime_checkable

from pydantic import BaseModel, ConfigDict, Field, JsonValue, model_validator
from pydantic.alias_generators import to_camel

from app.agent.models import DraftOperation, Intent
from app.common.enums import ProviderStatus

DeltaHandler = Callable[[str], Awaitable[None]]
StructuredDeltaHandler = Callable[[int, str], Awaitable[None]]
ModelRole = Literal["system", "user", "assistant", "tool"]
# These complete-response protocol failures happen before any call is returned to
# an executor. The Agent Loop may request one fresh decision within its budget;
# this is not permission to replay tool effects or retry an unknown submission.
NATIVE_TOOL_PROTOCOL_FAILURE_STAGES = frozenset(
    {
        "native_tool_calls_missing",
        "native_tool_call_limit_exceeded",
        "native_tool_call_id_invalid",
        "native_tool_call_id_duplicate",
        "native_tool_unavailable",
    }
)


@dataclass(frozen=True, slots=True)
class ModelToolCall:
    """One native function call. Arguments remain untrusted until tool validation."""

    id: str
    name: str
    arguments: str = field(repr=False)


@dataclass(frozen=True, slots=True)
class ModelToolDefinition:
    """An authorized tool schema supplied by the existing tool registry."""

    name: str
    description: str
    parameters: dict[str, object]


@dataclass(frozen=True, slots=True)
class ModelMessage:
    """One ordered, provider-neutral chat message."""

    role: ModelRole
    content: str
    tool_calls: tuple[ModelToolCall, ...] = ()
    tool_call_id: str | None = None
    # Retain provider protocol state, never send it as user-visible reply text.
    reasoning_content: str | None = field(default=None, repr=False)


ModelMessages = tuple[ModelMessage, ...]
StructuredOutputMode = Literal["JSON_OBJECT", "TOOL_CALL"]
StructuredModel = TypeVar("StructuredModel", bound=BaseModel)
STRUCTURED_VALIDATION_MAX_ERRORS = 8
STRUCTURED_VALIDATION_MAX_PATH_SEGMENTS = 6
STRUCTURED_SAFE_VALIDATION_TYPES = frozenset(
    (
        "missing",
        "extra_forbidden",
        "string_type",
        "string_too_short",
        "string_too_long",
        "dict_type",
        "list_type",
        "tuple_type",
        "int_type",
        "int_parsing",
        "bool_type",
        "float_type",
        "literal_error",
        "enum",
        "too_short",
        "too_long",
        "greater_than_equal",
        "less_than_equal",
        "invalid_json_value",
        "model_type",
        "value_error",
    )
)


class DiagnosticValidationCode(StrEnum):
    """Code-owned diagnostic reasons; no input values or exception messages."""

    SOURCE_MISSING = "diag_src_missing"
    QUOTE_ABSENT = "diag_quote_absent"
    PHASE_SHAPE = "diag_phase_shape"
    BRAND_CHANGED = "diag_brand_changed"
    REGION_CHANGED = "diag_region_change"
    PRIMARY_TERM = "diag_primary_term"
    BRAND_PROOF = "diag_brand_proof"
    ALIAS_PROOF = "diag_alias_proof"
    WEBSITE_HOST = "diag_website_host"
    SLOTS_EXCESS = "diag_slots_excess"
    COMPETITOR_DUPLICATE = "diag_comp_dup"
    SELF_COMPETITOR = "diag_self_comp"
    NAME_PROOF = "diag_name_proof"
    CITATION_SET = "diag_cite_set"
    QUESTION_COUNT = "diag_q_count"
    QUESTION_RETAINED = "diag_q_retained"
    QUESTION_DUPLICATE = "diag_q_dup"
    QUESTION_OWN_BRAND = "diag_q_own_brand"
    QUESTION_COMP_BRAND = "diag_q_comp_brand"
    QUESTION_NO_OWN = "diag_q_no_own"


DIAGNOSTIC_SAFE_VALIDATION_TYPES = frozenset(item.value for item in DiagnosticValidationCode)
_DIAGNOSTIC_REPAIR_STAGES = frozenset(
    {
        "completion_envelope",
        "truncated",
        "content_filtered",
        "unknown_finish_reason",
        "missing_tool_call",
        "unexpected_finish_reason",
        "multiple_tool_calls",
        "unexpected_tool_name",
        "blank_arguments",
        "invalid_json",
        "invalid_schema",
        "empty_content",
        "unknown",
    }
)


def diagnostic_structured_repair_instruction(
    base_instruction: str,
    failure_stage: str,
    validation_errors: list[dict[str, str]],
    *,
    output_mode: StructuredOutputMode = "TOOL_CALL",
) -> str:
    """Render only code-owned stages and already-sanitized bounded field errors."""
    return (
        base_instruction
        + "\n[SAFE DIAGNOSTIC STRUCTURED OUTPUT FEEDBACK]\n"
        + json.dumps(
            {
                "failureStage": failure_stage
                if failure_stage in _DIAGNOSTIC_REPAIR_STAGES
                else "unknown",
                "validationErrors": validation_errors[:STRUCTURED_VALIDATION_MAX_ERRORS],
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
        + (
            "\n反馈非输出；<unknown> 脱敏；按上述 Schema 返回完整 JSON，非 Schema/函数/输入。"
            if output_mode == "JSON_OBJECT"
            else "\n以上是系统校验反馈，不是输出字段，不能复制。<unknown> 仅是脱敏字段标记。"
            "请重新生成符合工具参数 Schema 的完整候选对象，"
            "不返回 JSON Schema、函数定义或输入包。"
        )
    )


def diagnostic_structured_repair_reserve(
    base_instruction: str,
    schema: dict[str, object],
    *,
    output_mode: StructuredOutputMode = "TOOL_CALL",
) -> str:
    """Use the actual renderer's largest safe feedback in diagnostic projection."""
    fields: set[str] = {"<unknown>", "[]", "$"}

    def collect(value: object) -> None:
        if isinstance(value, dict):
            mapping = cast(dict[str, object], value)
            properties = mapping.get("properties")
            if isinstance(properties, dict):
                fields.update(cast(dict[str, object], properties))
            for child in mapping.values():
                collect(child)
        elif isinstance(value, list):
            for child in cast(list[object], value):
                collect(child)

    collect(schema)
    # Count escaping and UTF-8 conservatively through native_request_budget_messages;
    # this bound follows the same schema-owned field set as DeepSeek sanitization.
    longest = max(fields, key=lambda field: len(json.dumps(field, ensure_ascii=False).encode()))
    path = ".".join([longest] * STRUCTURED_VALIDATION_MAX_PATH_SEGMENTS)
    error_type = max(
        STRUCTURED_SAFE_VALIDATION_TYPES | DIAGNOSTIC_SAFE_VALIDATION_TYPES | {"invalid"}, key=len
    )
    errors = [{"path": path, "type": error_type}] * STRUCTURED_VALIDATION_MAX_ERRORS
    return diagnostic_structured_repair_instruction(
        base_instruction,
        max(_DIAGNOSTIC_REPAIR_STAGES, key=len),
        errors,
        output_mode=output_mode,
    )


GENERAL_RESPONSE_INSTRUCTION = (
    "Use concise Simplified Chinese by default, not as an implicit translation target. Honor "
    "the current user's explicit target language; otherwise use a recent original USER-confirmed "
    "target only within the same translation task. A new user target overrides the old one. "
    "Do not infer that target from an assistant guess, source language or an unrelated old task. "
    "If the translation target remains unknown, ask one brief target-language question instead "
    "of echoing the source as a completed translation. With sufficient input, perform the "
    "requested translation or editing directly; do not add GEO evidence advice. Identical "
    "spellings can be valid for names or abbreviations, so identity alone is not failure. "
    "Catalog facts are scoped to the explicit, non-empty "
    "product and space.brand fields identified in the final fact scope, not the entire authorized "
    "Run context. Conversation summaries and workflow state are not product facts. "
    "If a useful conclusion "
    "comes from category knowledge or inference, label it as a hypothesis, recommendation, or "
    "point to verify; never present it as a fact about this product. A general MESSAGE must "
    "answer directly and must not create numbered workflow choices, ask for confirmation of an "
    "implicit draft, or pretend that free text owns continuation state. Short promotional copy, "
    "translation and editing are direct chat text, not article tasks; do not require a selected "
    "product, business space or quotation for them. When product facts are absent, promotional "
    "copy may only describe a possible use occasion, an invitation to a feeling, or a neutral "
    "topic: do not assert device structure, controls, features, processing methods, performance, "
    "convenience, suitability or results. Such claims require evidence even without numbers. "
    "A category name and previous assistant copy are not evidence. Do not claim one-button "
    "operation, fresh grinding, quietness, simplicity or everyday suitability merely because "
    "the product is a coffee machine. When editing prior assistant copy, remove unsupported "
    "claims rather than repeating them. Preserve facts explicitly supplied by the user as "
    "user-provided writing material, without exaggeration or claiming independent verification; "
    "merely asking to edit quoted copy does not endorse all its claims. Deliver the edited copy "
    "without a lengthy disclaimer. Do not self-certify the answer as free of invented facts. "
    "Missing information for an explicitly requested persistent task must be "
    "collected through a structured INTERACTION. A prior goal, intent or pending question is not "
    "an existing task: when no authorized draft, task or article exists, explain that it has not "
    "started instead of asking for creation parameters. Never invent product menus. Never claim "
    "a Java-side task or business "
    "side effect has completed. Never expose Java, Python, workers, queues, leases, service calls, "
    "internal enum values, or internal status codes to the user; translate them into product-level "
    "language and one actionable next step. Asset IDs, filenames and text metadata are not image "
    "pixels: never claim to have inspected an image unless this request contains image input. "
    "This chat currently accepts attachments as creation references, not visual analysis input; "
    "do not promise visual understanding after upload."
)


def last_user_content(messages: ModelMessages) -> str:
    """Return the current user turn without flattening prior conversation roles."""
    for message in reversed(messages):
        if message.role == "user":
            return message.content
    raise ModelGatewayError("MODEL_MESSAGES_CURRENT_USER_MISSING")


def serialize_messages(messages: ModelMessages) -> list[dict[str, object]]:
    """Create an OpenAI-compatible body without joining history into one string."""
    serialized: list[dict[str, object]] = []
    for message in messages:
        item: dict[str, object] = {"role": message.role, "content": message.content}
        if message.tool_calls:
            item["tool_calls"] = [
                {
                    "id": call.id,
                    "type": "function",
                    "function": {"name": call.name, "arguments": call.arguments},
                }
                for call in message.tool_calls
            ]
        if message.tool_call_id is not None:
            item["tool_call_id"] = message.tool_call_id
        if message.reasoning_content is not None:
            item["reasoning_content"] = message.reasoning_content
        serialized.append(item)
    return serialized


def diagnostic_analysis_json_schema_messages(
    messages: ModelMessages, schema: dict[str, object]
) -> ModelMessages:
    """Render the complete code-owned report schema for diagnostic JSON mode only."""
    if not messages or messages[0].role != "system":
        raise ModelGatewayError("MODEL_MESSAGES_INVALID")
    first = messages[0]
    return (
        ModelMessage(
            role="system",
            content=first.content + "\n[DIAGNOSTIC REPORT JSON SCHEMA]\n"
            "JSON 报告须符合完整 Schema。\n"
            + json.dumps(schema, ensure_ascii=False, separators=(",", ":"), allow_nan=False),
        ),
        *messages[1:],
    )


def diagnostic_json_request_budget_messages(messages: ModelMessages) -> ModelMessages:
    """Count the actual diagnostic JSON messages, schema and response-format metadata."""
    return (
        ModelMessage(
            role="system",
            content=json.dumps(
                {
                    "messages": serialize_messages(messages),
                    "response_format": {"type": "json_object"},
                },
                ensure_ascii=False,
                separators=(",", ":"),
                allow_nan=False,
            ),
        ),
    )


def native_request_budget_messages(
    messages: ModelMessages, tools: tuple[ModelToolDefinition, ...]
) -> ModelMessages:
    """Count native message metadata and tool schemas through the existing budget boundary.

    This projection is only for estimation; the provider receives the actual ordered roles.
    """
    return (
        ModelMessage(
            role="system",
            content=json.dumps(
                {
                    "messages": serialize_messages(messages),
                    "tools": [
                        {
                            "type": "function",
                            "function": {
                                "name": tool.name,
                                "description": tool.description,
                                "parameters": tool.parameters,
                            },
                        }
                        for tool in tools
                    ],
                },
                ensure_ascii=False,
                separators=(",", ":"),
                allow_nan=False,
            ),
        ),
    )


def task_parameter_instruction(intent: Intent, *, geo_max_questions: int = 10) -> str:
    allowed_keys = TASK_PARAMETER_KEYS.get(intent)
    if allowed_keys is None:
        raise ModelGatewayError("TASK_INTENT_UNSUPPORTED")
    instruction = (
        f"The intent is {intent.value}. The only permitted parameter keys are: "
        f"{', '.join(sorted(allowed_keys))}."
    )
    if intent is Intent.GEO:
        if not 3 <= geo_max_questions <= 10:
            raise ModelGatewayError("GEO_PREPARATION_CONSTRAINT_INVALID")
        return (
            instruction
            + f" Build a QUESTION_DISCOVERY proposal with a concise topic, a region, and 3 to "
            f"{geo_max_questions} "
            "complete natural-language questions. A structured pending choice, when present, has "
            "already been resolved into the current request before this call; never infer workflow "
            "state from previous assistant prose. Use the authorized product context and do not "
            "repeat a question that the user has already answered. Return question strings, not "
            "objects or database IDs. Questions must be neutral category/use-case questions "
            "a shopper would ask, without naming our product, our brand, aliases or competitors. "
            "Select platformCodes only from taskPreparationConstraints.geo.platformCodes; "
            "recommend at most two initially. Preserve existing platforms for unrelated changes. "
            "Never invent availability, prices or confirmation."
        )
    return instruction


TASK_PARAMETER_KEYS: dict[Intent, frozenset[str]] = {
    Intent.GEO: frozenset(("topic", "audience", "region", "mode", "questions", "platformCodes")),
    Intent.IMAGE: frozenset(
        (
            "scene",
            "style",
            "lighting",
            "aspectRatio",
            "count",
            "negativePrompt",
            "visualBrief",
        )
    ),
    Intent.VIDEO: frozenset(
        (
            "scene",
            "style",
            "durationSeconds",
            "aspectRatio",
            "cameraMotion",
            "resolution",
            "audio",
            "seed",
            "promptExtend",
            "watermark",
        )
    ),
    Intent.ARTICLE: frozenset(("topic", "tone", "language", "outline", "targetLength")),
}


def filter_task_parameters(
    intent: Intent,
    parameters: dict[str, JsonValue],
) -> tuple[dict[str, JsonValue], bool]:
    allowed_keys = TASK_PARAMETER_KEYS.get(intent, frozenset())
    filtered: dict[str, JsonValue] = {}
    dropped_unknown = False
    for key, value in parameters.items():
        if key not in allowed_keys:
            dropped_unknown = True
            continue
        # Models commonly emit empty optional fields instead of omitting them.
        # Treat those values as absent so one empty hint cannot invalidate an
        # otherwise valid task proposal. Numeric zero and empty collections are
        # intentionally preserved for the domain schema to validate.
        if value is None:
            continue
        if isinstance(value, str):
            value = value.strip()
            if not value:
                continue
        filtered[key] = value
    return filtered, dropped_unknown


@dataclass(frozen=True, slots=True)
class ModelIdentity:
    provider: str
    model: str
    version: str


@dataclass(frozen=True, slots=True)
class GatewayUsage:
    input_tokens: int = 0
    output_tokens: int = 0
    latency_ms: int = 0
    request_count: int = 0

    def __add__(self, other: "GatewayUsage") -> "GatewayUsage":
        return GatewayUsage(
            input_tokens=self.input_tokens + other.input_tokens,
            output_tokens=self.output_tokens + other.output_tokens,
            latency_ms=self.latency_ms + other.latency_ms,
            request_count=self.request_count + other.request_count,
        )


@dataclass(frozen=True, slots=True)
class StructuredResponseDiagnostics:
    mode: StructuredOutputMode
    attempt_count: int
    first_attempt_succeeded: bool
    recovered_by_retry: bool


class RouterDecision(StrEnum):
    ANSWER = "ANSWER"
    ANALYZE = "ANALYZE"
    PLAN = "PLAN"
    CLARIFY = "CLARIFY"
    PREPARE = "PREPARE"


class RouterComplexity(StrEnum):
    SIMPLE = "SIMPLE"
    STANDARD = "STANDARD"
    COMPLEX = "COMPLEX"


class RouterContextPath(StrEnum):
    SPACE = "space"
    PRODUCT = "product"
    COMPETITORS = "competitors"
    CONVERSATION_MEMORY = "conversationMemory"
    TASK_WORKSPACE = "taskWorkspace"
    PENDING_INTERACTION = "taskWorkspace.pendingInteraction"
    ACTIVE_DRAFT_ID = "taskWorkspace.activeDraftId"
    ACTIVE_TASK_ID = "taskWorkspace.activeTaskId"
    REFERENCE_ASSET_IDS = "taskWorkspace.referenceAssetIds"
    RUNNING_TASKS = "taskWorkspace.runningTasks"


class ClarificationReason(StrEnum):
    MISSING_CONTEXT = "MISSING_CONTEXT"
    OBJECT_MISMATCH = "OBJECT_MISMATCH"
    AMBIGUOUS_REQUEST = "AMBIGUOUS_REQUEST"


class RouterClarification(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    reason: ClarificationReason
    target: RouterContextPath | None
    question: str | None = Field(min_length=1, max_length=300)

    @model_validator(mode="after")
    def validate_target(self) -> "RouterClarification":
        if self.reason is ClarificationReason.OBJECT_MISMATCH and self.target not in {
            RouterContextPath.PRODUCT,
            RouterContextPath.SPACE,
            RouterContextPath.REFERENCE_ASSET_IDS,
        }:
            raise ValueError("OBJECT_MISMATCH requires an object target")
        if self.reason is ClarificationReason.MISSING_CONTEXT and self.target is None:
            raise ValueError("MISSING_CONTEXT requires a target")
        if self.reason is ClarificationReason.AMBIGUOUS_REQUEST and not self.question:
            raise ValueError("AMBIGUOUS_REQUEST requires one concrete question")
        return self


class IntentRoute(BaseModel):
    """The sole runtime model for the frozen Intent Router V2 contract."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
    )

    schema_version: Literal["intent-router-v2.2"]
    primary_intent: Intent = Field(
        description=(
            "Classify the current requested deliverable, not marketing keywords or a prior goal. "
            "GENERAL includes short chat copy, translation, rewriting, and task-status questions. "
            "ARTICLE requires an explicit persistent article, a full long-form article, or "
            "continuation of an actual authorized article stage."
        )
    )
    decision: RouterDecision
    complexity: RouterComplexity
    context_needs: tuple[RouterContextPath, ...] = Field(
        max_length=10,
        description=(
            "Only context necessary to fulfill this turn. Generic short chat copy does not "
            "require selected space/product. A missing task is not a reason to collect new "
            "task parameters when the user is only asking its status."
        ),
    )
    reference_targets: tuple[RouterContextPath, ...] = Field(max_length=10)
    requires_clarification: bool
    fallback_required: bool
    clarification: RouterClarification | None
    draft_operation: DraftOperation | None

    @model_validator(mode="after")
    def validate_router_invariants(self) -> "IntentRoute":
        task_intents = {Intent.GEO, Intent.IMAGE, Intent.VIDEO, Intent.ARTICLE, Intent.MEDIA}
        if len(self.context_needs) != len(set(self.context_needs)):
            raise ValueError("contextNeeds must be unique")
        if len(self.reference_targets) != len(set(self.reference_targets)):
            raise ValueError("referenceTargets must be unique")
        if self.decision is RouterDecision.PREPARE and self.primary_intent not in task_intents:
            raise ValueError("PREPARE requires a task intent")
        if self.primary_intent is Intent.AMBIGUOUS_CONFIRMATION and (
            self.decision is not RouterDecision.CLARIFY or not self.requires_clarification
        ):
            raise ValueError("ambiguous confirmation must clarify")
        if (self.decision is RouterDecision.CLARIFY) != self.requires_clarification:
            raise ValueError("requiresClarification must mirror CLARIFY")
        if (self.decision is RouterDecision.CLARIFY) != (self.clarification is not None):
            raise ValueError("CLARIFY requires clarification and other decisions forbid it")
        if (self.decision is RouterDecision.PREPARE) != (self.draft_operation is not None):
            raise ValueError("PREPARE requires draftOperation and other decisions forbid it")
        return self


def default_intent_route(intent: Intent) -> IntentRoute:
    """Compatibility route for focused test gateways which return only the legacy intent."""
    if intent is Intent.AMBIGUOUS_CONFIRMATION:
        decision = RouterDecision.CLARIFY
        complexity = RouterComplexity.SIMPLE
    elif intent in {Intent.GENERAL, Intent.MEDIA}:
        decision = RouterDecision.ANSWER
        complexity = RouterComplexity.SIMPLE
    else:
        decision = RouterDecision.PREPARE
        complexity = RouterComplexity.STANDARD
    return IntentRoute(
        schema_version="intent-router-v2.2",
        primary_intent=intent,
        decision=decision,
        complexity=complexity,
        context_needs=(),
        reference_targets=(),
        requires_clarification=decision is RouterDecision.CLARIFY,
        fallback_required=False,
        clarification=(
            RouterClarification(
                reason=ClarificationReason.AMBIGUOUS_REQUEST,
                target=None,
                question="你想确认哪一份待确认草稿？",
            )
            if decision is RouterDecision.CLARIFY
            else None
        ),
        draft_operation=DraftOperation.NEW if decision is RouterDecision.PREPARE else None,
    )


@dataclass(frozen=True, slots=True, init=False)
class IntentResult:
    route: IntentRoute
    usage: GatewayUsage = GatewayUsage()
    diagnostics: StructuredResponseDiagnostics | None = None

    def __init__(
        self,
        route: IntentRoute | Intent,
        usage: GatewayUsage | None = None,
        diagnostics: StructuredResponseDiagnostics | None = None,
    ) -> None:
        object.__setattr__(
            self,
            "route",
            route if isinstance(route, IntentRoute) else default_intent_route(route),
        )
        object.__setattr__(self, "usage", usage or GatewayUsage())
        object.__setattr__(self, "diagnostics", diagnostics)

    @property
    def intent(self) -> Intent:
        return self.route.primary_intent


@dataclass(frozen=True, slots=True)
class TextResult:
    text: str
    usage: GatewayUsage = GatewayUsage()


@dataclass(frozen=True, slots=True)
class ModelTurnResult:
    """A completed model turn, before any requested tools have been executed."""

    message: ModelMessage
    finish_reason: Literal["stop", "tool_calls"]
    usage: GatewayUsage = GatewayUsage()
    usage_reported: bool = True


@dataclass(frozen=True, slots=True)
class TaskParametersResult:
    summary: str
    parameters: dict[str, JsonValue]
    warnings: tuple[str, ...] = ()
    usage: GatewayUsage = GatewayUsage()


class ModelGatewayError(RuntimeError):
    """Safe Provider error; message and code never contain credentials or response bodies."""

    def __init__(
        self,
        code: str,
        *,
        retryable: bool = False,
        usage: GatewayUsage | None = None,
        structured_response_failure: bool = False,
        failure_stage: str | None = None,
        attempt_count: int = 0,
    ) -> None:
        self.code = code
        self.retryable = retryable
        self.usage = usage or GatewayUsage()
        self.structured_response_failure = structured_response_failure
        self.failure_stage = failure_stage
        self.attempt_count = attempt_count
        super().__init__(code)


_DETERMINISTIC_INTENT_KEYWORDS: tuple[tuple[Intent, tuple[str, ...]], ...] = (
    (Intent.VIDEO, ("视频", "短片", "动画", "video")),
    (Intent.IMAGE, ("图片", "主图", "海报", "生图", "image", "photo")),
    (
        Intent.ARTICLE,
        ("文章", "标题", "提纲", "文案", "选购指南", "article", "copywriting"),
    ),
    (
        Intent.GEO,
        (
            "geo",
            "搜索优化",
            "搜索平台",
            "问题发现",
            "关键词",
            "问答优化",
            "用户问答",
            "可见度监测",
            "平台监测",
            "监测",
        ),
    ),
)
_NEGATION_PREFIXES = (
    "不要",
    "不需要",
    "不生成",
    "不制作",
    "不创建",
    "无需",
    "别",
    "停止",
    "取消",
    "不是",
)
_EXPLANATION_ONLY_MARKERS = (
    "只解释",
    "仅解释",
    "只说明",
    "仅说明",
    "只用一句话解释",
    "只用一句话说明",
    "只做文字问答",
    "只做文字回复",
    "只分析",
    "仅分析",
    "只给计划",
    "先给计划",
    "帮我规划",
    "先帮我规划",
    "不创建任务",
    "不要创建任务",
    "不要创建任何任务",
    "先不要创建",
    "不执行任何任务",
)
_ADVISORY_QUESTION_MARKERS = (
    "为什么",
    "怎么判断",
    "如何优化",
    "应该先",
    "还是先",
    "能否",
    "是什么意思",
    "是一回事吗",
    "怎么解释",
)


def _request_clauses(content: str) -> tuple[str, ...]:
    text = "".join(content.casefold().split())
    return tuple(filter(None, re.split(r"[，。；,;!?\uff01\uff1f]|然后|并且|同时|并", text)))


def requests_domain_execution(content: str) -> bool:
    """Block read/general shortcuts, never grant permission or choose an intent.

    Consultative and negated clauses are not commands. Uncertain positive task
    wording is deliberately left to the existing semantic router and validator.
    """
    keywords = tuple(word for _, words in _DETERMINISTIC_INTENT_KEYWORDS for word in words)
    consultation = ("怎么", "如何", "怎样", "解释", "了解", "讲讲", "哪里开始", "哪开始")
    for clause in _request_clauses(content):
        if any(word in clause for word in ("确认后", "等我确认", "没有报告时")):
            continue
        # A concrete repeat command can omit the domain carried by the previous
        # clause/turn. This only keeps it in semantic routing, never authorizes it.
        if re.fullmatch(
            r"(?:(?:请|现在|直接|帮我|替我|给我|我要|我想|再)+)?"
            r"(?:重跑|再跑|重新跑|跑)(?:一)?(?:次|遍|轮)(?:吧)?",
            clause,
        ):
            return True
        if not any(word in clause for word in keywords) and not re.search(
            r"张图|发文|检测|可见度", clause
        ):
            continue
        for action in re.finditer(
            r"监测|检测|生成|创建|制作|写|发布|投稿|重跑|更新|跑|做|来", clause
        ):
            prefix = clause[: action.start()]
            if any(word in prefix for word in ("不要", "不用", "不想", "先不", "别", "取消")):
                continue
            imperative = tuple(
                re.finditer(r"帮我|替我|给我|请|我要|我想|现在|立即|直接|马上", prefix)
            )
            governed_prefix = prefix[imperative[-1].start() :] if imperative else prefix
            # "view the previous monitoring report" is a read: the monitoring
            # noun is governed by "view", not by the remote "please/help me".
            if any(word in governed_prefix for word in (*consultation, "看", "查", "读", "分析")):
                continue
            if re.search(r"是什么|什么意思|什么叫|含义|定义", clause[action.end() :]):
                continue
            if not imperative and any(word in clause for word in consultation):
                continue
            if prefix in {"", "再", "先", "开始", "重新", "执行", "启动", "发起"} or imperative:
                return True
    return False


def is_geo_status_consultation(content: str) -> bool:
    """Keep assessment of completion/failed samples read-only, not new work."""
    if requests_domain_execution(content):
        return False
    text = "".join(content.casefold().split())
    if re.fullmatch(r"(?:是否完成|完成了[吗没]|(?:都|全部)?成功了[吗没])[?？]?", text):
        return True
    topic = re.search(r"geo|监测|采集|采样|样本|平台|可见度|任务", text)
    status = re.search(r"成功|失败|完成|超时|缺失|没做完", text)
    question = re.search(
        r"能算|算.{0,12}[吗么]|是否|是不是|有没有|意味着|代表|"
        r"怎么理解|如何理解|解读|解释|说明|为什么|怎么处理|如何处理|"
        r"完成了吗|完成了没|做完了吗|做完了没|成功了吗|成功了没",
        text,
    )
    return topic is not None and status is not None and question is not None


def is_geo_guidance_request(content: str) -> bool:
    """Recognize learning/planning speech, not a request for saved data or execution.

    This is a conservative no-tool boundary shared by the early resource gate and
    the graph. Ambiguous/mixed requests still go to the existing semantic router;
    a GEO topic noun alone is never evidence that the user requested a report.
    """
    text = "".join(content.casefold().split())
    if requests_domain_execution(content):
        return False
    # A positive concrete evidence reference wins over an explanatory clause.
    # Negating a previous report must not trap a topic switch in old candidates.
    for clause in _request_clauses(content):
        if re.search(r"不看|不查|不读|不要|不用|别|先不|取消", clause):
            continue
        if re.search(
            r"(?:这份|这张|这个|那份|那张|那个|上次|上一份|上期|本期|本次|这次|刚才|之前|最新|最近)"
            r".{0,12}(?:报告|结果|数据|回答|提及率|可见度|下降|降低|上升)|"
            r"(?:报告|结果|数据)(?:里|中|里面)|跟上次比|和上次比|比上次|"
            r"(?:查|看|读|分析).{0,16}(?:报告|结果|数据|可见度)",
            clause,
        ):
            return False
    topic = bool(re.search(r"geo|可见度|ai|监测|优化", text))
    concept = any(
        word in text
        for word in (
            "是什么",
            "什么是",
            "什么叫",
            "什么意思",
            "含义",
            "定义",
            "怎么计算",
            "如何计算",
            "怎么算",
            "解释",
            "讲讲",
            "介绍一下",
        )
    )
    starting = any(
        word in text
        for word in (
            "怎么开始",
            "如何开始",
            "怎样开始",
            "不知道怎么",
            "从哪里开始",
            "从哪开始",
            "从何入手",
            "怎么入手",
            "如何入手",
            "怎么入门",
            "先做什么",
        )
    )
    approach = any(
        word in text
        for word in (
            "怎么做",
            "如何做",
            "怎样做",
            "怎么提高",
            "如何提高",
            "怎么提升",
            "如何提升",
            "有什么办法",
            "应该怎么",
        )
    )
    discussion_only = any(
        word in text for word in ("先聊", "只聊", "聊聊", "先讨论", "只讨论")
    ) and any(
        word in text for word in ("不要监测", "不用监测", "别监测", "先不监测", "不执行", "别执行")
    )
    topic_switch = any(
        word in text
        for word in ("换个话题", "说点别的", "先聊别的", "不做这个了", "刚才说的不做了")
    )
    return (
        starting
        or discussion_only
        or topic_switch
        or (topic and (concept or approach))
        or is_geo_status_consultation(content)
    )


def deterministic_intent(content: str) -> Intent:
    """Conservatively classify explicit domain wording without performing a side effect."""
    normalized = content.casefold()
    if normalized.strip() in {"确认", "确定", "开始", "执行", "confirm", "go"}:
        return Intent.AMBIGUOUS_CONFIRMATION
    if is_geo_guidance_request(content):
        return Intent.GENERAL
    if any(
        marker in normalized for marker in (*_EXPLANATION_ONLY_MARKERS, *_ADVISORY_QUESTION_MARKERS)
    ):
        return Intent.GENERAL
    for intent, keywords in _DETERMINISTIC_INTENT_KEYWORDS:
        if intent_is_explicitly_negated(normalized, intent):
            continue
        if any(
            keyword in normalized and not _keyword_is_negated(normalized, keyword)
            for keyword in keywords
        ):
            return intent
    return Intent.GENERAL


_COMPLEX_ANALYSIS_MARKERS = (
    "综合",
    "对比",
    "比较",
    "结合",
    "多个",
    "多方面",
    "完整分析",
    "系统分析",
    "经营诊断",
    "竞品",
    "历史",
    "约束",
)
_PLAN_MARKERS = (
    "规划",
    "计划",
    "步骤",
    "先做什么",
    "怎么推进",
    "路线图",
)
_ANALYSIS_MARKERS = (
    "分析",
    "诊断",
    "为什么",
    "怎么提高",
    "如何优化",
    "建议",
)


def deterministic_intent_route(content: str) -> IntentRoute:
    """Provide the conservative offline/failure route without business side effects."""
    intent = deterministic_intent(content)
    if intent is not Intent.GENERAL:
        return default_intent_route(intent)
    normalized = content.casefold()
    complexity = (
        RouterComplexity.COMPLEX
        if sum(marker in normalized for marker in _COMPLEX_ANALYSIS_MARKERS) >= 2
        else RouterComplexity.STANDARD
        if any(marker in normalized for marker in (*_ANALYSIS_MARKERS, *_PLAN_MARKERS))
        else RouterComplexity.SIMPLE
    )
    decision = (
        RouterDecision.PLAN
        if any(marker in normalized for marker in _PLAN_MARKERS)
        else RouterDecision.ANALYZE
        if any(marker in normalized for marker in _ANALYSIS_MARKERS)
        else RouterDecision.ANSWER
    )
    return IntentRoute(
        schema_version="intent-router-v2.2",
        primary_intent=Intent.GENERAL,
        decision=decision,
        complexity=complexity,
        context_needs=(),
        reference_targets=(),
        requires_clarification=False,
        fallback_required=False,
        clarification=None,
        draft_operation=None,
    )


def intent_is_explicitly_negated(content: str, intent: Intent) -> bool:
    """Return true only when every explicit keyword for one domain is negated."""
    normalized = content.casefold()
    keywords = next(
        (values for candidate, values in _DETERMINISTIC_INTENT_KEYWORDS if candidate is intent),
        (),
    )
    present = tuple(keyword for keyword in keywords if keyword in normalized)
    if not present:
        return False
    negated = tuple(_keyword_is_negated(normalized, keyword) for keyword in present)
    if all(negated):
        return True
    # A user may name the same domain again while asking what the term means,
    # e.g. "不要写文章，只说明文章提纲的作用".  Once a task keyword is
    # explicitly negated, an explanation-only qualifier keeps the conservative
    # route in free text.  A positive replacement such as "改为给我提纲" has no
    # such qualifier and therefore remains eligible for normal classification.
    has_negated_mention = any(
        _keyword_has_negated_occurrence(normalized, keyword) for keyword in present
    )
    return has_negated_mention and any(marker in normalized for marker in _EXPLANATION_ONLY_MARKERS)


def _keyword_is_negated(content: str, keyword: str) -> bool:
    offset = content.find(keyword)
    while offset >= 0:
        prefix = content[max(0, offset - 8) : offset]
        if not any(marker in prefix for marker in _NEGATION_PREFIXES):
            return False
        offset = content.find(keyword, offset + len(keyword))
    return True


def _keyword_has_negated_occurrence(content: str, keyword: str) -> bool:
    offset = content.find(keyword)
    while offset >= 0:
        prefix = content[max(0, offset - 8) : offset]
        if any(marker in prefix for marker in _NEGATION_PREFIXES):
            return True
        offset = content.find(keyword, offset + len(keyword))
    return False


class ModelGateway(Protocol):
    @property
    def identity(self) -> ModelIdentity: ...

    @property
    def is_mock(self) -> bool: ...

    @property
    def status(self) -> ProviderStatus: ...

    @property
    def last_error_code(self) -> str | None: ...

    async def probe(self) -> bool: ...

    async def classify_intent(self, messages: ModelMessages) -> IntentResult: ...

    async def answer_general(
        self,
        messages: ModelMessages,
        *,
        on_delta: DeltaHandler | None = None,
    ) -> TextResult: ...

    async def suggest_task_parameters(
        self,
        intent: Intent,
        messages: ModelMessages,
    ) -> TaskParametersResult: ...

    async def aclose(self) -> None: ...


@runtime_checkable
class NativeToolCompletionGateway(Protocol):
    """Optional native Agent Loop capability; old gateways keep their existing contract."""

    async def complete_turn(
        self,
        messages: ModelMessages,
        *,
        tools: tuple[ModelToolDefinition, ...],
        max_tokens: int = 4096,
        thinking: bool = True,
        operation: str = "agent_loop",
    ) -> ModelTurnResult: ...


@runtime_checkable
class NativeStreamingCompletionGateway(Protocol):
    """Optional real native SSE capability; legacy gateways/mocks remain unchanged."""

    async def complete_turn_stream(
        self,
        messages: ModelMessages,
        *,
        tools: tuple[ModelToolDefinition, ...],
        on_delta: DeltaHandler,
        max_tokens: int = 4096,
        thinking: bool = True,
        operation: str = "agent_loop",
    ) -> ModelTurnResult: ...


@runtime_checkable
class StructuredCompletionGateway(Protocol):
    """Shared bounded structured-output capability used by domain kernels."""

    async def complete_structured(
        self,
        messages: ModelMessages,
        response_model: type[StructuredModel],
        *,
        operation: str,
        max_tokens: int,
        error_code: str,
        repair_instruction: str,
        repair_attempts: int = 1,
        on_delta: StructuredDeltaHandler | None = None,
    ) -> tuple[StructuredModel, GatewayUsage]: ...


class LocalModelGateway:
    """Safe offline implementation used by local tests and explicit mock mode."""

    identity = ModelIdentity(provider="LOCAL", model="deterministic-router", version="1")
    is_mock = True
    status = ProviderStatus.AVAILABLE
    last_error_code: str | None = None

    async def probe(self) -> bool:
        return True

    async def classify_intent(self, messages: ModelMessages) -> IntentResult:
        content = last_user_content(messages)
        # Offline fixtures classify the current turn, not metadata field names or
        # historical report contents inside the production Router envelope.
        marker = "<intent-router-envelope>"
        if marker in content:
            try:
                raw = json.loads(
                    content.split(marker, 1)[1].rsplit("</intent-router-envelope>", 1)[0]
                )
                envelope = cast(dict[str, object], raw)
                current = cast(dict[str, object], envelope["currentMessage"])
                current_content = current["content"]
                if not isinstance(current_content, str):
                    raise ValueError("invalid current message")
                content = current_content
            except (ValueError, KeyError, TypeError):
                raise ModelGatewayError("LOCAL_ROUTER_ENVELOPE_INVALID") from None
        return IntentResult(deterministic_intent_route(content))

    async def answer_general(
        self,
        messages: ModelMessages,
        *,
        on_delta: DeltaHandler | None = None,
    ) -> TextResult:
        del on_delta
        content = last_user_content(messages)
        if any(greeting in content.casefold() for greeting in ("你好", "hello", "hi")):
            return TextResult(
                "你好，我是光禾AI智能体框架 Agent。本地演示已就绪，"
                "可以帮你梳理 GEO、图片、视频或文章任务。"
            )
        return TextResult(
            "我已理解这条消息。当前是离线 Agent 演示环境；你可以描述 GEO、商品图片、"
            "视频或文章需求，我会判断是需要补充信息，还是生成一份待确认的任务草稿。"
        )

    async def suggest_task_parameters(
        self,
        intent: Intent,
        messages: ModelMessages,
    ) -> TaskParametersResult:
        content = last_user_content(messages)
        parameters: dict[str, JsonValue] = {}
        if intent is Intent.IMAGE:
            # Explicit offline fixture only; live creation uses the structured model plan.
            parameters["visualBrief"] = {
                "version": "commerce-image-v1",
                "platform": "GENERAL",
                "placement": "MAIN_IMAGE",
                "visualFocus": "本地演示: 商品完整展示",
                "composition": "主体完整居中，留出安全边距",
                "background": "干净浅色背景",
                "lighting": "柔和均匀照明",
                "textPolicy": "NONE",
                "copyLines": [],
            }
        if intent is Intent.GEO:
            topic = content.strip()[:100]
            parameters = {
                "topic": topic,
                "region": "全国",
                "mode": "QUESTION_DISCOVERY",
                "questions": [
                    f"哪些{topic}相关商品值得推荐？",
                    f"选择{topic}相关商品时应关注什么？",
                    f"什么场景适合使用{topic}相关商品？",
                ],
            }
        return TaskParametersResult(
            summary=f"已根据 {intent.value} 需求整理本地任务参数。",
            parameters=parameters,
        )

    async def aclose(self) -> None:
        return None
