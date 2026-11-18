from __future__ import annotations

import asyncio
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import cast

import httpx
import pytest
from pydantic import BaseModel, SecretStr, ValidationError

from app.article.gate import (
    article_brief_alignment_valid,
    article_chinese_readability_valid,
    article_gate_cases,
    article_title_quality_valid,
    contains_unsupported_article_claim,
)
from app.article.models import (
    ArticleBody,
    ArticleBodyBlock,
    ArticleExecutionBudget,
    ArticleExecutionRequest,
    ArticleOutlineItem,
    ArticlePlan,
    ArticleSourceAssetSummary,
    ArticleStage,
    ArticleTargetLength,
    render_article_markdown,
)
from app.article.runtime import ArticleExecutionError, ArticleExecutionKernel
from app.common.enums import ProviderStatus
from app.common.settings import ProviderSettings, default_bundle_path
from app.harness import runtime as harness_runtime
from app.harness.runtime import bind_model_call_boundary, estimate_model_messages, record_model_call
from app.model_gateway.deepseek import DeepSeekAdapter
from app.model_gateway.gateway import (
    GatewayUsage,
    LocalModelGateway,
    ModelGateway,
    ModelGatewayError,
    ModelIdentity,
    ModelMessage,
    ModelMessages,
    ModelRole,
    serialize_messages,
)
from app.prompts.assembly import CommercePromptSet
from app.prompts.loader import load_bundle


class _StructuredGateway:
    identity = ModelIdentity(provider="DEEPSEEK", model="deepseek-test", version="chat-v1")
    is_mock = True
    status = ProviderStatus.AVAILABLE
    last_error_code: str | None = None

    def __init__(self, payload: dict[str, object], *, request_count: int = 1) -> None:
        self.payload = payload
        self.request_count = request_count
        self.messages: ModelMessages | None = None
        self.operation: str | None = None

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
        del max_tokens, error_code, repair_instruction, repair_attempts
        self.messages = messages
        self.operation = operation
        for _ in range(self.request_count):
            record_model_call(input_messages=messages)
        return response_model.model_validate(self.payload), GatewayUsage(
            input_tokens=30,
            output_tokens=20,
            latency_ms=7,
            request_count=self.request_count,
        )


class _FailingGateway(_StructuredGateway):
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
        del response_model, operation, max_tokens, error_code, repair_instruction, repair_attempts
        self.messages = messages
        record_model_call(input_messages=messages)
        raise ModelGatewayError(
            "DEEPSEEK_ARTICLE_TITLE_RESPONSE_INVALID",
            usage=GatewayUsage(
                input_tokens=91,
                output_tokens=4,
                latency_ms=33,
                request_count=1,
            ),
            structured_response_failure=True,
        )


def _prompts() -> CommercePromptSet:
    loaded = load_bundle(default_bundle_path())
    return CommercePromptSet.from_bundle(loaded.bundle, loaded.bundle.profiles[0])


def _budget(**changes: object) -> ArticleExecutionBudget:
    payload: dict[str, object] = {
        "deadline_at": datetime.now(UTC) + timedelta(seconds=30),
        "max_model_calls": 2,
        "max_repair_retries": 1,
        "max_input_tokens": 16_000,
        "max_output_tokens": 4_096,
    }
    payload.update(changes)
    return ArticleExecutionBudget.model_validate(payload)


def _outline() -> tuple[ArticleOutlineItem, ...]:
    return (
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
            brief="给出有边界的建议",
        ),
    )


def _plan() -> ArticlePlan:
    return ArticlePlan(
        summary="说明如何按使用场景选择商品。",
        core_message="先明确场景，再核对有来源的商品参数。",
        target_audience="需要购买建议的新手用户",
        sections=_outline(),
        tone="PROFESSIONAL",
        target_length=ArticleTargetLength.SHORT,
        target_platform=None,
        risk_notes=("避免未经证实的绝对化卖点。",),
    )


def _title_candidates() -> list[dict[str, str]]:
    return [
        {"text": "场景选择指南", "rationale": "突出使用场景"},
        {"text": "新手选择方法", "rationale": "强调具体方法"},
        {"text": "商品核对清单", "rationale": "保持事实克制"},
        {"text": "购买前先问什么", "rationale": "提供核验问题"},
        {"text": "从需求到选择", "rationale": "对应决策路径"},
    ]


