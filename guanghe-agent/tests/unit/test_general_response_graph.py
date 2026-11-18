"""Historical buffered-review adapter, not the production native chat execution path.

Scripted candidates test control flow and evidence fidelity, not semantic quality.
The full current bundle is checked separately from this bounded compatibility fixture.
"""

import asyncio
import json
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from typing import TypeVar
from uuid import uuid4

import pytest
from pydantic import BaseModel

from app.agent.general_response_review import (
    GENERAL_REVIEW_UNAVAILABLE,
    GEO_REVIEW_FALLBACK,
    GeneralReviewAnswerTopic,
    GeneralReviewReasonCode,
    build_general_response_review,
)
from app.agent.graphs.commerce import CommerceAssistantGraph
from app.agent.models import (
    BusinessOutcomeStatus,
    ChatMessage,
    ChatRunRequest,
    Intent,
    RunBudget,
    RunProgressStage,
    RunProgressStatus,
    TrustedState,
)
from app.common.settings import default_bundle_path
from app.harness.runtime import (
    HarnessViolation,
    RuntimeHarness,
    estimate_model_messages,
    record_model_call,
)
from app.model_gateway.gateway import (
    GENERAL_RESPONSE_INSTRUCTION,
    DeltaHandler,
    GatewayUsage,
    IntentResult,
    LocalModelGateway,
    ModelGatewayError,
    ModelMessage,
    ModelMessages,
    TextResult,
    default_intent_route,
)
from app.prompts.assembly import CommercePromptSet
from app.prompts.loader import load_bundle
from app.providers.java_context import (
    AgentIdentity,
    AuthorizedRunContext,
    BrandSnapshot,
    ConversationMemory,
    ConversationMemoryMessage,
    GeoInsightContext,
    LocalRunContextProvider,
    ProtocolFeatures,
    SpaceSnapshot,
    TaskPreparationConstraints,
    general_response_evidence_scope,
    general_response_platform_scope,
)
from app.providers.knowledge_context import KnowledgeContext, MemoryPreference

_Response = TypeVar("_Response", bound=BaseModel)
_CANDIDATE = "PRIVATE_CANDIDATE: 它答得出来，就说明信息是有的。"


class BufferedGateway(LocalModelGateway):
    is_mock = False

    def __init__(
        self,
        *,
        accept: bool = False,
        error: BaseException | None = None,
        topic: GeneralReviewAnswerTopic = "GENERAL",
        candidate: str = _CANDIDATE,
        rejection_reason: GeneralReviewReasonCode = "GEO_KNOWLEDGE_INFERENCE",
    ) -> None:
        self.accept = accept
        self.topic = topic
        self.error = error
        self.candidate = candidate
        self.rejection_reason = rejection_reason
        self.answer_messages: ModelMessages | None = None
        self.answer_calls = 0
        self.review_calls = 0
        self.review_messages: ModelMessages | None = None
        self.oversize = False

    async def classify_intent(self, messages: ModelMessages) -> IntentResult:
        record_model_call()
        return IntentResult(default_intent_route(Intent.GENERAL), GatewayUsage(input_tokens=11))

    async def answer_general(
        self, messages: ModelMessages, *, on_delta: DeltaHandler | None = None
    ) -> TextResult:
        record_model_call()
        self.answer_calls += 1
        self.answer_messages = messages
        assert on_delta is None
        return TextResult(
            self.candidate + ("X" * 140_000 if self.oversize else ""),
            GatewayUsage(input_tokens=100, output_tokens=30, request_count=1),
        )

    async def complete_structured(
        self,
        messages: ModelMessages,
        response_model: type[_Response],
        *,
        operation: str,
        max_tokens: int,
        error_code: str,
        repair_instruction: str,
        repair_attempts: int = 1,
    ) -> tuple[_Response, GatewayUsage]:
        record_model_call()
        self.review_calls += 1
        self.review_messages = messages
        if self.error is not None:
            raise self.error
        assert operation == "readonly_general_response_review" and repair_attempts == 0
        return response_model.model_validate(
            {
                "decision": "ACCEPT" if self.accept else "REJECT",
                "reasonCodes": [] if self.accept else [self.rejection_reason],
                "answerTopic": self.topic,
            }
        ), GatewayUsage(input_tokens=200, output_tokens=10, request_count=1)


def request(*, calls: int = 4) -> ChatRunRequest:
    return ChatRunRequest(
        run_id=uuid4(),
        conversation_id="review-test",
        user_context_ref="opaque-test",
        message=ChatMessage(message_id=str(uuid4()), content="请解释一下这个概念"),
        trusted_state=TrustedState(),
        budget=RunBudget(
            max_model_calls=calls, deadline_at=datetime.now(UTC) + timedelta(minutes=1)
        ),
    )


