#!/usr/bin/env python3
"""Mark an existing changelog entry as a major release, or change its description/photo.

A major release leads the rendered release notes (title as a heading, photo,
then its markdown description) and gets the headline card on the web update
page (brennan.games/dungeontrain/update/). Works on released entries too, so a
photo can be attached after the release has shipped.

Usage:
  python3 scripts/release-notes/set-major.py --id release-capture-the-view \
    --image https://example.com/capture-the-view.png
  python3 scripts/release-notes/set-major.py --id release-capture-the-view \
    --description-file notes.md
  python3 scripts/release-notes/set-major.py --id release-capture-the-view --clear-image
  python3 scripts/release-notes/set-major.py --id release-approved-on-modrinth --milestone

Path honours the CHANGELOG_FILE env override.
"""
import argparse
import sys

import changelog_io


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Mark a changelog entry as a major release.")
    p.add_argument("--id", required=True, help="Entry id to update.")
    p.add_argument("--description-file", default=None, help="Markdown file for the release description.")
    p.add_argument("--image", default=None, help="https:// URL of the release photo.")
    p.add_argument("--clear-image", action="store_true", help="Remove the release photo.")
    p.add_argument("--milestone", action="store_true", default=None,
                   help="A moment, not an update: the update page labels it 'Milestone'.")
    p.add_argument("--not-milestone", dest="milestone", action="store_false",
                   help="Drop the milestone label.")
    args = p.parse_args(argv)

    if args.image and args.clear_image:
        print("::error::--image contradicts --clear-image", file=sys.stderr)
        return 1

    data = changelog_io.load_changelog()
    entries = data["entries"]
    if changelog_io.find_entry(entries, args.id) is None:
        print(f"::error::no changelog entry with id '{args.id}'", file=sys.stderr)
        return 1
    try:
        description = None
        if args.description_file:
            with open(args.description_file, encoding="utf-8") as f:
                description = f.read()
        new_entries = [
            changelog_io.with_major(e, description=description, image=args.image,
                                    clear_image=args.clear_image, milestone=args.milestone)
            if e["id"] == args.id else e
            for e in entries
        ]
    except (OSError, ValueError) as e:
        print(f"::error::{e}", file=sys.stderr)
        return 1

    changelog_io.save_changelog({**data, "entries": new_entries})
    print(f"Marked '{args.id}' as a major release.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
