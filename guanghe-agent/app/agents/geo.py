"""GEO-specific context projections; the model chooses every native action."""

from typing import Any

from app.agent.brand_brief import project_brand_brief
from app.agents.behavior import NativeBehavior
from app.providers.java_context import AuthorizedRunContext
from app.providers.knowledge_context import ProjectMemoryContext


class GeoBehavior(NativeBehavior):
    def project_context(
        self,
        snapshot: dict[str, Any],
        context: AuthorizedRunContext,
        memory: ProjectMemoryContext | None,
        current_brand_identity: dict[str, Any] | None,
    ) -> None:
        snapshot["brandBrief"] = project_brand_brief(context, memory, current_brand_identity)
        if current_brand_identity is not None:
            snapshot["currentBrandIdentity"] = current_brand_identity
