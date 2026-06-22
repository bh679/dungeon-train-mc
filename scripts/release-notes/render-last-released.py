#!/usr/bin/env python3
"""Render the most recent *real* release's curated notes as Markdown.

Used by `release.yml` to build the CurseForge changelog for auto-release
cascade ticks. CurseForge's "Changelog" tab shows only the latest file's
changelog, and the latest file is perpetually a cascade micro-release (which
has no curated notes of its own). To keep that view meaningful, cascade ticks
carry forward the notes of the last real release — verbatim — on CurseForge
only (Modrinth shows full version history, so it needs no carry-forward).

"Last real release" = the entries whose `released_in` equals the highest-semver
`released_in` tag among all `released:true` entries. Those entries are rendered
grouped by version (newest first), reusing `changelog_io.render_markdown`.

Read-only. Prints nothing to stdout (exit 0) when no entry is released yet, so
the caller falls back to the workflow's generate-notes path.

Usage:
  python3 scripts/release-notes/render-last-released.py

Path honours the CHANGELOG_FILE env override.
"""
import sys

import changelog_io


def _released_in_key(tag: str | None) -> tuple[int, int, int]:
    """Semver sort key for a `released_in` tag (handles a leading 'v')."""
    parsed = changelog_io.parse_semver((tag or "").lstrip("v"))
    return parsed or (-1, -1, -1)


def last_released_entries(entries: list[dict]) -> list[dict]:
    """Return the entries belonging to the most recent real release.

    The most recent real release is the highest-semver `released_in` tag across
    all released entries. Returns [] when nothing is released yet.
    """
    released = [e for e in entries if e.get("released") and e.get("released_in")]
    if not released:
        return []
    latest_tag = max(
        (e["released_in"] for e in released), key=_released_in_key
    )
    return [e for e in released if e.get("released_in") == latest_tag]


def main(argv: list[str] | None = None) -> int:
    data = changelog_io.load_changelog()
    entries = last_released_entries(data["entries"])
    markdown = changelog_io.render_markdown(entries)
    if markdown:
        sys.stdout.write(markdown)
    else:
        print("No released changelog entries.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
