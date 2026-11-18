"""Explicit user format constraints reuse the existing per-stage repair budget."""

# ruff: noqa: RUF001 -- Deliberate user punctuation variants exercise recognition boundaries.

from __future__ import annotations

import asyncio
import json
from typing import Any

import httpx
import pytest
from pydantic import ValidationError

from app.article.models import (
    ArticleBodyBlock,
    ArticleOutlineItem,
    ArticleStage,
    article_body_visible_character_count,
    article_visible_character_count,
    article_writing_constraints,
)
from app.article.runtime import ArticleExecutionError, ArticleExecutionKernel
from app.model_gateway.deepseek import DeepSeekAdapter
from tests.unit.test_article_execution import (
    _completion_response,  # pyright: ignore[reportPrivateUsage]
    _deepseek_settings,  # pyright: ignore[reportPrivateUsage]
    _plan,  # pyright: ignore[reportPrivateUsage]
    _prompts,  # pyright: ignore[reportPrivateUsage]
    _request,  # pyright: ignore[reportPrivateUsage]
)

_BRIEF = "当前USER确认：继续做\n\n原USER写作要求：先给选题，以及约1200–1500字的四部分写作结构。"


def test_explicit_constraints_are_same_in_messages_and_repair() -> None:
    request = _request(ArticleStage.OUTLINE, brief=_BRIEF)
    constraints = article_writing_constraints(request)
    assert constraints.section_count == 4
    assert (constraints.min_visible_characters, constraints.max_visible_characters) == (1200, 1500)
    # These helpers are deliberately pure; no model or database is called.
    kernel = object.__new__(ArticleExecutionKernel)
    kernel._prompts = _prompts()  # pyright: ignore[reportPrivateUsage]
    messages = kernel._messages(request)  # pyright: ignore[reportPrivateUsage]
    payload = json.loads(messages[1].content.split("\n", 1)[1])
    assert payload["writingConstraints"] == constraints.model_dump(by_alias=True, exclude_none=True)
    assert constraints.model_dump_json(by_alias=True, exclude_none=True) in (
        kernel._repair_instruction(request)  # pyright: ignore[reportPrivateUsage]
    )


@pytest.mark.parametrize(
    "brief",
    [
        "写一篇完整文章",
        "继续做\n\n【写作参考／非授权】助手建议：写1200–1500字的四部分文章。",
        "参考原话：‘写1200–1500字的四部分文章’。",
        '参考原话："写1200–1500字的四部分文章"。',
        "```\n写1200–1500字的四部分文章\n```",
        "```\n写1200–1500字的四部分文章",
        "> 写1200–1500字的四部分文章",
        "不要写1200–1500字的四部分文章。",
        "例如写1200–1500字的四部分文章。",
        "例如，写1200–1500字的四部分文章。",
        "写1200–1500字，或者1800–2200字。",
        "写1200–1500字的四部分文章，改写1800–2200字的五部分文章。",
        "文章分4–6部分",
        "文章分四到六部分",
        "开头写100–200字。",
        "每节写100–200字。",
        "每部分写300–400字。",
        "每一节写300–400字。",
        "每一段写300–400字。",
        "上一版写了四部分，这次请重写。",
        "原来四部分太散，重新组织文章。",
        "参考稿共四章，写新的文章。",
        "正文最后两节讨论案例，前面保持不变。",
        "文章的第二部分写三节，其他部分不变。",
        "参考资料：某杂志要求文章800–1000字。请根据其内容另写完整稿。",
        "参考资料：某杂志要求如下\n全文控制在1200–1500字。",
        "原USER写作要求：参考资料：某杂志要求如下\n全文1200–1500字。",
        "客户过去的文章有1200–1500字。今天帮我写篇适合公众号的稿子。",
        "写1500–1200字的文章。",
        "写00–100字的文章。",
    ],
)
def test_ambiguous_old_or_non_authoritative_text_adds_no_constraints(brief: str) -> None:
    value = article_writing_constraints(_request(ArticleStage.OUTLINE, brief=brief))
    assert value.section_count is None
    assert value.min_visible_characters is None
    assert value.max_visible_characters is None


