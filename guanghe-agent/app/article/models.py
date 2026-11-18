"""Strict internal models for the bounded ARTICLE stage pipeline."""

from __future__ import annotations

import json
import re
import unicodedata
from datetime import datetime
from enum import StrEnum
from typing import Annotated, Literal, Self, cast

from markdown_it import MarkdownIt
from markdown_it.token import Token
from pydantic import AfterValidator, BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic.alias_generators import to_camel

from app.providers.knowledge_context import KnowledgeContext
from app.providers.web_research import WebResearchEvidence

_MAX_SNOWFLAKE = 9_223_372_036_854_775_807
_HTML_TAG = re.compile(r"<\s*/?\s*[A-Za-z][^>]*>")
_INTERNAL_ID = re.compile(r"(?<![0-9])[1-9][0-9]{15,18}(?![0-9])")


def _snowflake_within_signed_long(value: str) -> str:
    if int(value) > _MAX_SNOWFLAKE:
        raise ValueError("Snowflake exceeds signed long")
    return value


Snowflake = Annotated[
    str,
    Field(pattern=r"^[1-9][0-9]{0,18}$"),
    AfterValidator(_snowflake_within_signed_long),
]
BoundedWarning = Annotated[str, Field(min_length=1, max_length=500)]


class ArticleModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
        strict=True,
    )


class ArticleStage(StrEnum):
    TITLE = "TITLE"
    OUTLINE = "OUTLINE"
    BODY = "BODY"
    REWRITE = "REWRITE"


class ArticleTargetLength(StrEnum):
    SHORT = "SHORT"
    MEDIUM = "MEDIUM"
    LONG = "LONG"


class ArticleOutlineItem(ArticleModel):
    key: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$", max_length=64)
    sequence: int = Field(ge=1, le=12)
    level: int = Field(ge=1, le=6)
    heading: str = Field(min_length=1, max_length=300)
    brief: str = Field(min_length=1, max_length=1_000)

    @field_validator("heading", "brief")
    @classmethod
    def reject_blank_edges(cls, value: str) -> str:
        if value != value.strip():
            raise ValueError("article text must not contain leading or trailing whitespace")
        if "http://" in value.casefold() or "https://" in value.casefold():
            raise ValueError("article output cannot contain URLs")
        return value