def released_prompts() -> CommercePromptSet:
    bundle = load_bundle(default_bundle_path()).bundle
    return CommercePromptSet.from_bundle(bundle, bundle.profiles[0])


def prompts() -> CommercePromptSet:
    """Keep scripted adapter behavior independent of growing native-chat guidance.

    Runtime evidence scopes, message assembly and the entire released reviewer policy
    remain real. Only the candidate model's policy is a small fixture; no production
    limits or inputs are changed, and the full-policy boundary is covered below.
    """
    return replace(
        released_prompts(),
        general_response="Answer the current user task using only the supplied evidence.",
    )


def test_released_general_policies_preserve_fact_task_and_translation_boundaries() -> None:
    """Assert deployed policy content separately from scripted model control-flow tests."""
    configured = released_prompts()
    generation = configured.for_general_response(GENERAL_RESPONSE_INSTRUCTION)
    for policy in (
        "同项目知识库原始段落及已确认的经营资料",
        "不把整套下一步推给他逐个问平台",
        "本轮新任务优先于旧 GEO 话题",
        "不能代替到店、成交",
        "本轮更正优先",
    ):
        assert policy in generation
    assert configured.general_response_review is not None
    for policy in (
        "Latest original USER task overrides prior GEO topics",
        "AI mentions alone do not measure visits",
        "one short target-language question is valid",
    ):
        assert policy in configured.general_response_review


class NoviceProjectContext(LocalRunContextProvider):
    """Generic project with real-shaped original KB, no catalog product or paid grant."""

    async def load(self, request: ChatRunRequest) -> AuthorizedRunContext:
        base = await super().load(request)
        knowledge = KnowledgeContext.model_validate(
            {
                "documents": [
                    {
                        "id": "103",
                        "version": 1,
                        "name": "店铺资料",
                        "sha256": "a" * 64,
                        "sourceType": "PASTED_TEXT",
                        "sourceUrl": None,
                        "paragraphs": [
                            {
                                "index": 1,
                                "page": None,
                                "text": "小行星咖啡店在广州，主营咖啡、曲奇、蛋糕。价格未提供。",
                            }
                        ],
                    }
                ],
                "preferences": [],
                "businessHistory": None,
                "truncated": False,
                "sourceCount": 1,
                "returnedCharacters": 34,
            }
        )
        return replace(
            base,
            source="JAVA_AUTHORIZED_CONTEXT",
            selected_space_id="101",
            selected_product_id=None,
            product=None,
            space=SpaceSnapshot(
                id="101",
                name="小行星咖啡店",
                logo_asset_id=None,
                brand=BrandSnapshot(
                    name="小行星咖啡店",
                    aliases=(),
                    description=None,
                    tone=(),
                    audience=(),
                    selling_points=(),
                    prohibited_claims=(),
                ),
            ),
            knowledge_context=knowledge,
            agent_identity=AgentIdentity(
                installation_id="102",
                agent_key="geo",
                agent_version="1.0.0",
                name="GEO智能体",
                project_id="101",
                capabilities=("chat", "geo.read", "geo.monitor"),
            ),
            geo_insight_context=GeoInsightContext.model_validate(
                {
                    "schemaVersion": 1,
                    "status": "NO_REPORT",
                    "selectionSource": "NONE",
                    "readOnly": True,
                    "questionScope": "DISCOVERY",
                    "topicsTruncated": False,
                    "truncated": False,
                    "topicChoices": [],
                    "report": None,
                    "trend": None,
                    "competitors": None,
                    "links": {
                        "overviewPath": "/app/geo",
                        "reportPath": None,
                        "competitivePath": None,
                    },
                }
            ),
        )


