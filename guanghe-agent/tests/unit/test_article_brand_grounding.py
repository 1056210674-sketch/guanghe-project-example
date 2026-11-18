"""Brand grounding through the real ARTICLE kernel, gateway and bounded repair path."""

from __future__ import annotations

import asyncio
import json
from datetime import UTC, datetime
from typing import Any

import httpx
import pytest

from app.article.models import ArticleStage, article_brand_grounding
from app.article.runtime import ArticleExecutionError, ArticleExecutionKernel
from app.common.settings import default_bundle_path
from app.harness.runtime import estimate_model_messages
from app.model_gateway.deepseek import DeepSeekAdapter
from app.model_gateway.gateway import ModelMessage
from app.prompts.assembly import CommercePromptSet
from app.prompts.loader import load_bundle
from app.providers.knowledge_context import (
    KnowledgeContext,
    ProjectMemoryContext,
    ProjectMemoryEntry,
)
from tests.unit.test_article_execution import (
    _budget,  # pyright: ignore[reportPrivateUsage]
    _deepseek_settings,  # pyright: ignore[reportPrivateUsage]
    _plan,  # pyright: ignore[reportPrivateUsage]
    _prompts,  # pyright: ignore[reportPrivateUsage]
    _request,  # pyright: ignore[reportPrivateUsage]
)
from tests.unit.test_article_schema_repair import _response  # pyright: ignore[reportPrivateUsage]

_NAME = "星河测试科技有限公司"
_ALIAS = "星河测试"
_BRAND = json.dumps({"name": _NAME, "aliases": [_ALIAS]}, ensure_ascii=False)


def _memory(key: str, value: str | None, **changes: Any) -> ProjectMemoryEntry:
    return ProjectMemoryEntry.model_validate(
        {
            "id": "21",
            "scope": "PROJECT",
            "projectId": "11",
            "kind": "FACT",
            "key": key,
            "label": "合成品牌资料",
            "value": value,
            "version": 1,
            "deleted": value is None,
            "sourceType": "USER_INPUT",
            "sourceMessageId": None,
            "sourceConversationId": None,
            "updatedAt": datetime.now(UTC),
            **changes,
        }
    )


def _knowledge() -> KnowledgeContext:
    return KnowledgeContext(
        documents=(),
        preferences=(),
        business_history=None,
        truncated=False,
        source_count=0,
        returned_characters=0,
        memory=ProjectMemoryContext(
            schema_version=1,
            project_id="11",
            revision="revision-1",
            history_cutoff_at=None,
            entries=(
                _memory("brand.categories", "GEO搜索优化和智能体定制开发"),
                _memory("brand.service_area", "全国"),
                _memory("geo.target_regions", "广州"),
                _memory("geo.priority_offering", "GEO搜索优化", kind="PREFERENCE"),
                _memory("brand.address", None),
                _memory(
                    "brand.categories",
                    "其他客户的偏好",
                    scope="ACCOUNT",
                    projectId=None,
                    kind="PREFERENCE",
                ),
            ),
        ),
    )


def _payload(mode: ArticleStage, *, branded: bool) -> dict[str, Any]:
    if mode is ArticleStage.OUTLINE:
        plan = _plan().model_dump(mode="json", by_alias=True)
        if branded:
            plan["sections"][0]["brief"] = f"自然介绍{_ALIAS}的GEO搜索优化和定制开发业务。"
        return {"plan": plan, "warnings": [_NAME]}
    paragraphs = [
        f"{_ALIAS}提供GEO搜索优化与智能体定制开发，面向广州有相关需求的企业。"
        if branded
        else "企业可以先了解搜索优化与智能体定制开发的用途。",
        "实际服务范围为全国；推广地域并不意味着在当地有门店。",
    ]
    blocks = [
        {"key": f"section-{index + 1}", "heading": heading, "markdown": text}
        for index, (heading, text) in enumerate(
            zip(("选择标准", "使用建议"), paragraphs, strict=True)
        )
    ]
    if mode is ArticleStage.REWRITE:
        return {"replacements": blocks[1:], "warnings": [_NAME]}
    return {"blocks": blocks, "warnings": [_NAME]}


