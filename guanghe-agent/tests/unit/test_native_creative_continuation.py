"""Exercise creative continuation through the native executor without real tasks."""

import asyncio
import copy
import json
from collections.abc import Generator
from contextlib import contextmanager
from dataclasses import replace
from typing import Any, Literal, cast

import pytest

from app.agent.identity import CREATIVE_CAPABILITIES, bind_agent_execution, resolve_execution
from app.harness.runtime import RuntimeHarness, RuntimeUsageCounters, bind_runtime_usage
from app.integration.java_internal import (
    JavaAgentActionClientProtocol,
    JavaInternalClientError,
    JavaInternalErrorCode,
    JavaInternalResponse,
)
from app.tools.native import NativeTools
from app.tools.native_authorization import user_requested_native_actions
from tests.unit.test_creative_action_review import ReviewGateway, candidate_payload, raw_context
from tests.unit.test_monitor_continuation import request, tool


class CreativeJava:
    def __init__(
        self, *, write_status: Literal["COMPLETED", "WAIT_INPUT", "UNKNOWN"] = "COMPLETED"
    ) -> None:
        self.candidate = candidate_payload()
        self.write_status = write_status
        self.calls: list[str] = []
        self.write_arguments: list[dict[str, Any]] = []
        self.fail_read = False
        self.invalid_memory = False

    async def execute_run_action(self, *args: Any) -> JavaInternalResponse:
        action = args[2]["action"]
        self.calls.append(action)
        if action == "context.read":
            if self.fail_read:
                raise JavaInternalClientError(JavaInternalErrorCode.TIMEOUT)
            data: dict[str, Any] = {"creativeContinuation": copy.deepcopy(self.candidate)}
            if self.invalid_memory:
                data["knowledge"] = {
                    "memory": {
                        "schemaVersion": 1,
                        "projectId": "999",
                        "entries": [],
                        "revision": "1",
                        "historyCutoffAt": None,
                    }
                }
            status = "COMPLETED"
        elif action in {"image.generate", "video.generate"}:
            self.write_arguments.append(copy.deepcopy(args[2]["arguments"]))
            if self.write_status == "UNKNOWN":
                raise JavaInternalClientError(JavaInternalErrorCode.TIMEOUT)
            status = self.write_status
            data = (
                {"reasonCode": "TEST_NO_TASK_CREATED"}
                if status == "WAIT_INPUT"
                else {
                    "taskId": "501",
                    "taskType": "IMAGE" if action == "image.generate" else "VIDEO",
                    "status": "QUEUED",
                    "quotedPoints": 40,
                    "reused": False,
                }
            )
        else:
            raise AssertionError(f"Unexpected action: {action}")
        return JavaInternalResponse(
            200, {"action": action, "status": status, "data": data}, "0" * 32
        )


@contextmanager
def native_tools(
    content: str = "提交",
    *,
    decision: str = "VIDEO",
    write_status: Literal["COMPLETED", "WAIT_INPUT", "UNKNOWN"] = "COMPLETED",
    fail_review: bool = False,
    image_history: bool = False,
    reviewed_source_mode: str | None = None,
    attachment_asset_ids: tuple[str, ...] = (),
) -> Generator[tuple[NativeTools, CreativeJava, ReviewGateway, RuntimeUsageCounters], None, None]:
    req = request(content)
    req = req.model_copy(
        update={
            "message": req.message.model_copy(update={"attachment_asset_ids": attachment_asset_ids})
        }
    )
    java = CreativeJava(write_status=write_status)
    java.candidate["currentAttachmentAssetIds"] = list(attachment_asset_ids)
    gateway = ReviewGateway(
        decision,
        fail=fail_review,
        source_mode=reviewed_source_mode
        or ("PREVIOUS_IMAGE_RESULT" if image_history and decision in {"IMAGE", "VIDEO"} else None),
    )
    ctx = replace(raw_context(content), attachment_asset_ids=attachment_asset_ids)
    if image_history:
        java.candidate["sourceImageAssetId"] = "601"
        texts = {
            "120": "生成一张16:9咖啡新品海报，保留标题今日上新",
            "121": "图片任务已受理。",
            "122": "生成结果在哪？",
            "123": "上一张生成图在任务卡里。",
            "124": "这张图的主体不错。",
            "125": "可以继续调整上一张的比例与文字排版。",
        }
        for item in java.candidate["history"]:
            item["text"] = texts[item["messageId"]]
        assert ctx.conversation_memory is not None
        ctx = replace(
            ctx,
            conversation_memory=ctx.conversation_memory.model_copy(
                update={
                    "messages": tuple(
                        item.model_copy(update={"content": {"text": texts[item.message_id]}})
                        if item.message_id in texts
                        else item
                        for item in ctx.conversation_memory.messages
                    )
                }
            ),
        )
    counters = RuntimeUsageCounters(req, RuntimeHarness())
    with (
        bind_agent_execution(resolve_execution("creative", "1.0.0", tuple(CREATIVE_CAPABILITIES))),
        bind_runtime_usage(counters),
    ):
        yield (
            NativeTools(
                req,
                ctx,
                cast(JavaAgentActionClientProtocol, java),
                counters,
                gateway=gateway,
                creative_review_prompt="Review current USER and original dialogue only",
            ),
            java,
            gateway,
            counters,
        )


