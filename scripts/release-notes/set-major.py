#!/usr/bin/env python3
"""Mark an existing changelog entry as a major release, or change its description/photo.

A major release leads the rendered release notes (title as a heading, photo,
then its markdown description) and gets the headline card on the web update
page (dungeon.training/update/). Works on released entries too, so a
photo can be attached after the release has shipped.

Usage:
  python3 scripts/release-notes/set-major.py --id release-capture-the-view \
    --image https://example.com/capture-the-view.png
  python3 scripts/release-notes/set-major.py --id release-capture-the-view \
    --description-file notes.md
  python3 scripts/release-notes/set-major.py --id release-capture-the-view --clear-image
  python3 scripts/release-notes/set-major.py --id totems-with-a-catch \
    --video https://raw.githubusercontent.com/bh679/dungeon-train-mc/main/.github/release-notes/images/totems.mp4 \
    --video-cover 4.5
  python3 scripts/release-notes/set-major.py --id totems-with-a-catch --clear-video
  python3 scripts/release-notes/set-major.py --id totems-with-a-catch \
    --mention AshSeven instagram @ashyseven7 --mention AshSeven tiktok @ashseventh
  python3 scripts/release-notes/set-major.py --id release-approved-on-modrinth --milestone
  python3 scripts/release-notes/set-major.py --id release-advancement-tabs --notable

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
    p.add_argument("--video", default=None,
                   help="https:// URL of an .mp4 clip with sound: the social posts' videos are built from it.")
    p.add_argument("--video-cover", type=float, default=None,
                   help="The second of the clip whose frame is the social posts' cover (default 0.5).")
    for fmt in changelog_io.VIDEO_FORMATS:
        p.add_argument(f"--video-{fmt}", default=None,
                       help=f"https:// URL of an .mp4 clip recorded for the {fmt} post's visible area (a format "
                            "without one uses --video).")
    p.add_argument("--clear-video", action="store_true", help="Remove every clip and the cover time.")
    p.add_argument("--mention", nargs=3, action="append", default=None, metavar=("TEXT", "PLATFORM", "HANDLE"),
                   help="A description link whose text is TEXT shows as HANDLE (@name) in that PLATFORM's caption "
                        "(instagram or tiktok, which can't hold links). Repeat for each.")
    p.add_argument("--clear-mentions", action="store_true", help="Remove every mention (before adding --mention ones).")
    p.add_argument("--milestone", action="store_true", default=None,
                   help="A moment, not an update: the update page labels it 'Milestone'.")
    p.add_argument("--not-milestone", dest="milestone", action="store_false",
                   help="Drop the milestone label.")
    p.add_argument("--notable", action="store_true", default=None,
                   help="The middle tier: the update page shows a smaller 'Notable update' card.")
    p.add_argument("--not-notable", dest="notable", action="store_false",
                   help="Drop the notable tier (back to a full major).")
    args = p.parse_args(argv)

    if args.image and args.clear_image:
        print("::error::--image contradicts --clear-image", file=sys.stderr)
        return 1
    videos = {f: getattr(args, f"video_{f}") for f in changelog_io.VIDEO_FORMATS if getattr(args, f"video_{f}")}
    if (args.video or videos or args.video_cover is not None) and args.clear_video:
        print("::error::--video / --video-<format> / --video-cover contradict --clear-video", file=sys.stderr)
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
                                    clear_image=args.clear_image, milestone=args.milestone,
                                    notable=args.notable, video=args.video, clear_video=args.clear_video,
                                    video_cover=args.video_cover, videos=videos or None,
                                    mentions=changelog_io.mentions_from_args(args.mention),
                                    clear_mentions=args.clear_mentions)
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
