"""ARTICLE execution backend mounted into the existing bounded Worker runtime."""

from __future__ import annotations

import asyncio
import logging
from typing import Protocol

from app.article.models import (
    ArticleBody,
    ArticleExecutionResult,
    ArticleStage,
    render_article_markdown,
)
from app.article.runtime import ArticleExecutionError, ArticleExecutionKernel
from app.article.worker_models import (
    ArticleCompletion,
    ArticleExecutionContext,
    ArticleExecutionOutcome,
    ArticleOutlineSnapshot,
)
from app.common.settings import AppSettings
from app.model_gateway import build_model_gateway
from app.observability.trace import reset_current_trace_id, set_current_trace_id
from app.observability.tracing import safe_ref, start_span
from app.providers.models import SubmissionState
from app.workers.control_plane import (
    ControlPlaneRejected,
    ControlPlaneUnavailable,
    JavaTaskStatus,
)
from app.workers.lease import ExecutionLeaseMonitor
from app.workers.models import TaskExecutionMessage

_UNKNOWN_RESULT_CODES = frozenset(("ARTICLE_DEADLINE_EXCEEDED", "DEEPSEEK_SUBMISSION_UNKNOWN"))
_logger = logging.getLogger(__name__)


class ArticleWorkerControlPlane(Protocol):
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

    async def report_article_completion(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        completion: ArticleCompletion,
        traceparent: str,
    ) -> JavaTaskStatus: ...


class ArticleWorkerBackend:
    """Execute one Java-authorized ARTICLE stage through the shared DeepSeek gateway."""

    code = "DEEPSEEK"
    is_mock = False

    def __init__(self, kernel: ArticleExecutionKernel) -> None:
        self._kernel = kernel
        self.model = kernel.model_identity.model

    async def probe(self) -> bool:
        return await self._kernel.probe()

    async def execute(
        self,
        message: TaskExecutionMessage,
        context: ArticleExecutionContext,
        *,
        execution_token: str,
        control_plane: ArticleWorkerControlPlane,
        heartbeat_interval_seconds: float,
    ) -> ArticleExecutionOutcome:
        trace_token = set_current_trace_id(message.traceparent.split("-")[1])
        stage = "ARTICLE_GENERATING"
        lease_monitor: ExecutionLeaseMonitor | None = None
        preview_sequence = 0
        preview_available = True

        async def on_preview(attempt: int, markdown: str) -> None:
            nonlocal preview_sequence, preview_available
            if not preview_available:
                return
            preview_sequence += 1
            try:
                async with asyncio.timeout(2):
                    await control_plane.report_article_preview(
                        message.task_id,
                        execution_no=context.execution_no,
                        execution_token=execution_token,
                        attempt=attempt,
                        sequence=preview_sequence,
                        markdown=markdown,
                        traceparent=message.traceparent,
                    )
            except (ControlPlaneUnavailable, TimeoutError):
                # A preview is disposable, not a second generation/result submission.
                # Stop streaming previews on transient transport failure; the existing
                # lease monitor and authoritative final handoff still control the task.
                preview_available = False
                _logger.warning("article_preview_transport_unavailable")
            # Rejected authorization/lease fencing deliberately propagates: never
            # continue an expensive generation after Java has revoked execution.

        try:
            await control_plane.heartbeat(
                message.task_id,
                execution_no=context.execution_no,
                execution_token=execution_token,
                stage_code=stage,
                traceparent=message.traceparent,
            )
            await control_plane.report_progress(
                message.task_id,
                execution_no=context.execution_no,
                execution_token=execution_token,
                stage_code=stage,
                traceparent=message.traceparent,
            )
            lease_monitor = ExecutionLeaseMonitor(
                lambda: self._renew_lease(
                    message,
                    context,
                    execution_token,
                    control_plane,
                    stage,
                ),
                interval_seconds=heartbeat_interval_seconds,
            )
            lease_monitor.start(name=f"article-worker-heartbeat-{message.task_id}")
            try:
                with start_span(
                    f"article.stage.{context.request.mode.value.lower()}",
                    attributes={
                        "task.ref": safe_ref(message.task_id),
                        "executionNo": context.execution_no,
                        "article.stage": context.request.mode.value,
                    },
                ) as span:
                    try:
                        result = await lease_monitor.run(
                            lambda: self._kernel.execute(
                                context.request,
                                on_preview=on_preview
                                if context.request.mode is ArticleStage.BODY
                                else None,
                            ),
                            name=f"article-model-{message.task_id}",
                        )
                        completion = _completion(context, result)
                    except ArticleExecutionError as exc:
                        uncertain = (
                            exc.usage.request_count > 0 and exc.code in _UNKNOWN_RESULT_CODES
                        )
                        span.set("usage.inputTokens", exc.usage.input_tokens)
                        span.set("usage.outputTokens", exc.usage.output_tokens)
                        span.set("usage.requestCount", exc.usage.request_count)
                        # Failed responses can omit usage even when submission
                        # is confirmed. Visible telemetry is never proof of a
                        # complete zero-token Provider bill.
                        span.set("usage.isLowerBound", True)
                        span.set(
                            "provider.submissionState",
                            "UNKNOWN"
                            if uncertain
                            else "CONFIRMED"
                            if exc.usage.request_count > 0
                            else "NOT_SUBMITTED",
                        )
                        span.fail(exc.code)
                        raise
                    except (TypeError, ValueError):
                        span.fail("ARTICLE_RESULT_INVALID")
                        raise
            except ArticleExecutionError as exc:
                submission = (
                    SubmissionState.UNKNOWN
                    if exc.usage.request_count > 0 and exc.code in _UNKNOWN_RESULT_CODES
                    else SubmissionState.CONFIRMED
                    if exc.usage.request_count > 0
                    else SubmissionState.NOT_SUBMITTED
                )
                await control_plane.report_failure(
                    message.task_id,
                    execution_no=context.execution_no,
                    execution_token=execution_token,
                    code=exc.code,
                    submission_state=submission,
                    traceparent=message.traceparent,
                    provider=self.code if submission is SubmissionState.UNKNOWN else None,
                    model=self.model if submission is SubmissionState.UNKNOWN else None,
                )
                return ArticleExecutionOutcome(succeeded=False, stable_error_code=exc.code)
            except (TypeError, ValueError):
                code = "ARTICLE_RESULT_INVALID"
                await control_plane.report_failure(
                    message.task_id,
                    execution_no=context.execution_no,
                    execution_token=execution_token,
                    code=code,
                    submission_state=SubmissionState.CONFIRMED,
                    traceparent=message.traceparent,
                )
                return ArticleExecutionOutcome(succeeded=False, stable_error_code=code)

            with start_span(
                "java.result.handoff",
                attributes={
                    "task.ref": safe_ref(message.task_id),
                    "executionNo": context.execution_no,
                    "handoff.kind": "article-completion",
                },
            ) as span:
                try:
                    java_status = await control_plane.report_article_completion(
                        message.task_id,
                        execution_no=context.execution_no,
                        execution_token=execution_token,
                        completion=completion,
                        traceparent=message.traceparent,
                    )
                except (ControlPlaneUnavailable, ControlPlaneRejected) as exc:
                    span.fail(exc.code)
                    raise
                span.set("java.taskStatus", java_status)
            if java_status == "SUCCEEDED":
                return ArticleExecutionOutcome(succeeded=True, java_task_status=java_status)
            if java_status == "FAILED":
                return ArticleExecutionOutcome(
                    succeeded=False,
                    java_task_status=java_status,
                    stable_error_code="ARTICLE_VERSION_CONFLICT",
                )
            raise ControlPlaneRejected("JAVA_ARTICLE_RESULT_RESPONSE_INVALID")
        finally:
            if lease_monitor is not None:
                await lease_monitor.close()
            reset_current_trace_id(trace_token)

    @staticmethod
    async def _renew_lease(
        message: TaskExecutionMessage,
        context: ArticleExecutionContext,
        execution_token: str,
        control_plane: ArticleWorkerControlPlane,
        stage: str,
    ) -> None:
        await control_plane.heartbeat(
            message.task_id,
            execution_no=context.execution_no,
            execution_token=execution_token,
            stage_code=stage,
            traceparent=message.traceparent,
        )

    async def aclose(self) -> None:
        await self._kernel.aclose()


