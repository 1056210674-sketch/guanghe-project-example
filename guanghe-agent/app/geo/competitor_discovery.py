"""Verify model candidate quotes locally; only Java may persist or confirm competitors."""

from __future__ import annotations

import re
import unicodedata
from html.parser import HTMLParser

from pydantic import BaseModel

from app.geo.models import (
    GeoCompetitorCandidate,
    GeoCompetitorEvidence,
    GeoObservation,
    GeoObservationStatus,
)
from app.geo.worker_models import GeoSynthesisContext

_NON_ENTITIES = frozenset(
    {
        "京东",
        "淘宝",
        "天猫",
        "拼多多",
        "抖音",
        "快手",
        "小红书",
        "苏宁",
        "亚马逊",
        "知乎",
        "百度",
        "搜狐",
        "新浪",
        "网易",
        "腾讯",
        "什么值得买",
        "值得买",
        "人民日报",
        "新华社",
        "央视新闻",
        "人民网",
        "新华网",
        "中新网",
        "中国新闻网",
        "澎湃新闻",
        "腾讯新闻",
        "新浪新闻",
        "网易新闻",
        "搜狐新闻",
        "今日头条",
        "百度百科",
        "jd",
        "jd.com",
        "tmall",
        "taobao",
        "amazon",
        "1688",
        "alibaba",
        "商品",
        "产品",
        "品牌",
        "保温杯",
        "不锈钢保温杯",
        "304不锈钢",
        "316不锈钢",
        "不锈钢",
        "咖啡",
        "咖啡机",
        "美式咖啡",
        "水杯",
        "杯子",
        "手机",
        "服装",
        "product",
        "brand",
        "coffee",
        "参考资料",
        "引用来源",
    }
)
# Match explicit entity suffixes, not substrings or the generic character 店.
# For example 京东京造 and 商城牌保温杯 are still eligible concrete names.
_NON_ENTITY_SUFFIXES = (
    "旗舰店",
    "专卖店",
    "专营店",
    "直营店",
    "官方店",
    "官方网店",
    "自营店",
    "官方商城",
    "商城",
    "购物中心",
    "购物广场",
    "百货商场",
    "新闻网",
    "新闻网站",
    "门户网站",
    "资讯网",
    "媒体网",
    "官方网站",
    "官网",
    "店铺",
    "购物平台",
)
_SOURCE_HEADING = re.compile(
    r"(?im)^\s*(?:#{1,6}\s*)?(?:\*\*)?(?:参考资料|参考来源|引用来源|参考文献|资料来源|来源|sources|references)"
    r"(?:\*\*)?\s*(?:[:\uff1a]|$)"
)
_URL = re.compile(r"(?i)\b(?:https?://|www\.)[^\s<>\[\]\"']+")


