"""Bounded analysis of Java-frozen, already persisted GEO evidence; never sampling."""

import asyncio
import json
import logging
from datetime import UTC, datetime, timedelta
from typing import cast

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictInt,
    field_validator,
    model_validator,
)
from pydantic.alias_generators import to_camel

from app.geo.models import (
    GeoObservation,
    GeoObservationStatus,
    GeoSynthesis,
    GeoVisibilityEvidence,
)
from app.geo.models import (
    GeoVisibilityEvidenceUnit as GeoVisibilityEvidenceUnit,
)
from app.geo.runtime import GeoSynthesisError, GeoSynthesizer
from app.geo.synthesis_grounding import validate_visibility_evidence
from app.geo.worker_models import GeoSynthesisContext, GeoSynthesisUsage
from app.harness.runtime import HarnessViolation, bind_model_call_boundary
from app.model_gateway.gateway import ModelGateway, ModelGatewayError, StructuredCompletionGateway
from app.observability.tracing import start_span

_logger = logging.getLogger(__name__)


class _RecoveryWire(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
        strict=True,
        hide_input_in_errors=True,
    )


class GeoEvidenceProjection(_RecoveryWire):
    answer_char_limit: StrictInt = Field(ge=1, le=6000)
    max_citations_per_observation: StrictInt = Field(ge=3, le=3)
    truncated_answer_count: StrictInt = Field(ge=0, le=210)
    omitted_citation_count: StrictInt = Field(ge=0, le=21000)


class GeoSynthesisRecoveryRequest(_RecoveryWire):
    job_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,128}$")
    attempt: StrictInt = Field(ge=1, le=2)
    input_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    deadline_at: datetime = Field(strict=False)
    max_provider_calls: StrictInt = Field(ge=2, le=2)
    authorized_context: GeoSynthesisContext
    observations: tuple[GeoObservation, ...] = Field(min_length=1, max_length=210)
    evidence_projection: GeoEvidenceProjection
    visibility_evidence: GeoVisibilityEvidence

    @field_validator("deadline_at", mode="before")
    @classmethod
    def require_iso_timestamp(cls, value: object) -> object:
        if not isinstance(value, (str, datetime)):
            raise ValueError("deadlineAt must be an ISO timestamp")
        return value

    @field_validator("observations", mode="before")
    @classmethod
    def decode_saved_observations(cls, value: object) -> object:
        if not isinstance(value, (list, tuple)):
            return value
        # JSON-origin enums and timezone timestamps use the existing strict wire parser.
        return tuple(
            GeoObservation.model_validate_json(json.dumps(item)) if isinstance(item, dict) else item
            for item in cast(list[object] | tuple[object, ...], value)
        )

    @model_validator(mode="after")
    def require_authorized_evidence(self) -> "GeoSynthesisRecoveryRequest":
        if self.deadline_at.tzinfo is None or self.deadline_at.utcoffset() is None:
            raise ValueError("deadlineAt must have a timezone")
        allowed = {question.id for question in self.authorized_context.questions}
        keys = [
            (item.question_id, item.platform_code, item.sample_no) for item in self.observations
        ]
        if len(set(keys)) != len(keys):
            raise ValueError("observation units must be unique")
        if any(
            item.question_id not in allowed or item.provider != item.platform_code.value
            for item in self.observations
        ):
            raise ValueError("observation identity must match the authorized context")
        if not any(
            item.status is GeoObservationStatus.SUCCEEDED and (item.answer_text or "").strip()
            for item in self.observations
        ):
            raise ValueError("at least one saved effective answer is required")
        if any(
            len(item.answer_text or "") > self.evidence_projection.answer_char_limit
            or len(item.citations) > 3
            for item in self.observations
        ):
            raise ValueError("evidence exceeds declared projection limits")
        if self.evidence_projection.truncated_answer_count > sum(
            item.status is GeoObservationStatus.SUCCEEDED for item in self.observations
        ):
            raise ValueError("truncated answers exceed successful observations")
        validate_visibility_evidence(
            self.observations, self.authorized_context, self.visibility_evidence
        )
        if len(self.model_dump_json(by_alias=True).encode("utf-8")) > 900 * 1024:
            raise ValueError("evidence request exceeds 900 KiB")
        return self


class GeoSynthesisRecoveryResponse(_RecoveryWire):
    job_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,128}$")
    attempt: StrictInt = Field(ge=1, le=2)
    input_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    synthesis: GeoSynthesis
    synthesis_usage: GeoSynthesisUsage


async def regenerate_synthesis(
    payload: GeoSynthesisRecoveryRequest,
    gateway: ModelGateway,
    *,
    received_at: datetime,
    execution_prompt: str | None = None,
) -> GeoSynthesisRecoveryResponse:
    # UNAVAILABLE is the last request's health, not a permanent circuit breaker.
    # Java authorizes each bounded recovery attempt; a transient timeout must not
    # force an application restart before that next attempt can run.
    if (
        gateway.identity.provider != "DEEPSEEK"
        or gateway.is_mock
        or not isinstance(gateway, StructuredCompletionGateway)
    ):
        raise GeoSynthesisError("GEO_SYNTHESIS_MODEL_UNAVAILABLE")
    deadline = min(payload.deadline_at, received_at + timedelta(seconds=60))
    remaining_seconds = (deadline - datetime.now(UTC)).total_seconds()
    if remaining_seconds <= 0:
        raise GeoSynthesisError("GEO_SYNTHESIS_DEADLINE_EXCEEDED")
    with start_span(
        "geo.synthesis.recovery",
        attributes={
            "attemptCount": payload.attempt,
            "geo.observationCount": len(payload.observations),
            "model.name": gateway.identity.model,
        },
    ) as span:
        try:
            with bind_model_call_boundary(max_calls=2, deadline_at=deadline):
                async with asyncio.timeout(remaining_seconds):
                    synthesis, usage = await GeoSynthesizer(
                        gateway, execution_prompt=execution_prompt
                    ).synthesize_with_usage(
                        payload.observations,
                        authorized_context=payload.authorized_context,
                        evidence_projection=payload.evidence_projection,
                        visibility_evidence=payload.visibility_evidence,
                    )
        except TimeoutError as exc:
            span.fail("GEO_SYNTHESIS_DEADLINE_EXCEEDED")
            raise GeoSynthesisError("GEO_SYNTHESIS_DEADLINE_EXCEEDED") from exc
        except (GeoSynthesisError, ModelGatewayError, HarnessViolation) as exc:
            span.fail(exc.code)
            _logger.warning(
                "geo_synthesis_recovery_failed",
                extra={
                    "stableErrorCode": exc.code,
                    "attemptCount": payload.attempt,
                    "operation": "geo_synthesis",
                    "failureStage": getattr(exc, "failure_stage", None),
                },
            )
            raise
        span.set("usage.inputTokens", usage.input_tokens)
        span.set("usage.outputTokens", usage.output_tokens)
        span.set("usage.requestCount", usage.request_count)
        return GeoSynthesisRecoveryResponse(
            job_id=payload.job_id,
            attempt=payload.attempt,
            input_hash=payload.input_hash,
            synthesis=synthesis,
            synthesis_usage=GeoSynthesisUsage.from_gateway(gateway.identity.model, usage),
        )
