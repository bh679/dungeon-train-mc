#!/usr/bin/env python3
"""Shared helpers for the per-version changelog ledger.

The ledger (`.github/release-notes/changelog.json`) records one curated entry
per Gate-3 merge. The agent appends entries during Gate 3 (append-entry.py),
renders the unreleased set for confirmation before a release (render-unreleased.py),
and `release.yml` marks shipped entries released on a successful real release
(mark-released.py). This module is the single source of truth for reading,
writing, and transforming that ledger so the three entrypoints stay thin.

Inclusion in a release is governed by the per-entry `released` flag, never by
comparing version numbers — back-to-back Gate-3 merges can share one X.Y.0
(version-bump.yml skips when PATCH==0), so a version boundary would wrongly
exclude a feature merged right after a release. The `version` field is kept as
descriptive metadata and for grouping in the rendered notes.

Public surface:
  read_json / write_json — JSON I/O matching the auto-release convention
      (indent=2, explicit trailing newline, no sort_keys).
  parse_semver(s) — strict X.Y.Z parse, returns (int,int,int) or None.
  read_mod_version() — read mod_version from gradle.properties or None.
  compute_release_version(current) — the version this merge ships as, applying
      the SAME rule as version-bump.yml (PATCH==0 -> unchanged, else MINOR+1).
  load_changelog / save_changelog — read/write the ledger object.
  find_entry / make_entry / append_entry — entry construction (immutable).
  unreleased_entries / mark_all_released — the released-flag boundary.
  render_markdown(entries) — player-facing Markdown grouped by version desc.
  write_github_output(**kv) — append step outputs to $GITHUB_OUTPUT.

Env overrides (mirror scripts/auto-release for testability):
  CHANGELOG_FILE          default: .github/release-notes/changelog.json
  GRADLE_PROPERTIES_FILE  default: gradle.properties
"""
import json
import os
import sys
from datetime import datetime, timezone
from typing import Any

CHANGELOG_FILE = os.environ.get(
    "CHANGELOG_FILE", ".github/release-notes/changelog.json"
)
GRADLE_PROPERTIES_FILE = os.environ.get("GRADLE_PROPERTIES_FILE", "gradle.properties")

# Conventional-commit types accepted on an entry. feat/fix/content are the ones
# that usually matter to players; the rest are allowed so the ledger can record
# any merge without rejecting it.
VALID_TYPES = (
    "feat",
    "fix",
    "content",
    "perf",
    "refactor",
    "chore",
    "docs",
    "ci",
    "test",
)


def read_json(path: str) -> Any:
    with open(path) as f:
        return json.load(f)


def write_json(path: str, obj: Any) -> None:
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def parse_semver(s: str) -> tuple[int, int, int] | None:
    if not isinstance(s, str):
        return None
    parts = s.split(".")
    if len(parts) != 3:
        return None
    try:
        return tuple(int(p) for p in parts)  # type: ignore[return-value]
    except ValueError:
        return None


def read_mod_version(path: str | None = None) -> str | None:
    """Return the X.Y.Z value of mod_version in gradle.properties, or None."""
    target = path or GRADLE_PROPERTIES_FILE
    try:
        with open(target) as f:
            for line in f:
                if line.startswith("mod_version="):
                    return line[len("mod_version=") :].strip()
    except FileNotFoundError:
        return None
    return None


def compute_release_version(current: str) -> str:
    """The version a merge of the current branch will ship as.

    Replicates version-bump.yml exactly: read M.m.p; if PATCH==0 the bump is
    skipped (the feature shares the current MINOR with whatever merged before
    it), so the version stays M.m.0. Otherwise CI bumps to M.(m+1).0.
    """
    parsed = parse_semver(current)
    if parsed is None:
        raise ValueError(f"mod_version '{current}' is not a strict X.Y.Z version")
    major, minor, patch = parsed
    if patch == 0:
        return f"{major}.{minor}.0"
    return f"{major}.{minor + 1}.0"


