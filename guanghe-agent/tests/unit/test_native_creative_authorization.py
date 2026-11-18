"""Current-user authorization checks for native paid creative tools."""

import asyncio
import json
from datetime import UTC, datetime, timedelta
from typing import Any, cast
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.agent.identity import CREATIVE_CAPABILITIES, bind_agent_execution, resolve_execution
from app.agent.models import ChatMessage, ChatRunRequest, RunBudget, TrustedState
from app.harness.runtime import RuntimeHarness, RuntimeUsageCounters, bind_runtime_usage
from app.integration.java_internal import JavaAgentActionClientProtocol, JavaInternalResponse
from app.model_gateway.gateway import ModelToolCall
from app.providers.java_context import LocalRunContextProvider
from app.tools.native import ImageGenerateInput, NativeTools, VideoGenerateInput
from app.tools.native_authorization import user_requested_native_actions


@pytest.mark.parametrize(
    "content,expected",
    [
        ("帮我生成一张商品图", {"image.generate"}),
        ("把这张图片背景改成白色", {"image.generate"}),
        ("把图片等比例调整后生成", {"image.generate"}),
        ("基于已选商品生成一张主图", {"image.generate"}),
        ("根据之前的图片生成一张新图", {"image.generate"}),
        ("给我一张海报", {"image.generate"}),
        ("生成图片", {"image.generate"}),
        ("生成春日咖啡海报", {"image.generate"}),
        ("生成一张照片", {"image.generate"}),
        ("设计一张春日咖啡海报", {"image.generate"}),
        ("制作一个视频", {"video.generate"}),
        ("不用问我直接生成图片", {"image.generate"}),
        ("帮我画一只戴帽子的猫", {"image.generate"}),
        ("画一只猫", {"image.generate"}),
        ("请绘制一个蓝色咖啡杯", {"image.generate"}),
        ("参考当前商品生成一个5秒视频", {"video.generate"}),
        ("把这个视频剪短一点", {"video.generate"}),
        ("我要一个视频", {"video.generate"}),
        ("不要生成图片，帮我生成视频", {"video.generate"}),
        ("只生成图片，不要制作视频", {"image.generate"}),
        ("怎么生成图片", set[str]()),
        ("生成图片多少钱", set[str]()),
        ("如果预算够就生成图片", set[str]()),
        ("如果预算够，帮我生成图片", set[str]()),
        ("不要生成图片", set[str]()),
        ("图片不要改", set[str]()),
        ("只给我图片修改建议", set[str]()),
        ("能否生成一个视频", set[str]()),
        ("可以生成图片吗", set[str]()),
        ("等我确认后再生成视频", set[str]()),
        ("等我确认，生成视频", set[str]()),
        ("等素材准备好后，生成视频", set[str]()),
        ("视频修改方案", set[str]()),
        ("“帮我生成一张图片”", set[str]()),
        ("`生成视频`", set[str]()),
        ("'生成图片'", set[str]()),
        ("「生成视频」", set[str]()),
        ("> 帮我生成一张图片\n这句话是什么意思", set[str]()),
        ("客户说: 帮我生成一张图片", set[str]()),
        ("用户说帮我生成一张图片", set[str]()),
        ("这张图片已经生成了", set[str]()),
        ("图片改好了吗", set[str]()),
        ("请把背景改成白色", set[str]()),
        ("这只猫画得很好", set[str]()),
        ("我已经画了一只猫", set[str]()),
        ("怎么画一只猫", set[str]()),
        ("不要画一只猫", set[str]()),
        ("图片来自官网", set[str]()),
        ("视频制作很专业", set[str]()),
        ("海报做得很好", set[str]()),
        ("图片生成效果很好", set[str]()),
        ("生成图片效果很好", set[str]()),
        ("图片做工很好", set[str]()),
        ("制作海报的是我", set[str]()),
        ("生成图片的工具坏了", set[str]()),
        ("生成图片很耗时", set[str]()),
        ("制作视频的人是我", set[str]()),
        ("做图片需要技术", set[str]()),
        ("画图是我的工作", set[str]()),
        ("绘制效果很好", set[str]()),
        ("画面很好", set[str]()),
        ("画质不错", set[str]()),
        ("画面来自官网", set[str]()),
        ("画一张图是我的工作", set[str]()),
        ("帮我生成图片的工具坏了", set[str]()),
        ("帮我画画的人是我朋友", set[str]()),
        ("请画画是我的工作", set[str]()),
        ("给我制作视频的人离职了", set[str]()),
        ("帮我制作视频的团队很专业", set[str]()),
        ("请生成图片的人先离开", set[str]()),
        ("我需要生成图片的工具", set[str]()),
        ("我要一个视频播放器", set[str]()),
        ("给我一个视频链接", set[str]()),
        ("给我一个视频地址", set[str]()),
        ("给我一个视频编号", set[str]()),
        ("给我一个视频报价", set[str]()),
        ("给我一张图片链接", set[str]()),
        ("给我一张图片地址", set[str]()),
        ("给我一张图片说明", set[str]()),
        ("给我一张图片分析", set[str]()),
        ("给我一张图片报告", set[str]()),
        ("给我一张图片编号", set[str]()),
        ("给我图片看看", set[str]()),
        ("给我视频看看", set[str]()),
        ("我需要图片生成工具", set[str]()),
        ("我需要视频制作软件", set[str]()),
        ("帮我图片生成的人离职了", set[str]()),
    ],
)
def test_creative_actions_require_an_explicit_current_user_command(
    content: str, expected: set[str]
) -> None:
    assert user_requested_native_actions(content) & {"image.generate", "video.generate"} == expected