@pytest.mark.parametrize("question", ("我啥也没准备咋办", "别老讲术语，能用店老板听得懂的话吗"))
def test_real_novice_no_material_followup_rejection_keeps_existing_kb_and_one_next_step(
    question: str,
) -> None:
    """Regress A4's theory fallback through the real graph; reviewer decision is scripted."""
    gateway = BufferedGateway(topic="ONBOARDING", candidate=GEO_REVIEW_FALLBACK)
    current = request().model_copy(
        update={"message": ChatMessage(message_id=str(uuid4()), content=question)}
    )
    result = asyncio.run(
        CommerceAssistantGraph(
            NoviceProjectContext(), gateway=gateway, prompt_set=prompts()
        ).ainvoke(current)
    )
    assert "已经提供的项目资料" in result.message and "只补一个重点" in result.message
    assert "还没有开始监测或发布" in result.message and result.message.count("？") == 1
    assert "采集失败" not in result.message and result.message != GEO_REVIEW_FALLBACK
    assert result.task_draft is None and result.interaction is None
    assert result.business_outcome is not None
    assert result.business_outcome.status is BusinessOutcomeStatus.ANSWERED
    assert result.usage.tool_call_count == 0 and gateway.answer_calls == gateway.review_calls == 1
    assert (
        gateway.answer_messages is not None
        and "主营咖啡、曲奇、蛋糕" in gateway.answer_messages[0].content
    )
    assert gateway.review_messages is not None
    scope = json.loads(gateway.review_messages[-1].content)["platformScope"]
    assert scope["providedProjectMaterialAvailable"] is True


@pytest.mark.parametrize("accept", (False, True))
def test_real_check_if_ai_knows_me_never_denies_installed_monitor_or_starts_paid_work(
    accept: bool,
) -> None:
    """Regress A8 including a mistaken ACCEPT: only runtime preparation guidance is delivered."""
    gateway = BufferedGateway(
        accept=accept,
        topic="MONITOR_ENTRY",
        candidate="我没法替你去各个平台问一遍，你自己截图给我。",
    )
    current = request().model_copy(
        update={"message": ChatMessage(message_id=str(uuid4()), content="你看看现在AI知不知道我")}
    )
    result = asyncio.run(
        CommerceAssistantGraph(
            NoviceProjectContext(), gateway=gateway, prompt_set=prompts()
        ).ainvoke(current)
    )
    assert "GEO 智能体可以监测" in result.message and "[监测入口](/app/geo)" in result.message
    assert "本次报价" in result.message and "有效授权后" in result.message
    assert "当前没有开始监测、扣积分或发布" in result.message
    assert gateway.candidate not in result.message and "AI 已经知道或推荐了你" in result.message
    assert result.task_draft is None and result.interaction is None
    assert result.business_outcome is not None
    assert result.business_outcome.status is BusinessOutcomeStatus.ANSWERED
    assert result.usage.tool_call_count == 0 and gateway.answer_calls == gateway.review_calls == 1
    assert gateway.review_messages is not None
    scope = json.loads(gateway.review_messages[-1].content)["platformScope"]
    assert scope["geoMonitorPreparationAvailable"] is True


@pytest.mark.parametrize(
    "agent_key,version,installation,capabilities",
    (
        ("geo", "1.0.0", None, ("chat", "geo.monitor")),
        ("geo", "1.0.0", "102", ("chat",)),
        ("geo", "9.0.0", "102", ("chat", "geo.monitor")),
        ("unknown", "1.0.0", "102", ("chat", "geo.monitor")),
        ("summary", "1.0.0", "102", ("chat", "geo.monitor")),
    ),
)
def test_guidance_does_not_promote_uninstalled_unknown_or_denied_identity(
    agent_key: str, version: str, installation: str | None, capabilities: tuple[str, ...]
) -> None:
    context = asyncio.run(NoviceProjectContext().load(request()))
    context = replace(
        context,
        agent_identity=AgentIdentity(
            installation_id=installation,
            agent_key=agent_key,
            agent_version=version,
            name="test",
            project_id="101",
            capabilities=capabilities,
        ),
    )
    scope = general_response_platform_scope(context)
    assert not scope["geoMonitorSupported"] and not scope["geoMonitorPreparationAvailable"]


def test_guidance_preserves_disabled_preparation_and_distinguishes_support_from_grant() -> None:
    context = asyncio.run(NoviceProjectContext().load(request()))
    context = replace(
        context,
        task_preparation_constraints=TaskPreparationConstraints.model_validate(
            {
                "geo": {
                    "enabled": False,
                    "minQuestions": 3,
                    "maxQuestions": 0,
                    "platformCodes": [],
                    "maxProviderCalls": 0,
                },
            }
        ),
    )
    scope = general_response_platform_scope(context)
    assert scope["geoMonitorSupported"] and not scope["geoMonitorPreparationAvailable"]
    assert general_response_platform_scope(None) == {
        "geoMonitorSupported": False,
        "geoMonitorPreparationAvailable": False,
        "providedProjectMaterialAvailable": False,
    }


