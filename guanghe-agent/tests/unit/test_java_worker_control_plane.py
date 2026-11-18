"""Frozen Java IMAGE Worker control-plane contract tests; all HTTP stays in process."""

import asyncio
import json
import logging
from collections.abc import Callable, Mapping
from copy import deepcopy
from datetime import UTC, datetime
from typing import cast
from uuid import UUID

import httpx
import pytest

from app.article.models import (
    ArticleBody,
    ArticleBodyBlock,
    ArticleExecutionMetadata,
    ArticleExecutionResult,
    ArticleOutlineItem,
    ArticlePlan,
    ArticleStage,
    ArticleTargetLength,
    ArticleTitleCandidate,
    ArticleUsage,
    render_article_markdown,
)
from app.article.runtime import ArticleExecutionKernel
from app.article.worker_models import (
    ArticleCompletion,
    ArticleExecutionContext,
    ArticleOutlineSnapshot,
)
from app.common.enums import TaskType
from app.common.runtime import initialize_runtime
from app.common.settings import AppSettings, ProviderMockSettings
from app.creative.models import CreativeSourceMode
from app.geo.models import (
    GeoObservation,
    GeoObservationStatus,
    GeoPlatformCode,
    GeoProviderUsage,
    GeoSynthesis,
)
from app.geo.worker_models import (
    GeoCompletion,
    GeoExecutionContext,
    GeoObservationDisposition,
    GeoSynthesisUsage,
)
from app.integration.java_internal import (
    EXECUTION_NO_HEADER,
    EXECUTION_TOKEN_HEADER,
    JavaInternalClient,
    JavaInternalClientError,
    JavaInternalErrorCode,
    JavaInternalResponse,
    JavaReverseCallResult,
)
from app.model_gateway.gateway import ModelGateway
from app.observability.logging import JsonFormatter
from app.providers.mock import LocalTaskProviderMock
from app.providers.models import (
    ProviderArtifact,
    ProviderErrorCategory,
    ProviderUsage,
    QwenImageProviderContinuation,
    SubmissionState,
    TaskProviderError,
    TaskProviderRequest,
    TaskProviderResult,
    WanVideoProviderContinuation,
)
from app.security.internal_hmac import sign_content
from app.workers.control_plane import (
    ClaimDisposition,
    ClaimResult,
    ControlPlaneRejected,
    ControlPlaneUnavailable,
    ExecutionContext,
)
from app.workers.java_control_plane import (
    JavaWorkerControlPlane,
    _safe_artifact_hostname,  # pyright: ignore[reportPrivateUsage]
)
from app.workers.models import TaskExecutionMessage, WorkerStatusSnapshot, WorkerTaskStatus
from app.workers.runtime import WorkerRuntime
from app.workers.transport import LocalMessageBroker, RawDelivery
from tests.internal_security import (
    TEST_KEY_ID,
    TEST_SECRET_TEXT,
    TEST_TRACEPARENT,
    internal_settings,
)
from tests.unit.test_article_execution import (
    _prompts,  # pyright: ignore[reportPrivateUsage]
    _StructuredGateway,  # pyright: ignore[reportPrivateUsage]
    _title_candidates,  # pyright: ignore[reportPrivateUsage]
)
from tests.unit.test_article_execution import (
    _request as article_kernel_request,  # pyright: ignore[reportPrivateUsage]
)

TASK_ID = "1900012345678901700"
EVENT_ID = "1900012345678906000"
PRICING_VERSION_ID = "1900012345678901800"
EXECUTION_TOKEN = "v1.synthetic-execution-token"
LEASE_EXPIRES_AT = "2026-09-03T12:05:00Z"
SUBMIT_REQUEST_ID = "123e4567-e89b-42d3-a456-426614174101"
_INVALID_CLAIM_UPDATES: tuple[dict[str, object], ...] = (
    {"unknown": True},
    {"claimed": False},
    {"executionNo": 0},
    {"executionToken": ""},
    {"leaseExpiresAt": "2026-09-03T12:05:00"},
    {"disposition": "OTHER"},
)
_INVALID_CONTEXT_MUTATIONS: tuple[tuple[str, object, str], ...] = (
    ("budget", {"maxProviderCalls": 1, "maxImages": 5}, "JAVA_CONTROL_PLANE_RESPONSE_INVALID"),
    ("budget", {"maxProviderCalls": 1, "maxImages": 0}, "JAVA_CONTROL_PLANE_RESPONSE_INVALID"),
    ("budget", {"maxProviderCalls": 1, "maxImages": True}, "JAVA_CONTROL_PLANE_RESPONSE_INVALID"),
    ("taskId", "0", "JAVA_CONTROL_PLANE_RESPONSE_INVALID"),
    ("taskType", "VIDEO", "JAVA_CONTROL_PLANE_RESPONSE_INVALID"),
    ("snapshotSchemaVersion", 2, "JAVA_CONTROL_PLANE_RESPONSE_INVALID"),
    ("inputSnapshot", [], "JAVA_CONTROL_PLANE_RESPONSE_INVALID"),
    ("authorizedContext", [], "JAVA_CONTROL_PLANE_RESPONSE_INVALID"),
    (
        "pricing",
        {"pricingVersionId": "0", "quotedPoints": 7},
        "JAVA_CONTROL_PLANE_RESPONSE_INVALID",
    ),
    (
        "budget",
        {"maxProviderCalls": 2, "maxImages": 1},
        "JAVA_CONTROL_PLANE_RESPONSE_INVALID",
    ),
    ("taskId", "1900012345678901701", "JAVA_EXECUTION_CONTEXT_IDENTITY_MISMATCH"),
)


def _message(
    *,
    attempt: int = 1,
    task_id: str = TASK_ID,
    event_id: str = EVENT_ID,
    task_type: TaskType = TaskType.IMAGE,
) -> TaskExecutionMessage:
    return TaskExecutionMessage(
        schema_version=1,
        event_id=event_id,
        event_type="TASK_EXECUTION_REQUESTED",
        task_id=task_id,
        task_type=task_type,
        user_id="1900012345678901001",
        space_id="1900012345678901100",
        attempt=attempt,
        traceparent=TEST_TRACEPARENT,
        created_at=datetime(2026, 9, 3, 12, tzinfo=UTC),
    )


def _claimed_payload() -> dict[str, object]:
    return {
        "claimed": True,
        "disposition": "CLAIMED",
        "executionNo": 1,
        "executionToken": EXECUTION_TOKEN,
        "leaseExpiresAt": LEASE_EXPIRES_AT,
    }


def _creative_brief_payload() -> dict[str, object]:
    return {
        "sourceMode": "CATALOG_PRODUCT",
        "productId": "1900012345678901200",
        "referenceAssetIds": ["1900012345678901300"],
        "productFacts": {
            "name": "陶瓷马克杯",
            "category": None,
            "modelSpec": None,
            "sellingPoints": [],
            "parameters": {},
            "audience": [],
            "usageScenarios": [],
        },
        "creativeDirection": {"requestText": "生成商品素材"},
        "prohibitedClaims": ["不得声称销量第一"],
        "provenance": {
            "conversationId": "1900012345678901003",
            "messageId": "1900012345678901004",
        },
    }


def _controlled_reference_payload() -> dict[str, object]:
    return {
        "assetId": "1900012345678901300",
        "contentType": "image/png",
        "sizeBytes": 1024,
        "sha256": "a" * 64,
        "width": 1024,
        "height": 1024,
        "readUrl": "https://asset.test/product.png?signature=secret",
        "expiresAt": "2030-09-14T10:00:00+08:00",
    }


def _context_payload() -> dict[str, object]:
    return {
        "taskId": TASK_ID,
        "taskType": "IMAGE",
        "provider": "QWEN_IMAGE",
        "model": "qwen-image-3.0",
        "executionNo": 1,
        "snapshotSchemaVersion": 1,
        "inputSnapshot": {
            "requestText": "白色背景上的简约陶瓷马克杯",
            "mode": "AGENT_ASSISTED",
            "agentSummary": "已整理图片参数。",
            "sourceMode": "CATALOG_PRODUCT",
            "referenceAssetIds": ["1900012345678901300"],
            "aspectRatio": "1:1",
            "count": 1,
        },
        "productCreativeBrief": _creative_brief_payload(),
        "referenceAssets": [_controlled_reference_payload()],
        "authorizedContext": {
            "space": {"id": "1900012345678901100"},
            "product": {"id": "1900012345678901200"},
            "competitors": [],
        },
        "resumeOnly": False,
        "providerContinuation": None,
        "pricing": {"pricingVersionId": PRICING_VERSION_ID, "quotedPoints": 7},
        "budget": {"maxProviderCalls": 1, "maxImages": 1},
    }


def _recovery_context_payload() -> dict[str, object]:
    payload = _context_payload()
    payload["executionNo"] = 2
    payload["resumeOnly"] = True
    payload["providerContinuation"] = {
        "resumeOnly": True,
        "provider": "QWEN_IMAGE",
        "model": "qwen-image-3.0",
        "providerRequestId": SUBMIT_REQUEST_ID,
        "providerTaskId": "qwen-task-1",
        "submissionState": "UNKNOWN",
    }
    return payload


def _geo_context_payload() -> dict[str, object]:
    question_ids = [
        "1900012345678902101",
        "1900012345678902102",
        "1900012345678902103",
    ]
    return {
        "taskId": TASK_ID,
        "taskType": "GEO",
        "executionNo": 1,
        "snapshotSchemaVersion": 1,
        "inputSnapshot": {
            "parameters": {
                "mode": "MONITOR_RUN",
                "topicId": "1900012345678902001",
                "questionIds": question_ids,
                "platformCodes": ["DOUBAO", "WENXIN"],
                "sampleMode": "STANDARD",
                "region": "全国",
            }
        },
        "authorizedContext": {
            "topic": {
                "id": "1900012345678902001",
                "spaceId": "1900012345678901100",
                "productId": None,
                "name": "测试主题",
                "region": "全国",
                "version": 1,
            },
            "questions": [
                {"id": question_id, "category": "BRAND_DISCOVERY", "text": f"问题{index}"}
                for index, question_id in enumerate(question_ids, start=1)
            ],
            "competitorSetVersion": 1,
            "competitors": [
                {
                    "id": "1900012345678902201",
                    "name": "测试竞品",
                    "aliases": [],
                    "competitorType": "DIRECT",
                }
            ],
            "completedUnitKeys": [],
        },
        "referenceAssets": [],
        "resumeOnly": False,
        "providerContinuation": None,
        "pricing": {"pricingVersionId": PRICING_VERSION_ID, "quotedPoints": 18},
        "budget": {
            "maxProviderCalls": 12,
            "maxGeoQuestions": 10,
            "maxGeoPlatforms": 7,
            "samplesPerCombination": 1,
            "maxConcurrency": 2,
            "deadlineAt": "2026-09-08T13:00:00+08:00",
        },
    }


def _authorized_video_context() -> dict[str, object]:
    return {
        "space": {
            "id": "1900012345678901100",
            "name": "视频测试经营空间",
            "brand": {
                "name": "视频测试品牌",
                "aliases": [],
                "description": None,
                "tone": [],
                "audience": [],
                "sellingPoints": [],
                "prohibitedClaims": [],
            },
            "logoAssetId": None,
        },
        "product": {
            "id": "1900012345678901200",
            "spaceId": "1900012345678901100",
            "name": "陶瓷马克杯",
            "aliases": [],
            "category": None,
            "modelSpec": None,
            "sellingPoints": [],
            "parameters": {},
            "audience": [],
            "usageScenarios": [],
            "prohibitedClaims": [],
            "primaryAssetId": None,
            "identityAssetIds": [],
        },
        "competitors": [],
        "attachmentAssetIds": [],
    }


def _video_context_payload(*, recovery: bool = False) -> dict[str, object]:
    return {
        "taskId": TASK_ID,
        "taskType": "VIDEO",
        "provider": "WAN_VIDEO",
        "model": "wan3.0-video",
        "executionNo": 2 if recovery else 1,
        "snapshotSchemaVersion": 1,
        "inputSnapshot": {
            "requestText": "生成陶瓷马克杯旋转展示视频",
            "mode": "AGENT_ASSISTED",
            "agentSummary": "已整理视频参数。",
            "sourceMode": "CATALOG_PRODUCT",
            "referenceAssetIds": ["1900012345678901300"],
            "scene": "白色背景中的陶瓷马克杯缓慢旋转",
            "durationSeconds": 2,
            "aspectRatio": "16:9",
            "resolution": "480P",
            "audio": False,
            "seed": -1,
            "promptExtend": True,
            "watermark": False,
        },
        "authorizedContext": _authorized_video_context(),
        "productCreativeBrief": _creative_brief_payload(),
        "referenceAssets": [_controlled_reference_payload()],
        "resumeOnly": recovery,
        "providerContinuation": (
            {
                "resumeOnly": True,
                "provider": "WAN_VIDEO",
                "model": "wan3.0-video",
                "providerRequestId": SUBMIT_REQUEST_ID,
                "providerTaskId": "wan-task-1",
                "submissionState": "UNKNOWN",
            }
            if recovery
            else None
        ),
        "pricing": {"pricingVersionId": PRICING_VERSION_ID, "quotedPoints": 9},
        "budget": {
            "maxProviderCalls": 61,
            "maxVideos": 1,
            "maxVideoSeconds": 30,
        },
    }


def _article_context_payload(mode: str = "TITLE") -> dict[str, object]:
    outline = [
        {
            "key": "opening",
            "sequence": 1,
            "level": 2,
            "heading": "选择标准",
            "brief": "说明选择维度",
        },
        {
            "key": "usage",
            "sequence": 2,
            "level": 2,
            "heading": "使用建议",
            "brief": "给出有边界的使用建议",
        },
    ]
    plan = {
        "summary": "说明如何按使用场景选择商品。",
        "coreMessage": "先明确场景，再核对有来源的商品参数。",
        "targetAudience": "需要购买建议的新手用户",
        "sections": outline,
        "tone": "PROFESSIONAL",
        "targetLength": "SHORT",
        "targetPlatform": None,
        "riskNotes": ["避免未经证实的绝对化卖点。"],
    }
    title = "如何按使用场景选择商品"
    parameters: dict[str, object] = {
        "mode": mode,
        "articleId": "1900012345678903001",
        "articleVersion": 3,
        "brief": "为当前商品准备一篇真实、克制的中文选购内容",
        "selectedTitle": None if mode == "TITLE" else title,
        "confirmedOutline": outline if mode in {"BODY", "REWRITE"} else [],
        "confirmedPlan": deepcopy(plan) if mode in {"BODY", "REWRITE"} else None,
        "rewriteInstruction": "只把使用建议改得更简洁" if mode == "REWRITE" else None,
        "rewriteBlockIds": ["usage"] if mode == "REWRITE" else [],
        "targetLength": "SHORT",
        "tone": "PROFESSIONAL",
        "targetPlatform": None,
        "sourceAssetIds": ["1900012345678903101"],
        "geoReportId": "1900012345678903201",
    }
    article: dict[str, object] = {
        "id": "1900012345678903001",
        "version": 3,
        "title": "" if mode == "TITLE" else title,
        "outline": outline if mode in {"BODY", "REWRITE"} else [],
        "confirmedPlan": deepcopy(plan) if mode in {"BODY", "REWRITE"} else None,
    }
    if mode == "REWRITE":
        article.update(
            {
                "currentBody": {
                    "html": (
                        '<section data-article-block-id="opening"><h2>选择标准</h2>'
                        "<p>先明确使用场景。</p></section>"
                        '<section data-article-block-id="usage"><h2>使用建议</h2>'
                        "<p>再核对已知参数。</p></section>"
                    ),
                    "text": "选择标准 先明确使用场景。 使用建议 再核对已知参数。",
                },
                "currentVersionRef": 3,
                "rewriteBlockIds": ["usage"],
            }
        )
    authorized = _authorized_video_context()
    authorized.pop("competitors")
    authorized.pop("attachmentAssetIds")
    authorized.update(
        {
            "article": article,
            "sourceAssets": [
                {
                    "id": "1900012345678903101",
                    "assetType": "IMAGE",
                    "name": "授权商品图",
                    "contentType": "image/png",
                    "sha256": "a" * 64,
                }
            ],
            "geoReport": {
                "id": "1900012345678903201",
                "summary": "真实监测样本显示用户关注使用场景。",
                "methodVersion": "geo-synthesis-v1",
            },
        }
    )
    return {
        "taskId": TASK_ID,
        "taskType": "ARTICLE",
        "provider": "DEEPSEEK",
        "executionNo": 1,
        "snapshotSchemaVersion": 2,
        "inputSnapshot": {"parameters": parameters},
        "authorizedContext": authorized,
        "referenceAssets": [],
        "resumeOnly": False,
        "providerContinuation": None,
        "pricing": {"pricingVersionId": PRICING_VERSION_ID, "quotedPoints": 3},
        "budget": {
            "maxProviderCalls": 0,
            "maxModelCalls": 2,
            "maxRepairRetries": 1,
            "maxInputTokens": 16000,
            "maxOutputTokens": 8192,
            "deadlineAt": "2030-09-09T12:00:00+08:00",
        },
    }


def _wan_result() -> TaskProviderResult:
    request_id = UUID(SUBMIT_REQUEST_ID)
    return TaskProviderResult(
        provider="WAN_VIDEO",
        model="wan3.0-video",
        request_id=request_id,
        provider_task_id="wan-task-1",
        artifacts=(
            ProviderArtifact(
                artifact_type="VIDEO",
                unit_key="video:1",
                provider_url="https://result.example/video.mp4?Expires=synthetic",
                content_type="video/mp4",
                metadata={
                    "durationSeconds": 2.0,
                    "requestedDurationSeconds": 2,
                    "requestedAspectRatio": "16:9",
                    "requestedResolution": "480P",
                    "mock": False,
                    "providerRequestId": str(request_id),
                    "fps": 30,
                    "resolution": "480P",
                    "aspectRatio": "16:9",
                },
            ),
        ),
        usage=ProviderUsage(video_seconds=2),
        latency_ms=12_345,
    )


