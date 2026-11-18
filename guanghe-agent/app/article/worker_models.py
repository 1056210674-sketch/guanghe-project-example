"""Normalized ARTICLE values shared by the Worker and Java control-plane adapter."""

from __future__ import annotations

from dataclasses import dataclass
from html.parser import HTMLParser

from app.article.models import (
    ArticleBody,
    ArticleBodyBlock,
    ArticleExecutionRequest,
    ArticleExecutionResult,
    ArticleStage,
)


@dataclass(frozen=True, slots=True)
class ArticleOutlineSnapshot:
    key: str
    sequence: int
    level: int
    heading: str
    brief: str

    def __post_init__(self) -> None:
        if not 1 <= self.sequence <= 100 or not 1 <= self.level <= 6:
            raise ValueError("ARTICLE outline position is outside the frozen Java bounds")
        if not self.brief.strip():
            raise ValueError("ARTICLE outline brief is required")


@dataclass(frozen=True, slots=True)
class ArticleExecutionContext:
    task_id: str
    execution_no: int
    article_version: int
    request: ArticleExecutionRequest
    authorized_outline: tuple[ArticleOutlineSnapshot, ...]

    def __post_init__(self) -> None:
        if self.execution_no < 1 or self.article_version < 1:
            raise ValueError("ARTICLE execution identity is invalid")
        if self.request.mode in {
            ArticleStage.BODY,
            ArticleStage.REWRITE,
        } and tuple(
            (item.key, item.sequence, item.level, item.heading, item.brief)
            for item in self.authorized_outline
        ) != tuple(
            (item.key, item.sequence, item.level, item.heading, item.brief)
            for item in self.request.confirmed_outline
        ):
            raise ValueError("ARTICLE authorized outline does not match the execution request")


@dataclass(frozen=True, slots=True)
class ArticleCompletion:
    article_version: int
    result: ArticleExecutionResult
    outline: tuple[ArticleOutlineSnapshot, ...]
    body: ArticleBody | None


@dataclass(frozen=True, slots=True)
class ArticleExecutionOutcome:
    succeeded: bool
    java_task_status: str | None = None
    stable_error_code: str | None = None


class _CanonicalArticleBodyParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.blocks: list[tuple[str, int, str, str]] = []
        self._key: str | None = None
        self._level: int | None = None
        self._heading: list[str] = []
        self._markdown: list[str] = []
        self._part: str | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "section" and self._key is None and self._part is None:
            if len(attrs) != 1 or attrs[0][0] != "data-article-block-id" or not attrs[0][1]:
                raise ValueError("ARTICLE current body section is invalid")
            self._key = attrs[0][1]
            return
        if tag in {f"h{level}" for level in range(1, 7)} and self._key is not None:
            if attrs or self._part is not None or self._level is not None:
                raise ValueError("ARTICLE current body heading is invalid")
            self._level = int(tag[1])
            self._part = "heading"
            return
        if tag == "p" and self._key is not None:
            if attrs or self._part is not None or self._level is None:
                raise ValueError("ARTICLE current body paragraph is invalid")
            self._part = "markdown"
            return
        if tag == "br" and self._part == "markdown" and not attrs:
            self._markdown.append("\n")
            return
        raise ValueError("ARTICLE current body contains an unsupported element")

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "br":
            self.handle_starttag(tag, attrs)
            return
        raise ValueError("ARTICLE current body contains an unsupported empty element")

    def handle_endtag(self, tag: str) -> None:
        if tag in {f"h{level}" for level in range(1, 7)} and self._part == "heading":
            if self._level != int(tag[1]):
                raise ValueError("ARTICLE current body heading is unbalanced")
            self._part = None
            return
        if tag == "p" and self._part == "markdown":
            self._part = None
            return
        if tag == "section" and self._key is not None and self._part is None:
            if self._level is None:
                raise ValueError("ARTICLE current body section is incomplete")
            self.blocks.append(
                (
                    self._key,
                    self._level,
                    "".join(self._heading).strip(),
                    "".join(self._markdown).strip(),
                )
            )
            self._key = None
            self._level = None
            self._heading.clear()
            self._markdown.clear()
            return
        raise ValueError("ARTICLE current body is unbalanced")

    def handle_data(self, data: str) -> None:
        if self._part == "heading":
            self._heading.append(data)
        elif self._part == "markdown":
            self._markdown.append(data)
        elif data.strip():
            raise ValueError("ARTICLE current body contains text outside a canonical block")

    def handle_comment(self, data: str) -> None:
        del data
        raise ValueError("ARTICLE current body comments are forbidden")

    @property
    def complete(self) -> bool:
        return self._key is None and self._part is None


def parse_canonical_article_body(
    html: str,
    outline: tuple[ArticleOutlineSnapshot, ...],
) -> ArticleBody:
    parser = _CanonicalArticleBodyParser()
    parser.feed(html)
    parser.close()
    if not parser.complete:
        raise ValueError("ARTICLE current body is incomplete")
    expected = tuple((item.key, item.level, item.heading) for item in outline)
    actual = tuple((key, level, heading) for key, level, heading, _ in parser.blocks)
    if actual != expected:
        raise ValueError("ARTICLE current body does not match the authorized outline")
    blocks = tuple(
        ArticleBodyBlock(key=key, heading=heading, markdown=markdown)
        for key, _level, heading, markdown in parser.blocks
    )
    if not blocks:
        raise ValueError("ARTICLE current body is empty")
    from app.article.models import render_article_markdown

    return ArticleBody(markdown=render_article_markdown(blocks), blocks=blocks)


__all__ = [
    "ArticleCompletion",
    "ArticleExecutionContext",
    "ArticleExecutionOutcome",
    "ArticleOutlineSnapshot",
    "parse_canonical_article_body",
]
