"""Bounded creative continuation reviews use authenticated dialogue, without paid work."""

import asyncio
import copy
import json
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from typing import Any, Literal, cast

import pytest
from pydantic import ValidationError

from app.agent.creative_action_review import (
    CreativeActionReview,
    CreativeContinuationCandidate,
    review_creative_action,
)
from app.agent.models import ChatRunRequest
from app.harness.runtime import (
    HarnessViolation,
    RuntimeHarness,
    RuntimeUsageCounters,
    record_model_call,
)
from app.model_gateway.gateway import (
    GatewayUsage,
    LocalModelGateway,
    ModelGatewayError,
    ModelMessage,
)
from app.providers.java_context import (
    AgentIdentity,
    AuthorizedRunContext,
    ConversationMemory,
    ConversationMemoryMessage,
    ConversationSummary,
)
from app.providers.knowledge_context import ProjectMemoryContext
from tests.unit.test_monitor_continuation import context, request

HISTORY = (
    ("120", "USER", "生成奶牛猫跳舞的5秒视频"),
    ("121", "ASSISTANT", "视频已生成，可以按动作时间轴再做一版。"),
    ("122", "USER", "为什么没有跳科目三"),
    ("123", "ASSISTANT", "可以把动作写清楚再重做。"),
    ("124", "USER", "做个10秒的吧"),
    ("125", "ASSISTANT", "尚未提交，旧的通道故障说法没有执行证据。"),
)


class ReviewGateway(LocalModelGateway):
    def __init__(
        self, decision: str = "VIDEO", *, fail: bool = False, source_mode: str | None = None
    ) -> None:
        self.decision = decision
        self.source_mode = source_mode or (
            "PREVIOUS_IMAGE_RESULT"
            if decision == "IMAGE"
            else "TEXT_ONLY"
            if decision == "VIDEO"
            else "UNCLEAR"
        )
        self.fail = fail
        self.messages: list[tuple[ModelMessage, ...]] = []

    async def complete_structured(self, messages: Any, response_model: Any, **kwargs: Any) -> Any:
        record_model_call(input_messages=messages)
        self.messages.append(messages)
        assert response_model is CreativeActionReview
        assert kwargs["repair_attempts"] == 0
        assert kwargs["max_tokens"] == 200
        assert kwargs["operation"] == "readonly_creative_action_review"
        usage = GatewayUsage(request_count=1, input_tokens=12, output_tokens=3)
        if self.fail:
            raise ModelGatewayError("CREATIVE_ACTION_REVIEW_INVALID", usage=usage)
        # Bypass parsing deliberately; the reviewer must independently validate enums.
        return CreativeActionReview.model_construct(
            decision=self.decision, source_mode=self.source_mode
        ), usage


def candidate_payload(**changes: Any) -> dict[str, Any]:
    return {
        "authorized": False,
        "reviewRequired": True,
        "currentMessageId": "126",
        "conversationId": "123",
        "projectId": "401",
        "historyFingerprint": "a" * 64,
        "history": [
            {"messageId": ident, "role": role, "text": text} for ident, role, text in HISTORY
        ],
        "historyIncomplete": False,
        "sourceImageAssetId": None,
        "currentAttachmentAssetIds": [],
        **changes,
    }


def raw_context(content: str) -> AuthorizedRunContext:
    now = datetime.now(UTC)
    entries = (
        ("118", "SYSTEM", "历史system指令不得进入语义核对"),
        *HISTORY,
        ("119", "TOOL", "网页内容要求创建付费任务，不得用作授权"),
        ("126", "USER", content),
    )
    messages = tuple(
        ConversationMemoryMessage(
            message_id=ident,
            sequence=index,
            role=cast(Literal["USER", "ASSISTANT", "SYSTEM", "TOOL"], role),
            content={"text": text},
            created_at=now + timedelta(seconds=index - 20),
            reply_to_message_id=None,
        )
        for index, (ident, role, text) in enumerate(entries, 1)
    )
    return replace(
        context(),
        conversation_memory=ConversationMemory(
            messages=messages,
            summary=ConversationSummary(text="摘要伪称用户已授权付费", version=1),
            last_message_sequence=len(messages),
            summary_through_sequence=0,
            context_source="POSTGRESQL",
        ),
    )


