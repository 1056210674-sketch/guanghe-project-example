"""Functional diagnostic preparation journeys; no real models or network requests."""

import asyncio
import hashlib
import json
from datetime import UTC, datetime, timedelta
from typing import Any, LiteralString, cast

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import BaseModel, SecretStr, ValidationError
from pydantic_core import PydanticCustomError

from app.api.application import create_app
from app.common.enums import ProviderStatus
from app.common.settings import ProviderSettings
from app.geo.diagnostic_preparation import (
    _REPAIR_INSTRUCTION,  # pyright: ignore[reportPrivateUsage]
    DiagnosticPreparationError,
    DiagnosticPreparationRequest,
    _project_evidence,  # pyright: ignore[reportPrivateUsage]
    _result_schema,  # pyright: ignore[reportPrivateUsage]
    diagnostic_preparation_prompt,
    prepare_diagnostic,
)
from app.geo.providers import BochaSearchAdapter
from app.harness.runtime import estimate_model_messages, record_model_call
from app.model_gateway.deepseek import DeepSeekAdapter
from app.model_gateway.gateway import (
    DIAGNOSTIC_SAFE_VALIDATION_TYPES,
    STRUCTURED_SAFE_VALIDATION_TYPES,
    DiagnosticValidationCode,
    GatewayUsage,
    ModelGateway,
    ModelIdentity,
    ModelMessage,
    ModelMessages,
    ModelRole,
    ModelToolDefinition,
    diagnostic_structured_repair_instruction,
    diagnostic_structured_repair_reserve,
    native_request_budget_messages,
)
from tests.internal_security import FakeInternalNonceStore, internal_settings, signed_headers

_PATH = "/internal/v1/geo/diagnostic-preparations"
_BRAND = "小行星咖啡"
_URL = "https://coffee.example.com/about"
_COMPETITOR_URL = "https://other.example.com/about"
_BRAND_QUOTE = "小行星咖啡以咖啡饮品和烘焙食品为主要业务，提供到店自取服务。"
_COMPETITOR_QUOTE = "星环咖啡提供现磨咖啡和烘焙食品，在广州提供到店自取服务。"


def _phase_contract(system: str) -> dict[str, Any]:
    marker = "[DIAGNOSTIC PHASE OUTPUT CONTRACT]\n"
    contract = system.split(marker, 1)[1].split("[DIAGNOSTIC CANDIDATE OUTPUT]", 1)[0]
    parsed = cast(dict[str, Any], json.loads(contract.strip()))
    assert set(parsed) == {
        "version",
        "phase",
        "scope",
        "candidateLimit",
        "candidateCountRule",
        "existingRows",
    }
    assert "outputExample" not in system and "phaseOutputExample" not in system
    assert "prepare_geo_diagnostic" in system.split("[DIAGNOSTIC CANDIDATE OUTPUT]", 1)[1]
    return {
        "phase": parsed["phase"],
        "candidateSlots": parsed["candidateLimit"],
        "candidateContract": parsed,
    }


def _projected_request(system: str, user: str) -> dict[str, Any]:
    projected = cast(dict[str, Any], json.loads(user))
    assert (
        not {"phase", "candidateSlots", "candidateContract", "outputExample", "phaseOutputExample"}
        & projected.keys()
    )
    return {**projected, **_phase_contract(system)}


def _questions(phase: str, slots: int) -> list[dict[str, str]]:
    texts = (
        [
            "广州有哪些适合上班族自取的咖啡店？",
            "广州有哪些同时提供咖啡和烘焙食品的门店？",
            "广州的咖啡店如何安排午休时段的到店自取？",
            "广州有哪些适合多人预订咖啡后集中自取的店？",
            "广州如何选择支持提前下单的咖啡服务商？",
        ]
        if phase == "DISCOVERY_QUESTIONS"
        else [
            "小行星咖啡的到店自取体验如何？",
            "选择小行星咖啡前应核实哪些咖啡与烘焙服务？",
            "小行星咖啡是否适合工作日午休时段自取？",
        ]
    )
    return [{"text": text} for text in texts[:slots]]


def _assert_projected_competitor(
    competitors: list[dict[str, Any]], name: str, original_terms: list[str]
) -> None:
    assert len(competitors) == 1
    assert set(competitors[0]) == {"name", "brandTerms"}
    assert competitors[0]["name"] == name
    assert isinstance(competitors[0]["brandTerms"], list)
    projected_terms = cast(list[str], competitors[0]["brandTerms"])
    assert set(projected_terms) <= set(original_terms)


def _large_sources(
    count: int, snippet_chars: int, *, title_chars: int = 12
) -> list[dict[str, str]]:
    return [
        {
            "id": f"saved-{index}",
            "title": ("公开业务资料" * 100)[:title_chars],
            "url": f"https://saved{index}.example.com/about",
            "snippet": ("公开业务服务地域资料。" * 200)[:snippet_chars],
        }
        for index in range(count)
    ]


def _http_request_estimate(request: dict[str, Any]) -> int:
    messages = tuple(
        ModelMessage(role=cast(ModelRole, item["role"]), content=item["content"])
        for item in request["messages"]
    )
    tools = tuple(
        ModelToolDefinition(
            name=item["function"]["name"],
            description=item["function"]["description"],
            parameters=item["function"]["parameters"],
        )
        for item in request["tools"]
    )
    return estimate_model_messages(native_request_budget_messages(messages, tools))


def _payload(phase: str = "BRAND_RESEARCH") -> dict[str, Any]:
    return {
        "jobId": "1900012345678901999",
        "inputHash": "a" * 64,
        "phase": phase,
        "deadlineAt": (datetime.now(UTC) + timedelta(seconds=90)).isoformat(),
        "input": {
            "brandName": _BRAND,
            "profile": {
                "brandName": _BRAND,
                "website": _URL,
                "introduction": "咖啡饮品和烘焙食品，到店自取服务",
                "brandTerms": [_BRAND, "星咖"],
                "region": "广州",
            },
            "competitors": [],
            "discoveryQuestions": [],
            "reputationQuestions": [],
            "sources": [],
        },
    }


class _Gateway:
    identity = ModelIdentity(provider="DEEPSEEK", model="unit-test", version="1")
    is_mock = False
    status = ProviderStatus.AVAILABLE
    last_error_code: str | None = None

    def __init__(self, override: dict[str, Any] | None = None) -> None:
        self.override = override
        self.calls: list[dict[str, Any]] = []
        self.message_calls: list[ModelMessages] = []

    async def complete_structured(
        self,
        messages: ModelMessages,
        response_model: type[BaseModel],
        *,
        operation: str,
        max_tokens: int,
        error_code: str,
        repair_instruction: str,
        repair_attempts: int = 1,
    ) -> tuple[BaseModel, GatewayUsage]:
        assert operation == "readonly_geo_diagnostic_preparation"
        assert max_tokens == 4096 and repair_attempts == 1
        assert error_code == "GEO_DIAGNOSTIC_RESPONSE_INVALID"
        assert "真实 ID 和连续原文" in repair_instruction
        record_model_call(input_messages=messages)
        data = _projected_request(messages[0].content, messages[1].content)
        self.calls.append(data)
        self.message_calls.append(messages)
        output: dict[str, Any] = {
            "profile": None,
            "competitors": [],
            "questions": [],
            "warnings": [],
        }
        if data["phase"] == "BRAND_RESEARCH":
            source = next(item for item in data["sources"] if item["url"] == _URL)
            output["profile"] = {
                "brandName": _BRAND,
                "website": "https://coffee.example.com/",
                "introduction": "主要经营咖啡饮品和烘焙食品，支持到店自取。",
                "brandTerms": [_BRAND],
                "region": data["input"]["profile"]["region"],
                "evidence": [{"sourceId": source["id"], "quote": _BRAND_QUOTE}],
            }
        elif data["phase"] == "COMPETITORS":
            source = next(item for item in data["sources"] if item["url"] == _COMPETITOR_URL)
            output["competitors"] = [
                {
                    "name": "星环咖啡",
                    "brandTerms": ["星环咖啡"],
                    "reason": "同样在广州提供现磨咖啡、烘焙食品及到店自取服务。",
                    "sourceIds": [source["id"]],
                    "evidence": [{"sourceId": source["id"], "quote": _COMPETITOR_QUOTE}],
                }
            ]
        else:
            output["questions"] = _questions(data["phase"], data["candidateSlots"])
        if self.override:
            output.update(self.override)
        return response_model.model_validate(output), GatewayUsage(
            request_count=1, input_tokens=200, output_tokens=100
        )


def _search(client: httpx.AsyncClient) -> BochaSearchAdapter:
    return BochaSearchAdapter(
        ProviderSettings(credential=SecretStr("unit-test"), max_retries=0), client=client
    )


def _search_response(request: httpx.Request) -> httpx.Response:
    assert request.url.host == "api.bocha.cn"
    return httpx.Response(
        200,
        json={
            "code": 200,
            "data": {
                "webPages": {
                    "value": [
                        {"name": "小行星咖啡官网", "url": _URL, "summary": _BRAND_QUOTE},
                        {
                            "name": "星环咖啡官网",
                            "url": _COMPETITOR_URL,
                            "summary": _COMPETITOR_QUOTE,
                        },
                    ]
                }
            },
        },
    )


async def _run(payload: dict[str, Any], gateway: _Gateway) -> Any:
    async with httpx.AsyncClient(transport=httpx.MockTransport(_search_response)) as http:
        return await prepare_diagnostic(
            DiagnosticPreparationRequest.model_validate(payload),
            cast(ModelGateway, gateway),
            _search(http),
            received_at=datetime.now(UTC),
        )


def test_brand_research_is_real_search_grounded_with_separate_model_and_search_usage() -> None:
    gateway = _Gateway()
    result = asyncio.run(_run(_payload(), gateway))
    assert result.result.profile.brand_name == _BRAND
    assert result.result.profile.region == "广州"
    assert result.search_request_count == 3
    assert result.synthesis_usage.provider_call_count == 1
    assert result.synthesis_usage.input_tokens == 200
    assert {source.url for source in result.result.sources} == {_URL, _COMPETITOR_URL}
    assert all(source.id.startswith("web-") for source in result.result.sources)
    assert "evidence" not in result.result.profile.model_dump()
    assert len(gateway.calls) == 1
    assert result.prompt_version == "GEO_DIAGNOSTIC_PREPARATION_V5"


