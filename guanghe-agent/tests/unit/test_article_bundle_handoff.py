"""Every current-bundle article stage reaches the signed result handoff once."""

import asyncio
import json
from typing import cast

import httpx
import pytest

from app.article.models import ArticleStage
from app.article.runtime import ArticleExecutionKernel
from app.article.worker import _completion  # pyright: ignore[reportPrivateUsage]
from app.article.worker_models import ArticleExecutionContext, ArticleOutlineSnapshot
from app.integration.java_internal import JavaInternalClient
from app.model_gateway.gateway import ModelGateway
from app.security.internal_hmac import SIGNATURE_HEADER
from app.workers.control_plane import ControlPlaneRejected
from app.workers.java_control_plane import JavaWorkerControlPlane
from tests.internal_security import TEST_TRACEPARENT, internal_settings
from tests.unit.test_article_execution import (
    _plan,  # pyright: ignore[reportPrivateUsage]
    _prompts,  # pyright: ignore[reportPrivateUsage]
    _request,  # pyright: ignore[reportPrivateUsage]
    _StructuredGateway,  # pyright: ignore[reportPrivateUsage]
    _title_candidates,  # pyright: ignore[reportPrivateUsage]
)

_TASK_ID = "1900012345678901700"
_STAGES: tuple[tuple[ArticleStage, dict[str, object]], ...] = (
    (ArticleStage.TITLE, {"candidates": _title_candidates()}),
    (ArticleStage.OUTLINE, {"plan": _plan().model_dump(mode="json", by_alias=True)}),
    (
        ArticleStage.BODY,
        {
            "blocks": [
                {"key": "section-1", "heading": "选择标准", "markdown": "先明确使用场景。"},
                {"key": "section-2", "heading": "使用建议", "markdown": "再核对已有参数。"},
            ]
        },
    ),
    (
        ArticleStage.REWRITE,
        {
            "replacements": [
                {"key": "section-2", "heading": "使用建议", "markdown": "只核对有来源的参数。"}
            ]
        },
    ),
)


@pytest.mark.parametrize(("stage", "model_payload"), _STAGES)
@pytest.mark.parametrize("unknown_version", [None, "agent-bundle-v1.46.5", "agent-bundle-v9.99.0"])
def test_current_bundle_article_stages_handoff_through_real_signed_adapter(
    stage: ArticleStage, model_payload: dict[str, object], unknown_version: str | None
) -> None:
    async def scenario() -> None:
        calls: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            calls.append(request)
            assert request.method == "POST"
            assert request.url.path == f"/internal/v1/tasks/{_TASK_ID}/result-artifacts"
            assert request.headers[SIGNATURE_HEADER]
            return httpx.Response(200, json={"accepted": True, "taskStatus": "SUCCEEDED"})

        prompts = _prompts()
        request = _request(stage)
        result = await ArticleExecutionKernel(
            cast(ModelGateway, _StructuredGateway(model_payload)), prompts
        ).execute(request)
        assert result.prompt_version == prompts.bundle_version
        if unknown_version is not None:
            result = result.model_copy(update={"prompt_version": unknown_version})
        context = ArticleExecutionContext(
            task_id=_TASK_ID,
            execution_no=1,
            article_version=3,
            request=request,
            authorized_outline=tuple(
                ArticleOutlineSnapshot(
                    item.key, item.sequence, item.level, item.heading, item.brief
                )
                for item in request.confirmed_outline
            ),
        )
        async with httpx.AsyncClient(
            base_url="http://127.0.0.1:8080", transport=httpx.MockTransport(handler)
        ) as http:
            control = JavaWorkerControlPlane(
                JavaInternalClient(
                    internal_settings(java_internal_base_url="http://127.0.0.1:8080"), http
                )
            )
            handoff = control.report_article_completion(
                _TASK_ID,
                execution_no=1,
                execution_token="v1.synthetic-execution-token",
                completion=_completion(context, result),
                traceparent=TEST_TRACEPARENT,
            )
            if unknown_version is not None:
                with pytest.raises(
                    ControlPlaneRejected, match="JAVA_CONTROL_PLANE_REQUEST_INVALID"
                ):
                    await handoff
                assert not calls
                return
            assert await handoff == "SUCCEEDED"

        assert len(calls) == 1
        payload = json.loads(calls[0].content)
        assert payload["submissionState"] == "CONFIRMED"
        assert payload["providerTaskId"] is None
        assert payload["usage"]["inputTokens"] == result.usage.input_tokens
        assert payload["usage"]["outputTokens"] == result.usage.output_tokens
        artifact = payload["artifacts"][0]
        assert artifact["unitKey"] == f"article:{stage.value.lower()}"
        assert artifact["schemaVersion"] == 2
        assert artifact["inlineData"]["promptVersion"] == prompts.bundle_version
        assert artifact["inlineData"]["metadata"]["persisted"] is False
        assert artifact["inlineData"]["metadata"]["published"] is False
        if stage is ArticleStage.REWRITE:
            assert [b["key"] for b in artifact["inlineData"]["body"]["blocks"]] == ["section-2"]

    asyncio.run(scenario())
