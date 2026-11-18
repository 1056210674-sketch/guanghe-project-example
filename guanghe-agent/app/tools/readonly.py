"""Deterministic read-only tools over one Java-authorized Run context."""

from __future__ import annotations

import json
import re
from collections.abc import Callable, Mapping
from typing import Literal, cast

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from app.observability.tracing import safe_ref
from app.providers.java_context import AuthorizedRunContext


class ReadOnlyToolModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
        allow_inf_nan=False,
    )


CatalogEntity = Literal["brand", "space", "product", "competitors"]
WorkspaceField = Literal[
    "currentGoal",
    "currentIntent",
    "confirmedConstraints",
    "unresolvedQuestions",
    "pendingInteraction",
    "activeDraftId",
    "activeTaskId",
    "runningTasks",
    "referenceAssetIds",
]
ComparisonDimension = Literal[
    "aliases",
    "sellingPoints",
    "audience",
    "category",
    "parameters",
]


class CatalogInspectInput(ReadOnlyToolModel):
    entities: tuple[CatalogEntity, ...] = Field(min_length=1, max_length=4)
    max_items: int = Field(default=20, ge=1, le=20)


class CatalogFact(ReadOnlyToolModel):
    entity: CatalogEntity
    subject: str = Field(min_length=1, max_length=150)
    attribute: str = Field(min_length=1, max_length=64)
    value: str = Field(min_length=1, max_length=1_000)


class CatalogInspectOutput(ReadOnlyToolModel):
    facts: tuple[CatalogFact, ...] = Field(max_length=80)
    missing: tuple[CatalogEntity, ...] = Field(max_length=4)


class MemorySearchInput(ReadOnlyToolModel):
    query: str = Field(min_length=1, max_length=200)
    limit: int = Field(default=5, ge=1, le=10)


class MemoryMatch(ReadOnlyToolModel):
    message_ref: str = Field(pattern=r"^sha256:[0-9a-f]{16}$")
    sequence: int = Field(ge=0)
    role: Literal["USER", "ASSISTANT", "SUMMARY"]
    snippet: str = Field(min_length=1, max_length=240)


class MemorySearchOutput(ReadOnlyToolModel):
    matches: tuple[MemoryMatch, ...] = Field(max_length=10)
    missing: tuple[str, ...] = Field(default=(), max_length=4)


class TaskWorkspaceInspectInput(ReadOnlyToolModel):
    fields: tuple[WorkspaceField, ...] = Field(min_length=1, max_length=9)


class WorkspaceFact(ReadOnlyToolModel):
    field: WorkspaceField
    value: str = Field(min_length=1, max_length=1_000)


class TaskWorkspaceInspectOutput(ReadOnlyToolModel):
    facts: tuple[WorkspaceFact, ...] = Field(max_length=40)
    missing: tuple[WorkspaceField, ...] = Field(max_length=9)


class CompetitorCompareInput(ReadOnlyToolModel):
    dimensions: tuple[ComparisonDimension, ...] = Field(min_length=1, max_length=5)
    max_competitors: int = Field(default=10, ge=1, le=20)


class CompetitorComparison(ReadOnlyToolModel):
    competitor: str = Field(min_length=1, max_length=150)
    competitor_type: Literal["DIRECT", "INDIRECT", "SUBSTITUTE"]
    shared_signals: tuple[str, ...] = Field(max_length=20)
    product_only_signals: tuple[str, ...] = Field(max_length=20)
    competitor_only_signals: tuple[str, ...] = Field(max_length=20)


class CompetitorCompareOutput(ReadOnlyToolModel):
    product: str | None = Field(default=None, max_length=150)
    comparisons: tuple[CompetitorComparison, ...] = Field(max_length=20)
    missing: tuple[str, ...] = Field(default=(), max_length=4)
    caveat: Literal["仅比较当前授权资料中的文字字段，不代表销量、排名、市场份额或平台表现。"] = (
        "仅比较当前授权资料中的文字字段，不代表销量、排名、市场份额或平台表现。"
    )


class EvidenceAggregateInput(ReadOnlyToolModel):
    step_ids: tuple[str, ...] = Field(min_length=1, max_length=4)


class EvidenceAggregateOutput(ReadOnlyToolModel):
    facts: tuple[str, ...] = Field(max_length=30)
    inferences: tuple[str, ...] = Field(max_length=20)
    missing: tuple[str, ...] = Field(max_length=20)


class ReadOnlyToolError(RuntimeError):
    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


