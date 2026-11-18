"""Resolve immutable build metadata without invoking a shell command."""

import re
from pathlib import Path

_COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}(?:[0-9a-f]{24})?", re.IGNORECASE)


def discover_git_commit(start: Path | None = None) -> str | None:
    """Return the checked-out Git object id when running from a repository."""
    origin = (start or Path(__file__)).resolve()
    search_root = origin if origin.is_dir() else origin.parent
    for directory in (search_root, *search_root.parents):
        marker = directory / ".git"
        if marker.is_dir():
            return _read_git_commit(marker)
        if marker.is_file():
            git_dir = _resolve_git_file(marker)
            if git_dir is not None:
                return _read_git_commit(git_dir)
    return None


def is_full_git_commit(value: str | None) -> bool:
    """Accept complete SHA-1 or SHA-256 object ids, never abbreviations."""
    return (
        value is not None
        and _COMMIT_PATTERN.fullmatch(value) is not None
        and any(character != "0" for character in value)
    )


def resolve_runtime_git_commit(
    configured_commit: str | None,
    start: Path | None = None,
) -> str | None:
    """Return the actual HEAD only when optional configured metadata agrees with it."""
    actual_commit = discover_git_commit(start)
    if not is_full_git_commit(actual_commit):
        return None
    if configured_commit is not None and configured_commit != actual_commit:
        return None
    return actual_commit


def _resolve_git_file(marker: Path) -> Path | None:
    try:
        content = marker.read_text(encoding="utf-8").strip()
    except OSError:
        return None
    prefix = "gitdir:"
    if not content.lower().startswith(prefix):
        return None
    candidate = Path(content[len(prefix) :].strip())
    if not candidate.is_absolute():
        candidate = marker.parent / candidate
    try:
        return candidate.resolve()
    except OSError:
        return None


def _read_git_commit(git_dir: Path) -> str | None:
    try:
        head = (git_dir / "HEAD").read_text(encoding="ascii").strip()
    except OSError:
        return None
    if is_full_git_commit(head):
        return head.lower()
    prefix = "ref:"
    if not head.startswith(prefix):
        return None
    reference = head[len(prefix) :].strip()
    if not reference or reference.startswith("/") or ".." in Path(reference).parts:
        return None
    value = None
    for reference_root in _reference_roots(git_dir):
        loose_ref = reference_root.joinpath(*reference.split("/"))
        try:
            value = loose_ref.read_text(encoding="ascii").strip()
        except OSError:
            value = _read_packed_ref(reference_root / "packed-refs", reference)
        if value is not None:
            break
    return value.lower() if value is not None and is_full_git_commit(value) else None


def _reference_roots(git_dir: Path) -> tuple[Path, ...]:
    """Return private and shared ref roots for normal repositories and worktrees."""
    try:
        common = (git_dir / "commondir").read_text(encoding="utf-8").strip()
    except OSError:
        return (git_dir,)
    if not common:
        return (git_dir,)
    common_dir = Path(common)
    if not common_dir.is_absolute():
        common_dir = git_dir / common_dir
    try:
        resolved = common_dir.resolve()
    except OSError:
        return (git_dir,)
    return (git_dir, resolved) if resolved != git_dir else (git_dir,)


def _read_packed_ref(path: Path, reference: str) -> str | None:
    try:
        lines = path.read_text(encoding="ascii").splitlines()
    except OSError:
        return None
    for line in lines:
        if not line or line.startswith(("#", "^")):
            continue
        try:
            value, name = line.split(" ", maxsplit=1)
        except ValueError:
            continue
        if name == reference:
            return value
    return None