@pytest.mark.parametrize(
    ("phase", "slots"),
    [
        ("BRAND_RESEARCH", 1),
        ("COMPETITORS", 5),
        ("DISCOVERY_QUESTIONS", 5),
        ("REPUTATION_QUESTIONS", 3),
    ],
)
def test_phase_output_contract_is_system_owned_and_never_in_user_facts(
    phase: str, slots: int
) -> None:
    raw = _payload(phase)
    frozen = json.dumps(raw, ensure_ascii=False, sort_keys=True)
    gateway = _Gateway()
    asyncio.run(_run(raw, gateway))
    system, user = gateway.message_calls[0]
    assert (system.role, user.role) == ("system", "user")
    metadata = _phase_contract(system.content)
    assert metadata["phase"] == phase and metadata["candidateSlots"] == slots
    assert metadata["candidateContract"]["version"] == "GEO_DIAGNOSTIC_PREPARATION_V5"
    assert metadata["candidateContract"]["candidateLimit"] == slots
    assert metadata["candidateContract"]["candidateCountRule"] == (
        "EXACT_SLOTS" if "QUESTIONS" in phase else "UP_TO_SLOTS"
    )
    facts = json.loads(user.content)
    assert set(facts) == {"input", "inputProjection", "sources", "sourceProjection", "sourceScope"}
    projected_profile = facts["input"]["profile"]
    original_profile = raw["input"]["profile"]
    assert set(projected_profile) == set(original_profile)
    assert all(
        projected_profile[key] == original_profile[key]
        for key in ("brandName", "website", "region")
    )
    assert original_profile["introduction"].startswith(projected_profile["introduction"])
    assert (
        projected_profile["brandTerms"]
        == original_profile["brandTerms"][: len(projected_profile["brandTerms"])]
    )
    if projected_profile != original_profile:
        assert facts["inputProjection"]["partialContext"] is True
        assert facts["inputProjection"]["omittedAliases"] == len(
            original_profile["brandTerms"]
        ) - len(projected_profile["brandTerms"])
    for template_key in (
        "phase",
        "candidateSlots",
        "candidateContract",
        "outputExample",
        "phaseOutputExample",
    ):
        assert f'"{template_key}":' not in user.content
    assert "phaseOutputExample" not in metadata
    assert json.dumps(raw, ensure_ascii=False, sort_keys=True) == frozen


def test_competitors_are_source_linked_and_generate_only_unedited_slots() -> None:
    payload = _payload("COMPETITORS")
    payload["input"]["competitors"] = [
        {
            "id": "manual",
            "name": "用户自己选的店",
            "brandTerms": ["用户自己选的店"],
            "reason": "本次希望对比",
            "sourceIds": [],
            "origin": "USER",
            "edited": False,
        }
    ]
    gateway = _Gateway()
    result = asyncio.run(_run(payload, gateway))
    assert gateway.calls[0]["candidateSlots"] == 4
    assert result.result.competitors[0].name == "星环咖啡"
    source_ids = {item.id for item in result.result.sources}
    assert set(result.result.competitors[0].source_ids) <= source_ids
    assert "evidence" not in result.result.competitors[0].model_dump()


def test_search_sources_accept_public_http_and_missing_title_without_null_fields() -> None:
    bare_url = "http://public.example.com/source"

    def handler(request: httpx.Request) -> httpx.Response:
        raw = _search_response(request).json()
        raw["data"]["webPages"]["value"].append({"url": bare_url})
        return httpx.Response(200, json=raw)

    async def run() -> Any:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
            return await prepare_diagnostic(
                DiagnosticPreparationRequest.model_validate(_payload("COMPETITORS")),
                cast(ModelGateway, _Gateway()),
                _search(http),
                received_at=datetime.now(UTC),
            )

    response = asyncio.run(run())
    source = next(item for item in response.result.sources if item.url == bare_url)
    assert source.title == bare_url and source.snippet == ""


@pytest.mark.parametrize("phase", ["DISCOVERY_QUESTIONS", "REPUTATION_QUESTIONS"])
def test_questions_follow_confirmed_business_without_requiring_search(phase: str) -> None:
    async def run() -> Any:
        return await prepare_diagnostic(
            DiagnosticPreparationRequest.model_validate(_payload(phase)),
            cast(ModelGateway, _Gateway()),
            None,
            received_at=datetime.now(UTC),
        )

    response = asyncio.run(run())
    assert len(response.result.questions) == (5 if phase == "DISCOVERY_QUESTIONS" else 3)
    assert response.search_request_count == 0
    assert response.synthesis_usage.provider_call_count == 1


@pytest.mark.parametrize(
    ("phase", "text"),
    [
        ("DISCOVERY_QUESTIONS", "广州的小行星咖啡怎么样？"),
        ("DISCOVERY_QUESTIONS", "广州的星咖怎么样？"),
        ("REPUTATION_QUESTIONS", "广州有哪些好咖啡店？"),
    ],
)
def test_wrong_question_type_is_rejected_instead_of_becoming_a_false_visibility_sample(
    phase: str, text: str
) -> None:
    questions = _questions(phase, 5 if phase == "DISCOVERY_QUESTIONS" else 3)
    questions[0] = {"text": text}
    with pytest.raises(DiagnosticPreparationError) as error:
        asyncio.run(_run(_payload(phase), _Gateway({"questions": questions})))
    assert error.value.code == "GEO_DIAGNOSTIC_RESPONSE_INVALID"


def test_model_cannot_invent_source_ids_aliases_or_replace_target_region() -> None:
    payload = _payload()
    source_id = "web-" + hashlib.sha256(_URL.encode()).hexdigest()[:24]
    valid_profile = {
        "brandName": _BRAND,
        "website": "https://coffee.example.com/",
        "introduction": "咖啡饮品和烘焙食品",
        "brandTerms": [_BRAND],
        "region": "广州",
        "evidence": [{"sourceId": source_id, "quote": _BRAND_QUOTE}],
    }
    for change in (
        {"region": "全国"},
        {"website": "https://invented.example.com/"},
        {"brandTerms": [_BRAND, "编造的别名"]},
        {"evidence": [{"sourceId": "fake", "quote": _BRAND_QUOTE}]},
        {"evidence": [{"sourceId": source_id, "quote": "编造的获奖资料"}]},
    ):
        with pytest.raises(DiagnosticPreparationError) as error:
            asyncio.run(_run(payload, _Gateway({"profile": {**valid_profile, **change}})))
        assert error.value.code == "GEO_DIAGNOSTIC_RESPONSE_INVALID"


def test_question_slots_account_for_manual_items_and_other_question_group() -> None:
    payload = _payload("DISCOVERY_QUESTIONS")
    payload["input"]["discoveryQuestions"] = [
        {
            "id": "d1",
            "text": "广州有哪些可以买到咖啡和面包的店？",
            "origin": "USER",
            "edited": False,
        },
        {"id": "d2", "text": "广州哪些咖啡店支持到店自取？", "origin": "AI", "edited": True},
        {"id": "d3", "text": "这一条未修改AI候选将替换", "origin": "AI", "edited": False},
    ]
    payload["input"]["reputationQuestions"] = [
        {
            "id": f"r{i}",
            "text": f"小行星咖啡的第{i}种体验怎么样？",
            "origin": "USER",
            "edited": False,
        }
        for i in range(6)
    ]
    gateway = _Gateway()
    response = asyncio.run(_run(payload, gateway))
    assert gateway.calls[0]["candidateSlots"] == 2
    assert len(response.result.questions) == 2


def test_missing_region_private_urls_and_oversized_matrix_rejected_before_model() -> None:
    no_region = _payload("DISCOVERY_QUESTIONS")
    no_region["input"]["profile"]["region"] = ""
    private_url = _payload()
    private_url["input"]["profile"]["website"] = "https://127.0.0.1/private"
    too_many = _payload("DISCOVERY_QUESTIONS")
    too_many["input"]["discoveryQuestions"] = [
        {"id": f"d{i}", "text": f"广州第{i}种咖啡店如何选择？", "origin": "USER", "edited": False}
        for i in range(10)
    ]
    too_many["input"]["reputationQuestions"] = [
        {"id": "r1", "text": "小行星咖啡的体验如何？", "origin": "AI", "edited": False}
    ]
    for payload in (no_region, private_url, too_many):
        with pytest.raises(ValidationError):
            DiagnosticPreparationRequest.model_validate(payload)


@pytest.mark.parametrize("deadline", [1720000000, "1720000000", "1720000000.5", True, None])
def test_non_timestamp_deadline_is_rejected_before_model(deadline: object) -> None:
    payload = _payload()
    payload["deadlineAt"] = deadline
    with pytest.raises(ValidationError):
        DiagnosticPreparationRequest.model_validate(payload)


@pytest.mark.parametrize("field", ["warnings", "platformCodes"])
def test_java_input_is_a_strict_preparation_whitelist(field: str) -> None:
    payload = _payload()
    payload["input"][field] = []
    with pytest.raises(ValidationError):
        DiagnosticPreparationRequest.model_validate(payload)


def test_deadline_or_missing_search_fails_without_fake_success_or_model_call() -> None:
    async def run() -> None:
        for payload in (_payload(), _payload("DISCOVERY_QUESTIONS")):
            gateway = _Gateway()
            if payload["phase"] == "DISCOVERY_QUESTIONS":
                payload["deadlineAt"] = (datetime.now(UTC) - timedelta(seconds=1)).isoformat()
            with pytest.raises(DiagnosticPreparationError) as error:
                await prepare_diagnostic(
                    DiagnosticPreparationRequest.model_validate(payload),
                    cast(ModelGateway, gateway),
                    None,
                    received_at=datetime.now(UTC),
                )
            expected = (
                "GEO_DIAGNOSTIC_SEARCH_UNAVAILABLE"
                if payload["phase"] == "BRAND_RESEARCH"
                else "GEO_DIAGNOSTIC_DEADLINE_EXCEEDED"
            )
            assert error.value.code == expected and not gateway.calls

    asyncio.run(run())