def test_selected_project_name_or_personal_style_is_not_a_completed_fact_pack() -> None:
    context = asyncio.run(NoviceProjectContext().load(request()))
    context = replace(context, knowledge_context=None)
    assert not general_response_platform_scope(context)["providedProjectMaterialAvailable"]
    preference = MemoryPreference.model_validate(
        {
            "id": "104",
            "projectId": None,
            "scope": "PERSONAL",
            "content": "回答短一点",
            "sourceType": "USER_INPUT",
            "sourceMessageId": None,
            "version": 1,
            "createdAt": "2026-10-07T01:00:00Z",
            "updatedAt": "2026-10-07T01:00:00Z",
        }
    )
    context = replace(
        context,
        knowledge_context=KnowledgeContext(
            documents=(),
            preferences=(preference,),
            business_history=None,
            truncated=False,
            source_count=0,
            returned_characters=0,
        ),
    )
    assert not general_response_platform_scope(context)["providedProjectMaterialAvailable"]


@pytest.mark.parametrize("paragraph_text", ("", "   "))
def test_empty_knowledge_excerpts_are_not_useful_project_material(paragraph_text: str) -> None:
    context = asyncio.run(NoviceProjectContext().load(request()))
    assert context.knowledge_context is not None
    document = context.knowledge_context.documents[0]
    paragraph = document.paragraphs[0].model_copy(update={"text": paragraph_text})
    knowledge = context.knowledge_context.model_copy(
        update={
            "documents": (document.model_copy(update={"paragraphs": (paragraph,)}),),
        }
    )
    assert not general_response_platform_scope(replace(context, knowledge_context=knowledge))[
        "providedProjectMaterialAvailable"
    ]


@pytest.mark.parametrize(
    "question,candidate",
    (
        (
            "我开个咖啡店，完全不懂GEO，能简单讲讲吗",
            "就是观察顾客问AI时有没有提到你的店，再决定要不要补内容。"
            "先整理顾客的问题，之后可在光禾AI智能体框架准备监测，核对范围和报价；不会直接收费。",
        ),
        (
            "咖啡曲奇蛋糕都有，都想让人知道",
            "可以一起整理品牌资料，再分别准备咖啡、曲奇和蛋糕的问题。"
            "之后通过光禾AI智能体框架准备监测；写了资料不保证AI推荐，这轮尚未执行。",
        ),
        (
            "我没告诉你价格，你会自己猜个便宜的价格吗",
            "不会猜。我可以用你提供的原话、同项目知识库或已确认资料里的准数；"
            "这些资料都没给价格时就不写价格。",
        ),
        (
            "那是不是发一篇就肯定排第一",
            "不能保证。回答可能列出有先后顺序的店，但不等于固定排名，"
            "也不能据此承诺一篇文章让你排第一。",
        ),
    ),
)
def test_getting_started_and_fact_boundaries_preserve_direct_help_without_execution(
    question: str,
    candidate: str,
) -> None:
    """Delivery/source fidelity only; a scripted ACCEPT is not live semantic certification."""
    gateway = BufferedGateway(accept=True, topic="GENERAL", candidate=candidate)
    current = request().model_copy(
        update={
            "message": ChatMessage(message_id=str(uuid4()), content=question),
        }
    )
    result = asyncio.run(
        CommerceAssistantGraph(
            NoviceProjectContext(),
            gateway=gateway,
            prompt_set=prompts(),
        ).ainvoke(current)
    )
    assert result.message == candidate and result.task_draft is None and result.interaction is None
    assert result.usage.tool_call_count == 0 and gateway.answer_calls == gateway.review_calls == 1
    assert gateway.answer_messages is not None
    system = gateway.answer_messages[0].content
    assert "价格未提供" in system and '"geoMonitorSupported":true' in system


@pytest.mark.parametrize(
    "question,candidate",
    (
        ("只翻译成英文: 我啥也没准备咋办", "What should I do if I haven't prepared anything?"),
        ("只翻译成英文: 你看看现在AI知不知道我", "Can you see whether AI knows about me now?"),
        (
            "假设一个店在AI回答里没被提到，能说AI完全不知道它吗？",
            "不能。一次回答未提及不足以判断平台是否知道它。",
        ),
        ("先不监测，给我一句咖啡店邀请文案", "来店里坐坐，给自己留一点咖啡时间。"),
    ),
)
def test_neighbor_translation_hypothesis_and_negated_monitor_keep_requested_text(
    question: str, candidate: str
) -> None:
    gateway = BufferedGateway(accept=True, topic="GENERAL", candidate=candidate)
    current = request().model_copy(
        update={"message": ChatMessage(message_id=str(uuid4()), content=question)}
    )
    result = asyncio.run(
        CommerceAssistantGraph(
            NoviceProjectContext(), gateway=gateway, prompt_set=prompts()
        ).ainvoke(current)
    )
    assert result.message == candidate and result.task_draft is None
    assert result.usage.tool_call_count == 0