def test_neutral_current_explicit_request_still_gets_its_format_not_history() -> None:
    value = article_writing_constraints(
        _request(
            ArticleStage.OUTLINE,
            brief="写一篇中立文章，全文1200到1500字，分成四部分。"
            "\n\n【写作参考／非授权】旧稿五部分1800–2200字。",
        )
    )
    assert value.section_count == 4
    assert value.max_visible_characters == 1500
    rewrite = article_writing_constraints(_request(ArticleStage.REWRITE, brief=_BRIEF))
    assert rewrite.section_count is None and rewrite.min_visible_characters is None


def test_section_count_does_not_promote_per_section_budget_to_whole_body() -> None:
    value = article_writing_constraints(
        _request(ArticleStage.OUTLINE, brief="全文四部分，每一部分写300–400字。")
    )
    assert value.section_count == 4 and value.min_visible_characters is None
    nested = article_writing_constraints(
        _request(ArticleStage.OUTLINE, brief="文章写四部分，其中第一部分300–400字，其余按需。")
    )
    assert nested.section_count == 4 and nested.min_visible_characters is None


def test_original_plan_only_request_keeps_article_format_after_short_confirmation() -> None:
    request = _request(
        ArticleStage.OUTLINE,
        brief="当前USER确认：继续做\n\n原USER写作要求：请使用本项目已经确认的资料，"
        "先给一个面向潜在客户的文章选题，以及约 1200–1500 字的四部分写作结构。"
        "本轮只讨论选题和结构，先不要生成正文，不要启动监测。"
        "请在最后提出按这个选题和结构写一篇完整稿的单一提议，等我答应后再写。"
        "\n\n【写作参考／非授权】助手建议：写五节、3000字。",
    )
    constraints = article_writing_constraints(request)
    assert constraints.section_count == 4
    assert constraints.min_visible_characters == 1200 and constraints.max_visible_characters == 1500


@pytest.mark.parametrize(
    ("markdown", "expected"),
    [
        ("## 标题\n\n**结论**。\n\n- 条目", len("标题结论。条目")),
        ("| 维度 | 判断 |\n|---|---|\n| **甲** | 乙 |", len("维度判断甲乙")),
        (r"\*不是强调\* &amp; &#x4e2d;", len("*不是强调*&中")),
        ("```text\n甲 乙\n```\n\n`丙 丁`", len("甲乙丙丁")),
        ("[正文][r]\n\n[r]: https://example.test/hidden", len("正文")),
        ("![图片替代文本](https://example.test/image.png)\n\n正文", len("正文")),
        ("<!-- shown -->\n\n正文", len("<!--shown-->正文")),
        ("## **标题**\n\n正文", len("标题正文")),
    ],
)
def test_visible_character_count_uses_commonmark_not_source_length(
    markdown: str, expected: int
) -> None:
    assert article_visible_character_count(markdown) == expected


def test_body_count_matches_java_literal_headings_and_per_block_markdown() -> None:
    blocks = (
        ArticleBodyBlock(key="section-1", heading="**标题**", markdown="正文[标签][ref]"),
        ArticleBodyBlock(key="section-2", heading="标题 &amp;", markdown="[ref]: /target\n\n正文"),
    )
    assert article_body_visible_character_count(blocks) == len(
        "**标题**正文[标签][ref]标题&amp;正文"
    )


def _four_sections() -> tuple[ArticleOutlineItem, ...]:
    return tuple(
        ArticleOutlineItem(
            key=f"section-{i}", sequence=i, level=2, heading=f"问题{i}", brief="具体判断"
        )
        for i in range(1, 5)
    )


