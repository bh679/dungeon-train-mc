#!/usr/bin/env python3
"""Fail a PR whose merge would lose a changelog entry.

Run on the PR's merge ref (what actions/checkout gives a pull_request job). Every entry id on the
base branch tip or the PR head must still be in the merged changelog.json, unless one side
deliberately deleted it since their merge base. This catches the classic conflict resolution
"take main's file" that drops the branch's own entries, and the reverse.

    python3 scripts/release-notes/check-changelog-ids.py --base origin/main --head <pr head sha>
"""
import argparse
import json
import subprocess
import sys

CHANGELOG_PATH = ".github/release-notes/changelog.json"


def ids_at(rev: str) -> set[str]:
    raw = subprocess.run(["git", "show", f"{rev}:{CHANGELOG_PATH}"], check=False, text=True,
                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout
    if not raw.strip():
        return set()
    return {e["id"] for e in json.loads(raw).get("entries", []) if isinstance(e, dict) and "id" in e}


def lost_ids(base: set[str], ours: set[str], theirs: set[str], merged: set[str]) -> set[str]:
    """Ids on either side missing from the merge, less the ones a side removed on purpose."""
    deliberately_removed = (base - ours) | (base - theirs)
    return (ours | theirs) - merged - deliberately_removed


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--base", required=True, help="the PR's base branch tip")
    parser.add_argument("--head", required=True, help="the PR's head commit")
    parser.add_argument("--merged", default="HEAD", help="the merge result (default HEAD)")
    args = parser.parse_args(argv)
    merge_base = subprocess.run(["git", "merge-base", args.base, args.head], check=True,
                                text=True, stdout=subprocess.PIPE).stdout.strip()
    lost = lost_ids(ids_at(merge_base), ids_at(args.base), ids_at(args.head), ids_at(args.merged))
    if lost:
        for eid in sorted(lost):
            print(f"::error file={CHANGELOG_PATH}::changelog entry '{eid}' is lost by this merge")
        print("A changelog.json conflict was resolved by taking one side. Re-merge, then run\n"
              "  python3 scripts/release-notes/merge-changelog.py --resolve\n"
              "which keeps every entry from both sides.", file=sys.stderr)
        return 1
    print(f"✓ no changelog entries lost ({len(ids_at(args.merged))} in the merge)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