def video(**changes: Any) -> Any:
    return tool(
        "video_generate",
        **{
            "sourceMode": "TEXT_ONLY",
            "scene": "奶牛猫拟人化跳舞，按动作时间轴衔接",
            "durationSeconds": 10,
            "aspectRatio": "9:16",
            **changes,
        },
    )


@pytest.mark.parametrize("content", ("那来个10秒的", "做个10秒的吧", "提交"))
def test_context_read_then_review_submits_ten_seconds_with_harness_owned_evidence(
    content: str,
) -> None:
    async def run() -> None:
        with native_tools(content) as (tools, java, gateway, counters):
            assert {"image_generate", "video_generate"}.issubset(
                {item.name for item in tools.catalog()}
            )
            missing = json.loads(await tools.execute(video()))
            assert missing["reasonCode"] == "CREATIVE_CONTINUATION_REQUIRED"
            assert not java.calls
            await tools.execute(tool("context_read"))
            result = json.loads(await tools.execute(video()))
            assert result["status"] == "COMPLETED"
            assert tools.has_task_receipt
            assert not tools.has_unknown_write
            assert len(gateway.messages) == counters.model_call_count == 1
            assert java.calls == ["context.read", "video.generate"]
            assert java.write_arguments[0]["durationSeconds"] == 10
            assert java.write_arguments[0]["continuationReview"] == {
                "currentMessageId": "126",
                "historyFingerprint": "a" * 64,
                "taskType": "VIDEO",
                "decision": "CREATE_NEW",
                "sourceMode": "TEXT_ONLY",
            }

    asyncio.run(run())


def test_model_cannot_forge_continuation_review_arguments() -> None:
    async def run() -> None:
        with native_tools() as (tools, java, gateway, _):
            schema = next(
                item.parameters for item in tools.catalog() if item.name == "video_generate"
            )
            assert "continuationReview" not in cast(dict[str, object], schema["properties"])
            assert schema["additionalProperties"] is False
            await tools.execute(tool("context_read"))
            result = json.loads(
                await tools.execute(video(continuationReview={"decision": "CREATE_NEW"}))
            )
            assert result["reasonCode"] == "TOOL_INPUT_INVALID"
            assert java.calls == ["context.read"]
            assert not gateway.messages

    asyncio.run(run())


def test_wrong_tool_type_does_not_write_and_correct_tool_reuses_the_review() -> None:
    async def run() -> None:
        with native_tools() as (tools, java, gateway, _):
            await tools.execute(tool("context_read"))
            wrong = json.loads(
                await tools.execute(tool("image_generate", sourceMode="TEXT_ONLY", scene="猫"))
            )
            assert wrong["reasonCode"] == "CREATIVE_ACTION_TYPE_MISMATCH"
            assert java.calls == ["context.read"]
            assert json.loads(await tools.execute(video()))["status"] == "COMPLETED"
            assert java.calls == ["context.read", "video.generate"]
            assert len(gateway.messages) == 1

    asyncio.run(run())


@pytest.mark.parametrize(
    ("content", "decision", "reason"),
    (
        ("10秒多少钱", "DISCUSSION", "CREATIVE_EXECUTION_NOT_AUTHORIZED"),
        ("先说怎么改", "DISCUSSION", "CREATIVE_EXECUTION_NOT_AUTHORIZED"),
        ("不要生成", "DISCUSSION", "CREATIVE_EXECUTION_NOT_AUTHORIZED"),
        ('翻译"提交视频"', "DISCUSSION", "CREATIVE_EXECUTION_NOT_AUTHORIZED"),
        ("好", "UNCLEAR", "CREATIVE_EXECUTION_NOT_AUTHORIZED"),
        ("按那个做", "UNCLEAR", "CREATIVE_EXECUTION_NOT_AUTHORIZED"),
        ("提交", "INVALID", "CREATIVE_INTENT_REVIEW_UNAVAILABLE"),
    ),
)
def test_nonexecution_or_invalid_verdict_never_reaches_java_write(
    content: str, decision: str, reason: str
) -> None:
    async def run() -> None:
        with native_tools(content, decision=decision) as (tools, java, gateway, _):
            await tools.execute(tool("context_read"))
            result = json.loads(await tools.execute(video()))
            await tools.execute(video(scene="改变参数不能再次进行授权核对"))
            assert result["data"]["reasonCode"] == reason
            assert "不表示" in result["data"]["message"]
            assert not java.write_arguments
            assert len(gateway.messages) == 1

    asyncio.run(run())


