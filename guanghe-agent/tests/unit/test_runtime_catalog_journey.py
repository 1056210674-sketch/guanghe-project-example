"""Functional fourth-Agent journeys through the production RunManager/native loop."""

import asyncio
import json
from dataclasses import replace
from pathlib import Path
from typing import Any

import pytest

from app.agent.models import ChatRunRequest
from app.agent.run_manager import ChatRunManager
from app.agents.catalog import CatalogError, RuntimeCatalog, parse_catalog
from app.common.runtime import initialize_runtime
from app.common.settings import AppSettings
from app.harness.runtime import record_model_call
from app.model_gateway.gateway import (
    GatewayUsage,
    LocalModelGateway,
    ModelMessage,
    ModelTurnResult,
)
from app.providers.java_context import AgentIdentity, AuthorizedRunContext, LocalRunContextProvider
from app.providers.knowledge_context import KnowledgeContext, KnowledgeDocument, KnowledgeParagraph
from tests.unit.test_chat_run_isolation import (
    _collect,  # pyright: ignore[reportPrivateUsage]
    _payload,  # pyright: ignore[reportPrivateUsage]
    _request,  # pyright: ignore[reportPrivateUsage]
)


def fixture_catalog() -> RuntimeCatalog:
    content = json.loads(
        (Path(__file__).parents[2] / "app/agents/runtime-catalog.json").read_bytes()
    )
    content["agents"].append(
        {
            "agentKey": "reviewer",
            "agentVersion": "1.0.0",
            "catalogueAdapterKey": "reviewer",
            "runtimeKind": "native",
            "promptCode": "SUMMARY_TEXT_V1",
            "executionRelease": "fixture-fourth-v1",
            "intent": "GENERAL",
            "contextPolicy": "plain",
            "continuationPolicy": "none",
            "explicitActions": [],
            "requiredCapabilities": ["chat"],
            "capabilities": ["chat", "resources.read"],
            "legacyCapabilityGrants": {},
            "allowedTools": ["context.read", "memory.read", "memory.upsert", "memory.forget"],
        }
    )
    return parse_catalog(json.dumps(content).encode())


class JourneyContext(LocalRunContextProvider):
    def __init__(self, identities: dict[str, AgentIdentity]) -> None:
        self.identities = identities

    async def load(self, request: ChatRunRequest) -> AuthorizedRunContext:
        return replace(
            await super().load(request),
            source="JAVA_AUTHORIZED_CONTEXT",
            agent_identity=self.identities[request.conversation_id],
            knowledge_context=KnowledgeContext(
                documents=(
                    KnowledgeDocument(
                        id="123",
                        version=1,
                        name="PRIVATE_DOCUMENT",
                        sha256="a" * 64,
                        source_type="PASTED_TEXT",
                        source_url=None,
                        paragraphs=(
                            KnowledgeParagraph(index=1, page=None, text="PRIVATE_BRAND_FACT"),
                        ),
                    ),
                ),
                preferences=(),
                business_history=None,
                truncated=False,
                source_count=1,
                returned_characters=18,
            ),
        )


class JourneyGateway(LocalModelGateway):
    is_mock = False

    def __init__(self, *, wait: bool = False) -> None:
        self.wait = wait
        self.entered = asyncio.Event()
        self.release = asyncio.Event()
        self.turns: list[tuple[str, set[str]]] = []

    async def complete_turn(self, *_: Any, **__: Any) -> ModelTurnResult:
        raise AssertionError("RunManager must use genuine streaming callbacks")

    async def complete_turn_stream(self, messages: Any, **kwargs: Any) -> ModelTurnResult:
        record_model_call(input_messages=messages)
        self.turns.append((messages[0].content, {tool.name for tool in kwargs["tools"]}))
        await kwargs["on_delta"]("已阅读当前请求。")
        self.entered.set()
        if self.wait:
            await self.release.wait()
        await kwargs["on_delta"]("这是独立智能体的回复。")
        return ModelTurnResult(
            ModelMessage("assistant", "已阅读当前请求。这是独立智能体的回复。"),
            "stop",
            GatewayUsage(request_count=1),
        )


def fixture_identity(
    catalog: RuntimeCatalog,
    key: str = "reviewer",
    caps: tuple[str, ...] = ("chat",),
) -> AgentIdentity:
    registration = catalog.agents[(key, "1.0.0")]
    return AgentIdentity(
        installation_id="123",
        agent_key=key,
        agent_version="1.0.0",
        name="Fixture",
        project_id=None,
        capabilities=caps,
        execution_release=registration.execution_release,
        catalog_hash=catalog.catalog_hash,
    )


def test_fourth_agent_streams_without_geo_or_unauthorized_knowledge() -> None:
    async def scenario() -> None:
        catalog = fixture_catalog()
        gateway = JourneyGateway()
        manager = ChatRunManager(
            initialize_runtime(AppSettings(mock_event_delay_ms=0)),
            gateway,
            JourneyContext({"chat": fixture_identity(catalog)}),
            catalog=catalog,
        )
        request = _request(
            "总结这句话，不要执行任务", conversation_id="chat", attachment_ids=("999",)
        )
        try:
            await manager.start(request)
            events = await _collect(manager, request.run_id)
            assert events[-1].event_type == "run.completed"
            assert any(event.event_type == "message.delta" for event in events)
            assert len(gateway.turns) == 1
            prompt, tools = gateway.turns[0]
            assert not tools
            assert "PRIVATE_BRAND_FACT" not in prompt and "PRIVATE_DOCUMENT" not in prompt
            assert "999" not in prompt and "brandBrief" not in prompt
        finally:
            await manager.close()

    asyncio.run(scenario())