def test_signed_http_route_rejects_unsigned_and_does_not_echo_private_input_on_error() -> None:
    body = json.dumps(_payload(), ensure_ascii=False).encode()
    with TestClient(
        create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    ) as client:
        unsigned = client.post(_PATH, content=body)
        unavailable = client.post(
            _PATH,
            content=body,
            headers={
                **signed_headers("POST", _PATH, body=body),
                "Content-Type": "application/json",
            },
        )
        private = _payload()
        private["input"]["privateCanary"] = "DO_NOT_ECHO_PRIVATE_CANARY"
        private_body = json.dumps(private).encode()
        invalid = client.post(
            _PATH,
            content=private_body,
            headers={
                **signed_headers("POST", _PATH, body=private_body),
                "Content-Type": "application/json",
            },
        )
    assert unsigned.status_code == 401
    assert unavailable.status_code == 503
    assert invalid.status_code == 422
    assert "DO_NOT_ECHO_PRIVATE_CANARY" not in invalid.text
    assert invalid.json()["detail"]["code"] == "GEO_DIAGNOSTIC_INPUT_INVALID"


def test_real_gateway_envelope_repairs_one_unbranded_reputation_question_with_known_usage() -> None:
    requests: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        payload = cast(dict[str, Any], json.loads(request.content))
        requests.append(payload)
        name = payload["tool_choice"]["function"]["name"]
        assert name == "prepare_geo_diagnostic"
        projected = _projected_request(
            payload["messages"][0]["content"], payload["messages"][1]["content"]
        )
        questions = _questions("REPUTATION_QUESTIONS", projected["candidateSlots"])
        if len(requests) == 1:
            questions[0] = {"text": "广州有哪些好咖啡店？"}
        output = {
            "profile": None,
            "competitors": [],
            "questions": questions,
            "warnings": [],
        }
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "tool_calls",
                        "message": {
                            "content": None,
                            "tool_calls": [
                                {
                                    "type": "function",
                                    "function": {"name": name, "arguments": json.dumps(output)},
                                }
                            ],
                        },
                    }
                ],
                "usage": {"prompt_tokens": 20, "completion_tokens": 10},
            },
        )

    transport = httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
    )
    gateway = DeepSeekAdapter(
        ProviderSettings(credential=SecretStr("test-only"), model="deepseek-test", max_retries=0),
        client=transport,
    )
    app = create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    repair_payload = _payload("REPUTATION_QUESTIONS")
    repair_payload["input"]["profile"]["introduction"] = "咖啡饮品到店自取服务。" * 500
    repair_payload["input"]["profile"]["brandTerms"] = [_BRAND] + ["别" * 200] * 49
    repair_payload["input"]["sources"] = _large_sources(32, 600, title_chars=500)
    body = json.dumps(repair_payload, ensure_ascii=False).encode()
    with TestClient(app) as client:
        app.state.geo_visual_gateway = gateway
        response = client.post(
            _PATH,
            content=body,
            headers={
                **signed_headers("POST", _PATH, body=body),
                "Content-Type": "application/json",
            },
        )
    asyncio.run(transport.aclose())
    assert response.status_code == 200, response.text
    assert len(requests) == 2
    assert requests[0]["messages"][1] == requests[1]["messages"][1]
    assert requests[0]["messages"][0]["content"] in requests[1]["messages"][0]["content"]
    assert "response_format" not in requests[0]
    assert response.json()["synthesisUsage"]["providerCallCount"] == 2
    assert response.json()["synthesisUsage"]["inputTokens"] == 40
    assert response.json()["searchRequestCount"] == 0
    assert len(response.json()["result"]["questions"]) == 3
    assert _diagnostic_feedback(requests[1]["messages"][0]["content"]) == {
        "failureStage": "invalid_schema",
        "validationErrors": [{"path": "$", "type": "diag_q_no_own"}],
    }
    assert all(_http_request_estimate(request) <= 16000 for request in requests)
    assert all(
        estimate_model_messages(
            tuple(
                ModelMessage(role="user", content=item["content"]) for item in request["messages"]
            )
        )
        <= (11000 if index == 0 else 16000)
        for index, request in enumerate(requests)
    )


@pytest.mark.parametrize(
    ("count", "snippet_chars", "title_chars"), [(32, 600, 500), (24, 2000, 500)]
)
def test_chinese_evidence_projection_fits_both_requests_and_keeps_full_sources_and_manual_item(
    count: int, snippet_chars: int, title_chars: int
) -> None:
    raw = _payload("COMPETITORS")
    raw["input"]["sources"] = _large_sources(count, snippet_chars, title_chars=title_chars)
    raw["input"]["competitors"] = [
        {
            "id": "manual",
            "name": "用户自己选的店",
            "brandTerms": ["用户自己选的店"],
            "reason": "用户完整保留的手工理由",
            "sourceIds": ["saved-0"],
            "origin": "AI",
            "edited": True,
        }
    ]
    payload = DiagnosticPreparationRequest.model_validate(raw)
    frozen = payload.model_dump_json(by_alias=True)
    gateway = _Gateway()

    async def run() -> Any:
        async with httpx.AsyncClient(transport=httpx.MockTransport(_search_response)) as http:
            return await prepare_diagnostic(
                payload, cast(ModelGateway, gateway), _search(http), received_at=datetime.now(UTC)
            )

    response = asyncio.run(run())
    assert payload.model_dump_json(by_alias=True) == frozen
    returned = {source.id: source for source in response.result.sources}
    assert returned["saved-0"] == payload.input.sources[0]
    assert all(
        source == returned[source.id] for source in payload.input.sources if source.id in returned
    )
    assert len(returned) <= 32 and response.result.competitors
    assert gateway.calls[0]["candidateSlots"] == 4
    _assert_projected_competitor(
        gateway.calls[0]["input"]["competitors"], "用户自己选的店", ["用户自己选的店"]
    )
    shown = gateway.calls[0]["sources"]
    assert "saved-0" in {source["id"] for source in shown}
    assert _COMPETITOR_URL in {source["url"] for source in shown[:2]}
    assert all(source["snippet"] in returned[source["id"]].snippet for source in shown)
    assert any("部分来源摘录" in warning for warning in response.result.warnings)
    messages = gateway.message_calls[0]
    schema = _result_schema(payload, tuple(response.result.sources), 4).model_json_schema(
        by_alias=True
    )
    repaired = (
        ModelMessage(
            role="system",
            content=messages[0].content
            + "\n\n[STRUCTURED OUTPUT REPAIR]\n"
            + diagnostic_structured_repair_reserve(_REPAIR_INSTRUCTION, schema),
        ),
        *messages[1:],
    )
    assert estimate_model_messages(messages) <= 16000
    assert estimate_model_messages(repaired) <= 16000


@pytest.mark.parametrize("phase", ["DISCOVERY_QUESTIONS", "REPUTATION_QUESTIONS"])
@pytest.mark.parametrize("difference", [-1, 1])
def test_question_generation_rejects_inexact_candidate_count(phase: str, difference: int) -> None:
    count = 5 if phase == "DISCOVERY_QUESTIONS" else 3
    questions = _questions(phase, count)
    if difference < 0:
        questions = questions[:-1]
    else:
        questions.append(
            {
                "text": "广州如何为团队选择咖啡与烘焙自取服务？"
                if phase == "DISCOVERY_QUESTIONS"
                else "小行星咖啡的烘焙食品是否适合搭配自取咖啡？"
            }
        )
    gateway = _Gateway({"questions": questions})
    with pytest.raises(DiagnosticPreparationError) as error:
        asyncio.run(_run(_payload(phase), gateway))
    assert error.value.code == "GEO_DIAGNOSTIC_RESPONSE_INVALID"
    assert len(gateway.calls) == 1


@pytest.mark.parametrize("phase", ["DISCOVERY_QUESTIONS", "REPUTATION_QUESTIONS"])
def test_question_projection_omits_history_excerpts_and_fits_maximal_chinese_context(
    phase: str,
) -> None:
    raw = _payload(phase)
    raw["input"]["profile"]["introduction"] = "经营咖啡饮品与到店自取服务。" * 400
    raw["input"]["profile"]["brandTerms"] = [_BRAND] + ["别" * 200] * 49
    raw["input"]["sources"] = _large_sources(32, 600, title_chars=500)
    payload = DiagnosticPreparationRequest.model_validate(raw)
    frozen = payload.model_dump_json(by_alias=True)
    gateway = _Gateway()

    async def run() -> Any:
        return await prepare_diagnostic(
            payload, cast(ModelGateway, gateway), None, received_at=datetime.now(UTC)
        )

    response = asyncio.run(run())
    assert payload.model_dump_json(by_alias=True) == frozen
    assert response.result.sources == payload.input.sources
    assert gateway.calls[0]["sources"] == []
    assert gateway.calls[0]["sourceScope"] == "CONFIRMED_PROFILE_AND_SELECTED_COMPETITORS_ONLY"
    assert gateway.calls[0]["inputProjection"]["partialContext"] is True
    assert response.search_request_count == 0 and response.synthesis_usage.provider_call_count == 1


def test_quote_from_clipped_tail_cannot_validate_against_full_returned_source() -> None:
    raw = _payload("COMPETITORS")
    raw["input"]["sources"] = _large_sources(32, 600)
    hidden_quote = "隐藏咖啡品牌提供现磨咖啡与到店自取服务。"
    raw["input"]["sources"][0]["snippet"] = "普通公开业务。" * 100 + hidden_quote
    raw["input"]["competitors"] = [
        {
            "id": "manual",
            "name": "用户的对照店",
            "brandTerms": ["用户的对照店"],
            "reason": "手动选择",
            "sourceIds": ["saved-0"],
            "origin": "USER",
            "edited": False,
        }
    ]
    gateway = _Gateway(
        {
            "competitors": [
                {
                    "name": "隐藏咖啡品牌",
                    "brandTerms": ["隐藏咖啡品牌"],
                    "reason": "提供咖啡与到店自取服务",
                    "sourceIds": ["saved-0"],
                    "evidence": [{"sourceId": "saved-0", "quote": hidden_quote}],
                }
            ]
        }
    )
    with pytest.raises(DiagnosticPreparationError) as error:
        asyncio.run(_run(raw, gateway))
    assert error.value.code == "GEO_DIAGNOSTIC_RESPONSE_INVALID"
    shown = next(source for source in gateway.calls[0]["sources"] if source["id"] == "saved-0")
    assert hidden_quote in raw["input"]["sources"][0]["snippet"]
    assert hidden_quote not in shown["snippet"]