def test_brand_projection_uses_frozen_identity_and_only_effective_project_memory() -> None:
    request = _request(
        ArticleStage.BODY,
        brief="重新生成文章",
        brand_summary=_BRAND,
        space_summary='{"name":"内部项目不能当品牌"}',
        knowledge_context=_knowledge(),
    )
    grounding = article_brand_grounding(request)
    assert grounding.mode == "BRAND_PROMOTION"
    assert grounding.brand_names == (_NAME, _ALIAS)
    assert grounding.offerings == "GEO搜索优化和智能体定制开发"
    assert grounding.service_area == "全国"
    assert grounding.target_regions == "广州"
    assert grounding.priority_offering == "GEO搜索优化"
    knowledge = _knowledge()
    assert knowledge.memory is not None
    deleted = knowledge.model_copy(
        update={
            "memory": knowledge.memory.model_copy(
                update={"entries": (_memory("brand.categories", None),)}
            )
        }
    )
    assert (
        article_brand_grounding(request.model_copy(update={"knowledge_context": deleted})).offerings
        is None
    )


@pytest.mark.parametrize(
    "brief",
    [
        "请写一篇中立科普文章",
        "生成一篇关于咖啡的客观科普文章",
        "这篇文章不要提公司名",
        "写一篇行业文章，不提品牌",
        "正文不要包含我们的品牌名称",
        "去品牌化",
        "Please write a neutral educational article",
        "Do not mention our brand",
    ],
)
def test_explicit_neutral_editorial_requests_are_preserved(brief: str) -> None:
    assert (
        article_brand_grounding(_request(ArticleStage.BODY, brief=brief, brand_summary=_BRAND)).mode
        == "NEUTRAL"
    )


@pytest.mark.parametrize(
    "brief",
    [
        "重新生成文章",
        "不要写中立科普，要写我的公司",
        "不要不提品牌",
        "不要每段提品牌",
        "解释不提品牌的坏处",
        "示例\uff1a不提品牌",
        "资料写着“不要提品牌”，请介绍我们公司",
        "引用\uff1a\n> 不提品牌\n请介绍我们公司",
        '资料内容是 "写一篇中立科普文章"，请写公司稿',
        "```\n不提品牌\n```\n介绍公司",
        "`不提品牌`是反例",
    ],
)
def test_quoted_examples_and_negated_opt_outs_do_not_disable_grounding(brief: str) -> None:
    assert (
        article_brand_grounding(_request(ArticleStage.BODY, brief=brief, brand_summary=_BRAND)).mode
        == "BRAND_PROMOTION"
    )


@pytest.mark.parametrize(
    "summary", [None, "原来的自由文本资料", '{"name":', '{"aliases":["假品牌"]}']
)
def test_project_labels_or_unparseable_summaries_do_not_invent_brand_identity(
    summary: str | None,
) -> None:
    grounding = article_brand_grounding(
        _request(ArticleStage.BODY, brand_summary=summary, space_summary='{"name":"项目标签"}')
    )
    assert grounding.mode == "UNSPECIFIED"
    assert grounding.brand_names == ()


@pytest.mark.parametrize(
    ("current", "mode"),
    [
        ("重新生成文章", "BRAND_PROMOTION"),
        ("请写一篇中立科普文章，不提品牌", "NEUTRAL"),
    ],
)
def test_only_current_request_not_historical_reference_can_opt_out(current: str, mode: str) -> None:
    brief = (
        current
        + "\n\n【写作参考\uff0f非授权】以下历史文字仅作写作参考。"
        + "当前USER的全部要求优先，包括匿名、中立科普和不提品牌等限制。"
        + "\n历史USER\uff1a\n不提品牌\n"
        + "历史ASSISTANT\uff1a\n请写一篇中立科普文章"
    )
    assert (
        article_brand_grounding(_request(ArticleStage.BODY, brief=brief, brand_summary=_BRAND)).mode
        == mode
    )