def _completion(
    context: ArticleExecutionContext,
    result: ArticleExecutionResult,
) -> ArticleCompletion:
    if result.mode is ArticleStage.OUTLINE:
        outline = tuple(
            ArticleOutlineSnapshot(
                key=item.key,
                sequence=item.sequence,
                level=item.level,
                heading=item.heading,
                brief=item.brief,
            )
            for item in result.outline
        )
    elif result.mode in {ArticleStage.BODY, ArticleStage.REWRITE}:
        outline = context.authorized_outline
    else:
        outline = ()

    body = result.body
    if result.mode is ArticleStage.REWRITE:
        if body is None:
            raise ValueError("REWRITE result is missing body")
        by_key = {item.key: item for item in body.blocks}
        selected = tuple(by_key[key] for key in context.request.rewrite_block_ids)
        body = ArticleBody(markdown=render_article_markdown(selected), blocks=selected)
    return ArticleCompletion(
        article_version=context.article_version,
        result=result,
        outline=outline,
        body=body,
    )


def build_article_worker_backend(settings: AppSettings) -> ArticleWorkerBackend:
    from app.prompts.runtime import load_commerce_prompt_set

    prompts = load_commerce_prompt_set(settings.bundle_path)
    return ArticleWorkerBackend(ArticleExecutionKernel(build_model_gateway(settings), prompts))


__all__ = ["ArticleWorkerBackend", "ArticleWorkerControlPlane", "build_article_worker_backend"]
