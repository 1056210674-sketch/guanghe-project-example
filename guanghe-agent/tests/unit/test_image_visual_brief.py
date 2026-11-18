"""Confirmed commercial image direction reaches the actual provider payload intact."""

import asyncio
import json
from dataclasses import replace
from typing import cast

import httpx
import pytest
from pydantic import JsonValue, ValidationError

from app.agent.graphs.commerce import CommerceAssistantGraph
from app.agent.graphs.domains import DomainGraphError, ImageParameters
from app.agent.models import DraftOperation, Intent, TaskDraftProposal
from app.creative.image_brief import ImageVisualBrief
from app.creative.models import ProductCreativeBrief
from app.model_gateway.gateway import ModelMessages, TaskParametersResult, default_intent_route
from app.providers.models import TaskProviderError
from app.providers.qwen_image import QwenImageAdapter
from tests.unit.test_commerce_graph import (
    _java_context,  # pyright: ignore[reportPrivateUsage]
    _MappedJavaContextProvider,  # pyright: ignore[reportPrivateUsage]
    _SemanticDraftGateway,  # pyright: ignore[reportPrivateUsage]
    _with_active_image_workspace,  # pyright: ignore[reportPrivateUsage]
)
from tests.unit.test_commerce_graph import (
    _request as chat_request,  # pyright: ignore[reportPrivateUsage]
)
from tests.unit.test_qwen_image_adapter import (
    _request as provider_request,  # pyright: ignore[reportPrivateUsage]
)
from tests.unit.test_qwen_image_adapter import _settings  # pyright: ignore[reportPrivateUsage]


def visual_brief() -> dict[str, JsonValue]:
    return {
        "version": "commerce-image-v1",
        "platform": "PINDUODUO",
        "placement": "MAIN_IMAGE",
        "visualFocus": "完整商品的清楚轮廓与参考图真实细节",
        "composition": "正面近景，主体占画面约七成，完整不裁切，保持真实包装数量",
        "background": "纯白背景，无道具，阴影形成前后层次",
        "lighting": "侧前方柔光，主体边缘清楚，真实接触阴影",
        "textPolicy": "NONE",
        "copyLines": [],
    }


@pytest.mark.parametrize(
    "override",
    [
        {"version": "invented"},
        {"price": 9.9},
        {"composition": "x" * 241},
        {"lighting": " "},
        {"textPolicy": "NONE", "copyLines": ["销量第一"]},
        {"textPolicy": "USER_SUPPLIED", "copyLines": []},
        {"textPolicy": "USER_SUPPLIED", "copyLines": ["x" * 41]},
    ],
)
def test_visual_brief_rejects_unbounded_or_inconsistent_fields(override: dict[str, object]) -> None:
    with pytest.raises(ValidationError):
        ImageParameters.model_validate({"visualBrief": {**visual_brief(), **override}})


def test_new_readable_copy_must_be_literal_user_input_or_same_revision_copy() -> None:
    raw = {**visual_brief(), "textPolicy": "USER_SUPPLIED", "copyLines": ["夏日时刻"]}
    brief = ImageVisualBrief.model_validate(raw)
    brief.validate_copy_source("请在图上加字: 夏日时刻")
    brief.validate_copy_source("只把背景换成白色", raw)
    with pytest.raises(ValueError, match="explicitly supplied"):
        brief.validate_copy_source("生成一张拼多多风格商品图")
    with pytest.raises(ValueError, match="explicitly supplied"):
        brief.validate_copy_source("改背景", visual_brief())
    with pytest.raises(ValueError, match="explicitly supplied"):
        brief.validate_copy_source("不要写夏日时刻", raw)
    with pytest.raises(ValueError, match="explicitly supplied"):
        brief.validate_copy_source("夏日时刻这个说法怎么样？")


def test_negative_mention_never_authorizes_generated_copy() -> None:
    brief = ImageVisualBrief.model_validate(
        {
            **visual_brief(),
            "textPolicy": "USER_SUPPLIED",
            "copyLines": ["全网第一"],
        }
    )
    with pytest.raises(ValueError, match="explicitly supplied"):
        brief.validate_copy_source("拼多多商品图，不要写全网第一")


@pytest.mark.parametrize(
    "line, request_text",
    [
        ("别熬夜", "文案写“别熬夜”"),
        ("春日上新", "写春日上新不要太小"),
        ("不要字", "文案写“不要字”"),
    ],
)
def test_copy_content_and_font_size_negation_are_not_copy_denials(
    line: str, request_text: str
) -> None:
    brief = ImageVisualBrief.model_validate(
        {
            **visual_brief(),
            "textPolicy": "USER_SUPPLIED",
            "copyLines": [line],
        }
    )
    assert not brief.request_excludes_text(request_text)
    brief.validate_copy_source(request_text)