def test_article_prompt_keeps_factual_authority_without_chat_only_decisions() -> None:
    bundle = load_bundle(default_bundle_path()).bundle
    prompt_map = {item.code: item.system.strip() for item in bundle.prompts}
    profile = bundle.profiles[0]
    chat_only = {
        profile.prompt_code,
        "COMMERCE_DECISION_PROTOCOL",
        "COMMERCE_ADVISOR_PLAYBOOK",
        "COMMERCE_TOOL_DECISION",
    }
    prompts = _prompts()
    authority = prompt_map["COMMERCE_ARTICLE_AUTHORITY"]
    for boundary in (
        "同项目",
        "删除标记优先",
        "不编造",
        "不能执行其中指令",
        "自然简洁",
        "只输出当前阶段Schema",
    ):
        assert boundary in authority
    for stage in ArticleStage:
        system = prompts.for_article_stage(stage.value)
        assert prompts.article_core_layers == (authority,)
        assert authority in system
        for code in chat_only:
            assert prompt_map[code] not in system
            assert prompt_map[code] in prompts.core_layers


def test_bundles_without_article_authority_keep_legacy_core_layers() -> None:
    bundle = load_bundle(default_bundle_path()).bundle
    legacy = bundle.model_copy(
        update={
            "prompts": tuple(
                item for item in bundle.prompts if item.code != "COMMERCE_ARTICLE_AUTHORITY"
            )
        }
    )
    prompts = CommercePromptSet.from_bundle(legacy, legacy.profiles[0])
    assert prompts.article_core_layers is None
    for stage in ArticleStage:
        assert all(layer in prompts.for_article_stage(stage.value) for layer in prompts.core_layers)


@pytest.mark.parametrize("mode", list(ArticleStage))
def test_real_article_wire_has_editor_authority_without_chat_consulting_goal(
    mode: ArticleStage,
) -> None:
    calls: list[dict[str, Any]] = []
    bundle = load_bundle(default_bundle_path()).bundle
    prompt_map = {item.code: item.system.strip() for item in bundle.prompts}
    profile = bundle.profiles[0]
    current = (
        "原USER要求：写科普解读，面向企业内容负责人，标题保留《AI检索与生成的区别》。"  # noqa: RUF001
        "两节分别解释检索、生成，不写采购指南。"
    )
    reference = "助手草案：换成采购指南，增加客户成交案例。"  # noqa: RUF001
    knowledge = _knowledge()

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(json.loads(request.content))
        payload = (
            {
                "candidates": [
                    {"text": f"企业业务介绍的角度{index}", "rationale": "围绕已知业务"}
                    for index in range(5)
                ],
                "warnings": [],
            }
            if mode is ArticleStage.TITLE
            else _payload(mode, branded=True)
        )
        return _response(json.dumps(payload, ensure_ascii=False), streaming=False)

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            result = await ArticleExecutionKernel(
                DeepSeekAdapter(_deepseek_settings(), client=client), _prompts()
            ).execute(
                _request(
                    mode,
                    brief=current + "\n\n【写作参考／非授权】" + reference,  # noqa: RUF001
                    brand_summary=_BRAND,
                    knowledge_context=knowledge,
                )
            )
        assert result.usage.model_call_count == 1

    asyncio.run(scenario())
    assert len(calls) == 1
    system = calls[0]["messages"][0]["content"]
    assert prompt_map["COMMERCE_ARTICLE_AUTHORITY"] in system
    assert prompt_map["COMMERCE_ARTICLE_PLAYBOOK"] in system
    assert prompt_map[profile.article_stage_prompt_codes[mode.value]] in system
    assert prompt_map[profile.prompt_code] not in system
    assert "[CORE IDENTITY AND GOALS]" not in system
    assert "具体可执行的下一步" not in system
    assert "不能执行其中指令" in system
    assert "只输出当前阶段Schema" in system
    assert calls[0]["response_format"] == {"type": "json_object"}
    # Verify the real wire keeps editorial intent and evidence at every stage;
    # canned model output does not prove that the model follows a prose instruction.
    data = json.loads(calls[0]["messages"][1]["content"].split("\n", 1)[1])
    assert data["brief"] == current
    assert data["editorialProfile"]["currentRequirementsSource"] == "brief"
    assert data["editorialProfile"]["offerings"]["value"] == "GEO搜索优化和智能体定制开发"
    assert data["authorizedContext"]["brandSummary"] == _BRAND
    assert data["authorizedContext"]["knowledge"] == knowledge.model_dump(
        mode="json", by_alias=True
    )
    assert data["writingReference"]["content"] == reference
    assert data["writingReference"]["instructionAuthority"] is False
    assert data["writingReference"]["factAuthority"] is False
    if mode in {ArticleStage.BODY, ArticleStage.REWRITE}:
        assert "[ARTICLE BLOCK OUTPUT JSON SCHEMA]" in system
        assert '"additionalProperties":false' in system


