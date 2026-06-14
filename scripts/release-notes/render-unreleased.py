#!/usr/bin/env python3
"""Render all unreleased changelog entries as Markdown (release-time step).

Run by the agent before dispatching a release. It prints the player-facing
release notes for every entry not yet marked released — i.e. everything merged
since the last real release — grouped by version (newest first). The agent
shows this to the user for confirmation, then passes it to release.yml via
`-f changelog=...`.

Read-only. Prints nothing to stdout (exit 0) when nothing is unreleased, so the
release falls back to the workflow's generate-notes path.

Usage:
  python3 scripts/release-notes/render-unreleased.py

Path honours the CHANGELOG_FILE env override.
"""
import sys

import changelog_io


def main(argv: list[str] | None = None) -> int:
    data = changelog_io.load_changelog()
    entries = changelog_io.unreleased_entries(data["entries"])
    markdown = changelog_io.render_markdown(entries)
    if markdown:
        sys.stdout.write(markdown)
    else:
        print("No unreleased changelog entries.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