def test_signed_route_reproduces_old_pre_input_failure_without_model_http_then_fits_one_request(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    raw = _payload("COMPETITORS")
    raw["input"]["sources"] = _large_sources(32, 600)
    model_requests: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        data = cast(dict[str, Any], json.loads(request.content))
        model_requests.append(data)
        name = data["tool_choice"]["function"]["name"]
        shown = json.loads(data["messages"][1]["content"])["sources"]
        source = next(item for item in shown if item["url"] == _COMPETITOR_URL)
        result = {
            "profile": None,
            "competitors": [
                {
                    "name": "星环咖啡",
                    "brandTerms": ["星环咖啡"],
                    "reason": "同类咖啡业务与到店自取服务",
                    "sourceIds": [source["id"]],
                    "evidence": [{"sourceId": source["id"], "quote": _COMPETITOR_QUOTE}],
                }
            ],
            "questions": [],
            "warnings": [],
        }
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "tool_calls",
                        "message": {
                            "tool_calls": [
                                {
                                    "type": "function",
                                    "function": {"name": name, "arguments": json.dumps(result)},
                                }
                            ]
                        },
                    }
                ],
                "usage": {"prompt_tokens": 20, "completion_tokens": 10},
            },
        )

    def old_projection(payload: Any, sources: Any, slots: int, **_options: object) -> Any:
        data = {
            "phase": payload.phase,
            "candidateSlots": slots,
            "input": payload.input.model_dump(mode="json", by_alias=True, exclude={"sources"}),
            "sources": [source.model_dump(mode="json") for source in sources],
            "regionDefault": "",
            "sourceScope": "SEARCH_EXCERPTS_NOT_FULL_WEBSITE_AUDIT",
        }
        messages = (
            ModelMessage(role="system", content=diagnostic_preparation_prompt()),
            ModelMessage(
                role="user", content=json.dumps(data, ensure_ascii=False, separators=(",", ":"))
            ),
        )
        assert estimate_model_messages(messages) > 16000
        return messages, sources, False

    model_http = httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
    )
    search_http = httpx.AsyncClient(transport=httpx.MockTransport(_search_response))
    gateway = DeepSeekAdapter(
        ProviderSettings(credential=SecretStr("test-only"), model="deepseek-test", max_retries=0),
        client=model_http,
    )
    app = create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    body = json.dumps(raw, ensure_ascii=False).encode()
    with TestClient(app) as client:
        app.state.geo_visual_gateway = gateway
        app.state.geo_diagnostic_search = _search(search_http)
        monkeypatch.setattr("app.geo.diagnostic_preparation._project_evidence", old_projection)
        failed = client.post(
            _PATH,
            content=body,
            headers={
                **signed_headers("POST", _PATH, body=body),
                "Content-Type": "application/json",
            },
        )
        assert failed.status_code == 422
        assert failed.json()["detail"]["code"] == "GEO_DIAGNOSTIC_INPUT_BUDGET_EXCEEDED"
        assert "缩短资料" in failed.json()["detail"]["message"]
        assert not model_requests
        monkeypatch.undo()
        succeeded = client.post(
            _PATH,
            content=body,
            headers={
                **signed_headers("POST", _PATH, body=body),
                "Content-Type": "application/json",
            },
        )
    asyncio.run(model_http.aclose())
    asyncio.run(search_http.aclose())
    assert succeeded.status_code == 200, succeeded.text
    assert len(model_requests) == 1
    assert (
        estimate_model_messages(
            tuple(
                ModelMessage(role="user", content=item["content"])
                for item in model_requests[0]["messages"]
            )
        )
        <= 11000
    )
    assert _http_request_estimate(model_requests[0]) <= 16000
    assert succeeded.json()["synthesisUsage"]["providerCallCount"] == 1


def test_all_referenced_sources_survive_capacity_limit_without_inventing_fresh_evidence() -> None:
    raw = _payload("COMPETITORS")
    raw["input"]["sources"] = _large_sources(32, 600)
    raw["input"]["competitors"] = [
        {
            "id": f"manual-{index}",
            "name": f"用户选定的第{index}家店",
            "brandTerms": [f"用户选定的第{index}家店"],
            "reason": "用户完整选择理由",
            "sourceIds": [f"saved-{source}" for source in range(index * 8, (index + 1) * 8)],
            "origin": "USER",
            "edited": False,
        }
        for index in range(4)
    ]

    class EmptyGateway(_Gateway):
        async def complete_structured(
            self,
            messages: ModelMessages,
            response_model: type[BaseModel],
            **_options: object,
        ) -> tuple[BaseModel, GatewayUsage]:
            record_model_call(input_messages=messages)
            data = _projected_request(messages[0].content, messages[1].content)
            self.calls.append(data)
            self.message_calls.append(messages)
            return response_model.model_validate(
                {"profile": None, "competitors": [], "questions": [], "warnings": []}
            ), GatewayUsage(request_count=1, input_tokens=20, output_tokens=10)

    gateway = EmptyGateway()
    response = asyncio.run(_run(raw, gateway))
    assert {item.id for item in response.result.sources} == {
        f"saved-{index}" for index in range(32)
    }
    assert all(source["url"].startswith("https://saved") for source in gateway.calls[0]["sources"])
    assert len(gateway.calls[0]["input"]["competitors"]) == 4
    assert not response.result.competitors
    assert any("来源数量已达上限" in warning for warning in response.result.warnings)


def test_repeated_url_reuses_protected_id_and_one_excerpt_without_mutating_snapshot() -> None:
    raw = _payload("COMPETITORS")
    raw["input"]["sources"] = _large_sources(32, 600)
    for source in raw["input"]["sources"][:2]:
        source["url"] = _COMPETITOR_URL
        source["snippet"] = _COMPETITOR_QUOTE + "旧版公开资料仍保留在原始输入快照中。"
    raw["input"]["competitors"] = [
        {
            "id": "manual",
            "name": "用户的对照店",
            "brandTerms": ["用户的对照店"],
            "reason": "手动选择",
            "sourceIds": ["saved-0"],
            "origin": "USER",
            "edited": False,
        }
    ]
    frozen = json.dumps(raw, ensure_ascii=False, sort_keys=True)
    gateway = _Gateway()
    response = asyncio.run(_run(raw, gateway))
    assert json.dumps(raw, ensure_ascii=False, sort_keys=True) == frozen
    repeated = [item for item in gateway.calls[0]["sources"] if item["url"] == _COMPETITOR_URL]
    assert len(repeated) == 1 and repeated[0]["id"] == "saved-0"
    assert repeated[0]["snippet"] == raw["input"]["sources"][0]["snippet"]
    returned = next(item for item in response.result.sources if item.id == "saved-0")
    assert returned.snippet == raw["input"]["sources"][0]["snippet"]
    assert returned.url == _COMPETITOR_URL
    assert response.result.competitors[0].source_ids == ("saved-0",)


def test_protected_legacy_citations_do_not_hide_this_phase_competitor_search() -> None:
    raw = _payload("COMPETITORS")
    raw["input"]["sources"] = _large_sources(32, 600, title_chars=180)
    raw["input"]["competitors"] = [
        {
            "id": f"manual-{index}",
            "name": f"用户选定的第{index}家店",
            "brandTerms": [f"用户选定的第{index}家店"],
            "reason": "用户选择",
            "sourceIds": [f"saved-{source}" for source in range(index * 8, (index + 1) * 8)],
            "origin": "USER",
            "edited": False,
        }
        for index in range(3)
    ]
    gateway = _Gateway()
    response = asyncio.run(_run(raw, gateway))
    assert _COMPETITOR_URL in {source["url"] for source in gateway.calls[0]["sources"][:2]}
    returned_ids = {source.id for source in response.result.sources}
    assert {f"saved-{index}" for index in range(24)} <= returned_ids
    assert response.result.competitors and gateway.calls[0]["candidateSlots"] == 2


def test_evidence_allocation_target_does_not_reject_large_immutable_context_that_fits() -> None:
    raw = _payload("COMPETITORS")
    brand = "牌" * 200
    raw["input"]["brandName"] = brand
    raw["input"]["profile"] = {
        "brandName": brand,
        "website": "https://public.example.com/" + "p" * 2021,
        "introduction": "业" * 6000,
        "brandTerms": [],
        "region": "市" * 100,
    }
    raw["input"]["competitors"] = [
        {
            "id": str(index) * 128,
            "name": str(index) + "店" * 199,
            "brandTerms": [],
            "reason": "用户选择",
            "sourceIds": [],
            "origin": "USER",
            "edited": False,
        }
        for index in range(4)
    ]
    gateway = _Gateway()
    response = asyncio.run(_run(raw, gateway))
    assert response.result.competitors
    projected = gateway.message_calls[0]
    assert estimate_model_messages(projected) <= 11000
    assert gateway.calls[0]["input"]["profile"]["website"] == raw["input"]["profile"]["website"]


def test_discovery_blacklist_uses_original_aliases_omitted_from_model_projection() -> None:
    raw = _payload("DISCOVERY_QUESTIONS")
    hidden_alias = "星语咖啡"
    raw["input"]["profile"]["introduction"] = "咖啡饮品到店自取服务。" * 500
    raw["input"]["profile"]["brandTerms"] = [_BRAND] + ["别" * 200] * 48 + [hidden_alias]
    questions = _questions("DISCOVERY_QUESTIONS", 5)
    questions[0] = {"text": f"广州的{hidden_alias}适合哪些人？"}
    gateway = _Gateway({"questions": questions})
    with pytest.raises(DiagnosticPreparationError) as error:
        asyncio.run(_run(raw, gateway))
    assert error.value.code == "GEO_DIAGNOSTIC_RESPONSE_INVALID"
    assert hidden_alias not in gateway.calls[0]["input"]["profile"]["brandTerms"]


@pytest.mark.parametrize("field", ["id", "origin", "edited"])
def test_candidate_metadata_echo_is_rejected_without_stripping_unknown_fields(field: str) -> None:
    source_id = "web-" + hashlib.sha256(_COMPETITOR_URL.encode()).hexdigest()[:24]
    candidate = {
        "name": "星环咖啡",
        "brandTerms": ["星环咖啡"],
        "reason": "同类咖啡业务",
        "sourceIds": [source_id],
        "evidence": [{"sourceId": source_id, "quote": _COMPETITOR_QUOTE}],
        field: True if field == "edited" else "PRIVATE_MANAGED_VALUE",
    }
    gateway = _Gateway({"competitors": [candidate]})
    with pytest.raises(DiagnosticPreparationError) as error:
        asyncio.run(_run(_payload("COMPETITORS"), gateway))
    assert error.value.code == "GEO_DIAGNOSTIC_RESPONSE_INVALID"
    assert len(gateway.calls) == 1


