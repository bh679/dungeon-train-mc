#!/usr/bin/env python3
"""Set the topical tags of existing changelog entries from a reviewed mapping.

The correction tool for the ledger's tags. Tags are chosen by the agent (see
changelog_io.TAG_GUIDE) — this script only applies a decision already made, it
never infers one. The mapping is `{entry id: [topical tags]}`; every listed
entry has its tags replaced by the type-derived tag plus exactly those topical
tags (canonical order). Entries not in the mapping are untouched. An unknown id
or tag aborts before anything is written.

Usage:
  python3 scripts/release-notes/set-tags.py mapping.json            # rewrite the ledger
  python3 scripts/release-notes/set-tags.py mapping.json --dry-run  # report only
  python3 scripts/release-notes/set-tags.py --report                # per-tag counts of the ledger

The report (always printed) shows per-tag counts before → after and how many
entries changed; --show-changes lists each changed entry's old and new tags.

Path honours the CHANGELOG_FILE env override.
"""
import argparse
import json
import sys
from collections import Counter

import changelog_io


def load_mapping(path: str) -> dict[str, list[str]]:
    with open(path) as f:
        raw = json.load(f)
    if not isinstance(raw, dict):
        raise ValueError("mapping must be a JSON object of {id: [tags]}")
    out: dict[str, list[str]] = {}
    for entry_id, tags in raw.items():
        if not isinstance(tags, list) or not all(isinstance(t, str) for t in tags):
            raise ValueError(f"mapping['{entry_id}'] must be a list of tag strings")
        out[entry_id] = list(tags)
    return out


def apply_mapping(entries: list[dict], mapping: dict[str, list[str]]) -> tuple[list[dict], list[tuple[dict, list[str], list[str]]]]:
    """Return (new entries, [(entry, old tags, new tags)] for those that changed).

    Raises ValueError on an id absent from the ledger or an unknown tag; the
    input list is never mutated.
    """
    known = {e["id"] for e in entries}
    missing = sorted(set(mapping) - known)
    if missing:
        raise ValueError(f"mapping names id(s) not in the ledger: {', '.join(missing)}")
    out: list[dict] = []
    changed: list[tuple[dict, list[str], list[str]]] = []
    for e in entries:
        if e["id"] not in mapping:
            out.append(e)
            continue
        try:
            tags = changelog_io.normalise_tags(e.get("type", ""), mapping[e["id"]])
        except ValueError as err:
            raise ValueError(f"{e['id']}: {err}") from err
        current = list(e.get("tags") or [])
        if tags == current:
            out.append(e)
        else:
            out.append({**e, "tags": tags})
            changed.append((e, current, tags))
    return out, changed


def tag_counter(entries: list[dict]) -> Counter[str]:
    c: Counter[str] = Counter()
    for e in entries:
        c.update(e.get("tags") or [])
    return c


def report(before: list[dict], after: list[dict], changed: list, show_changes: bool) -> None:
    b, a = tag_counter(before), tag_counter(after)
    print(f"{len(after)} entries, {len(changed)} changed")
    for tag in changelog_io.VALID_TAGS:
        delta = a[tag] - b[tag]
        arrow = f" → {a[tag]:4} ({delta:+d})" if delta else ""
        print(f"  {tag:14} {b[tag]:4}{arrow}")
    untagged = sum(1 for e in after if not e.get("tags"))
    print(f"  {'(none)':14} {untagged:4}")
    if show_changes:
        for e, old, new in changed:
            print(f"  - {e['id']}: {old} -> {new}")


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Set changelog entry tags from a reviewed mapping.")
    p.add_argument("mapping", nargs="?", help="JSON file of {entry id: [topical tags]}.")
    p.add_argument("--dry-run", action="store_true", help="Report; do not write.")
    p.add_argument("--report", action="store_true", help="Print the ledger's tag counts and exit.")
    p.add_argument("--show-changes", action="store_true", help="List each changed entry's old → new tags.")
    args = p.parse_args(argv)

    data = changelog_io.load_changelog()
    entries = data["entries"]
    if args.report or args.mapping is None:
        if args.mapping is None and not args.report:
            print("::error::a mapping file is required (or --report)", file=sys.stderr)
            return 1
        report(entries, entries, [], False)
        return 0

    try:
        mapping = load_mapping(args.mapping)
        new_entries, changed = apply_mapping(entries, mapping)
    except (OSError, ValueError, json.JSONDecodeError) as e:
        print(f"::error::{e}", file=sys.stderr)
        return 1

    report(entries, new_entries, changed, args.show_changes)
    if args.dry_run:
        print("(dry run — nothing written)")
        return 0
    if not changed:
        print("Nothing to change.")
        return 0
    changelog_io.save_changelog({**data, "entries": new_entries})
    print(f"Retagged {len(changed)} entries.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
