"""Small native-loop extension points shared by the built-in Agent modules."""

from dataclasses import dataclass
from typing import Any

from app.agent.progress import RunProgressHandler
from app.harness.runtime import RuntimeUsageCounters
from app.providers.java_context import AuthorizedRunContext
from app.providers.knowledge_context import ProjectMemoryContext
from app.tools.native import NativeTools


@dataclass(frozen=True, slots=True)
class ContinuationResult:
    message: str
    completed: bool


class NativeBehavior:
    """Default behavior has no brand projection or automatic business continuation."""

    def project_context(
        self,
        snapshot: dict[str, Any],
        context: AuthorizedRunContext,
        memory: ProjectMemoryContext | None,
        current_brand_identity: dict[str, Any] | None,
    ) -> None:
        del snapshot, context, memory, current_brand_identity

    async def after_tool_batch(
        self,
        tools: NativeTools,
        counters: RuntimeUsageCounters,
        on_progress: RunProgressHandler | None,
    ) -> ContinuationResult | None:
        del tools, counters, on_progress
        return None
