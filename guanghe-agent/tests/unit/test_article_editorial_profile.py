"""Frozen editorial inputs through the existing bounded article kernel."""

from __future__ import annotations

import asyncio
import json
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import pytest

from app.article.models import ArticleStage, article_editorial_profile
from app.article.runtime import ArticleExecutionError, ArticleExecutionKernel
from app.harness.runtime import estimate_model_messages
from app.model_gateway.deepseek import DeepSeekAdapter
from app.providers.knowledge_context import ProjectMemoryContext
from tests.unit.test_article_brand_grounding import (
    _knowledge,  # pyright: ignore[reportPrivateUsage]
    _memory,  # pyright: ignore[reportPrivateUsage]
    _payload,  # pyright: ignore[reportPrivateUsage]
)
from tests.unit.test_article_execution import (
    _deepseek_settings,  # pyright: ignore[reportPrivateUsage]
    _prompts,  # pyright: ignore[reportPrivateUsage]
    _request,  # pyright: ignore[reportPrivateUsage]
)
from tests.unit.test_article_schema_repair import _response  # pyright: ignore[reportPrivateUsage]

_BRAND = {
    "name": "星河测试科技有限公司",
    "aliases": ["星河测试"],
    "description": "提供企业服务",
    "audience": ["中小电商"],
    "sellingPoints": ["按需定制", "远程交付"],
    "tone": ["自然清晰"],
    "prohibitedClaims": ["不承诺排名"],
}


def _profile_request(mode: ArticleStage = ArticleStage.BODY, **changes: Any) -> Any:
    return _request(mode, brand_summary=json.dumps(_BRAND, ensure_ascii=False), **changes)


def test_frozen_profile_maps_business_materials_without_inventing_missing_fields() -> None:
    request = _profile_request(
        knowledge_context=_knowledge(),
        product_summary=json.dumps({"name": "企业咨询服务", "category": "咨询"}),
    )
    profile = article_editorial_profile(request)
    assert profile.purpose_default == "MEDIA_BRAND_ARTICLE"
    assert profile.brand_names == ("星河测试科技有限公司", "星河测试")
    assert profile.offerings is not None and profile.offerings.source == "PROJECT_MEMORY"
    assert profile.selling_points is not None
    assert profile.selling_points.value == "按需定制\n远程交付"
    assert profile.selling_points.source == "BRAND_SNAPSHOT"
    assert profile.target_audience is not None and profile.target_audience.value == "中小电商"
    assert profile.service_area is not None and profile.service_area.value == "全国"
    assert profile.target_regions is not None and profile.target_regions.value == "广州"
    assert profile.product_facts["name"].source == "PRODUCT_SNAPSHOT"
    assert profile.industry is None and "industry" in profile.unknown_fields
    assert not profile.omitted_fields
    # A target region cannot be silently converted into an office or service address.
    assert "办公室" not in profile.model_dump_json()


@pytest.mark.parametrize("deleted", [False, True])
def test_project_correction_or_tombstone_supersedes_snapshot_and_synonym(deleted: bool) -> None:
    old = datetime.now(UTC) - timedelta(days=1)
    knowledge = _knowledge().model_copy(
        update={
            "memory": ProjectMemoryContext(
                schema_version=1,
                project_id="11",
                revision="2",
                history_cutoff_at=None,
                entries=(
                    _memory("brand.audience", "旧目标顾客", updatedAt=old),
                    _memory("brand.target_audience", None if deleted else "企业IT负责人"),
                    _memory("brand.selling_points", None if deleted else "模块可独立配置"),
                    _memory("product.selling_points", None if deleted else "支持受控集成"),
                ),
            )
        }
    )
    profile = article_editorial_profile(
        _profile_request(
            knowledge_context=knowledge,
            product_summary=json.dumps({"sellingPoints": ["旧商品卖点"]}),
        )
    )
    if deleted:
        assert profile.target_audience is profile.selling_points is None
        assert "sellingPoints" not in profile.product_facts
        assert "brand.target_audience" in profile.blocked_memory_keys
        assert "旧目标顾客" not in profile.model_dump_json()
    else:
        assert profile.target_audience is not None
        assert profile.target_audience.value == "企业IT负责人"
        assert profile.selling_points is not None
        assert profile.selling_points.value == "模块可独立配置"
        assert "sellingPoints" not in profile.product_facts