class ReadOnlyToolBindings:
    """Per-Run handlers; the Context and prior outputs never escape this Run."""

    def __init__(self, context: AuthorizedRunContext) -> None:
        self._context = context
        self._outputs: dict[str, BaseModel] = {}

    @property
    def handlers(self) -> Mapping[str, Callable[[BaseModel], BaseModel]]:
        return {
            "context.catalog.inspect": self.inspect_catalog,
            "context.memory.search": self.search_memory,
            "context.task_workspace.inspect": self.inspect_workspace,
            "analysis.competitor.compare": self.compare_competitors,
            "analysis.evidence.aggregate": self.aggregate_evidence,
        }

    def remember(self, step_id: str, output: BaseModel) -> None:
        self._outputs[step_id] = output

    def inspect_catalog(self, raw: BaseModel) -> CatalogInspectOutput:
        request = CatalogInspectInput.model_validate(raw)
        facts: list[CatalogFact] = []
        missing: list[CatalogEntity] = []
        for entity in request.entities:
            if entity == "space":
                if self._context.space is None:
                    missing.append(entity)
                else:
                    facts.append(
                        CatalogFact(
                            entity=entity,
                            subject="当前经营空间",
                            attribute="name",
                            value=self._context.space.name,
                        )
                    )
            elif entity == "brand":
                if self._context.space is None:
                    missing.append(entity)
                else:
                    brand = self._context.space.brand
                    facts.extend(
                        _catalog_values(
                            entity,
                            brand.name,
                            brand.model_dump(mode="json", by_alias=True),
                            (
                                "name",
                                "aliases",
                                "description",
                                "tone",
                                "audience",
                                "sellingPoints",
                                "prohibitedClaims",
                            ),
                        )
                    )
            elif entity == "product":
                if self._context.product is None:
                    missing.append(entity)
                else:
                    product = self._context.product
                    facts.extend(
                        _catalog_values(
                            entity,
                            product.name,
                            product.model_dump(mode="json", by_alias=True),
                            (
                                "name",
                                "aliases",
                                "category",
                                "modelSpec",
                                "sellingPoints",
                                "parameters",
                                "audience",
                                "usageScenarios",
                                "prohibitedClaims",
                            ),
                        )
                    )
            else:
                if not self._context.competitors:
                    missing.append(entity)
                for competitor in self._context.competitors[: request.max_items]:
                    facts.extend(
                        _catalog_values(
                            entity,
                            competitor.name,
                            competitor.model_dump(mode="json", by_alias=True),
                            ("name", "aliases", "type", "description", "status"),
                        )
                    )
        return CatalogInspectOutput(
            facts=tuple(facts[: request.max_items * 6]), missing=tuple(missing)
        )

    def search_memory(self, raw: BaseModel) -> MemorySearchOutput:
        request = MemorySearchInput.model_validate(raw)
        memory = self._context.conversation_memory
        if memory is None:
            return MemorySearchOutput(matches=(), missing=("conversationMemory",))
        terms = _query_terms(request.query)
        matches: list[MemoryMatch] = []
        for message in reversed(memory.messages):
            if message.role not in {"USER", "ASSISTANT"}:
                continue
            text = message.content.get("text")
            if not isinstance(text, str) or not text.strip():
                continue
            normalized = text.casefold()
            if terms and not any(term in normalized for term in terms):
                continue
            matches.append(
                MemoryMatch(
                    message_ref=safe_ref(message.message_id),
                    sequence=message.sequence,
                    role=cast(Literal["USER", "ASSISTANT"], message.role),
                    snippet=_snippet(text),
                )
            )
            if len(matches) >= request.limit:
                break
        summary = memory.summary
        if len(matches) < request.limit and summary is not None:
            normalized_summary = summary.text.casefold()
            if not terms or any(term in normalized_summary for term in terms):
                matches.append(
                    MemoryMatch(
                        message_ref=safe_ref(f"summary:{summary.version}"),
                        sequence=memory.summary_through_sequence,
                        role="SUMMARY",
                        snippet=_snippet(summary.text),
                    )
                )
        matches.sort(key=lambda item: item.sequence)
        missing = () if matches else ("matchingConversationMemory",)
        return MemorySearchOutput(matches=tuple(matches[: request.limit]), missing=missing)

    def inspect_workspace(self, raw: BaseModel) -> TaskWorkspaceInspectOutput:
        request = TaskWorkspaceInspectInput.model_validate(raw)
        workspace = self._context.task_workspace
        if workspace is None:
            return TaskWorkspaceInspectOutput(facts=(), missing=request.fields)
        payload = workspace.model_dump(mode="json", by_alias=True)
        facts: list[WorkspaceFact] = []
        missing: list[WorkspaceField] = []
        for field in request.fields:
            value = payload.get(field)
            if value in (None, "", [], {}):
                missing.append(field)
                continue
            if field in {"activeDraftId", "activeTaskId", "referenceAssetIds"}:
                value = _safe_id_value(value)
            elif field == "runningTasks":
                value = [
                    {
                        "taskRef": safe_ref(item["taskId"]),
                        "taskType": item["taskType"],
                        "status": item["status"],
                        "stageCode": item["stageCode"],
                    }
                    for item in value
                ]
            facts.append(WorkspaceFact(field=field, value=_compact(value)))
        return TaskWorkspaceInspectOutput(facts=tuple(facts), missing=tuple(missing))

    def compare_competitors(self, raw: BaseModel) -> CompetitorCompareOutput:
        request = CompetitorCompareInput.model_validate(raw)
        product = self._context.product
        if product is None:
            return CompetitorCompareOutput(product=None, comparisons=(), missing=("product",))
        if not self._context.competitors:
            return CompetitorCompareOutput(
                product=product.name, comparisons=(), missing=("competitors",)
            )
        product_signals = _comparison_signals(
            product.model_dump(mode="json", by_alias=True), request.dimensions
        )
        comparisons: list[CompetitorComparison] = []
        for competitor in self._context.competitors[: request.max_competitors]:
            competitor_payload = competitor.model_dump(mode="json", by_alias=True)
            competitor_signals = _comparison_signals(competitor_payload, request.dimensions)
            comparisons.append(
                CompetitorComparison(
                    competitor=competitor.name,
                    competitor_type=competitor.type,
                    shared_signals=tuple(sorted(product_signals & competitor_signals))[:20],
                    product_only_signals=tuple(sorted(product_signals - competitor_signals))[:20],
                    competitor_only_signals=tuple(sorted(competitor_signals - product_signals))[
                        :20
                    ],
                )
            )
        return CompetitorCompareOutput(product=product.name, comparisons=tuple(comparisons))

    def aggregate_evidence(self, raw: BaseModel) -> EvidenceAggregateOutput:
        request = EvidenceAggregateInput.model_validate(raw)
        facts: list[str] = []
        inferences: list[str] = []
        missing: list[str] = []
        for step_id in request.step_ids:
            output = self._outputs.get(step_id)
            if output is None:
                missing.append(f"{step_id}:observation")
                continue
            payload = output.model_dump(mode="json", by_alias=True)
            for item in payload.get("missing", []):
                missing.append(f"{step_id}:{item}")
            if isinstance(output, CompetitorCompareOutput):
                for comparison in output.comparisons:
                    inferences.append(_compact(comparison.model_dump(mode="json", by_alias=True)))
            else:
                values = payload.get("facts", payload.get("matches", []))
                for value in values:
                    facts.append(_compact(value))
        return EvidenceAggregateOutput(
            facts=tuple(facts[:30]),
            inferences=tuple(inferences[:20]),
            missing=tuple(dict.fromkeys(missing))[:20],
        )


