#!/usr/bin/env python3
"""Save & commit: write an editor change set into the repo, then commit and push just what it wrote.

The local editor's "Save & commit" button (``serve.py`` → ``POST /api/commit``). In order:

1. apply the change set (``apply.apply_changes``), exactly as "Save to repo" does;
2. stop there — saved, not committed — when the save left work for a person (text to translate, a new
   advancement with no trigger), since a half-translated commit fails CI;
3. regenerate the Everything Burrito golden list and run ``test_apply.py``;
4. bump ``mod_version``'s PATCH in ``gradle.properties`` (every commit in this repo does);
5. ``git commit -- <paths>`` with only the files written plus those two, so nothing else in the working
   tree is swept in, and ``git push origin HEAD``.

Refuses on ``main``/``master``, on a detached HEAD, and when the save changed nothing.
"""

from __future__ import annotations

import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

import apply
import capstone_rules

PROTECTED_BRANCHES = ("main", "master")
VERSION_RE = re.compile(r"^(mod_version=)(\d+)\.(\d+)\.(\d+)[ \t]*$", re.M)
#: Save todos that leave the repo unfinished (CI would fail): these stop the commit.
BLOCKING_TODO = re.compile(r"^(Translate|Re-translate)\b|granted by nothing|granted by code")


class CommitError(RuntimeError):
    """The save could not be committed; the message says why, for the page."""


@dataclass
class Repo:
    """Where everything lives — the real repo by default, a throwaway one in tests."""
    root: Path = apply.REPO
    adv_dir: Path = apply.ADV_DIR
    tabs_file: Path = apply.TABS_FILE
    lang_file: Path = apply.LANG_FILE

    @property
    def gradle(self) -> Path:
        return self.root / "gradle.properties"

    @property
    def golden(self) -> Path:
        return self.root / "src/test/resources/advancement/burrito_required.txt"


@dataclass
class Result:
    committed: bool
    message: str
    written: list[str] = field(default_factory=list)
    todo: list[str] = field(default_factory=list)
    commit: str = ""
    version: str = ""


# ---------- pure pieces --------------------------------------------------------------------------

def bump_patch(text: str) -> tuple[str, str]:
    """``gradle.properties`` text with ``mod_version``'s PATCH raised by one, and the new version."""
    m = VERSION_RE.search(text)
    if not m:
        raise CommitError("gradle.properties has no mod_version=MAJOR.MINOR.PATCH line")
    version = f"{m.group(2)}.{m.group(3)}.{int(m.group(4)) + 1}"
    return VERSION_RE.sub(lambda mm: f"{mm.group(1)}{version}", text, count=1), version


def blocking(todo: list[str]) -> list[str]:
    """The save todos that must be finished before the change can be committed."""
    return [t for t in todo if BLOCKING_TODO.search(t)]


def summary(changes: dict) -> str:
    """A short subject for the commit, from what the change set touches."""
    def n(key: str) -> int:
        v = changes.get(key)
        return len(v) if isinstance(v, (dict, list)) else 0
    parts = []
    for key, one, many in (("created", "new advancement", "new advancements"), ("deleted", "removal", "removals"),
                           ("parents", "move", "moves"), ("icons", "icon", "icons"), ("values", "value", "values"),
                           ("texts", "text edit", "text edits"), ("backgrounds", "background", "backgrounds"),
                           ("tabNames", "tab name", "tab names"), ("unlocks", "unlock", "unlocks"),
                           ("visibility", "visibility change", "visibility changes"),
                           ("capstone", "burrito/reset setting", "burrito/reset settings")):
        if n(key):
            parts.append(f"{n(key)} {one if n(key) == 1 else many}")
    if changes.get("order"):
        parts.append("tab order")
    return ", ".join(parts) or "no changes"


def refuse_branch(branch: str) -> None:
    if not branch or branch == "HEAD":
        raise CommitError("Not on a branch (detached HEAD): check out your feature branch first.")
    if branch in PROTECTED_BRANCHES:
        raise CommitError(f"On {branch}: commit from a feature branch, never {branch}.")


# ---------- git ----------------------------------------------------------------------------------

def git(repo: Repo, *args: str) -> str:
    proc = subprocess.run(["git", *args], cwd=repo.root, capture_output=True, text=True)
    if proc.returncode:
        raise CommitError(f"git {args[0]} failed: {(proc.stderr or proc.stdout).strip()[:400]}")
    return proc.stdout.strip()


def current_branch(repo: Repo) -> str:
    """The checked-out branch, or "" on a detached HEAD."""
    proc = subprocess.run(["git", "symbolic-ref", "--short", "-q", "HEAD"], cwd=repo.root, capture_output=True, text=True)
    return proc.stdout.strip() if proc.returncode == 0 else ""


def run_editor_tests(repo: Repo) -> None:
    proc = subprocess.run([sys.executable, "-B", str(Path(__file__).with_name("test_apply.py"))],
                          cwd=repo.root, capture_output=True, text=True)
    if proc.returncode:
        fails = [ln for ln in proc.stdout.splitlines() if ln.startswith("FAIL")]
        raise CommitError("Editor tests failed, not committed: " + ("; ".join(fails) or proc.stdout[-400:]))


def save_and_commit(changes: dict, repo: Repo | None = None, *, push: bool = True, run_tests=run_editor_tests) -> Result:
    repo = repo or Repo()
    refuse_branch(current_branch(repo))
    report = apply.apply_changes(changes, adv_dir=repo.adv_dir, tabs_file=repo.tabs_file, lang_file=repo.lang_file)
    if not report.written:
        raise CommitError("Nothing to save: the repo already matches the editor.")
    stop = blocking(report.todo)
    if stop:
        return Result(False, f"Saved, not committed: {len(stop)} thing(s) need a person first — "
                             "ask Claude to finish and commit.", report.written, report.todo)
    repo.golden.parent.mkdir(parents=True, exist_ok=True)
    repo.golden.write_text(capstone_rules.golden_burrito(repo.adv_dir, apply.load_tabs(repo.tabs_file)))
    run_tests(repo)
    text, version = bump_patch(repo.gradle.read_text())
    repo.gradle.write_text(text)

    paths = sorted({*(str((repo.root / p).resolve().relative_to(repo.root.resolve())) for p in report.written),
                    "gradle.properties", str(repo.golden.relative_to(repo.root))})
    git(repo, "add", "--", *paths)
    git(repo, "commit", "-q", "-m", f"feat: apply editor edits — {summary(changes)}", "--", *paths)
    sha = git(repo, "rev-parse", "--short", "HEAD")
    pushed = ""
    if push:
        git(repo, "push", "-q", "origin", "HEAD")
        pushed = " and pushed"
    return Result(True, f"Committed {sha}{pushed} ({version}).", report.written, report.todo, sha, version)