@pytest.mark.parametrize(
    "content",
    [
        "帮我分析海报设计",
        "帮我评价海报设计",
        "帮我点评海报设计",
        "帮我介绍海报设计",
        "帮我审查海报设计",
        "帮我审核海报设计",
        "帮我研究海报设计",
        "帮我学习海报设计",
        "帮我讨论海报设计",
        "帮我看看海报设计",
        "帮我复盘海报设计",
        "帮我比较海报设计",
    ],
)
def test_image_design_noun_does_not_authorize_a_paid_generation(content: str) -> None:
    assert not user_requested_native_actions(content) & {"image.generate", "video.generate"}


@pytest.mark.parametrize(
    "content,expected",
    [
        ("请把背景改成白色", {"image.generate"}),
        ("去掉水印", {"image.generate"}),
        ("裁成方图", {"image.generate"}),
        ("再添加一个大狗，这只小狗靠着那只大狗", {"image.generate"}),
        ("添加星星", {"image.generate"}),
        ("再添加一棵树", {"image.generate"}),
        ("加上一位人物", {"image.generate"}),
        ("再加上远山", {"image.generate"}),
        ("请再添加一朵云", {"image.generate"}),
        ("麻烦再加上太阳", {"image.generate"}),
        ("添加树", {"image.generate"}),
        ("让它动起来", {"video.generate"}),
        ("把这张图做成一个视频", {"video.generate"}),
        ("让图中主体动起来", {"video.generate"}),
        ("让图中主体冒热气", {"video.generate"}),
        ("让图中主体眨眼", {"video.generate"}),
        ("让图中主体挥手", {"video.generate"}),
        ("让镜头推进", {"video.generate"}),
        ("把这张图做成动态效果", {"video.generate"}),
    ],
)
def test_current_message_attachments_authorize_bounded_media_edits(
    content: str, expected: set[str]
) -> None:
    assert (
        user_requested_native_actions(
            content,
            has_current_attachments=True,
        )
        & {"image.generate", "video.generate"}
        == expected
    )
    assert not user_requested_native_actions(content) & {"image.generate", "video.generate"}


def test_current_attachment_mark_request_authorizes_reference_regeneration() -> None:
    content = "把这张图片里面的人物的脸上涂一道红色的痕迹"

    assert user_requested_native_actions(
        content,
        has_current_attachments=True,
    ) & {"image.generate", "video.generate"} == {"image.generate"}
    assert not user_requested_native_actions(content) & {"image.generate", "video.generate"}


@pytest.mark.parametrize(
    "content",
    [
        "你按这张图作参考生成一版带红痕的",
        "按前面上传的照片作参考重新生成一版",
        "根据上一轮我上传的图片创作一张新图",
        "把前面我发的照片改成暖色",
    ],
)
def test_previous_user_attachment_reference_opens_image_tool(content: str) -> None:
    assert user_requested_native_actions(content) & {
        "image.generate",
        "video.generate",
    } == {"image.generate"}