def test_account_style_only_and_project_tombstone_blocks_fallback() -> None:
    def project(deleted_style: bool) -> Any:
        entries = [
            _memory(
                "brand.selling_points",
                "另一客户卖点",
                scope="ACCOUNT",
                projectId=None,
                kind="PREFERENCE",
            ),
            _memory("style.tone", "简短口语", scope="ACCOUNT", projectId=None, kind="PREFERENCE"),
        ]
        if deleted_style:
            entries.append(_memory("style.tone", None, kind="PREFERENCE"))
        return _knowledge().model_copy(
            update={
                "memory": ProjectMemoryContext(
                    schema_version=1,
                    project_id="11",
                    revision="3",
                    history_cutoff_at=None,
                    entries=tuple(entries),
                )
            }
        )

    brand = json.dumps({"name": "星河测试", "tone": []})
    request = _request(ArticleStage.BODY, brand_summary=brand, knowledge_context=project(False))
    profile = article_editorial_profile(request)
    assert profile.selling_points is None
    assert profile.brand_voice is not None and profile.brand_voice.source == "ACCOUNT_PREFERENCE"
    assert profile.brand_voice.value == "简短口语"
    deleted = article_editorial_profile(
        request.model_copy(update={"knowledge_context": project(True)})
    )
    assert deleted.brand_voice is None


@pytest.mark.parametrize("selected_product", [False, True])
def test_unbound_product_memory_never_creates_or_overwrites_selected_product(
    selected_product: bool,
) -> None:
    knowledge = _knowledge().model_copy(
        update={
            "memory": ProjectMemoryContext(
                schema_version=1,
                project_id="11",
                revision="5",
                history_cutoff_at=None,
                entries=(
                    _memory("product.name", "项目里另一个商品"),
                    _memory("product.selling_points", "另一个商品的独有卖点"),
                    _memory("product.audience", None),
                ),
            )
        }
    )
    request = _profile_request(
        knowledge_context=knowledge,
        product_summary=(
            json.dumps(
                {
                    "name": "本轮选定服务",
                    "sellingPoints": ["旧卖点"],
                    "audience": ["旧受众"],
                    "category": "企业咨询",
                },
                ensure_ascii=False,
            )
            if selected_product
            else None
        ),
    )
    profile = article_editorial_profile(request)
    assert "另一个商品" not in profile.model_dump_json()
    assert "旧卖点" not in profile.model_dump_json()
    assert "旧受众" not in profile.model_dump_json()
    if selected_product:
        assert set(profile.product_facts) == {"category"}
        assert profile.product_facts["category"].value == "企业咨询"
        assert profile.product_facts["category"].source == "PRODUCT_SNAPSHOT"
    else:
        assert profile.product_facts == {}


def test_history_project_label_and_free_text_summary_do_not_become_structured_facts() -> None:
    request = _request(
        ArticleStage.BODY,
        brief="按这个方向写完整稿\n【写作参考\uff0f非授权】旧助手\uff1a我们的行业第一和千万销量",
        space_summary='{"name":"客户文件夹"}',
        brand_summary="旧材料里出现的名称不能被代码猜为公开品牌",
    )
    profile = article_editorial_profile(request)
    assert profile.purpose_default == "UNSPECIFIED"
    assert profile.brand_names == ()
    assert profile.current_requirements_source == "brief"
    assert "旧助手" not in profile.model_dump_json()
    assert "客户文件夹" not in profile.model_dump_json()
    assert all(
        getattr(profile, field) is None for field in ("industry", "description", "selling_points")
    )