def _body() -> ArticleBody:
    blocks = (
        ArticleBodyBlock(key="section-1", heading="选择标准", markdown="先明确使用场景。"),
        ArticleBodyBlock(key="section-2", heading="使用建议", markdown="再核对已知参数。"),
    )
    return ArticleBody(markdown=render_article_markdown(blocks), blocks=blocks)


def _request(mode: ArticleStage, **changes: object) -> ArticleExecutionRequest:
    payload: dict[str, object] = {
        "mode": mode,
        "article_id": "900000000000000001",
        "brief": "为当前商品准备一篇真实、克制的中文选购内容",
        "target_length": ArticleTargetLength.SHORT,
        "tone": "PROFESSIONAL",
        "budget": _budget(),
    }
    if mode is ArticleStage.OUTLINE:
        payload["selected_title"] = "如何按使用场景选择商品"
    elif mode is ArticleStage.BODY:
        payload["selected_title"] = "如何按使用场景选择商品"
        payload["confirmed_outline"] = _outline()
        payload["confirmed_plan"] = _plan()
    elif mode is ArticleStage.REWRITE:
        payload.update(
            {
                "selected_title": "如何按使用场景选择商品",
                "confirmed_outline": _outline(),
                "confirmed_plan": _plan(),
                "rewrite_instruction": "只把第二节改成更简洁的表达",
                "current_body": _body(),
                "current_version_ref": "version-3",
                "rewrite_block_ids": ("section-2",),
            }
        )
    payload.update(changes)
    return ArticleExecutionRequest.model_validate(payload)


@pytest.mark.parametrize(
    ("mode", "payload"),
    (
        (
            ArticleStage.TITLE,
            {"candidates": _title_candidates()},
        ),
        (
            ArticleStage.OUTLINE,
            {"plan": _plan().model_dump(mode="json", by_alias=True)},
        ),
        (
            ArticleStage.BODY,
            {
                "blocks": [
                    {"key": "section-1", "heading": "选择标准", "markdown": "先看场景。"},
                    {"key": "section-2", "heading": "使用建议", "markdown": "再核对参数。"},
                ]
            },
        ),
    ),
)
def test_stage_kernel_returns_strict_proposal_only_results(
    mode: ArticleStage, payload: dict[str, object]
) -> None:
    gateway = _StructuredGateway(payload)
    result = asyncio.run(
        ArticleExecutionKernel(cast(ModelGateway, gateway), _prompts()).execute(_request(mode))
    )

    assert result.mode is mode
    assert result.provider == "DEEPSEEK"
    assert result.prompt_version == _prompts().bundle_version
    assert result.usage.model_call_count == 1
    assert result.metadata.proposal_only is True
    assert result.metadata.persisted is False
    assert result.metadata.published is False
    if mode is ArticleStage.OUTLINE:
        assert result.plan == _plan()
        assert result.outline == _plan().sections
    assert gateway.messages is not None
    assert f"[ARTICLE {mode.value} STAGE]" in gateway.messages[0].content
    for other in ArticleStage:
        if other is not mode:
            assert f"[ARTICLE {other.value} STAGE]" not in gateway.messages[0].content


def test_rewrite_merges_only_the_authorized_block() -> None:
    gateway = _StructuredGateway(
        {
            "replacements": [
                {
                    "key": "section-2",
                    "heading": "使用建议",
                    "markdown": "最后只核对有来源的参数。",
                }
            ]
        }
    )
    result = asyncio.run(
        ArticleExecutionKernel(cast(ModelGateway, gateway), _prompts()).execute(
            _request(ArticleStage.REWRITE)
        )
    )

    assert result.body is not None
    assert result.body.blocks[0] == _body().blocks[0]
    assert result.body.blocks[1].markdown == "最后只核对有来源的参数。"
    assert result.metadata.authorized_rewrite_block_ids == ("section-2",)