def test_failed_review_is_cached_across_context_reads_and_new_call_ids() -> None:
    async def run() -> None:
        with native_tools(fail_review=True) as (tools, java, gateway, _):
            await tools.execute(tool("context_read"))
            first = json.loads(await tools.execute(video()))
            await tools.execute(tool("context_read"))
            second = json.loads(await tools.execute(video(scene="第二个参数版本")))
            assert (
                first["data"]["reasonCode"]
                == second["data"]["reasonCode"]
                == "CREATIVE_INTENT_REVIEW_UNAVAILABLE"
            )
            assert not java.write_arguments
            assert len(gateway.messages) == 1

    asyncio.run(run())


def test_changed_context_fingerprint_cannot_reuse_an_earlier_verdict() -> None:
    async def run() -> None:
        with native_tools(write_status="WAIT_INPUT") as (tools, java, gateway, _):
            await tools.execute(tool("context_read"))
            await tools.execute(video())
            assert len(java.write_arguments) == 1
            java.candidate["historyFingerprint"] = "b" * 64
            await tools.execute(tool("context_read"))
            result = json.loads(await tools.execute(video(scene="更换参数不能更新旧核对凭据")))
            assert result["data"]["reasonCode"] == "CREATIVE_INTENT_REVIEW_UNAVAILABLE"
            assert len(java.write_arguments) == len(gateway.messages) == 1

    asyncio.run(run())


@pytest.mark.parametrize("mode", ("failed_read", "invalid_memory"))
def test_failed_fresh_context_read_revokes_old_candidate(mode: str) -> None:
    async def run() -> None:
        with native_tools() as (tools, java, gateway, _):
            await tools.execute(tool("context_read"))
            java.fail_read = mode == "failed_read"
            java.invalid_memory = mode == "invalid_memory"
            await tools.execute(tool("context_read"))
            result = json.loads(await tools.execute(video()))
            assert result["reasonCode"] == "CREATIVE_CONTINUATION_REQUIRED"
            assert not java.write_arguments
            assert not gateway.messages

    asyncio.run(run())


def test_receipt_prevents_a_second_paid_task_with_changed_parameters_or_type() -> None:
    async def run() -> None:
        with native_tools() as (tools, java, gateway, _):
            await tools.execute(tool("context_read"))
            first_call = video()
            first = await tools.execute(first_call)
            assert await tools.execute(first_call) == first
            assert await tools.execute(video()) == first
            changed = json.loads(await tools.execute(video(durationSeconds=12)))
            image = json.loads(
                await tools.execute(tool("image_generate", sourceMode="TEXT_ONLY", scene="猫"))
            )
            assert changed["reasonCode"] == image["reasonCode"] == "CREATIVE_TASK_ALREADY_ACCEPTED"
            assert len(java.write_arguments) == len(gateway.messages) == 1

    asyncio.run(run())


def test_unknown_write_fences_new_ids_parameters_types_and_context_reads() -> None:
    async def run() -> None:
        with native_tools(write_status="UNKNOWN") as (tools, java, gateway, _):
            await tools.execute(tool("context_read"))
            first = await tools.execute(video())
            assert json.loads(first)["data"]["reasonCode"] == "ACTION_STATUS_UNKNOWN"
            assert tools.has_unknown_write
            await tools.execute(tool("context_read"))
            subsequent = [
                json.loads(await tools.execute(video(durationSeconds=12))),
                json.loads(
                    await tools.execute(tool("image_generate", sourceMode="TEXT_ONLY", scene="猫"))
                ),
            ]
            for result in subsequent:
                assert result["status"] == "WAIT_INPUT"
                assert result["data"]["reasonCode"] == "ACTION_STATUS_UNKNOWN"
            assert tools.has_unknown_write
            assert not tools.has_task_receipt
            assert len(java.write_arguments) == len(gateway.messages) == 1

    asyncio.run(run())


@pytest.mark.parametrize(
    "content",
    (
        "把16:9改成正方形，内容不变",
        "文字太多了，没有重点，修改字体排版",
    ),
)
def test_explicit_image_edits_with_criticism_bind_the_unique_java_source(content: str) -> None:
    async def run() -> None:
        with native_tools(content, decision="IMAGE", image_history=True) as (
            tools,
            java,
            gateway,
            counters,
        ):
            await tools.execute(tool("context_read"))
            result = json.loads(
                await tools.execute(
                    tool(
                        "image_generate",
                        sourceMode="PREVIOUS_IMAGE_RESULT",
                        scene=content,
                        aspectRatio="1:1",
                    )
                )
            )
            assert result["status"] == "COMPLETED"
            assert java.calls == ["context.read", "image.generate"]
            assert java.write_arguments[0]["sourceMode"] == "PREVIOUS_IMAGE_RESULT"
            assert java.write_arguments[0]["continuationReview"] == {
                "currentMessageId": "126",
                "historyFingerprint": "a" * 64,
                "taskType": "IMAGE",
                "decision": "CREATE_NEW",
                "sourceImageAssetId": "601",
                "sourceMode": "PREVIOUS_IMAGE_RESULT",
            }
            payload = json.loads(gateway.messages[0][1].content)
            assert payload["currentUser"] == content
            assert payload["history"][0]["text"].startswith("生成一张16:9咖啡新品海报")
            assert "sourceImageAssetId" not in payload
            assert counters.model_call_count == 1

    asyncio.run(run())