@pytest.mark.parametrize(
    "question,candidate",
    (
        (
            "先不弄了，我问个别的，怎么排一个简单的工作清单",
            "先写下今天要做的事，挑出最重要的三件，再给每件安排一个时间段；做完一件就勾掉。",
        ),
        (
            "别老讲术语，能用店老板听得懂的话吗",
            "可以，我接下来会用简单的话说，必要的术语也会解释。",
        ),
        (
            "如果我想让更多人知道这个品牌并来店里，先从哪下手",
            "先整理门店介绍，再核对地址、营业时间和导航入口，让顾客找到路；"
            "随后记录实际咨询、到店或订单及时间，AI提及另外看，不能代替到店结果。",
        ),
    ),
)
def test_new_topic_style_and_visit_plan_preserve_current_user_task_with_business_context(
    question: str, candidate: str
) -> None:
    """Prompt/input and delivery regression only, not scripted-review semantic certification."""
    gateway = BufferedGateway(accept=True, candidate=candidate)
    current = request().model_copy(
        update={"message": ChatMessage(message_id=str(uuid4()), content=question)}
    )
    result = asyncio.run(
        CommerceAssistantGraph(
            NoviceProjectContext(), gateway=gateway, prompt_set=prompts()
        ).ainvoke(current)
    )
    assert result.message == candidate and result.task_draft is None and result.interaction is None
    assert result.business_outcome is not None
    assert result.business_outcome.status is BusinessOutcomeStatus.ANSWERED
    assert gateway.answer_calls == gateway.review_calls == 1 and result.usage.tool_call_count == 0
    assert gateway.answer_messages is not None and gateway.review_messages is not None
    assert gateway.answer_messages[-1].content == question
    assert (
        "Latest original USER task overrides prior GEO topics" in gateway.review_messages[0].content
    )
    assert "AI mentions alone do not measure visits" in gateway.review_messages[0].content


@pytest.mark.parametrize(
    "question,candidate",
    (
        ("换个话题，给我普通的工作清单", "先记店名，再整理顾客问题，然后做AI监测。"),
        ("怎么安排让顾客到店的计划", "整理介绍后发文，最后只看AI有没有提到你，作为到店结果。"),
    ),
)
def test_rejected_wrong_task_warning_cannot_be_counted_as_answered(
    question: str, candidate: str
) -> None:
    gateway = BufferedGateway(candidate=candidate, rejection_reason="QUESTION_NOT_ADDRESSED")
    current = request().model_copy(
        update={"message": ChatMessage(message_id=str(uuid4()), content=question)}
    )
    result = asyncio.run(
        CommerceAssistantGraph(
            NoviceProjectContext(), gateway=gateway, prompt_set=prompts()
        ).ainvoke(current)
    )
    assert candidate not in result.message
    assert result.business_outcome is not None
    assert result.business_outcome.status is BusinessOutcomeStatus.FAILED
    assert gateway.review_calls == 1 and result.task_draft is None


@pytest.mark.parametrize("accept", (False, True))
def test_candidate_is_buffered_and_only_selected_final_text_is_returned(accept: bool) -> None:
    gateway = BufferedGateway(accept=accept)
    graph = CommerceAssistantGraph(LocalRunContextProvider(), gateway=gateway, prompt_set=prompts())
    deltas: list[str] = []

    async def on_delta(text: str) -> None:
        deltas.append(text)

    result = asyncio.run(graph.ainvoke(request(), on_delta=on_delta))
    assert deltas == []  # RunManager, not Graph, emits selected text with stop checks.
    assert result.message == (_CANDIDATE if accept else GEO_REVIEW_FALLBACK)
    assert gateway.answer_calls == gateway.review_calls == 1
    assert result.usage.model_call_count == 3 and result.usage.tool_call_count == 0
    assert result.usage.provider_input_tokens == 311 and result.usage.provider_output_tokens == 40
    assert result.task_draft is None
    assert gateway.review_messages is not None and len(gateway.review_messages) == 2
    envelope = json.loads(gateway.review_messages[-1].content)
    assert envelope["candidateText"] == _CANDIDATE
    assert envelope["evidenceScope"] == general_response_evidence_scope()
    generation_system = envelope["sourceMessages"][0]["content"]
    fact_scope = generation_system.split("[GENERAL RESPONSE FACT SCOPE]\n", 1)[1]
    manifest = json.loads(next(line for line in fact_scope.splitlines() if line.startswith("{")))
    assert manifest["evidenceScope"] == envelope["evidenceScope"]
    assert envelope["sourceMessages"][-1]["content"] == "请解释一下这个概念"
    assert "[GENERAL RESPONSE EVIDENCE REVIEW]" in gateway.review_messages[0].content
    assert _CANDIDATE not in gateway.review_messages[0].content