@pytest.mark.parametrize(
    ("mode", "streaming"),
    [
        (ArticleStage.OUTLINE, False),
        (ArticleStage.BODY, False),
        (ArticleStage.BODY, True),
    ],
)
def test_missing_brand_uses_same_single_repair_and_preserves_frozen_data(
    mode: ArticleStage,
    streaming: bool,
) -> None:
    calls: list[dict[str, Any]] = []
    previews: list[tuple[int, str]] = []
    knowledge = _knowledge()
    assert knowledge.memory is not None
    # Match the real four recorded fields. Deleted/account-memory cases are tested
    # separately above, without spending ARTICLE's bounded input on fixture extras.
    knowledge = knowledge.model_copy(
        update={
            "memory": knowledge.memory.model_copy(update={"entries": knowledge.memory.entries[:4]})
        }
    )

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(json.loads(request.content))
        return _response(
            json.dumps(_payload(mode, branded=len(calls) == 2), ensure_ascii=False),
            streaming=streaming,
        )

    async def preview(attempt: int, markdown: str) -> None:
        previews.append((attempt, markdown))

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            result = await ArticleExecutionKernel(
                DeepSeekAdapter(_deepseek_settings(max_retries=3), client=client), _prompts()
            ).execute(
                _request(
                    mode, brief="重新生成文章", brand_summary=_BRAND, knowledge_context=knowledge
                ),
                on_preview=preview if streaming else None,
            )
            assert result.usage.model_call_count == 2
            assert result.usage.input_tokens == 22 and result.usage.output_tokens == 6
            if mode is ArticleStage.BODY:
                assert result.body and _ALIAS in result.body.markdown
                if streaming:
                    assert (2, "") in previews
                    assert previews[-1] == (2, result.body.markdown)
            else:
                assert result.plan and _ALIAS in result.plan.sections[0].brief

    asyncio.run(scenario())
    assert len(calls) == 2
    assert calls[1]["messages"][1:] == calls[0]["messages"][1:]
    assert "brandGrounding.mode=BRAND_PROMOTION" in calls[1]["messages"][0]["content"]
    data = json.loads(calls[0]["messages"][1]["content"].split("\n", 1)[1])
    assert data["brief"] == "重新生成文章"  # Original evidence is not rewritten.
    assert data["brandGrounding"]["brandNames"] == [_NAME, _ALIAS]
    assert data["brandGrounding"]["serviceArea"] == "全国"
    assert data["brandGrounding"]["targetRegions"] == "广州"


