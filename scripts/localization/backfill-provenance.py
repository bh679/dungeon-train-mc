#!/usr/bin/env python3
"""One-off generator of the per-line translation provenance sidecars (July 2026).

Committed for auditability: this script IS the documentation of how the initial
``localization/provenance/<locale>.json`` files were derived. It is not part of any
ongoing workflow — future translation waves are stamped with ``stamp-provenance.py``,
and rerunning this after a new lang commit fails loudly on the unmapped sha (the
commit map below is deliberately closed).

Method: for each non-English locale, walk every git snapshot of its lang file
oldest→newest, tracking the last commit that changed each key's VALUE. That commit's
wave determines the author. Reviewer stamps come from two attested review events:

  * PR #770 (``FLIP_SHA``) flipped zh_cn's ``localization_credits`` ``human_reviewed``
    to true when zh_cn had 806 keys — attesting 老本願's review of what existed then.
    A key gets ``reviewer: 老本願`` iff it existed at the flip AND its value is
    unchanged since (so a later re-translation correctly drops the stamp).
  * PR #823 (``fe025127``) applied 阿世xAsh's revision pass over the 15
    ``gui.dungeontrain.support.*`` strings (the #809 additions) in zh_cn AND zh_tw —
    12 values changed, 3 confirmed as-is. All 15 get ``reviewer: 阿世xAsh`` except
    ``gui.dungeontrain.support.subtitle``, which ships Claude's 力所能及 idiom fix
    "pending translator confirmation" (that PR's own words) and stays unreviewed.
    The 3 ``.modpack`` sibling keys the same PR added are Claude-derived variants
    (模组→整合包 over 阿世xAsh's copy) the translator never saw: author Opus,
    unreviewed.

Wave → author map (the complete history — exactly 9 commits ever touched these files):

  #754 c1a43f78  zh_cn created: 748 keys seeded from 老本願's community translation
                 + 13 Claude draft keys (the keys added to en_us in the same commit)
  #755 01e4ad2a  zh_cn parity fills                          → Claude (pre-provenance)
  #759 d229aa73  老本願's v0.458.0 translation drop           → 老本願
  #763 d9e24a00  one zh_cn value tweak                       → Claude (pre-provenance)
  #768 9e658fba  8 locales, "Machine-translated with Claude Opus 4.8"   → Opus 4.8
  #776 b8dc06a4  10 locales, "machine-translated (Opus 4.8, high effort)" → Opus 4.8
  #809 819aa678  +15 Ways-to-Help keys × 19, Opus 4.8        → Opus 4.8
  #821 051bb85c  +43 book-vote keys × 19, Opus 4.8           → Opus 4.8
  #823 fe025127  阿世xAsh revision pass (changed keys) + .modpack variants (added keys)

Usage:
  python3 scripts/localization/backfill-provenance.py
  python3 scripts/localization/backfill-provenance.py --locale zh_cn --out-dir /tmp/prov
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

import provenance_io

REPO_ROOT = provenance_io.REPO_ROOT
LANG_REL = "src/main/resources/assets/dungeontrain/lang"

AUTHOR_OPUS = "Opus 4.8 (Claude)"
AUTHOR_PRE = "Claude (pre-provenance, model unrecorded)"
AUTHOR_LBY = "老本願"
AUTHOR_ASH = "阿世xAsh"

# The closed commit → wave map. A sha marked "special" is attributed per-key in
# attribute_authors(); anything not listed here aborts the run.
WAVE_AUTHORS: dict[str, str] = {
    "c1a43f78": "special:seed",      # #754 — split 老本願 seed vs Claude drafts
    "01e4ad2a": AUTHOR_PRE,          # #755
    "d229aa73": AUTHOR_LBY,          # #759
    "d9e24a00": AUTHOR_PRE,          # #763
    "9e658fba": AUTHOR_OPUS,         # #768
    "b8dc06a4": AUTHOR_OPUS,         # #776
    "819aa678": AUTHOR_OPUS,         # #809
    "051bb85c": AUTHOR_OPUS,         # #821
    "fe025127": "special:revision",  # #823 — split 阿世xAsh revisions vs .modpack adds
}

SEED_SHA = "c1a43f78"
REVISION_SHA = "fe025127"
FLIP_SHA = "2d43c007"  # #770 — zh_cn human_reviewed flip
SUBTITLE_KEY = "gui.dungeontrain.support.subtitle"
WAYS_TO_HELP_SHA = "819aa678"  # #809 — the support.* keys 阿世xAsh's pass covered


def git_show_json(sha: str, rel_path: str) -> dict[str, str] | None:
    """The JSON object at ``sha:rel_path``, or None if the file doesn't exist there."""
    proc = subprocess.run(
        ["git", "show", f"{sha}:{rel_path}"],
        capture_output=True, text=True, cwd=REPO_ROOT,
    )
    if proc.returncode != 0:
        return None
    return json.loads(proc.stdout)


def lang_history(locale: str) -> list[str]:
    """Shas that touched the locale's lang file, oldest first."""
    rel = f"{LANG_REL}/{locale}.json"
    proc = subprocess.run(
        ["git", "log", "--reverse", "--format=%H", "--", rel],
        capture_output=True, text=True, cwd=REPO_ROOT, check=True,
    )
    return proc.stdout.split()


