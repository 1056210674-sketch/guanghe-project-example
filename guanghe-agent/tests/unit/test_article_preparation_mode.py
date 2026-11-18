"""Current full-article receipt cannot regress into the old TITLE/paid draft path."""

import asyncio
from dataclasses import replace
from typing import Any
from unittest.mock import AsyncMock, PropertyMock, patch

import pytest

from app.agent.graphs.commerce import CommerceAssistantGraph
from app.agent.identity import AgentIdentityError, bind_agent_execution, resolve_execution
from app.agent.loop import NativeAgentLoop
from app.agent.models import ChatRunRequest, Intent
from app.agent.run_manager import ChatRunManager
from app.common.runtime import initialize_runtime
from app.common.settings import AppSettings
from app.model_gateway.gateway import LocalModelGateway
from app.providers.java_context import (
    JavaRunContextResponse,
    RunContextUnavailableError,
    authorized_run_context_from_snapshot,
)
from tests.unit.test_chat_geo_context import geo_payload
from tests.unit.test_chat_run_isolation import (
    _collect,  # pyright: ignore[reportPrivateUsage]
)
from tests.unit.test_java_run_context_provider import (
    _payload,  # pyright: ignore[reportPrivateUsage]
    _provider_for_payload,  # pyright: ignore[reportPrivateUsage]
    _request,  # pyright: ignore[reportPrivateUsage]
)


def _full_payload(request: ChatRunRequest) -> dict[str, Any]:
    payload: dict[str, Any] = _payload(request)
    payload["agentIdentity"] = {
        "installationId": "501",
        "agentKey": "geo",
        "agentVersion": "1.0.0",
        "name": "GEO",
        "projectId": request.trusted_state.selected_space_id,
        "capabilities": ["chat", "resources.read", "article.generate"],
    }
    payload["articlePreparationMode"] = "ARTICLE_FULL_V1"
    payload["conversationMemory"]["messages"][0]["content"]["articlePreparationMode"] = (
        "ARTICLE_FULL_V1"
    )
    return payload


def test_mode_is_java_current_user_request_not_model_input_or_historical_authority() -> None:
    request = _request(content="写篇文章")
    payload = _full_payload(request)
    context = asyncio.run(_provider_for_payload(payload).load(request))
    assert context.article_preparation_mode == "ARTICLE_FULL_V1"
    # It is metadata for routing, not a user-controlled model execution parameter.
    assert "articlePreparationMode" not in context.model_context_json()
    assert context.current_attachment_only().article_preparation_mode is None
    snapshot = JavaRunContextResponse.model_validate(payload)
    assert authorized_run_context_from_snapshot(snapshot).article_preparation_mode is None
    variants: tuple[dict[str, Any], ...] = (
        {k: v for k, v in payload.items() if k != "articlePreparationMode"},
        {**payload, "articlePreparationMode": None},
    )
    for top in variants:
        assert (
            asyncio.run(_provider_for_payload(top).load(request)).article_preparation_mode is None
        )


def _platform_payload(request: ChatRunRequest) -> dict[str, Any]:
    payload = _full_payload(request)
    payload["agentIdentity"]["projectId"] = "601"
    payload["agentIdentity"]["capabilities"].append("geo.monitor")
    report: dict[str, Any] = geo_payload()
    report["report"].update(spaceId="601", productId=None)
    payload["geoInsightContext"] = report
    return payload


def test_generic_platform_project_full_receipt_does_not_require_fake_merchant_catalog() -> None:
    request = _request(content="根据这份报告写完整文章", space_id=None, product_id=None)
    payload = _platform_payload(request)
    context = asyncio.run(_provider_for_payload(payload).load(request))
    assert context.space is None and context.product is None
    assert context.selected_space_id is None and context.agent_identity is not None
    assert context.agent_identity.project_id == "601"
    outcome = asyncio.run(CommerceAssistantGraph(_provider_for_payload(payload)).ainvoke(request))
    assert outcome.intent == Intent.ARTICLE and outcome.task_draft is None
    assert outcome.usage.context_load_count == 1
    assert outcome.usage.model_call_count == outcome.usage.provider_call_count == 0


@pytest.mark.parametrize(
    "mutation",
    [
        "foreign_report",
        "report_product",
        "null_project",
        "deleted_installation",
        "unknown_version",
        "no_capability",
        "legacy_scope",
        "old_user_mode",
        "foreign_knowledge",
    ],
)
def test_generic_platform_full_mode_keeps_project_and_current_user_authority(mutation: str) -> None:
    request = _request(content="写完整文章", space_id=None, product_id=None)
    payload = _platform_payload(request)
    identity = payload["agentIdentity"]
    if mutation == "foreign_report":
        payload["geoInsightContext"]["report"]["spaceId"] = "602"
    elif mutation == "report_product":
        payload["geoInsightContext"]["report"]["productId"] = "302"
    elif mutation == "null_project":
        identity["projectId"] = None
    elif mutation == "deleted_installation":
        identity["installationId"] = None
    elif mutation == "unknown_version":
        identity["agentVersion"] = "9.0.0"
    elif mutation == "no_capability":
        identity["capabilities"] = ["chat", "resources.read", "geo.monitor"]
    elif mutation == "legacy_scope":
        payload["taskWorkspace"]["activeScope"]["spaceId"] = "601"
    elif mutation == "foreign_knowledge":
        payload["knowledgeContext"] = {
            "documents": [],
            "preferences": [],
            "truncated": False,
            "sourceCount": 0,
            "returnedCharacters": 0,
            "businessHistory": {
                "projectId": "602",
                "writtenArticleCount": 0,
                "publishedArticleCount": 0,
                "publishedPlacementCount": 0,
                "asOf": "2026-10-07T01:00:00Z",
            },
        }
    else:
        del payload["conversationMemory"]["messages"][0]["content"]["articlePreparationMode"]
    with pytest.raises(RunContextUnavailableError):
        asyncio.run(_provider_for_payload(payload).load(request))