@pytest.mark.parametrize(
    "content,action,decision",
    (
        ("再添加一个大狗，这只小狗靠着那只大狗", "image_generate", "IMAGE"),
        ("请把背景改成白色", "image_generate", "IMAGE"),
        ("直接生成一张图片", "image_generate", "IMAGE"),
        ("让它动起来", "video_generate", "VIDEO"),
        ("制作一个视频", "video_generate", "VIDEO"),
    ),
)
def test_current_selected_image_always_requires_review_even_for_a_direct_command(
    content: str, action: str, decision: str
) -> None:
    async def run() -> None:
        assert action.replace("_", ".") in user_requested_native_actions(
            content, has_current_attachments=True
        )
        with native_tools(
            content,
            decision=decision,
            image_history=True,
            reviewed_source_mode="CURRENT_ATTACHMENTS",
            attachment_asset_ids=("601",),
        ) as (tools, java, gateway, counters):
            missing = json.loads(
                await tools.execute(tool(action, sourceMode="CURRENT_ATTACHMENTS", scene=content))
            )
            assert missing["reasonCode"] == "CREATIVE_CONTINUATION_REQUIRED"
            assert not java.calls
            await tools.execute(tool("context_read"))
            result = json.loads(
                await tools.execute(tool(action, sourceMode="CURRENT_ATTACHMENTS", scene=content))
            )

            assert result["status"] == "COMPLETED"
            assert java.calls == ["context.read", action.replace("_", ".")]
            arguments = java.write_arguments[0]
            assert arguments["sourceMode"] == "CURRENT_ATTACHMENTS"
            assert arguments["scene"] == content
            assert arguments["continuationReview"] == {
                "currentMessageId": "126",
                "historyFingerprint": "a" * 64,
                "taskType": decision,
                "decision": "CREATE_NEW",
                "sourceMode": "CURRENT_ATTACHMENTS",
            }
            assert (
                not {
                    "sourceImageAssetId",
                    "referenceAssetIds",
                    "assetId",
                }
                & arguments.keys()
            )
            assert tools.request.message.attachment_asset_ids == ("601",)
            assert len(gateway.messages) == counters.model_call_count == 1

    asyncio.run(run())


@pytest.mark.parametrize(
    "content",
    (
        "背景淡一点",
        "主体小一些",
        "右边多留点空间",
        "换成暖色",
        "把左边那个人拿掉",
        "太挤了，字距松一点",
    ),
)
def test_selected_image_edits_use_semantic_review_beyond_the_direct_command_vocabulary(
    content: str,
) -> None:
    async def run() -> None:
        assert "image.generate" not in user_requested_native_actions(
            content, has_current_attachments=True
        )
        with native_tools(
            content,
            decision="IMAGE",
            reviewed_source_mode="CURRENT_ATTACHMENTS",
            attachment_asset_ids=("701",),
        ) as (tools, java, gateway, counters):
            java.candidate["history"] = []
            java.candidate["historyIncomplete"] = True
            missing = json.loads(
                await tools.execute(
                    tool("image_generate", sourceMode="CURRENT_ATTACHMENTS", scene=content)
                )
            )
            assert missing["reasonCode"] == "CREATIVE_CONTINUATION_REQUIRED"
            assert not java.calls
            await tools.execute(tool("context_read"))
            result = json.loads(
                await tools.execute(
                    tool("image_generate", sourceMode="CURRENT_ATTACHMENTS", scene=content)
                )
            )
            assert result["status"] == "COMPLETED"
            assert java.calls == ["context.read", "image.generate"]
            assert java.write_arguments[0]["sourceMode"] == "CURRENT_ATTACHMENTS"
            assert java.write_arguments[0]["continuationReview"] == {
                "currentMessageId": "126",
                "historyFingerprint": "a" * 64,
                "taskType": "IMAGE",
                "decision": "CREATE_NEW",
                "sourceMode": "CURRENT_ATTACHMENTS",
            }
            assert len(gateway.messages) == counters.model_call_count == 1
            payload = json.loads(gateway.messages[0][1].content)
            assert payload["currentUser"] == content
            assert payload["currentAttachmentCount"] == 1
            assert payload["history"] == []
            assert "701" not in str(payload)

    asyncio.run(run())