def test_translation_target_changes_preserve_user_history_through_review() -> None:
    """Real graph/assembly with scripted candidates; not a model-semantic acceptance test."""

    class MemoryContext(LocalRunContextProvider):
        history: ModelMessages = ()

        async def load(self, request: ChatRunRequest) -> AuthorizedRunContext:
            base = await super().load(request)
            if not self.history:
                return base
            messages = tuple(
                ConversationMemoryMessage(
                    message_id=str(900 + index),
                    sequence=index + 1,
                    role="USER" if message.role == "user" else "ASSISTANT",
                    content={"text": message.content},
                    created_at=datetime.now(UTC),
                    reply_to_message_id=None,
                )
                for index, message in enumerate(self.history)
            )
            return replace(
                base,
                conversation_memory=ConversationMemory(
                    messages=messages,
                    summary=None,
                    last_message_sequence=len(messages),
                    summary_through_sequence=0,
                    context_source="POSTGRESQL",
                ),
            )

    provider = MemoryContext()
    turns = (
        ("先不聊证据了，只翻译：平台给出了一个来源链接。", "想翻译成哪种语言？"),  # noqa: RUF001
        ("英文就行", "The platform provided a source link."),
        ("再翻回中文，只给译文", "平台给出了一个来源链接。"),
    )
    for question, scripted_answer in turns:
        gateway = BufferedGateway(accept=True, candidate=scripted_answer)
        graph = CommerceAssistantGraph(provider, gateway=gateway, prompt_set=prompts())
        current = request().model_copy(
            update={"message": ChatMessage(message_id=str(uuid4()), content=question)}
        )
        result = asyncio.run(graph.ainvoke(current))
        assert result.intent is Intent.GENERAL and result.message == scripted_answer
        assert result.task_draft is None and result.interaction is None
        assert result.usage.model_call_count == 3 and result.usage.tool_call_count == 0
        assert gateway.answer_calls == gateway.review_calls == 1
        assert gateway.answer_messages is not None and gateway.review_messages is not None
        expected_turns = (*provider.history, ModelMessage(role="user", content=question))
        assert gateway.answer_messages[1:] == expected_turns
        assert "not as an implicit translation target" in gateway.answer_messages[0].content
        envelope = json.loads(gateway.review_messages[-1].content)
        assert envelope["sourceMessages"][1:] == [
            {"role": message.role, "content": message.content} for message in expected_turns
        ]
        assert envelope["candidateText"] == scripted_answer
        assert "one short target-language question is valid" in gateway.review_messages[0].content
        provider.history = (*expected_turns, ModelMessage(role="assistant", content=result.message))


def test_call_headroom_rejects_before_spending_on_an_unreviewable_candidate() -> None:
    gateway = BufferedGateway()
    result = asyncio.run(
        CommerceAssistantGraph(
            LocalRunContextProvider(), gateway=gateway, prompt_set=prompts()
        ).ainvoke(request(calls=2))
    )
    assert result.message == GENERAL_REVIEW_UNAVAILABLE
    assert result.usage.model_call_count == 1
    assert gateway.answer_calls == gateway.review_calls == 0


def test_source_topic_accept_is_projected_before_delivery_without_extra_calls() -> None:
    gateway = BufferedGateway(accept=True, topic="SOURCE_EVIDENCE")
    graph = CommerceAssistantGraph(LocalRunContextProvider(), gateway=gateway, prompt_set=prompts())
    current = request().model_copy(
        update={
            "message": ChatMessage(
                message_id=str(uuid4()), content="核实网页存在且内容对应，就能证明平台使用了它吗？"
            )
        }
    )
    result = asyncio.run(graph.ainvoke(current))
    assert _CANDIDATE not in result.message
    assert "检索返回不等于回答采用" in result.message
    assert "本轮普通对话" in result.message
    assert gateway.answer_calls == gateway.review_calls == 1
    assert result.usage.model_call_count == 3 and result.usage.tool_call_count == 0
    assert result.task_draft is None
    assert result.business_outcome is not None
    assert result.business_outcome.status is BusinessOutcomeStatus.ANSWERED