@pytest.mark.parametrize(
    "content",
    [
        "分析这张图脸上的红痕",
        "怎么在这张图人物脸上涂一道红色痕迹",
        "不要在这张图人物脸上涂一道红色痕迹",
        "这张图脸上的红痕怎么样",
    ],
)
def test_mark_discussion_or_negation_never_authorizes_generation(content: str) -> None:
    assert not user_requested_native_actions(
        content,
        has_current_attachments=True,
    ) & {"image.generate", "video.generate"}


@pytest.mark.parametrize(
    "content",
    [
        "他在人物脸上涂了一道红色的痕迹",
        "我觉得人物脸上涂一道红痕会更好",
        "这张图展示了在人物脸上涂一道红痕的过程",
    ],
)
def test_declarative_mark_descriptions_never_authorize_generation(content: str) -> None:
    assert not user_requested_native_actions(
        content,
        has_current_attachments=True,
    ) & {"image.generate", "video.generate"}


@pytest.mark.parametrize(
    "content,expected",
    [
        ("让刚才上传的图动起来", {"video.generate"}),
        ("让刚才提供的图片动起来", {"video.generate"}),
        ("按刚才上传的图继续加深红痕", {"image.generate"}),
    ],
)
def test_explicit_previous_user_upload_continuations_open_the_right_tool(
    content: str,
    expected: set[str],
) -> None:
    assert (
        user_requested_native_actions(content)
        & {
            "image.generate",
            "video.generate",
        }
        == expected
    )


@pytest.mark.parametrize(
    "content",
    [
        "按这张图作参考生成一版带红痕的会更好",
        "按这张图作参考生成一版带红痕的是个好主意",
        "按这张图作参考生成一版带红痕的方案不错",
        "按这张图作参考生成一版带红痕的，可以考虑",
        "按这张图作参考生成一版带红痕的,我觉得会更好",
        "按这张图作参考生成一版带红痕的。这样会更好",
        "按这张图作参考生成一版带红痕的,应该不错",
        "按这张图作参考生成一版带红痕的会更好一点",
        "按这张图作参考生成一版带红痕的会更好一些",
        "按这张图作参考生成一版带红痕的可能比较好",
        "按这张图作参考生成一版带红痕的，我觉得不错呀",
        "按这张图作参考生成一版带红痕的，我觉得可行的",
        "按这张图作参考生成一版带红痕的，值得试试",
        "按这张图作参考生成一版带红痕的，好吗？",
        "按这张图作参考生成一版带红痕的，你觉得呢？",
        "按这张图作参考生成一版带红痕的，应该可以吧",
        "按这张图作参考生成一版带红痕的，你觉得这样行吗？",
        "按这张图作参考生成一版带红痕的，这样行不行？",
        "按这张图作参考生成一版带红痕的，要不要这样做？",
        "按这张图作参考生成一版带红痕的，我建议这样做。",
        "按这张图作参考生成一版带红痕的，不妨试试。",
        "按我刚才上传的图片重新生成一版会更好一点...",
        "按我刚才上传的图片重新生成一版，可以吗？",
        "让我刚才提供的图片动起来，可以吗？",
    ],
)
def test_previous_upload_idea_or_evaluation_does_not_authorize_paid_generation(
    content: str,
) -> None:
    assert not user_requested_native_actions(content) & {
        "image.generate",
        "video.generate",
    }


def test_previous_user_upload_reference_accepts_subject_before_time_word() -> None:
    assert user_requested_native_actions("按我刚才上传的图片重新生成一版") & {
        "image.generate",
        "video.generate",
    } == {"image.generate"}


@pytest.mark.parametrize(
    "content",
    [
        "你看这张图，按这张图作参考生成一版带红痕的",
        "我觉得红痕太浅了，按这张图作参考生成一版带红痕的",
        "我认为背景不够亮，请按这张图作参考生成一版更亮的",
    ],
)
def test_later_explicit_image_command_overrides_earlier_observation(content: str) -> None:
    assert user_requested_native_actions(content) & {
        "image.generate",
        "video.generate",
    } == {"image.generate"}