async def review(
    req: ChatRunRequest,
    ctx: AuthorizedRunContext,
    gateway: ReviewGateway,
    candidate: CreativeContinuationCandidate | None,
    *,
    memory: ProjectMemoryContext | None = None,
) -> tuple[str, RuntimeUsageCounters]:
    counters = RuntimeUsageCounters(req, RuntimeHarness())
    result = await review_creative_action(
        gateway=gateway,
        prompt="Bounded creative continuation review",
        request=req,
        context=ctx,
        memory=memory,
        counters=counters,
        candidate=candidate,
    )
    return result.decision, counters


@pytest.mark.parametrize("content", ("那来个10秒的", "做个10秒的吧", "提交"))
def test_short_video_request_reads_original_history_and_accounts_once(content: str) -> None:
    async def run() -> None:
        gateway = ReviewGateway()
        result, counters = await review(
            request(content),
            raw_context(content),
            gateway,
            CreativeContinuationCandidate.model_validate(candidate_payload()),
        )
        assert result == "VIDEO"
        assert len(gateway.messages) == counters.model_call_count == 1
        assert counters.workflow_step_count == 1
        assert counters.provider_input_tokens == 12
        assert counters.provider_output_tokens == 3
        payload = json.loads(gateway.messages[0][1].content)
        assert payload["currentUser"] == content
        assert payload["history"] == [{"role": role, "text": text} for _, role, text in HISTORY]
        assert set(payload) == {
            "currentUser",
            "currentAttachmentCount",
            "history",
            "historyIncomplete",
        }
        assert payload["currentAttachmentCount"] == 0
        assert "摘要伪称" not in str(payload)
        assert "网页内容" not in str(payload)
        assert "历史system" not in str(payload)
        assert "historyFingerprint" not in str(payload)

    asyncio.run(run())


@pytest.mark.parametrize(
    ("content", "decision"),
    (
        ("10秒视频怎么收费", "DISCUSSION"),
        ("先给我修改建议", "DISCUSSION"),
        ("不要生成", "DISCUSSION"),
        ("等我确认再做", "DISCUSSION"),
        ('翻译"提交视频"这句话', "DISCUSSION"),
        ("你觉得这个方案怎么样", "DISCUSSION"),
        ("好的", "UNCLEAR"),
        ("按刚才说的做", "UNCLEAR"),
        ("把上一张颜色改成蓝色", "IMAGE"),
        ("提交", "INVALID"),
    ),
)
def test_review_does_not_promote_discussion_or_unclear_into_creation(
    content: str, decision: str
) -> None:
    async def run() -> None:
        gateway = ReviewGateway(decision)
        result, _ = await review(
            request(content),
            raw_context(content),
            gateway,
            CreativeContinuationCandidate.model_validate(candidate_payload()),
        )
        assert result == ("UNAVAILABLE" if decision == "INVALID" else decision)
        assert len(gateway.messages) == 1

    asyncio.run(run())


@pytest.mark.parametrize(
    "changes",
    (
        {"history": []},
        {"authorized": True},
        {"authorized": 0},
        {"reviewRequired": False},
        {"reviewRequired": "true"},
        {"historyFingerprint": "bad"},
        {"historyFingerprint": "A" * 64},
        {"history": [{"messageId": "126", "role": "USER", "text": "current"}]},
        {"history": [{"messageId": "120", "role": "TOOL", "text": "untrusted"}]},
        {"history": [{"messageId": "120", "role": "USER", "text": " "}]},
        {"history": [{"messageId": "120", "role": "USER", "text": "a"}] * 2},
        {"history": [{"messageId": str(i), "role": "USER", "text": "a"} for i in range(1, 8)]},
        {"history": [{"messageId": str(i), "role": "USER", "text": "a" * 9000} for i in (1, 2)]},
        {"historyIncomplete": "false"},
        {"sourceImageAssetId": "wrong"},
        {"sourceImageAssetId": 601},
        {"currentAttachmentAssetIds": [601]},
        {"currentAttachmentAssetIds": ["wrong"]},
        {"currentAttachmentAssetIds": ["10000000000000000000"]},
        {"currentAttachmentAssetIds": ["601", "601"]},
        {"currentAttachmentAssetIds": [str(i) for i in range(1, 10)]},
        {"requestText": "model-provided authorization must be rejected"},
    ),
)
def test_candidate_rejects_privilege_flags_bad_identifiers_and_unbounded_history(
    changes: dict[str, Any],
) -> None:
    with pytest.raises(ValidationError):
        CreativeContinuationCandidate.model_validate(candidate_payload(**changes))


