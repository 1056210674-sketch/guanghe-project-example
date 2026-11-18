"""Worker control-plane boundary and local-only deterministic implementation."""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from typing import TYPE_CHECKING, Literal, Protocol
from uuid import uuid4

from pydantic import JsonValue

from app.common.enums import TaskType
from app.creative.models import CreativeReferenceAsset, ProductCreativeBrief
from app.providers.models import (
    SubmissionState,
    TaskProviderContinuation,
    TaskProviderResult,
)
from app.workers.models import TaskExecutionMessage

if TYPE_CHECKING:
    from app.article.worker_models import ArticleCompletion, ArticleExecutionContext
    from app.geo.models import GeoObservation, GeoVisibilityEvidence
    from app.geo.worker_models import (
        GeoCompletion,
        GeoExecutionContext,
        GeoObservationDisposition,
    )

JavaTaskStatus = Literal[
    "RUNNING",
    "RETRY_WAIT",
    "SUCCEEDED",
    "PARTIAL",
    "FAILED",
    "SYSTEM_TERMINATED",
]


class ClaimDisposition(StrEnum):
    CLAIMED = "CLAIMED"
    DUPLICATE = "DUPLICATE"
    TERMINAL = "TERMINAL"


@dataclass(frozen=True, slots=True)
class ClaimResult:
    disposition: ClaimDisposition
    execution_no: int | None = None
    execution_token: str | None = None
    lease_expires_at: datetime | None = None


@dataclass(frozen=True, slots=True)
class ExecutionContext:
    task_id: str
    task_type: TaskType
    execution_no: int
    parameters: dict[str, JsonValue]
    creative_brief: ProductCreativeBrief | None = None
    reference_assets: tuple[CreativeReferenceAsset, ...] = ()
    controlled_mask_asset: CreativeReferenceAsset | None = None
    provider: str | None = None
    model: str | None = None
    max_provider_calls: int | None = None
    max_images: int | None = None
    max_videos: int | None = None
    max_video_seconds: int | None = None
    deadline_at: datetime | None = None
    resume_only: bool = False
    provider_continuation: TaskProviderContinuation | None = None


class ControlPlaneUnavailable(RuntimeError):
    def __init__(self, code: str = "JAVA_CONTROL_PLANE_UNAVAILABLE") -> None:
        self.code = _safe_error_code(code, "JAVA_CONTROL_PLANE_UNAVAILABLE")
        super().__init__(self.code)


class ControlPlaneRejected(RuntimeError):
    def __init__(self, code: str = "JAVA_CONTROL_PLANE_REJECTED") -> None:
        self.code = _safe_error_code(code, "JAVA_CONTROL_PLANE_REJECTED")
        super().__init__(self.code)


class ControlPlaneConfigurationError(RuntimeError):
    def __init__(self, code: str = "JAVA_CONTROL_PLANE_CONFIGURATION_UNAVAILABLE") -> None:
        self.code = _safe_error_code(code, "JAVA_CONTROL_PLANE_CONFIGURATION_UNAVAILABLE")
        super().__init__(self.code)


class WorkerControlPlane(Protocol):
    async def probe(self) -> bool: ...

    async def claim(
        self,
        message: TaskExecutionMessage,
        *,
        consumer_name: str,
        worker_id: str,
        bundle_version: str,
    ) -> ClaimResult: ...

    async def get_execution_context(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        traceparent: str,
    ) -> ExecutionContext | GeoExecutionContext | ArticleExecutionContext: ...

    async def heartbeat(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        stage_code: str,
        traceparent: str,
    ) -> None: ...

    async def report_progress(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        stage_code: str,
        traceparent: str,
    ) -> None: ...

    async def report_result(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        result: TaskProviderResult,
        traceparent: str,
    ) -> JavaTaskStatus: ...

    async def report_failure(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        code: str,
        submission_state: SubmissionState,
        traceparent: str,
        provider: str | None = None,
        model: str | None = None,
        provider_request_id: str | None = None,
        provider_task_id: str | None = None,
    ) -> None: ...

    async def list_geo_observations(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        traceparent: str,
    ) -> tuple[GeoObservation, ...]: ...

    async def report_geo_observation(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        observation: GeoObservation,
        traceparent: str,
    ) -> GeoObservationDisposition: ...

    async def get_geo_visibility_evidence(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        traceparent: str,
    ) -> GeoVisibilityEvidence: ...

    async def report_geo_completion(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        completion: GeoCompletion,
        traceparent: str,
    ) -> JavaTaskStatus: ...

    async def report_article_preview(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        attempt: int,
        sequence: int,
        markdown: str,
        traceparent: str,
    ) -> None: ...

    async def report_article_completion(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        completion: ArticleCompletion,
        traceparent: str,
    ) -> JavaTaskStatus: ...

    async def aclose(self) -> None: ...


