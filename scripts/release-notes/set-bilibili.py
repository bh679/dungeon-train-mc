#!/usr/bin/env python3
"""Attach (or remove) a major entry's Bilibili video upload: the title, description and tags in Chinese and English.

Bilibili has no posting connection, so the dp-relay Socials page shows each major update that carries this block as a
ready-to-paste video upload (投稿), with a Copy button per field and language and the update's clip to download.
Works on released entries too, so the text can follow the release.

The file is JSON:
  {"title": {"zh": "…", "en": "…"},
   "description": {"zh": "…", "en": "…"},
   "tags": {"zh": ["…"], "en": ["…"]}}
Limits are Bilibili's: title 80 characters, description 2000, 1 to 10 tags of up to 20. No long dashes.

Usage:
  python3 scripts/release-notes/set-bilibili.py --id nap-farewell-pages --file bilibili.json
  python3 scripts/release-notes/set-bilibili.py --id nap-farewell-pages --clear

Path honours the CHANGELOG_FILE env override.
"""
import argparse
import json
import sys

import changelog_io


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Attach a major entry's Bilibili video upload text.")
    p.add_argument("--id", required=True, help="Entry id to update.")
    group = p.add_mutually_exclusive_group(required=True)
    group.add_argument("--file", help="JSON file holding the bilibili block.")
    group.add_argument("--clear", action="store_true", help="Remove the bilibili block.")
    args = p.parse_args(argv)

    data = changelog_io.load_changelog()
    entry = changelog_io.find_entry(data["entries"], args.id)
    if entry is None:
        print(f"::error::no changelog entry with id '{args.id}'", file=sys.stderr)
        return 1
    if not args.clear and not entry.get("major"):
        print(f"::error::'{args.id}' is not a major entry (set-major.py first)", file=sys.stderr)
        return 1

    block = None
    if not args.clear:
        try:
            with open(args.file, encoding="utf-8") as f:
                block = changelog_io.validate_bilibili(json.load(f))
        except (OSError, ValueError) as e:  # json.JSONDecodeError is a ValueError
            print(f"::error::{e}", file=sys.stderr)
            return 1

    def updated(e: dict) -> dict:
        if e["id"] != args.id:
            return e
        rest = {k: v for k, v in e.items() if k != "bilibili"}
        return rest if block is None else {**rest, "bilibili": block}

    changelog_io.save_changelog({**data, "entries": [updated(e) for e in data["entries"]]})
    print(f"{'Removed' if block is None else 'Set'} the Bilibili post for '{args.id}'.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