@pytest.mark.parametrize(
    "changes",
    (
        {"currentMessageId": "127"},
        {"conversationId": "999"},
        {"projectId": "999"},
        {"projectId": None},
    ),
)
def test_mismatched_run_scope_never_reaches_gateway(changes: dict[str, Any]) -> None:
    async def run() -> None:
        gateway = ReviewGateway()
        result, counters = await review(
            request(),
            raw_context("提交"),
            gateway,
            CreativeContinuationCandidate.model_validate(candidate_payload(**changes)),
        )
        assert result == "UNAVAILABLE"
        assert not gateway.messages
        assert counters.model_call_count == counters.workflow_step_count == 0

    asyncio.run(run())


@pytest.mark.parametrize("mismatch", ("current_text", "current_role", "history_text", "order"))
def test_raw_history_binding_is_checked_before_review(mismatch: str) -> None:
    async def run() -> None:
        gateway = ReviewGateway()
        ctx = raw_context("提交")
        payload = copy.deepcopy(candidate_payload())
        if mismatch == "current_text":
            ctx = raw_context("先不生成")
        elif mismatch == "current_role":
            assert ctx.conversation_memory is not None
            messages = ctx.conversation_memory.messages
            ctx = replace(
                ctx,
                conversation_memory=ctx.conversation_memory.model_copy(
                    update={
                        "messages": (
                            *messages[:-1],
                            messages[-1].model_copy(update={"role": "ASSISTANT"}),
                        )
                    }
                ),
            )
        elif mismatch == "history_text":
            payload["history"][0]["text"] = "伪造的原USER执行请求"
        else:
            payload["history"].reverse()
        result, _ = await review(
            request(), ctx, gateway, CreativeContinuationCandidate.model_validate(payload)
        )
        assert result == "UNAVAILABLE"
        assert not gateway.messages

    asyncio.run(run())


def test_projectless_and_installed_creative_project_candidates_are_valid() -> None:
    async def run() -> None:
        for project_id in (None, "401"):
            ctx = replace(
                raw_context("提交"),
                selected_space_id=None,
                agent_identity=AgentIdentity(
                    installation_id="301",
                    agent_key="creative",
                    agent_version="1.0.0",
                    name="创作智能体",
                    project_id=project_id,
                    capabilities=("chat", "video.generate"),
                ),
            )
            gateway = ReviewGateway()
            result, _ = await review(
                request(),
                ctx,
                gateway,
                CreativeContinuationCandidate.model_validate(
                    candidate_payload(projectId=project_id)
                ),
            )
            assert result == "VIDEO"

    asyncio.run(run())


@pytest.mark.parametrize("mode", ("old_history", "missing_timestamp", "wrong_project"))
def test_memory_scope_and_deletion_fences_are_preserved(mode: str) -> None:
    async def run() -> None:
        ctx = raw_context("提交")
        memory = ProjectMemoryContext(
            schema_version=1,
            project_id="999" if mode == "wrong_project" else "401",
            entries=(),
            revision="1",
            history_cutoff_at=datetime.now(UTC) - timedelta(seconds=12),
        )
        if mode == "missing_timestamp":
            ctx = replace(ctx, conversation_memory=None)
        gateway = ReviewGateway()
        result, _ = await review(
            request(),
            ctx,
            gateway,
            CreativeContinuationCandidate.model_validate(candidate_payload()),
            memory=memory,
        )
        assert result == "UNAVAILABLE"
        assert not gateway.messages

    asyncio.run(run())


@pytest.mark.parametrize(
    "mode", ("missing_prompt", "missing_gateway", "local", "candidate", "budget", "failed")
)
def test_missing_or_unavailable_review_fails_closed(mode: str) -> None:
    async def run() -> None:
        req = request()
        gateway = ReviewGateway(fail=mode == "failed")
        counters = RuntimeUsageCounters(req, RuntimeHarness())
        if mode == "budget":
            counters.model_call_count = req.budget.max_model_calls
        result = await review_creative_action(
            gateway=None if mode == "missing_gateway" else gateway,
            prompt=None if mode == "missing_prompt" else "review",
            request=req,
            context=replace(raw_context("提交"), source="LOCAL_TRUSTED_STATE")
            if mode == "local"
            else raw_context("提交"),
            memory=None,
            counters=counters,
            candidate=None
            if mode == "candidate"
            else CreativeContinuationCandidate.model_validate(candidate_payload()),
        )
        assert result.decision == "UNAVAILABLE"
        assert result.source_mode == "UNCLEAR"
        assert len(gateway.messages) == (1 if mode == "failed" else 0)
        if mode == "failed":
            assert counters.model_call_count == 1
            assert counters.provider_input_tokens == 12

    asyncio.run(run())