@pytest.mark.parametrize("content", ("背景淡一点需要多少积分", "先不要把左边那个人拿掉"))
def test_selected_image_consultation_or_negation_never_becomes_a_paid_edit(content: str) -> None:
    async def run() -> None:
        with native_tools(content, decision="DISCUSSION", attachment_asset_ids=("701",)) as (
            tools,
            java,
            gateway,
            counters,
        ):
            java.candidate["history"] = []
            await tools.execute(tool("context_read"))
            result = json.loads(
                await tools.execute(
                    tool(
                        "image_generate",
                        sourceMode="CURRENT_ATTACHMENTS",
                        scene="背景淡一点，把左边那个人拿掉",
                    )
                )
            )
            assert result["data"]["reasonCode"] == "CREATIVE_EXECUTION_NOT_AUTHORIZED"
            assert not java.write_arguments
            assert len(gateway.messages) == counters.model_call_count == 1
            assert json.loads(gateway.messages[0][1].content)["currentUser"] == content

    asyncio.run(run())


@pytest.mark.parametrize(
    "content",
    (
        "哈哈这狗再加一只就能开狗场了",
        "这构图绝了，闭着眼画的吧",
        "这也太丑了哈哈",
        "我没让你修改",
        "再添加一只就能开狗场了",
    ),
)
@pytest.mark.parametrize("decision", ("DISCUSSION", "UNCLEAR"))
def test_selected_image_criticism_or_jokes_cannot_authorize_a_paid_edit(
    content: str, decision: str
) -> None:
    async def run() -> None:
        with native_tools(content, decision=decision, attachment_asset_ids=("701",)) as (
            tools,
            java,
            gateway,
            counters,
        ):
            java.candidate["history"] = []
            await tools.execute(tool("context_read"))
            result = json.loads(
                await tools.execute(
                    tool(
                        "image_generate",
                        sourceMode="CURRENT_ATTACHMENTS",
                        scene="添加一只大狗并优化构图",
                    )
                )
            )
            assert result["data"]["reasonCode"] == "CREATIVE_EXECUTION_NOT_AUTHORIZED"
            assert not java.write_arguments
            assert len(gateway.messages) == counters.model_call_count == 1
            assert json.loads(gateway.messages[0][1].content)["currentUser"] == content

    asyncio.run(run())


def test_direct_creation_without_current_attachments_keeps_the_existing_path() -> None:
    async def run() -> None:
        with native_tools("直接生成一张图片", decision="IMAGE") as (tools, java, gateway, counters):
            result = json.loads(
                await tools.execute(tool("image_generate", sourceMode="TEXT_ONLY", scene="一棵树"))
            )
            assert result["status"] == "COMPLETED"
            assert java.calls == ["image.generate"]
            assert "continuationReview" not in java.write_arguments[0]
            assert not gateway.messages
            assert counters.model_call_count == 0

    asyncio.run(run())


@pytest.mark.parametrize("mismatch", ("request_only", "context_only", "different_ids"))
def test_direct_command_cannot_bypass_mismatched_current_attachment_authority(
    mismatch: str,
) -> None:
    async def run() -> None:
        with native_tools(
            "直接生成一张图片",
            decision="IMAGE",
            reviewed_source_mode="CURRENT_ATTACHMENTS",
            attachment_asset_ids=() if mismatch == "context_only" else ("701",),
        ) as (tools, java, gateway, counters):
            tools.context = replace(
                tools.context,
                attachment_asset_ids=()
                if mismatch == "request_only"
                else ("701",)
                if mismatch == "context_only"
                else ("702",),
            )
            java.candidate["currentAttachmentAssetIds"] = ["701"]
            missing = json.loads(
                await tools.execute(
                    tool("image_generate", sourceMode="CURRENT_ATTACHMENTS", scene="一棵树")
                )
            )
            assert missing["reasonCode"] == "CREATIVE_CONTINUATION_REQUIRED"
            assert not java.calls
            await tools.execute(tool("context_read"))
            result = json.loads(
                await tools.execute(
                    tool("image_generate", sourceMode="CURRENT_ATTACHMENTS", scene="一棵树")
                )
            )
            assert result["data"]["reasonCode"] == "CREATIVE_INTENT_REVIEW_UNAVAILABLE"
            assert not java.write_arguments
            assert not gateway.messages
            assert counters.model_call_count == 0

    asyncio.run(run())


def test_direct_selected_image_command_does_not_retry_a_failed_review() -> None:
    async def run() -> None:
        with native_tools(
            "请把背景改成白色",
            decision="IMAGE",
            reviewed_source_mode="CURRENT_ATTACHMENTS",
            attachment_asset_ids=("701",),
            fail_review=True,
        ) as (tools, java, gateway, counters):
            for scene in ("白色背景", "纯白背景"):
                await tools.execute(tool("context_read"))
                result = json.loads(
                    await tools.execute(
                        tool("image_generate", sourceMode="CURRENT_ATTACHMENTS", scene=scene)
                    )
                )
                assert result["data"]["reasonCode"] == "CREATIVE_INTENT_REVIEW_UNAVAILABLE"
            assert not java.write_arguments
            assert len(gateway.messages) == counters.model_call_count == 1

    asyncio.run(run())