@pytest.mark.parametrize(
    "mutation",
    [
        "mode",
        "missing_identity",
        "summary",
        "version",
        "installation",
        "project",
        "capability",
        "attachments",
        "resource",
        "grant",
        "current_missing",
        "current_other",
        "role",
    ],
)
def test_mode_rejects_invalid_authority_or_mixed_actions(mutation: str) -> None:
    request = _request()
    payload = _full_payload(request)
    identity = payload["agentIdentity"]
    current = payload["conversationMemory"]["messages"][0]
    if mutation == "mode":
        payload["articlePreparationMode"] = "TITLE"
    elif mutation == "missing_identity":
        del payload["agentIdentity"]
    elif mutation == "summary":
        identity["agentKey"] = "summary"
    elif mutation == "version":
        identity["agentVersion"] = "2.0.0"
    elif mutation == "installation":
        identity["installationId"] = None
    elif mutation == "project":
        identity["projectId"] = "99"
    elif mutation == "capability":
        identity["capabilities"] = ["chat"]
    elif mutation == "attachments":
        payload["attachmentAssetIds"] = ["801"]
        payload["taskWorkspace"]["referenceAssetIds"] = ["801"]
    elif mutation == "resource":
        payload["resourceSelection"] = {"candidateSetId": "701", "choiceId": "candidate"}
    elif mutation == "grant":
        payload["articlePreparationMode"] = {"grantId": "1"}
    elif mutation == "current_missing":
        del current["content"]["articlePreparationMode"]
    elif mutation == "current_other":
        current["content"]["articlePreparationMode"] = "TITLE"
    else:
        current["role"] = "ASSISTANT"
    with pytest.raises(RunContextUnavailableError):
        asyncio.run(_provider_for_payload(payload).load(request))


@pytest.mark.parametrize("text", ["写文章", "继续", "确认", "监测一下、生图再帮我投稿", "随便"])
def test_full_mode_never_routes_to_old_title_draft_or_workflow_even_with_pending_state(
    text: str,
) -> None:
    request = _request(content=text, pending_interaction_id="91", draft_id="92")
    graph = CommerceAssistantGraph(_provider_for_payload(_full_payload(request)))
    with (
        patch.object(
            graph._intent_router,  # pyright: ignore[reportPrivateUsage]
            "classify",
            new=AsyncMock(side_effect=AssertionError("NO_ROUTER")),
        ),
        patch.object(
            graph._gateway,  # pyright: ignore[reportPrivateUsage]
            "suggest_task_parameters",
            new=AsyncMock(side_effect=AssertionError("NO_MODEL")),
        ),
        patch.object(
            type(graph._workflow_plan),  # pyright: ignore[reportPrivateUsage]
            "available",
            new_callable=PropertyMock,
            return_value=True,
        ),
    ):
        outcome = asyncio.run(graph.ainvoke(request))
    assert outcome.kind == "MESSAGE"
    assert outcome.intent == Intent.ARTICLE
    assert outcome.task_draft is None
    assert outcome.interaction is None
    assert "发布前仍需你确认" in outcome.message
    assert outcome.usage.model_call_count == outcome.usage.provider_call_count == 0
    assert outcome.usage.tool_call_count == 0
    assert outcome.usage.context_load_count == 1


def test_full_mode_requires_java_source_and_effective_article_capability() -> None:
    request = _request()
    context = asyncio.run(_provider_for_payload(_full_payload(request)).load(request))
    graph = CommerceAssistantGraph(_provider_for_payload(_full_payload(request)))
    with pytest.raises(AgentIdentityError, match="AGENT_CAPABILITY_DENIED"):
        asyncio.run(
            graph.ainvoke(
                request, authorized_context=replace(context, source="LOCAL_TRUSTED_STATE")
            )
        )
    with (
        bind_agent_execution(resolve_execution("geo", "1.0.0", ("chat",))),
        pytest.raises(AgentIdentityError, match="AGENT_CAPABILITY_DENIED"),
    ):
        asyncio.run(graph.ainvoke(request, authorized_context=context))


def test_run_manager_receipt_has_no_proposal_or_router_probe_and_zero_model_calls() -> None:
    async def scenario() -> None:
        request = _request(content="按这些资料写完整文章")
        manager = ChatRunManager(
            initialize_runtime(AppSettings(mock_event_delay_ms=0)),
            LocalModelGateway(),
            _provider_for_payload(_full_payload(request)),
        )
        try:
            with (
                patch.object(manager, "_router_is_ready", return_value=False),
                patch.object(
                    NativeAgentLoop,
                    "ainvoke",
                    new=AsyncMock(side_effect=AssertionError("NO_NATIVE_MODEL_LOOP")),
                ),
                patch.object(
                    manager._gateway,  # pyright: ignore[reportPrivateUsage]
                    "probe",
                    new=AsyncMock(side_effect=AssertionError("NO_MODEL_PROBE")),
                ),
            ):
                await manager.start(request)
                events = await _collect(manager, request.run_id)
            assert events[-1].event_type == "run.completed"
            assert events[-1].payload["status"] == "COMPLETED"
            assert not any(
                event.event_type
                in {"task-draft.proposed", "interaction.required", "workflow.proposed"}
                for event in events
            )
            usage = next(event.payload for event in events if event.event_type == "usage.reported")
            assert (
                usage["modelCallCount"] == usage["providerCallCount"] == usage["toolCallCount"] == 0
            )
            assert usage["contextLoadCount"] == 1
        finally:
            await manager.close()

    asyncio.run(scenario())
