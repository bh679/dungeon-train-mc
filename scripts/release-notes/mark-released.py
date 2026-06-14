#!/usr/bin/env python3
"""Mark all unreleased changelog entries as released (CI step in release.yml).

Run by release.yml after a successful real release (auto == false). It flips
every `released: false` entry to released, stamping the release tag and a UTC
timestamp, so the next release's unreleased set starts clean. The workflow then
commits the changed ledger to main with a [skip ci] message.

Writes the file only — git commit/push lives in the workflow YAML (mirroring how
apply-change.py writes files and the workflow commits). Emits `changed` and
`count` to $GITHUB_OUTPUT so the workflow knows whether to commit.

Usage:
  python3 scripts/release-notes/mark-released.py --released-in v0.292.0

Path honours the CHANGELOG_FILE env override.
"""
import argparse
import sys

import changelog_io


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Mark unreleased entries released.")
    p.add_argument(
        "--released-in",
        required=True,
        dest="released_in",
        help="The release tag, e.g. v0.292.0",
    )
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    data = changelog_io.load_changelog()
    new_entries, count = changelog_io.mark_all_released(
        data["entries"], args.released_in, changelog_io.utc_now_iso()
    )
    if count == 0:
        print("No unreleased changelog entries to mark.")
        changelog_io.write_github_output(changed="false", count=0)
        return 0

    changelog_io.save_changelog({**data, "entries": new_entries})
    print(f"Marked {count} changelog entr{'y' if count == 1 else 'ies'} released in {args.released_in}.")
    changelog_io.write_github_output(changed="true", count=count)
    return 0


if __name__ == "__main__":
    sys.exit(main())
