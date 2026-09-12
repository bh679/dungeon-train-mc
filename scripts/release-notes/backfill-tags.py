#!/usr/bin/env python3
"""Backfill `tags` on changelog entries that have none.

One-off migration kept in the repo so the pass is reproducible and its rules
reviewable. Every entry without a `tags` key gets the type-derived tag (see
changelog_io.TYPE_TAGS) plus whichever topical tags the keyword rules below
match against its title, summary and highlights. Entries that already carry
`tags` are left exactly as they are, so re-running is a no-op.

Usage:
  python3 scripts/release-notes/backfill-tags.py            # rewrite the ledger
  python3 scripts/release-notes/backfill-tags.py --dry-run  # report only
  python3 scripts/release-notes/backfill-tags.py --show TAG # list entries a tag would get
  python3 scripts/release-notes/backfill-tags.py --retag TAG # recompute one tag after tuning its rule

Path honours the CHANGELOG_FILE env override.
"""
import argparse
import re
import sys
from collections import Counter

import changelog_io

# Keyword rules: tag -> regex matched (case-insensitively) against the entry's
# title + summary + highlights. Deliberately word-bounded and specific: a broad
# word ("item", "spawn") drags in half the ledger and makes the tag useless.
#
# Tags in TITLE_ONLY are matched against the title alone. Lesson from the first
# pass: the summaries talk *around* a topic constantly — "the books other players
# wrote", "most noticeable on multiplayer servers", "server owners can set this
# in the config" — so a body match says the topic was mentioned, not that the
# change is about it. The title says what the change is about. Multiplayer was
# 50 tagged / ~5 real on body matching; keep any tag whose vocabulary doubles as
# everyday narration in TITLE_ONLY.
TITLE_ONLY = {"multiplayer"}

RULES: dict[str, str] = {
    "editor": (
        r"\beditor\b|\bbuilder\b|\btemplates?\b|\bstage organi[sz]er|\bsub-?variants?\b"
        r"|\bx menu\b|\bworkshop\b|\bstamp(ing)?\b|\bportal room author|\bcarriage part"
    ),
    "multiplayer": (
        r"\bmultiplayer\b|\bdedicated servers?\b|\blan\b|\bremote players?\b"
        r"|\bplayers? (joins?|joining|leav(e|es|ing))\b"
    ),
    "community": (
        r"\brelay\b|\bshared (carriage|book|build|content)s?|\bleaderboards?\b|\bcredits\b"
        r"|\bvideos? page|\btwitch\b|\byoutube\b|\bdiscord\b|\bmenu chat\b|\bcommunity\b"
        r"|\bsupporters?\b|\bbackers?\b|\bdonat|\bsubmit(ted)? (a |your )?(build|book|carriage)"
        r"|\bplayer(-| )(authored|made|written|built)|\bvote|\bsigning\b|\bcredited\b"
    ),
    "translations": (
        r"\btranslat|\blocali[sz]|\blanguages?\b|\blocales?\b|\bi18n\b|\bin \d+ languages"
    ),
    "compatibility": (
        r"\bshaders?\b|\biris\b|\bsodium\b|\boculus\b|\bdistant horizons\b|\bmodpack\b"
        r"|\bcurseforge\b|\bmodrinth\b|\bcompanion mods?\b|\bcompat"
        r"|\badventure item (names|stats)\b|\binteractive player mobs\b|\bender chest persistence\b"
        r"|\btrade everything\b|\bkeep trim\b|\bworldedit\b|\bvivecraft\b|\bjade\b|\bneoforge\b"
        r"|\bsable\b|\bc2me\b|\bmodernfix\b|\bferritecore\b|\bappleskin\b|\bjourneymap\b|\bxaero"
    ),
    "world": (
        r"\btrains?\b|\bcarriages?\b|\bworld ?gen|\bbiomes?\b|\bnether\b|\bthe end\b|\bend (city|dimension)"
        r"|\bterrain\b|\btunnels?\b|\bbridges?\b|\bportal rooms?\b|\bdimension"
        r"|\bupside down\b|\bflatbed\b|\btracks?\b"
    ),
    "mobs": (
        r"\bmobs?\b|\bechoe?s?\b|\bvillagers?\b|\bzombies?\b|\bskeletons?\b|\bcreepers?\b|\bghasts?\b"
        r"|\bendermen\b|\benderman\b|\bpiglins?\b|\bboss(es)?\b|\bhostiles?\b|\bmonsters?\b"
        r"|\b(mob|monster|enemy|hostile) spawn|\bspawn(s|ing)? (of )?(mobs|monsters|enemies)"
        r"|\bplayer ?mobs?\b|\benemies\b|\benemy\b|\bwolf\b|\bwolves\b"
        r"|\bhorses?\b|\banimals?\b|\bwardens?\b|\bwither\b|\billagers?\b|\bpillagers?\b"
    ),
    "loot": (
        r"\bloot\b|\bchests?\b|\bpotions?\b|\bbooks?\b|\btreasure\b|\barmou?r\b"
        r"|\bweapons?\b|\benchant|\btrades?\b|\bshop\b|\bbrush(ing|able)|\blectern"
        r"|\bbackpack|\bdeath note\b|\bplayerbooks?\b|\bletters?\b|\bgear\b"
    ),
    "advancements": r"\badvancements?\b|\bachievements?\b|\bunlock",
    "ui": (
        r"\bmenus?\b|\bscreens?\b|\bpages?\b|\bbuttons?\b|\bhud\b|\btooltips?\b|\bpopup"
        r"|\boverlay\b|\bsplash\b|\bui\b|\bcinematic\b|\bcamera\b|\bkeybind"
        r"|\btitle screen\b|\bpause menu\b|\bdeath screen\b|\bsurvey\b|\bconfig screen"
    ),
    "balance": (
        r"\bbalanc|\bdifficulty\b|\brebalanc|\b(loot|spawn|drop) (weights?|rates?)\b|\bharder\b"
        r"|\beasier\b|\bscal(e|es|ing) with\b|\btuning\b|\bnerf|\bbuff(ed)?\b|\bless often\b"
        r"|\bmore often\b|\brarer\b|\bmore common\b|\bover-?powered\b|\btougher\b"
    ),
    "performance": (
        r"\bperformance\b|\blag\b|\bstutter|\bfps\b|\bfaster\b|\bmemory\b|\bleak\b|\bfreez"
        r"|\bhang(s|ing)?\b|\bhitch|\bsmoother\b|\bslow(er|down)?\b|\btick rate\b|\bframe"
    ),
}

