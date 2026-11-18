"""Run-local GEO continuation proofs; Java remains the admission authority.

This module interprets only completed Java context reads. It never calls Java,
executes tools, spends budget or treats model arguments as authorization.
"""

from __future__ import annotations

import re
from typing import Annotated, Literal

from pydantic import Field, JsonValue, StrictBool, StringConstraints, ValidationError

from app.agent.identity import current_agent_execution, tool_available
from app.agent.models import ChatRunRequest
from app.agents.catalog import load_catalog
from app.providers.java_context import AuthorizedRunContext, current_authorized_project_id
from app.tools.native_authorization import user_requested_native_actions
from app.tools.registry import ToolGroup, ToolRuntimeModel


class ExistingArticlePreparation(ToolRuntimeModel):
    id: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    article_id: str | None = Field(pattern=r"^[1-9][0-9]{0,18}$")
    status: Literal["RUNNING", "SUCCEEDED", "FAILED"]


class ArticleContinuation(ToolRuntimeModel):
    """Scoped raw conversation candidate, never an execution authorization."""

    authorized: StrictBool
    review_required: StrictBool
    action: Literal["article.generate"]
    current_message_id: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    conversation_id: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    project_id: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    source_message_id: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    offer_message_id: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    geo_report_id: str | None = Field(default=None, pattern=r"^[1-9][0-9]{0,18}$")
    request_text: Annotated[
        str, StringConstraints(strip_whitespace=True, min_length=1, max_length=4000)
    ]
    source_text: Annotated[str, StringConstraints(min_length=1, max_length=4000)]
    offer_text: Annotated[str, StringConstraints(min_length=1, max_length=4000)]
    submission_state: Literal["NOT_ATTEMPTED", "ALREADY_STARTED"]
    preparation: ExistingArticlePreparation | None = None


