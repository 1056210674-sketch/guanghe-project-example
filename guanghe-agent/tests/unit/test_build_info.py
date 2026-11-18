from pathlib import Path

from app.common.build_info import (
    discover_git_commit,
    is_full_git_commit,
    resolve_runtime_git_commit,
)


def test_discovers_loose_git_reference(tmp_path: Path) -> None:
    git_dir = tmp_path / ".git"
    ref = git_dir / "refs" / "heads" / "master"
    ref.parent.mkdir(parents=True)
    (git_dir / "HEAD").write_text("ref: refs/heads/master\n", encoding="ascii")
    ref.write_text("a" * 40 + "\n", encoding="ascii")

    assert discover_git_commit(tmp_path / "app") == "a" * 40


def test_discovers_detached_git_reference_from_gitfile(tmp_path: Path) -> None:
    worktree = tmp_path / "worktree"
    worktree.mkdir()
    git_dir = tmp_path / "metadata"
    git_dir.mkdir()
    (worktree / ".git").write_text("gitdir: ../metadata\n", encoding="utf-8")
    (git_dir / "HEAD").write_text("B" * 64 + "\n", encoding="ascii")

    assert discover_git_commit(worktree) == "b" * 64


def test_rejects_missing_or_abbreviated_commits(tmp_path: Path) -> None:
    assert discover_git_commit(tmp_path) is None
    assert not is_full_git_commit(None)
    assert not is_full_git_commit("abc1234")
    assert not is_full_git_commit("0" * 40)


def test_runtime_commit_uses_actual_head_only_when_configuration_matches(tmp_path: Path) -> None:
    git_dir = tmp_path / ".git"
    git_dir.mkdir()
    actual_commit = "a" * 40
    (git_dir / "HEAD").write_text(actual_commit + "\n", encoding="ascii")

    assert resolve_runtime_git_commit(None, tmp_path) == actual_commit
    assert resolve_runtime_git_commit(actual_commit, tmp_path) == actual_commit
    assert resolve_runtime_git_commit("b" * 40, tmp_path) is None
    assert resolve_runtime_git_commit("0" * 40, tmp_path) is None
    assert resolve_runtime_git_commit("invalid", tmp_path) is None


def test_discovers_worktree_reference_from_common_git_directory(tmp_path: Path) -> None:
    worktree = tmp_path / "worktree"
    worktree.mkdir()
    common_dir = tmp_path / "repository.git"
    worktree_git_dir = common_dir / "worktrees" / "feature"
    worktree_git_dir.mkdir(parents=True)
    reference = common_dir / "refs" / "heads" / "feature"
    reference.parent.mkdir(parents=True)
    actual_commit = "c" * 40
    (worktree / ".git").write_text(
        "gitdir: ../repository.git/worktrees/feature\n",
        encoding="utf-8",
    )
    (worktree_git_dir / "HEAD").write_text("ref: refs/heads/feature\n", encoding="ascii")
    (worktree_git_dir / "commondir").write_text("../..\n", encoding="ascii")
    reference.write_text(actual_commit + "\n", encoding="ascii")

    assert discover_git_commit(worktree) == actual_commit
