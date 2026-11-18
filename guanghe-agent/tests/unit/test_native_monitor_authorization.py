"""User-phrase functional checks through the native catalog and execution fence.

The Java recorder is deliberately non-submitting: these checks cannot start a
monitor, spend points, or prove live model answer quality.
"""

import asyncio
import json
from datetime import UTC, datetime, timedelta
from typing import Any, cast
from uuid import uuid4

import pytest

from app.agent.identity import GEO_CAPABILITIES, bind_agent_execution, resolve_execution
from app.agent.models import ChatMessage, ChatRunRequest, RunBudget, TrustedState
from app.harness.runtime import RuntimeHarness, RuntimeUsageCounters, bind_runtime_usage
from app.integration.java_internal import JavaAgentActionClientProtocol, JavaInternalResponse
from app.model_gateway.gateway import ModelToolCall
from app.providers.java_context import LocalRunContextProvider
from app.tools.native import NativeTools
from app.tools.native_authorization import user_requested_native_actions


@pytest.mark.parametrize(
    "content",
    [
        "那跑一次监测吧",
        "跑一次监测吧",
        "提交监测",
        "帮我检测一下",
        "启动一次监测",
        "那就开始监测吧",
        "好的，那现在帮我监测一下小行星咖啡在广州的可见度",
        "麻烦安排一次监测",
        "请执行本次监测任务",
        "现在用这七个平台跑一次监测",
        "根据上面的三个问题监测一下",
        "针对当前品牌做一次监测",
        "帮小行星做一次监测",
        "发起监测",
        "监测一次",
        "重新监测",
        "再跑一次",
        "不要写文章，先监测一下",
    ],
)
def test_natural_explicit_monitor_commands(content: str) -> None:
    assert "geo.monitor" in user_requested_native_actions(content)


@pytest.mark.parametrize(
    "content",
    [
        "帮我做 GEO 优化，提高我的品牌在广州的可见度",
        "先给优化方案，不要自行启动监测",
        "帮我看看最近的监测报告",
        "监测结果怎么样",
        "监测完成了吗",
        "这次监测失败了",
        "提交监测成功了吗",
        "监测还没开始",
        "监测任务没有生成",
        "监测有什么问题",
        "监测已经提交了",
        "监测失败",
        "监测花了21积分",
        "监测正在进行中",
        "我刚刚跑了一次监测",
        "最近已经启动过监测",
        "怎么启动监测",
        "启动监测要多少钱",
        "监测一次多少积分",
        "能否启动监测",
        "是不是要先启动监测",
        "监测是什么意思",
        "不要监测",
        "暂时别执行监测",
        "取消监测",
        "跑一次监测，先别执行",
        "只给方案，监测一下的事以后再说",
        "如果信息够了就监测",
        "如果准备好了，跑一次监测吧",
        "等我确认，启动监测",
        "预算足够就监测",
        "等额度配置好后再监测",
        "比如，启动一次监测",
        "请解释这句话\uff1a那跑一次监测吧",
        "请翻译\uff1a提交监测",
        "客户说\uff1a帮我监测一次",
        "报告建议启动监测",
        "“那跑一次监测吧”",
        "`提交监测`",
        '"帮我检测一下"',
        "```text\n启动监测\n```",
        "> 那跑一次监测吧\n这句话是什么含义",
        "好的",
        "提交",
        "继续",
        "再跑一次文章生成",
    ],
)
def test_read_only_negated_conditional_quoted_and_ambiguous_requests(content: str) -> None:
    assert "geo.monitor" not in user_requested_native_actions(content)


@pytest.mark.parametrize(
    "content,expected",
    [
        ("根据这份报告帮我生成一篇文章", {"article.generate"}),
        ("联网查资料写篇文章", {"article.generate"}),
        ("我的咖啡店叫做小行星", {"brand.save"}),
        ("先不要写文章，只给我方法", set[str]()),
    ],
)
def test_article_and_brand_fences_keep_their_behavior(content: str, expected: set[str]) -> None:
    assert user_requested_native_actions(content) == expected


class NonSubmittingJava:
    def __init__(self) -> None:
        self.actions: list[str] = []

    async def execute_run_action(self, *args: Any) -> JavaInternalResponse:
        action = args[2]["action"]
        self.actions.append(action)
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
    "content,allowed",
    [
        ("那跑一次监测吧", True),
        ("提交监测", True),
        ("帮我检测一下", True),
        ("启动一次监测", True),
        ("帮我进行 GEO 优化", False),
        ("查看监测报告", False),
        ("如果准备好了，跑一次监测", False),
        ("提交", False),
    ],
)
def test_real_catalog_and_executor_only_offer_requested_monitoring(
    content: str, allowed: bool
) -> None:
    asyncio.run(_check_catalog_and_executor(content, allowed))


async def _check_catalog_and_executor(content: str, allowed: bool) -> None:
    request = ChatRunRequest(
        run_id=uuid4(),
        conversation_id="123",
        user_context_ref="test-monitor-command-fence",
        message=ChatMessage(message_id="124", content=content),
        trusted_state=TrustedState(),
        budget=RunBudget(
            max_tool_calls=3,
            deadline_at=datetime.now(UTC) + timedelta(minutes=1),
        ),
    )
    recorder = NonSubmittingJava()
    counters = RuntimeUsageCounters(request, RuntimeHarness())
    with (
        bind_agent_execution(resolve_execution("geo", "1.0.0", tuple(GEO_CAPABILITIES))),
        bind_runtime_usage(counters),
    ):
        tools = NativeTools(
            request,
            await LocalRunContextProvider().load(request),
            cast(JavaAgentActionClientProtocol, recorder),
            counters,
        )
        catalog = {tool.name for tool in tools.catalog()}
        assert ("geo_monitor" in catalog) is allowed
        assert "brand_save" not in catalog
        # Article is a candidate capability, not permission to create a paid
        # draft. Missing semantic review must still fail closed.
        article = json.loads(
            await tools.execute(ModelToolCall(str(uuid4()), "article_generate", "{}"))
        )
        assert article["reasonCode"] in {"ARTICLE_ACTION_UNAVAILABLE", "TOOL_NOT_AUTHORIZED"}
        assert recorder.actions == []
        result = json.loads(
            await tools.execute(
                ModelToolCall(
                    id=str(uuid4()),
                    name="geo_monitor",
                    arguments=json.dumps(
                        {
                            "region": "广州",
                            "questions": [
                                "广州有哪些现磨咖啡店？",
                                "广州去哪喝美式咖啡？",
                                "广州有什么特色咖啡？",
                            ],
                        },
                        ensure_ascii=False,
                    ),
                )
            )
        )
        if allowed:
            assert recorder.actions == ["geo.monitor"]
            assert result["data"]["reasonCode"] == "TEST_NO_TASK_CREATED"
        else:
            assert recorder.actions == []
            assert result["reasonCode"] == "TOOL_NOT_AUTHORIZED"