def load_changelog(path: str | None = None) -> dict:
    """Load the ledger, returning {'entries': []} when the file is absent."""
    target = path or CHANGELOG_FILE
    try:
        data = read_json(target)
    except FileNotFoundError:
        return {"entries": []}
    if not isinstance(data, dict) or not isinstance(data.get("entries"), list):
        raise ValueError(f"{target}: expected an object with an 'entries' array")
    return data


def save_changelog(data: dict, path: str | None = None) -> None:
    write_json(path or CHANGELOG_FILE, data)


def find_entry(entries: list[dict], entry_id: str) -> dict | None:
    for e in entries:
        if e.get("id") == entry_id:
            return e
    return None


def make_entry(
    entry_id: str,
    version: str,
    entry_type: str,
    title: str,
    summary: str,
    date: str,
    highlights: list[str] | None = None,
    pr: int | None = None,
) -> dict:
    """Build a normalized, unreleased entry. Keys are inserted in display order."""
    entry: dict = {
        "id": entry_id,
        "version": version,
        "type": entry_type,
        "title": title,
        "summary": summary,
        "highlights": list(highlights or []),
    }
    if pr is not None:
        entry["pr"] = pr
    entry["date"] = date
    entry["released"] = False
    entry["released_in"] = None
    entry["released_at"] = None
    return entry


def append_entry(entries: list[dict], entry: dict) -> list[dict]:
    """Return a new entries list with `entry` appended. Raises on duplicate id."""
    if find_entry(entries, entry["id"]) is not None:
        raise ValueError(f"changelog entry id '{entry['id']}' already exists")
    return [*entries, entry]


def unreleased_entries(entries: list[dict]) -> list[dict]:
    return [e for e in entries if not e.get("released", False)]


def mark_all_released(
    entries: list[dict], released_in: str, released_at: str
) -> tuple[list[dict], int]:
    """Return (new entries, count) with every unreleased entry flipped released.

    Immutable: changed entries are fresh dicts; untouched entries are reused.
    """
    new_entries: list[dict] = []
    count = 0
    for e in entries:
        if not e.get("released", False):
            new_entries.append(
                {
                    **e,
                    "released": True,
                    "released_in": released_in,
                    "released_at": released_at,
                }
            )
            count += 1
        else:
            new_entries.append(e)
    return new_entries, count


def _render_entry(entry: dict) -> str:
    block: list[str] = []
    title = (entry.get("title") or "").strip()
    if title:
        block.append(f"**{title}**")
    summary = (entry.get("summary") or "").strip()
    if summary:
        block.append(summary)
    highlights = entry.get("highlights") or []
    if highlights:
        block.append("\n".join(f"- {h}" for h in highlights))
    return "\n\n".join(block)


def render_markdown(entries: list[dict]) -> str:
    """Render entries as player-facing Markdown, grouped by version (desc).

    Returns "" when there are no entries (so a release with nothing logged
    simply falls back to the workflow's generate-notes path).
    """
    if not entries:
        return ""
    groups: dict[str, list[dict]] = {}
    for e in entries:
        groups.setdefault(e.get("version", ""), []).append(e)
    ordered_versions = sorted(
        groups, key=lambda v: parse_semver(v) or (-1, -1, -1), reverse=True
    )
    sections: list[str] = []
    for version in ordered_versions:
        body = "\n\n".join(_render_entry(e) for e in groups[version])
        sections.append(f"### {version}\n\n{body}")
    return "\n\n".join(sections) + "\n"


def utc_today() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d")


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def write_github_output(**kv: Any) -> None:
    """Append key=value pairs to $GITHUB_OUTPUT (and echo them) for the workflow."""
    gh_out = os.environ.get("GITHUB_OUTPUT")
    fh = open(gh_out, "a") if gh_out else None
    try:
        for k, v in kv.items():
            line = f"{k}={v}"
            print(line)
            if fh:
                fh.write(line + "\n")
    finally:
        if fh:
            fh.close()