def keys_added_at(sha: str, rel_path: str) -> set[str]:
    """Keys present at ``sha`` but not in its parent's version of the file."""
    current = git_show_json(sha, rel_path) or {}
    parent = git_show_json(f"{sha}^", rel_path) or {}
    return set(current) - set(parent)


def last_change_by_key(locale: str) -> tuple[dict[str, str], dict[str, str]]:
    """(current lang values, key → short-sha of the last commit that set its value)."""
    rel = f"{LANG_REL}/{locale}.json"
    last_changed: dict[str, str] = {}
    prev: dict[str, str] = {}
    for sha in lang_history(locale):
        snapshot = git_show_json(sha, rel)
        if snapshot is None:
            raise RuntimeError(f"git show failed for {sha}:{rel}")
        for key, value in snapshot.items():
            if key not in prev or prev[key] != value:
                last_changed[key] = sha[:8]
        for key in set(prev) - set(snapshot):
            del last_changed[key]  # deleted; a re-adding commit re-stamps it
        prev = snapshot
    return prev, last_changed


def attribute_authors(locale: str, values: dict[str, str],
                      last_changed: dict[str, str]) -> dict[str, str]:
    """key → author name, resolving the two per-key special cases."""
    claude_draft_keys = keys_added_at(SEED_SHA, f"{LANG_REL}/en_us.json")
    revision_added = keys_added_at(REVISION_SHA, f"{LANG_REL}/{locale}.json")

    authors: dict[str, str] = {}
    for key in values:
        sha = last_changed[key]
        wave = WAVE_AUTHORS.get(sha)
        if wave is None:
            raise SystemExit(
                f"::error::unmapped lang commit {sha} for {locale}/{key} — this backfill's "
                f"commit map is closed to July 2026. New waves are stamped with "
                f"stamp-provenance.py, not by rerunning the backfill."
            )
        if wave == "special:seed":
            # #754: 13 Claude draft keys (added to en_us in the same commit); the
            # other 748 are 老本願's community translation, seeded wholesale.
            authors[key] = AUTHOR_PRE if key in claude_draft_keys else AUTHOR_LBY
        elif wave == "special:revision":
            # #823: values 阿世xAsh changed vs the .modpack variants the PR added.
            authors[key] = AUTHOR_OPUS if key in revision_added else AUTHOR_ASH
        else:
            authors[key] = wave
    return authors


def attribute_reviewers(locale: str, values: dict[str, str]) -> dict[str, str]:
    """key → reviewer name ("" = unreviewed), from the two attested review events."""
    reviewers = {key: "" for key in values}

    if locale == "zh_cn":
        flip = git_show_json(FLIP_SHA, f"{LANG_REL}/zh_cn.json") or {}
        for key, value in values.items():
            if key in flip and flip[key] == value:
                reviewers[key] = AUTHOR_LBY

    if locale in ("zh_cn", "zh_tw"):
        pass_keys = keys_added_at(WAYS_TO_HELP_SHA, f"{LANG_REL}/{locale}.json")
        for key in pass_keys & set(values):
            reviewers[key] = "" if key == SUBTITLE_KEY else AUTHOR_ASH

    return reviewers


def backfill_locale(locale: str, out_dir: Path) -> dict[str, int]:
    """Generate one sidecar; returns author-bucket counts + reviewed count."""
    values, last_changed = last_change_by_key(locale)
    authors = attribute_authors(locale, values, last_changed)
    reviewers = attribute_reviewers(locale, values)

    prov = {
        key: {"author": authors[key], "reviewer": reviewers[key]}
        for key in values  # lang-file order
    }
    provenance_io.write_provenance(out_dir / f"{locale}.json", prov)

    stats: dict[str, int] = {}
    for entry in prov.values():
        stats[entry["author"]] = stats.get(entry["author"], 0) + 1
    stats["__reviewed__"] = sum(1 for e in prov.values() if e["reviewer"])
    return stats


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lang-dir", type=Path, default=provenance_io.DEFAULT_LANG_DIR)
    parser.add_argument("--out-dir", type=Path, default=provenance_io.DEFAULT_PROVENANCE_DIR)
    parser.add_argument("--locale", action="append",
                        help="restrict to specific locale(s); default all non-en_us")
    args = parser.parse_args(argv)

    all_locales = provenance_io.locales(args.lang_dir)
    targets = args.locale or all_locales
    unknown = sorted(set(targets) - set(all_locales))
    if unknown:
        print(f"ERROR: unknown locale(s): {', '.join(unknown)}", file=sys.stderr)
        return 2

    for locale in targets:
        stats = backfill_locale(locale, args.out_dir)
        reviewed = stats.pop("__reviewed__")
        total = sum(stats.values())
        buckets = ", ".join(f"{name}={n}" for name, n in sorted(stats.items(), key=lambda kv: -kv[1]))
        print(f"OK: {locale} — {total} keys, {reviewed} reviewed; {buckets}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