@pytest.mark.parametrize("wrong_mode", ("TEXT_ONLY", "PREVIOUS_IMAGE_RESULT"))
def test_reviewed_current_attachment_cannot_be_substituted_with_another_source(
    wrong_mode: str,
) -> None:
    async def run() -> None:
        with native_tools(
            "主体小一些",
            decision="IMAGE",
            image_history=True,
            reviewed_source_mode="CURRENT_ATTACHMENTS",
            attachment_asset_ids=("701",),
        ) as (tools, java, gateway, _):
            await tools.execute(tool("context_read"))
            rejected = json.loads(
                await tools.execute(
                    tool("image_generate", sourceMode=wrong_mode, scene="主体小一些")
                )
            )
            assert rejected["reasonCode"] == "CREATIVE_SOURCE_MODE_MISMATCH"
            assert not java.write_arguments
            accepted = json.loads(
                await tools.execute(
                    tool("image_generate", sourceMode="CURRENT_ATTACHMENTS", scene="主体小一些")
                )
            )
            assert accepted["status"] == "COMPLETED"
            evidence = java.write_arguments[0]["continuationReview"]
            assert evidence["sourceMode"] == "CURRENT_ATTACHMENTS"
            assert "sourceImageAssetId" not in evidence
            assert len(gateway.messages) == 1

    asyncio.run(run())


def test_changed_current_attachment_candidate_cannot_reuse_the_review() -> None:
    async def run() -> None:
        with native_tools(
            "右边多留点空间",
            decision="IMAGE",
            reviewed_source_mode="CURRENT_ATTACHMENTS",
            attachment_asset_ids=("701",),
            write_status="WAIT_INPUT",
        ) as (tools, java, gateway, _):
            await tools.execute(tool("context_read"))
            await tools.execute(
                tool("image_generate", sourceMode="CURRENT_ATTACHMENTS", scene="右边多留点空间")
            )
            assert len(java.write_arguments) == 1
            java.candidate["currentAttachmentAssetIds"] = ["702"]
            await tools.execute(tool("context_read"))
            result = json.loads(
                await tools.execute(
                    tool(
                        "image_generate", sourceMode="CURRENT_ATTACHMENTS", scene="右侧多留一些空间"
                    )
                )
            )
            assert result["data"]["reasonCode"] == "CREATIVE_INTENT_REVIEW_UNAVAILABLE"
            assert len(gateway.messages) == len(java.write_arguments) == 1

    asyncio.run(run())


def test_previous_image_reference_keeps_its_source_with_a_current_selection() -> None:
    async def run() -> None:
        with native_tools(
            "上一张主体小一些",
            decision="IMAGE",
            image_history=True,
            reviewed_source_mode="PREVIOUS_IMAGE_RESULT",
            attachment_asset_ids=("701",),
        ) as (tools, java, gateway, _):
            await tools.execute(tool("context_read"))
            rejected = json.loads(
                await tools.execute(
                    tool("image_generate", sourceMode="CURRENT_ATTACHMENTS", scene="主体小一些")
                )
            )
            assert rejected["reasonCode"] == "CREATIVE_SOURCE_MODE_MISMATCH"
            assert not java.write_arguments
            result = json.loads(
                await tools.execute(
                    tool("image_generate", sourceMode="PREVIOUS_IMAGE_RESULT", scene="主体小一些")
                )
            )
            assert result["status"] == "COMPLETED"
            assert java.write_arguments[0]["continuationReview"]["sourceImageAssetId"] == "601"
            assert len(gateway.messages) == 1

    asyncio.run(run())


def test_current_image_animation_can_use_review_without_prior_dialogue() -> None:
    async def run() -> None:
        with native_tools(
            "让远处的树轻轻摆动",
            decision="VIDEO",
            reviewed_source_mode="CURRENT_ATTACHMENTS",
            attachment_asset_ids=("701",),
        ) as (tools, java, gateway, counters):
            java.candidate["history"] = []
            await tools.execute(tool("context_read"))
            result = json.loads(await tools.execute(video(sourceMode="CURRENT_ATTACHMENTS")))
            assert result["status"] == "COMPLETED"
            assert java.write_arguments[0]["sourceMode"] == "CURRENT_ATTACHMENTS"
            evidence = java.write_arguments[0]["continuationReview"]
            assert evidence["sourceMode"] == "CURRENT_ATTACHMENTS"
            assert "sourceImageAssetId" not in evidence
            assert len(gateway.messages) == counters.model_call_count == 1

    asyncio.run(run())