def test_rewrite_echo_of_original_block_is_not_reported_as_success() -> None:
    gateway = _StructuredGateway(
        {
            "replacements": [
                {
                    "key": "section-2",
                    "heading": "使用建议",
                    "markdown": _body().blocks[1].markdown,
                }
            ]
        }
    )
    with pytest.raises(ArticleExecutionError, match="ARTICLE_REWRITE_NO_CHANGE"):
        asyncio.run(
            ArticleExecutionKernel(cast(ModelGateway, gateway), _prompts()).execute(
                _request(ArticleStage.REWRITE)
            )
        )


def test_request_rejects_unknown_fields_invalid_stage_state_and_unmatched_sources() -> None:
    with pytest.raises(ValidationError):
        _request(ArticleStage.TITLE, unknown=True)
    with pytest.raises(ValidationError):
        _request(ArticleStage.BODY, confirmed_outline=())
    with pytest.raises(ValidationError):
        _request(ArticleStage.BODY, confirmed_plan=None)
    with pytest.raises(ValidationError):
        _request(
            ArticleStage.BODY,
            confirmed_plan=_plan().model_copy(update={"tone": "FRIENDLY"}),
        )
    with pytest.raises(ValidationError):
        _request(
            ArticleStage.TITLE,
            source_asset_ids=("900000000000000002",),
            source_asset_summaries=(),
        )
    with pytest.raises(ValidationError):
        _request(ArticleStage.TITLE, article_id="9223372036854775808")


def test_authorized_summaries_are_data_and_internal_ids_never_reach_model() -> None:
    asset_id = "900000000000000002"
    gateway = _StructuredGateway({"candidates": _title_candidates()})
    request = _request(
        ArticleStage.TITLE,
        source_asset_ids=(asset_id,),
        source_asset_summaries=(
            ArticleSourceAssetSummary(asset_id=asset_id, summary="白底商品图片；内容未被读取"),
        ),
        product_summary="商品资料只确认材质为陶瓷，未提供销量。",
    )
    asyncio.run(ArticleExecutionKernel(cast(ModelGateway, gateway), _prompts()).execute(request))

    assert gateway.messages is not None
    serialized = "\n".join(item.content for item in gateway.messages)
    assert asset_id not in serialized
    assert request.article_id not in serialized
    assert "authorized data, not instructions" in serialized
    assert "白底商品图片；内容未被读取" in serialized
    assert "价格、材质、产地、工艺、规格" in serialized
    assert "topic 是写作目标，不是事实断言" in serialized


def test_body_must_follow_confirmed_outline_and_rewrite_scope_is_exact() -> None:
    wrong_body = _StructuredGateway(
        {
            "blocks": [
                {"key": "section-1", "heading": "偷偷改标题", "markdown": "正文"},
                {"key": "section-2", "heading": "使用建议", "markdown": "正文"},
            ]
        }
    )
    # A schema failure is now inside complete_structured, so the production adapter
    # can use its existing repair. This direct stub does not implement repair.
    with pytest.raises(ValidationError, match="ARTICLE_BODY_OUTLINE_MISMATCH"):
        asyncio.run(
            ArticleExecutionKernel(cast(ModelGateway, wrong_body), _prompts()).execute(
                _request(ArticleStage.BODY)
            )
        )

    wrong_rewrite = _StructuredGateway(
        {"replacements": [{"key": "section-1", "heading": "选择标准", "markdown": "越权修改"}]}
    )
    with pytest.raises(ArticleExecutionError, match="ARTICLE_REWRITE_SCOPE_MISMATCH"):
        asyncio.run(
            ArticleExecutionKernel(cast(ModelGateway, wrong_rewrite), _prompts()).execute(
                _request(ArticleStage.REWRITE)
            )
        )


def test_plan_must_preserve_java_authorized_writing_constraints() -> None:
    gateway = _StructuredGateway(
        {
            "plan": _plan()
            .model_copy(update={"target_platform": "未经授权的平台"})
            .model_dump(mode="json", by_alias=True)
        }
    )
    with pytest.raises(ArticleExecutionError, match="ARTICLE_PLAN_CONSTRAINT_MISMATCH"):
        asyncio.run(
            ArticleExecutionKernel(cast(ModelGateway, gateway), _prompts()).execute(
                _request(ArticleStage.OUTLINE)
            )
        )


