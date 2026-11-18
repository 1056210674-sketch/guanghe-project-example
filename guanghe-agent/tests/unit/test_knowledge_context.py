"""Java-owned knowledge transport rejects mismatched or oversized authority."""

import copy

import pytest
from pydantic import ValidationError

from app.providers.knowledge_context import KnowledgeContext


def _context() -> dict[str, object]:
    return {
        "documents": [
            {
                "id": "103",
                "version": 2,
                "name": "小行星门店说明",
                "sha256": "a" * 64,
                "sourceType": "PASTED_TEXT",
                "sourceUrl": None,
                "paragraphs": [{"index": 1, "page": None, "text": "小行星主营咖啡与曲奇。"}],
            }
        ],
        "preferences": [
            {
                "id": "104",
                "projectId": "101",
                "scope": "PROJECT",
                "content": "语气自然，不夸大。",
                "sourceType": "USER_INPUT",
                "sourceMessageId": None,
                "version": 1,
                "createdAt": "2026-10-07T01:00:00Z",
                "updatedAt": "2026-10-07T01:00:00Z",
            }
        ],
        "businessHistory": {
            "projectId": "101",
            "writtenArticleCount": 3,
            "publishedArticleCount": 1,
            "publishedPlacementCount": 2,
            "asOf": "2026-10-07T01:00:00Z",
        },
        "truncated": False,
        "sourceCount": 1,
        "returnedCharacters": 21,
    }


def test_same_project_knowledge_retains_distinct_business_counts() -> None:
    context = KnowledgeContext.model_validate(_context())
    context.validate_project_binding("101")
    assert context.documents[0].version == 2
    assert context.business_history is not None
    assert context.business_history.written_article_count == 3
    assert context.business_history.published_article_count == 1
    assert context.business_history.published_placement_count == 2


@pytest.mark.parametrize("project", [None, "102"])
def test_project_knowledge_cannot_cross_project_or_personal_boundary(project: str | None) -> None:
    with pytest.raises(ValueError):
        KnowledgeContext.model_validate(_context()).validate_project_binding(project)


def test_personal_preference_is_allowed_without_project_and_no_business_history() -> None:
    payload = _context()
    payload["documents"] = []
    payload["businessHistory"] = None
    payload["preferences"] = [
        {
            "id": "104",
            "projectId": None,
            "scope": "PERSONAL",
            "content": "回答短一点。",
            "sourceType": "USER_INPUT",
            "sourceMessageId": None,
            "version": 1,
            "createdAt": "2026-10-07T01:00:00Z",
            "updatedAt": "2026-10-07T01:00:00Z",
        }
    ]
    KnowledgeContext.model_validate(payload).validate_project_binding(None)


@pytest.mark.parametrize("scope, project", [("PERSONAL", "101"), ("PROJECT", None)])
def test_scope_and_project_must_agree(scope: str, project: str | None) -> None:
    payload = _context()
    payload["preferences"] = [
        {
            "id": "104",
            "projectId": project,
            "scope": scope,
            "content": "自然",
            "sourceType": "USER_INPUT",
            "sourceMessageId": None,
            "version": 1,
            "createdAt": "2026-10-07T01:00:00Z",
            "updatedAt": "2026-10-07T01:00:00Z",
        }
    ]
    with pytest.raises(ValidationError):
        KnowledgeContext.model_validate(payload)


@pytest.mark.parametrize(
    "field, value",
    [
        ("returnedCharacters", 12001),
        ("returnedCharacters", True),
        ("sourceCount", -1),
        ("truncated", "false"),
    ],
)
def test_context_boundaries_reject_coercion_and_overflow(field: str, value: object) -> None:
    payload = _context()
    payload[field] = value
    with pytest.raises(ValidationError):
        KnowledgeContext.model_validate(payload)


def test_signed_long_id_and_unknown_permissions_are_rejected() -> None:
    payload = _context()
    docs = copy.deepcopy(payload["documents"])
    assert isinstance(docs, list)
    docs[0]["id"] = "9223372036854775808"
    payload["documents"] = docs
    with pytest.raises(ValidationError):
        KnowledgeContext.model_validate(payload)
    payload = _context()
    payload["permissions"] = ["publish"]
    with pytest.raises(ValidationError):
        KnowledgeContext.model_validate(payload)


def test_too_many_documents_are_not_silently_truncated() -> None:
    payload = _context()
    assert isinstance(payload["documents"], list)
    payload["documents"] = payload["documents"] * 13
    with pytest.raises(ValidationError):
        KnowledgeContext.model_validate(payload)