@pytest.mark.parametrize("source_mode", ["CURRENT_ATTACHMENTS", "CATALOG_PRODUCT", "TEXT_ONLY"])
def test_frozen_brief_and_negative_constraints_reach_provider_once(source_mode: str) -> None:
    direction = visual_brief()
    request_text = "生成一张拼多多风格商品图，不要红色，不加字，白底"
    parameters: dict[str, object] = {
        "requestText": request_text,
        "scene": "完整商品近景白底展示",
        "style": "清晰简洁",
        "negativePrompt": "红色背景、新增文字、价格标签、赠品",
        "aspectRatio": "1:1",
        "count": 1,
        "visualBrief": direction,
    }
    request = provider_request(parameters=parameters)
    assert request.creative_brief is not None
    wire = request.creative_brief.model_dump(mode="json", by_alias=True)
    wire.update(sourceMode=source_mode, creativeDirection=parameters)
    if source_mode == "CATALOG_PRODUCT":
        wire.update(productId="1900012345678901002", productFacts={"name": "商品事实标记-987"})
    if source_mode == "TEXT_ONLY":
        wire["referenceAssetIds"] = []
    request = request.model_copy(
        update={
            "creative_brief": ProductCreativeBrief.model_validate(wire),
            "reference_assets": () if source_mode == "TEXT_ONLY" else request.reference_assets,
        }
    )

    async def scenario() -> None:
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(lambda _: httpx.Response(500))
        ) as client:
            adapter = QwenImageAdapter(_settings(), client=client)
            payload, size, count = adapter._build_payload(request)  # pyright: ignore[reportPrivateUsage]
            serialized = json.dumps(payload, ensure_ascii=False)
            assert serialized.count("commerce-image-v1") == 1
            assert serialized.count(request_text) == 1
            assert "主体占画面约七成" in serialized
            assert "保持真实包装数量" in serialized
            assert "不得重绘成另一款商品" in serialized
            assert "红色背景、新增文字、价格标签、赠品" in serialized
            assert "no-system-secret" not in serialized
            assert count == 1 and size == "1024*1024"
            assert payload["parameters"] == {
                "prompt_extend": True,
                "prompt_extend_mode": "direct",
                "enable_thinking": True,
                "watermark": False,
                "n": 1,
                "negative_prompt": parameters["negativePrompt"],
                "size": "1024*1024",
            }
            if source_mode == "TEXT_ONLY":
                assert "asset.test" not in serialized
                assert "不声称复现真实商品身份" in serialized
            else:
                assert serialized.count("https://asset.test/current.png?signature=secret") == 1
            assert ("商品事实标记-987" in serialized) == (source_mode == "CATALOG_PRODUCT")

    asyncio.run(scenario())


def test_provider_rejects_plan_changed_after_confirmation_without_calling_network() -> None:
    async def scenario() -> None:
        def reject_network(_: httpx.Request) -> httpx.Response:
            raise AssertionError("invalid frozen direction must never reach a provider")

        async with httpx.AsyncClient(transport=httpx.MockTransport(reject_network)) as client:
            adapter = QwenImageAdapter(_settings(), client=client)
            with pytest.raises(TaskProviderError) as error:
                await adapter.execute(
                    provider_request(
                        parameters={"requestText": "商品图", "visualBrief": visual_brief()}
                    )
                )
            assert error.value.code == "IMAGE_VISUAL_BRIEF_MISMATCH"

    asyncio.run(scenario())


@pytest.mark.parametrize("operation", [DraftOperation.REVISE, DraftOperation.NEW])
def test_multiturn_revision_preserves_visual_fields_but_new_draft_does_not_inherit(
    operation: DraftOperation,
) -> None:
    request = chat_request(
        "把当前图背景改成浅灰" if operation is DraftOperation.REVISE else "另做一张商品图",
        space_id="1900012345678901001",
        product_id="1900012345678902001",
    )
    context = _with_active_image_workspace(
        _java_context("1", user_context_ref=request.user_context_ref)
    )
    assert context.task_workspace is not None
    context = replace(
        context,
        task_workspace=context.task_workspace.model_copy(
            update={
                "confirmed_constraints": {
                    "visualBrief": visual_brief(),
                    "count": 2,
                    "aspectRatio": "16:9",
                }
            }
        ),
    )

    class Gateway(_SemanticDraftGateway):
        async def suggest_task_parameters(
            self, intent: Intent, messages: ModelMessages
        ) -> TaskParametersResult:
            self.task_messages = messages
            parameters: dict[str, JsonValue] = {"scene": "商品完整展示"}
            if operation is DraftOperation.REVISE:
                parameters["visualBrief"] = {"background": "浅灰背景，不添加道具"}
            else:
                parameters["visualBrief"] = {**visual_brief(), "platform": "GENERAL"}
            return TaskParametersResult(summary="清晰商品主图", parameters=parameters)

    gateway = Gateway(
        default_intent_route(Intent.IMAGE).model_copy(update={"draft_operation": operation})
    )
    outcome = asyncio.run(
        CommerceAssistantGraph(
            _MappedJavaContextProvider({request.run_id: context}), gateway=gateway
        ).ainvoke(request)
    )
    assert outcome.task_draft is not None
    params = outcome.task_draft.parameters
    if operation is DraftOperation.REVISE:
        assert params["visualBrief"] == {**visual_brief(), "background": "浅灰背景，不添加道具"}
        assert params["count"] == 2 and params["aspectRatio"] == "16:9"
    else:
        assert params["visualBrief"] == {**visual_brief(), "platform": "GENERAL"}
        assert params["count"] == 1
        assert gateway.task_messages is not None
        assert "主体占画面约七成" not in gateway.task_messages[0].content