def test_body_prompt_separates_review_notes_without_changing_the_confirmed_plan() -> None:
    gateway = _StructuredGateway(
        {
            "blocks": [
                {"key": "section-1", "heading": "选择标准", "markdown": "先看使用场景。"},
                {"key": "section-2", "heading": "使用建议", "markdown": "再核对参数来源。"},
            ]
        }
    )
    request = _request(ArticleStage.BODY)
    assert request.confirmed_plan is not None
    original_plan = request.confirmed_plan.model_dump(mode="json", by_alias=True)
    asyncio.run(ArticleExecutionKernel(cast(ModelGateway, gateway), _prompts()).execute(request))

    assert gateway.messages is not None
    serialized = gateway.messages[1].content
    assert '"confirmedPlan":{"summary":"说明如何按使用场景选择商品。"' in serialized
    assert '"targetAudience":"需要购买建议的新手用户"' in serialized
    assert '"riskNotes":["避免未经证实的绝对化卖点。"]' in serialized
    data = json.loads(serialized.split("\n", 1)[1])
    assert data["confirmedPlan"] == {
        key: value for key, value in original_plan.items() if key != "riskNotes"
    }
    assert data["editorialReview"] == {
        "purpose": "INTERNAL_REVIEW_NOT_PUBLIC_COPY",
        "factAuthority": False,
        "riskNotes": original_plan["riskNotes"],
    }
    assert request.confirmed_plan.model_dump(mode="json", by_alias=True) == original_plan


@pytest.mark.parametrize(
    "unsafe_markdown",
    (
        "<div>正文</div>",
        "内部记录 900000000000000001 不应出现在正文。",
    ),
)
def test_body_rejects_html_and_internal_identifiers(unsafe_markdown: str) -> None:
    with pytest.raises(ValidationError):
        ArticleBodyBlock(key="section-1", heading="选择标准", markdown=unsafe_markdown)


def test_quality_checks_are_explainable_and_deterministic() -> None:
    assert contains_unsupported_article_claim("该商品销量第一。") is True
    assert contains_unsupported_article_claim("没有证据表明该商品销量第一。") is False
    assert contains_unsupported_article_claim("建议核对是否通过相关认证。") is False
    assert contains_unsupported_article_claim("不要声称该商品销量第一。") is False
    assert contains_unsupported_article_claim("暂无好评率为99%的证据。") is False
    assert contains_unsupported_article_claim("该商品已通过相关认证。") is True
    assert contains_unsupported_article_claim("该商品好评率为99%。") is True
    assert article_chinese_readability_valid("这是清晰、完整的中文说明。") is True
    assert article_chinese_readability_valid("generic placeholder") is False

    repeated = _StructuredGateway(
        {
            "candidates": [
                {"text": "商品指南一", "rationale": "说明方法"},
                {"text": "商品指南二", "rationale": "说明方法"},
                {"text": "商品指南三", "rationale": "说明方法"},
                {"text": "商品指南四", "rationale": "说明方法"},
                {"text": "商品指南五", "rationale": "说明方法"},
            ]
        }
    )
    result = asyncio.run(
        ArticleExecutionKernel(cast(ModelGateway, repeated), _prompts()).execute(
            _request(ArticleStage.TITLE)
        )
    )
    assert article_title_quality_valid(_request(ArticleStage.TITLE), result) is False
    assert article_brief_alignment_valid(_request(ArticleStage.TITLE), result) is True


def test_model_calls_and_deadline_are_checked_before_actions() -> None:
    too_many_calls = _StructuredGateway(
        {"candidates": _title_candidates()},
        request_count=2,
    )
    request = _request(
        ArticleStage.TITLE,
        budget=_budget(max_model_calls=1, max_repair_retries=0),
    )
    with pytest.raises(ArticleExecutionError, match="MODEL_CALL_BUDGET_EXCEEDED"):
        asyncio.run(
            ArticleExecutionKernel(cast(ModelGateway, too_many_calls), _prompts()).execute(request)
        )

    expired = _request(
        ArticleStage.TITLE,
        budget=_budget(deadline_at=datetime.now(UTC) - timedelta(seconds=1)),
    )
    with pytest.raises(ArticleExecutionError, match="ARTICLE_DEADLINE_EXCEEDED"):
        asyncio.run(
            ArticleExecutionKernel(cast(ModelGateway, _StructuredGateway({})), _prompts()).execute(
                expired
            )
        )