def _response(payload: Mapping[str, object]) -> JavaInternalResponse:
    return JavaInternalResponse(status_code=200, payload=dict(payload), trace_id="a" * 32)


def _qwen_result(*, request_id: UUID | None = None, count: int = 1) -> TaskProviderResult:
    resolved_request_id = request_id or UUID(SUBMIT_REQUEST_ID)
    return TaskProviderResult(
        provider="QWEN_IMAGE",
        model="qwen-image-3.0",
        request_id=resolved_request_id,
        provider_task_id="qwen-task-123",
        artifacts=tuple(
            ProviderArtifact(
                artifact_type="IMAGE",
                unit_key=f"image:{index}",
                provider_url=(
                    "https://result.example/image.png?Expires=synthetic"
                    if index == 1
                    else f"https://result.example/image-{index}.png?Expires=synthetic"
                ),
                content_type="image/png",
                expires_at=datetime(2030, 9, 5, tzinfo=UTC),
                metadata={
                    "index": index,
                    "width": 1024,
                    "height": 1024,
                    "mock": False,
                    "providerRequestId": str(resolved_request_id),
                },
            )
            for index in range(1, count + 1)
        ),
        usage=ProviderUsage(images=count),
        latency_ms=3210,
    )


class _FakeWorkerJavaClient:
    def __init__(self) -> None:
        self.claim_result: JavaInternalResponse | JavaInternalClientError = _response(
            _claimed_payload()
        )
        self.context_result: JavaInternalResponse | JavaInternalClientError = _response(
            _context_payload()
        )
        self.heartbeat_result: JavaInternalResponse | JavaInternalClientError = _response(
            {"accepted": True, "leaseExpiresAt": LEASE_EXPIRES_AT}
        )
        self.event_result: JavaInternalResponse | JavaInternalClientError = _response(
            {"accepted": True, "taskStatus": "RUNNING"}
        )
        self.failure_event_result: JavaInternalResponse | JavaInternalClientError | None = None
        self.result_result: JavaInternalResponse | JavaInternalClientError = _response(
            {"accepted": True, "taskStatus": "SUCCEEDED"}
        )
        self.geo_pages: dict[int, JavaInternalResponse | JavaInternalClientError] = {
            1: _response(
                {
                    "items": [],
                    "page": 1,
                    "size": 50,
                    "total": 0,
                    "totalPages": 0,
                    "visibilityEvidence": {
                        "targetStatus": "TARGET_UNRESOLVED",
                        "matchingVersion": "explicit-name-mention-v1",
                        "units": [],
                    },
                }
            )
        }
        self.geo_observation_result: JavaInternalResponse | JavaInternalClientError = _response(
            {"accepted": True, "disposition": "CREATED", "taskStatus": "RUNNING"}
        )
        self.geo_completion_result: JavaInternalResponse | JavaInternalClientError = _response(
            {
                "accepted": True,
                "taskStatus": "SUCCEEDED",
                "runStatus": "SUCCEEDED",
                "reportId": "1900012345678902301",
                "settledPoints": 18,
                "releasedPoints": 0,
            }
        )
        self.calls: list[tuple[str, str, dict[str, object], str | None]] = []
        self.closed = False

    async def reverse_call(self, traceparent: str | None = None) -> JavaReverseCallResult:
        del traceparent
        return JavaReverseCallResult(True, "test-key", "0" * 32)

    async def claim_task(
        self,
        task_id: str,
        payload: Mapping[str, object],
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        self.calls.append(("claim", task_id, dict(payload), traceparent))
        return self._resolve(self.claim_result)

    async def get_task_execution_context(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        self.calls.append(
            (
                "context",
                task_id,
                {"executionNo": execution_no, "executionToken": execution_token},
                traceparent,
            )
        )
        return self._resolve(self.context_result)

    async def heartbeat_task(
        self,
        task_id: str,
        payload: Mapping[str, object],
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        self.calls.append(("heartbeat", task_id, dict(payload), traceparent))
        return self._resolve(self.heartbeat_result)

    async def report_task_execution_event(
        self,
        task_id: str,
        payload: Mapping[str, object],
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        request = dict(payload)
        self.calls.append(("event", task_id, request, traceparent))
        result = self.event_result
        if request.get("eventType") == "TASK_FAILED" and self.failure_event_result is not None:
            result = self.failure_event_result
        return self._resolve(result)

    async def report_task_result(
        self,
        task_id: str,
        payload: Mapping[str, object],
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        self.calls.append(("result", task_id, dict(payload), traceparent))
        return self._resolve(self.result_result)

    async def get_task_geo_observations(
        self,
        task_id: str,
        *,
        execution_no: int,
        execution_token: str,
        page: int,
        size: int,
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        self.calls.append(
            (
                "geo-list",
                task_id,
                {
                    "executionNo": execution_no,
                    "executionToken": execution_token,
                    "page": page,
                    "size": size,
                },
                traceparent,
            )
        )
        return self._resolve(self.geo_pages[page])

    async def report_task_geo_observation(
        self,
        task_id: str,
        payload: Mapping[str, object],
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        self.calls.append(("geo-observation", task_id, dict(payload), traceparent))
        return self._resolve(self.geo_observation_result)

    async def report_task_geo_completion(
        self,
        task_id: str,
        payload: Mapping[str, object],
        traceparent: str | None = None,
    ) -> JavaInternalResponse:
        self.calls.append(("geo-completion", task_id, dict(payload), traceparent))
        return self._resolve(self.geo_completion_result)

    async def aclose(self) -> None:
        self.closed = True

    @staticmethod
    def _resolve(
        result: JavaInternalResponse | JavaInternalClientError,
    ) -> JavaInternalResponse:
        if isinstance(result, JavaInternalClientError):
            raise result
        return result


@pytest.mark.parametrize(
    ("status", "operation", "body", "expected_business_code"),
    (
        (409, "context", b'{"code":"KNOWLEDGE_SOURCE_CHANGED"}', "KNOWLEDGE_SOURCE_CHANGED"),
        (409, "claim", b'{"code":"KNOWLEDGE_SOURCE_CHANGED"}', None),
        (409, "unsigned-context", b'{"code":"KNOWLEDGE_SOURCE_CHANGED"}', None),
        (403, "context", b'{"code":"KNOWLEDGE_SOURCE_CHANGED"}', None),
        (404, "context", b'{"code":"KNOWLEDGE_SOURCE_CHANGED"}', None),
        (422, "context", b'{"code":"KNOWLEDGE_SOURCE_CHANGED"}', None),
        (503, "context", b'{"code":"KNOWLEDGE_SOURCE_CHANGED"}', None),
        (409, "context", b'{"code":"EXECUTION_LEASE_EXPIRED"}', None),
        (409, "context", b'{"code":"PRIVATE_ARBITRARY_ERROR"}', None),
        (409, "context", b'{"code":{"private":"secret"}}', None),
        (409, "context", b'["KNOWLEDGE_SOURCE_CHANGED"]', None),
        (409, "context", b"private non-JSON response", None),
        (
            409,
            "context",
            b'{"code":"KNOWLEDGE_SOURCE_CHANGED","message":"' + b"x" * 1_048_576 + b'"}',
            None,
        ),
    ),
    ids=(
        "context-known",
        "claim-untrusted",
        "missing-execution-headers",
        "forbidden",
        "not-found",
        "validation",
        "upstream-failure",
        "expired-lease",
        "unlisted-code",
        "wrong-code-type",
        "wrong-envelope-type",
        "non-json",
        "oversized-body",
    ),
)
def test_context_rejection_preserves_only_bounded_audited_business_code(
    status: int, operation: str, body: bytes, expected_business_code: str | None
) -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(status, content=body, headers={"Content-Type": "application/json"})

    async def scenario() -> None:
        shared = JavaInternalClient(
            internal_settings(java_internal_base_url="http://127.0.0.1:8080"),
            httpx.AsyncClient(
                base_url="http://127.0.0.1:8080", transport=httpx.MockTransport(handler)
            ),
        )
        with pytest.raises(JavaInternalClientError) as captured:
            if operation == "claim":
                await shared.claim_task(TASK_ID, {})
            elif operation == "unsigned-context":
                await shared.get(f"/internal/v1/tasks/{TASK_ID}/execution-context")
            else:
                await shared.get_task_execution_context(
                    TASK_ID, execution_no=1, execution_token=EXECUTION_TOKEN
                )
        assert captured.value.status_code == status
        assert captured.value.business_code == expected_business_code
        assert set(vars(captured.value)) == {"code", "status_code", "business_code"}
        assert str(captured.value) == captured.value.code.value
        assert "private" not in str(captured.value).lower()
        await shared.aclose()

    asyncio.run(scenario())
    assert len(requests) == 1


def test_knowledge_rejection_http_to_worker_closes_without_provider_and_keeps_working(
    caplog: pytest.LogCaptureFixture,
) -> None:
    requests: list[httpx.Request] = []
    failures: list[dict[str, object]] = []
    next_task_id = "1900012345678901701"
    next_event_id = "1900012345678906001"

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        path = request.url.path
        if path.endswith("/reverse-call"):
            return httpx.Response(
                200,
                json={
                    "accepted": True,
                    "callerKeyId": TEST_KEY_ID,
                    "traceId": request.headers["traceparent"].split("-")[1],
                },
            )
        if path.endswith("/claim"):
            return httpx.Response(200, json=_claimed_payload())
        if path == f"/internal/v1/tasks/{TASK_ID}/execution-context":
            return httpx.Response(
                409,
                json={
                    "code": "KNOWLEDGE_SOURCE_CHANGED",
                    "message": "private document text, secret, and facts",
                    "details": {"private": "hidden"},
                },
            )
        if path == f"/internal/v1/tasks/{next_task_id}/execution-context":
            context = _context_payload()
            context["taskId"] = next_task_id
            return httpx.Response(200, json=context)
        if path.endswith("/heartbeat"):
            return httpx.Response(200, json={"accepted": True, "leaseExpiresAt": LEASE_EXPIRES_AT})
        if path.endswith("/execution-events"):
            event = cast(dict[str, object], json.loads(request.content))
            if event["eventType"] == "TASK_FAILED":
                failures.append(event)
                return httpx.Response(200, json={"accepted": True, "taskStatus": "FAILED"})
            return httpx.Response(200, json={"accepted": True, "taskStatus": "RUNNING"})
        if path.endswith("/result-artifacts"):
            return httpx.Response(200, json={"accepted": True, "taskStatus": "SUCCEEDED"})
        raise AssertionError(path)

    class CountingProvider(LocalTaskProviderMock):
        def __init__(self) -> None:
            super().__init__(ProviderMockSettings(latency_ms=0))
            self.calls = 0

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            self.calls += 1
            assert request.task_id == next_task_id
            return await super().execute(request)

    async def scenario() -> None:
        provider = CountingProvider()
        shared = JavaInternalClient(
            internal_settings(java_internal_base_url="http://127.0.0.1:8080"),
            httpx.AsyncClient(
                base_url="http://127.0.0.1:8080", transport=httpx.MockTransport(handler)
            ),
        )
        worker = WorkerRuntime(
            initialize_runtime(AppSettings(environment="test")),
            provider=provider,
            control_plane=JavaWorkerControlPlane(shared),
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        failed_delivery = RawDelivery(
            body=_message(task_type=TaskType.ARTICLE).model_dump_json(by_alias=True).encode()
        )
        await worker.process_delivery(failed_delivery)
        failed = worker.get_task(EVENT_ID)
        assert failed is not None
        assert failed_delivery.state == "ACKED"
        assert failed.status is WorkerTaskStatus.FAILED
        assert failed.error_code == "KNOWLEDGE_SOURCE_CHANGED"
        assert provider.calls == 0
        assert worker.is_ready
        assert len(failures) == 1
        assert failures[0]["details"] == {
            "code": "KNOWLEDGE_SOURCE_CHANGED",
            "submissionState": "NOT_SUBMITTED",
        }
        assert failures[0]["executionNo"] == 1
        assert failures[0]["executionToken"] == EXECUTION_TOKEN
        assert failures[0]["stageCode"] == "FAILED"
        assert failures[0]["eventType"] == "TASK_FAILED"

        next_delivery = RawDelivery(
            body=_message(task_id=next_task_id, event_id=next_event_id)
            .model_dump_json(by_alias=True)
            .encode()
        )
        await worker.process_delivery(next_delivery)
        completed = worker.get_task(next_event_id)
        assert completed is not None
        assert completed.status is WorkerTaskStatus.RESULT_READY
        assert provider.calls == 1
        assert worker.is_ready
        assert worker.status_snapshot().failed == 1
        assert worker.status_snapshot().processed == 1
        await worker.close()

    with caplog.at_level(logging.ERROR, logger="app.workers.runtime"):
        asyncio.run(scenario())
    assert len(failures) == 1
    assert [request.url.path.rsplit("/", 1)[1] for request in requests[:4]] == [
        "reverse-call",
        "claim",
        "execution-context",
        "execution-events",
    ]
    assert "private document" not in caplog.text
    assert "secret" not in caplog.text
    assert EXECUTION_TOKEN not in caplog.text


@pytest.mark.parametrize(
    ("task_type", "attempt", "failure_phase", "failure_report_rejected", "expected_event_count"),
    (
        (TaskType.ARTICLE, 1, "claim", False, 0),
        (TaskType.ARTICLE, 2, "context", False, 0),
        (TaskType.IMAGE, 1, "context", False, 0),
        (TaskType.ARTICLE, 1, "context", True, 1),
        (TaskType.IMAGE, 1, "result", False, 1),
    ),
)
def test_knowledge_rejection_does_not_refund_conflicts_recovery_or_late_results(
    task_type: TaskType,
    attempt: int,
    failure_phase: str,
    failure_report_rejected: bool,
    expected_event_count: int,
) -> None:
    async def scenario() -> None:
        client = _FakeWorkerJavaClient()
        rejection = JavaInternalClientError(
            JavaInternalErrorCode.HTTP_ERROR,
            status_code=409,
            business_code="KNOWLEDGE_SOURCE_CHANGED",
        )
        if failure_phase == "claim":
            client.claim_result = rejection
        elif failure_phase == "context":
            client.claim_result = _response({**_claimed_payload(), "executionNo": attempt})
            client.context_result = rejection
        else:
            client.result_result = rejection
        if failure_report_rejected:
            # An execution can be fenced between loading Context and reporting.
            # Do not retry/report with a replacement token or stop other workers.
            client.failure_event_result = JavaInternalClientError(
                JavaInternalErrorCode.HTTP_ERROR, status_code=409
            )
        worker = WorkerRuntime(
            initialize_runtime(AppSettings(environment="test")),
            provider=LocalTaskProviderMock(ProviderMockSettings(latency_ms=0)),
            control_plane=JavaWorkerControlPlane(client),
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        delivery = RawDelivery(
            body=_message(task_type=task_type, attempt=attempt)
            .model_dump_json(by_alias=True)
            .encode()
        )
        await worker.process_delivery(delivery)
        events = [call[2] for call in client.calls if call[0] == "event"]
        assert len(events) == expected_event_count
        if failure_report_rejected:
            assert events[0]["eventType"] == "TASK_FAILED"
            snapshot = worker.get_task(EVENT_ID)
            assert snapshot is not None
            assert snapshot.error_code == "JAVA_FAILURE_REPORT_UNAVAILABLE"
        elif events:
            assert events[0]["eventType"] == "TASK_PROGRESS"
        assert worker.is_ready
        assert delivery.state == ("REJECTED" if failure_phase == "claim" else "ACKED")
        await worker.close()

    asyncio.run(scenario())


def test_shared_java_client_signs_all_worker_operations_and_preserves_trace() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        raw_path = request.url.raw_path.decode("ascii")
        expected = sign_content(
            secret=TEST_SECRET_TEXT.encode(),
            timestamp=request.headers["X-Internal-Timestamp"],
            nonce=request.headers["X-Internal-Nonce"],
            method=request.method,
            raw_path_with_query=raw_path,
            body=request.content,
        )
        assert request.headers["X-Content-SHA256"] == expected.body_sha256
        assert request.headers["X-Internal-Signature"] == expected.signature_base64
        assert request.headers["X-Internal-Key-Id"] == TEST_KEY_ID
        assert request.headers["traceparent"].split("-")[1] == TEST_TRACEPARENT.split("-")[1]
        assert "Authorization" not in request.headers

        path = request.url.path
        if path.endswith("/claim"):
            return httpx.Response(200, json=_claimed_payload())
        if path.endswith("/execution-context"):
            assert request.method == "GET"
            assert request.content == b""
            assert request.headers[EXECUTION_NO_HEADER] == "1"
            assert request.headers[EXECUTION_TOKEN_HEADER] == EXECUTION_TOKEN
            return httpx.Response(200, json=_context_payload())
        if path.endswith("/heartbeat"):
            return httpx.Response(
                200,
                json={"accepted": True, "leaseExpiresAt": LEASE_EXPIRES_AT},
            )
        if path.endswith("/execution-events"):
            return httpx.Response(200, json={"accepted": True, "taskStatus": "RUNNING"})
        if path.endswith("/result-artifacts"):
            return httpx.Response(200, json={"accepted": True, "taskStatus": "SUCCEEDED"})
        if path.endswith("/geo-observations") and request.method == "GET":
            assert raw_path.endswith("/geo-observations?page=1&size=50")
            assert request.headers[EXECUTION_NO_HEADER] == "1"
            assert request.headers[EXECUTION_TOKEN_HEADER] == EXECUTION_TOKEN
            return httpx.Response(
                200,
                json={
                    "items": [],
                    "page": 1,
                    "size": 50,
                    "total": 0,
                    "totalPages": 0,
                    "visibilityEvidence": {
                        "targetStatus": "TARGET_UNRESOLVED",
                        "matchingVersion": "explicit-name-mention-v1",
                        "units": [],
                    },
                },
            )
        if path.endswith("/geo-observations"):
            return httpx.Response(
                200,
                json={"accepted": True, "disposition": "CREATED", "taskStatus": "RUNNING"},
            )
        if path.endswith("/geo-completion"):
            return httpx.Response(
                200,
                json={
                    "accepted": True,
                    "taskStatus": "SUCCEEDED",
                    "runStatus": "SUCCEEDED",
                    "reportId": "1900012345678902301",
                    "settledPoints": 1,
                    "releasedPoints": 0,
                },
            )
        raise AssertionError(raw_path)

    async def scenario() -> None:
        http_client = httpx.AsyncClient(
            base_url="http://127.0.0.1:8080",
            transport=httpx.MockTransport(handler),
        )
        shared_client = JavaInternalClient(
            internal_settings(java_internal_base_url="http://127.0.0.1:8080"),
            http_client,
        )
        control = JavaWorkerControlPlane(shared_client)
        message = _message()
        claim = await control.claim(
            message,
            consumer_name="zhixiaohe-image-worker-v1",
            worker_id="image-worker-01",
            bundle_version="commerce-assistant-v1.1.0",
        )
        assert claim.disposition is ClaimDisposition.CLAIMED
        assert claim.execution_no == 1
        assert claim.execution_token == EXECUTION_TOKEN
        assert claim.lease_expires_at == datetime(2026, 9, 3, 12, 5, tzinfo=UTC)

        context = await control.get_execution_context(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            traceparent=TEST_TRACEPARENT,
        )
        assert isinstance(context, ExecutionContext)
        assert context.max_provider_calls == 1
        assert context.max_images == 1
        await control.heartbeat(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            stage_code="PROVIDER_GENERATING",
            traceparent=TEST_TRACEPARENT,
        )
        await control.report_progress(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            stage_code="PROVIDER_GENERATING",
            traceparent=TEST_TRACEPARENT,
        )
        provider = LocalTaskProviderMock(ProviderMockSettings(latency_ms=0))
        result = await provider.execute(
            TaskProviderRequest(
                request_id=UUID("123e4567-e89b-12d3-a456-426614174000"),
                task_id=TASK_ID,
                task_type=TaskType.IMAGE,
                execution_no=1,
                parameters=context.parameters,
            )
        )
        await control.report_result(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            result=result,
            traceparent=TEST_TRACEPARENT,
        )
        await shared_client.get_task_geo_observations(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            page=1,
            size=50,
            traceparent=TEST_TRACEPARENT,
        )
        await shared_client.report_task_geo_observation(
            TASK_ID,
            {"eventId": "123e4567-e89b-12d3-a456-426614174001"},
            traceparent=TEST_TRACEPARENT,
        )
        await shared_client.report_task_geo_completion(
            TASK_ID,
            {"eventId": "123e4567-e89b-12d3-a456-426614174002"},
            traceparent=TEST_TRACEPARENT,
        )
        await provider.aclose()
        await control.aclose()

    asyncio.run(scenario())

    assert [request.method for request in requests] == [
        "POST",
        "GET",
        "POST",
        "POST",
        "POST",
        "GET",
        "POST",
        "POST",
    ]
    assert [request.url.path for request in requests] == [
        f"/internal/v1/tasks/{TASK_ID}/claim",
        f"/internal/v1/tasks/{TASK_ID}/execution-context",
        f"/internal/v1/tasks/{TASK_ID}/heartbeat",
        f"/internal/v1/tasks/{TASK_ID}/execution-events",
        f"/internal/v1/tasks/{TASK_ID}/result-artifacts",
        f"/internal/v1/tasks/{TASK_ID}/geo-observations",
        f"/internal/v1/tasks/{TASK_ID}/geo-observations",
        f"/internal/v1/tasks/{TASK_ID}/geo-completion",
    ]
    assert len({request.headers["X-Internal-Nonce"] for request in requests}) == 8

    bodies = [json.loads(request.content) for request in requests if request.content]
    assert bodies[0] == {
        "consumerName": "zhixiaohe-image-worker-v1",
        "eventId": EVENT_ID,
        "workerId": "image-worker-01",
        "attempt": 1,
        "supportedBundleVersion": "commerce-assistant-v1.1.0",
    }
    assert bodies[1] == {
        "executionNo": 1,
        "executionToken": EXECUTION_TOKEN,
        "stageCode": "PROVIDER_GENERATING",
    }
    progress = cast(dict[str, object], bodies[2])
    assert set(progress) == {
        "eventId",
        "executionNo",
        "executionToken",
        "eventType",
        "stageCode",
    }
    assert progress["eventType"] == "TASK_PROGRESS"
    UUID(cast(str, progress["eventId"]))
    result_body = cast(dict[str, object], bodies[3])
    assert result_body["eventId"] == "123e4567-e89b-12d3-a456-426614174000"
    assert result_body["provider"] == "LOCAL_TASK_PROVIDER"
    assert result_body["model"] == "deterministic-provider-mock-v1"
    assert result_body["providerTaskId"] == ("mock-image-123e4567-e89b-12d3-a456-426614174000")
    assert result_body["usage"] == {
        "inputTokens": 0,
        "outputTokens": 0,
        "images": 1,
        "videoSeconds": 0,
        "providerCost": "0.0000",
        "currency": "CNY",
    }
    artifacts = cast(list[dict[str, object]], result_body["artifacts"])
    assert len(artifacts) == 1
    assert artifacts[0]["artifactType"] == "IMAGE"
    assert artifacts[0]["contentType"] == "image/png"
    assert artifacts[0]["metadata"] == {"width": 1024, "height": 1024, "mock": True}


def test_java_control_plane_maps_real_qwen_image_result_without_claiming_free_cost() -> None:
    client = _FakeWorkerJavaClient()
    client.result_result = _response({"accepted": True, "taskStatus": "RUNNING"})
    control = JavaWorkerControlPlane(client)
    result = _qwen_result()

    task_status = asyncio.run(
        control.report_result(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            result=result,
            traceparent=TEST_TRACEPARENT,
        )
    )
    assert task_status == "RUNNING"

    operation, task_id, payload, traceparent = client.calls[0]
    assert operation == "result"
    assert task_id == TASK_ID
    assert traceparent == TEST_TRACEPARENT
    assert payload["provider"] == "QWEN_IMAGE"
    assert payload["model"] == "qwen-image-3.0"
    assert payload["requestId"] == "123e4567-e89b-42d3-a456-426614174101"
    assert payload["providerTaskId"] == "qwen-task-123"
    assert payload["submissionState"] == "CONFIRMED"
    assert payload["usage"] == {
        "inputTokens": 0,
        "outputTokens": 0,
        "images": 1,
        "videoSeconds": 0,
        "providerCost": None,
        "currency": None,
    }
    artifacts = cast(list[dict[str, object]], payload["artifacts"])
    assert len(artifacts) == 1
    assert artifacts[0]["providerUrl"] == "https://result.example/image.png?Expires=synthetic"
    assert artifacts[0]["expiresAt"] == "2030-09-05T00:00:00Z"
    assert artifacts[0]["metadata"] == {
        "index": 1,
        "width": 1024,
        "height": 1024,
        "mock": False,
        "providerRequestId": "123e4567-e89b-42d3-a456-426614174101",
    }


@pytest.mark.parametrize(
    ("client_error", "expected_exception", "expected_code", "http_status"),
    (
        (
            JavaInternalErrorCode.HTTP_ERROR,
            ControlPlaneRejected,
            "JAVA_CONTROL_PLANE_REQUEST_REJECTED",
            422,
        ),
        (
            JavaInternalErrorCode.UPSTREAM_5XX,
            ControlPlaneUnavailable,
            "JAVA_CONTROL_PLANE_UPSTREAM_UNAVAILABLE",
            503,
        ),
    ),
)
def test_result_handoff_failure_logs_only_bounded_host_and_status(
    caplog: pytest.LogCaptureFixture,
    client_error: JavaInternalErrorCode,
    expected_exception: type[ControlPlaneRejected] | type[ControlPlaneUnavailable],
    expected_code: str,
    http_status: int,
) -> None:
    client = _FakeWorkerJavaClient()
    client.result_result = JavaInternalClientError(client_error, status_code=http_status)
    result = _qwen_result()
    artifact = result.artifacts[0].model_copy(
        update={
            "provider_url": "https://dashscope-a717.oss-accelerate.aliyuncs.com/private/image.png"
            "?token=must-not-log",
        }
    )
    result = result.model_copy(update={"artifacts": (artifact,)})

    with (
        caplog.at_level(logging.ERROR, logger="app.workers.java_control_plane"),
        pytest.raises(expected_exception, match=expected_code),
    ):
        asyncio.run(
            JavaWorkerControlPlane(client).report_result(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                result=result,
                traceparent=TEST_TRACEPARENT,
            )
        )

    records = [record for record in caplog.records if record.msg == "worker_result_handoff_failed"]
    assert len(records) == 1
    safe_log = json.loads(JsonFormatter().format(records[0]))
    assert safe_log["taskId"] == TASK_ID
    assert safe_log["attempt"] == 1
    assert safe_log["failureStage"] == "RESULT_HANDOFF"
    assert safe_log["stableErrorCode"] == expected_code
    assert safe_log["httpStatus"] == http_status
    assert safe_log["artifactHost"] == "dashscope-a717.oss-accelerate.aliyuncs.com"
    assert "private" not in json.dumps(safe_log)
    assert "must-not-log" not in json.dumps(safe_log)


def test_result_handoff_host_diagnostic_never_raises_for_malformed_url() -> None:
    result = _qwen_result()
    broken_artifact = result.artifacts[0].model_copy(update={"provider_url": "https://[bad"})
    broken_result = result.model_copy(update={"artifacts": (broken_artifact,)})
    assert _safe_artifact_hostname(broken_result) is None


@pytest.mark.parametrize(
    "corruption", ("duplicate_url", "index", "unit_key", "request_id", "usage", "too_many")
)
def test_java_image_candidates_reject_inconsistent_evidence_before_http(corruption: str) -> None:
    result = _qwen_result(count=2)
    first, second = result.artifacts
    if corruption == "duplicate_url":
        second = second.model_copy(update={"provider_url": first.provider_url})
    elif corruption == "index":
        second = second.model_copy(update={"metadata": {**second.metadata, "index": 1}})
    elif corruption == "unit_key":
        second = second.model_copy(update={"unit_key": "image:1"})
    elif corruption == "request_id":
        second = second.model_copy(
            update={"metadata": {**second.metadata, "providerRequestId": str(UUID(int=1))}}
        )
    result = result.model_copy(update={"artifacts": (first, second)})
    if corruption == "usage":
        result = result.model_copy(update={"usage": ProviderUsage(images=1)})
    elif corruption == "too_many":
        result = _qwen_result(count=5)
    client = _FakeWorkerJavaClient()
    with pytest.raises(ControlPlaneRejected) as captured:
        asyncio.run(
            JavaWorkerControlPlane(client).report_result(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                result=result,
                traceparent=TEST_TRACEPARENT,
            )
        )
    assert captured.value.code == "JAVA_CONTROL_PLANE_REQUEST_INVALID"
    assert client.calls == []


def test_java_control_plane_claims_and_maps_strict_video_execution_context() -> None:
    client = _FakeWorkerJavaClient()
    client.context_result = _response(_video_context_payload())
    control = JavaWorkerControlPlane(client)

    claim = asyncio.run(
        control.claim(
            _message(task_type=TaskType.VIDEO),
            consumer_name="zhixiaohe-video-worker-v1",
            worker_id="video-worker-01",
            bundle_version="commerce-assistant-v1.1.0",
        )
    )
    context = asyncio.run(
        control.get_execution_context(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            traceparent=TEST_TRACEPARENT,
        )
    )
    assert isinstance(context, ExecutionContext)

    assert claim.disposition is ClaimDisposition.CLAIMED
    assert context.task_type is TaskType.VIDEO
    expected_parameters = dict(cast(dict[str, object], _video_context_payload()["inputSnapshot"]))
    for control_plane_field in (
        "requestText",
        "mode",
        "agentSummary",
        "sourceMode",
        "referenceAssetIds",
    ):
        expected_parameters.pop(control_plane_field)
    assert context.parameters == expected_parameters
    assert context.max_provider_calls == 61
    assert context.max_images is None
    assert context.max_videos == 1
    assert context.max_video_seconds == 30
    assert context.resume_only is False
    assert context.provider_continuation is None
    assert [call[0] for call in client.calls] == ["claim", "context"]
    assert all(call[3] == TEST_TRACEPARENT for call in client.calls)


def test_video_current_attachment_context_accepts_frozen_ids_without_a_catalog_product() -> None:
    payload = _video_context_payload()
    input_snapshot = cast(dict[str, object], payload["inputSnapshot"])
    input_snapshot["sourceMode"] = "CURRENT_ATTACHMENTS"
    authorized = cast(dict[str, object], payload["authorizedContext"])
    authorized.update(
        {
            "space": None,
            "product": None,
            "competitors": [],
            "attachmentAssetIds": ["1900012345678901300"],
        }
    )
    brief = cast(dict[str, object], payload["productCreativeBrief"])
    brief.update(
        {
            "sourceMode": "CURRENT_ATTACHMENTS",
            "productId": None,
            "productFacts": None,
            "prohibitedClaims": [],
        }
    )
    client = _FakeWorkerJavaClient()
    client.context_result = _response(payload)
    context = asyncio.run(
        JavaWorkerControlPlane(client).get_execution_context(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            traceparent=TEST_TRACEPARENT,
        )
    )
    assert isinstance(context, ExecutionContext)
    assert context.task_type is TaskType.VIDEO
    assert context.creative_brief is not None
    assert context.creative_brief.product_id is None
    assert context.creative_brief.reference_asset_ids == ("1900012345678901300",)
    assert "creativePlan" not in context.parameters

    mismatched = deepcopy(payload)
    cast(dict[str, object], mismatched["authorizedContext"])["attachmentAssetIds"] = []
    client.context_result = _response(mismatched)
    with pytest.raises(ControlPlaneUnavailable, match="JAVA_CONTROL_PLANE_RESPONSE_INVALID"):
        asyncio.run(
            JavaWorkerControlPlane(client).get_execution_context(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                traceparent=TEST_TRACEPARENT,
            )
        )


def test_video_previous_result_context_accepts_one_frozen_non_attachment_image() -> None:
    payload = _video_context_payload()
    input_snapshot = cast(dict[str, object], payload["inputSnapshot"])
    input_snapshot["sourceMode"] = "PREVIOUS_IMAGE_RESULT"
    payload["authorizedContext"] = {
        "space": None,
        "product": None,
        "competitors": [],
        "attachmentAssetIds": [],
    }
    brief = cast(dict[str, object], payload["productCreativeBrief"])
    brief.update(
        {
            "sourceMode": "PREVIOUS_IMAGE_RESULT",
            "productId": None,
            "productFacts": None,
            "prohibitedClaims": [],
        }
    )
    client = _FakeWorkerJavaClient()
    client.context_result = _response(payload)

    context = asyncio.run(
        JavaWorkerControlPlane(client).get_execution_context(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            traceparent=TEST_TRACEPARENT,
        )
    )

    assert isinstance(context, ExecutionContext)
    assert context.creative_brief is not None
    assert context.creative_brief.source_mode is CreativeSourceMode.PREVIOUS_IMAGE_RESULT
    assert len(context.reference_assets) == 1


def test_video_previous_user_attachment_context_accepts_frozen_source_message() -> None:
    payload = _video_context_payload()
    input_snapshot = cast(dict[str, object], payload["inputSnapshot"])
    input_snapshot["sourceMode"] = "PREVIOUS_USER_ATTACHMENT"
    payload["authorizedContext"] = {
        "space": None,
        "product": None,
        "competitors": [],
        "attachmentAssetIds": [],
    }
    brief = cast(dict[str, object], payload["productCreativeBrief"])
    brief.update(
        {
            "sourceMode": "PREVIOUS_USER_ATTACHMENT",
            "productId": None,
            "productFacts": None,
            "prohibitedClaims": [],
            "provenance": {
                "conversationId": "1900012345678901003",
                "messageId": "1900012345678901004",
                "referenceMessageId": "1900012345678901005",
            },
        }
    )
    client = _FakeWorkerJavaClient()
    client.context_result = _response(payload)

    context = asyncio.run(
        JavaWorkerControlPlane(client).get_execution_context(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            traceparent=TEST_TRACEPARENT,
        )
    )

    assert isinstance(context, ExecutionContext)
    assert context.creative_brief is not None
    assert context.creative_brief.source_mode is CreativeSourceMode.PREVIOUS_USER_ATTACHMENT
    assert context.creative_brief.provenance.reference_message_id == "1900012345678901005"
    assert len(context.reference_assets) == 1


def test_video_text_only_context_needs_neither_product_nor_attachment() -> None:
    payload = _video_context_payload()
    input_snapshot = cast(dict[str, object], payload["inputSnapshot"])
    input_snapshot.update({"sourceMode": "TEXT_ONLY", "referenceAssetIds": []})
    payload["authorizedContext"] = {
        "space": None,
        "product": None,
        "competitors": [],
        "attachmentAssetIds": [],
    }
    brief = cast(dict[str, object], payload["productCreativeBrief"])
    brief.update(
        {
            "sourceMode": "TEXT_ONLY",
            "productId": None,
            "referenceAssetIds": [],
            "productFacts": None,
            "prohibitedClaims": [],
        }
    )
    payload["referenceAssets"] = []
    client = _FakeWorkerJavaClient()
    client.context_result = _response(payload)
    context = asyncio.run(
        JavaWorkerControlPlane(client).get_execution_context(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            traceparent=TEST_TRACEPARENT,
        )
    )
    assert isinstance(context, ExecutionContext)
    assert context.task_type is TaskType.VIDEO
    assert context.creative_brief is not None
    assert context.creative_brief.product_id is None
    assert context.creative_brief.reference_asset_ids == ()
    assert "creativePlan" not in context.parameters


def test_video_geo_report_context_validates_both_signed_plan_copies_before_provider() -> None:
    payload = _video_context_payload()
    plan = {
        "version": "geo-visual-plan-v1",
        "taskType": "VIDEO",
        "reportId": "1900012345678901999",
        "recommendationId": None,
        "proof": "v1." + "A" * 43,
        "opportunity": "需要展示商品的使用场景",
        "audience": "需要咖啡的上班族",
        "message": "展示便携咖啡场景",
        "evidenceKeys": ["sample-1"],
        "scene": "桌面上的咖啡杯与商品",
        "visualFocus": "商品外观",
        "shotBeats": ["先看商品外观", "再展示使用场景"],
        "limitations": ["不夸大未证实的商品性能"],
    }
    input_snapshot = cast(dict[str, object], payload["inputSnapshot"])
    input_snapshot.update(
        {
            "sourceMode": "GEO_REPORT",
            "geoReportId": "1900012345678901999",
            "creativePlan": deepcopy(plan),
        }
    )
    brief = cast(dict[str, object], payload["productCreativeBrief"])
    brief["sourceMode"] = "GEO_REPORT"
    cast(dict[str, object], brief["creativeDirection"])["creativePlan"] = deepcopy(plan)
    client = _FakeWorkerJavaClient()
    client.context_result = _response(payload)
    context = asyncio.run(
        JavaWorkerControlPlane(client).get_execution_context(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            traceparent=TEST_TRACEPARENT,
        )
    )
    assert isinstance(context, ExecutionContext)
    assert context.task_type is TaskType.VIDEO
    assert context.parameters["geoReportId"] == "1900012345678901999"
    assert "creativePlan" not in context.parameters

    mismatched = deepcopy(payload)
    cast(dict[str, object], mismatched["inputSnapshot"])["creativePlan"] = {
        **plan,
        "message": "另一份未经授权的创意方案",
    }
    client.context_result = _response(mismatched)
    with pytest.raises(ControlPlaneUnavailable, match="JAVA_CONTROL_PLANE_RESPONSE_INVALID"):
        asyncio.run(
            JavaWorkerControlPlane(client).get_execution_context(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                traceparent=TEST_TRACEPARENT,
            )
        )


def test_video_execution_context_maps_only_the_exact_wan_continuation() -> None:
    client = _FakeWorkerJavaClient()
    client.context_result = _response(_video_context_payload(recovery=True))

    context = asyncio.run(
        JavaWorkerControlPlane(client).get_execution_context(
            TASK_ID,
            execution_no=2,
            execution_token=EXECUTION_TOKEN,
            traceparent=TEST_TRACEPARENT,
        )
    )
    assert isinstance(context, ExecutionContext)

    continuation = context.provider_continuation
    assert context.resume_only is True
    assert isinstance(continuation, WanVideoProviderContinuation)
    assert continuation.provider_request_id == UUID(SUBMIT_REQUEST_ID)
    assert continuation.provider_task_id == "wan-task-1"
    assert continuation.provider == "WAN_VIDEO"
    assert continuation.model == "wan3.0-video"


def test_video_execution_context_rejects_mixed_or_unfrozen_fields() -> None:
    invalid_payloads: list[dict[str, object]] = []
    mutations: tuple[Callable[[dict[str, object]], None], ...] = (
        lambda payload: payload.update({"model": "wan3.0-video-prime"}),
        lambda payload: payload.update({"unknown": True}),
        lambda payload: cast(dict[str, object], payload["inputSnapshot"]).update(
            {"negativePrompt": "forbidden"}
        ),
        lambda payload: cast(dict[str, object], payload["budget"]).update({"maxVideos": 2}),
        lambda payload: cast(
            dict[str, object],
            cast(dict[str, object], payload["authorizedContext"])["product"],
        ).update({"spaceId": "1900012345678901199"}),
    )
    for mutate in mutations:
        payload = _video_context_payload()
        mutate(payload)
        invalid_payloads.append(payload)

    mixed = _video_context_payload(recovery=True)
    continuation = cast(dict[str, object], mixed["providerContinuation"])
    continuation["provider"] = "QWEN_IMAGE"
    continuation["model"] = "qwen-image-3.0"
    invalid_payloads.append(mixed)

    for payload in invalid_payloads:
        client = _FakeWorkerJavaClient()
        client.context_result = _response(payload)
        with pytest.raises(ControlPlaneUnavailable) as captured:
            asyncio.run(
                JavaWorkerControlPlane(client).get_execution_context(
                    TASK_ID,
                    execution_no=cast(int, payload["executionNo"]),
                    execution_token=EXECUTION_TOKEN,
                    traceparent=TEST_TRACEPARENT,
                )
            )
        assert captured.value.code == "JAVA_CONTROL_PLANE_RESPONSE_INVALID"


def test_java_control_plane_reports_exact_wan_video_handoff() -> None:
    client = _FakeWorkerJavaClient()
    client.result_result = _response({"accepted": True, "taskStatus": "RUNNING"})

    task_status = asyncio.run(
        JavaWorkerControlPlane(client).report_result(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            result=_wan_result(),
            traceparent=TEST_TRACEPARENT,
        )
    )

    assert task_status == "RUNNING"
    operation, task_id, payload, traceparent = client.calls[0]
    assert (operation, task_id, traceparent) == ("result", TASK_ID, TEST_TRACEPARENT)
    assert payload["provider"] == "WAN_VIDEO"
    assert payload["model"] == "wan3.0-video"
    assert payload["requestId"] == SUBMIT_REQUEST_ID
    assert payload["providerTaskId"] == "wan-task-1"
    assert payload["submissionState"] == "CONFIRMED"
    assert payload["usage"] == {
        "inputTokens": 0,
        "outputTokens": 0,
        "images": 0,
        "videoSeconds": 2,
        "providerCost": None,
        "currency": None,
    }
    artifacts = cast(list[dict[str, object]], payload["artifacts"])
    assert artifacts[0]["unitKey"] == "video:1"
    assert artifacts[0]["expiresAt"] is None
    assert artifacts[0]["metadata"] == {
        "durationSeconds": 2.0,
        "requestedDurationSeconds": 2,
        "requestedAspectRatio": "16:9",
        "requestedResolution": "480P",
        "mock": False,
        "providerRequestId": SUBMIT_REQUEST_ID,
        "fps": 30.0,
        "resolution": "480P",
        "aspectRatio": "16:9",
    }


@pytest.mark.parametrize("task_status", ["SUCCEEDED", "PARTIAL"])
def test_wan_video_result_idempotent_terminal_replay_is_accepted(task_status: str) -> None:
    client = _FakeWorkerJavaClient()
    client.result_result = _response({"accepted": True, "taskStatus": task_status})

    actual = asyncio.run(
        JavaWorkerControlPlane(client).report_result(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            result=_wan_result(),
            traceparent=TEST_TRACEPARENT,
        )
    )

    assert actual == task_status


@pytest.mark.parametrize("task_status", ["RETRY_WAIT", "FAILED", "SYSTEM_TERMINATED"])
@pytest.mark.parametrize(
    "result_factory,error_code",
    [
        (_qwen_result, "JAVA_IMAGE_RESULT_HANDOFF_RESPONSE_INVALID"),
        (_wan_result, "JAVA_VIDEO_RESULT_HANDOFF_RESPONSE_INVALID"),
    ],
)
def test_real_media_result_handoff_rejects_non_result_statuses(
    task_status: str,
    result_factory: Callable[[], TaskProviderResult],
    error_code: str,
) -> None:
    client = _FakeWorkerJavaClient()
    client.result_result = _response({"accepted": True, "taskStatus": task_status})

    with pytest.raises(ControlPlaneRejected) as captured:
        asyncio.run(
            JavaWorkerControlPlane(client).report_result(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                result=result_factory(),
                traceparent=TEST_TRACEPARENT,
            )
        )

    assert captured.value.code == error_code


def test_wan_unknown_failure_preserves_exact_continuation_identity() -> None:
    client = _FakeWorkerJavaClient()
    client.event_result = _response({"accepted": True, "taskStatus": "RETRY_WAIT"})

    asyncio.run(
        JavaWorkerControlPlane(client).report_failure(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            code="WAN_VIDEO_POLL_TIMEOUT",
            submission_state=SubmissionState.UNKNOWN,
            traceparent=TEST_TRACEPARENT,
            provider="WAN_VIDEO",
            model="wan3.0-video",
            provider_request_id=SUBMIT_REQUEST_ID,
            provider_task_id="wan-task-1",
        )
    )

    assert client.calls[0][2]["details"] == {
        "code": "WAN_VIDEO_POLL_TIMEOUT",
        "provider": "WAN_VIDEO",
        "model": "wan3.0-video",
        "providerRequestId": SUBMIT_REQUEST_ID,
        "providerTaskId": "wan-task-1",
        "submissionState": "UNKNOWN",
    }


@pytest.mark.parametrize(
    "payload",
    (
        {"accepted": False, "taskStatus": "RUNNING"},
        {"accepted": True, "taskStatus": "ASSET_INGESTING"},
        {"accepted": True, "taskStatus": "RUNNING", "unknown": True},
        {"accepted": True},
    ),
)
def test_qwen_result_handoff_rejects_false_or_out_of_contract_java_response(
    payload: dict[str, object],
) -> None:
    client = _FakeWorkerJavaClient()
    client.result_result = _response(payload)

    with pytest.raises(ControlPlaneUnavailable) as captured:
        asyncio.run(
            JavaWorkerControlPlane(client).report_result(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                result=_qwen_result(),
                traceparent=TEST_TRACEPARENT,
            )
        )

    assert captured.value.code == "JAVA_CONTROL_PLANE_RESPONSE_INVALID"


def test_mock_result_still_requires_java_succeeded() -> None:
    async def scenario() -> None:
        provider = LocalTaskProviderMock(ProviderMockSettings(latency_ms=0))
        result = await provider.execute(
            TaskProviderRequest(
                request_id=UUID("123e4567-e89b-12d3-a456-426614174000"),
                task_id=TASK_ID,
                task_type=TaskType.IMAGE,
                execution_no=1,
                parameters={},
            )
        )
        client = _FakeWorkerJavaClient()
        client.result_result = _response({"accepted": True, "taskStatus": "RUNNING"})
        with pytest.raises(ControlPlaneRejected) as captured:
            await JavaWorkerControlPlane(client).report_result(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                result=result,
                traceparent=TEST_TRACEPARENT,
            )
        assert captured.value.code == "JAVA_TASK_RESULT_RESPONSE_INVALID"
        await provider.aclose()

    asyncio.run(scenario())


def test_java_control_plane_rejects_out_of_contract_qwen_evidence_before_http() -> None:
    valid = TaskProviderResult(
        provider="QWEN_IMAGE",
        model="qwen-image-3.0",
        request_id=UUID("123e4567-e89b-42d3-a456-426614174101"),
        provider_task_id="qwen-task-123",
        artifacts=(
            ProviderArtifact(
                artifact_type="IMAGE",
                unit_key="image:1",
                provider_url="https://result.example/image.png?Expires=synthetic",
                content_type="image/png",
                expires_at=datetime(2030, 9, 5, tzinfo=UTC),
                metadata={
                    "index": 1,
                    "width": 1024,
                    "height": 1024,
                    "mock": False,
                    "providerRequestId": "123e4567-e89b-42d3-a456-426614174101",
                },
            ),
        ),
        usage=ProviderUsage(images=1),
        latency_ms=1,
    )
    artifact = valid.artifacts[0]
    invalid = (
        valid.model_copy(update={"provider_task_id": "task with spaces"}),
        valid.model_copy(
            update={
                "artifacts": (
                    artifact.model_copy(update={"provider_url": "http://result.example/image.png"}),
                )
            }
        ),
        valid.model_copy(
            update={
                "artifacts": (
                    artifact.model_copy(update={"expires_at": datetime(2020, 1, 1, tzinfo=UTC)}),
                )
            }
        ),
        valid.model_copy(
            update={
                "artifacts": (
                    artifact.model_copy(
                        update={
                            "metadata": {
                                "width": 1024,
                                "height": 1024,
                                "mock": False,
                            }
                        }
                    ),
                )
            }
        ),
    )

    for result in invalid:
        client = _FakeWorkerJavaClient()
        with pytest.raises(ControlPlaneRejected) as captured:
            asyncio.run(
                JavaWorkerControlPlane(client).report_result(
                    TASK_ID,
                    execution_no=1,
                    execution_token=EXECUTION_TOKEN,
                    result=result,
                    traceparent=TEST_TRACEPARENT,
                )
            )
        assert captured.value.code == "JAVA_CONTROL_PLANE_REQUEST_INVALID"
        assert client.calls == []


@pytest.mark.parametrize(
    ("payload", "disposition"),
    [
        (
            {
                "claimed": False,
                "disposition": "DUPLICATE",
                "executionNo": 1,
                "executionToken": EXECUTION_TOKEN,
                "leaseExpiresAt": LEASE_EXPIRES_AT,
            },
            ClaimDisposition.DUPLICATE,
        ),
        (
            {
                "claimed": False,
                "disposition": "TERMINAL",
                "executionNo": None,
                "executionToken": None,
                "leaseExpiresAt": None,
            },
            ClaimDisposition.TERMINAL,
        ),
    ],
)
def test_claim_maps_java_duplicate_and_terminal_dispositions(
    payload: dict[str, object], disposition: ClaimDisposition
) -> None:
    client = _FakeWorkerJavaClient()
    client.claim_result = _response(payload)
    control = JavaWorkerControlPlane(client)

    result = asyncio.run(
        control.claim(
            _message(),
            consumer_name="worker-consumer",
            worker_id="worker-1",
            bundle_version="bundle-v1",
        )
    )

    assert result.disposition is disposition
    assert [call[0] for call in client.calls] == ["claim"]


@pytest.mark.parametrize(
    "updates",
    _INVALID_CLAIM_UPDATES,
)
def test_claim_rejects_invalid_or_contradictory_success_dto(
    updates: dict[str, object],
) -> None:
    payload = _claimed_payload()
    payload.update(updates)
    client = _FakeWorkerJavaClient()
    client.claim_result = _response(payload)

    with pytest.raises(ControlPlaneUnavailable) as captured:
        asyncio.run(
            JavaWorkerControlPlane(client).claim(
                _message(),
                consumer_name="worker-consumer",
                worker_id="worker-1",
                bundle_version="bundle-v1",
            )
        )

    assert captured.value.code == "JAVA_CONTROL_PLANE_RESPONSE_INVALID"


@pytest.mark.parametrize(
    ("field_name", "field_value", "expected_code"),
    _INVALID_CONTEXT_MUTATIONS,
)
def test_execution_context_strictly_validates_frozen_dto_and_identity(
    field_name: str, field_value: object, expected_code: str
) -> None:
    payload = deepcopy(_context_payload())
    payload[field_name] = field_value
    client = _FakeWorkerJavaClient()
    client.context_result = _response(payload)

    with pytest.raises((ControlPlaneUnavailable, ControlPlaneRejected)) as captured:
        asyncio.run(
            JavaWorkerControlPlane(client).get_execution_context(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                traceparent=TEST_TRACEPARENT,
            )
        )

    assert captured.value.code == expected_code


def test_recovery_context_maps_exact_qwen_continuation() -> None:
    client = _FakeWorkerJavaClient()
    client.context_result = _response(_recovery_context_payload())

    context = asyncio.run(
        JavaWorkerControlPlane(client).get_execution_context(
            TASK_ID,
            execution_no=2,
            execution_token=EXECUTION_TOKEN,
            traceparent=TEST_TRACEPARENT,
        )
    )
    assert isinstance(context, ExecutionContext)

    assert context.resume_only is True
    continuation = context.provider_continuation
    assert continuation is not None
    assert continuation.provider == "QWEN_IMAGE"
    assert continuation.model == "qwen-image-3.0"
    assert continuation.provider_request_id == UUID(SUBMIT_REQUEST_ID)
    assert continuation.provider_task_id == "qwen-task-1"
    assert continuation.submission_state is SubmissionState.UNKNOWN


def test_recovery_context_rejects_missing_mixed_or_illegal_continuation() -> None:
    invalid_payloads: list[dict[str, object]] = []

    missing = _context_payload()
    missing["resumeOnly"] = True
    invalid_payloads.append(missing)

    unexpected = _context_payload()
    unexpected["providerContinuation"] = _recovery_context_payload()["providerContinuation"]
    invalid_payloads.append(unexpected)

    for field, value in (
        ("provider", "LOCAL_TASK_PROVIDER"),
        ("model", "qwen-image-test"),
        ("providerRequestId", "not-a-uuid"),
        ("providerRequestId", SUBMIT_REQUEST_ID.upper()),
        ("providerTaskId", "task with spaces"),
        ("submissionState", "CONFIRMED"),
        ("resumeOnly", False),
        ("unknown", True),
    ):
        payload = _recovery_context_payload()
        continuation = cast(dict[str, object], payload["providerContinuation"])
        continuation[field] = value
        invalid_payloads.append(payload)

    for payload in invalid_payloads:
        client = _FakeWorkerJavaClient()
        client.context_result = _response(payload)
        with pytest.raises(ControlPlaneUnavailable) as captured:
            asyncio.run(
                JavaWorkerControlPlane(client).get_execution_context(
                    TASK_ID,
                    execution_no=2,
                    execution_token=EXECUTION_TOKEN,
                    traceparent=TEST_TRACEPARENT,
                )
            )
        assert captured.value.code == "JAVA_CONTROL_PLANE_RESPONSE_INVALID"


@pytest.mark.parametrize(
    ("provider", "model"),
    (
        ("QWEN_IMAGE", "qwen-image-3.0"),
        ("WAN_IMAGE_EDIT", "wanx2.1-imageedit"),
        ("WAN_VIDEO", "wan3.0-video"),
    ),
)
@pytest.mark.parametrize(
    ("request_id", "task_id"),
    ((None, None), (SUBMIT_REQUEST_ID, None), (None, "upstream-task")),
)
def test_unknown_failure_without_complete_receipt_is_reported_for_termination(
    provider: str, model: str, request_id: str | None, task_id: str | None
) -> None:
    client = _FakeWorkerJavaClient()
    client.event_result = _response({"accepted": True, "taskStatus": "SYSTEM_TERMINATED"})
    asyncio.run(
        JavaWorkerControlPlane(client).report_failure(
            TASK_ID,
            execution_no=2,
            execution_token=EXECUTION_TOKEN,
            code="PROVIDER_SUBMISSION_UNKNOWN",
            submission_state=SubmissionState.UNKNOWN,
            traceparent=TEST_TRACEPARENT,
            provider=provider,
            model=model,
            provider_request_id=request_id,
            provider_task_id=task_id,
        )
    )
    details = client.calls[-1][2]["details"]
    assert details == {
        "code": "PROVIDER_SUBMISSION_UNKNOWN",
        "submissionState": "UNKNOWN",
        "provider": provider,
        "model": model,
        "providerRequestId": request_id,
        "providerTaskId": task_id,
    }


@pytest.mark.parametrize("code", ("ARTICLE_DEADLINE_EXCEEDED", "DEEPSEEK_SUBMISSION_UNKNOWN"))
def test_article_unknown_failure_without_receipts_requires_termination(code: str) -> None:
    client = _FakeWorkerJavaClient()
    client.event_result = _response({"accepted": True, "taskStatus": "SYSTEM_TERMINATED"})
    asyncio.run(
        JavaWorkerControlPlane(client).report_failure(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            code=code,
            submission_state=SubmissionState.UNKNOWN,
            traceparent=TEST_TRACEPARENT,
            provider="DEEPSEEK",
            model="deepseek-v4-flash",
        )
    )
    assert len(client.calls) == 1
    assert client.calls[0][2]["stageCode"] == "PROVIDER_RESULT_UNKNOWN"
    assert client.calls[0][2]["details"] == {
        "code": code,
        "submissionState": "UNKNOWN",
        "provider": "DEEPSEEK",
        "model": "deepseek-v4-flash",
        "providerRequestId": None,
        "providerTaskId": None,
    }


@pytest.mark.parametrize(
    ("provider", "model", "code", "request_id", "task_id"),
    (
        ("DEEPSEEK", "deepseek-v4-flash", "ARBITRARY_FAILURE", None, None),
        ("DEEPSEEK", "deepseek-v4-flash", "ARTICLE_DEADLINE_EXCEEDED", SUBMIT_REQUEST_ID, None),
        ("DEEPSEEK", "deepseek-v4-flash", "ARTICLE_DEADLINE_EXCEEDED", None, "upstream-task"),
        ("DEEPSEEK", "qwen-image-3.0", "ARTICLE_DEADLINE_EXCEEDED", None, None),
        ("QWEN_IMAGE", "deepseek-v4-flash", "ARTICLE_DEADLINE_EXCEEDED", None, None),
        ("OPENAI", "deepseek-v4-flash", "ARTICLE_DEADLINE_EXCEEDED", None, None),
        ("DEEPSEEK", "deepseek-", "ARTICLE_DEADLINE_EXCEEDED", None, None),
        ("DEEPSEEK", "deepseek-" + "a" * 113, "ARTICLE_DEADLINE_EXCEEDED", None, None),
        ("DEEPSEEK", "deepseek-v4/flash", "ARTICLE_DEADLINE_EXCEEDED", None, None),
    ),
)
def test_article_unknown_failure_rejects_unfrozen_identity_code_and_any_receipt(
    provider: str, model: str, code: str, request_id: str | None, task_id: str | None
) -> None:
    client = _FakeWorkerJavaClient()
    with pytest.raises(ControlPlaneRejected, match="JAVA_CONTROL_PLANE_REQUEST_INVALID"):
        asyncio.run(
            JavaWorkerControlPlane(client).report_failure(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                code=code,
                submission_state=SubmissionState.UNKNOWN,
                traceparent=TEST_TRACEPARENT,
                provider=provider,
                model=model,
                provider_request_id=request_id,
                provider_task_id=task_id,
            )
        )
    assert client.calls == []


def test_article_unknown_failure_cannot_accept_retry_wait() -> None:
    client = _FakeWorkerJavaClient()
    client.event_result = _response({"accepted": True, "taskStatus": "RETRY_WAIT"})
    with pytest.raises(ControlPlaneRejected, match="JAVA_TASK_FAILURE_RESPONSE_INVALID"):
        asyncio.run(
            JavaWorkerControlPlane(client).report_failure(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                code="ARTICLE_DEADLINE_EXCEEDED",
                submission_state=SubmissionState.UNKNOWN,
                traceparent=TEST_TRACEPARENT,
                provider="DEEPSEEK",
                model="deepseek-v4-flash",
            )
        )
    assert len(client.calls) == 1


def test_unknown_failure_without_receipt_cannot_accept_a_retry_response() -> None:
    client = _FakeWorkerJavaClient()
    client.event_result = _response({"accepted": True, "taskStatus": "RETRY_WAIT"})

    with pytest.raises(ControlPlaneRejected) as captured:
        asyncio.run(
            JavaWorkerControlPlane(client).report_failure(
                TASK_ID,
                execution_no=2,
                execution_token=EXECUTION_TOKEN,
                code="QWEN_IMAGE_RESULT_TIMEOUT",
                submission_state=SubmissionState.UNKNOWN,
                traceparent=TEST_TRACEPARENT,
                provider="QWEN_IMAGE",
                model="qwen-image-3.0",
                provider_request_id=SUBMIT_REQUEST_ID,
            )
        )

    assert captured.value.code == "JAVA_TASK_FAILURE_RESPONSE_INVALID"
    assert len(client.calls) == 1


@pytest.mark.parametrize(
    ("provider", "model", "request_id", "task_id"),
    (
        ("QWEN_IMAGE", "wan3.0-video", None, None),
        ("WAN_VIDEO", "wan3.0-video", "not-a-uuid", None),
        ("WAN_VIDEO", "wan3.0-video", None, "task with spaces"),
        ("LOCAL_TASK_PROVIDER", "local-task-v1", None, None),
        (None, None, None, None),
    ),
)
def test_unknown_no_receipt_preserves_provider_and_identifier_validation(
    provider: str | None, model: str | None, request_id: str | None, task_id: str | None
) -> None:
    client = _FakeWorkerJavaClient()
    with pytest.raises(ControlPlaneRejected) as captured:
        asyncio.run(
            JavaWorkerControlPlane(client).report_failure(
                TASK_ID,
                execution_no=2,
                execution_token=EXECUTION_TOKEN,
                code="PROVIDER_SUBMISSION_UNKNOWN",
                submission_state=SubmissionState.UNKNOWN,
                traceparent=TEST_TRACEPARENT,
                provider=provider,
                model=model,
                provider_request_id=request_id,
                provider_task_id=task_id,
            )
        )
    assert captured.value.code == "JAVA_CONTROL_PLANE_REQUEST_INVALID"
    assert client.calls == []


@pytest.mark.parametrize(
    ("submission_state", "task_status", "stage_code"),
    (
        (SubmissionState.NOT_SUBMITTED, "FAILED", "FAILED"),
        (SubmissionState.CONFIRMED, "FAILED", "FAILED"),
        (SubmissionState.UNKNOWN, "RETRY_WAIT", "PROVIDER_RESULT_UNKNOWN"),
    ),
)
def test_failure_event_uses_exact_details_and_stable_terminal_mapping(
    submission_state: SubmissionState,
    task_status: str,
    stage_code: str,
) -> None:
    client = _FakeWorkerJavaClient()
    client.event_result = _response({"accepted": True, "taskStatus": task_status})
    control = JavaWorkerControlPlane(client)

    continuation = (
        {
            "provider": "QWEN_IMAGE",
            "model": "qwen-image-3.0",
            "provider_request_id": SUBMIT_REQUEST_ID,
            "provider_task_id": "qwen-task-1",
        }
        if submission_state is SubmissionState.UNKNOWN
        else {}
    )

    asyncio.run(
        control.report_failure(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            code="PROVIDER_SUBMISSION_UNKNOWN",
            submission_state=submission_state,
            traceparent=TEST_TRACEPARENT,
            **continuation,
        )
    )

    event = client.calls[-1]
    assert event[0] == "event"
    assert event[2]["eventType"] == "TASK_FAILED"
    assert event[2]["stageCode"] == stage_code
    expected_details: dict[str, object] = {
        "code": "PROVIDER_SUBMISSION_UNKNOWN",
        "submissionState": submission_state.value,
    }
    if submission_state is SubmissionState.UNKNOWN:
        expected_details.update(
            {
                "provider": "QWEN_IMAGE",
                "model": "qwen-image-3.0",
                "providerRequestId": SUBMIT_REQUEST_ID,
                "providerTaskId": "qwen-task-1",
            }
        )
    assert event[2]["details"] == expected_details


@pytest.mark.parametrize(
    ("error", "exception_type", "expected_code"),
    [
        (
            JavaInternalClientError(JavaInternalErrorCode.CONNECTION_FAILED),
            ControlPlaneUnavailable,
            "JAVA_CONTROL_PLANE_CONNECTION_FAILED",
        ),
        (
            JavaInternalClientError(JavaInternalErrorCode.TIMEOUT),
            ControlPlaneUnavailable,
            "JAVA_CONTROL_PLANE_TIMEOUT",
        ),
        (
            JavaInternalClientError(JavaInternalErrorCode.UPSTREAM_5XX, status_code=503),
            ControlPlaneUnavailable,
            "JAVA_CONTROL_PLANE_UPSTREAM_UNAVAILABLE",
        ),
        (
            JavaInternalClientError(JavaInternalErrorCode.AUTHENTICATION_REJECTED, status_code=401),
            ControlPlaneUnavailable,
            "JAVA_CONTROL_PLANE_AUTHENTICATION_REJECTED",
        ),
        (
            JavaInternalClientError(JavaInternalErrorCode.HTTP_ERROR, status_code=404),
            ControlPlaneRejected,
            "JAVA_CONTROL_PLANE_NOT_FOUND",
        ),
        (
            JavaInternalClientError(JavaInternalErrorCode.HTTP_ERROR, status_code=409),
            ControlPlaneRejected,
            "JAVA_CONTROL_PLANE_CONFLICT",
        ),
        (
            JavaInternalClientError(JavaInternalErrorCode.HTTP_ERROR, status_code=422),
            ControlPlaneRejected,
            "JAVA_CONTROL_PLANE_REQUEST_REJECTED",
        ),
    ],
)
def test_claim_error_statuses_map_without_exposing_java_body(
    error: JavaInternalClientError,
    exception_type: type[ControlPlaneUnavailable] | type[ControlPlaneRejected],
    expected_code: str,
) -> None:
    client = _FakeWorkerJavaClient()
    client.claim_result = error

    with pytest.raises(exception_type) as captured:
        asyncio.run(
            JavaWorkerControlPlane(client).claim(
                _message(),
                consumer_name="worker-consumer",
                worker_id="worker-1",
                bundle_version="bundle-v1",
            )
        )

    assert captured.value.code == expected_code
    assert str(captured.value) == expected_code


def test_claim_rejects_attempt_above_java_limit_without_http_call() -> None:
    client = _FakeWorkerJavaClient()
    with pytest.raises(ControlPlaneRejected) as captured:
        asyncio.run(
            JavaWorkerControlPlane(client).claim(
                _message(attempt=33),
                consumer_name="worker-consumer",
                worker_id="worker-1",
                bundle_version="bundle-v1",
            )
        )
    assert captured.value.code == "JAVA_CONTROL_PLANE_REQUEST_INVALID"
    assert client.calls == []


def test_claim_rejects_java_owned_media_task_before_http() -> None:
    client = _FakeWorkerJavaClient()
    message = _message().model_copy(update={"task_type": TaskType.MEDIA})

    with pytest.raises(ControlPlaneRejected) as captured:
        asyncio.run(
            JavaWorkerControlPlane(client).claim(
                message,
                consumer_name="worker-consumer",
                worker_id="worker-1",
                bundle_version="bundle-v1",
            )
        )

    assert captured.value.code == "JAVA_CONTROL_PLANE_REQUEST_INVALID"
    assert client.calls == []


@pytest.mark.parametrize(
    "message",
    (
        _message(task_id="9223372036854775808"),
        _message(event_id="9223372036854775808"),
    ),
)
def test_claim_rejects_ids_outside_java_signed_long_without_http_call(
    message: TaskExecutionMessage,
) -> None:
    client = _FakeWorkerJavaClient()
    with pytest.raises(ControlPlaneRejected) as captured:
        asyncio.run(
            JavaWorkerControlPlane(client).claim(
                message,
                consumer_name="worker-consumer",
                worker_id="worker-1",
                bundle_version="bundle-v1",
            )
        )
    assert captured.value.code == "JAVA_CONTROL_PLANE_REQUEST_INVALID"
    assert client.calls == []


def test_result_boundary_rejects_non_mock_or_malformed_image_evidence() -> None:
    async def scenario() -> None:
        provider = LocalTaskProviderMock(ProviderMockSettings(latency_ms=0))
        valid = await provider.execute(
            TaskProviderRequest(
                request_id=UUID("123e4567-e89b-12d3-a456-426614174000"),
                task_id=TASK_ID,
                task_type=TaskType.IMAGE,
                execution_no=1,
                parameters={},
            )
        )
        invalid_results = (
            valid.model_copy(update={"provider": "QWEN_IMAGE"}),
            valid.model_copy(update={"provider_task_id": "other-task"}),
            valid.model_copy(update={"artifacts": ()}),
            valid.model_copy(
                update={
                    "artifacts": (
                        valid.artifacts[0].model_copy(update={"metadata": {"mock": False}}),
                    )
                }
            ),
        )
        for invalid in invalid_results:
            client = _FakeWorkerJavaClient()
            with pytest.raises(ControlPlaneRejected) as captured:
                await JavaWorkerControlPlane(client).report_result(
                    TASK_ID,
                    execution_no=1,
                    execution_token=EXECUTION_TOKEN,
                    result=invalid,
                    traceparent=TEST_TRACEPARENT,
                )
            assert captured.value.code == "JAVA_CONTROL_PLANE_REQUEST_INVALID"
            assert client.calls == []
        await provider.aclose()

    asyncio.run(scenario())


def test_java_error_body_execution_token_and_context_never_enter_logs(
    caplog: pytest.LogCaptureFixture,
) -> None:
    sensitive_body = "synthetic-java-private-response"

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, text=sensitive_body)

    async def scenario() -> None:
        http_client = httpx.AsyncClient(
            base_url="http://127.0.0.1:8080",
            transport=httpx.MockTransport(handler),
        )
        shared_client = JavaInternalClient(
            internal_settings(java_internal_base_url="http://127.0.0.1:8080"),
            http_client,
        )
        try:
            with pytest.raises(ControlPlaneRejected):
                await JavaWorkerControlPlane(shared_client).claim(
                    _message(),
                    consumer_name="worker-consumer",
                    worker_id="worker-1",
                    bundle_version="bundle-v1",
                )
        finally:
            await shared_client.aclose()

    with caplog.at_level(logging.DEBUG):
        asyncio.run(scenario())

    logs = caplog.text
    assert sensitive_body not in logs
    assert EXECUTION_TOKEN not in logs
    assert "白色背景上的简约陶瓷马克杯" not in logs


def test_control_plane_closes_its_shared_java_client() -> None:
    client = _FakeWorkerJavaClient()
    asyncio.run(JavaWorkerControlPlane(client).aclose())
    assert client.closed is True


@pytest.mark.parametrize("mode", ("TITLE", "OUTLINE", "BODY", "REWRITE"))
def test_java_control_plane_maps_all_strict_article_stage_contexts(mode: str) -> None:
    client = _FakeWorkerJavaClient()
    client.context_result = _response(_article_context_payload(mode))

    context = asyncio.run(
        JavaWorkerControlPlane(client).get_execution_context(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            traceparent=TEST_TRACEPARENT,
        )
    )

    assert isinstance(context, ArticleExecutionContext)
    assert context.request.mode.value == mode
    assert context.request.article_id == "1900012345678903001"
    assert context.request.source_asset_ids == ("1900012345678903101",)
    assert context.request.geo_report_id == "1900012345678903201"
    assert context.request.budget.max_repair_retries == 1
    if mode in {"BODY", "REWRITE"}:
        assert [item.key for item in context.authorized_outline] == ["opening", "usage"]
        assert context.request.confirmed_plan is not None
        assert context.request.confirmed_plan.sections == context.request.confirmed_outline
        assert context.request.confirmed_plan.core_message == (
            "先明确场景，再核对有来源的商品参数。"
        )
    if mode == "REWRITE":
        assert context.request.current_body is not None
        assert context.request.current_body.blocks[1].markdown == "再核对已知参数。"


def test_java_control_plane_accepts_committed_article_runtime_snapshot_shape() -> None:
    payload = _article_context_payload("TITLE")
    documented = cast(dict[str, object], payload["inputSnapshot"])
    payload["inputSnapshot"] = documented["parameters"]
    client = _FakeWorkerJavaClient()
    client.context_result = _response(payload)

    context = asyncio.run(
        JavaWorkerControlPlane(client).get_execution_context(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            traceparent=TEST_TRACEPARENT,
        )
    )

    assert isinstance(context, ArticleExecutionContext)
    assert context.request.mode is ArticleStage.TITLE


@pytest.mark.parametrize("snapshot_shape", ("wrapped", "direct"))
@pytest.mark.parametrize(
    "recommendation_id",
    (None, "1900012345678903201:recommendation:1"),
)
def test_article_context_accepts_geo_recommendation_metadata_without_prompt_leak(
    snapshot_shape: str, recommendation_id: str | None
) -> None:
    payload = _article_context_payload("TITLE")
    wrapped = cast(dict[str, object], payload["inputSnapshot"])
    parameters = cast(dict[str, object], wrapped["parameters"])
    parameters["geoRecommendationId"] = recommendation_id
    if snapshot_shape == "direct":
        payload["inputSnapshot"] = parameters
    client = _FakeWorkerJavaClient()
    client.context_result = _response(payload)

    context = asyncio.run(
        JavaWorkerControlPlane(client).get_execution_context(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            traceparent=TEST_TRACEPARENT,
        )
    )

    assert isinstance(context, ArticleExecutionContext)
    assert context.request.mode is ArticleStage.TITLE
    assert "geoRecommendationId" not in context.request.model_dump_json(by_alias=True)


@pytest.mark.parametrize("mode", ("TITLE", "OUTLINE", "BODY"))
@pytest.mark.parametrize("snapshot_shape", ("wrapped", "direct"))
@pytest.mark.parametrize("ordinal", (None, 1, 30))
def test_article_context_accepts_nonempty_java_geo_recommendations_without_reference_leak(
    mode: str, snapshot_shape: str, ordinal: int | None
) -> None:
    payload = _article_context_payload(mode)
    authorized = cast(dict[str, object], payload["authorizedContext"])
    report = cast(dict[str, object], authorized["geoReport"])
    recommendation: dict[str, object] = {
        "priority": "P0",
        "title": "介绍咖啡店的已知品类",
        "rationale": "本次样本未提及门店，应补充有来源的店铺介绍，不承诺排名。",
    }
    if ordinal is not None:
        recommendation["id"] = f"{report['id']}:recommendation:{ordinal}"
    report.update(
        {
            "coverageGaps": ["本次样本未提及小行星咖啡店。"],
            "findings": ["咖啡和下午茶是已采集问题的阅读场景。"],
            "recommendations": [recommendation],
            "contentBlueprint": ["只介绍知识库确认的咖啡、曲奇和蛋糕品类。"],
            "missingSamples": [],
        }
    )
    if snapshot_shape == "direct":
        payload["inputSnapshot"] = cast(dict[str, object], payload["inputSnapshot"])["parameters"]
    client = _FakeWorkerJavaClient()
    client.context_result = _response(payload)

    context = asyncio.run(
        JavaWorkerControlPlane(client).get_execution_context(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            traceparent=TEST_TRACEPARENT,
        )
    )

    assert isinstance(context, ArticleExecutionContext)
    assert context.request.geo_report_summary is not None
    assert context.request.geo_report_summary.geo_report_id == report["id"]
    assert context.request.geo_report_summary.recommendations[0].title == recommendation["title"]
    assert context.request.geo_report_summary.coverage_gaps == tuple(
        cast(list[str], report["coverageGaps"])
    )
    assert context.request.geo_report_summary.content_blueprint == tuple(
        cast(list[str], report["contentBlueprint"])
    )
    assert ":recommendation:" not in context.request.model_dump_json(by_alias=True)


@pytest.mark.parametrize(
    "mutation,value",
    (
        ("id", "1900012345678903999:recommendation:1"),
        ("id", 123),
        ("id", "1900012345678903201:recommendation:0"),
        ("id", "1900012345678903201:recommendation:31"),
        ("id", "1900012345678903201:recommendation:01"),
        ("id", "1900012345678903201:recommendation:+1"),
        ("id", "1900012345678903201:recommendation:one"),
        ("id", "1900012345678903201:recommendation:1\n"),
        ("unexpected", "not-authorized"),
    ),
)
def test_article_context_rejects_foreign_or_invalid_recommendation_reference_and_unknown_fields(
    mutation: str, value: object
) -> None:
    payload = _article_context_payload("TITLE")
    report = cast(
        dict[str, object], cast(dict[str, object], payload["authorizedContext"])["geoReport"]
    )
    recommendation: dict[str, object] = {
        "id": "1900012345678903201:recommendation:1",
        "priority": "P0",
        "title": "补充门店资料",
        "rationale": "仅使用有来源的信息。",
    }
    recommendation[mutation] = value
    report["recommendations"] = [recommendation]
    client = _FakeWorkerJavaClient()
    client.context_result = _response(payload)

    with pytest.raises(ControlPlaneUnavailable, match="JAVA_CONTROL_PLANE_RESPONSE_INVALID"):
        asyncio.run(
            JavaWorkerControlPlane(client).get_execution_context(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                traceparent=TEST_TRACEPARENT,
            )
        )


@pytest.mark.parametrize(
    "recommendation_id",
    (
        "1900012345678903999:recommendation:1",
        "1900012345678903201:recommendation:0",
        "1900012345678903201:recommendation:not-a-number",
        "1900012345678903201:recommendation:2147483648",
        123,
    ),
)
def test_article_context_rejects_invalid_geo_recommendation_metadata(
    recommendation_id: object,
) -> None:
    payload = _article_context_payload("TITLE")
    parameters = cast(
        dict[str, object], cast(dict[str, object], payload["inputSnapshot"])["parameters"]
    )
    parameters["geoRecommendationId"] = recommendation_id
    client = _FakeWorkerJavaClient()
    client.context_result = _response(payload)

    with pytest.raises(ControlPlaneUnavailable, match="JAVA_CONTROL_PLANE_RESPONSE_INVALID"):
        asyncio.run(
            JavaWorkerControlPlane(client).get_execution_context(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                traceparent=TEST_TRACEPARENT,
            )
        )


def test_java_control_plane_rejects_unknown_field_in_runtime_snapshot_shape() -> None:
    payload = _article_context_payload("TITLE")
    documented = cast(dict[str, object], payload["inputSnapshot"])
    direct = cast(dict[str, object], documented["parameters"])
    direct["unexpected"] = True
    payload["inputSnapshot"] = direct
    client = _FakeWorkerJavaClient()
    client.context_result = _response(payload)

    with pytest.raises(ControlPlaneUnavailable, match="JAVA_CONTROL_PLANE_RESPONSE_INVALID"):
        asyncio.run(
            JavaWorkerControlPlane(client).get_execution_context(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                traceparent=TEST_TRACEPARENT,
            )
        )


@pytest.mark.parametrize(
    "mutation",
    (
        "unknown",
        "articleId",
        "articleVersion",
        "sourceAssetIds",
        "geoReportId",
        "outline",
        "plan",
        "rewriteScope",
    ),
)
def test_article_context_rejects_unknown_or_mismatched_authority(mutation: str) -> None:
    payload = _article_context_payload("REWRITE")
    parameters = cast(
        dict[str, object], cast(dict[str, object], payload["inputSnapshot"])["parameters"]
    )
    authorized = cast(dict[str, object], payload["authorizedContext"])
    article = cast(dict[str, object], authorized["article"])
    if mutation == "unknown":
        authorized["unknown"] = True
    elif mutation == "articleId":
        article["id"] = "1900012345678903999"
    elif mutation == "articleVersion":
        article["version"] = 4
    elif mutation == "sourceAssetIds":
        parameters["sourceAssetIds"] = []
    elif mutation == "geoReportId":
        parameters["geoReportId"] = None
    elif mutation == "outline":
        cast(list[dict[str, object]], article["outline"])[0]["sequence"] = 2
    elif mutation == "plan":
        cast(dict[str, object], article["confirmedPlan"])["coreMessage"] = "越权修改方案"
    else:
        article["rewriteBlockIds"] = ["opening"]
    client = _FakeWorkerJavaClient()
    client.context_result = _response(payload)

    with pytest.raises(ControlPlaneUnavailable, match="JAVA_CONTROL_PLANE_RESPONSE_INVALID"):
        asyncio.run(
            JavaWorkerControlPlane(client).get_execution_context(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                traceparent=TEST_TRACEPARENT,
            )
        )


@pytest.mark.parametrize(
    "prompt_version",
    [
        "agent-bundle-v1.12.0",
        "agent-bundle-v1.16.0",
        "agent-bundle-v1.17.0",
        "agent-bundle-v1.18.0",
        "agent-bundle-v1.19.0",
        "agent-bundle-v1.20.0",
        "agent-bundle-v1.21.0",
        "agent-bundle-v1.22.0",
    ],
)
def test_article_result_maps_exact_java_contract_and_requires_terminal_acceptance(
    prompt_version: str,
) -> None:
    result = ArticleExecutionResult(
        mode=ArticleStage.TITLE,
        title_candidates=(
            ArticleTitleCandidate(text="标题一", rationale="强调场景"),
            ArticleTitleCandidate(text="标题二", rationale="说明方法"),
            ArticleTitleCandidate(text="标题三", rationale="保持克制"),
            ArticleTitleCandidate(text="标题四", rationale="提供清单"),
            ArticleTitleCandidate(text="标题五", rationale="强调核验"),
        ),
        provider="DEEPSEEK",
        model="deepseek-v4-flash",
        model_version="chat-v1",
        prompt_version=prompt_version,
        usage=ArticleUsage(
            model_call_count=1,
            input_tokens=123,
            output_tokens=45,
            latency_ms=678,
        ),
        metadata=ArticleExecutionMetadata(),
    )
    completion = ArticleCompletion(
        article_version=3,
        result=result,
        outline=(),
        body=None,
    )
    client = _FakeWorkerJavaClient()

    status = asyncio.run(
        JavaWorkerControlPlane(client).report_article_completion(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            completion=completion,
            traceparent=TEST_TRACEPARENT,
        )
    )

    assert status == "SUCCEEDED"
    call = client.calls[-1]
    assert call[0] == "result" and call[3] == TEST_TRACEPARENT
    payload = call[2]
    assert payload["providerTaskId"] is None
    assert payload["submissionState"] == "CONFIRMED"
    assert payload["usage"] == {
        "inputTokens": 123,
        "outputTokens": 45,
        "images": 0,
        "videoSeconds": 0,
        "providerCost": None,
        "currency": None,
    }
    artifact = cast(list[dict[str, object]], payload["artifacts"])[0]
    assert artifact["unitKey"] == "article:title"
    assert artifact["schemaVersion"] == 2
    data = cast(dict[str, object], artifact["inlineData"])
    assert data["articleVersion"] == 3
    assert data["titleCandidates"] == ["标题一", "标题二", "标题三", "标题四", "标题五"]
    assert data["plan"] is None
    assert "rationale" not in json.dumps(data, ensure_ascii=False)


@pytest.mark.parametrize("unknown_version", (False, True))
def test_current_article_kernel_result_survives_worker_handoff_not_unknown_versions(
    unknown_version: bool,
) -> None:
    gateway = _StructuredGateway({"candidates": _title_candidates()})
    result = asyncio.run(
        ArticleExecutionKernel(cast(ModelGateway, gateway), _prompts()).execute(
            article_kernel_request(ArticleStage.TITLE)
        )
    )
    assert result.prompt_version == _prompts().bundle_version
    if unknown_version:
        result = result.model_copy(update={"prompt_version": "agent-bundle-v9.99.0"})
    completion = ArticleCompletion(article_version=3, result=result, outline=(), body=None)
    client = _FakeWorkerJavaClient()
    call = JavaWorkerControlPlane(client).report_article_completion(
        TASK_ID,
        execution_no=1,
        execution_token=EXECUTION_TOKEN,
        completion=completion,
        traceparent=TEST_TRACEPARENT,
    )
    if unknown_version:
        with pytest.raises(ControlPlaneRejected, match="INVALID"):
            asyncio.run(call)
        assert not client.calls
    else:
        assert asyncio.run(call) == "SUCCEEDED"
        assert client.calls[-1][0] == "result"


def test_article_plan_result_maps_complete_structure_and_legacy_outline_mirror() -> None:
    sections = (
        ArticleOutlineItem(
            key="section-1",
            sequence=1,
            level=2,
            heading="选择标准",
            brief="说明选择维度",
        ),
        ArticleOutlineItem(
            key="section-2",
            sequence=2,
            level=2,
            heading="使用建议",
            brief="给出有边界的使用建议",
        ),
    )
    plan = ArticlePlan(
        summary="说明如何按使用场景选择商品。",
        core_message="先明确场景，再核对有来源的商品参数。",
        target_audience="需要购买建议的新手用户",
        sections=sections,
        tone="PROFESSIONAL",
        target_length=ArticleTargetLength.SHORT,
        target_platform=None,
        risk_notes=("避免未经证实的绝对化卖点。",),
    )
    result = ArticleExecutionResult(
        mode=ArticleStage.OUTLINE,
        title="如何按使用场景选择商品",
        plan=plan,
        outline=sections,
        provider="DEEPSEEK",
        model="deepseek-v4-flash",
        model_version="chat-v1",
        prompt_version="agent-bundle-v1.12.0",
        usage=ArticleUsage(model_call_count=1, input_tokens=20, output_tokens=10, latency_ms=5),
        metadata=ArticleExecutionMetadata(),
    )
    completion = ArticleCompletion(
        article_version=3,
        result=result,
        outline=tuple(
            ArticleOutlineSnapshot(
                item.key,
                item.sequence,
                item.level,
                item.heading,
                item.brief,
            )
            for item in sections
        ),
        body=None,
    )
    client = _FakeWorkerJavaClient()

    asyncio.run(
        JavaWorkerControlPlane(client).report_article_completion(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            completion=completion,
            traceparent=TEST_TRACEPARENT,
        )
    )

    artifact = cast(list[dict[str, object]], client.calls[-1][2]["artifacts"])[0]
    data = cast(dict[str, object], artifact["inlineData"])
    assert data["plan"] == plan.model_dump(mode="json", by_alias=True)
    assert cast(dict[str, object], data["plan"])["sections"] == data["outline"]


def test_article_rewrite_result_reports_only_authorized_blocks() -> None:
    outline = (
        ArticleOutlineSnapshot("opening", 1, 2, "选择标准", "说明选择维度"),
        ArticleOutlineSnapshot("usage", 2, 2, "使用建议", "给出有边界的使用建议"),
    )
    internal_outline = tuple(
        ArticleOutlineItem(
            key=item.key,
            sequence=item.sequence,
            level=item.level,
            heading=item.heading,
            brief=item.brief or item.heading,
        )
        for item in outline
    )
    selected = ArticleBodyBlock(key="usage", heading="使用建议", markdown="先核对场景。")
    body = ArticleBody(markdown=render_article_markdown((selected,)), blocks=(selected,))
    result = ArticleExecutionResult(
        mode=ArticleStage.REWRITE,
        title="如何按使用场景选择商品",
        outline=internal_outline,
        body=ArticleBody(
            markdown=render_article_markdown(
                (
                    ArticleBodyBlock(
                        key="opening", heading="选择标准", markdown="先明确使用场景。"
                    ),
                    selected,
                )
            ),
            blocks=(
                ArticleBodyBlock(key="opening", heading="选择标准", markdown="先明确使用场景。"),
                selected,
            ),
        ),
        provider="DEEPSEEK",
        model="deepseek-v4-flash",
        model_version="chat-v1",
        prompt_version="agent-bundle-v1.12.0",
        usage=ArticleUsage(model_call_count=1, input_tokens=10, output_tokens=8, latency_ms=5),
        metadata=ArticleExecutionMetadata(authorized_rewrite_block_ids=("usage",)),
    )
    client = _FakeWorkerJavaClient()
    asyncio.run(
        JavaWorkerControlPlane(client).report_article_completion(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            completion=ArticleCompletion(
                article_version=3,
                result=result,
                outline=outline,
                body=body,
            ),
            traceparent=TEST_TRACEPARENT,
        )
    )
    payload = client.calls[-1][2]
    artifact = cast(list[dict[str, object]], payload["artifacts"])[0]
    data = cast(dict[str, object], artifact["inlineData"])
    reported_body = cast(dict[str, object], data["body"])
    assert [item["key"] for item in cast(list[dict[str, object]], reported_body["blocks"])] == [
        "usage"
    ]


@pytest.mark.parametrize("count", (1, 2, 4))
def test_worker_runtime_executes_image_mock_through_real_java_control_mapping(count: int) -> None:
    class CountingImageMock(LocalTaskProviderMock):
        def __init__(self) -> None:
            super().__init__(ProviderMockSettings(latency_ms=0))
            self.calls = 0

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            self.calls += 1
            return await super().execute(request)

    async def scenario() -> tuple[str, list[str], int, str, str | None]:
        java_client = _FakeWorkerJavaClient()
        context = _context_payload()
        cast(dict[str, object], context["inputSnapshot"])["count"] = count
        cast(dict[str, object], context["budget"])["maxImages"] = count
        java_client.context_result = _response(context)
        provider = CountingImageMock()
        worker = WorkerRuntime(
            initialize_runtime(AppSettings(environment="test")),
            provider=provider,
            control_plane=JavaWorkerControlPlane(java_client),
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        delivery = RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        assert snapshot.artifact_count == count
        result = (
            delivery.state,
            [call[0] for call in java_client.calls],
            provider.calls,
            snapshot.status.value,
            snapshot.error_code,
        )
        await worker.close()
        assert java_client.closed is True
        return result

    delivery_state, calls, provider_calls, task_status, error_code = asyncio.run(scenario())
    assert delivery_state == "ACKED"
    assert calls == ["claim", "context", "heartbeat", "event", "result"]
    assert provider_calls == 1
    assert task_status == "RESULT_READY"
    assert error_code is None


def test_real_provider_must_match_java_identity_before_submission() -> None:
    class WrongModelProvider(LocalTaskProviderMock):
        code = "QWEN_IMAGE"
        model = "unapproved-image-model"
        is_mock = False

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            raise AssertionError("identity mismatch must never submit")

    async def scenario() -> None:
        java_client = _FakeWorkerJavaClient()
        java_client.failure_event_result = _response({"accepted": True, "taskStatus": "FAILED"})
        worker = WorkerRuntime(
            initialize_runtime(AppSettings(environment="test")),
            provider=WrongModelProvider(),
            control_plane=JavaWorkerControlPlane(java_client),
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        await worker.process_delivery(
            RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
        )
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        assert snapshot.error_code == "PROVIDER_CONTEXT_IDENTITY_MISMATCH"
        assert "result" not in [call[0] for call in java_client.calls]
        await worker.close()

    asyncio.run(scenario())


@pytest.mark.parametrize(("requested_count", "actual_count"), ((1, 1), (4, 4), (4, 2), (4, 1)))
def test_real_qwen_running_handoff_is_processed_logged_safely_and_not_repeated(
    caplog: pytest.LogCaptureFixture,
    requested_count: int,
    actual_count: int,
) -> None:
    class CountingQwenProvider(LocalTaskProviderMock):
        code = "QWEN_IMAGE"
        model = "qwen-image-3.0"
        is_mock = False

        def __init__(self) -> None:
            super().__init__(ProviderMockSettings(latency_ms=0))
            self.submit_calls = 0

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            self.submit_calls += 1
            assert request.parameters["count"] == requested_count
            return _qwen_result(request_id=request.request_id, count=actual_count)

    async def scenario() -> tuple[WorkerTaskStatus, WorkerStatusSnapshot, int, int, int, int]:
        java_client = _FakeWorkerJavaClient()
        context = _context_payload()
        cast(dict[str, object], context["inputSnapshot"])["count"] = requested_count
        cast(dict[str, object], context["budget"])["maxImages"] = requested_count
        java_client.context_result = _response(context)
        java_client.result_result = _response({"accepted": True, "taskStatus": "RUNNING"})
        provider = CountingQwenProvider()
        worker = WorkerRuntime(
            initialize_runtime(AppSettings(environment="test")),
            provider=provider,
            control_plane=JavaWorkerControlPlane(java_client),
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        message_body = _message().model_dump_json(by_alias=True).encode()
        with caplog.at_level(logging.INFO, logger="app.workers.runtime"):
            await worker.process_delivery(RawDelivery(body=message_body))
        first_snapshot = worker.get_task(EVENT_ID)
        assert first_snapshot is not None
        assert first_snapshot.artifact_count == actual_count
        result_payload = next(call[2] for call in java_client.calls if call[0] == "result")
        reported_artifacts = cast(list[dict[str, object]], result_payload["artifacts"])
        assert len(reported_artifacts) == actual_count
        for index, artifact in enumerate(reported_artifacts, start=1):
            metadata = cast(dict[str, object], artifact["metadata"])
            assert metadata["index"] == index
            assert "outputImageType" not in metadata
            assert "providerResultRequestId" not in metadata
        first_status = worker.status_snapshot()
        first_task_status = first_snapshot.status

        java_client.claim_result = _response(
            {
                "claimed": False,
                "disposition": "DUPLICATE",
                "executionNo": 1,
                "executionToken": EXECUTION_TOKEN,
                "leaseExpiresAt": LEASE_EXPIRES_AT,
            }
        )
        await worker.process_delivery(RawDelivery(body=message_body))
        final_status = worker.status_snapshot()
        result_calls = sum(call[0] == "result" for call in java_client.calls)
        await worker.close()
        return (
            first_task_status,
            first_status,
            final_status.processed,
            final_status.failed,
            provider.submit_calls,
            result_calls,
        )

    (
        first_task_status,
        first_status,
        final_processed,
        final_failed,
        provider_submit_calls,
        result_calls,
    ) = asyncio.run(scenario())
    assert first_task_status.value == "HANDOFF_ACCEPTED"
    assert first_status.processed == 1
    assert first_status.failed == 0
    assert first_status.in_flight == 0
    assert final_processed == 2
    assert final_failed == 0
    assert provider_submit_calls == 1
    assert result_calls == 1

    records = [
        record for record in caplog.records if record.message == "worker_result_handoff_accepted"
    ]
    assert len(records) == 1
    formatted = JsonFormatter().format(records[0])
    payload = json.loads(formatted)
    assert payload["provider"] == "QWEN_IMAGE"
    assert payload["operation"] == "RESULT_HANDOFF"
    assert payload["status"] == "ACCEPTED"
    assert payload["javaTaskStatus"] == "RUNNING"
    assert SUBMIT_REQUEST_ID not in formatted
    assert "qwen-task-123" not in formatted
    assert "https://result.example" not in formatted


def test_worker_resume_only_uses_continuation_without_provider_submit() -> None:
    class RecoveryOnlyProvider(LocalTaskProviderMock):
        code = "QWEN_IMAGE"
        model = "qwen-image-3.0"
        is_mock = False

        def __init__(self) -> None:
            super().__init__(ProviderMockSettings(latency_ms=0))
            self.submit_calls = 0
            self.resume_calls = 0
            self.resume_request: TaskProviderRequest | None = None

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            self.submit_calls += 1
            raise AssertionError(f"resumeOnly must not submit task {request.task_id}")

        async def resume(
            self,
            request: TaskProviderRequest,
            continuation: QwenImageProviderContinuation,
        ) -> TaskProviderResult:
            self.resume_calls += 1
            self.resume_request = request
            assert continuation.provider_request_id == UUID(SUBMIT_REQUEST_ID)
            assert continuation.provider_task_id == "qwen-task-1"
            return TaskProviderResult(
                provider=self.code,
                model=self.model,
                request_id=continuation.provider_request_id,
                provider_task_id=continuation.provider_task_id,
                artifacts=(
                    ProviderArtifact(
                        artifact_type="IMAGE",
                        unit_key="image:1",
                        provider_url="https://result.example/recovered.png",
                        content_type="image/png",
                        expires_at=datetime(2030, 9, 5, tzinfo=UTC),
                        metadata={
                            "index": 1,
                            "width": 1024,
                            "height": 1024,
                            "mock": False,
                            "providerRequestId": SUBMIT_REQUEST_ID,
                        },
                    ),
                ),
                usage=ProviderUsage(images=1),
                latency_ms=42,
            )

    async def scenario() -> tuple[RecoveryOnlyProvider, _FakeWorkerJavaClient, str, str]:
        java_client = _FakeWorkerJavaClient()
        claimed = _claimed_payload()
        claimed["executionNo"] = 2
        java_client.claim_result = _response(claimed)
        java_client.context_result = _response(_recovery_context_payload())
        provider = RecoveryOnlyProvider()
        worker = WorkerRuntime(
            initialize_runtime(AppSettings(environment="test")),
            provider=provider,
            control_plane=JavaWorkerControlPlane(java_client),
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        delivery = RawDelivery(body=_message(attempt=2).model_dump_json(by_alias=True).encode())
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        status = snapshot.status.value
        await worker.close()
        return provider, java_client, delivery.state, status

    provider, java_client, delivery_state, status = asyncio.run(scenario())
    assert delivery_state == "ACKED"
    assert status == "RESULT_READY"
    assert provider.submit_calls == 0
    assert provider.resume_calls == 1
    assert provider.resume_request is not None
    assert provider.resume_request.request_id == UUID(SUBMIT_REQUEST_ID)
    assert provider.resume_request.execution_no == 2
    assert provider.resume_request.traceparent == TEST_TRACEPARENT
    assert [call[0] for call in java_client.calls] == [
        "claim",
        "context",
        "heartbeat",
        "event",
        "result",
    ]
    assert java_client.calls[2][2]["stageCode"] == "PROVIDER_RESULT_RECOVERY"
    assert java_client.calls[3][2]["stageCode"] == "PROVIDER_RESULT_RECOVERY"
    assert all(call[3] == TEST_TRACEPARENT for call in java_client.calls)


def test_worker_resume_uncertainty_reports_exact_qwen_ids_without_submit() -> None:
    class StillUnknownProvider(LocalTaskProviderMock):
        code = "QWEN_IMAGE"
        model = "qwen-image-3.0"
        is_mock = False

        def __init__(self) -> None:
            super().__init__(ProviderMockSettings(latency_ms=0))
            self.submit_calls = 0
            self.resume_calls = 0

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            self.submit_calls += 1
            raise AssertionError(f"resumeOnly must not submit task {request.task_id}")

        async def resume(
            self,
            request: TaskProviderRequest,
            continuation: QwenImageProviderContinuation,
        ) -> TaskProviderResult:
            del request
            self.resume_calls += 1
            raise TaskProviderError(
                "QWEN_IMAGE_RESULT_TIMEOUT",
                category=ProviderErrorCategory.TIMEOUT,
                retryable=False,
                submission_state=SubmissionState.UNKNOWN,
                provider_request_id=str(continuation.provider_request_id),
                provider_task_id=continuation.provider_task_id,
            )

    async def scenario() -> tuple[StillUnknownProvider, _FakeWorkerJavaClient, str | None]:
        java_client = _FakeWorkerJavaClient()
        claimed = _claimed_payload()
        claimed["executionNo"] = 2
        java_client.claim_result = _response(claimed)
        java_client.context_result = _response(_recovery_context_payload())
        java_client.failure_event_result = _response({"accepted": True, "taskStatus": "RETRY_WAIT"})
        provider = StillUnknownProvider()
        worker = WorkerRuntime(
            initialize_runtime(AppSettings(environment="test")),
            provider=provider,
            control_plane=JavaWorkerControlPlane(java_client),
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        delivery = RawDelivery(body=_message(attempt=2).model_dump_json(by_alias=True).encode())
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        error_code = snapshot.error_code
        await worker.close()
        return provider, java_client, error_code

    provider, java_client, error_code = asyncio.run(scenario())
    assert error_code == "QWEN_IMAGE_RESULT_TIMEOUT"
    assert provider.submit_calls == 0
    assert provider.resume_calls == 1
    failure = java_client.calls[-1]
    assert failure[0] == "event"
    assert failure[2]["stageCode"] == "PROVIDER_RESULT_UNKNOWN"
    assert failure[2]["details"] == {
        "code": "QWEN_IMAGE_RESULT_TIMEOUT",
        "provider": "QWEN_IMAGE",
        "model": "qwen-image-3.0",
        "providerRequestId": SUBMIT_REQUEST_ID,
        "providerTaskId": "qwen-task-1",
        "submissionState": "UNKNOWN",
    }


def test_java_execution_budget_limits_retryable_provider_to_one_call() -> None:
    class AlwaysRetryableProvider(LocalTaskProviderMock):
        def __init__(self) -> None:
            super().__init__(ProviderMockSettings(latency_ms=0))
            self.calls = 0

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            self.calls += 1
            raise TaskProviderError(
                "PROVIDER_UPSTREAM_ERROR",
                category=ProviderErrorCategory.UPSTREAM,
                retryable=True,
                submission_state=SubmissionState.NOT_SUBMITTED,
            )

    async def scenario() -> tuple[int, str, str | None, list[tuple[str, dict[str, object]]]]:
        java_client = _FakeWorkerJavaClient()
        java_client.failure_event_result = _response({"accepted": True, "taskStatus": "FAILED"})
        provider = AlwaysRetryableProvider()
        worker = WorkerRuntime(
            initialize_runtime(
                AppSettings(
                    environment="test",
                    worker_provider_max_attempts=3,
                    worker_provider_retry_base_seconds=0,
                    worker_provider_retry_max_seconds=0,
                )
            ),
            provider=provider,
            control_plane=JavaWorkerControlPlane(java_client),
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        delivery = RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        events = [(call[0], call[2]) for call in java_client.calls]
        result = provider.calls, snapshot.status.value, snapshot.error_code, events
        await worker.close()
        return result

    provider_calls, task_status, error_code, calls = asyncio.run(scenario())
    assert provider_calls == 1
    assert task_status == "FAILED"
    assert error_code == "PROVIDER_UPSTREAM_ERROR"
    assert [name for name, _payload in calls] == [
        "claim",
        "context",
        "heartbeat",
        "event",
        "event",
    ]
    assert calls[-1][1]["eventType"] == "TASK_FAILED"
    assert calls[-1][1]["details"] == {
        "code": "PROVIDER_UPSTREAM_ERROR",
        "submissionState": "NOT_SUBMITTED",
    }


def test_java_execution_budget_rejects_more_than_one_image() -> None:
    class TooManyImagesProvider(LocalTaskProviderMock):
        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            valid = await super().execute(request)
            return valid.model_copy(
                update={
                    "artifacts": valid.artifacts + valid.artifacts,
                    "usage": valid.usage.model_copy(update={"images": 2}),
                }
            )

    async def scenario() -> tuple[str, str | None, list[str]]:
        java_client = _FakeWorkerJavaClient()
        java_client.failure_event_result = _response({"accepted": True, "taskStatus": "FAILED"})
        worker = WorkerRuntime(
            initialize_runtime(AppSettings(environment="test")),
            provider=TooManyImagesProvider(ProviderMockSettings(latency_ms=0)),
            control_plane=JavaWorkerControlPlane(java_client),
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        delivery = RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        result = (
            snapshot.status.value,
            snapshot.error_code,
            [call[0] for call in java_client.calls],
        )
        await worker.close()
        return result

    task_status, error_code, calls = asyncio.run(scenario())
    assert task_status == "FAILED"
    assert error_code == "PROVIDER_BUDGET_EXCEEDED"
    assert calls == ["claim", "context", "heartbeat", "event", "event"]


def test_java_image_count_is_checked_before_provider_submission() -> None:
    class CountingProvider(LocalTaskProviderMock):
        def __init__(self) -> None:
            super().__init__(ProviderMockSettings(latency_ms=0))
            self.calls = 0

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            self.calls += 1
            return await super().execute(request)

    async def scenario() -> tuple[int, str | None, list[tuple[str, dict[str, object]]]]:
        java_client = _FakeWorkerJavaClient()
        invalid_context = _context_payload()
        input_snapshot = cast(dict[str, object], invalid_context["inputSnapshot"])
        input_snapshot["count"] = 2
        java_client.context_result = _response(invalid_context)
        java_client.failure_event_result = _response({"accepted": True, "taskStatus": "FAILED"})
        provider = CountingProvider()
        worker = WorkerRuntime(
            initialize_runtime(AppSettings(environment="test")),
            provider=provider,
            control_plane=JavaWorkerControlPlane(java_client),
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        delivery = RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        calls = [(call[0], call[2]) for call in java_client.calls]
        result = provider.calls, snapshot.error_code, calls
        await worker.close()
        return result

    provider_calls, error_code, calls = asyncio.run(scenario())
    assert provider_calls == 0
    assert error_code == "PROVIDER_IMAGE_BUDGET_INVALID"
    assert [name for name, _payload in calls] == ["claim", "context", "event"]
    assert calls[-1][1]["details"] == {
        "code": "PROVIDER_IMAGE_BUDGET_INVALID",
        "submissionState": "NOT_SUBMITTED",
    }


@pytest.mark.parametrize(
    ("error", "delivery_state", "requeue", "worker_status"),
    (
        (
            JavaInternalClientError(
                JavaInternalErrorCode.AUTHENTICATION_REJECTED,
                status_code=401,
            ),
            "NACKED",
            True,
            "NOT_READY",
        ),
        (
            JavaInternalClientError(JavaInternalErrorCode.UPSTREAM_5XX, status_code=503),
            "NACKED",
            True,
            "NOT_READY",
        ),
        (
            JavaInternalClientError(JavaInternalErrorCode.HTTP_ERROR, status_code=404),
            "REJECTED",
            False,
            "READY",
        ),
        (
            JavaInternalClientError(JavaInternalErrorCode.HTTP_ERROR, status_code=409),
            "REJECTED",
            False,
            "READY",
        ),
        (
            JavaInternalClientError(JavaInternalErrorCode.HTTP_ERROR, status_code=422),
            "REJECTED",
            False,
            "READY",
        ),
    ),
)
def test_worker_settlement_follows_java_claim_status_classification(
    error: JavaInternalClientError,
    delivery_state: str,
    requeue: bool,
    worker_status: str,
) -> None:
    class UnexpectedProvider(LocalTaskProviderMock):
        def __init__(self) -> None:
            super().__init__(ProviderMockSettings(latency_ms=0))
            self.calls = 0

        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            self.calls += 1
            raise AssertionError(f"Provider must not run after rejected claim: {request.task_id}")

    async def scenario() -> tuple[str, bool, str, int, list[str]]:
        java_client = _FakeWorkerJavaClient()
        java_client.claim_result = error
        provider = UnexpectedProvider()
        worker = WorkerRuntime(
            initialize_runtime(AppSettings(environment="test")),
            provider=provider,
            control_plane=JavaWorkerControlPlane(java_client),
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        delivery = RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
        await worker.process_delivery(delivery)
        status = worker.status_snapshot().status
        result = (
            delivery.state,
            delivery.requeue,
            status,
            provider.calls,
            [call[0] for call in java_client.calls],
        )
        await worker.close()
        return result

    actual_state, actual_requeue, actual_status, provider_calls, calls = asyncio.run(scenario())
    assert actual_state == delivery_state
    assert actual_requeue is requeue
    assert actual_status == worker_status
    assert provider_calls == 0
    assert calls == ["claim"]


@pytest.mark.parametrize("disposition", ("DUPLICATE", "TERMINAL"))
def test_worker_runtime_acks_non_claimed_java_disposition_without_provider(
    disposition: str,
) -> None:
    class UnexpectedProvider(LocalTaskProviderMock):
        async def execute(self, request: TaskProviderRequest) -> TaskProviderResult:
            raise AssertionError(f"Provider must not execute for {disposition}: {request.task_id}")

    async def scenario() -> tuple[str, list[str], str]:
        java_client = _FakeWorkerJavaClient()
        java_client.claim_result = _response(
            {
                "claimed": False,
                "disposition": disposition,
                "executionNo": None,
                "executionToken": None,
                "leaseExpiresAt": None,
            }
        )
        worker = WorkerRuntime(
            initialize_runtime(AppSettings(environment="test")),
            provider=UnexpectedProvider(ProviderMockSettings(latency_ms=0)),
            control_plane=JavaWorkerControlPlane(java_client),
            broker=LocalMessageBroker(),
        )
        assert await worker.start()
        delivery = RawDelivery(body=_message().model_dump_json(by_alias=True).encode())
        await worker.process_delivery(delivery)
        snapshot = worker.get_task(EVENT_ID)
        assert snapshot is not None
        result = delivery.state, [call[0] for call in java_client.calls], snapshot.status.value
        await worker.close()
        return result

    delivery_state, calls, task_status = asyncio.run(scenario())
    assert delivery_state == "ACKED"
    assert calls == ["claim"]
    assert task_status == "DUPLICATE"


def _geo_observation(question_id: str = "1900012345678902101") -> GeoObservation:
    now = datetime(2026, 9, 8, 12, tzinfo=UTC)
    return GeoObservation(
        platform_code=GeoPlatformCode.DOUBAO,
        question_id=question_id,
        sample_no=1,
        status=GeoObservationStatus.SUCCEEDED,
        answer_text="provider answer",
        provider="DOUBAO",
        model="doubao-test",
        started_at=now,
        finished_at=now,
        latency_ms=12,
        usage=GeoProviderUsage(input_tokens=3, output_tokens=2, search_requests=1),
        provider_call_count=1,
    )


def _stored_geo_observation(question_id: str) -> dict[str, object]:
    payload = _geo_observation(question_id).model_dump(mode="json", by_alias=True)
    payload.update(
        {
            "unitKey": f"{question_id}:DOUBAO:1",
            "questionText": "authorized question",
        }
    )
    return payload


def _geo_synthesis() -> GeoSynthesis:
    return GeoSynthesis(
        summary="bounded summary",
        coverage_gaps=(),
        citation_structure=(),
        competitor_signals=(),
        findings=("one finding",),
        recommendations=(),
        content_blueprint=(),
        missing_samples=(),
        method_version="geo-synthesis-v1",
    )


def test_java_control_plane_claims_and_maps_strict_geo_context() -> None:
    async def scenario() -> tuple[ClaimResult, GeoExecutionContext]:
        client = _FakeWorkerJavaClient()
        client.context_result = _response(_geo_context_payload())
        control = JavaWorkerControlPlane(client)
        claim = await control.claim(
            _message(task_type=TaskType.GEO),
            consumer_name="geo-worker-v1",
            worker_id="geo-worker-01",
            bundle_version="agent-bundle-v1.6.0",
        )
        context = await control.get_execution_context(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            traceparent=TEST_TRACEPARENT,
        )
        assert isinstance(context, GeoExecutionContext)
        return claim, context

    claim, context = asyncio.run(scenario())
    assert claim.disposition is ClaimDisposition.CLAIMED
    assert context.question_ids == (
        "1900012345678902101",
        "1900012345678902102",
        "1900012345678902103",
    )
    assert context.platform_codes == (GeoPlatformCode.DOUBAO, GeoPlatformCode.WENXIN)
    assert context.samples_per_combination == 1
    assert context.completed_unit_keys == frozenset()
    assert context.synthesis_context.monitoring_target is None


def test_geo_context_preserves_java_frozen_monitoring_target() -> None:
    payload = _geo_context_payload()
    authorized = cast(dict[str, object], payload["authorizedContext"])
    authorized["monitoringTarget"] = {
        "kind": "PRODUCT",
        "name": "晨光随行咖啡机",
        "aliases": ["晨光便携咖啡机"],
    }
    client = _FakeWorkerJavaClient()
    client.context_result = _response(payload)

    context = asyncio.run(
        JavaWorkerControlPlane(client).get_execution_context(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            traceparent=TEST_TRACEPARENT,
        )
    )

    assert isinstance(context, GeoExecutionContext)
    target = context.synthesis_context.monitoring_target
    assert target is not None
    assert target.kind == "PRODUCT"
    assert target.name == "晨光随行咖啡机"
    assert target.aliases == ("晨光便携咖啡机",)


def test_geo_context_rejects_malformed_monitoring_target() -> None:
    payload = _geo_context_payload()
    authorized = cast(dict[str, object], payload["authorizedContext"])
    authorized["monitoringTarget"] = {
        "kind": "PRODUCT",
        "name": "晨光随行咖啡机",
        "aliases": "not-an-array",
    }
    client = _FakeWorkerJavaClient()
    client.context_result = _response(payload)

    with pytest.raises(ControlPlaneUnavailable, match="JAVA_CONTROL_PLANE_RESPONSE_INVALID"):
        asyncio.run(
            JavaWorkerControlPlane(client).get_execution_context(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                traceparent=TEST_TRACEPARENT,
            )
        )


@pytest.mark.parametrize(
    ("mutate", "value"),
    (
        ("unknown", True),
        ("resumeOnly", True),
        ("providerContinuation", {}),
        ("provider", "DOUBAO"),
    ),
)
def test_geo_context_rejects_unknown_media_or_resume_fields(mutate: str, value: object) -> None:
    payload = _geo_context_payload()
    payload[mutate] = value
    client = _FakeWorkerJavaClient()
    client.context_result = _response(payload)

    with pytest.raises(ControlPlaneUnavailable, match="JAVA_CONTROL_PLANE_RESPONSE_INVALID"):
        asyncio.run(
            JavaWorkerControlPlane(client).get_execution_context(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                traceparent=TEST_TRACEPARENT,
            )
        )


def test_geo_observation_created_and_duplicate_are_mapped_with_exact_payload() -> None:
    async def scenario() -> tuple[
        GeoObservationDisposition,
        dict[str, object],
        GeoObservationDisposition,
    ]:
        client = _FakeWorkerJavaClient()
        control = JavaWorkerControlPlane(client)
        first = await control.report_geo_observation(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            observation=_geo_observation().model_copy(
                update={"collection_method": "doubao-responses-web-search-v1"}
            ),
            traceparent=TEST_TRACEPARENT,
        )
        first_payload = client.calls[-1][2]
        client.geo_observation_result = _response(
            {"accepted": True, "disposition": "DUPLICATE", "taskStatus": "RUNNING"}
        )
        second = await control.report_geo_observation(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            observation=_geo_observation(),
            traceparent=TEST_TRACEPARENT,
        )
        return first, first_payload, second

    first, payload, second = asyncio.run(scenario())
    assert first.value == "CREATED"
    assert second.value == "DUPLICATE"
    assert set(payload) == {"eventId", "executionNo", "executionToken", "observation"}
    assert cast(dict[str, object], payload["observation"])["provider"] == "DOUBAO"
    assert (
        cast(dict[str, object], payload["observation"])["collectionMethod"]
        == "doubao-responses-web-search-v1"
    )


@pytest.mark.parametrize(
    "observation",
    [
        _geo_observation().model_copy(update={"question_id": "geo-question"}),
        _geo_observation().model_copy(update={"provider": "WENXIN"}),
    ],
)
def test_geo_observation_rejects_non_java_identity_before_http(
    observation: GeoObservation,
) -> None:
    client = _FakeWorkerJavaClient()

    with pytest.raises(ControlPlaneRejected, match="JAVA_CONTROL_PLANE_REQUEST_INVALID"):
        asyncio.run(
            JavaWorkerControlPlane(client).report_geo_observation(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                observation=observation,
                traceparent=TEST_TRACEPARENT,
            )
        )

    assert client.calls == []


def test_geo_observation_rejects_stale_execution_without_exposing_java_body() -> None:
    client = _FakeWorkerJavaClient()
    client.geo_observation_result = JavaInternalClientError(
        JavaInternalErrorCode.HTTP_ERROR,
        status_code=409,
    )

    with pytest.raises(ControlPlaneRejected, match="JAVA_CONTROL_PLANE_CONFLICT"):
        asyncio.run(
            JavaWorkerControlPlane(client).report_geo_observation(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                observation=_geo_observation(),
                traceparent=TEST_TRACEPARENT,
            )
        )


def test_geo_observation_recovery_pages_through_java_without_losing_order() -> None:
    async def scenario() -> tuple[
        tuple[GeoObservation, ...], list[tuple[str, str, dict[str, object], str | None]]
    ]:
        client = _FakeWorkerJavaClient()
        payloads = [
            _stored_geo_observation(str(1900012345678910000 + index)) for index in range(51)
        ]
        payloads[0]["collectionMethod"] = "doubao-responses-web-search-v1"
        payloads[1].pop("collectionMethod")  # Historical producer remains compatible.
        visibility = {
            "targetStatus": "TARGET_UNRESOLVED",
            "matchingVersion": "explicit-name-mention-v1",
            "units": [
                {
                    "questionId": item["questionId"],
                    "platformCode": item["platformCode"],
                    "sampleNo": item["sampleNo"],
                    "targetMentioned": None,
                }
                for item in payloads
            ],
        }
        client.geo_pages = {
            1: _response(
                {
                    "items": payloads[:50],
                    "page": 1,
                    "size": 50,
                    "total": 51,
                    "totalPages": 2,
                    "visibilityEvidence": visibility,
                }
            ),
            2: _response(
                {
                    "items": payloads[50:],
                    "page": 2,
                    "size": 50,
                    "total": 51,
                    "totalPages": 2,
                    "visibilityEvidence": visibility,
                }
            ),
        }
        control = JavaWorkerControlPlane(client)
        values = await control.list_geo_observations(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            traceparent=TEST_TRACEPARENT,
        )
        return values, client.calls

    values, calls = asyncio.run(scenario())
    assert len(values) == 51
    assert values[0].question_id == "1900012345678910000"
    assert values[0].collection_method == "doubao-responses-web-search-v1"
    assert values[1].collection_method is None
    assert values[-1].question_id == "1900012345678910050"
    assert [call[2]["page"] for call in calls] == [1, 2]
    assert all(call[2]["size"] == 50 for call in calls)


def _visibility_page() -> dict[str, object]:
    # Full verification units are not truncated to the first HTTP page.
    payloads = [_stored_geo_observation(str(1900012345678910000 + i)) for i in range(51)]
    units = [
        {
            "questionId": item["questionId"],
            "platformCode": item["platformCode"],
            "sampleNo": item["sampleNo"],
            "targetMentioned": i == 50,
        }
        for i, item in enumerate(payloads)
    ]
    return {
        "items": payloads[:50],
        "page": 1,
        "size": 50,
        "total": 51,
        "totalPages": 2,
        "visibilityEvidence": {
            "targetStatus": "AVAILABLE",
            "matchingVersion": "explicit-name-mention-v1",
            "units": units,
        },
    }


def test_geo_full_answer_verification_uses_one_signed_read_without_a_provider_call() -> None:
    client = _FakeWorkerJavaClient()
    client.geo_pages = {1: _response(_visibility_page())}
    flags = asyncio.run(
        JavaWorkerControlPlane(client).get_geo_visibility_evidence(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            traceparent=TEST_TRACEPARENT,
        )
    )
    assert flags.target_status == "AVAILABLE"
    assert len(flags.units) == 51
    assert flags.units[0].target_mentioned is False
    assert flags.units[-1].target_mentioned is True
    assert len(client.calls) == 1
    assert client.calls[0][0] == "geo-list"
    assert client.calls[0][2] == {
        "executionNo": 1,
        "executionToken": EXECUTION_TOKEN,
        "page": 1,
        "size": 50,
    }
    assert client.calls[0][3] == TEST_TRACEPARENT


@pytest.mark.parametrize(
    "bad",
    (
        "missing",
        "duplicate",
        "incomplete",
        "wrong_page_unit",
        "unknown_method",
        "failed_as_not_mentioned",
        "unresolved_as_not_mentioned",
        "unknown_property",
    ),
)
def test_geo_verification_rejects_missing_or_noncanonical_java_flags(bad: str) -> None:
    payload = _visibility_page()
    evidence = cast(dict[str, object], payload["visibilityEvidence"])
    units = cast(list[dict[str, object]], evidence["units"])
    items = cast(list[dict[str, object]], payload["items"])
    if bad == "missing":
        payload.pop("visibilityEvidence")
    elif bad == "duplicate":
        units[-1] = deepcopy(units[0])
    elif bad == "incomplete":
        units.pop()
    elif bad == "wrong_page_unit":
        units[0]["questionId"] = "1900012345678999999"
    elif bad == "unknown_method":
        evidence["matchingVersion"] = "invented-matching"
    elif bad == "failed_as_not_mentioned":
        items[0].update(
            {
                "status": "FAILED",
                "answerText": None,
                "citations": [],
                "stableErrorCode": "PROVIDER_UNAVAILABLE",
            }
        )
    elif bad == "unresolved_as_not_mentioned":
        evidence["targetStatus"] = "TARGET_UNRESOLVED"
    elif bad == "unknown_property":
        evidence["modelAssessedVisibility"] = 100
    client = _FakeWorkerJavaClient()
    client.geo_pages = {1: _response(payload)}
    with pytest.raises(ControlPlaneUnavailable, match=r"JAVA_.*(?:RESPONSE|EVIDENCE)_INVALID"):
        asyncio.run(
            JavaWorkerControlPlane(client).get_geo_visibility_evidence(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                traceparent=TEST_TRACEPARENT,
            )
        )
    assert len(client.calls) == 1


def test_geo_verification_keeps_failed_or_unresolved_units_unknown() -> None:
    payload = _visibility_page()
    evidence = cast(dict[str, object], payload["visibilityEvidence"])
    evidence["targetStatus"] = "TARGET_UNRESOLVED"
    for unit in cast(list[dict[str, object]], evidence["units"]):
        unit["targetMentioned"] = None
    client = _FakeWorkerJavaClient()
    client.geo_pages = {1: _response(payload)}
    flags = asyncio.run(
        JavaWorkerControlPlane(client).get_geo_visibility_evidence(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            traceparent=TEST_TRACEPARENT,
        )
    )
    assert flags.target_status == "TARGET_UNRESOLVED"
    assert all(unit.target_mentioned is None for unit in flags.units)


def test_geo_completion_uses_deepseek_usage_and_accepts_java_partial() -> None:
    async def scenario() -> tuple[str, dict[str, object]]:
        client = _FakeWorkerJavaClient()
        client.geo_completion_result = _response(
            {
                "accepted": True,
                "taskStatus": "PARTIAL",
                "runStatus": "PARTIAL",
                "reportId": "1900012345678902301",
                "settledPoints": 9,
                "releasedPoints": 9,
            }
        )
        status = await JavaWorkerControlPlane(client).report_geo_completion(
            TASK_ID,
            execution_no=1,
            execution_token=EXECUTION_TOKEN,
            completion=GeoCompletion(
                synthesis=_geo_synthesis(),
                synthesis_usage=GeoSynthesisUsage(
                    provider="DEEPSEEK",
                    model="deepseek-v4-flash",
                    input_tokens=20,
                    output_tokens=10,
                    provider_call_count=1,
                    latency_ms=50,
                ),
            ),
            traceparent=TEST_TRACEPARENT,
        )
        return status, client.calls[-1][2]

    status, payload = asyncio.run(scenario())
    assert status == "PARTIAL"
    assert set(payload) == {
        "eventId",
        "executionNo",
        "executionToken",
        "synthesis",
        "synthesisUsage",
    }
    usage = cast(dict[str, object], payload["synthesisUsage"])
    assert usage["provider"] == "DEEPSEEK"
    assert usage["providerCallCount"] == 1


def test_geo_completion_rejects_unfrozen_method_before_http() -> None:
    client = _FakeWorkerJavaClient()
    synthesis = _geo_synthesis().model_copy(update={"method_version": "geo-synthesis-v2"})

    with pytest.raises(ControlPlaneRejected, match="JAVA_CONTROL_PLANE_REQUEST_INVALID"):
        asyncio.run(
            JavaWorkerControlPlane(client).report_geo_completion(
                TASK_ID,
                execution_no=1,
                execution_token=EXECUTION_TOKEN,
                completion=GeoCompletion(
                    synthesis=synthesis,
                    synthesis_usage=GeoSynthesisUsage(
                        model="deepseek-v4-flash",
                        input_tokens=1,
                        output_tokens=1,
                        provider_call_count=1,
                        latency_ms=1,
                    ),
                ),
                traceparent=TEST_TRACEPARENT,
            )
        )

    assert client.calls == []