class ArticlePlan(ArticleModel):
    summary: str = Field(min_length=1, max_length=1_000)
    core_message: str = Field(min_length=1, max_length=2_000)
    target_audience: str = Field(min_length=1, max_length=1_000)
    sections: tuple[ArticleOutlineItem, ...] = Field(min_length=2, max_length=12)
    tone: str = Field(pattern=r"^[A-Z][A-Z0-9_]{0,63}$", max_length=64)
    target_length: ArticleTargetLength
    target_platform: str | None = Field(min_length=1, max_length=100)
    risk_notes: tuple[BoundedWarning, ...] = Field(
        max_length=20,
        json_schema_extra={"uniqueItems": True},
    )

    @field_validator("sections", "risk_notes", mode="before")
    @classmethod
    def freeze_arrays(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @field_validator("target_length", mode="before")
    @classmethod
    def parse_target_length(cls, value: object) -> object:
        return ArticleTargetLength(value) if isinstance(value, str) else value

    @field_validator(
        "summary",
        "core_message",
        "target_audience",
        "tone",
        "target_platform",
    )
    @classmethod
    def reject_unsafe_or_blank_text(cls, value: str | None) -> str | None:
        if value is None:
            return None
        if value != value.strip():
            raise ValueError("article plan text must not contain edge whitespace")
        if "http://" in value.casefold() or "https://" in value.casefold():
            raise ValueError("article plan cannot contain URLs")
        if _HTML_TAG.search(value) or _INTERNAL_ID.search(value):
            raise ValueError("article plan cannot contain HTML or internal identifiers")
        return value

    @field_validator("risk_notes")
    @classmethod
    def validate_risk_notes(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        if len(set(value)) != len(value):
            raise ValueError("article plan risk notes must be unique")
        for item in value:
            if item != item.strip():
                raise ValueError("article plan risk notes cannot contain edge whitespace")
            if "http://" in item.casefold() or "https://" in item.casefold():
                raise ValueError("article plan risk notes cannot contain URLs")
            if _HTML_TAG.search(item) or _INTERNAL_ID.search(item):
                raise ValueError(
                    "article plan risk notes cannot contain HTML or internal identifiers"
                )
        return value

    @model_validator(mode="after")
    def validate_section_order(self) -> Self:
        expected_sequences = tuple(range(1, len(self.sections) + 1))
        if tuple(item.sequence for item in self.sections) != expected_sequences:
            raise ValueError("article plan section sequence must be contiguous")
        if len({item.key for item in self.sections}) != len(self.sections):
            raise ValueError("article plan section keys must be unique")
        return self


class ArticleBodyBlock(ArticleModel):
    key: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$", max_length=64)
    heading: str = Field(min_length=1, max_length=300)
    markdown: str = Field(min_length=1, max_length=131_072)

    @field_validator("heading", "markdown")
    @classmethod
    def reject_unsafe_or_blank_content(cls, value: str) -> str:
        if value != value.strip():
            raise ValueError("article content must not contain edge whitespace")
        lowered = value.casefold()
        if any(
            marker in lowered
            for marker in ("<script", "data:", "authorization:", "http://", "https://")
        ):
            raise ValueError("unsafe article content")
        if _HTML_TAG.search(value) or _INTERNAL_ID.search(value):
            raise ValueError("article content cannot contain HTML or internal identifiers")
        return value


class ArticleBody(ArticleModel):
    markdown: str = Field(min_length=1, max_length=524_288)
    blocks: tuple[ArticleBodyBlock, ...] = Field(min_length=1, max_length=100)

    @model_validator(mode="after")
    def markdown_must_be_canonical(self) -> Self:
        if len({item.key for item in self.blocks}) != len(self.blocks):
            raise ValueError("article body block keys must be unique")
        if self.markdown != render_article_markdown(self.blocks):
            raise ValueError("article markdown must be derived from ordered blocks")
        return self


class ArticleSourceAssetSummary(ArticleModel):
    asset_id: Snowflake
    asset_type: Literal["IMAGE"] = "IMAGE"
    summary: str = Field(min_length=1, max_length=2_000)


class ArticleGeoRecommendation(ArticleModel):
    priority: Literal["P0", "P1", "P2"]
    title: str = Field(min_length=1, max_length=300)
    rationale: str = Field(min_length=1, max_length=1_000)


class ArticleGeoReportSummary(ArticleModel):
    geo_report_id: Snowflake
    summary: str = Field(min_length=1, max_length=4_000)
    method_version: str = Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9._-]+$")
    coverage_gaps: tuple[str, ...] = Field(default=(), max_length=20)
    findings: tuple[str, ...] = Field(default=(), max_length=50)
    recommendations: tuple[ArticleGeoRecommendation, ...] = Field(default=(), max_length=30)
    content_blueprint: tuple[str, ...] = Field(default=(), max_length=50)
    missing_samples: tuple[str, ...] = Field(default=(), max_length=50)

    @field_validator(
        "coverage_gaps", "findings", "content_blueprint", "missing_samples", mode="after"
    )
    @classmethod
    def geo_text_items_must_be_bounded(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        if any(not item.strip() or len(item) > 1_000 for item in value):
            raise ValueError("GEO report text items must be non-empty and bounded")
        return value


class ArticleExecutionBudget(ArticleModel):
    deadline_at: datetime
    max_model_calls: int = Field(default=2, ge=1, le=10)
    max_repair_retries: int = Field(default=1, ge=0, le=1)
    max_input_tokens: int = Field(default=16_000, ge=1, le=200_000)
    max_output_tokens: int = Field(default=4_096, ge=1, le=64_000)

    @field_validator("deadline_at")
    @classmethod
    def require_aware_deadline(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("deadlineAt must include timezone")
        return value

    @model_validator(mode="after")
    def repair_budget_must_fit_call_budget(self) -> Self:
        if self.max_repair_retries + 1 > self.max_model_calls:
            raise ValueError("repair retries exceed model call budget")
        return self


class ArticleConfirmedFacts(ArticleModel):
    """Java-scoped source excerpts explicitly affirmed by a persisted USER message.

    This is attributable customer evidence, not an independent verification,
    current instruction, saved-memory receipt, or permission to execute tools.
    """

    source: Literal["USER_CONFIRMED_SOURCE_EXCERPT"]
    project_id: Snowflake
    conversation_id: Snowflake
    source_assistant_message_id: Snowflake
    confirmation_user_message_id: Snowflake
    source_created_at: str = Field(min_length=1, max_length=40)
    confirmed_at: str = Field(min_length=1, max_length=40)
    source_url: str | None = Field(default=None, min_length=1, max_length=2048)
    source_excerpt: str = Field(min_length=1, max_length=2000)
    confirmation_quote: str = Field(min_length=1, max_length=500)
    facts: tuple[str, ...] = Field(min_length=1, max_length=10)

    @field_validator("facts", mode="before")
    @classmethod
    def freeze_fact_array(cls, value: object) -> object:
        return tuple(cast(list[object], value)) if isinstance(value, list) else value

    @model_validator(mode="after")
    def evidence_must_remain_attributable(self) -> Self:
        source_time = datetime.fromisoformat(self.source_created_at)
        confirmed_time = datetime.fromisoformat(self.confirmed_at)
        if (
            source_time.tzinfo is None
            or confirmed_time.tzinfo is None
            or source_time > confirmed_time
            or self.source_assistant_message_id == self.confirmation_user_message_id
        ):
            raise ValueError("invalid confirmed article fact provenance")
        if self.source_url is not None and not self.source_url.startswith("https://"):
            raise ValueError("confirmed article source URL must use HTTPS")
        if any(
            not fact.strip()
            or len(fact) > 1000
            or fact != fact.strip()
            or fact not in self.source_excerpt
            for fact in self.facts
        ):
            raise ValueError("confirmed article facts must be complete source excerpts")
        return self


class ArticleExecutionRequest(ArticleModel):
    mode: ArticleStage
    article_id: Snowflake
    brief: str = Field(min_length=1, max_length=4_000)
    selected_title: str | None = Field(default=None, min_length=1, max_length=300)
    confirmed_outline: tuple[ArticleOutlineItem, ...] = Field(default=(), max_length=100)
    confirmed_plan: ArticlePlan | None = None
    rewrite_instruction: str | None = Field(default=None, min_length=1, max_length=4_000)
    target_length: ArticleTargetLength = ArticleTargetLength.MEDIUM
    tone: str = Field(
        default="PROFESSIONAL",
        pattern=r"^[A-Z][A-Z0-9_]{0,63}$",
        max_length=64,
    )
    target_platform: str | None = Field(default=None, min_length=1, max_length=100)
    source_asset_ids: tuple[Snowflake, ...] = Field(default=(), max_length=10)
    geo_report_id: Snowflake | None = None
    space_summary: str | None = Field(default=None, min_length=1, max_length=2_000)
    brand_summary: str | None = Field(default=None, min_length=1, max_length=4_000)
    product_summary: str | None = Field(default=None, min_length=1, max_length=4_000)
    source_asset_summaries: tuple[ArticleSourceAssetSummary, ...] = Field(default=(), max_length=10)
    geo_report_summary: ArticleGeoReportSummary | None = None
    knowledge_context: KnowledgeContext | None = None
    untrusted_research: WebResearchEvidence | None = None
    confirmed_facts: tuple[ArticleConfirmedFacts, ...] = Field(default=(), max_length=3)
    current_body: ArticleBody | None = None
    current_version_ref: str | None = Field(
        default=None, min_length=1, max_length=128, pattern=r"^[A-Za-z0-9._:-]+$"
    )
    rewrite_block_ids: tuple[str, ...] = Field(default=(), max_length=100)
    budget: ArticleExecutionBudget

    @field_validator("article_id", "geo_report_id", mode="after")
    @classmethod
    def validate_snowflake_range(cls, value: str | None) -> str | None:
        if value is not None and int(value) > _MAX_SNOWFLAKE:
            raise ValueError("Snowflake exceeds signed long")
        return value

    @field_validator(
        "brief",
        "selected_title",
        "rewrite_instruction",
        "tone",
        "target_platform",
    )
    @classmethod
    def reject_blank_edges(cls, value: str | None) -> str | None:
        if value is not None and value != value.strip():
            raise ValueError("article input text must not contain edge whitespace")
        return value

    @field_validator("confirmed_outline")
    @classmethod
    def validate_outline_order(
        cls, value: tuple[ArticleOutlineItem, ...]
    ) -> tuple[ArticleOutlineItem, ...]:
        if len({item.key for item in value}) != len(value):
            raise ValueError("confirmedOutline keys must be unique")
        return value

    @field_validator("source_asset_ids", "rewrite_block_ids")
    @classmethod
    def reject_duplicates(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        if len(set(value)) != len(value):
            raise ValueError("identifier collection must be unique")
        return value

    @model_validator(mode="after")
    def validate_stage_and_authorized_summaries(self) -> Self:
        summary_ids = tuple(item.asset_id for item in self.source_asset_summaries)
        if summary_ids != self.source_asset_ids:
            raise ValueError("source asset IDs require matching ordered authorized summaries")
        if (self.geo_report_id is None) != (self.geo_report_summary is None):
            raise ValueError("geoReportId and its authorized summary must move together")
        if (
            self.geo_report_summary is not None
            and self.geo_report_summary.geo_report_id != self.geo_report_id
        ):
            raise ValueError("GEO report summary does not match the authorized report ID")

        if self.mode is ArticleStage.TITLE:
            if any(
                (
                    self.selected_title is not None,
                    bool(self.confirmed_outline),
                    self.confirmed_plan is not None,
                    self.rewrite_instruction is not None,
                    self.current_body is not None,
                    self.current_version_ref is not None,
                    bool(self.rewrite_block_ids),
                )
            ):
                raise ValueError("TITLE cannot receive later-stage state")
        elif self.mode is ArticleStage.OUTLINE:
            if self.selected_title is None:
                raise ValueError("OUTLINE requires selectedTitle")
            if any(
                (
                    bool(self.confirmed_outline),
                    self.confirmed_plan is not None,
                    self.rewrite_instruction is not None,
                    self.current_body is not None,
                    self.current_version_ref is not None,
                    bool(self.rewrite_block_ids),
                )
            ):
                raise ValueError("OUTLINE cannot receive body or rewrite state")
        elif self.mode is ArticleStage.BODY:
            if (
                self.selected_title is None
                or not self.confirmed_outline
                or self.confirmed_plan is None
            ):
                raise ValueError("BODY requires selectedTitle, confirmedOutline and confirmedPlan")
            if any(
                (
                    self.rewrite_instruction is not None,
                    self.current_body is not None,
                    self.current_version_ref is not None,
                    bool(self.rewrite_block_ids),
                )
            ):
                raise ValueError("BODY cannot receive rewrite state")
        else:
            if (
                self.selected_title is None
                or not self.confirmed_outline
                or self.confirmed_plan is None
                or self.rewrite_instruction is None
                or self.current_body is None
                or self.current_version_ref is None
                or not self.rewrite_block_ids
            ):
                raise ValueError("REWRITE requires title, outline, instruction, body and version")
            body_keys = tuple(item.key for item in self.current_body.blocks)
            outline_keys = tuple(item.key for item in self.confirmed_outline)
            if body_keys != outline_keys:
                raise ValueError("currentBody must match confirmedOutline")
            if not set(self.rewrite_block_ids).issubset(body_keys):
                raise ValueError("rewriteBlockIds must be an authorized body subset")
        if self.confirmed_plan is not None:
            if self.confirmed_plan.sections != self.confirmed_outline:
                raise ValueError("confirmedPlan sections must match confirmedOutline")
            if self.confirmed_plan.tone != self.tone:
                raise ValueError("confirmedPlan tone must match the execution request")
            if self.confirmed_plan.target_length is not self.target_length:
                raise ValueError("confirmedPlan targetLength must match the execution request")
            if self.confirmed_plan.target_platform != self.target_platform:
                raise ValueError("confirmedPlan targetPlatform must match the execution request")
        return self


class ArticleTitleCandidate(ArticleModel):
    text: str = Field(min_length=1, max_length=300)
    rationale: str = Field(min_length=1, max_length=300)

    @field_validator("text", "rationale")
    @classmethod
    def reject_urls(cls, value: str) -> str:
        if "http://" in value.casefold() or "https://" in value.casefold():
            raise ValueError("article output cannot contain URLs")
        return value


class ArticleBrandGrounding(ArticleModel):
    """Request-local editorial facts, never another store or task authority."""

    mode: Literal["BRAND_PROMOTION", "NEUTRAL", "UNSPECIFIED"]
    brand_names: tuple[str, ...] = Field(max_length=21)
    offerings: str | None = None
    service_area: str | None = None
    target_regions: str | None = None
    priority_offering: str | None = None

    def contains_brand(self, text: str) -> bool:
        def normalized(value: str) -> str:
            value = unicodedata.normalize("NFKC", value).casefold()
            return re.sub(r"[\s*`]", "", value)

        # Ignore hidden Markdown reference definitions/comments and code examples;
        # these are not a company introduction in the reader-facing article.
        text = re.sub(r"<!--.*?-->|```.*?```", " ", text, flags=re.DOTALL)
        text = re.sub(r"(?m)^\s{0,3}\[[^\]]+\]:.*$", " ", text)
        text = re.sub(r"(?m)^\s{0,3}#{1,6}\s+.*$", " ", text)
        content = normalized(text)
        return any(normalized(name) in content for name in self.brand_names)


_NEUTRAL_ARTICLE_REQUEST = re.compile(
    r"^(?:(?:请|麻烦)?(?:帮我|给我)?|我(?:希望|想要)|"
    r"(?:这篇|本篇|本次)(?:文章|稿件)?(?:请|要)?|(?:文章|正文|全文)(?:中|里)?)"
    r"(?:"
    r"(?:不要|不需要|无需|不得|不必|请勿|别|不)"
    r"(?:提及?|写入|出现|包含|宣传|带入|体现|带)(?:任何|具体的?|我们的?|本项目的?)?"
    r"(?:品牌|公司|企业)(?:名(?:称|字)?)?"
    r"|(?:写|生成|创作|改成|重写成)(?:一篇|篇)?[^不别。；;，,\n]{0,30}?"
    r"(?:中立|客观|纯)(?:的)?(?:行业)?科普(?:文章|稿件|内容)?"
    r"|(?:不带品牌|去品牌化)(?:的文章|稿件)?"
    r")(?:即可|就行|为主)?$"
    r"|^(?:please\s+)?(?:do not|don't)\s+mention\s+(?:our\s+|the\s+)?brand[.!]?$"
    r"|^(?:please\s+)?(?:write|create)\s+(?:a\s+)?neutral\s+(?:educational|informational)"
    r"\s+article[.!]?$",
    re.IGNORECASE,
)


def _explicit_neutral_article(brief: str) -> bool:
    # Java appends older conversation as writing reference, not new authority.
    # Neither that reference nor its safety preamble can opt the customer out
    # of brand grounding; only the current/explicitly approved request can.
    brief = brief.split("【写作参考\uff0f非授权】", 1)[0]
    # Quoted material, code and examples are not the customer's editorial instruction.
    text = re.sub(r"```.*?```|`[^`]*`", " ", brief, flags=re.DOTALL)
    text = re.sub(r'“[^”]*”|\u2018[^\u2019]*\u2019|「[^」]*」|『[^』]*』|"[^"]*"', " ", text)
    text = "\n".join(line for line in text.splitlines() if not line.lstrip().startswith(">"))
    return any(
        _NEUTRAL_ARTICLE_REQUEST.fullmatch(clause.strip()) is not None
        for clause in re.split(r"[。；;\uFF01!\n，,]", text)
    )


class ArticleWritingConstraints(ArticleModel):
    """Local format projection, not a new API field or a business decision."""

    section_count: int | None = Field(default=None, ge=2, le=12)
    min_visible_characters: int | None = Field(default=None, ge=1)
    max_visible_characters: int | None = Field(default=None, ge=1)
    character_counting: Literal["BODY_VISIBLE_NON_WHITESPACE_INCLUDING_HEADINGS"] = (
        "BODY_VISIBLE_NON_WHITESPACE_INCLUDING_HEADINGS"
    )


_WRITING_RANGE = r"(?P<lower>[0-9]{2,5})[-\u2013\u2014~\uff5e至到](?P<upper>[0-9]{2,5})字"
_WRITING_COUNT = r"(?P<count>1[0-2]|[2-9]|十一|十二|十|[二两三四五六七八九])(?:个)?(?:部分|节|章)"
# Deliberately small positive grammar: full clauses describing this whole output,
# not arbitrary numbers near a writing-related word or a partial paragraph budget.
_WRITING_REQUIREMENTS = (
    re.compile(
        r"(?:请)?(?:帮我|为我)?(?:写|生成|创作|重写|改写|拟定|给出|先给|以及)"
        rf"(?:一篇|一份)?(?:约)?{_WRITING_RANGE}(?:的)?(?:{_WRITING_COUNT})?"
        r"(?:写作结构|文章|正文|稿件|完整稿|稿)?"
    ),
    re.compile(
        rf"(?:全文|整篇|正文|文章)(?:字数|篇幅)?(?:为|控制在|写|写成|保持在)?(?:约)?{_WRITING_RANGE}"
    ),
    re.compile(
        rf"(?:(?:全文|整篇|文章|正文)(?:写成|写|分成|分为|分|共|为)?|(?:请)?(?:分成|分为))"
        rf"{_WRITING_COUNT}(?:写作结构|结构)?"
    ),
)
_CHINESE_SECTION_COUNTS: dict[str, int] = dict(
    zip("二两三四五六七八九", (2, 2, 3, 4, 5, 6, 7, 8, 9), strict=True)
)
_CHINESE_SECTION_COUNTS.update({"十": 10, "十一": 11, "十二": 12})


def article_writing_constraints(request: ArticleExecutionRequest) -> ArticleWritingConstraints:
    """Recognize only unambiguous user format requests; abstain otherwise.

    Older frozen briefs and MEDIUM do not acquire new defaults. Assistant references,
    examples and local rewrites cannot impose a whole-article output contract.
    """
    if request.mode is ArticleStage.REWRITE:
        return ArticleWritingConstraints()
    text = request.brief.partition("\n\n【写作参考\uff0f非授权】")[0]
    text = re.sub(r"(?m)^(?:当前USER确认|原USER写作要求)[:\uff1a]\s*", "", text)
    text = re.sub(r"```.*?(?:```|$)|~~~.*?(?:~~~|$)|`[^`]*`", " ", text, flags=re.DOTALL)
    text = re.sub(r'“[^”]*”|\u2018[^\u2019]*\u2019|「[^」]*」|『[^』]*』|"[^"]*"', " ", text)
    text = "\n".join(line for line in text.splitlines() if not line.lstrip().startswith(">"))
    # An explicitly labelled source can continue across newlines without quote
    # punctuation. It cannot establish this request's format contract.
    if re.search(r"(?m)^\s*(?:参考资料|参考稿|引用原文|原文)[:\uff1a]", text):
        return ArticleWritingConstraints()
    lengths: set[tuple[int, int]] = set()
    sections: set[int] = set()
    for sentence in re.split(r"[。；;\uff01？!?\n]", text):
        # An alternative/example scopes its following comma-separated clauses too.
        if re.search(r"例如|比如|示例|假设|是否|能否|或者|或是|任选", sentence):
            continue
        for clause in re.split(r"[，,]", sentence):
            clause = re.sub(r"\s+", "", clause)
            for pattern in _WRITING_REQUIREMENTS:
                match = pattern.fullmatch(clause)
                if match is None:
                    continue
                groups = match.groupdict()
                if groups.get("lower") is not None:
                    lengths.add((int(groups["lower"]), int(groups["upper"])))
                value = groups.get("count")
                if value is not None:
                    sections.add(int(value) if value.isascii() else _CHINESE_SECTION_COUNTS[value])
    length = next(iter(lengths)) if len(lengths) == 1 else None
    if length is not None and (length[0] < 1 or length[0] > length[1]):
        length = None
    return ArticleWritingConstraints(
        section_count=next(iter(sections)) if len(sections) == 1 else None,
        min_visible_characters=None if length is None else length[0],
        max_visible_characters=None if length is None else length[1],
    )


def article_visible_character_count(markdown: str) -> int:
    """Count rendered body text, including headings/table cells but not syntax.

    Use the same CommonMark + tables dialect as publication rendering. Images and
    reference destinations are not prose; code and decoded entities are visible text.
    A local parser avoids mutable cross-request options or hidden network work.
    """

    def count(tokens: list[Token]) -> int:
        total = 0
        for token in tokens:
            if token.type == "image":
                continue
            if token.children is not None:
                total += count(token.children)
            elif token.type in {
                "text",
                "code_inline",
                "code_block",
                "fence",
                "html_block",
                "html_inline",
            }:
                # Java's escapeHtml(true) renders even comments as visible text.
                total += sum(not char.isspace() for char in token.content)
        return total

    return count(MarkdownIt("commonmark").enable("table").parse(markdown))


def article_body_visible_character_count(blocks: tuple[ArticleBodyBlock, ...]) -> int:
    # Java escapes each block heading literally, then parses that block's Markdown
    # independently; do not parse heading emphasis or resolve cross-block references.
    return sum(
        sum(not char.isspace() for char in block.heading)
        + article_visible_character_count(block.markdown)
        for block in blocks
    )


def article_brand_grounding(request: ArticleExecutionRequest) -> ArticleBrandGrounding:
    """Project already-authorized identity/memory without inventing facts from chat or URLs.

    The Java worker adapter has already checked knowledge's project binding. Do not use
    project labels, ACCOUNT preferences, documents, search snippets or deleted values
    as another source of customer identity or structured company facts here.
    """
    names: list[str] = []
    if request.brand_summary:
        try:
            brand = json.loads(request.brand_summary)
        except (TypeError, ValueError):
            brand = None  # Legacy bounded free-text summaries are not structured identity.
        if isinstance(brand, dict):
            identity = cast(dict[str, object], brand)
            name = identity.get("name")
            aliases = identity.get("aliases", [])
            if isinstance(name, str) and name.strip() and len(name) <= 150:
                names.append(name.strip())
                if isinstance(aliases, list):
                    names.extend(
                        alias.strip()
                        for alias in cast(list[object], aliases)[:20]
                        if isinstance(alias, str) and alias.strip() and len(alias) <= 150
                    )
    names = list(dict.fromkeys(names))
    memory = None if request.knowledge_context is None else request.knowledge_context.memory

    def fact(key: str, kind: str = "FACT") -> str | None:
        if memory is None or memory.project_id is None:
            return None
        return next(
            (
                item.value
                for item in memory.entries
                if item.scope == "PROJECT"
                and item.project_id == memory.project_id
                and item.kind == kind
                and item.key == key
                and not item.deleted
            ),
            None,
        )

    neutral = _explicit_neutral_article(request.brief)
    return ArticleBrandGrounding(
        mode="NEUTRAL" if neutral else "BRAND_PROMOTION" if names else "UNSPECIFIED",
        brand_names=tuple(names),
        offerings=fact("brand.categories"),
        service_area=fact("brand.service_area"),
        target_regions=fact("geo.target_regions"),
        priority_offering=fact("geo.priority_offering", "PREFERENCE"),
    )


class ArticleEditorialValue(ArticleModel):
    """An existing frozen value with provenance; not a model-inferred claim."""

    value: str = Field(min_length=1, max_length=4_000)
    source: Literal["PROJECT_MEMORY", "ACCOUNT_PREFERENCE", "BRAND_SNAPSHOT", "PRODUCT_SNAPSHOT"]
    source_key: str = Field(min_length=1, max_length=64)


class ArticleEditorialProfile(ArticleModel):
    """Read-only writing projection; it is not a new request or admission contract."""

    purpose_default: Literal["MEDIA_BRAND_ARTICLE", "NEUTRAL_ARTICLE", "UNSPECIFIED"]
    current_requirements_source: Literal["brief"]
    brand_names: tuple[str, ...] = Field(max_length=21)
    industry: ArticleEditorialValue | None
    description: ArticleEditorialValue | None
    offerings: ArticleEditorialValue | None
    selling_points: ArticleEditorialValue | None
    target_audience: ArticleEditorialValue | None
    brand_voice: ArticleEditorialValue | None
    service_area: ArticleEditorialValue | None
    target_regions: ArticleEditorialValue | None
    priority_offering: ArticleEditorialValue | None
    target_keywords: ArticleEditorialValue | None
    prohibited_claims: ArticleEditorialValue | None
    product_facts: dict[str, ArticleEditorialValue] = Field(max_length=6)
    tone: str
    target_length: ArticleTargetLength
    target_platform: str | None
    unknown_fields: tuple[str, ...] = Field(max_length=11)
    omitted_fields: tuple[str, ...] = Field(max_length=17)
    blocked_memory_keys: tuple[str, ...] = Field(max_length=32)
    reference_materials_available: bool


def article_editorial_profile(request: ArticleExecutionRequest) -> ArticleEditorialProfile:
    """Organize approved materials without mining historical, document or web text.

    A matching project memory entry, including a tombstone, supersedes the legacy
    snapshot value. Saved expression preferences never establish brand facts.
    The original authorized materials remain available separately for reading.
    """
    grounding = article_brand_grounding(request)
    knowledge = request.knowledge_context
    memory = None if knowledge is None else knowledge.memory
    project_entries = (
        ()
        if memory is None or memory.project_id is None
        else tuple(
            item
            for item in memory.entries
            if item.scope == "PROJECT" and item.project_id == memory.project_id
        )
    )

    def snapshot(raw: str | None) -> dict[str, object]:
        try:
            parsed = json.loads(raw) if raw else None
        except (TypeError, ValueError):
            return {}
        return cast(dict[str, object], parsed) if isinstance(parsed, dict) else {}

    brand, product = snapshot(request.brand_summary), snapshot(request.product_summary)

    def snapshot_value(
        data: dict[str, object], key: str, source: Literal["BRAND_SNAPSHOT", "PRODUCT_SNAPSHOT"]
    ) -> ArticleEditorialValue | None:
        raw = data.get(key)
        if isinstance(raw, str):
            value = raw.strip()
        elif isinstance(raw, list) and all(
            isinstance(item, str) for item in cast(list[object], raw)
        ):
            value = "\n".join(item.strip() for item in cast(list[str], raw) if item.strip())
        else:
            return None
        # Never cut a fact halfway through a qualifier to fit the projection.
        if not value or len(value) > 4_000:
            return None
        return ArticleEditorialValue(value=value, source=source, source_key=key)

    def project_value(
        keys: tuple[str, ...],
        *,
        kind: str,
        snapshot_key: str | None = None,
    ) -> ArticleEditorialValue | None:
        candidates = [item for item in project_entries if item.key in keys]
        if candidates:
            # Synonyms may coexist in older projects. An explicit deletion must
            # not silently resurrect another spelling or the legacy snapshot.
            if any(item.deleted for item in candidates):
                return None
            entry = max(candidates, key=lambda item: (item.updated_at, item.version, item.key))
            if entry.kind != kind or entry.value is None:
                return None
            return ArticleEditorialValue(
                value=entry.value, source="PROJECT_MEMORY", source_key=entry.key
            )
        return (
            snapshot_value(brand, snapshot_key, "BRAND_SNAPSHOT")
            if snapshot_key is not None
            else None
        )

    specifications = {
        "industry": (("brand.industry",), "FACT", None),
        "description": (("brand.description",), "FACT", "description"),
        "offerings": (("brand.categories",), "FACT", None),
        "selling_points": (("brand.selling_points",), "FACT", "sellingPoints"),
        "target_audience": (("brand.target_audience", "brand.audience"), "FACT", "audience"),
        "brand_voice": (("style.tone", "brand.voice"), "PREFERENCE", "tone"),
        "service_area": (("brand.service_area",), "FACT", None),
        "target_regions": (("geo.target_regions",), "FACT", None),
        "priority_offering": (("geo.priority_offering",), "PREFERENCE", None),
        "target_keywords": (("geo.target_queries",), "PREFERENCE", None),
        "prohibited_claims": (("brand.prohibited_claims",), "PREFERENCE", "prohibitedClaims"),
    }
    values = {
        field: project_value(keys, kind=kind, snapshot_key=legacy)
        for field, (keys, kind, legacy) in specifications.items()
    }
    if not any(item.key in {"style.tone", "brand.voice"} for item in project_entries):
        account_tone = next(
            (
                item
                for item in (() if memory is None else memory.entries)
                if item.scope == "ACCOUNT" and item.key == "style.tone"
            ),
            None,
        )
        if values["brand_voice"] is None and account_tone is not None and not account_tone.deleted:
            assert account_tone.value is not None
            values["brand_voice"] = ArticleEditorialValue(
                value=account_tone.value, source="ACCOUNT_PREFERENCE", source_key="style.tone"
            )
    product_keys = {
        "name": ("product.name",),
        "category": ("product.category",),
        "modelSpec": ("product.model_spec",),
        "sellingPoints": ("product.selling_points",),
        "audience": ("product.audience",),
        "usageScenarios": ("product.usage_scenarios",),
    }
    # Project-level product.* memory has no selected-product ID binding. It may
    # refer to another product: never promote it into this product's facts. A
    # correction/tombstone conservatively suppresses its conflicting snapshot
    # field; the original materials remain available for explicit verification.
    product_facts = {
        key: value
        for key, keys in product_keys.items()
        if not any(item.key in keys for item in project_entries)
        and (value := snapshot_value(product, key, "PRODUCT_SNAPSHOT")) is not None
    }
    # Keep this convenience projection smaller than its original materials. Never
    # shorten values mid-qualifier; omitted values stay readable in authorizedContext.
    unknown_fields = tuple(to_camel(field) for field, value in values.items() if value is None)
    remaining_characters = 2_400
    omitted_fields: list[str] = []
    for field in (
        "offerings",
        "selling_points",
        "target_audience",
        "service_area",
        "target_regions",
        "priority_offering",
        "target_keywords",
        "brand_voice",
        "prohibited_claims",
        "description",
        "industry",
    ):
        item = values[field]
        if item is not None:
            if len(item.value) > remaining_characters:
                values[field] = None
                omitted_fields.append(to_camel(field))
            else:
                remaining_characters -= len(item.value)
    for key, item in tuple(product_facts.items()):
        if len(item.value) > remaining_characters:
            product_facts.pop(key)
            omitted_fields.append("productFacts." + key)
        else:
            remaining_characters -= len(item.value)
    relevant_keys = {key for keys, _, _ in specifications.values() for key in keys} | {
        key for keys in product_keys.values() for key in keys
    }
    return ArticleEditorialProfile(
        purpose_default=(
            "MEDIA_BRAND_ARTICLE"
            if grounding.mode == "BRAND_PROMOTION"
            else "NEUTRAL_ARTICLE"
            if grounding.mode == "NEUTRAL"
            else "UNSPECIFIED"
        ),
        current_requirements_source="brief",
        brand_names=grounding.brand_names,
        **values,
        product_facts=product_facts,
        tone=request.tone,
        target_length=request.target_length,
        target_platform=request.target_platform,
        unknown_fields=unknown_fields,
        omitted_fields=tuple(omitted_fields),
        blocked_memory_keys=tuple(
            item.key for item in project_entries if item.deleted and item.key in relevant_keys
        ),
        reference_materials_available=bool(knowledge is not None and knowledge.documents),
    )


class ArticleUsage(ArticleModel):
    model_call_count: int = Field(ge=1, le=10)
    input_tokens: int = Field(ge=0)
    output_tokens: int = Field(ge=0)
    latency_ms: int = Field(ge=0)
    monetary_cost: None = None
    currency: None = None


class ArticleExecutionMetadata(ArticleModel):
    proposal_only: Literal[True] = True
    persisted: Literal[False] = False
    published: Literal[False] = False
    authorized_rewrite_block_ids: tuple[str, ...] = Field(default=(), max_length=100)


class ArticleExecutionResult(ArticleModel):
    mode: ArticleStage
    title_candidates: tuple[ArticleTitleCandidate, ...] = Field(default=(), max_length=5)
    title: str | None = Field(default=None, min_length=1, max_length=300)
    plan: ArticlePlan | None = None
    outline: tuple[ArticleOutlineItem, ...] = Field(default=(), max_length=100)
    body: ArticleBody | None = None
    provider: Literal["DEEPSEEK"]
    model: str = Field(min_length=1, max_length=128)
    model_version: str = Field(min_length=1, max_length=64)
    prompt_version: str = Field(min_length=1, max_length=64)
    usage: ArticleUsage
    warnings: tuple[BoundedWarning, ...] = Field(default=(), max_length=20)
    metadata: ArticleExecutionMetadata

    @model_validator(mode="after")
    def validate_stage_shape(self) -> Self:
        if self.mode is ArticleStage.TITLE:
            if len(self.title_candidates) != 5:
                raise ValueError("TITLE requires exactly 5 candidates")
            if len({item.text.casefold() for item in self.title_candidates}) != len(
                self.title_candidates
            ):
                raise ValueError("title candidates must be unique")
            if (
                self.title is not None
                or self.plan is not None
                or self.outline
                or self.body is not None
            ):
                raise ValueError("TITLE result cannot contain later-stage content")
        elif self.mode is ArticleStage.OUTLINE:
            if (
                self.title is None
                or self.plan is None
                or self.outline != self.plan.sections
                or self.body is not None
            ):
                raise ValueError("OUTLINE result shape is invalid")
        elif self.mode in {ArticleStage.BODY, ArticleStage.REWRITE}:
            if self.title is None or self.plan is not None or not self.outline or self.body is None:
                raise ValueError("BODY/REWRITE result requires title, outline and body")
        return self


def render_article_markdown(blocks: tuple[ArticleBodyBlock, ...]) -> str:
    return "\n\n".join(f"## {item.heading}\n\n{item.markdown}" for item in blocks)