def identity_input(payload: BaseModel) -> BaseModel:
    return payload


def _catalog_values(
    entity: CatalogEntity, subject: str, payload: dict[str, object], fields: tuple[str, ...]
) -> list[CatalogFact]:
    result: list[CatalogFact] = []
    for field in fields:
        value = payload.get(field)
        if value in (None, "", [], {}):
            continue
        result.append(
            CatalogFact(entity=entity, subject=subject, attribute=field, value=_compact(value))
        )
    return result


def _query_terms(query: str) -> tuple[str, ...]:
    normalized = query.casefold()
    chunks = re.findall(r"[a-z0-9_]{2,}|[\u4e00-\u9fff]{2,}", normalized)
    terms: list[str] = []
    for chunk in chunks:
        terms.append(chunk)
        if any("\u4e00" <= character <= "\u9fff" for character in chunk):
            terms.extend(chunk[index : index + 2] for index in range(len(chunk) - 1))
    return tuple(dict.fromkeys(terms))


def _snippet(value: str) -> str:
    normalized = " ".join(value.split())
    return normalized if len(normalized) <= 240 else f"{normalized[:237]}..."


def _compact(value: object) -> str:
    if isinstance(value, str):
        rendered = value
    else:
        rendered = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
    return rendered if len(rendered) <= 1_000 else f"{rendered[:997]}..."


def _safe_id_value(value: object) -> object:
    if isinstance(value, str):
        return safe_ref(value)
    if isinstance(value, list):
        return [safe_ref(item) for item in cast(list[object], value)]
    return value


def _comparison_signals(
    payload: dict[str, object], dimensions: tuple[ComparisonDimension, ...]
) -> set[str]:
    signals: set[str] = set()
    mapping = {
        "aliases": "aliases",
        "sellingPoints": "sellingPoints",
        "audience": "audience",
        "category": "category",
        "parameters": "parameters",
    }
    for dimension in dimensions:
        value = payload.get(mapping[dimension])
        if isinstance(value, str) and value.strip():
            signals.add(value.strip())
        elif isinstance(value, list):
            items = cast(list[object], value)
            signals.update(str(item).strip() for item in items if str(item).strip())
        elif isinstance(value, dict):
            mapping_value = cast(dict[object, object], value)
            signals.update(f"{key}={item}" for key, item in mapping_value.items())
    return signals


__all__ = [
    "CatalogInspectInput",
    "CatalogInspectOutput",
    "CompetitorCompareInput",
    "CompetitorCompareOutput",
    "EvidenceAggregateInput",
    "EvidenceAggregateOutput",
    "MemorySearchInput",
    "MemorySearchOutput",
    "ReadOnlyToolBindings",
    "TaskWorkspaceInspectInput",
    "TaskWorkspaceInspectOutput",
    "identity_input",
]