def test_expired_run_preserves_deadline_failure() -> None:
    async def run() -> None:
        req = request()
        req = req.model_copy(
            update={
                "budget": req.budget.model_copy(
                    update={"deadline_at": datetime.now(UTC) - timedelta(seconds=1)}
                )
            }
        )
        gateway = ReviewGateway()
        with pytest.raises(HarnessViolation, match="deadline"):
            await review(
                req,
                raw_context("提交"),
                gateway,
                CreativeContinuationCandidate.model_validate(candidate_payload()),
            )
        assert not gateway.messages

    asyncio.run(run())


@pytest.mark.parametrize(
    "payload",
    (
        {"decision": "VIDEO"},
        {"decision": "IMAGE", "sourceMode": "TEXT_ONLY"},
        {"decision": "VIDEO", "sourceMode": "UNCLEAR"},
        {"decision": "IMAGE", "sourceMode": "CURRENT_ATTACHMENTS", "sourceImageAssetId": "601"},
        {"decision": "DISCUSSION", "sourceMode": "PREVIOUS_IMAGE_RESULT"},
        {"decision": "UNCLEAR", "sourceMode": "TEXT_ONLY"},
    ),
)
def test_review_rejects_missing_or_inconsistent_source_semantics(payload: dict[str, Any]) -> None:
    with pytest.raises(ValidationError):
        CreativeActionReview.model_validate(payload)
    assert "sourceMode" in CreativeActionReview.model_json_schema()["required"]


@pytest.mark.parametrize("source_mode", ("TEXT_ONLY", "PREVIOUS_IMAGE_RESULT"))
def test_video_review_carries_source_semantics_independent_of_tool_arguments(
    source_mode: str,
) -> None:
    async def run() -> None:
        req = request("让它动起来")
        gateway = ReviewGateway("VIDEO", source_mode=source_mode)
        counters = RuntimeUsageCounters(req, RuntimeHarness())
        result = await review_creative_action(
            gateway=gateway,
            prompt="Review source semantics",
            request=req,
            context=raw_context(req.message.content),
            memory=None,
            counters=counters,
            candidate=CreativeContinuationCandidate.model_validate(candidate_payload()),
        )
        assert result.decision == "VIDEO"
        assert result.source_mode == source_mode
        assert len(gateway.messages) == 1
        assert "scene" not in json.loads(gateway.messages[0][1].content)

    asyncio.run(run())


@pytest.mark.parametrize(
    "content",
    ("背景淡一点", "主体小一些", "右边多留点空间", "换成暖色", "把左边那个人拿掉"),
)
@pytest.mark.parametrize("history_incomplete", (False, True))
def test_current_attachment_review_uses_the_whole_user_without_requiring_history(
    content: str, history_incomplete: bool
) -> None:
    async def run() -> None:
        req = request(content)
        req = req.model_copy(
            update={"message": req.message.model_copy(update={"attachment_asset_ids": ("601",)})}
        )
        ctx = replace(raw_context(content), attachment_asset_ids=("601",))
        gateway = ReviewGateway("IMAGE", source_mode="CURRENT_ATTACHMENTS")
        counters = RuntimeUsageCounters(req, RuntimeHarness())
        result = await review_creative_action(
            gateway=gateway,
            prompt="Review the current selected image and whole USER",
            request=req,
            context=ctx,
            memory=None,
            counters=counters,
            candidate=CreativeContinuationCandidate.model_validate(
                candidate_payload(
                    history=[],
                    historyIncomplete=history_incomplete,
                    currentAttachmentAssetIds=["601"],
                )
            ),
        )
        assert result.decision == "IMAGE"
        assert result.source_mode == "CURRENT_ATTACHMENTS"
        assert counters.model_call_count == 1
        payload = json.loads(gateway.messages[0][1].content)
        assert payload == {
            "currentUser": content,
            "currentAttachmentCount": 1,
            "history": [],
            "historyIncomplete": history_incomplete,
        }
        assert "601" not in str(payload)

    asyncio.run(run())