class GeoContinuations:
    """One Run's temporary GEO grant state, separate from generic tool execution."""

    def __init__(
        self,
        request: ChatRunRequest,
        context: AuthorizedRunContext,
        *,
        article_allowed: bool,
        monitor_allowed: bool,
    ) -> None:
        self.request = request
        self.context = context
        self._article_allowed = article_allowed
        self._monitor_allowed = monitor_allowed
        self.article_attempted = False
        self.article_result: str | None = None
        self._article_candidate: ArticleContinuation | None = None
        self._other_write_attempted = False

    def revoke_article(self, *, other_write: bool = False) -> bool:
        """Revoke before a fresh read/other mutation, including unsuccessful ones."""
        self._other_write_attempted |= other_write
        had_candidate = self._article_candidate is not None
        self._article_candidate = None
        return had_candidate

    def begin_article_attempt(self) -> None:
        # Consume before any await; rejection, cancellation and unknown writes
        # must not restore an offer in this Run.
        self.article_attempted = True
        self._article_candidate = None

    def accept_read(
        self,
        data: dict[str, JsonValue],
        *,
        has_unknown_write: bool,
        has_task_receipt: bool,
    ) -> tuple[str, ...]:
        """Sanitize continuation projections and return only proven tool names."""
        restored: list[str] = []
        if self._accept_monitor(data):
            restored.append("geo.monitor")
        else:
            data.pop("monitorContinuation", None)
        if not self._accept_article(
            data,
            has_unknown_write=has_unknown_write,
            has_task_receipt=has_task_receipt,
        ):
            data.pop("articleContinuation", None)
        return tuple(restored)

    def _accept_article(
        self,
        data: dict[str, JsonValue],
        *,
        has_unknown_write: bool,
        has_task_receipt: bool,
    ) -> bool:
        if (
            not self._article_allowed
            or self.context.source != "JAVA_AUTHORIZED_CONTEXT"
            or current_authorized_project_id(self.context) is None
            or not tool_available("article.generate")
        ):
            return False
        try:
            candidate = ArticleContinuation.model_validate(data.get("articleContinuation"))
        except ValidationError:
            return False
        if (
            candidate.current_message_id != self.request.message.message_id
            or candidate.conversation_id != self.request.conversation_id
            or candidate.project_id != current_authorized_project_id(self.context)
            or len(
                {
                    candidate.current_message_id,
                    candidate.source_message_id,
                    candidate.offer_message_id,
                }
            )
            != 3
        ):
            return False
        if candidate.submission_state == "ALREADY_STARTED":
            # Existing jobs stay readable without ever becoming a new task.
            return (
                not candidate.authorized
                and not candidate.review_required
                and candidate.preparation is not None
                and (
                    candidate.preparation.status != "SUCCEEDED"
                    or candidate.preparation.article_id is not None
                )
            )
        if (
            candidate.authorized
            or not candidate.review_required
            or candidate.preparation is not None
            or has_unknown_write
            or has_task_receipt
            or self.article_attempted
            or self._other_write_attempted
        ):
            return False
        if not candidate.source_text.strip() or not candidate.offer_text.strip():
            return False
        # Java supplies the scoped raw messages, not a task authorization. When
        # the same messages are also present in the run snapshot, disagreement
        # about their role/content/order makes the candidate unusable.
        memory = self.context.conversation_memory
        if memory is not None:
            expected = (
                (candidate.source_message_id, "USER", candidate.source_text),
                (candidate.offer_message_id, "ASSISTANT", candidate.offer_text),
                (candidate.current_message_id, "USER", self.request.message.content),
            )
            sequences: list[int] = []
            for message_id, role, text in expected:
                matches = [m for m in memory.messages if m.message_id == message_id]
                if len(matches) > 1:
                    return False
                if matches:
                    message = matches[0]
                    if message.role != role or message.content.get("text") != text:
                        return False
                    sequences.append(message.sequence)
            if sequences != sorted(set(sequences)):
                return False
        self._article_candidate = candidate
        return True

    def article_candidate(
        self, *, has_task_receipt: bool, has_unknown_write: bool
    ) -> ArticleContinuation | None:
        if (
            self.article_attempted
            or has_task_receipt
            or has_unknown_write
            or self._other_write_attempted
            or not tool_available("article.generate")
            or ToolGroup.TASK_PREPARATION.value not in self.context.loaded_tool_groups
        ):
            return None
        return self._article_candidate

    def _accept_monitor(self, data: dict[str, JsonValue]) -> bool:
        candidate = data.get("monitorContinuation")
        if (
            not self._monitor_allowed
            or self.context.source != "JAVA_AUTHORIZED_CONTEXT"
            or current_authorized_project_id(self.context) is None
            or not _is_monitor_submit_confirmation(self.request.message.content)
            or not isinstance(candidate, dict)
            or candidate.get("currentMessageId") != self.request.message.message_id
            or candidate.get("conversationId") != self.request.conversation_id
            or candidate.get("projectId") != current_authorized_project_id(self.context)
            or candidate.get("submissionState") != "NOT_ATTEMPTED"
            or not isinstance(candidate.get("sourceMessageId"), str)
            or not re.fullmatch(r"[1-9][0-9]{0,18}", str(candidate["sourceMessageId"]))
            or candidate["sourceMessageId"] == self.request.message.message_id
            or not isinstance(candidate.get("requestText"), str)
            or not tool_available("geo.monitor")
        ):
            return False
        previous_request = str(candidate["requestText"])
        # A bare retry/assistant suggestion must not become a paid monitor.
        unquoted_request = re.sub(
            r"```[\s\S]*?```|`[^`]*`|“[^”]*”|\u2018[^\u2019]*\u2019|\"[^\"]*\"",
            "",
            previous_request,
        )
        if re.search(r"监测|检测|可见度", unquoted_request) is None:
            return False
        requested = user_requested_native_actions(previous_request)
        if requested.intersection({"geo.monitor", "article.generate"}) != frozenset(
            {"geo.monitor"}
        ):
            return False
        candidate["currentSubmitAuthorized"] = True
        return True


def geo_continuations_for_run(
    request: ChatRunRequest, context: AuthorizedRunContext
) -> GeoContinuations | None:
    execution = current_agent_execution()
    if execution is None:
        # Direct historical callers predate RunManager's identity binding. Only
        # their absent identity is compatible; a supplied identity is not GEO.
        if context.agent_identity is not None:
            return None
        registration = load_catalog().agents[("geo", "1.0.0")]
    else:
        registration = execution.registration
    article_allowed = registration.continuation_policy == "geo-article" and tool_available(
        "article.generate"
    )
    monitor_allowed = registration.context_policy == "geo-brand" and tool_available("geo.monitor")
    if not article_allowed and not monitor_allowed:
        return None
    return GeoContinuations(
        request,
        context,
        article_allowed=article_allowed,
        monitor_allowed=monitor_allowed,
    )


def _is_monitor_submit_confirmation(content: str) -> bool:
    """Deliberate submit only; '好的/继续' must not revive a paid task."""
    return (
        re.fullmatch(
            r"(?:那|好[的吧]?[,，]?)?(?:请|现在|直接)?(?:提交|确认提交|可以提交|执行|开始执行)(?:吧|一下)?[。\uff01!]?",
            "".join(content.split()),
        )
        is not None
    )