@pytest.mark.parametrize("phase", ["DISCOVERY_QUESTIONS", "REPUTATION_QUESTIONS"])
def test_retained_questions_and_selected_competitors_have_only_semantic_model_fields(
    phase: str,
) -> None:
    raw = _payload(phase)
    group = "discoveryQuestions" if phase == "DISCOVERY_QUESTIONS" else "reputationQuestions"
    texts = ["广州哪些咖啡店支持预约自取？", "广州哪里可以同时买咖啡和面包？"]
    if phase == "REPUTATION_QUESTIONS":
        texts = ["小行星咖啡适合哪些人？", "小行星咖啡有哪些服务项目？"]
    raw["input"][group] = [
        {"id": "user-question", "text": texts[0], "origin": "USER", "edited": False},
        {"id": "edited-question", "text": texts[1], "origin": "AI", "edited": True},
    ]
    raw["input"]["competitors"] = [
        {
            "id": "unmodified-ai-competitor",
            "name": "星环咖啡",
            "brandTerms": ["星环咖啡", "星环"],
            "reason": "内部理由不应回显",
            "sourceIds": [],
            "origin": "AI",
            "edited": False,
        }
    ]
    frozen = json.dumps(raw, ensure_ascii=False, sort_keys=True)
    gateway = _Gateway()
    response = asyncio.run(_run(raw, gateway))
    assert json.dumps(raw, ensure_ascii=False, sort_keys=True) == frozen
    assert gateway.calls[0]["input"][group] == [{"text": text} for text in texts]
    assert gateway.calls[0]["input"]["competitors"] == [
        {"name": "星环咖啡", "brandTerms": ["星环咖啡", "星环"]}
    ]
    assert gateway.calls[0]["candidateSlots"] == (3 if phase == "DISCOVERY_QUESTIONS" else 1)
    assert len(response.result.questions) == (3 if phase == "DISCOVERY_QUESTIONS" else 1)


def test_backed_retained_competitor_is_a_noop_after_strict_grounding() -> None:
    raw = _payload("COMPETITORS")
    raw["input"]["competitors"] = [
        {
            "id": "retained",
            "name": "星环咖啡",
            "brandTerms": ["星环咖啡"],
            "reason": "用户已修改的保留理由",
            "sourceIds": [],
            "origin": "AI",
            "edited": True,
        }
    ]
    frozen = json.dumps(raw, ensure_ascii=False, sort_keys=True)
    gateway = _Gateway()
    response = asyncio.run(_run(raw, gateway))
    assert not response.result.competitors
    assert any("未重复添加" in warning for warning in response.result.warnings)
    assert any("可靠新增竞品不足" in warning for warning in response.result.warnings)
    assert json.dumps(raw, ensure_ascii=False, sort_keys=True) == frozen
    assert gateway.calls[0]["candidateSlots"] == 4
    _assert_projected_competitor(gateway.calls[0]["input"]["competitors"], "星环咖啡", ["星环咖啡"])
    assert raw["input"]["competitors"][0]["reason"] == "用户已修改的保留理由"


def _candidate(name: str, source_id: str, quote: str) -> dict[str, Any]:
    return {
        "name": name,
        "brandTerms": [name],
        "reason": "来源支持的同类咖啡与自取业务",
        "sourceIds": [source_id],
        "evidence": [{"sourceId": source_id, "quote": quote}],
    }


def _retained_competitor_payload() -> dict[str, Any]:
    raw = _payload("COMPETITORS")
    raw["input"]["sources"] = [
        {
            "id": "retained-source",
            "title": "星环咖啡官网",
            "url": _COMPETITOR_URL,
            "snippet": _COMPETITOR_QUOTE,
        },
        {
            "id": "new-source",
            "title": "月光咖啡官网",
            "url": "https://new.example.com/about",
            "snippet": "月光咖啡在广州提供咖啡、烘焙和到店自取服务。",
        },
    ]
    raw["input"]["competitors"] = [
        {
            "id": "retained",
            "name": "星环咖啡",
            "brandTerms": ["星环咖啡", "星环"],
            "reason": "用户确认的完整保留理由",
            "sourceIds": ["retained-source"],
            "origin": "AI",
            "edited": True,
        }
    ]
    return raw


def test_grounded_quote_cannot_span_artificial_title_snippet_join() -> None:
    raw = _retained_competitor_payload()
    source = raw["input"]["sources"][1]
    payload = DiagnosticPreparationRequest.model_validate(raw)
    schema = _result_schema(payload, payload.input.sources, 4)
    candidate = _candidate("月光咖啡", source["id"], source["title"] + "\n" + source["snippet"])
    result = {"profile": None, "competitors": [candidate], "questions": [], "warnings": []}
    with pytest.raises(ValidationError) as failure:
        schema.model_validate(result)
    assert failure.value.errors(include_input=False)[0]["type"] == "diag_quote_absent"
    candidate["evidence"][0]["quote"] = source["snippet"]
    assert schema.model_validate(result)


def test_competitor_search_excludes_only_retained_names_without_new_calls_or_operators() -> None:
    raw = _retained_competitor_payload()
    raw["input"]["competitors"].append(
        {
            "id": "old-unedited",
            "name": "旧AI未编辑店",
            "brandTerms": ["旧AI未编辑店"],
            "reason": "未保留的旧候选",
            "sourceIds": [],
            "origin": "AI",
            "edited": False,
        }
    )
    queries: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        queries.append(json.loads(request.content)["query"])
        return _search_response(request)

    async def run() -> Any:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
            return await prepare_diagnostic(
                DiagnosticPreparationRequest.model_validate(raw),
                cast(ModelGateway, _Gateway()),
                _search(http),
                received_at=datetime.now(UTC),
            )

    response = asyncio.run(run())
    assert len(queries) == response.search_request_count == 3
    assert all("不重复推荐已经保留的品牌: 星环咖啡" in query for query in queries)
    assert all("旧AI未编辑店" not in query for query in queries)
    assert all(
        "-星环咖啡" not in query and "NOT " not in query and "site:" not in query
        for query in queries
    )


def test_retained_and_new_grounded_competitors_return_only_new_candidates() -> None:
    raw = _retained_competitor_payload()
    frozen = json.dumps(raw, ensure_ascii=False, sort_keys=True)
    new_quote = raw["input"]["sources"][1]["snippet"]
    gateway = _Gateway(
        {
            "competitors": [
                _candidate("星环咖啡", "retained-source", _COMPETITOR_QUOTE),
                _candidate("月光咖啡", "new-source", new_quote),
            ]
        }
    )
    response = asyncio.run(_run(raw, gateway))
    assert [item.name for item in response.result.competitors] == ["月光咖啡"]
    assert any("未重复添加" in warning for warning in response.result.warnings)
    assert any("可靠新增竞品不足" in warning for warning in response.result.warnings)
    returned = {item.id: item.model_dump(mode="json") for item in response.result.sources}
    assert all(returned[item["id"]] == item for item in raw["input"]["sources"])
    assert json.dumps(raw, ensure_ascii=False, sort_keys=True) == frozen


@pytest.mark.parametrize(
    ("failure", "expected_code"),
    [
        ("source", "diag_src_missing"),
        ("quote", "diag_quote_absent"),
        ("name", "diag_name_proof"),
        ("alias", "diag_alias_proof"),
        ("citations", "diag_cite_set"),
    ],
)
def test_retained_competitor_never_bypasses_any_grounding_check(
    failure: str, expected_code: str
) -> None:
    raw = _retained_competitor_payload()
    candidate = _candidate("星环咖啡", "retained-source", _COMPETITOR_QUOTE)
    if failure == "source":
        candidate["evidence"][0]["sourceId"] = "missing-source"
    elif failure == "quote":
        candidate["evidence"][0]["quote"] = "PRIVATE_UNGROUNDED_QUOTE_CANARY"
    elif failure == "name":
        candidate["evidence"] = [
            {"sourceId": "new-source", "quote": raw["input"]["sources"][1]["snippet"]}
        ]
        candidate["sourceIds"] = ["new-source"]
    elif failure == "alias":
        candidate["brandTerms"].append("PRIVATE_UNGROUNDED_ALIAS_CANARY")
    else:
        candidate["sourceIds"] = ["new-source"]
    payload = DiagnosticPreparationRequest.model_validate(raw)
    schema = _result_schema(payload, payload.input.sources, 4)
    with pytest.raises(ValidationError) as error:
        schema.model_validate(
            {"profile": None, "competitors": [candidate], "questions": [], "warnings": []}
        )
    assert (
        error.value.errors(include_input=False, include_context=False)[0]["type"] == expected_code
    )
    gateway = _Gateway({"competitors": [candidate]})
    with pytest.raises(DiagnosticPreparationError) as runtime_error:
        asyncio.run(_run(raw, gateway))
    assert runtime_error.value.code == "GEO_DIAGNOSTIC_RESPONSE_INVALID"


@pytest.mark.parametrize("phase", ["DISCOVERY_QUESTIONS", "REPUTATION_QUESTIONS"])
def test_zero_candidate_slots_has_empty_phase_example_and_keeps_retained_snapshot(
    phase: str,
) -> None:
    raw = _payload(phase)
    discovery = phase == "DISCOVERY_QUESTIONS"
    group = "discoveryQuestions" if discovery else "reputationQuestions"
    raw["input"][group] = [
        {
            "id": f"retained-{index}",
            "text": f"广州第{index}种咖啡业务如何选择？"
            if discovery
            else f"小行星咖啡第{index}种服务体验如何？",
            "origin": "USER",
            "edited": False,
        }
        for index in range(5 if discovery else 3)
    ]
    frozen = json.dumps(raw, ensure_ascii=False, sort_keys=True)
    gateway = _Gateway({"questions": []})
    response = asyncio.run(_run(raw, gateway))
    assert json.dumps(raw, ensure_ascii=False, sort_keys=True) == frozen
    assert gateway.calls[0]["candidateSlots"] == 0
    assert "phaseOutputExample" not in gateway.calls[0]
    assert not response.result.questions