@pytest.mark.parametrize("max_repairs", [0, 1])
def test_brand_only_in_warning_cannot_pass_or_create_unbounded_calls(max_repairs: int) -> None:
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return _response(json.dumps(_payload(ArticleStage.BODY, branded=False)), streaming=False)

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            with pytest.raises(ArticleExecutionError) as captured:
                await ArticleExecutionKernel(
                    DeepSeekAdapter(_deepseek_settings(max_retries=3), client=client), _prompts()
                ).execute(
                    _request(
                        ArticleStage.BODY,
                        brand_summary=_BRAND,
                        budget=_budget(
                            max_model_calls=max_repairs + 1, max_repair_retries=max_repairs
                        ),
                    )
                )
            assert captured.value.code == "DEEPSEEK_ARTICLE_BODY_RESPONSE_INVALID"
            assert captured.value.usage.request_count == max_repairs + 1

    asyncio.run(scenario())
    assert len(calls) == max_repairs + 1


@pytest.mark.parametrize(
    ("mode", "brief"),
    [
        (ArticleStage.BODY, "写一篇中立科普文章，不提品牌"),
        (ArticleStage.REWRITE, "介绍我们的公司"),
    ],
)
def test_neutral_body_and_limited_rewrite_do_not_force_brand_outside_authorized_scope(
    mode: ArticleStage,
    brief: str,
) -> None:
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return _response(json.dumps(_payload(mode, branded=False)), streaming=False)

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            result = await ArticleExecutionKernel(
                DeepSeekAdapter(_deepseek_settings(), client=client), _prompts()
            ).execute(_request(mode, brief=brief, brand_summary=_BRAND))
            assert (
                result.body
                and _NAME not in result.body.markdown
                and _ALIAS not in result.body.markdown
            )
            if mode is ArticleStage.REWRITE:
                assert result.body.blocks[0].markdown == "先明确使用场景。"

    asyncio.run(scenario())
    assert len(calls) == 1


@pytest.mark.parametrize(
    "hidden", [f"<!-- {_NAME} -->", f"```\n{_NAME}\n```", f"[company]: {_NAME}", f"## {_NAME}"]
)
def test_hidden_comments_references_and_code_do_not_count_as_company_prose(hidden: str) -> None:
    grounding = article_brand_grounding(_request(ArticleStage.BODY, brand_summary=_BRAND))
    assert not grounding.contains_brand("行业背景。\n" + hidden)
    assert grounding.contains_brand(f"**{_ALIAS}**提供已确认的业务。")


def test_title_and_headings_cannot_substitute_for_company_in_body() -> None:
    calls: list[httpx.Request] = []
    original_plan = _plan()
    sections = (
        original_plan.sections[0].model_copy(update={"heading": _NAME}),
        *original_plan.sections[1:],
    )
    plan = original_plan.model_copy(update={"sections": sections})

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        payload = _payload(ArticleStage.BODY, branded=False)
        payload["blocks"][0]["heading"] = _NAME
        return _response(json.dumps(payload), streaming=False)

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            with pytest.raises(
                ArticleExecutionError, match="DEEPSEEK_ARTICLE_BODY_RESPONSE_INVALID"
            ):
                await ArticleExecutionKernel(
                    DeepSeekAdapter(_deepseek_settings(), client=client), _prompts()
                ).execute(
                    _request(
                        ArticleStage.BODY,
                        brand_summary=_BRAND,
                        selected_title=_NAME,
                        confirmed_plan=plan,
                        confirmed_outline=sections,
                    )
                )

    asyncio.run(scenario())
    assert len(calls) == 2


