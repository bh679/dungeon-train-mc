#!/usr/bin/env python3
"""Append one curated entry to the changelog ledger (Gate-3 merge step).

Run by the agent on the feature branch during Gate 3, before merging, so the
entry lands in the PR diff and squash-merges atomically. The shipped version is
computed from gradle.properties using the same rule as version-bump.yml, so the
logged `version` matches what CI assigns after the squash.

Usage:
  python3 scripts/release-notes/append-entry.py \
    --id toolsmith-shop-carriage \
    --type feat \
    --title "Toolsmith shop carriage + armorer loot rebalance" \
    --summary "A toolsmith shop carriage now rides the train, trading tools…" \
    --highlight "New toolsmith shop carriage" \
    --highlight "Rebalanced armorer chest loot" \
    --tag train --tag loot --tag balance \
    --pr 360

Tags: the type-derived tag (feat→feature, fix→fix, content→content,
perf→performance) is added automatically. Topical tags are the agent's call,
made from the diff and intent it holds at Gate 3 — there is no keyword fallback.
A tag decision is mandatory: pass --tag for each topical tag that applies
(editor, multiplayer, community, translations, compatibility, train, world, mobs,
loot, books, advancements, ui, balance), or --no-topical-tags to state that only
the type-derived tag applies. `--tag-guide` prints the question to answer for
each tag. The Versions page filters release notes by these.

Optional:
  --version X.Y.Z   Override the computed version (rarely needed).

Paths honour CHANGELOG_FILE / GRADLE_PROPERTIES_FILE env overrides.
"""
import argparse
import sys

import changelog_io


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Append a changelog ledger entry.")
    p.add_argument(
        "--tag-guide",
        action="store_true",
        help="Print the per-tag question to answer when choosing --tag, then exit.",
    )
    p.add_argument("--id", help="Unique slug, e.g. toolsmith-shop-carriage")
    p.add_argument("--type", choices=changelog_io.VALID_TYPES)
    p.add_argument("--title", help="Short headline.")
    p.add_argument("--summary", help="Player-facing prose.")
    p.add_argument(
        "--highlight",
        action="append",
        default=[],
        dest="highlights",
        help="A bullet point (repeatable).",
    )
    p.add_argument(
        "--tag",
        action="append",
        default=[],
        dest="tags",
        choices=changelog_io.VALID_TAGS,
        metavar="TAG",
        help="A topical tag (repeatable). One of: " + ", ".join(changelog_io.VALID_TAGS)
        + ". The type-derived tag is always added.",
    )
    p.add_argument(
        "--no-topical-tags",
        action="store_true",
        help="Explicitly state that no topical tag applies (only the type-derived tag).",
    )
    p.add_argument("--pr", type=int, default=None, help="PR number (optional).")
    p.add_argument(
        "--version",
        default=None,
        help="Override the computed ship version (X.Y.Z).",
    )
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)

    if args.tag_guide:
        print(changelog_io.render_tag_guide())
        return 0

    missing = [f for f in ("id", "type", "title", "summary") if getattr(args, f) is None]
    if missing:
        print(
            "::error::missing required argument(s): " + ", ".join(f"--{f}" for f in missing),
            file=sys.stderr,
        )
        return 1

    if args.tags and args.no_topical_tags:
        print("::error::--no-topical-tags contradicts --tag", file=sys.stderr)
        return 1
    if not args.tags and not args.no_topical_tags:
        print(
            "::error::no tag decision: pass --tag for each topical tag that applies, "
            "or --no-topical-tags if none do.\n" + changelog_io.render_tag_guide(),
            file=sys.stderr,
        )
        return 1

    if args.version is not None:
        if changelog_io.parse_semver(args.version) is None:
            print(
                f"::error::--version '{args.version}' is not a strict X.Y.Z version",
                file=sys.stderr,
            )
            return 1
        version = args.version
    else:
        current = changelog_io.read_mod_version()
        if current is None:
            print(
                "::error::could not read mod_version from gradle.properties "
                "(pass --version to override)",
                file=sys.stderr,
            )
            return 1
        try:
            version = changelog_io.compute_release_version(current)
        except ValueError as e:
            print(f"::error::{e}", file=sys.stderr)
            return 1

    data = changelog_io.load_changelog()
    entry = changelog_io.make_entry(
        entry_id=args.id,
        version=version,
        entry_type=args.type,
        title=args.title,
        summary=args.summary,
        date=changelog_io.utc_today(),
        highlights=args.highlights,
        pr=args.pr,
        tags=args.tags,
    )
    try:
        new_entries = changelog_io.append_entry(data["entries"], entry)
    except ValueError as e:
        print(f"::error::{e}", file=sys.stderr)
        return 1

    changelog_io.save_changelog({**data, "entries": new_entries})
    print(
        f"Logged changelog entry '{args.id}' for v{version} "
        f"({len(new_entries)} entr{'y' if len(new_entries) == 1 else 'ies'} total)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