@pytest.mark.parametrize("recover", [True, False])
def test_native_manual_regeneration_repairs_metadata_echo_once_or_fails_strictly(
    recover: bool,
    caplog: pytest.LogCaptureFixture,
) -> None:
    raw = _payload("COMPETITORS")
    raw["input"]["sources"] = _large_sources(32, 600)
    raw["input"]["competitors"] = [
        {
            "id": "retained-first",
            "name": "用户保留的咖啡店",
            "brandTerms": ["用户保留的咖啡店"],
            "reason": "人工改后的第一项理由",
            "sourceIds": [],
            "origin": "AI",
            "edited": True,
        }
    ] + [
        {
            "id": f"old-ai-{index}",
            "name": f"旧AI咖啡{index}",
            "brandTerms": [f"旧AI咖啡{index}"],
            "reason": "未编辑项目将替换",
            "sourceIds": [],
            "origin": "AI",
            "edited": False,
        }
        for index in range(4)
    ]
    frozen = json.dumps(raw, ensure_ascii=False, sort_keys=True)
    requests: list[dict[str, Any]] = []
    names = [f"星环{index}咖啡" for index in range(1, 5)]
    quotes = [f"{name}提供现磨咖啡和烘焙食品，在广州提供到店自取服务。" for name in names]

    def search_handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "code": 200,
                "data": {
                    "webPages": {
                        "value": [
                            {
                                "name": name,
                                "url": f"https://candidate{index}.example.com/about",
                                "summary": quote,
                            }
                            for index, (name, quote) in enumerate(zip(names, quotes, strict=True))
                        ]
                    }
                },
            },
        )

    def model_handler(request: httpx.Request) -> httpx.Response:
        data = cast(dict[str, Any], json.loads(request.content))
        requests.append(data)
        projected = _projected_request(
            data["messages"][0]["content"], data["messages"][1]["content"]
        )
        _assert_projected_competitor(
            projected["input"]["competitors"], "用户保留的咖啡店", ["用户保留的咖啡店"]
        )
        assert projected["candidateSlots"] == 4
        contract = projected["candidateContract"]
        assert contract["version"] == "GEO_DIAGNOSTIC_PREPARATION_V5"
        assert contract["candidateLimit"] == 4 and "phaseOutputExample" not in projected
        schema = data["tools"][0]["function"]["parameters"]
        assert schema["properties"]["profile"]["type"] == "null"
        assert schema["properties"]["questions"]["maxItems"] == 0
        assert schema["properties"]["competitors"]["maxItems"] == 4
        candidates: list[dict[str, Any]] = []
        for index, (name, quote) in enumerate(zip(names, quotes, strict=True)):
            source = next(
                item
                for item in projected["sources"]
                if item["url"] == f"https://candidate{index}.example.com/about"
            )
            candidates.append(
                {
                    "name": name,
                    "brandTerms": [name],
                    "reason": "同类咖啡与自取业务",
                    "sourceIds": [source["id"]],
                    "evidence": [{"sourceId": source["id"], "quote": quote}],
                }
            )
        if len(requests) == 1 or not recover:
            candidates[0].update(
                {"id": "PRIVATE_SCHEMA_ECHO_CANARY", "origin": "AI", "edited": True}
            )
        output = {"profile": None, "competitors": candidates, "questions": [], "warnings": []}
        name = data["tool_choice"]["function"]["name"]
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "tool_calls",
                        "message": {
                            "tool_calls": [
                                {
                                    "type": "function",
                                    "function": {"name": name, "arguments": json.dumps(output)},
                                }
                            ]
                        },
                    }
                ],
                "usage": {"prompt_tokens": 20, "completion_tokens": 10},
            },
        )

    model_http = httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(model_handler)
    )
    search_http = httpx.AsyncClient(transport=httpx.MockTransport(search_handler))
    gateway = DeepSeekAdapter(
        ProviderSettings(credential=SecretStr("test-only"), model="deepseek-test", max_retries=0),
        client=model_http,
    )
    app = create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    body = json.dumps(raw, ensure_ascii=False).encode()
    with TestClient(app) as client:
        app.state.geo_visual_gateway = gateway
        app.state.geo_diagnostic_search = _search(search_http)
        response = client.post(
            _PATH,
            content=body,
            headers={
                **signed_headers("POST", _PATH, body=body),
                "Content-Type": "application/json",
            },
        )
    asyncio.run(model_http.aclose())
    asyncio.run(search_http.aclose())
    assert json.dumps(raw, ensure_ascii=False, sort_keys=True) == frozen
    assert len(requests) == 2 and requests[0]["messages"][1] == requests[1]["messages"][1]
    assert all(_http_request_estimate(request) <= 16000 for request in requests)
    assert "不得输出 id、origin、edited" in requests[1]["messages"][0]["content"]
    assert "PRIVATE_SCHEMA_ECHO_CANARY" not in caplog.text
    if recover:
        assert response.status_code == 200, response.text
        assert response.json()["promptVersion"] == "GEO_DIAGNOSTIC_PREPARATION_V5"
        assert len(response.json()["result"]["competitors"]) == 4
        assert response.json()["synthesisUsage"]["providerCallCount"] == 2
    else:
        assert response.status_code == 503
        assert response.json()["detail"]["code"] == "GEO_DIAGNOSTIC_RESPONSE_INVALID"


def _diagnostic_feedback(system_message: str) -> dict[str, Any]:
    marker = "[SAFE DIAGNOSTIC STRUCTURED OUTPUT FEEDBACK]\n"
    feedback = system_message.split(marker, 1)[1].split("\n", 1)[0]
    return cast(dict[str, Any], json.loads(feedback))


@pytest.mark.parametrize(
    "variant", ["schema_envelope", "input_echo", "wrapped_valid", "trailing_json"]
)
@pytest.mark.parametrize("recover", [True, False])
def test_native_wrong_root_or_trailing_json_repairs_once_without_unwrapping(
    variant: str, recover: bool, caplog: pytest.LogCaptureFixture
) -> None:
    raw = _payload("COMPETITORS")
    raw["input"]["sources"] = _large_sources(32, 600, title_chars=500)
    raw["input"]["profile"]["introduction"] = ("经营咖啡与烘焙食品，到店自取服务。" * 400)[:6000]
    raw["input"]["profile"]["brandTerms"] = [_BRAND] + ["别" * 200] * 49
    raw["input"]["competitors"] = [
        {
            "id": "retained-manual",
            "name": "用户的对照店",
            "brandTerms": ["用户的对照店"],
            "reason": "用户修改的完整理由仍由原始快照保存。",
            "sourceIds": ["saved-0"],
            "origin": "AI",
            "edited": True,
        }
    ]
    frozen = json.dumps(raw, ensure_ascii=False, sort_keys=True)
    requests: list[dict[str, Any]] = []
    canary = "PRIVATE_WRONG_ROOT_CANARY"

    def handler(request: httpx.Request) -> httpx.Response:
        data = cast(dict[str, Any], json.loads(request.content))
        requests.append(data)
        projected = _projected_request(
            data["messages"][0]["content"], data["messages"][1]["content"]
        )
        assert projected["candidateSlots"] == 4
        _assert_projected_competitor(
            projected["input"]["competitors"], "用户的对照店", ["用户的对照店"]
        )
        source = next(item for item in projected["sources"] if item["url"] == _COMPETITOR_URL)
        valid = {
            "profile": None,
            "competitors": [
                {
                    "name": "星环咖啡",
                    "brandTerms": ["星环咖啡"],
                    "reason": "同类咖啡、烘焙与到店自取业务",
                    "sourceIds": [source["id"]],
                    "evidence": [{"sourceId": source["id"], "quote": _COMPETITOR_QUOTE}],
                }
            ],
            "questions": [],
            "warnings": [],
        }
        if len(requests) == 2 and recover:
            arguments = json.dumps(valid, ensure_ascii=False)
        elif variant == "schema_envelope":
            arguments = json.dumps(
                {"type": "object", "properties": valid, "required": list(valid), canary: canary},
                ensure_ascii=False,
            )
        elif variant == "input_echo":
            echoed = {
                key: projected[key]
                for key in (
                    "phase",
                    "candidateSlots",
                    "input",
                    "inputProjection",
                    "sourceProjection",
                    "sources",
                )
            }
            arguments = json.dumps(
                {**echoed, "regionDefault": "", canary: canary}, ensure_ascii=False
            )
        elif variant == "wrapped_valid":
            arguments = json.dumps(
                {"result": valid, "wrapper": canary, canary: canary}, ensure_ascii=False
            )
        else:
            arguments = json.dumps(valid, ensure_ascii=False) + "\n" + canary
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "tool_calls",
                        "message": {
                            "tool_calls": [
                                {
                                    "type": "function",
                                    "function": {
                                        "name": "prepare_geo_diagnostic",
                                        "arguments": arguments,
                                    },
                                }
                            ]
                        },
                    }
                ],
                "usage": {"prompt_tokens": 20, "completion_tokens": 10},
            },
        )

    model_http = httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
    )
    search_http = httpx.AsyncClient(transport=httpx.MockTransport(_search_response))
    gateway = DeepSeekAdapter(
        ProviderSettings(credential=SecretStr("test-only"), model="deepseek-test", max_retries=0),
        client=model_http,
    )
    app = create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    body = json.dumps(raw, ensure_ascii=False).encode()
    with TestClient(app) as client:
        app.state.geo_visual_gateway = gateway
        app.state.geo_diagnostic_search = _search(search_http)
        response = client.post(
            _PATH,
            content=body,
            headers={
                **signed_headers("POST", _PATH, body=body),
                "Content-Type": "application/json",
            },
        )
    asyncio.run(model_http.aclose())
    asyncio.run(search_http.aclose())
    assert json.dumps(raw, ensure_ascii=False, sort_keys=True) == frozen
    assert len(requests) == 2
    assert requests[0]["messages"][1] == requests[1]["messages"][1]
    assert requests[0]["tools"] == requests[1]["tools"]
    assert all(_http_request_estimate(request) <= 16000 for request in requests)
    first_system = requests[0]["messages"][0]["content"]
    repaired_system = requests[1]["messages"][0]["content"]
    assert repaired_system.startswith(first_system)
    instruction = repaired_system[len(first_system) :]
    assert "顶层必须且只能含 profile、competitors、questions、warnings" in instruction
    assert "JSON 之后不得附加任何字符" in instruction
    feedback = _diagnostic_feedback(repaired_system)
    assert feedback["failureStage"] == (
        "invalid_json" if variant == "trailing_json" else "invalid_schema"
    )
    errors = feedback["validationErrors"]
    assert len(errors) <= 8
    assert all(len(error["path"].split(".")) <= 6 for error in errors)
    if variant == "trailing_json":
        assert errors == []
    else:
        assert {error["path"] for error in errors if error["type"] == "missing"} == {
            "profile",
            "competitors",
            "questions",
            "warnings",
        }
        assert all(
            error["path"] == "<unknown>" for error in errors if error["type"] == "extra_forbidden"
        )
        assert any(error["type"] == "extra_forbidden" for error in errors)
    assert canary not in instruction
    assert canary not in response.text
    assert canary not in json.dumps([record.__dict__ for record in caplog.records], default=str)
    schema = requests[0]["tools"][0]["function"]["parameters"]
    worst = diagnostic_structured_repair_reserve(_REPAIR_INSTRUCTION, schema)
    worst_request = {
        **requests[0],
        "messages": [
            {
                "role": "system",
                "content": first_system + "\n\n[STRUCTURED OUTPUT REPAIR]\n" + worst,
            },
            requests[0]["messages"][1],
        ],
    }
    assert _http_request_estimate(worst_request) <= 16000
    if recover:
        assert response.status_code == 200, response.text
        result = response.json()["result"]
        assert response.json()["synthesisUsage"]["providerCallCount"] == 2
        assert response.json()["synthesisUsage"]["inputTokens"] == 40
        assert len(result["competitors"]) == 1
        returned = {source["id"]: source for source in result["sources"]}
        assert len(returned) == 32
        assert returned["saved-0"] == raw["input"]["sources"][0]
        assert all(
            original == returned[original["id"]]
            for original in raw["input"]["sources"]
            if original["id"] in returned
        )
        assert set(result["competitors"][0]["sourceIds"]) <= returned.keys()
    else:
        assert response.status_code == 503
        assert response.json()["detail"]["code"] == "GEO_DIAGNOSTIC_RESPONSE_INVALID"