def test_review_input_budget_never_truncates_then_approves_full_candidate() -> None:
    gateway = BufferedGateway(accept=True)
    gateway.oversize = True
    result = asyncio.run(
        CommerceAssistantGraph(
            LocalRunContextProvider(), gateway=gateway, prompt_set=prompts()
        ).ainvoke(request())
    )
    assert result.message == GENERAL_REVIEW_UNAVAILABLE and _CANDIDATE not in result.message
    assert gateway.answer_calls == 1 and gateway.review_calls == 0
    assert result.usage.model_call_count == 2 and result.usage.provider_output_tokens == 30


def test_full_released_policy_and_project_context_fail_closed_at_default_review_budget() -> None:
    """Current full policy exceeds this historical adapter's two-stage 32K envelope.

    Keep the real limit visible: a smaller scripted fixture is not proof that the
    full release prompt can be sent twice, and production chat uses the native loop.
    """
    gateway = BufferedGateway(accept=True)
    provider = NoviceProjectContext()
    current = request()
    configured = released_prompts()
    deltas: list[str] = []

    async def on_delta(text: str) -> None:
        deltas.append(text)

    result = asyncio.run(
        CommerceAssistantGraph(provider, gateway=gateway, prompt_set=configured).ainvoke(
            current, on_delta=on_delta
        )
    )
    assert current.budget.max_input_tokens == 32_000
    assert gateway.answer_messages is not None and configured.general_response_review is not None
    prepared = build_general_response_review(
        gateway.answer_messages,
        gateway.candidate,
        configured.general_response_review,
        context=asyncio.run(provider.load(current)),
    )
    assert estimate_model_messages(gateway.answer_messages) <= current.budget.max_input_tokens
    assert estimate_model_messages(prepared.messages) > current.budget.max_input_tokens
    assert result.message == GENERAL_REVIEW_UNAVAILABLE and _CANDIDATE not in result.message
    assert deltas == [] and gateway.answer_calls == 1 and gateway.review_calls == 0
    assert result.usage.model_call_count == 2 and result.usage.tool_call_count == 0
    assert result.usage.provider_input_tokens == 111 and result.usage.provider_output_tokens == 30
    assert result.business_outcome is not None
    assert result.business_outcome.status is BusinessOutcomeStatus.FAILED
    assert result.business_outcome.reason_code == "READONLY_SYNTHESIS_UNAVAILABLE"
    assert result.task_draft is None and result.interaction is None


def test_review_failure_usage_is_recorded_once_in_addition_to_candidate() -> None:
    gateway = BufferedGateway(
        error=ModelGatewayError(
            "GENERAL_RESPONSE_REVIEW_INVALID",
            structured_response_failure=True,
            usage=GatewayUsage(input_tokens=77, output_tokens=8, request_count=1),
        )
    )
    result = asyncio.run(
        CommerceAssistantGraph(
            LocalRunContextProvider(), gateway=gateway, prompt_set=prompts()
        ).ainvoke(request())
    )
    assert result.message == GENERAL_REVIEW_UNAVAILABLE
    assert result.usage.provider_input_tokens == 188 and result.usage.provider_output_tokens == 38
    assert result.business_outcome is not None
    assert result.business_outcome.status is BusinessOutcomeStatus.FAILED
    assert result.business_outcome.reason_code == "READONLY_SYNTHESIS_UNAVAILABLE"
    assert result.business_outcome.failure_stage == "SYNTHESIS"