@pytest.mark.parametrize("mismatch", ("request", "context", "candidate", "current_user"))
def test_current_attachment_binding_must_match_every_authoritative_snapshot(mismatch: str) -> None:
    async def run() -> None:
        req = request("背景淡一点")
        req = req.model_copy(
            update={
                "message": req.message.model_copy(
                    update={"attachment_asset_ids": ("602",) if mismatch == "request" else ("601",)}
                )
            }
        )
        ctx = replace(
            raw_context("先不修改" if mismatch == "current_user" else req.message.content),
            attachment_asset_ids=() if mismatch == "context" else ("601",),
        )
        gateway = ReviewGateway("IMAGE", source_mode="CURRENT_ATTACHMENTS")
        result, counters = await review(
            req,
            ctx,
            gateway,
            CreativeContinuationCandidate.model_validate(
                candidate_payload(
                    history=[],
                    currentAttachmentAssetIds=["602"] if mismatch == "candidate" else ["601"],
                )
            ),
        )
        assert result == "UNAVAILABLE"
        assert not gateway.messages
        assert counters.model_call_count == 0

    asyncio.run(run())


@pytest.mark.parametrize("source_mode", ("TEXT_ONLY", "PREVIOUS_IMAGE_RESULT"))
def test_current_attachments_cannot_make_incomplete_history_authoritative_for_other_sources(
    source_mode: str,
) -> None:
    async def run() -> None:
        req = request("主体小一些")
        req = req.model_copy(
            update={"message": req.message.model_copy(update={"attachment_asset_ids": ("601",)})}
        )
        gateway = ReviewGateway("VIDEO", source_mode=source_mode)
        result, _ = await review(
            req,
            replace(raw_context(req.message.content), attachment_asset_ids=("601",)),
            gateway,
            CreativeContinuationCandidate.model_validate(
                candidate_payload(historyIncomplete=True, currentAttachmentAssetIds=["601"])
            ),
        )
        assert result == "UNAVAILABLE"
        assert json.loads(gateway.messages[0][1].content)["history"] == []

    asyncio.run(run())


def test_current_attachment_field_is_required_on_the_java_candidate() -> None:
    payload = candidate_payload()
    del payload["currentAttachmentAssetIds"]
    with pytest.raises(ValidationError):
        CreativeContinuationCandidate.model_validate(payload)
    candidate = CreativeContinuationCandidate.model_validate(
        candidate_payload(currentAttachmentAssetIds=["9223372036854775808"])
    )
    assert candidate.current_attachment_asset_ids == ("9223372036854775808",)


def test_review_cannot_invent_current_attachments_from_history() -> None:
    async def run() -> None:
        gateway = ReviewGateway("IMAGE", source_mode="CURRENT_ATTACHMENTS")
        result, counters = await review(
            request("背景淡一点"),
            raw_context("背景淡一点"),
            gateway,
            CreativeContinuationCandidate.model_validate(candidate_payload()),
        )
        assert result == "UNAVAILABLE"
        assert json.loads(gateway.messages[0][1].content)["currentAttachmentCount"] == 0
        assert counters.model_call_count == 1

    asyncio.run(run())


@pytest.mark.parametrize(
    "source_mode", ("CURRENT_ATTACHMENTS", "PREVIOUS_IMAGE_RESULT", "TEXT_ONLY")
)
def test_current_source_cannot_restore_deleted_history(source_mode: str) -> None:
    async def run() -> None:
        req = request("主体小一些")
        req = req.model_copy(
            update={"message": req.message.model_copy(update={"attachment_asset_ids": ("601",)})}
        )
        ctx = replace(raw_context(req.message.content), attachment_asset_ids=("601",))
        gateway = ReviewGateway("VIDEO", source_mode=source_mode)
        result, _ = await review(
            req,
            ctx,
            gateway,
            CreativeContinuationCandidate.model_validate(
                candidate_payload(currentAttachmentAssetIds=["601"])
            ),
            memory=ProjectMemoryContext(
                schema_version=1,
                project_id="401",
                entries=(),
                revision="1",
                history_cutoff_at=datetime.now(UTC) - timedelta(seconds=12),
            ),
        )
        assert result == ("VIDEO" if source_mode == "CURRENT_ATTACHMENTS" else "UNAVAILABLE")
        payload = json.loads(gateway.messages[0][1].content)
        assert payload["history"] == []
        assert payload["historyIncomplete"] is True

    asyncio.run(run())


@pytest.mark.parametrize("decision", ("IMAGE", "VIDEO"))
def test_current_attachment_is_a_valid_reviewed_image_or_video_source(decision: str) -> None:
    reviewed = CreativeActionReview.model_validate(
        {"decision": decision, "sourceMode": "CURRENT_ATTACHMENTS"}
    )
    assert reviewed.source_mode == "CURRENT_ATTACHMENTS"