_COMPILED = {tag: re.compile(rx, re.IGNORECASE) for tag, rx in RULES.items()}


def entry_text(entry: dict) -> str:
    parts = [entry.get("title") or "", entry.get("summary") or ""]
    parts.extend(entry.get("highlights") or [])
    return "\n".join(parts)


def rule_matches(tag: str, entry: dict) -> bool:
    rx = _COMPILED.get(tag)
    if rx is None:
        return False
    text = entry.get("title") or "" if tag in TITLE_ONLY else entry_text(entry)
    return rx.search(text) is not None


def topical_tags(entry: dict) -> list[str]:
    """Topical tags the keyword rules assign to `entry` (canonical order)."""
    return [tag for tag in changelog_io.VALID_TAGS if rule_matches(tag, entry)]


def tags_for(entry: dict) -> list[str]:
    return changelog_io.normalise_tags(entry.get("type", ""), topical_tags(entry))


def backfill(entries: list[dict]) -> tuple[list[dict], int]:
    """Return (new entries, count changed). Entries already tagged are reused untouched."""
    out: list[dict] = []
    changed = 0
    for e in entries:
        if "tags" in e:
            out.append(e)
            continue
        tags = tags_for(e)
        # Rebuild so `tags` sits after `type`, matching make_entry's key order.
        rebuilt: dict = {}
        for k, v in e.items():
            rebuilt[k] = v
            if k == "type":
                rebuilt["tags"] = tags
        if "tags" not in rebuilt:
            rebuilt["tags"] = tags
        out.append(rebuilt)
        changed += 1
    return out, changed


def retag(entries: list[dict], tag: str) -> tuple[list[dict], int]:
    """Recompute ONE topical tag on every entry from the current rule.

    For re-running after a rule is tuned: other tags are untouched, the
    type-derived tag is never removed. Returns (new entries, count changed).
    """
    if tag in changelog_io.TYPE_TAGS.values() and tag not in RULES:
        raise ValueError(f"'{tag}' is type-derived only; nothing to recompute")
    out: list[dict] = []
    changed = 0
    for e in entries:
        current = list(e.get("tags") or [])
        wanted = set(current) - {tag}
        if rule_matches(tag, e) or changelog_io.TYPE_TAGS.get(e.get("type")) == tag:
            wanted.add(tag)
        tags = [t for t in changelog_io.VALID_TAGS if t in wanted]
        if tags == current:
            out.append(e)
        else:
            out.append({**e, "tags": tags})
            changed += 1
    return out, changed


def report(entries: list[dict]) -> None:
    counts: Counter[str] = Counter()
    untagged: list[dict] = []
    for e in entries:
        tags = e.get("tags", [])
        counts.update(tags)
        if not tags:
            untagged.append(e)
    print(f"{len(entries)} entries")
    for tag in changelog_io.VALID_TAGS:
        print(f"  {tag:14} {counts[tag]}")
    print(f"  {'(none)':14} {len(untagged)}")
    for e in untagged[:20]:
        print(f"    - [{e.get('type')}] {e.get('title')}")


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Backfill changelog entry tags.")
    p.add_argument("--dry-run", action="store_true", help="Report; do not write.")
    p.add_argument("--show", metavar="TAG", choices=changelog_io.VALID_TAGS,
                   help="List the titles the rules give TAG, then exit.")
    p.add_argument("--retag", metavar="TAG", choices=changelog_io.VALID_TAGS,
                   help="Recompute TAG on every entry from its (tuned) rule; other tags untouched.")
    args = p.parse_args(argv)

    data = changelog_io.load_changelog()
    new_entries, changed = backfill(data["entries"])
    if args.retag:
        new_entries, changed = retag(new_entries, args.retag)

    if args.show:
        for e in new_entries:
            if args.show in e.get("tags", []):
                print(f"[{e['type']}] {e['title']}")
        return 0

    report(new_entries)
    if args.dry_run:
        print(f"(dry run — {changed} entries would be tagged)")
        return 0
    if changed == 0:
        print("Nothing to backfill.")
        return 0
    changelog_io.save_changelog({**data, "entries": new_entries})
    print(f"{'Retagged' if args.retag else 'Tagged'} {changed} entries.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
