"""Strict server-issued resource references and bounded read-only projections."""

from __future__ import annotations

import json
from typing import Annotated, Literal

from pydantic import (
    AfterValidator,
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    JsonValue,
    StrictBool,
    StrictInt,
    StrictStr,
    StringConstraints,
    model_validator,
)
from pydantic.alias_generators import to_camel
from pydantic.json_schema import SkipJsonSchema


def _nonblank(value: str) -> str:
    if not value.strip():
        raise ValueError("reference must not be blank")
    return value


ResourceId = Annotated[StrictStr, StringConstraints(pattern=r"^[1-9][0-9]{0,18}$")]
ResourceRef = Annotated[
    StrictStr, StringConstraints(min_length=1, max_length=128), AfterValidator(_nonblank)
]
ResourceName = Annotated[StrictStr, StringConstraints(min_length=1, max_length=200)]
ResourceKind = Literal["SPACE", "PRODUCT", "GEO_TOPIC"]
ReadPurpose = Literal[
    "CATALOG_SUMMARY",
    "LATEST_GEO_REPORT",
    "GEO_ANSWER_EVIDENCE",
    "ASSET_INVENTORY",
    "GEO_DIAGNOSTIC_REPORT",
]
ReportPolicy = Literal["LATEST_ATTEMPT", "LATEST_USABLE"]
SampleRef = Annotated[
    StrictStr, StringConstraints(min_length=1, max_length=200), AfterValidator(_nonblank)
]


class ResourceModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
        allow_inf_nan=False,
    )


class ResourceSearchRequest(ResourceModel):
    tool_call_id: ResourceRef
    kind: ResourceKind
    keyword: Annotated[ResourceName, AfterValidator(_nonblank)]
    scope_ref: ResourceId | SkipJsonSchema[None] = None


class ResourceCandidate(ResourceModel):
    choice_id: ResourceRef
    kind: ResourceKind
    resource_id: ResourceId
    name: ResourceName
    space_id: ResourceId | SkipJsonSchema[None] = None
    space_name: ResourceName | SkipJsonSchema[None] = None
    has_report: StrictBool
    exact: StrictBool


class ResourceSearchResponse(ResourceModel):
    candidate_set_id: ResourceId
    kind: ResourceKind
    status: Literal["UNIQUE", "MULTIPLE", "NOT_FOUND", "INCOMPLETE"]
    total_matches: StrictInt = Field(ge=0)
    complete: StrictBool
    candidates: tuple[ResourceCandidate, ...] = Field(max_length=6)
    expires_at: AwareDatetime

    @model_validator(mode="after")
    def validate_candidate_consistency(self) -> ResourceSearchResponse:
        if len({item.choice_id for item in self.candidates}) != len(self.candidates):
            raise ValueError("candidate choices must be unique")
        if any(item.kind != self.kind for item in self.candidates):
            raise ValueError("candidate kinds must match the search")
        if self.total_matches < len(self.candidates):
            raise ValueError("candidate count exceeds total matches")
        if self.status == "UNIQUE" and not (
            self.complete
            and self.total_matches == 1
            and len(self.candidates) == 1
            and self.candidates[0].exact
        ):
            raise ValueError("UNIQUE requires one complete exact match")
        if self.status == "NOT_FOUND" and not (
            self.complete and self.total_matches == 0 and not self.candidates
        ):
            raise ValueError("NOT_FOUND requires a complete empty result")
        if self.status == "INCOMPLETE" and self.complete:
            raise ValueError("INCOMPLETE cannot claim completeness")
        return self


class ResourceSelection(ResourceModel):
    candidate_set_id: ResourceId
    choice_id: ResourceRef


class FrozenResourceSelection(ResourceSelection):
    kind: ResourceKind
    resource_id: ResourceId
    name: ResourceName
    space_id: ResourceId
    product_id: ResourceId | None


class ResourceReadRequest(ResourceModel):
    tool_call_id: ResourceRef
    purpose: ReadPurpose
    candidate_set_id: ResourceId | SkipJsonSchema[None] = None
    choice_id: ResourceRef | SkipJsonSchema[None] = None
    observation_id: ResourceId | SkipJsonSchema[None] = None
    report_policy: ReportPolicy | SkipJsonSchema[None] = None
    sample_refs: tuple[SampleRef, ...] = Field(
        default=(), max_length=3, json_schema_extra={"uniqueItems": True}
    )

    @model_validator(mode="after")
    def validate_source_and_purpose(self) -> ResourceReadRequest:
        pair = self.candidate_set_id is not None and self.choice_id is not None
        if (self.candidate_set_id is None) != (self.choice_id is None):
            raise ValueError("candidateSetId and choiceId must be paired")
        if pair == (self.observation_id is not None):
            raise ValueError("read requires exactly one server-issued source")
        if self.report_policy is not None and self.purpose != "LATEST_GEO_REPORT":
            raise ValueError("reportPolicy only applies to report reads")
        if self.purpose == "GEO_ANSWER_EVIDENCE":
            if self.observation_id is None or not self.sample_refs:
                raise ValueError("evidence requires a frozen observation and sample refs")
        elif self.sample_refs:
            raise ValueError("sampleRefs only applies to evidence reads")
        if len(set(self.sample_refs)) != len(self.sample_refs):
            raise ValueError("sampleRefs must be unique")
        return self


class ResourceReportAttempt(ResourceModel):
    run_id: ResourceId
    status: Literal["QUEUED", "RUNNING", "SUCCEEDED", "PARTIAL", "FAILED", "SYSTEM_TERMINATED"]
    created_at: AwareDatetime
    newer_failed_attempt: StrictBool


class ResourceReadResponse(ResourceModel):
    observation_id: ResourceId
    kind: ResourceKind
    purpose: ReadPurpose
    resource_id: ResourceId
    name: ResourceName
    space_id: ResourceId
    product_id: ResourceId | SkipJsonSchema[None] = None
    observed_at: AwareDatetime
    source_hash: Annotated[StrictStr, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
    report_id: ResourceId | SkipJsonSchema[None] = None
    report_attempt: ResourceReportAttempt | SkipJsonSchema[None] = None
    projection: dict[StrictStr, JsonValue]
    truncated: StrictBool

    @model_validator(mode="after")
    def projection_is_bounded(self) -> ResourceReadResponse:
        encoded = json.dumps(
            self.projection, ensure_ascii=False, allow_nan=False, separators=(",", ":")
        ).encode()
        if len(encoded) > 16 * 1024:
            raise ValueError("resource projection exceeds 16KiB")
        if self.purpose == "GEO_ANSWER_EVIDENCE" and self.report_id is None:
            raise ValueError("evidence must bind a saved report")
        if (
            self.purpose in {"LATEST_GEO_REPORT", "GEO_ANSWER_EVIDENCE"}
            and self.kind != "GEO_TOPIC"
        ):
            raise ValueError("report purposes only bind GEO_TOPIC resources")
        if self.purpose == "GEO_DIAGNOSTIC_REPORT" and (
            self.kind != "SPACE"
            or self.resource_id != self.space_id
            or self.product_id is not None
            or self.report_id is not None
            or self.report_attempt is not None
        ):
            raise ValueError("Diagnostic reports bind a project, not ordinary GEO reports")
        return self