@pytest.mark.parametrize(
    "source_mode",
    ("TEXT_ONLY", "CURRENT_ATTACHMENTS", "PREVIOUS_USER_ATTACHMENT", "CATALOG_PRODUCT"),
)
def test_implicit_image_edit_cannot_replace_its_source_with_another_mode(source_mode: str) -> None:
    async def run() -> None:
        with native_tools("把16:9改成正方形，内容不变", decision="IMAGE", image_history=True) as (
            tools,
            java,
            gateway,
            _,
        ):
            await tools.execute(tool("context_read"))
            rejected = json.loads(
                await tools.execute(
                    tool("image_generate", sourceMode=source_mode, scene="改成方图，保留原内容")
                )
            )
            assert rejected["reasonCode"] == "CREATIVE_SOURCE_MODE_MISMATCH"
            assert not java.write_arguments
            accepted = json.loads(
                await tools.execute(
                    tool(
                        "image_generate",
                        sourceMode="PREVIOUS_IMAGE_RESULT",
                        scene="改成方图，保留原内容",
                    )
                )
            )
            assert accepted["status"] == "COMPLETED"
            assert len(gateway.messages) == len(java.write_arguments) == 1

    asyncio.run(run())


def test_missing_or_ambiguous_source_requires_clarification() -> None:
    async def run() -> None:
        with native_tools("把16:9改成正方形，内容不变", decision="IMAGE", image_history=True) as (
            tools,
            java,
            _,
            _,
        ):
            # Java returns null for either no eligible image or multiple eligible images.
            java.candidate["sourceImageAssetId"] = None
            await tools.execute(tool("context_read"))
            result = json.loads(
                await tools.execute(
                    tool("image_generate", sourceMode="PREVIOUS_IMAGE_RESULT", scene="改成正方形")
                )
            )
            assert result["status"] == "WAIT_INPUT"
            assert result["data"]["reasonCode"] == "CREATIVE_IMAGE_REFERENCE_UNAVAILABLE"
            assert tools.wait_input
            assert not java.write_arguments

    asyncio.run(run())


@pytest.mark.parametrize("field", ("sourceImageAssetId", "continuationReview", "assetId"))
def test_model_cannot_supply_image_source_or_review_evidence(field: str) -> None:
    async def run() -> None:
        with native_tools("修改字体排版", decision="IMAGE", image_history=True) as (
            tools,
            java,
            gateway,
            _,
        ):
            await tools.execute(tool("context_read"))
            result = json.loads(
                await tools.execute(
                    tool(
                        "image_generate",
                        sourceMode="PREVIOUS_IMAGE_RESULT",
                        scene="修改字体排版",
                        **{field: "999"},
                    )
                )
            )
            assert result["reasonCode"] == "TOOL_INPUT_INVALID"
            assert not java.write_arguments
            assert not gateway.messages

    asyncio.run(run())


def test_changed_source_image_cannot_reuse_the_earlier_review() -> None:
    async def run() -> None:
        with native_tools(
            "把16:9改成正方形，内容不变",
            decision="IMAGE",
            image_history=True,
            write_status="WAIT_INPUT",
        ) as (tools, java, gateway, _):
            await tools.execute(tool("context_read"))
            await tools.execute(
                tool("image_generate", sourceMode="PREVIOUS_IMAGE_RESULT", scene="修改成方图")
            )
            assert len(java.write_arguments) == 1
            java.candidate["sourceImageAssetId"] = "602"
            await tools.execute(tool("context_read"))
            result = json.loads(
                await tools.execute(
                    tool(
                        "image_generate",
                        sourceMode="PREVIOUS_IMAGE_RESULT",
                        scene="修正比例，保留内容",
                    )
                )
            )
            assert result["data"]["reasonCode"] == "CREATIVE_INTENT_REVIEW_UNAVAILABLE"
            assert len(java.write_arguments) == len(gateway.messages) == 1

    asyncio.run(run())


@pytest.mark.parametrize(
    "content",
    (
        "文字太多了，先给我建议，暂不修改",
        "不要改比例",
        "等我确认后再改字体",
        "如果预算够就改成正方形",
        "这个字体为什么这么乱",
        "先不生成了",
    ),
)
def test_criticism_with_negation_or_advice_never_executes_a_paid_edit(content: str) -> None:
    async def run() -> None:
        with native_tools(content, decision="DISCUSSION", image_history=True) as (
            tools,
            java,
            gateway,
            _,
        ):
            await tools.execute(tool("context_read"))
            result = json.loads(
                await tools.execute(
                    tool(
                        "image_generate", sourceMode="PREVIOUS_IMAGE_RESULT", scene="模型想擅自改图"
                    )
                )
            )
            assert result["status"] == "WAIT_INPUT"
            assert result["data"]["reasonCode"] == "CREATIVE_EXECUTION_NOT_AUTHORIZED"
            assert not java.write_arguments
            assert len(gateway.messages) == 1

    asyncio.run(run())