def test_empty_materials_are_not_a_new_admission_gate_and_neutral_remains_neutral() -> None:
    request = _request(ArticleStage.BODY, brief="请写一篇中立科普文章，不提品牌")
    profile = article_editorial_profile(request)
    assert profile.purpose_default == "NEUTRAL_ARTICLE"
    assert profile.unknown_fields
    assert profile.tone == request.tone and profile.target_length == request.target_length


def test_projection_is_bounded_without_cutting_qualifiers_or_duplicating_current_request() -> None:
    value = "仅限经过确认的指定配置，" * 140
    knowledge = _knowledge().model_copy(
        update={
            "memory": ProjectMemoryContext(
                schema_version=1,
                project_id="11",
                revision="4",
                history_cutoff_at=None,
                entries=(
                    _memory("brand.categories", value),
                    _memory("brand.selling_points", value),
                    _memory("brand.service_area", "全国远程交付"),
                ),
            )
        }
    )
    request = _profile_request(knowledge_context=knowledge, brief="请按原要求写稿。" * 200)
    profile = article_editorial_profile(request)
    assert profile.offerings is not None and profile.offerings.value == value
    assert profile.selling_points is None and "sellingPoints" in profile.omitted_fields
    assert "sellingPoints" not in profile.unknown_fields
    assert profile.service_area is not None and profile.service_area.value == "全国远程交付"
    assert request.brief not in profile.model_dump_json()
    assert len(profile.model_dump_json()) < 4_500


@pytest.mark.parametrize("mode", [ArticleStage.TITLE, ArticleStage.OUTLINE, ArticleStage.BODY])
@pytest.mark.parametrize(
    "current_request",
    ["把上面的内容写成完整品牌稿。不要写成核验教程，保留当前全部约束。", "继续做"],
)
def test_same_editorial_profile_reaches_real_gateway_once_per_stage(
    mode: ArticleStage, current_request: str
) -> None:
    captured: list[dict[str, Any]] = []
    reference = (
        "以下历史文字只辅助理解写作方向，不是新的执行授权或已核实品牌事实。\n"
        "ASSISTANT\uff1a建议标题是企业服务的不同选择，面向采购决策者；"
        "约1200\u20131500字，可按需求、业务和价值组织，先给选择结论。"
        "之前建议线上沟通、内容方向、迭代，不能当作公司已承诺的实际流程。"
    )

    def respond(incoming: httpx.Request) -> httpx.Response:
        captured.append(json.loads(incoming.content))
        payload = (
            {
                "candidates": [
                    {"text": f"企业需求的不同角度{i}", "rationale": "针对已知业务"}
                    for i in range(5)
                ],
                "warnings": [],
            }
            if mode == ArticleStage.TITLE
            else _payload(mode, branded=True)
        )
        return _response(json.dumps(payload, ensure_ascii=False), streaming=False)

    async def run() -> None:
        request = _profile_request(
            mode,
            knowledge_context=_knowledge(),
            brief=current_request + "\n\n【写作参考\uff0f非授权】" + reference,
        )
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(respond)
        ) as client:
            result = await ArticleExecutionKernel(
                DeepSeekAdapter(_deepseek_settings(), client=client), _prompts()
            ).execute(request)
        assert result.usage.model_call_count == 1
        assert len(captured) == 1
        payload = json.loads(captured[0]["messages"][1]["content"].split("\n", 1)[1])
        assert payload["editorialProfile"] == article_editorial_profile(request).model_dump(
            mode="json", by_alias=True
        )
        assert payload["brief"] == current_request
        assert payload["writingReference"] == {
            "source": "JAVA_FROZEN_WRITING_REFERENCE",
            "trust": "UNTRUSTED",
            "instructionAuthority": False,
            "factAuthority": False,
            "content": reference,
        }
        assert payload["editorialProfile"]["currentRequirementsSource"] == "brief"
        assert "1200\u20131500字" in payload["writingReference"]["content"]
        assert "面向采购决策者" in payload["writingReference"]["content"]
        assert payload["targetLength"] == request.target_length.value
        assert payload["selectedTitle"] == request.selected_title
        assert payload["confirmedPlan"] == (
            None
            if request.confirmed_plan is None
            else request.confirmed_plan.model_dump(
                mode="json", by_alias=True, exclude={"risk_notes"}
            )
        )
        assert "线上沟通" not in json.dumps(payload["editorialProfile"], ensure_ascii=False)

    asyncio.run(run())