def test_chat_rejects_model_invented_marketing_copy_before_draft_creation() -> None:
    request = chat_request(
        "生成拼多多商品图", space_id="1900012345678901001", product_id="1900012345678902001"
    )
    context = _java_context("1", user_context_ref=request.user_context_ref)

    class Gateway(_SemanticDraftGateway):
        async def suggest_task_parameters(
            self, intent: Intent, messages: ModelMessages
        ) -> TaskParametersResult:
            return TaskParametersResult(
                summary="商品主图",
                parameters={
                    "visualBrief": {
                        **visual_brief(),
                        "textPolicy": "USER_SUPPLIED",
                        "copyLines": ["销量第一"],
                    }
                },
            )

    with pytest.raises(DomainGraphError, match="IMAGE_PARAMETERS_INVALID"):
        asyncio.run(
            CommerceAssistantGraph(
                _MappedJavaContextProvider({request.run_id: context}),
                gateway=Gateway(default_intent_route(Intent.IMAGE)),
            ).ainvoke(request)
        )


def test_missing_new_chat_plan_is_not_silently_published_as_finished_draft() -> None:
    request = chat_request(
        "生成拼多多商品图", space_id="1900012345678901001", product_id="1900012345678902001"
    )
    context = _java_context("1", user_context_ref=request.user_context_ref)

    class Gateway(_SemanticDraftGateway):
        async def suggest_task_parameters(
            self, intent: Intent, messages: ModelMessages
        ) -> TaskParametersResult:
            return TaskParametersResult(summary="主图", parameters={"scene": "拼多多风格"})

    with pytest.raises(DomainGraphError, match="IMAGE_PARAMETERS_INVALID"):
        asyncio.run(
            CommerceAssistantGraph(
                _MappedJavaContextProvider({request.run_id: context}),
                gateway=Gateway(default_intent_route(Intent.IMAGE)),
            ).ainvoke(request)
        )


def test_no_text_revision_clears_frozen_copy_even_if_model_only_changes_background() -> None:
    request = chat_request(
        "背景改灰色，不要字", space_id="1900012345678901001", product_id="1900012345678902001"
    )
    context = _with_active_image_workspace(
        _java_context("1", user_context_ref=request.user_context_ref)
    )
    assert context.task_workspace is not None
    old_brief = {**visual_brief(), "textPolicy": "USER_SUPPLIED", "copyLines": ["夏日时刻"]}
    context = replace(
        context,
        task_workspace=context.task_workspace.model_copy(
            update={
                "confirmed_constraints": {
                    "visualBrief": old_brief,
                    "count": 2,
                    "aspectRatio": "16:9",
                }
            }
        ),
    )

    class Gateway(_SemanticDraftGateway):
        async def suggest_task_parameters(
            self, intent: Intent, messages: ModelMessages
        ) -> TaskParametersResult:
            return TaskParametersResult(
                summary="修改背景", parameters={"visualBrief": {"background": "灰色"}}
            )

    outcome = asyncio.run(
        CommerceAssistantGraph(
            _MappedJavaContextProvider({request.run_id: context}),
            gateway=Gateway(
                default_intent_route(Intent.IMAGE).model_copy(
                    update={"draft_operation": DraftOperation.REVISE}
                )
            ),
        ).ainvoke(request)
    )
    assert outcome.task_draft is not None
    assert outcome.task_draft.parameters["visualBrief"] == {
        **visual_brief(),
        "background": "灰色",
        "textPolicy": "NONE",
        "copyLines": [],
    }
    assert outcome.task_draft.parameters["count"] == 2


def test_wire_contract_uses_same_visual_schema_as_provider() -> None:
    schema = TaskDraftProposal.model_json_schema(by_alias=True)
    properties = cast(dict[str, object], schema["properties"])
    params = cast(dict[str, object], properties["parameters"])
    nested = cast(dict[str, object], params["properties"])
    assert nested["visualBrief"] == ImageVisualBrief.model_json_schema(by_alias=True)
