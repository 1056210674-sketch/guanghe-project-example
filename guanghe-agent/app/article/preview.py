"""Render only observed BODY JSON strings as an explicitly unfinished preview."""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from time import monotonic
from typing import cast

from pydantic import JsonValue
from pydantic_core import from_json

from app.article.models import ArticleOutlineItem

ArticlePreviewHandler = Callable[[int, str], Awaitable[None]]


class ArticleBodyPreview:
    """Decode partial JSON, never invent body text or accept it as the final result."""

    def __init__(
        self,
        outline: tuple[ArticleOutlineItem, ...],
        on_preview: ArticlePreviewHandler,
    ) -> None:
        self._outline = outline
        self._on_preview = on_preview
        self._attempt = 0
        self._raw = ""
        self._markdown = ""
        self._sent = ""
        self._last_sent_at = 0.0

    async def on_delta(self, attempt: int, delta: str) -> None:
        if attempt != self._attempt:
            self._attempt = attempt
            self._raw = self._markdown = self._sent = ""
            await self._emit()
        self._raw += delta
        markdown = self._decode()
        if markdown is None or not markdown.startswith(self._markdown):
            return
        self._markdown = markdown[:120_000]
        if self._markdown != self._sent and monotonic() - self._last_sent_at >= 0.5:
            await self._emit()

    async def flush(self) -> None:
        if self._markdown != self._sent:
            await self._emit()

    async def _emit(self) -> None:
        remaining = 0.5 - (monotonic() - self._last_sent_at)
        if remaining > 0:
            await asyncio.sleep(remaining)
        await self._on_preview(self._attempt, self._markdown)
        self._sent = self._markdown
        self._last_sent_at = monotonic()

    def _decode(self) -> str | None:
        try:
            decoded = cast(JsonValue, from_json(self._raw, allow_partial="trailing-strings"))
        except ValueError:
            return None
        if not isinstance(decoded, dict) or not isinstance(decoded.get("blocks"), list):
            return None
        blocks = decoded["blocks"]
        assert isinstance(blocks, list)
        parts: list[str] = []
        for index, block in enumerate(blocks):
            if index >= len(self._outline) or not isinstance(block, dict):
                break
            expected = self._outline[index]
            markdown = block.get("markdown")
            if (
                block.get("key") != expected.key
                or block.get("heading") != expected.heading
                or not isinstance(markdown, str)
            ):
                break
            parts.append(f"## {expected.heading}\n\n{markdown}")
        return "\n\n".join(parts)