def test_maximal_chinese_projection_reserves_eight_six_segment_errors_with_native_schema() -> None:
    raw = _payload("COMPETITORS")
    raw["input"]["profile"]["introduction"] = "业" * 6000
    raw["input"]["profile"]["brandTerms"] = [_BRAND] + ["别" * 200] * 49
    raw["input"]["sources"] = _large_sources(32, 600, title_chars=500)
    payload = DiagnosticPreparationRequest.model_validate(raw)
    frozen = payload.model_dump_json(by_alias=True)
    messages, shown, partial = _project_evidence(payload, payload.input.sources, 5)
    schema = _result_schema(payload, shown, 5).model_json_schema(by_alias=True)
    tools = (
        ModelToolDefinition(
            name="prepare_geo_diagnostic",
            description="Prepare grounded diagnostic card candidates; never execute.",
            parameters=schema,
        ),
    )
    reserve = diagnostic_structured_repair_reserve(_REPAIR_INSTRUCTION, schema)
    feedback = _diagnostic_feedback(reserve)
    assert len(feedback["validationErrors"]) == 8
    assert all(len(error["path"].split(".")) == 6 for error in feedback["validationErrors"])
    allowed_types = STRUCTURED_SAFE_VALIDATION_TYPES | DIAGNOSTIC_SAFE_VALIDATION_TYPES
    assert max(map(len, allowed_types)) == 18
    assert all(len(error["type"]) == 18 for error in feedback["validationErrors"])
    longest_diagnostic_type = max(DIAGNOSTIC_SAFE_VALIDATION_TYPES, key=len)
    canary = "PRIVATE_WORST_FEEDBACK_CANARY"
    validation_error = ValidationError.from_exception_data(
        "DiagnosticCandidate",
        [
            {
                "type": PydanticCustomError(
                    cast(LiteralString, longest_diagnostic_type), canary, {"private": canary}
                ),
                "loc": tuple(["introduction"] * 7),
                "input": canary,
            }
            for _ in range(9)
        ],
    )
    actual_errors = DeepSeekAdapter._safe_validation_errors(  # pyright: ignore[reportPrivateUsage]
        validation_error, schema, diagnostic=True
    )
    assert len(actual_errors) == 8
    assert all(len(error["path"].split(".")) == 6 for error in actual_errors)
    actual_instruction = diagnostic_structured_repair_instruction(
        _REPAIR_INSTRUCTION, "invalid_schema", actual_errors
    )
    assert canary not in actual_instruction
    assert len(actual_instruction.encode()) <= len(reserve.encode())
    assert estimate_model_messages(messages) <= 11000
    assert estimate_model_messages(native_request_budget_messages(messages, tools)) <= 16000
    repaired = (
        ModelMessage(
            role="system",
            content=messages[0].content + "\n\n[STRUCTURED OUTPUT REPAIR]\n" + reserve,
        ),
        *messages[1:],
    )
    assert estimate_model_messages(native_request_budget_messages(repaired, tools)) <= 16000
    actual_repaired = (
        ModelMessage(
            role="system",
            content=messages[0].content + "\n\n[STRUCTURED OUTPUT REPAIR]\n" + actual_instruction,
        ),
        *messages[1:],
    )
    assert estimate_model_messages(native_request_budget_messages(actual_repaired, tools)) <= 16000
    assert payload.model_dump_json(by_alias=True) == frozen
    assert len(payload.input.sources) == 32 and shown and partial


def test_unknown_repair_failure_stage_is_redacted_instead_of_echoed() -> None:
    canary = "PRIVATE_FAILURE_STAGE_CANARY"
    instruction = diagnostic_structured_repair_instruction(_REPAIR_INSTRUCTION, canary, [])
    assert _diagnostic_feedback(instruction) == {"failureStage": "unknown", "validationErrors": []}
    assert canary not in instruction


@pytest.mark.parametrize(
    "phase",
    [
        "BRAND_RESEARCH",
        "COMPETITORS",
        "DISCOVERY_QUESTIONS",
        "REPUTATION_QUESTIONS",
    ],
)
def test_v5_native_tool_has_only_phase_valid_fields_without_template_or_input_shell(
    phase: str,
) -> None:
    payload = DiagnosticPreparationRequest.model_validate(_payload(phase))
    slots = 1 if phase == "BRAND_RESEARCH" else 5 if phase != "REPUTATION_QUESTIONS" else 3
    model = _result_schema(payload, payload.input.sources, slots)
    wire = cast(
        dict[str, Any],
        DeepSeekAdapter._structured_tool_payload(  # pyright: ignore[reportPrivateUsage]
            model,
            "prepare_geo_diagnostic",
            None,
        ),
    )
    function = wire["tools"][0]["function"]
    schema = function["parameters"]
    assert schema["additionalProperties"] is False
    assert (
        set(schema["required"])
        == set(schema["properties"])
        == {
            "profile",
            "competitors",
            "questions",
            "warnings",
        }
    )
    assert function["name"] == wire["tool_choice"]["function"]["name"] == "prepare_geo_diagnostic"
    assert "example" not in json.dumps(schema).lower()
    if phase != "BRAND_RESEARCH":
        assert schema["properties"]["profile"]["type"] == "null"
    if phase != "COMPETITORS":
        assert schema["properties"]["competitors"]["maxItems"] == 0
    if "QUESTIONS" not in phase:
        assert schema["properties"]["questions"]["maxItems"] == 0
    else:
        assert schema["properties"]["questions"]["minItems"] == slots
        assert schema["properties"]["questions"]["maxItems"] == slots
    messages, _, _ = _project_evidence(payload, payload.input.sources, slots)
    system = messages[0].content
    assert "phaseOutputExample" not in system and '"candidateContract"' not in system
    assert "<实际" not in system and "<同一个" not in system
    with pytest.raises(ValidationError):
        model.model_validate({"input": payload.input.model_dump(), "sources": []})


def test_private_introduction_and_unbacked_manual_names_never_enter_search_queries() -> None:
    raw = _payload("COMPETITORS")
    intro = "PRIVATE_INTRODUCTION_CANARY_请不要公开内部约束"
    name = "PRIVATE_MANUAL_COMPETITOR_CANARY"
    raw["input"]["profile"]["introduction"] = intro
    raw["input"]["competitors"] = [
        {
            "id": "manual-private",
            "name": name,
            "brandTerms": [name],
            "reason": "PRIVATE_REASON_CANARY",
            "sourceIds": [],
            "origin": "USER",
            "edited": True,
        }
    ]
    gateway = _Gateway()
    queries: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        queries.append(json.loads(request.content)["query"])
        return _search_response(request)

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
            await prepare_diagnostic(
                DiagnosticPreparationRequest.model_validate(raw),
                cast(ModelGateway, gateway),
                _search(http),
                received_at=datetime.now(UTC),
            )

    asyncio.run(scenario())
    assert len(queries) == 3
    assert not any("PRIVATE_" in query or "内部约束" in query for query in queries)
    assert gateway.calls[0]["input"]["profile"]["introduction"] == intro
    assert gateway.calls[0]["input"]["competitors"][0]["name"] == name