def test_parallel_fourth_agent_and_geo_do_not_revive_memory_writes() -> None:
    async def scenario() -> None:
        catalog = fixture_catalog()
        gateway = JourneyGateway()
        caps = ("chat", "resources.read")
        manager = ChatRunManager(
            initialize_runtime(AppSettings(mock_event_delay_ms=0)),
            gateway,
            JourneyContext(
                {key: fixture_identity(catalog, key, caps) for key in ("reviewer", "geo")}
            ),
            catalog=catalog,
        )
        requests = [_request("你好", conversation_id=key) for key in ("reviewer", "geo")]
        try:
            await asyncio.gather(*(manager.start(request) for request in requests))
            results = await asyncio.gather(
                *(_collect(manager, request.run_id) for request in requests)
            )
            assert all(events[-1].event_type == "run.completed" for events in results)
            for _, tools in gateway.turns:
                assert "context_read" in tools
                assert not {"memory_read", "memory_upsert", "memory_forget", "brand_save"} & tools
                assert not {"geo_monitor", "article_generate", "image_generate"} & tools
            assert sum("brandBrief" in prompt for prompt, _ in gateway.turns) == 1
        finally:
            await manager.close()

    asyncio.run(scenario())


def test_fourth_agent_stop_preserves_partial_text_without_business_action() -> None:
    async def scenario() -> None:
        catalog = fixture_catalog()
        gateway = JourneyGateway(wait=True)
        manager = ChatRunManager(
            initialize_runtime(AppSettings(mock_event_delay_ms=0)),
            gateway,
            JourneyContext({"chat": fixture_identity(catalog)}),
            catalog=catalog,
        )
        request = _request("总结", conversation_id="chat")
        try:
            await manager.start(request)
            await asyncio.wait_for(gateway.entered.wait(), 2)
            await manager.stop(request.run_id)
            events = await _collect(manager, request.run_id)
            assert events[-1].payload["status"] == "STOPPED"
            assert any(event.event_type == "message.delta" for event in events)
            assert not any(event.event_type == "task-draft.proposed" for event in events)
        finally:
            await manager.close()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "change,code",
    [
        ({"execution_release": "wrong"}, "AGENT_EXECUTION_RELEASE_MISMATCH"),
        ({"catalog_hash": "0" * 64}, "AGENT_EXECUTION_RELEASE_MISMATCH"),
        ({"catalog_hash": None, "execution_release": None}, "AGENT_EXECUTION_RELEASE_REQUIRED"),
        ({"agent_version": "2.0.0"}, "AGENT_VERSION_NOT_REGISTERED"),
        ({"agent_key": "unknown"}, "AGENT_NOT_REGISTERED"),
    ],
)
def test_identity_release_mismatch_fails_before_model(change: dict[str, Any], code: str) -> None:
    async def scenario() -> None:
        catalog = fixture_catalog()
        gateway = JourneyGateway()
        identity = fixture_identity(catalog).model_copy(update=change)
        manager = ChatRunManager(
            initialize_runtime(AppSettings(mock_event_delay_ms=0)),
            gateway,
            JourneyContext({"chat": identity}),
            catalog=catalog,
        )
        request = _request("你好", conversation_id="chat")
        try:
            await manager.start(request)
            events = await _collect(manager, request.run_id)
            assert _payload(events, "run.failed")["code"] == code
            assert not gateway.turns
        finally:
            await manager.close()

    asyncio.run(scenario())


def test_catalogue_rejects_changed_bundle_before_service_runs() -> None:
    runtime = initialize_runtime(AppSettings())
    assert runtime.bundle is not None
    with pytest.raises(CatalogError, match="AGENT_CATALOG_BUNDLE_MISMATCH"):
        fixture_catalog().validate_bundle(replace(runtime.bundle, normalized_bundle_hash="0" * 64))


@pytest.mark.parametrize(
    "mutation",
    [
        "capability-name",
        "adapter-name",
        "prompt-name",
        "profile-name",
        "duplicate-tool-caps",
        "duplicate-actions",
        "duplicate-versions",
        "missing-current-version",
        "duplicate-grants",
    ],
)
def test_catalogue_rejects_invalid_declarations_before_service_runs(mutation: str) -> None:
    content = json.loads(
        (Path(__file__).parents[2] / "app/agents/runtime-catalog.json").read_bytes()
    )
    geo = next(agent for agent in content["agents"] if agent["agentKey"] == "geo")
    if mutation == "capability-name":
        geo["capabilities"].append("CAPABILITY")
    elif mutation == "adapter-name":
        geo["catalogueAdapterKey"] = "geo_adapter"
    elif mutation == "prompt-name":
        geo["promptCode"] = "prompt.name"
    elif mutation == "profile-name":
        content["workerProfileCode"] = "profile.name"
    elif mutation == "duplicate-tool-caps":
        next(iter(content["tools"].values()))["requiredCapabilities"] = ["chat", "chat"]
    elif mutation == "duplicate-actions":
        geo["explicitActions"] = ["MONITOR_RUN", "MONITOR_RUN"]
    elif mutation == "duplicate-versions":
        content["acceptedArticlePromptVersions"].append(content["bundleVersion"])
    elif mutation == "missing-current-version":
        content["acceptedArticlePromptVersions"].remove(content["bundleVersion"])
    else:
        geo["legacyCapabilityGrants"]["resources.read"] = ["memory.read", "memory.read"]
    with pytest.raises(CatalogError, match="AGENT_CATALOG_INVALID"):
        parse_catalog(json.dumps(content).encode())