@pytest.mark.parametrize(
    "question,code,structural,failure_stage",
    (
        (
            "别老讲术语，能用店老板听得懂的话吗",
            "GENERAL_RESPONSE_REVIEW_INVALID",
            True,
            "invalid_json",
        ),
        ("帮我解释一个简单概念", "MODEL_TIMEOUT", False, None),
    ),
)
def test_legacy_review_failure_returns_failed_business_outcome_without_retry(
    question: str, code: str, structural: bool, failure_stage: str | None
) -> None:
    """Legacy adapter failure shape; native RunManager lifecycle is tested separately."""

    class OutcomeContext(NoviceProjectContext):
        async def load(self, request: ChatRunRequest) -> AuthorizedRunContext:
            return replace(
                await super().load(request),
                protocol_features=ProtocolFeatures(business_outcome=True),
            )

    async def scenario() -> None:
        gateway = BufferedGateway(
            error=ModelGatewayError(
                code,
                structured_response_failure=structural,
                failure_stage=failure_stage,
                usage=GatewayUsage(input_tokens=77, output_tokens=8, request_count=1),
            )
        )
        current = request().model_copy(
            update={"message": ChatMessage(message_id=str(uuid4()), content=question)}
        )
        deltas: list[str] = []
        composing: list[RunProgressStatus] = []

        async def on_delta(text: str) -> None:
            deltas.append(text)

        async def on_progress(
            stage: RunProgressStage,
            status: RunProgressStatus,
            current: int | None,
            total: int | None,
        ) -> None:
            if stage is RunProgressStage.COMPOSING_RESPONSE:
                composing.append(status)

        result = await CommerceAssistantGraph(
            OutcomeContext(), gateway=gateway, prompt_set=prompts()
        ).ainvoke(current, on_delta=on_delta, on_progress=on_progress)
        assert result.business_outcome is not None
        assert result.business_outcome.model_dump(by_alias=True) == {
            "schemaVersion": 1,
            "status": "FAILED",
            "reasonCode": "READONLY_SYNTHESIS_UNAVAILABLE",
            "failureStage": "SYNTHESIS",
        }
        assert result.message == GENERAL_REVIEW_UNAVAILABLE and _CANDIDATE not in result.message
        assert deltas == []  # Only the reviewed final result may be delivered by a caller.
        assert composing == [RunProgressStatus.RUNNING, RunProgressStatus.FAILED]
        assert result.usage.model_call_count == 3 and result.usage.tool_call_count == 0
        assert result.usage.provider_input_tokens == 188
        assert result.usage.provider_output_tokens == 38
        assert gateway.answer_calls == gateway.review_calls == 1
        assert result.task_draft is None and result.interaction is None
        assert result.kind == "MESSAGE"

    asyncio.run(scenario())


def test_review_deadline_keeps_candidate_usage_without_delivery() -> None:
    gateway = BufferedGateway(error=HarnessViolation("RUN_DEADLINE_EXCEEDED", "test deadline"))
    current = request()
    counters = RuntimeHarness().new_usage_counters(current)
    deltas: list[str] = []

    async def on_delta(text: str) -> None:
        deltas.append(text)

    with pytest.raises(HarnessViolation, match="test deadline"):
        asyncio.run(
            CommerceAssistantGraph(
                LocalRunContextProvider(), gateway=gateway, prompt_set=prompts()
            ).ainvoke(current, on_delta=on_delta, usage_counters=counters)
        )
    assert deltas == [] and counters.provider_input_tokens == 111
    assert counters.provider_output_tokens == 30


def test_cancelling_legacy_review_never_emits_candidate_and_retains_usage() -> None:
    class PausedGateway(BufferedGateway):
        def __init__(self) -> None:
            super().__init__()
            self.started = asyncio.Event()
            self.release = asyncio.Event()

        async def complete_structured(
            self,
            messages: ModelMessages,
            response_model: type[_Response],
            *,
            operation: str,
            max_tokens: int,
            error_code: str,
            repair_instruction: str,
            repair_attempts: int = 1,
        ) -> tuple[_Response, GatewayUsage]:
            self.started.set()
            await self.release.wait()
            return await super().complete_structured(
                messages,
                response_model,
                operation=operation,
                max_tokens=max_tokens,
                error_code=error_code,
                repair_instruction=repair_instruction,
                repair_attempts=repair_attempts,
            )

    async def scenario() -> None:
        gateway = PausedGateway()
        current = request()
        counters = RuntimeHarness().new_usage_counters(current)
        deltas: list[str] = []

        async def on_delta(text: str) -> None:
            deltas.append(text)

        task = asyncio.create_task(
            CommerceAssistantGraph(
                LocalRunContextProvider(), gateway=gateway, prompt_set=prompts()
            ).ainvoke(current, on_delta=on_delta, usage_counters=counters)
        )
        try:
            await asyncio.wait_for(gateway.started.wait(), timeout=2)
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
            assert task.cancelled() and deltas == []
            assert counters.provider_input_tokens == 111 and counters.provider_output_tokens == 30
            assert gateway.answer_calls == 1 and gateway.review_calls == 0
        finally:
            if not task.done():
                task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    asyncio.run(scenario())


def test_local_fixture_does_not_use_review_and_legacy_unconfigured_behavior_is_preserved() -> None:
    configured = prompts()
    local = LocalModelGateway()
    result = asyncio.run(
        CommerceAssistantGraph(
            LocalRunContextProvider(), gateway=local, prompt_set=configured
        ).ainvoke(request())
    )
    assert "离线 Agent 演示" in result.message
    assert result.message != GENERAL_REVIEW_UNAVAILABLE
    assert replace(configured, general_response_review=None).general_response_review is None