def _format_payload(mode: ArticleStage, *, invalid: bool, mismatch: bool = False) -> dict[str, Any]:
    sections = _four_sections()
    if mode is ArticleStage.OUTLINE:
        if invalid:
            sections += (
                ArticleOutlineItem(
                    key="section-5", sequence=5, level=2, heading="重复结尾", brief="收尾"
                ),
            )
        return {
            "plan": _plan()
            .model_copy(update={"sections": sections})
            .model_dump(mode="json", by_alias=True)
        }
    total = 1935 if invalid and not mismatch else 1300
    blocks = [{"key": s.key, "heading": s.heading, "markdown": "问"} for s in sections]
    blocks[0]["markdown"] = "读" * (total - sum(len(s.heading) for s in sections) - 3)
    if invalid and mismatch:
        blocks[0]["heading"] = "错误标题"
    return {"blocks": blocks, "warnings": []}


@pytest.mark.parametrize(
    ("count", "valid"), [(1199, False), (1200, True), (1500, True), (1501, False)]
)
def test_visible_length_bounds_are_inclusive_without_extra_tolerance(
    count: int, valid: bool
) -> None:
    request = _request(
        ArticleStage.BODY,
        brief=_BRIEF,
        confirmed_outline=_four_sections(),
        confirmed_plan=_plan().model_copy(update={"sections": _four_sections()}),
    )
    model = ArticleExecutionKernel._response_model(ArticleStage.BODY, request=request)  # pyright: ignore[reportPrivateUsage]
    payload = _format_payload(ArticleStage.BODY, invalid=False)
    payload["blocks"][0]["markdown"] = "读" * (count - 15)
    if valid:
        assert model.model_validate(payload)
    else:
        with pytest.raises(ValidationError, match="ARTICLE_EXPLICIT_LENGTH_MISMATCH"):
            model.model_validate(payload)


@pytest.mark.parametrize(
    ("mode", "mismatch"),
    [(ArticleStage.OUTLINE, False), (ArticleStage.BODY, False), (ArticleStage.BODY, True)],
)
def test_existing_single_repair_corrects_count_length_and_frozen_outline(
    mode: ArticleStage, mismatch: bool
) -> None:
    sent: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        sent.append(json.loads(request.content))
        return _completion_response(
            json.dumps(_format_payload(mode, invalid=len(sent) == 1, mismatch=mismatch))
        )

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            request = _request(mode, brief=_BRIEF)
            if mode is ArticleStage.BODY:
                request = request.model_copy(
                    update={
                        "confirmed_outline": _four_sections(),
                        "confirmed_plan": _plan().model_copy(update={"sections": _four_sections()}),
                    }
                )
            result = await ArticleExecutionKernel(
                DeepSeekAdapter(_deepseek_settings(), client=client), _prompts()
            ).execute(request)
            assert result.usage.model_call_count == 2
            assert result.usage.input_tokens == 22 and result.usage.output_tokens == 6
            assert len(result.outline) == 4
            if result.body is not None:
                assert article_visible_character_count(result.body.markdown) == 1300

    asyncio.run(scenario())
    assert len(sent) == 2
    assert sent[0]["messages"][1:] == sent[1]["messages"][1:]
    assert '"sectionCount":4' in sent[1]["messages"][0]["content"]
    assert '"minVisibleCharacters":1200' in sent[1]["messages"][0]["content"]


def test_explicit_constraint_failure_does_not_add_a_third_request_or_truncate() -> None:
    sent: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        sent.append(request)
        return _completion_response(json.dumps(_format_payload(ArticleStage.OUTLINE, invalid=True)))

    async def scenario() -> None:
        async with httpx.AsyncClient(
            base_url="https://deepseek.test", transport=httpx.MockTransport(handler)
        ) as client:
            with pytest.raises(
                ArticleExecutionError, match="DEEPSEEK_ARTICLE_OUTLINE_RESPONSE_INVALID"
            ) as captured:
                await ArticleExecutionKernel(
                    DeepSeekAdapter(_deepseek_settings(), client=client), _prompts()
                ).execute(_request(ArticleStage.OUTLINE, brief=_BRIEF))
            assert captured.value.usage.request_count == 2

    asyncio.run(scenario())
    assert len(sent) == 2