@pytest.mark.parametrize("code", list(DiagnosticValidationCode))
def test_diagnostic_semantic_failures_have_code_owned_types_only_for_diagnostic_repair(
    code: DiagnosticValidationCode,
) -> None:
    raw = _retained_competitor_payload()
    raw["input"]["sources"].append(
        {"id": "brand-source", "title": "品牌资料", "url": _URL, "snippet": _BRAND_QUOTE}
    )
    slots = 4
    candidates = [_candidate("星环咖啡", "retained-source", _COMPETITOR_QUOTE)]
    output: dict[str, Any] = {
        "profile": None,
        "competitors": candidates,
        "questions": [],
        "warnings": [],
    }
    brand_codes = {
        DiagnosticValidationCode.PHASE_SHAPE,
        DiagnosticValidationCode.BRAND_CHANGED,
        DiagnosticValidationCode.REGION_CHANGED,
        DiagnosticValidationCode.PRIMARY_TERM,
        DiagnosticValidationCode.BRAND_PROOF,
        DiagnosticValidationCode.WEBSITE_HOST,
    }
    if code in brand_codes:
        raw["phase"] = "BRAND_RESEARCH"
        output["competitors"] = []
        profile = {
            "brandName": _BRAND,
            "website": _URL,
            "introduction": "咖啡与自取业务",
            "brandTerms": [_BRAND],
            "region": "广州",
            "evidence": [{"sourceId": "brand-source", "quote": _BRAND_QUOTE}],
        }
        output["profile"] = profile
        if code == DiagnosticValidationCode.PHASE_SHAPE:
            output["profile"] = None
        elif code == DiagnosticValidationCode.BRAND_CHANGED:
            profile["brandName"] = "另一家咖啡"
        elif code == DiagnosticValidationCode.REGION_CHANGED:
            profile["region"] = "深圳"
        elif code == DiagnosticValidationCode.PRIMARY_TERM:
            profile["brandTerms"] = ["星咖"]
        elif code == DiagnosticValidationCode.BRAND_PROOF:
            profile["evidence"] = [{"sourceId": "retained-source", "quote": _COMPETITOR_QUOTE}]
        else:
            profile["website"] = "https://unbacked.example.com/"
    elif code.value.startswith("diag_q_"):
        phase = (
            "REPUTATION_QUESTIONS"
            if code == DiagnosticValidationCode.QUESTION_NO_OWN
            else "DISCOVERY_QUESTIONS"
        )
        raw["phase"] = phase
        slots = 3 if phase == "REPUTATION_QUESTIONS" else 5
        questions = _questions(phase, slots)
        output["competitors"] = []
        output["questions"] = questions
        if code == DiagnosticValidationCode.QUESTION_COUNT:
            questions.pop()
        elif code == DiagnosticValidationCode.QUESTION_RETAINED:
            raw["input"]["discoveryQuestions"] = [
                {
                    "id": "retained-question",
                    "text": questions[0]["text"],
                    "origin": "USER",
                    "edited": False,
                }
            ]
            slots = 4
            questions.pop()
        elif code == DiagnosticValidationCode.QUESTION_DUPLICATE:
            questions[1] = dict(questions[0])
        elif code == DiagnosticValidationCode.QUESTION_OWN_BRAND:
            questions[0] = {"text": "广州的小行星咖啡适合哪些人？"}
        elif code == DiagnosticValidationCode.QUESTION_COMP_BRAND:
            questions[0] = {"text": "广州的星环咖啡适合哪些人？"}
        else:
            questions[0] = {"text": "广州有哪些咖啡店值得核实？"}
    elif code == DiagnosticValidationCode.SOURCE_MISSING:
        candidates[0]["evidence"][0]["sourceId"] = "PRIVATE_SOURCE_CANARY"
    elif code == DiagnosticValidationCode.QUOTE_ABSENT:
        candidates[0]["evidence"][0]["quote"] = "PRIVATE_QUOTE_CANARY"
    elif code == DiagnosticValidationCode.ALIAS_PROOF:
        candidates[0]["brandTerms"].append("PRIVATE_ALIAS_CANARY")
    elif code == DiagnosticValidationCode.SLOTS_EXCESS:
        slots = 1
        candidates.append(dict(candidates[0]))
    elif code == DiagnosticValidationCode.COMPETITOR_DUPLICATE:
        candidates.append(dict(candidates[0]))
    elif code == DiagnosticValidationCode.SELF_COMPETITOR:
        candidates[0] = _candidate(_BRAND, "brand-source", _BRAND_QUOTE)
    elif code == DiagnosticValidationCode.NAME_PROOF:
        candidates[0]["name"] = "PRIVATE_NAME_CANARY"
        candidates[0]["brandTerms"] = ["PRIVATE_NAME_CANARY"]
    else:
        assert code == DiagnosticValidationCode.CITATION_SET
        candidates[0]["sourceIds"] = ["brand-source"]
    payload = DiagnosticPreparationRequest.model_validate(raw)
    model = _result_schema(payload, payload.input.sources, slots)
    with pytest.raises(ValidationError) as error:
        model.model_validate(output)
    schema = model.model_json_schema(by_alias=True)
    structural = {
        DiagnosticValidationCode.PHASE_SHAPE: ("profile", "model_type"),
        DiagnosticValidationCode.SLOTS_EXCESS: ("competitors", "too_long"),
        DiagnosticValidationCode.QUESTION_COUNT: ("questions", "too_short"),
    }
    expected_path, expected_type = structural.get(code, ("$", code.value))
    assert (
        error.value.errors(include_input=False, include_context=False)[0]["type"] == expected_type
    )
    safe = DeepSeekAdapter._safe_validation_errors(  # pyright: ignore[reportPrivateUsage]
        error.value, schema, diagnostic=True
    )
    generic = DeepSeekAdapter._safe_validation_errors(  # pyright: ignore[reportPrivateUsage]
        error.value, schema
    )
    assert safe == [{"path": expected_path, "type": expected_type}]
    assert generic == (safe if code in structural else [{"path": "$", "type": "invalid"}])
    instruction = diagnostic_structured_repair_instruction(
        _REPAIR_INSTRUCTION, "invalid_schema", safe
    )
    assert _diagnostic_feedback(instruction)["validationErrors"] == safe
    assert "PRIVATE_" not in instruction
    assert code.value.isascii() and len(code.value) <= 18
    assert code.value in DIAGNOSTIC_SAFE_VALIDATION_TYPES
    assert code.value not in STRUCTURED_SAFE_VALIDATION_TYPES


@pytest.mark.parametrize(
    ("failure", "expected_code"),
    [
        ("duplicate", "diag_comp_dup"),
        ("own_brand", "diag_self_comp"),
        ("over_limit", "too_long"),
        ("source", "diag_src_missing"),
        ("quote", "diag_quote_absent"),
        ("name", "diag_name_proof"),
        ("alias", "diag_alias_proof"),
        ("citations", "diag_cite_set"),
    ],
)
@pytest.mark.parametrize("recover", [True, False])
def test_native_grounding_repair_uses_stable_diagnostic_reason_without_retained_bypass(
    failure: str, expected_code: str, recover: bool, caplog: pytest.LogCaptureFixture
) -> None:
    raw = _retained_competitor_payload()
    frozen = json.dumps(raw, ensure_ascii=False, sort_keys=True)
    requests: list[dict[str, Any]] = []
    canary = "PRIVATE_SEMANTIC_VALIDATION_CANARY"

    def handler(request: httpx.Request) -> httpx.Response:
        data = cast(dict[str, Any], json.loads(request.content))
        requests.append(data)
        projected = _projected_request(
            data["messages"][0]["content"], data["messages"][1]["content"]
        )
        assert projected["candidateSlots"] == 4
        candidates = [
            _candidate("星环咖啡", "retained-source", _COMPETITOR_QUOTE),
            _candidate("月光咖啡", "new-source", raw["input"]["sources"][1]["snippet"]),
        ]
        if len(requests) == 1 or not recover:
            if failure == "duplicate":
                candidates[1] = dict(candidates[0])
            elif failure == "own_brand":
                source = next(item for item in projected["sources"] if item["url"] == _URL)
                candidates[0] = _candidate(_BRAND, source["id"], _BRAND_QUOTE)
            elif failure == "over_limit":
                candidates = [dict(candidates[0]) for _ in range(5)]
            elif failure == "source":
                candidates[0]["evidence"][0]["sourceId"] = canary
            elif failure == "quote":
                candidates[0]["evidence"][0]["quote"] = canary
            elif failure == "name":
                candidates[0]["evidence"] = candidates[1]["evidence"]
                candidates[0]["sourceIds"] = ["new-source"]
            elif failure == "alias":
                candidates[0]["brandTerms"].append(canary)
            else:
                candidates[0]["sourceIds"] = ["new-source"]
        output = {"profile": None, "competitors": candidates, "questions": [], "warnings": []}
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "tool_calls",
                        "message": {
                            "tool_calls": [
                                {
                                    "type": "function",
                                    "function": {
                                        "name": "prepare_geo_diagnostic",
                                        "arguments": json.dumps(output, ensure_ascii=False),
                                    },
                                }
                            ]
                        },
                    }
                ],
                "usage": {"prompt_tokens": 20, "completion_tokens": 10},
            },
        )

    model_http = httpx.AsyncClient(
        base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
    )
    search_http = httpx.AsyncClient(transport=httpx.MockTransport(_search_response))
    gateway = DeepSeekAdapter(
        ProviderSettings(credential=SecretStr("test-only"), model="deepseek-test", max_retries=0),
        client=model_http,
    )
    app = create_app(internal_settings(), internal_nonce_store=FakeInternalNonceStore())
    body = json.dumps(raw, ensure_ascii=False).encode()
    with TestClient(app) as client:
        app.state.geo_visual_gateway = gateway
        app.state.geo_diagnostic_search = _search(search_http)
        response = client.post(
            _PATH,
            content=body,
            headers={
                **signed_headers("POST", _PATH, body=body),
                "Content-Type": "application/json",
            },
        )
    asyncio.run(model_http.aclose())
    asyncio.run(search_http.aclose())
    assert json.dumps(raw, ensure_ascii=False, sort_keys=True) == frozen
    assert len(requests) == 2
    assert requests[0]["messages"][1] == requests[1]["messages"][1]
    assert requests[0]["tools"] == requests[1]["tools"]
    assert _diagnostic_feedback(requests[1]["messages"][0]["content"]) == {
        "failureStage": "invalid_schema",
        "validationErrors": [
            {"path": "competitors" if failure == "over_limit" else "$", "type": expected_code}
        ],
    }
    assert all(_http_request_estimate(item) <= 16000 for item in requests)
    assert canary not in requests[1]["messages"][0]["content"]
    assert canary not in response.text
    assert canary not in json.dumps([record.__dict__ for record in caplog.records], default=str)
    if recover:
        assert response.status_code == 200, response.text
        result = response.json()["result"]
        assert [item["name"] for item in result["competitors"]] == ["月光咖啡"]
        assert any("未重复添加" in warning for warning in result["warnings"])
        assert any("可靠新增竞品不足" in warning for warning in result["warnings"])
        returned = {item["id"]: item for item in result["sources"]}
        assert all(returned[item["id"]] == item for item in raw["input"]["sources"])
        assert response.json()["synthesisUsage"]["providerCallCount"] == 2
    else:
        assert response.status_code == 503
        assert response.json()["detail"]["code"] == "GEO_DIAGNOSTIC_RESPONSE_INVALID"