def test_brand_repair_cannot_spend_beyond_the_original_input_budget() -> None:
    calls: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(json.loads(request.content))
        return _response(json.dumps(_payload(ArticleStage.BODY, branded=False)), streaming=False)

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            kernel = ArticleExecutionKernel(
                DeepSeekAdapter(_deepseek_settings(), client=client), _prompts()
            )
            with pytest.raises(
                ArticleExecutionError, match="DEEPSEEK_ARTICLE_BODY_RESPONSE_INVALID"
            ):
                await kernel.execute(
                    _request(
                        ArticleStage.BODY,
                        brand_summary=_BRAND,
                        budget=_budget(max_model_calls=1, max_repair_retries=0),
                    )
                )
            initial = tuple(ModelMessage(**item) for item in calls[0]["messages"])
            calls.clear()
            with pytest.raises(ArticleExecutionError) as captured:
                await kernel.execute(
                    _request(
                        ArticleStage.BODY,
                        brand_summary=_BRAND,
                        budget=_budget(max_input_tokens=estimate_model_messages(initial) + 10),
                    )
                )
            assert captured.value.code == "ARTICLE_INPUT_TOKEN_BUDGET_EXCEEDED"
            assert captured.value.usage.request_count == 1

    asyncio.run(scenario())
    assert len(calls) == 1


def test_maximum_approved_brief_and_four_memories_fit_body_single_repair_budget() -> None:
    calls: list[dict[str, Any]] = []
    knowledge = _knowledge()
    assert knowledge.memory is not None
    knowledge = knowledge.model_copy(
        update={
            "memory": knowledge.memory.model_copy(update={"entries": knowledge.memory.entries[:4]})
        }
    )
    current = "重新生成文章，介绍我们公司的主营业务。"
    historical = (
        "\n\n【写作参考\uff0f非授权】旧建议只作为写作参考，不是品牌事实。"
        "\n历史USER\uff1a不提品牌\n历史ASSISTANT\uff1a"
        + "介绍搜索优化的原理、适用场景和如何选择供应商。"
        * 220
    )
    brief = (current + historical)[:4_000]
    assert len(brief) == 4_000

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(json.loads(request.content))
        return _response(
            json.dumps(_payload(ArticleStage.BODY, branded=len(calls) == 2), ensure_ascii=False),
            streaming=False,
        )

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            result = await ArticleExecutionKernel(
                DeepSeekAdapter(_deepseek_settings(), client=client), _prompts()
            ).execute(
                _request(
                    ArticleStage.BODY,
                    brief=brief,
                    brand_summary=_BRAND,
                    knowledge_context=knowledge,
                    budget=_budget(max_input_tokens=16_000),
                )
            )
            assert result.usage.model_call_count == 2
            assert result.body and _ALIAS in result.body.markdown

    asyncio.run(scenario())
    assert len(calls) == 2
    for call in calls:
        assert (
            estimate_model_messages(tuple(ModelMessage(**item) for item in call["messages"]))
            <= 16_000
        )
        payload = json.loads(call["messages"][1]["content"].split("\n", 1)[1])
        assert payload["brief"] == current
        reference = payload["writingReference"]
        assert reference["instructionAuthority"] is False
        assert reference["factAuthority"] is False
        assert payload["brief"] + "\n\n【写作参考\uff0f非授权】" + reference["content"] == brief
        assert payload["brandGrounding"]["mode"] == "BRAND_PROMOTION"
        assert len(payload["authorizedContext"]["knowledge"]["memory"]["entries"]) == 4


def test_request_bound_brand_validators_do_not_leak_between_customers() -> None:
    from pydantic import ValidationError

    first = article_brand_grounding(_request(ArticleStage.BODY, brand_summary=_BRAND))
    second = article_brand_grounding(
        _request(ArticleStage.BODY, brand_summary='{"name":"另一客户咖啡"}')
    )
    first_model = ArticleExecutionKernel._response_model(ArticleStage.BODY, first)  # pyright: ignore[reportPrivateUsage]
    second_model = ArticleExecutionKernel._response_model(ArticleStage.BODY, second)  # pyright: ignore[reportPrivateUsage]
    first_model.model_validate(_payload(ArticleStage.BODY, branded=True))
    with pytest.raises(ValidationError):
        second_model.model_validate(_payload(ArticleStage.BODY, branded=True))
    # Building the second request did not mutate the first request's validator.
    first_model.model_validate(_payload(ArticleStage.BODY, branded=True))