def test_incomplete_history_cannot_authorize_edit_even_with_a_unique_image() -> None:
    async def run() -> None:
        with native_tools("修改字体排版", decision="IMAGE", image_history=True) as (
            tools,
            java,
            gateway,
            _,
        ):
            java.candidate["historyIncomplete"] = True
            await tools.execute(tool("context_read"))
            result = json.loads(
                await tools.execute(
                    tool("image_generate", sourceMode="PREVIOUS_IMAGE_RESULT", scene="修改字体排版")
                )
            )
            assert result["status"] == "UNAVAILABLE"
            assert not java.write_arguments
            assert not gateway.messages

    asyncio.run(run())


@pytest.mark.parametrize(
    "source_mode",
    (
        "TEXT_ONLY",
        "CURRENT_ATTACHMENTS",
        "PREVIOUS_USER_ATTACHMENT",
        "CATALOG_PRODUCT",
        "PRODUCT_MATERIALS",
    ),
)
def test_image_to_video_cannot_replace_reviewed_image_with_model_chosen_mode(
    source_mode: str,
) -> None:
    async def run() -> None:
        with native_tools("让它动起来", decision="VIDEO", image_history=True) as (
            tools,
            java,
            gateway,
            counters,
        ):
            await tools.execute(tool("context_read"))
            rejected = json.loads(await tools.execute(video(sourceMode=source_mode)))
            assert rejected["reasonCode"] == "CREATIVE_SOURCE_MODE_MISMATCH"
            assert not java.write_arguments
            accepted = json.loads(
                await tools.execute(
                    video(
                        sourceMode="PREVIOUS_IMAGE_RESULT",
                        scene="让上一张咖啡海报中的主体自然动起来",
                    )
                )
            )
            assert accepted["status"] == "COMPLETED"
            assert java.calls == ["context.read", "video.generate"]
            assert java.write_arguments[0]["continuationReview"] == {
                "currentMessageId": "126",
                "historyFingerprint": "a" * 64,
                "taskType": "VIDEO",
                "decision": "CREATE_NEW",
                "sourceMode": "PREVIOUS_IMAGE_RESULT",
                "sourceImageAssetId": "601",
            }
            assert len(gateway.messages) == counters.model_call_count == 1

    asyncio.run(run())


@pytest.mark.parametrize("source_mode", ("TEXT_ONLY", "PREVIOUS_IMAGE_RESULT"))
def test_image_to_video_missing_or_ambiguous_source_cannot_fallback_to_text(
    source_mode: str,
) -> None:
    async def run() -> None:
        with native_tools("让它动起来", decision="VIDEO", image_history=True) as (
            tools,
            java,
            _,
            _,
        ):
            java.candidate["sourceImageAssetId"] = None
            await tools.execute(tool("context_read"))
            result = json.loads(await tools.execute(video(sourceMode=source_mode)))
            assert result["status"] == "WAIT_INPUT"
            assert result["data"]["reasonCode"] == "CREATIVE_IMAGE_REFERENCE_UNAVAILABLE"
            assert tools.wait_input
            assert not java.write_arguments

    asyncio.run(run())


def test_text_only_video_review_cannot_be_substituted_with_image_by_main_model() -> None:
    async def run() -> None:
        with native_tools("做个10秒的吧", decision="VIDEO") as (tools, java, gateway, _):
            await tools.execute(tool("context_read"))
            rejected = json.loads(await tools.execute(video(sourceMode="PREVIOUS_IMAGE_RESULT")))
            assert rejected["reasonCode"] == "CREATIVE_SOURCE_MODE_MISMATCH"
            assert not java.write_arguments
            assert json.loads(await tools.execute(video()))["status"] == "COMPLETED"
            review = java.write_arguments[0]["continuationReview"]
            assert review["sourceMode"] == "TEXT_ONLY"
            assert "sourceImageAssetId" not in review
            assert len(gateway.messages) == 1

    asyncio.run(run())


@pytest.mark.parametrize(
    "content",
    (
        "先不要让它动起来",
        "先说怎么让它动起来，暂不生成",
        "如果我确认就让它动起来",
    ),
)
def test_image_animation_negation_and_advice_do_not_create_video(content: str) -> None:
    async def run() -> None:
        with native_tools(content, decision="DISCUSSION", image_history=True) as (
            tools,
            java,
            gateway,
            _,
        ):
            await tools.execute(tool("context_read"))
            result = json.loads(await tools.execute(video(sourceMode="PREVIOUS_IMAGE_RESULT")))
            assert result["status"] == "WAIT_INPUT"
            assert result["data"]["reasonCode"] == "CREATIVE_EXECUTION_NOT_AUTHORIZED"
            assert not java.write_arguments
            assert len(gateway.messages) == 1

    asyncio.run(run())