@pytest.mark.parametrize(
    "content,expected",
    [
        ("把刚才那张图背景改成白色", {"image.generate"}),
        ("把刚才生成的那张图背景改成白色", {"image.generate"}),
        ("把上一张背景改成白色", {"image.generate"}),
        ("把刚才那张改成红色", {"image.generate"}),
        ("把上一张改成红色", {"image.generate"}),
        ("把上一张图换成#ff0000", {"image.generate"}),
        ("把上一张照片改成红色", {"image.generate"}),
        ("把上一张相片换成深蓝色", {"image.generate"}),
        ("把上一张详情图背景改成白色", {"image.generate"}),
        ("沿用上一张照片设计新海报", {"image.generate"}),
        ("把前一张照片改成红色", {"image.generate"}),
        ("让上一张照片动起来", {"video.generate"}),
        ("让上一张插画眨眼", {"video.generate"}),
        ("让前一张插画动起来", {"video.generate"}),
        ("让上张插画动起来", {"video.generate"}),
        ("给上一张照片加动态效果", {"video.generate"}),
        ("为上一张照片添加动态效果", {"video.generate"}),
        ("让上一张眨眼", {"video.generate"}),
        ("把上一张做成视频", {"video.generate"}),
        ("把上一张做成动态效果", {"video.generate"}),
    ],
)
def test_previous_generated_image_reference_opens_the_matching_tool(
    content: str, expected: set[str]
) -> None:
    assert (
        user_requested_native_actions(content)
        & {
            "image.generate",
            "video.generate",
        }
        == expected
    )


@pytest.mark.parametrize(
    "content",
    [
        "把上一张表格的背景改白",
        "把上一张发票的背景改白",
        "把上一张PPT的背景改白",
        "把刚才生成的那张表格背景改白",
        "让上一张表格动起来",
        "把上一张发票做成动态效果",
        "把上一张图表的背景改白",
        "把上一张表格改成红色",
        "把上一张PPT换成#ff0000",
        "让上一张视频动起来",
        "把上一张视频做成动态效果",
        "让上一张短片动起来",
        "让上一张动画挥手",
        "把上一张动效做成动态效果",
        "让上一张动态图眨眼",
        "让上一张音频动起来",
        "把上一张录音做成动态效果",
        "沿用上一张视频设计新海报",
        "给上一张视频加动态效果",
        "让前一张视频动起来",
        "让前一张短片挥手",
        "把前一张动画做成动态效果",
        "让前一张音频动起来",
        "让上张视频动起来",
        "把上张音频做成动态效果",
    ],
)
@pytest.mark.parametrize("has_current_attachments", [False, True])
def test_non_image_previous_items_never_authorize_paid_media_tools(
    content: str, has_current_attachments: bool
) -> None:
    assert not user_requested_native_actions(
        content,
        has_current_attachments=has_current_attachments,
    ) & {"image.generate", "video.generate"}


@pytest.mark.parametrize(
    "content",
    [
        "分析上一张的背景修改",
        "评价上一张的背景修改",
        "讨论上一张的背景修改",
        "复盘上一张的背景修改",
        "看看上一张的背景修改",
        "说明上一张的背景修改",
        "审核上一张的背景修改",
    ],
)
def test_read_only_previous_image_language_never_authorizes_paid_media_tools(
    content: str,
) -> None:
    assert not user_requested_native_actions(content) & {
        "image.generate",
        "video.generate",
    }


@pytest.mark.parametrize(
    "content",
    [
        "怎么把上一张改成红色",
        "不要把上一张改成红色",
        "把上一张改成红色可以吗",
        "分析把上一张改成红色",
        "如何沿用上一张照片设计新海报",
        "不要沿用上一张照片设计新海报",
        "怎么给上一张照片加动态效果",
        "不要给上一张照片加动态效果",
    ],
)
def test_previous_image_color_edit_consultation_or_negation_is_not_authority(
    content: str,
) -> None:
    assert not user_requested_native_actions(content) & {
        "image.generate",
        "video.generate",
    }