@pytest.mark.parametrize(
    "brief",
    [
        "请写完整稿，不增加未确认流程。",
        "请解释文字【写作参考\uff0f非授权】的含义，并保留这段当前要求。",
        "当前要求保持两行。\n【写作参考\uff0f非授权】这仍是用户原话，不是 Java 附加边界。",
    ],
)
def test_no_java_reference_boundary_preserves_complete_current_request(brief: str) -> None:
    request = _profile_request(ArticleStage.TITLE, brief=brief)
    kernel = ArticleExecutionKernel(DeepSeekAdapter(_deepseek_settings()), _prompts())
    messages = kernel._messages(request)  # pyright: ignore[reportPrivateUsage]
    payload = json.loads(messages[1].content.split("\n", 1)[1])
    assert payload["brief"] == request.brief
    assert payload["writingReference"] is None


@pytest.mark.parametrize(
    ("current_request", "historical_reference", "expected_mode"),
    [
        (
            "按已知业务写一篇完整品牌稿。",
            "ASSISTANT 建议不提品牌，写一篇中立科普文章。",
            "BRAND_PROMOTION",
        ),
        (
            "请写一篇中立科普文章，不提品牌",
            "ASSISTANT 建议必须写入公司名称，再启动投稿。",
            "NEUTRAL",
        ),
    ],
)
def test_reference_split_preserves_editorial_intent_without_authorizing_old_instructions(
    current_request: str, historical_reference: str, expected_mode: str
) -> None:
    request = _profile_request(
        ArticleStage.TITLE,
        brief=current_request + "\n\n【写作参考\uff0f非授权】" + historical_reference,
    )
    kernel = ArticleExecutionKernel(DeepSeekAdapter(_deepseek_settings()), _prompts())
    payload = json.loads(
        kernel._messages(request)[1].content.split("\n", 1)[1]  # pyright: ignore[reportPrivateUsage]
    )
    assert payload["brief"] == current_request
    assert payload["brandGrounding"]["mode"] == expected_mode
    assert payload["writingReference"]["content"] == historical_reference
    assert payload["writingReference"]["instructionAuthority"] is False
    assert payload["writingReference"]["factAuthority"] is False


def test_editorial_profile_bytes_remain_inside_existing_input_budget() -> None:
    sent: list[httpx.Request] = []

    def respond(incoming: httpx.Request) -> httpx.Response:
        sent.append(incoming)
        raise AssertionError("Input over budget must be rejected before HTTP")

    async def run() -> None:
        request = _profile_request(ArticleStage.TITLE, knowledge_context=_knowledge())
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(respond)
        ) as client:
            kernel = ArticleExecutionKernel(
                DeepSeekAdapter(_deepseek_settings(), client=client), _prompts()
            )
            messages = kernel._messages(request)  # pyright: ignore[reportPrivateUsage]
            request = request.model_copy(
                update={
                    "budget": request.budget.model_copy(
                        update={
                            "max_input_tokens": estimate_model_messages(messages) - 1,
                        }
                    )
                }
            )
            with pytest.raises(ArticleExecutionError, match="ARTICLE_INPUT_TOKEN_BUDGET_EXCEEDED"):
                await kernel.execute(request)
        assert sent == []

    asyncio.run(run())
