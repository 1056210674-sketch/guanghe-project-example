"""Immutable Prompt Bundle schema and cross-reference validation."""

from typing import Literal, Self

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic.alias_generators import to_camel

from app.common.enums import TaskType

ArticleStageCode = Literal["TITLE", "OUTLINE", "BODY", "REWRITE"]
_ARTICLE_STAGE_CODES: tuple[ArticleStageCode, ...] = ("TITLE", "OUTLINE", "BODY", "REWRITE")


class BundleModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
    )


class PromptDefinition(BundleModel):
    code: str = Field(min_length=1, max_length=64, pattern=r"^[A-Z][A-Z0-9_]*$")
    version: str = Field(min_length=1, max_length=32)
    system: str = Field(min_length=1, max_length=16_384)


class ModelPolicy(BundleModel):
    code: str = Field(min_length=1, max_length=64, pattern=r"^[A-Z][A-Z0-9_]*$")
    provider: str = Field(min_length=1, max_length=64, pattern=r"^[A-Z][A-Z0-9_]*$")
    max_model_calls: int = Field(ge=1, le=16)
    max_input_tokens: int = Field(ge=1, le=128_000)


class ToolRetryPolicy(BundleModel):
    max_attempts: int = Field(ge=1, le=4)
    backoff_ms: int = Field(ge=0, le=10_000)


class ToolCost(BundleModel):
    units: int = Field(ge=0, le=1_000)


class ToolDefinition(BundleModel):
    name: str = Field(min_length=1, max_length=128, pattern=r"^[a-z][a-z0-9_.-]*$")
    version: str = Field(min_length=1, max_length=32)
    description: str = Field(min_length=1, max_length=500)
    input_model: str = Field(min_length=1, max_length=128, pattern=r"^[A-Z][A-Za-z0-9]+$")
    output_model: str = Field(min_length=1, max_length=128, pattern=r"^[A-Z][A-Za-z0-9]+$")
    tool_group: str = Field(min_length=1, max_length=64, pattern=r"^[A-Z][A-Z0-9_]*$")
    tool_type: Literal["JAVA_PROXY", "MODEL_PROVIDER", "PURE_FUNCTION"]
    risk_level: Literal["QUERY", "ANALYZE", "PREPARE", "COMMIT"]
    execution_mode: Literal["INLINE", "ASYNC_TASK"]
    timeout_seconds: float = Field(gt=0, le=120)
    retry: ToolRetryPolicy
    cost: ToolCost
    confirmation_policy: Literal["NONE", "JAVA_REQUIRED"]


class AgentProfile(BundleModel):
    code: str = Field(min_length=1, max_length=64, pattern=r"^[A-Z][A-Z0-9_]*$")
    version: str = Field(min_length=1, max_length=32)
    graph_code: str = Field(min_length=1, max_length=64)
    graph_version: str = Field(min_length=1, max_length=32)
    prompt_code: str
    prompt_layer_codes: tuple[str, ...] = ()
    general_response_prompt_code: str | None = None
    general_response_review_prompt_code: str | None = None
    intent_prompt_code: str | None = None
    task_prompt_code: str | None = None
    readonly_planner_prompt_code: str | None = None
    readonly_synthesis_prompt_code: str | None = None
    geo_readonly_selector_prompt_code: str | None = None
    resource_lookup_prompt_code: str | None = None
    domain_playbook_prompt_codes: dict[TaskType, str] = Field(
        default_factory=lambda: dict[TaskType, str]()
    )
    article_stage_prompt_codes: dict[ArticleStageCode, str] = Field(
        default_factory=lambda: dict[ArticleStageCode, str]()
    )
    execution_prompt_codes: dict[TaskType, str] = Field(
        default_factory=lambda: dict[TaskType, str]()
    )
    model_policy_codes: tuple[str, ...] = Field(min_length=1)
    tool_names: tuple[str, ...] = ()
    task_types: tuple[TaskType, ...] = Field(min_length=1)

    @field_validator("task_types")
    @classmethod
    def reject_media(cls, value: tuple[TaskType, ...]) -> tuple[TaskType, ...]:
        if TaskType.MEDIA in value:
            raise ValueError("MEDIA is owned by Java and cannot be an Agent profile capability")
        return value