class LocalWorkerControlPlane:
    """Ephemeral local/test Stub; never a replacement for Java business truth."""

    def __init__(self) -> None:
        self._contexts: dict[str, tuple[TaskType, dict[str, JsonValue]]] = {}
        self._claims: dict[str, ClaimResult] = {}
        self._results: dict[str, TaskProviderResult] = {}
        self._failures: dict[str, tuple[str, SubmissionState]] = {}
        self.audit: list[str] = []

    async def probe(self) -> bool:
        return True

    def register_context(
        self,
        task_id: str,
        task_type: TaskType,
        parameters: dict[str, JsonValue],
    ) -> None:
        self._contexts[task_id] = (task_type, dict(parameters))

    async def claim(
        self,
        message: TaskExecutionMessage,
        *,
        consumer_name: str,
        worker_id: str,
        bundle_version: str,
    ) -> ClaimResult:
        del consumer_name, worker_id, bundle_version
        self.audit.append(f"claim:{message.event_id}")
        existing = self._claims.get(message.event_id)
        if existing is not None:
            return ClaimResult(disposition=ClaimDisposition.DUPLICATE)
        token = f"local-only-{uuid4()}"
        claimed = ClaimResult(
            disposition=ClaimDisposition.CLAIMED,
            execution_no=message.attempt,
            execution_token=token,
            lease_expires_at=datetime.now(UTC) + timedelta(minutes=2),
        )
        self._claims[message.event_id] = claimed
        return claimed

    async def get_execution_context(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        traceparent: str,
    ) -> ExecutionContext:
        del execution_token, traceparent
        self.audit.append(f"context:{task_id}")
        try:
            task_type, parameters = self._contexts[task_id]
        except KeyError as exc:
            raise ControlPlaneUnavailable("Local execution context is missing") from exc
        return ExecutionContext(
            task_id=task_id,
            task_type=task_type,
            execution_no=execution_no,
            parameters=dict(parameters),
        )

    async def heartbeat(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        stage_code: str,
        traceparent: str,
    ) -> None:
        del execution_no, execution_token, stage_code, traceparent
        self.audit.append(f"heartbeat:{task_id}")

    async def report_progress(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        stage_code: str,
        traceparent: str,
    ) -> None:
        del execution_no, execution_token, stage_code, traceparent
        self.audit.append(f"progress:{task_id}")

    async def report_result(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        result: TaskProviderResult,
        traceparent: str,
    ) -> JavaTaskStatus:
        del execution_no, execution_token, traceparent
        self.audit.append(f"result:{task_id}")
        self._results[task_id] = result
        return "SUCCEEDED"

    async def report_failure(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        code: str,
        submission_state: SubmissionState,
        traceparent: str,
        provider: str | None = None,
        model: str | None = None,
        provider_request_id: str | None = None,
        provider_task_id: str | None = None,
    ) -> None:
        del (
            execution_no,
            execution_token,
            provider,
            model,
            provider_request_id,
            provider_task_id,
            traceparent,
        )
        self.audit.append(f"failure:{task_id}")
        self._failures[task_id] = (code, submission_state)

    async def list_geo_observations(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        traceparent: str,
    ) -> tuple[GeoObservation, ...]:
        del task_id, execution_no, execution_token, traceparent
        raise ControlPlaneConfigurationError("LOCAL_GEO_CONTROL_PLANE_NOT_CONFIGURED")

    async def report_geo_observation(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        observation: GeoObservation,
        traceparent: str,
    ) -> GeoObservationDisposition:
        del task_id, execution_no, execution_token, observation, traceparent
        raise ControlPlaneConfigurationError("LOCAL_GEO_CONTROL_PLANE_NOT_CONFIGURED")

    async def get_geo_visibility_evidence(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        traceparent: str,
    ) -> GeoVisibilityEvidence:
        del task_id, execution_no, execution_token, traceparent
        # The local-only stub cannot assert Java's business truth or manufacture
        # an unresolved target to conceal missing real GEO configuration.
        raise ControlPlaneConfigurationError("LOCAL_GEO_CONTROL_PLANE_NOT_CONFIGURED")

    async def report_geo_completion(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        completion: GeoCompletion,
        traceparent: str,
    ) -> JavaTaskStatus:
        del task_id, execution_no, execution_token, completion, traceparent
        raise ControlPlaneConfigurationError("LOCAL_GEO_CONTROL_PLANE_NOT_CONFIGURED")

    async def report_article_preview(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        attempt: int,
        sequence: int,
        markdown: str,
        traceparent: str,
    ) -> None:
        del execution_no, execution_token, attempt, sequence, markdown, traceparent
        self.audit.append(f"article-preview:{task_id}")

    async def report_article_completion(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        completion: ArticleCompletion,
        traceparent: str,
    ) -> JavaTaskStatus:
        del task_id, execution_no, execution_token, completion, traceparent
        raise ControlPlaneConfigurationError("LOCAL_ARTICLE_CONTROL_PLANE_NOT_CONFIGURED")

    async def aclose(self) -> None:
        return None


def _safe_error_code(value: str, fallback: str) -> str:
    return value if re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", value) else fallback