class _VisibleBody(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.hidden: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        style = (values.get("style") or "").replace(" ", "").casefold()
        hide = (
            tag in {"script", "style", "template", "noscript"}
            or "hidden" in values
            or values.get("aria-hidden") == "true"
            or "display:none" in style
            or "visibility:hidden" in style
        )
        if tag not in {"img", "br", "hr", "input", "meta", "link", "wbr"} and (self.hidden or hide):
            self.hidden.append(tag)

    def handle_endtag(self, tag: str) -> None:
        if tag in self.hidden:
            last = len(self.hidden) - 1 - self.hidden[::-1].index(tag)
            del self.hidden[last:]

    def handle_data(self, data: str) -> None:
        if not self.hidden:
            self.parts.append(data)


def _body(answer: str) -> str:
    heading = _SOURCE_HEADING.search(answer)
    return answer[: heading.start()] if heading else answer


def _visible(text: str, *, complete: bool = True) -> str:
    # A source-list title and a URL destination are not an answer's brand mention.
    text = re.sub(r"(?m)^ {0,3}\[[^\]\r\n]+\]:[^\r\n]*(?:\r?\n|$)", " ", text)
    text = re.sub(r"!\[[^\]\r\n]*\]\([^\r\n]*?\)", " ", text)
    # A line consisting only of a citation link is a source, not prose recommending its title.
    text = re.sub(
        r"(?m)^\s*(?:[-*]|\d+[.)]|\[\d+\])?\s*\[[^\]\r\n]+\]"
        r"\(https?://[^\r\n]+\)\s*$",
        " ",
        text,
    )
    visible: list[str] = []
    index = 0
    while index < len(text):
        value = text[index]
        visible.append(value)
        if value == "]" and text[index + 1 : index + 2] == "(":
            end, depth = index + 2, 1
            while end < len(text) and depth:
                if text[end] == "\\" and end + 1 < len(text):
                    end += 2
                    continue
                depth += (text[end] == "(") - (text[end] == ")")
                end += 1
            if depth == 0:
                index = end - 1
        index += 1
    without_urls = _URL.sub(" ", "".join(visible))
    parser = _VisibleBody()
    parser.feed(without_urls)
    if complete:
        parser.close()
    return "".join(parser.parts)


def _normalized(text: str) -> str:
    return unicodedata.normalize("NFKC", text).casefold().strip()


def _mentioned(name: str, text: str) -> bool:
    left = r"(?<![a-z0-9_])" if name[0].isascii() and name[0].isalnum() else ""
    right = r"(?![a-z0-9_])" if name[-1].isascii() and name[-1].isalnum() else ""
    return re.search(left + re.escape(name) + right, _normalized(text)) is not None


def _visible_quote_occurrence(name: str, quote: str, answer: str) -> bool:
    # Rendering the quotation alone loses whether its occurrence was inside an
    # HTML attribute, hidden node or URL. Require at least one visible occurrence
    # at the original position, not just the same brand elsewhere in the answer.
    start = answer.find(quote)
    # Bound repeated tiny quotes in adversarial answers; rejecting an ambiguous
    # candidate is preferable to spending unbounded CPU on extracted evidence.
    for _ in range(8):
        if start < 0:
            break
        line_start = answer.rfind("\n", 0, start) + 1
        line_end = answer.find("\n", start)
        line = answer[line_start : line_end if line_end >= 0 else len(answer)]
        if not _mentioned(name, _visible(line)):
            start = answer.find(quote, start + 1)
            continue
        before = _visible(answer[:start], complete=False)
        through = _visible(answer[: start + len(quote)], complete=False)
        if through.startswith(before) and _mentioned(name, through[len(before) :]):
            return True
        start = answer.find(quote, start + 1)
    return False


def verified_competitor_candidates(
    candidates: tuple[GeoCompetitorCandidate, ...],
    observations: tuple[GeoObservation, ...],
    context: BaseModel | None,
) -> tuple[GeoCompetitorCandidate, ...]:
    """Reject only untrusted candidate evidence, without repairing/retrying the report."""
    own_names: set[str] = set()
    if isinstance(context, GeoSynthesisContext) and context.monitoring_target:
        own_names = {
            _normalized(name)
            for name in (context.monitoring_target.name, *context.monitoring_target.aliases)
        }
    by_unit = {
        f"{item.question_id}:{item.platform_code.value}:{item.sample_no}": _body(item.answer_text)
        for item in observations
        if item.status is GeoObservationStatus.SUCCEEDED and item.answer_text
    }
    result: list[GeoCompetitorCandidate] = []
    seen: set[tuple[str, str]] = set()
    for candidate in candidates:
        name = _normalized(candidate.name)
        key = (candidate.entity_kind, name)
        if (
            len(name) < 2
            or name in _NON_ENTITIES
            or name.endswith(_NON_ENTITY_SUFFIXES)
            or any(own and _mentioned(own, name) for own in own_names)
            or key in seen
        ):
            continue
        if any(token in name for token in ("://", "www.", "<", ">", "\n")) or re.fullmatch(
            r"(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}", name
        ):
            continue
        evidence: list[GeoCompetitorEvidence] = []
        seen_evidence: set[tuple[str, str]] = set()
        for item in candidate.evidence:
            answer = by_unit.get(item.unit_key)
            quote = item.quote
            evidence_key = (item.unit_key, quote)
            if (
                answer is None
                or not quote.strip()
                or quote not in answer
                or evidence_key in seen_evidence
                or not _mentioned(name, _visible(quote))
                or not _mentioned(name, _visible(answer))
                or not _visible_quote_occurrence(name, quote, answer)
            ):
                continue
            evidence.append(item)
            seen_evidence.add(evidence_key)
        if evidence:
            result.append(candidate.model_copy(update={"evidence": tuple(evidence)}))
            seen.add(key)
    return tuple(result)
