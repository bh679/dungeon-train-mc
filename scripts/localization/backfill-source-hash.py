#!/usr/bin/env python3
"""One-off seeding of ``source_hash`` into every provenance sidecar (September 2026).

Committed for auditability, like ``backfill-provenance.py``: this script IS the record of how
the initial ``source_hash`` values were derived. It is not part of any ongoing workflow —
from here on every stamp (``stamp-provenance.py`` / ``stamp-narrative-provenance.py``)
records the digest itself.

Method: a stamp attests the English of its moment, so for each entry find the commit that
last changed its ``author`` or ``reviewer`` (walking the sidecar's git history oldest→newest)
and hash the English as it was AT that commit — ``en_us.json`` for a lang key, the English
book file for a narrative entry. That keeps lines whose English has since moved on visible
as stale, rather than absorbing them by hashing today's English. Fallbacks:

  * no history for the key (never committed) → today's English;
  * the English lacked the key / book at that commit → today's English;
  * a sibling namespace (its English is not in this repo) → ``""`` (unknown).

Usage:
  python3 scripts/localization/backfill-source-hash.py [--dry-run]
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

import provenance_io as pio

REPO = pio.REPO_ROOT


def _commits(path: Path) -> list[str]:
    """Every commit touching ``path``, oldest first."""
    rel = path.relative_to(REPO).as_posix()
    out = subprocess.run(["git", "log", "--reverse", "--format=%H", "--", rel],
                         cwd=REPO, capture_output=True, text=True, check=True).stdout
    return [line.strip() for line in out.splitlines() if line.strip()]


_BLOBS: dict[tuple[str, str], dict | list | None] = {}


def _blob(sha: str, path: Path):
    """``path`` parsed as JSON at ``sha``; None if absent or unparseable there."""
    rel = path.relative_to(REPO).as_posix()
    key = (sha, rel)
    if key not in _BLOBS:
        r = subprocess.run(["git", "show", f"{sha}:{rel}"], cwd=REPO,
                           capture_output=True, text=True)
        try:
            _BLOBS[key] = json.loads(r.stdout) if r.returncode == 0 else None
        except json.JSONDecodeError:
            _BLOBS[key] = None
    return _BLOBS[key]


def stamp_commits(sidecar: Path) -> dict[str, str]:
    """key -> the commit that last changed its author or reviewer."""
    stamped: dict[str, str] = {}
    prev: dict = {}
    for sha in _commits(sidecar):
        cur = _blob(sha, sidecar) or {}
        for key, entry in cur.items():
            if not isinstance(entry, dict):
                continue
            was = prev.get(key) if isinstance(prev.get(key), dict) else {}
            if (entry.get("author"), entry.get("reviewer")) != (was.get("author"), was.get("reviewer")):
                stamped[key] = sha
        prev = cur
    return stamped


def lang_hashes(ns: pio.Namespace, sidecar: Path, prov: dict) -> tuple[dict[str, str], int]:
    """(key -> source_hash, how many fell back to today's English)."""
    english_path = ns.lang_dir / f"{pio.SOURCE_LOCALE}.json"
    if not english_path.is_file():
        return {key: "" for key in prov}, 0
    today = pio.english_lang(ns)
    at = stamp_commits(sidecar)
    out: dict[str, str] = {}
    fallbacks = 0
    for key in prov:
        then = _blob(at[key], english_path) if key in at else None
        value = then.get(key) if isinstance(then, dict) else None
        if not isinstance(value, str):
            value = today.get(key)
            fallbacks += 1
        out[key] = pio.source_hash(value) if value is not None else ""
    return out, fallbacks


def book_hashes(sidecar: Path, prov: dict) -> tuple[dict[str, str], int]:
    english_dir = pio.DEFAULT_NARRATIVE_DIR.parent
    at = stamp_commits(sidecar)
    out: dict[str, str] = {}
    fallbacks = 0
    for book in prov:
        path = pio.english_book_path(english_dir, book)
        then = _blob(at[book], path) if book in at else None
        if then is None:
            fallbacks += 1
            out[book] = pio.english_book_hash(english_dir, book)
        else:
            out[book] = pio.book_source_hash(then)
    return out, fallbacks


def apply(sidecar: Path, hashes: dict[str, str], prov: dict, dry_run: bool) -> None:
    seeded = {key: {**entry, "source_hash": hashes[key]} for key, entry in prov.items()}
    if not dry_run:
        pio.write_provenance(sidecar, seeded)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)

    for ns in pio.namespaces():
        for sidecar in sorted(ns.prov_dir.glob("*.json")):
            prov = pio.load_provenance(sidecar)
            hashes, fallbacks = lang_hashes(ns, sidecar, prov)
            stale = len(pio.source_changed_keys(
                {k: {"source_hash": h} for k, h in hashes.items()},
                {k: pio.source_hash(v) for k, v in pio.english_lang(ns).items()}))
            apply(sidecar, hashes, prov, args.dry_run)
            print(f"{ns.name}/{sidecar.stem}: {len(prov)} entries, {fallbacks} fell back to "
                  f"today's English, {stale} stale")

    narrative_dir = pio.DEFAULT_NARRATIVE_PROVENANCE_DIR
    english_dir = pio.DEFAULT_NARRATIVE_DIR.parent
    for sidecar in sorted(narrative_dir.glob("*.json")):
        prov = pio.load_provenance(sidecar)
        hashes, fallbacks = book_hashes(sidecar, prov)
        stale = len(pio.source_changed_keys(
            {k: {"source_hash": h} for k, h in hashes.items()},
            {k: pio.english_book_hash(english_dir, k) for k in prov}))
        apply(sidecar, hashes, prov, args.dry_run)
        print(f"books/{sidecar.stem}: {len(prov)} entries, {fallbacks} fell back to "
              f"today's English, {stale} stale")
    return 0


if __name__ == "__main__":
    sys.exit(main())