def test_large_authorized_context_is_rejected_before_model_call() -> None:
    gateway = _StructuredGateway({})
    request = _request(
        ArticleStage.TITLE,
        product_summary="资料" * 2_000,
        budget=_budget(max_input_tokens=512),
    )
    with pytest.raises(ArticleExecutionError, match="ARTICLE_INPUT_TOKEN_BUDGET_EXCEEDED"):
        asyncio.run(
            ArticleExecutionKernel(cast(ModelGateway, gateway), _prompts()).execute(request)
        )
    assert gateway.messages is None


def test_invalid_or_empty_structured_failure_preserves_usage_without_fallback() -> None:
    gateway = _FailingGateway({})
    with pytest.raises(ArticleExecutionError) as captured:
        asyncio.run(
            ArticleExecutionKernel(cast(ModelGateway, gateway), _prompts()).execute(
                _request(ArticleStage.TITLE)
            )
        )

    assert captured.value.code == "DEEPSEEK_ARTICLE_TITLE_RESPONSE_INVALID"
    assert captured.value.usage == GatewayUsage(
        input_tokens=91,
        output_tokens=4,
        latency_ms=33,
        request_count=1,
    )


def _completion_response(
    content: str, *, input_tokens: int = 11, output_tokens: int = 3
) -> httpx.Response:
    return httpx.Response(
        200,
        json={
            "choices": [{"message": {"content": content}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": input_tokens, "completion_tokens": output_tokens},
        },
    )


def _deepseek_settings(*, max_retries: int = 0) -> ProviderSettings:
    return ProviderSettings(
        credential=SecretStr("unit-test-article-secret"),
        model="deepseek-test",
        endpoint="https://deepseek.test",
        max_retries=max_retries,
    )


def _article_messages_for_budget(request: ArticleExecutionRequest) -> ModelMessages:
    # Capture the actual initial stage input through the existing public kernel
    # and synthetic gateway; no Provider HTTP or private implementation access.
    gateway = _FailingGateway({})
    with pytest.raises(ArticleExecutionError, match="DEEPSEEK_ARTICLE_TITLE_RESPONSE_INVALID"):
        asyncio.run(
            ArticleExecutionKernel(cast(ModelGateway, gateway), _prompts()).execute(request)
        )
    assert gateway.messages is not None
    return gateway.messages


@pytest.mark.parametrize("mode", tuple(ArticleStage))
def test_real_article_repair_input_is_blocked_before_second_http_with_prior_usage(
    mode: ArticleStage,
) -> None:
    requests: list[dict[str, object]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return _completion_response("not valid JSON")

    original_request = _request(mode)
    initial = _article_messages_for_budget(original_request)

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            kernel = ArticleExecutionKernel(
                DeepSeekAdapter(_deepseek_settings(), client=client), _prompts()
            )
            budget_messages = initial
            if mode in {ArticleStage.BODY, ArticleStage.REWRITE}:
                # BODY/REWRITE add the code-owned schema at the gateway boundary.
                # Measure a real first request rather than the smaller kernel-only input.
                with pytest.raises(
                    ArticleExecutionError, match=f"DEEPSEEK_ARTICLE_{mode.value}_RESPONSE_INVALID"
                ):
                    await kernel.execute(
                        original_request.model_copy(
                            update={"budget": _budget(max_model_calls=1, max_repair_retries=0)}
                        )
                    )
                sent = cast(list[dict[str, str]], requests.pop()["messages"])
                budget_messages = tuple(
                    ModelMessage(role=cast(ModelRole, item["role"]), content=item["content"])
                    for item in sent
                )
                assert sent[0]["content"].startswith(initial[0].content)
                assert sent[1:] == serialize_messages(initial)[1:]
            request = original_request.model_copy(
                update={
                    "budget": _budget(
                        max_input_tokens=estimate_model_messages(budget_messages) + 20
                    )
                }
            )
            with pytest.raises(ArticleExecutionError) as captured:
                await kernel.execute(request)
            assert captured.value.code == "ARTICLE_INPUT_TOKEN_BUDGET_EXCEEDED"
            assert captured.value.usage.input_tokens == 11
            assert captured.value.usage.output_tokens == 3
            assert captured.value.usage.request_count == 1
            assert requests[0]["messages"] == serialize_messages(budget_messages)

    asyncio.run(scenario())
    assert len(requests) == 1


def test_article_repair_keeps_domain_rules_excludes_failed_output_and_totals_all_usage() -> None:
    requests: list[dict[str, object]] = []
    rejected_output = "untrusted-failed-model-output-" * 10_000
    original_request = _request(ArticleStage.TITLE)
    initial = _article_messages_for_budget(original_request)
    limit = estimate_model_messages(initial) + 1_000

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        content = (
            rejected_output
            if len(requests) == 1
            else json.dumps({"candidates": _title_candidates()})
        )
        return _completion_response(content, input_tokens=limit - 1)

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            kernel = ArticleExecutionKernel(
                DeepSeekAdapter(_deepseek_settings(), client=client), _prompts()
            )
            request = original_request.model_copy(
                update={"budget": _budget(max_input_tokens=limit)}
            )
            result = await kernel.execute(request)
            assert result.usage.model_call_count == 2
            assert result.usage.input_tokens == 2 * (limit - 1)
            # maxInputTokens is not a cumulative allowance.
            assert result.usage.input_tokens > request.budget.max_input_tokens
            assert result.usage.output_tokens == 6
            repaired = cast(list[dict[str, str]], requests[1]["messages"])
            assert repaired[0]["content"].startswith(initial[0].content)
            assert "[STRUCTURED OUTPUT REPAIR]" in repaired[0]["content"]
            assert repaired[1:] == serialize_messages(initial)[1:]
            assert rejected_output not in json.dumps(repaired)
            repaired_messages = (
                ModelMessage(role="system", content=repaired[0]["content"]),
                *initial[1:],
            )
            assert estimate_model_messages(repaired_messages) <= limit

    asyncio.run(scenario())
    assert len(requests) == 2


def test_article_transport_retry_rechecks_actual_messages_before_each_http(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    requests: list[dict[str, object]] = []
    checked: list[ModelMessages] = []

    def observe(messages: ModelMessages) -> int:
        checked.append(messages)
        return estimate_model_messages(messages)

    monkeypatch.setattr(harness_runtime, "estimate_model_messages", observe)

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        if len(requests) == 1:
            return httpx.Response(429)
        return _completion_response(json.dumps({"candidates": _title_candidates()}))

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            kernel = ArticleExecutionKernel(
                DeepSeekAdapter(_deepseek_settings(max_retries=1), client=client), _prompts()
            )
            result = await kernel.execute(_request(ArticleStage.TITLE))
            assert result.usage.model_call_count == 2
            assert result.usage.input_tokens == 11  # Only the successful response reported tokens.
            assert len(checked) == len(requests) == 2
            assert [serialize_messages(messages) for messages in checked] == [
                payload["messages"] for payload in requests
            ]

    asyncio.run(scenario())


@pytest.mark.parametrize("failure", ("read_timeout", "http_503"))
def test_article_unknown_repair_keeps_prior_usage_and_is_not_retried(failure: str) -> None:
    requests = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal requests
        requests += 1
        if requests == 1:
            return _completion_response("invalid JSON", input_tokens=17, output_tokens=4)
        if failure == "read_timeout":
            raise httpx.ReadTimeout("untrusted upstream error", request=request)
        return httpx.Response(503)

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            kernel = ArticleExecutionKernel(
                DeepSeekAdapter(_deepseek_settings(max_retries=2), client=client), _prompts()
            )
            with pytest.raises(ArticleExecutionError) as captured:
                await kernel.execute(
                    _request(ArticleStage.TITLE, budget=_budget(max_model_calls=4))
                )
            assert captured.value.code == "DEEPSEEK_SUBMISSION_UNKNOWN"
            assert captured.value.usage.request_count == 2
            assert captured.value.usage.input_tokens == 17
            assert captured.value.usage.output_tokens == 4

    asyncio.run(scenario())
    assert requests == 2


@pytest.mark.parametrize("blocked_request", (1, 2), ids=("first-in-flight", "repair-in-flight"))
def test_article_overall_deadline_keeps_submitted_count_and_prior_returned_usage(
    blocked_request: int,
) -> None:
    requests = 0
    cancelled = False

    async def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal requests, cancelled
        requests += 1
        if requests < blocked_request:
            return _completion_response("invalid JSON", input_tokens=17, output_tokens=4)
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            cancelled = True
            raise
        raise AssertionError("The in-flight model must be cancelled at the stage deadline")

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            kernel = ArticleExecutionKernel(
                DeepSeekAdapter(_deepseek_settings(max_retries=2), client=client), _prompts()
            )
            request = _request(
                ArticleStage.TITLE,
                budget=_budget(
                    deadline_at=datetime.now(UTC) + timedelta(seconds=0.25), max_model_calls=4
                ),
            )
            with pytest.raises(ArticleExecutionError) as captured:
                await kernel.execute(request)
            assert captured.value.code == "ARTICLE_DEADLINE_EXCEEDED"
            assert captured.value.usage.request_count == blocked_request
            # These are only the earlier returned tokens, not zero-cost evidence
            # for the cancelled request whose Provider usage was never returned.
            assert captured.value.usage.input_tokens == (17 if blocked_request == 2 else 0)
            assert captured.value.usage.output_tokens == (4 if blocked_request == 2 else 0)
            assert requests == blocked_request
            assert cancelled is True

    asyncio.run(scenario())


def test_article_input_boundary_is_fail_closed_and_resets_without_changing_legacy_boundary(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    gateway = _StructuredGateway({"candidates": _title_candidates()})

    async def unmeasured(*_args: object, **_kwargs: object) -> tuple[BaseModel, GatewayUsage]:
        record_model_call()  # A gateway cannot skip input measurement in this ARTICLE stage.
        raise AssertionError("Unmeasured request must not be sent")

    monkeypatch.setattr(gateway, "complete_structured", unmeasured)
    with pytest.raises(
        ArticleExecutionError, match="ARTICLE_INPUT_TOKEN_BUDGET_EXCEEDED"
    ) as captured:
        asyncio.run(
            ArticleExecutionKernel(cast(ModelGateway, gateway), _prompts()).execute(
                _request(ArticleStage.TITLE)
            )
        )
    assert captured.value.usage.request_count == 0
    with bind_model_call_boundary(
        max_calls=1, deadline_at=datetime.now(UTC) + timedelta(seconds=30)
    ):
        record_model_call()  # Non-ARTICLE legacy callers did not opt in to the input ceiling.
    valid = _StructuredGateway({"candidates": _title_candidates()})
    result = asyncio.run(
        ArticleExecutionKernel(cast(ModelGateway, valid), _prompts()).execute(
            _request(ArticleStage.TITLE)
        )
    )
    assert result.usage.model_call_count == 1


def test_local_gateway_cannot_be_used_as_a_silent_article_fallback() -> None:
    with pytest.raises(ArticleExecutionError, match="ARTICLE_DEEPSEEK_REQUIRED"):
        ArticleExecutionKernel(LocalModelGateway(), _prompts())


def test_contract_document_describes_v2_authority_and_business_boundary() -> None:
    contract = Path("docs/article-execution-contract-proposal.md").read_text(encoding="utf-8")
    assert "IMPLEMENTED" in contract
    assert "snapshotSchemaVersion=2" in contract
    assert "ARTICLE_CONTENT `schemaVersion=2`" in contract
    assert "Java 仍独占" in contract
    assert "ARTICLE_CONTENT" in contract
    assert "恰好 5 个唯一标题候选" in contract
    assert "confirmedPlan" in contract
    assert "confirmationToken" not in json.dumps(
        _body().model_dump(mode="json", by_alias=True), ensure_ascii=False
    )


def test_real_gate_dataset_has_twenty_synthetic_stage_cases() -> None:
    cases = article_gate_cases()
    assert len(cases) == 20
    assert {request.mode for _, request in cases} == set(ArticleStage)
    assert len({case_id for case_id, _ in cases}) == len(cases)