class AgentBundle(BundleModel):
    schema_version: int = Field(ge=1, le=1)
    bundle_version: str = Field(
        min_length=1,
        max_length=64,
        pattern=r"^[a-z0-9][a-z0-9._-]*$",
    )
    profiles: tuple[AgentProfile, ...] = Field(min_length=1)
    prompts: tuple[PromptDefinition, ...] = Field(min_length=1)
    model_policies: tuple[ModelPolicy, ...] = Field(min_length=1)
    tools: tuple[ToolDefinition, ...] = ()

    @model_validator(mode="after")
    def validate_references(self) -> Self:
        prompt_codes = _unique_codes("prompt", tuple(item.code for item in self.prompts))
        policy_codes = _unique_codes(
            "model policy", tuple(item.code for item in self.model_policies)
        )
        tool_names = _unique_codes("tool", tuple(item.name for item in self.tools))
        _unique_codes("profile", tuple(item.code for item in self.profiles))

        for profile in self.profiles:
            if profile.prompt_code not in prompt_codes:
                raise ValueError(f"unknown prompt reference: {profile.prompt_code}")
            optional_prompt_codes = tuple(
                code
                for code in (
                    *profile.prompt_layer_codes,
                    profile.general_response_prompt_code,
                    profile.general_response_review_prompt_code,
                    profile.intent_prompt_code,
                    profile.task_prompt_code,
                    profile.readonly_planner_prompt_code,
                    profile.readonly_synthesis_prompt_code,
                    profile.geo_readonly_selector_prompt_code,
                    profile.resource_lookup_prompt_code,
                    *profile.domain_playbook_prompt_codes.values(),
                    *profile.article_stage_prompt_codes.values(),
                    *profile.execution_prompt_codes.values(),
                )
                if code is not None
            )
            unknown_prompts = set(optional_prompt_codes) - prompt_codes
            if unknown_prompts:
                raise ValueError(f"unknown prompt references: {sorted(unknown_prompts)}")
            if profile.graph_code == "commerce-assistant" and (
                profile.intent_prompt_code is None
                or profile.task_prompt_code is None
                or profile.readonly_planner_prompt_code is None
                or profile.readonly_synthesis_prompt_code is None
                or profile.geo_readonly_selector_prompt_code is None
            ):
                raise ValueError(
                    "commerce-assistant requires intent, task, planner, and synthesis prompts"
                )
            if profile.graph_code == "commerce-assistant" and set(
                profile.domain_playbook_prompt_codes
            ) != set(profile.task_types):
                raise ValueError(
                    "commerce-assistant requires exactly one playbook for each task type"
                )
            if (
                profile.graph_code == "commerce-assistant"
                and TaskType.ARTICLE in profile.task_types
                and set(profile.article_stage_prompt_codes) != set(_ARTICLE_STAGE_CODES)
            ):
                raise ValueError(
                    "commerce-assistant requires exactly one prompt for each article stage"
                )
            if profile.graph_code == "commerce-assistant" and set(
                profile.execution_prompt_codes
            ) != {TaskType.GEO, TaskType.IMAGE, TaskType.VIDEO}:
                raise ValueError(
                    "commerce-assistant requires GEO, IMAGE, and VIDEO execution prompts"
                )
            unknown_policies = set(profile.model_policy_codes) - policy_codes
            if unknown_policies:
                raise ValueError(f"unknown model policy references: {sorted(unknown_policies)}")
            unknown_tools = set(profile.tool_names) - tool_names
            if unknown_tools:
                raise ValueError(f"unknown tool references: {sorted(unknown_tools)}")
        return self


def _unique_codes(label: str, values: tuple[str, ...]) -> set[str]:
    unique = set(values)
    if len(unique) != len(values):
        raise ValueError(f"duplicate {label} code")
    return unique