def test_previous_image_result_tool_inputs_need_no_model_supplied_asset_id() -> None:
    image = ImageGenerateInput.model_validate(
        {"sourceMode": "PREVIOUS_IMAGE_RESULT", "scene": "把背景改成白色"}
    )
    video = VideoGenerateInput.model_validate(
        {"sourceMode": "PREVIOUS_IMAGE_RESULT", "scene": "让主体自然挥手"}
    )
    assert image.source_mode == video.source_mode == "PREVIOUS_IMAGE_RESULT"
    with pytest.raises(ValidationError):
        ImageGenerateInput.model_validate(
            {
                "sourceMode": "PREVIOUS_IMAGE_RESULT",
                "scene": "把背景改成白色",
                "assetId": "1900012345678901005",
            }
        )


def test_previous_user_attachment_tool_inputs_need_no_model_supplied_asset_id() -> None:
    image = ImageGenerateInput.model_validate(
        {"sourceMode": "PREVIOUS_USER_ATTACHMENT", "scene": "参考上一轮上传图生成带红痕版本"}
    )
    video = VideoGenerateInput.model_validate(
        {"sourceMode": "PREVIOUS_USER_ATTACHMENT", "scene": "让上一轮上传图里的主体挥手"}
    )
    assert image.source_mode == video.source_mode == "PREVIOUS_USER_ATTACHMENT"
    with pytest.raises(ValidationError):
        ImageGenerateInput.model_validate(
            {
                "sourceMode": "PREVIOUS_USER_ATTACHMENT",
                "scene": "参考上一轮上传图生成带红痕版本",
                "assetId": "1900012345678901005",
            }
        )


@pytest.mark.parametrize(
    "content",
    [
        "去掉水印的方法",
        "修改图片需要技术",
        "修改图片的人是我",
        "裁剪图片教程",
        "修改背景怎么做",
        "修图多少钱",
        "修改图片效果很好",
        "不要去掉水印",
        "这个水印不要去掉",
        "背景不要修改",
        "把这张图不要做成视频",
        "让它不要动起来",
        "这张图怎么做成视频",
        "把这张图做成视频多少钱",
        "如果可以把这张图做成视频",
        "不要让图中主体眨眼",
        "如果可以让图中主体挥手",
        "让图中主体动起来的方法",
        "添加",
        "再添加",
        "加上",
        "再加上",
        "请再添加   ",
        "不要再添加一棵树",
        "先别再加上一位人物",
        "再添加一棵树需要多少积分？",
        "再添加一棵树怎么操作",
        "再加上远山可以吗？",
        "如果可以再添加一棵树",
        "等我确认后再加上远山",
        "明天再添加一棵树",
        "再添加一棵树会更好",
        "再加上远山，你觉得呢？",
        "用户说\uff1a\u2018再添加一棵树\u2019",
        "\u2018再加上远山\u2019",
    ],
)
def test_current_attachments_do_not_authorize_image_edit_descriptions(content: str) -> None:
    assert not user_requested_native_actions(
        content,
        has_current_attachments=True,
    ) & {"image.generate", "video.generate"}


class NonSubmittingJava:
    def __init__(self) -> None:
        self.actions: list[str] = []
        self.arguments: list[dict[str, Any]] = []

    async def execute_run_action(self, *args: Any) -> JavaInternalResponse:
        action = args[2]["action"]
        self.actions.append(action)
        self.arguments.append(args[2]["arguments"])
        return JavaInternalResponse(
            200,
            {
                "action": action,
                "status": "WAIT_INPUT",
                "data": {"reasonCode": "TEST_NO_TASK_CREATED"},
            },
            "0" * 32,
        )


@pytest.mark.parametrize(
    "content,attachment_asset_ids,expected",
    [
        ("直接生成一张图片", (), {"image.generate"}),
        ("把这个视频剪短一点", (), {"video.generate"}),
        ("生成图片要多少积分", (), set[str]()),
        ("如果素材齐全，帮我生成视频", (), set[str]()),
        ("“生成图片”，这句话是什么意思", (), set[str]()),
        ("不要生成图片，帮我生成视频", (), {"video.generate"}),
        ("请把背景改成白色", ("asset-current",), set[str]()),
        ("请把背景改成白色", (), set[str]()),
        (
            "把这张图片里面的人物的脸上涂一道红色的痕迹",
            ("asset-current",),
            set[str](),
        ),
        ("你按这张图作参考生成一版带红痕的", (), {"image.generate"}),
    ],
)
def test_creative_catalog_and_executor_follow_the_current_user_fence(
    content: str,
    attachment_asset_ids: tuple[str, ...],
    expected: set[str],
) -> None:
    asyncio.run(_check_creative_catalog_and_executor(content, attachment_asset_ids, expected))


