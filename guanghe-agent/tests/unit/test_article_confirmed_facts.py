"""Confirmed customer evidence survives the real Java adapter and all article stage inputs."""

from __future__ import annotations

import json
from typing import Any, cast

import pytest
from pydantic import ValidationError

from app.article.runtime import ArticleExecutionKernel
from app.model_gateway.gateway import ModelGateway
from app.workers.java_control_plane import (
    _article_execution_context,  # pyright: ignore[reportPrivateUsage]
    _ArticleTaskExecutionContextResponse,  # pyright: ignore[reportPrivateUsage]
)
from tests.unit.test_article_execution import (
    _prompts,  # pyright: ignore[reportPrivateUsage]
    _StructuredGateway,  # pyright: ignore[reportPrivateUsage]
)
from tests.unit.test_java_worker_control_plane import (
    _article_context_payload,  # pyright: ignore[reportPrivateUsage]
)


def _payload(mode: str = "TITLE") -> dict[str, Any]:
    payload = cast(dict[str, Any], _article_context_payload(mode))
    project = payload["authorizedContext"]["space"]["id"]
    payload["authorizedContext"]["confirmedFacts"] = [
        {
            "source": "USER_CONFIRMED_SOURCE_EXCERPT",
            "projectId": project,
            "conversationId": "123",
            "sourceAssistantMessageId": "101",
            "confirmationUserMessageId": "102",
            "sourceCreatedAt": "2026-10-10T04:05:00Z",
            "confirmedAt": "2026-10-10T04:06:00Z",
            "sourceUrl": "https://example.com/source",
            "sourceExcerpt": "合成星河AI成立于2023年；服务中小企业。",
            "confirmationQuote": "你获取的消息都是准确的",
            "facts": ["合成星河AI成立于2023年", "服务中小企业。"],
        }
    ]
    return payload


@pytest.mark.parametrize("mode", ("TITLE", "OUTLINE", "BODY"))
def test_confirmed_source_stays_separate_from_history_and_reaches_each_stage(mode: str) -> None:
    payload = _payload(mode)
    parsed = _ArticleTaskExecutionContextResponse.model_validate(payload)
    context = _article_execution_context(parsed)
    kernel = ArticleExecutionKernel(cast(ModelGateway, _StructuredGateway({})), _prompts())
    messages = kernel._messages(context.request)  # pyright: ignore[reportPrivateUsage]
    model_input = json.loads(str(messages[1].content).split("\n", 1)[1])
    evidence = model_input["authorizedContext"]["confirmedFacts"]
    assert evidence == payload["authorizedContext"]["confirmedFacts"]
    assert model_input["writingReference"] is None
    assert "confirmedFacts" not in model_input["brief"]


def test_cross_project_changed_excerpt_and_forgotten_sources_are_rejected() -> None:
    wrong_project = _payload()
    wrong_project["authorizedContext"]["confirmedFacts"][0]["projectId"] = "999"
    with pytest.raises(ValidationError, match="authorized project"):
        _ArticleTaskExecutionContextResponse.model_validate(wrong_project)

    altered = _payload()
    altered["authorizedContext"]["confirmedFacts"][0]["facts"] = ["编造的案例与排名"]
    with pytest.raises(ValidationError, match="complete source excerpts"):
        _ArticleTaskExecutionContextResponse.model_validate(altered)

    forgotten = _payload()
    project = forgotten["authorizedContext"]["space"]["id"]
    forgotten["authorizedContext"]["knowledge"] = {
        "documents": [],
        "preferences": [],
        "businessHistory": None,
        "truncated": False,
        "sourceCount": 0,
        "returnedCharacters": 0,
        "memory": {
            "schemaVersion": 1,
            "projectId": project,
            "entries": [],
            "revision": "synthetic-cutoff",
            "historyCutoffAt": "2026-10-11T04:00:00Z",
        },
    }
    with pytest.raises(ValidationError, match="memory boundary"):
        _ArticleTaskExecutionContextResponse.model_validate(forgotten)
