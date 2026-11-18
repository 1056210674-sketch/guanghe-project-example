"""Fixed adapters for Java-owned explicit actions, without an LLM router or graph.

These controls are current signed context, not instructions inferred from chat.
Java still owns admission, idempotency, billing, and the eventual worker task.
"""

from typing import cast

from app.agent.graphs.domains import (
    _MonitorTaskDraftInput,  # pyright: ignore[reportPrivateUsage] - signed program-only input
    prepare_task_draft,
    prepare_task_draft_input,
)
from app.agent.identity import AgentIdentityError, require_capability
from app.agent.models import (
    BusinessOutcome,
    BusinessOutcomeStatus,
    ChatRunRequest,
    Intent,
    RunOutcome,
    RunProgressStage,
    RunProgressStatus,
    TaskDraftProposal,
)
from app.agent.progress import RunProgressHandler, notify_progress
from app.harness.runtime import RuntimeHarness, RuntimeUsageCounters
from app.model_gateway.gateway import DeltaHandler, ModelGateway
from app.providers.java_context import AuthorizedRunContext
from app.tools.registry import DEFAULT_TOOL_REGISTRY, ToolExecutor, ToolGroup, ToolPolicy


class ExplicitActionAdapter:
    def __init__(self, gateway: ModelGateway) -> None:
        self._gateway = gateway
        self._harness = RuntimeHarness()
        self._tools = ToolExecutor(
            DEFAULT_TOOL_REGISTRY,
            ToolPolicy(
                allowed_tool_names=frozenset({"task_draft.propose"}),
                allowed_tool_groups=frozenset(ToolGroup),
            ),
            {"task_draft.propose": prepare_task_draft_input},
            {"task_draft.propose": prepare_task_draft},
        )

    @staticmethod
    def graph_for_intent(intent: Intent) -> str:
        # The external event retains its historical field name for old clients.
        return "explicit-action-v1"

    async def ainvoke(
        self,
        request: ChatRunRequest,
        *,
        authorized_context: AuthorizedRunContext,
        usage_counters: RuntimeUsageCounters,
        on_delta: DeltaHandler | None = None,
        on_progress: RunProgressHandler | None = None,
    ) -> RunOutcome:
        context, counters = authorized_context, usage_counters
        counters.before_workflow_step()
        self._harness.validate_action_deadline(request)
        if context.source != "JAVA_AUTHORIZED_CONTEXT":
            raise AgentIdentityError("AGENT_CAPABILITY_DENIED")
        draft = None
        if context.article_preparation_mode == "ARTICLE_FULL_V1":
            require_capability("article.generate")
            intent = Intent.ARTICLE
            message = "已收到完整文章要求，接下来会生成稿件供你审阅；发布前仍需你确认。"
        else:
            require_capability("geo.monitor")
            monitor = context.monitor_action_context
            if (
                monitor is None
                or context.selected_space_id != monitor.space_id
                or context.selected_product_id != monitor.product_id
                or request.trusted_state.selected_space_id != monitor.space_id
                or request.trusted_state.selected_product_id != monitor.product_id
            ):
                raise AgentIdentityError("AGENT_CAPABILITY_DENIED")
            intent = Intent.GEO
            await notify_progress(
                on_progress, RunProgressStage.PREPARING_TASK_DRAFT, RunProgressStatus.RUNNING
            )
            try:
                execution = await self._tools.execute(
                    run_id=request.run_id,
                    name="task_draft.propose",
                    version="1",
                    payload=_MonitorTaskDraftInput(
                        intent=Intent.GEO,
                        request_text=request.message.content,
                        selected_space_id=monitor.space_id,
                        selected_product_id=monitor.product_id,
                        candidate_summary="按本轮明确选择的范围提议单次监测",
                        candidate_parameters=monitor.parameters.model_dump(
                            mode="json", by_alias=True
                        ),
                        monitor_action_context=monitor,
                    ),
                    counters=counters,
                    authorized_tool_groups=context.loaded_tool_groups,
                )
            except Exception:
                await notify_progress(
                    on_progress, RunProgressStage.PREPARING_TASK_DRAFT, RunProgressStatus.FAILED
                )
                raise
            await notify_progress(
                on_progress, RunProgressStage.PREPARING_TASK_DRAFT, RunProgressStatus.SUCCEEDED
            )
            draft = cast(TaskDraftProposal, execution.output)
            message = "已按你选择的范围整理监测提案；尚未开始执行，是否受理请以任务状态为准。"
        if on_delta is not None:
            await on_delta(message)
        outcome = RunOutcome(
            kind="TASK_DRAFT" if draft is not None else "MESSAGE",
            intent=intent,
            message=message,
            task_draft=draft,
            usage=counters.usage(
                provider=self._gateway.identity.provider, model=self._gateway.identity.model
            ),
            business_outcome=BusinessOutcome(status=BusinessOutcomeStatus.ANSWERED),
        )
        self._harness.validate_outcome(request, outcome)
        return outcome