async def _check_creative_catalog_and_executor(
    content: str,
    attachment_asset_ids: tuple[str, ...],
    expected: set[str],
) -> None:
    request = ChatRunRequest(
        run_id=uuid4(),
        conversation_id="123",
        user_context_ref="test-creative-command-fence",
        message=ChatMessage(
            message_id="124",
            content=content,
            attachment_asset_ids=attachment_asset_ids,
        ),
        trusted_state=TrustedState(),
        budget=RunBudget(
            max_tool_calls=4,
            deadline_at=datetime.now(UTC) + timedelta(minutes=1),
        ),
    )
    recorder = NonSubmittingJava()
    counters = RuntimeUsageCounters(request, RuntimeHarness())
    with (
        bind_agent_execution(resolve_execution("creative", "1.0.0", tuple(CREATIVE_CAPABILITIES))),
        bind_runtime_usage(counters),
    ):
        tools = NativeTools(
            request,
            await LocalRunContextProvider().load(request),
            cast(JavaAgentActionClientProtocol, recorder),
            counters,
        )
        catalog = {tool.name for tool in tools.catalog()}
        visible = {
            action
            for alias, action in (
                ("image_generate", "image.generate"),
                ("video_generate", "video.generate"),
            )
            if alias in catalog
        }
        assert visible == {"image.generate", "video.generate"}

        payloads = {
            "image.generate": ("image_generate", {"sourceMode": "TEXT_ONLY", "scene": "一只杯子"}),
            "video.generate": (
                "video_generate",
                {"sourceMode": "TEXT_ONLY", "scene": "一只杯子缓慢旋转"},
            ),
        }
        for action, (alias, payload) in payloads.items():
            before = tuple(recorder.actions)
            result = json.loads(
                await tools.execute(
                    ModelToolCall(str(uuid4()), alias, json.dumps(payload, ensure_ascii=False))
                )
            )
            if action in expected:
                assert recorder.actions[-1] == action
                assert result["data"]["reasonCode"] == "TEST_NO_TASK_CREATED"
            else:
                assert tuple(recorder.actions) == before
                assert result["reasonCode"] == "CREATIVE_CONTINUATION_REQUIRED"


def test_previous_upload_followup_reaches_java_with_server_resolved_source_mode() -> None:
    asyncio.run(_check_previous_upload_followup_source_mode())


async def _check_previous_upload_followup_source_mode() -> None:
    request = ChatRunRequest(
        run_id=uuid4(),
        conversation_id="123",
        user_context_ref="test-previous-upload-followup",
        message=ChatMessage(
            message_id="125",
            content="你按这张图作参考生成一版带红痕的",
        ),
        trusted_state=TrustedState(),
        budget=RunBudget(
            max_tool_calls=2,
            deadline_at=datetime.now(UTC) + timedelta(minutes=1),
        ),
    )
    recorder = NonSubmittingJava()
    counters = RuntimeUsageCounters(request, RuntimeHarness())
    with (
        bind_agent_execution(resolve_execution("creative", "1.0.0", tuple(CREATIVE_CAPABILITIES))),
        bind_runtime_usage(counters),
    ):
        tools = NativeTools(
            request,
            await LocalRunContextProvider().load(request),
            cast(JavaAgentActionClientProtocol, recorder),
            counters,
        )
        result = json.loads(
            await tools.execute(
                ModelToolCall(
                    str(uuid4()),
                    "image_generate",
                    json.dumps(
                        {
                            "sourceMode": "PREVIOUS_USER_ATTACHMENT",
                            "scene": "参考上一轮上传图生成带红痕版本",
                        },
                        ensure_ascii=False,
                    ),
                )
            )
        )

    assert recorder.actions == ["image.generate"]
    assert recorder.arguments == [
        {
            "sourceMode": "PREVIOUS_USER_ATTACHMENT",
            "scene": "参考上一轮上传图生成带红痕版本",
            "count": 1,
            "aspectRatio": "1:1",
        }
    ]
    assert result["status"] == "WAIT_INPUT"
    assert result["data"]["reasonCode"] == "TEST_NO_TASK_CREATED"
