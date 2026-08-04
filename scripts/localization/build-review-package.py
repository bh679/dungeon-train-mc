#!/usr/bin/env python3
"""Build a translator review package: every line that needs a human translator's eyes.

Two conditions, across all three bodies the provenance system tracks (dungeontrain UI,
sibling-mod UI, narrative books):

  needs_first_review          author is registered ``ai`` and reviewer is "" — machine
                              translation nobody has checked. Read straight from the sidecars.
  source_changed_since_review the line IS human-reviewed, but the ENGLISH has been edited
                              since that review landed — so the review attests a translation
                              of text that no longer exists. Nothing in the schema records
                              this (``stamp-provenance.py`` resets the reviewer when the
                              *translation* changes, not when the *source* does), so it is
                              derived from git history here.

Output is one CSV per locale per body under ``localization/review/`` (gitignored — generated),
each row carrying the English, the current translation, and two empty columns for the
translator to fill: ``new_translation`` and ``verdict``. Feed the filled file back through
``apply-review-csv.py``.

Usage:
  python3 scripts/localization/build-review-package.py [--locale zh_cn ...] [--out DIR]
"""
import argparse
import csv
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path

import provenance_io as pio

REPO = pio.REPO_ROOT
NARRATIVE_EN_DIR = REPO / "src/main/resources/data/dungeontrain/narratives"
DEFAULT_OUT = REPO / "localization" / "review"

NEEDS_FIRST = "needs_first_review"
SOURCE_CHANGED = "source_changed_since_review"

COLUMNS = ["key", "reason", "author", "reviewer", "english", "current_translation",
           "english_changed_at", "reviewed_at", "new_translation", "verdict"]
BOOK_COLUMNS = ["book", "reason", "author", "reviewer", "english_file", "translation_file",
                "english_changed_at", "reviewed_at", "verdict", "notes"]


# ---------------------------------------------------------------- git archaeology

def _commits(path: Path, repo: Path = REPO) -> list[tuple[str, int]]:
    """(sha, unix-time) for every commit touching ``path``, oldest first."""
    rel = path.relative_to(repo).as_posix()
    out = subprocess.run(["git", "log", "--reverse", "--format=%H %ct", "--", rel],
                         cwd=repo, capture_output=True, text=True, check=True).stdout
    return [(line.split()[0], int(line.split()[1])) for line in out.splitlines() if line.strip()]


def _blob(sha: str, path: Path, repo: Path = REPO) -> dict:
    """``path`` as JSON at commit ``sha``; {} if absent or unparseable at that point."""
    rel = path.relative_to(repo).as_posix()
    r = subprocess.run(["git", "show", f"{sha}:{rel}"], cwd=repo,
                       capture_output=True, text=True)
    if r.returncode != 0:
        return {}
    try:
        data = json.loads(r.stdout)
    except json.JSONDecodeError:
        return {}
    return data if isinstance(data, dict) else {}


def value_change_times(path: Path, repo: Path = REPO) -> dict[str, int]:
    """key -> unix time its VALUE last changed in ``path`` (including when first added).

    The last known value is remembered ACROSS a key's absence, deliberately: a key can leave
    the file and come back with identical text (#868 dropped four consent-card keys and #869
    re-added them verbatim), and that is not an English edit — flagging it would send a
    translator to re-check lines whose text never moved.
    """
    times: dict[str, int] = {}
    last: dict[str, str] = {}
    for sha, when in _commits(path, repo):
        for key, value in _blob(sha, path, repo).items():
            if key not in last or last[key] != value:
                times[key] = when
                last[key] = value
    return times


def review_stamp_times(path: Path, repo: Path = REPO) -> dict[str, int]:
    """key -> unix time its CURRENT reviewer was stamped in a provenance sidecar."""
    times: dict[str, int] = {}
    prev: dict = {}
    for sha, when in _commits(path, repo):
        cur = _blob(sha, path, repo)
        for key, entry in cur.items():
            if not isinstance(entry, dict):
                continue
            was = prev.get(key)
            before = was.get("reviewer") if isinstance(was, dict) else None
            if entry.get("reviewer") != before:
                times[key] = when
        prev = cur
    return times


def file_change_time(path: Path, repo: Path = REPO) -> int | None:
    """Unix time ``path`` was last committed, or None if it has no history."""
    commits = _commits(path, repo)
    return commits[-1][1] if commits else None


def stamp(when: int | None) -> str:
    return datetime.fromtimestamp(when, timezone.utc).strftime("%Y-%m-%d") if when else ""


# ---------------------------------------------------------------- row building

def classify(entry: dict, authors: dict[str, str], en_changed: int | None,
             reviewed: int | None) -> str | None:
    """Which condition this line falls under, or None when it needs no review.

    A source change at the SAME timestamp as the review is not stale: a squash merge can
    carry an English edit and its review in one commit, and the review wins there.
    """
    author, reviewer = entry.get("author", ""), entry.get("reviewer", "")
    if not reviewer:
        return NEEDS_FIRST if authors.get(author) == "ai" else None
    if en_changed and reviewed and en_changed > reviewed:
        return SOURCE_CHANGED
    return None


def write_csv(path: Path, columns: list[str], rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    # utf-8-sig: Excel/WPS need the BOM or CJK opens as mojibake.
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=columns)
        w.writeheader()
        w.writerows(rows)


def ui_rows(ns: pio.Namespace, locale: str, authors: dict[str, str],
            english: dict[str, str], en_times: dict[str, int]) -> list[dict]:
    prov_path = ns.prov_dir / f"{locale}.json"
    lang_path = ns.lang_dir / f"{locale}.json"
    if not prov_path.exists() or not lang_path.exists():
        return []
    prov = pio.load_provenance(prov_path)
    lang = pio.load_lang(lang_path)
    reviewed_times = review_stamp_times(prov_path)

    rows = []
    for key, entry in prov.items():
        reason = classify(entry, authors, en_times.get(key), reviewed_times.get(key))
        if not reason:
            continue
        rows.append({
            "key": key,
            "reason": reason,
            "author": entry.get("author", ""),
            "reviewer": entry.get("reviewer", ""),
            "english": english.get(key, ""),
            "current_translation": lang.get(key, ""),
            "english_changed_at": stamp(en_times.get(key)),
            "reviewed_at": stamp(reviewed_times.get(key)),
            "new_translation": "",
            "verdict": "",
        })
    return rows


def book_rows(locale: str, authors: dict[str, str], en_book_times: dict[str, int]) -> list[dict]:
    prov_path = pio.DEFAULT_NARRATIVE_PROVENANCE_DIR / f"{locale}.json"
    locale_dir = pio.DEFAULT_NARRATIVE_DIR / locale
    if not prov_path.exists() or not locale_dir.is_dir():
        return []
    prov = pio.load_provenance(prov_path)
    reviewed_times = review_stamp_times(prov_path)

    rows = []
    for book, entry in prov.items():
        reason = classify(entry, authors, en_book_times.get(book), reviewed_times.get(book))
        if not reason:
            continue
        en_file = NARRATIVE_EN_DIR / f"{book}.json"
        rows.append({
            "book": book,
            "reason": reason,
            "author": entry.get("author", ""),
            "reviewer": entry.get("reviewer", ""),
            "english_file": en_file.relative_to(REPO).as_posix() if en_file.exists() else "",
            "translation_file": (locale_dir / f"{book}.json").relative_to(REPO).as_posix(),
            "english_changed_at": stamp(en_book_times.get(book)),
            "reviewed_at": stamp(reviewed_times.get(book)),
            "verdict": "",
            "notes": "",
        })
    return rows


# ---------------------------------------------------------------- generated docs

README = """# Translation review — what's in here, and what to do with it

Every line in these files needs a human translator's eyes, for one of two reasons (the
`reason` column):

| reason | meaning |
|---|---|
| `needs_first_review` | Machine translation (see `author`) that no human has checked. |
| `source_changed_since_review` | You (or another translator) reviewed this line, but the **English has been edited since** — so the translation may no longer match what it's translating. `reviewed_at` vs `english_changed_at` shows the gap. |

`SUMMARY.md` has the counts per language. Start with your own language's folder.

## Reviewing a UI line (`dungeontrain-ui.csv`, `siblings-ui.csv`)

Fill in the last two columns and send the file back — that's all:

| `verdict` | meaning |
|---|---|
| `ok` | The translation is fine as it stands. Credited as reviewed by you; the original author stays. |
| `fixed` | Put your wording in **`new_translation`**. You become the line's author *and* its reviewer. |
| `skip` | Not sure / leave for later. Nothing changes. |

Leave `verdict` blank and the line is simply untouched, so a partly-filled file is fine to
return at any point.

**Two things must survive translation** (the game and CI both check):

- Placeholders — `%s`, `%1$s`, `%2$s`. Same ones, same count. `%1$s`/`%2$s` are numbered, so
  you may reorder them to fit your grammar; plain `%s` must stay in the same order.
- Line breaks written as `\\n` — keep the same number of them.

## Reviewing a book (`narrative-books.csv`)

Books are reviewed whole, not line by line. `english_file` and `translation_file` are paths in
the repository; read both, then set `verdict` (`ok` / `fixed` / `skip`) and use `notes` for
anything worth saying. If you rewrite a book, edit `translation_file` directly.

## Where the files actually live

| What | Where |
|---|---|
| The translations you'd edit | `src/main/resources/assets/dungeontrain/lang/<locale>.json` |
| Sibling-mod translations (Adventure Item Names, PlayerMob, Discord Presence) | `src/main/resources/assets/<mod>/lang/<locale>.json` |
| Book translations | `src/main/resources/data/dungeontrain/narrative_localizations/<locale>/` |
| **The author / reviewer record** | `localization/provenance/<locale>.json`, `localization/provenance/<mod>/<locale>.json`, `localization/narrative_provenance/<locale>.json` |
| Who's registered as a translator | `localization/authors.json` |

**Please don't hand-edit the provenance files.** The in-game "AI" badge and the blue ring in
the language list are generated from them, and CI fails if the two drift apart. They're
updated by a script instead:

```bash
# a filled-in CSV, applied for you (translations + credit in one step):
python3 scripts/localization/apply-review-csv.py --reviewer "<your name>" localization/review/<locale>/dungeontrain-ui.csv

# or, if you edited the JSON directly — lines you rewrote:
python3 scripts/localization/stamp-provenance.py --locale <locale> --author "<your name>" --reviewer "<your name>" --keys <key> ...
# lines you confirmed as they were:
python3 scripts/localization/stamp-provenance.py --locale <locale> --reviewer "<your name>" --keys <key> ...
```

A new translator name has to be added to `localization/authors.json` first — the scripts
refuse names they don't know, so the credits can't drift.

Regenerate this package any time with:

```bash
python3 scripts/localization/build-review-package.py
```
"""


def write_summary(out: Path, tally: dict) -> None:
    lines = ["# Review queue — counts per language", "",
             "`first` = never human-reviewed machine translation · "
             "`stale` = reviewed, but the English changed afterwards", "",
             "| locale | UI first | UI stale | siblings first | siblings stale | books first | books stale |",
             "|---|---|---|---|---|---|---|"]
    for locale in sorted(tally):
        t = tally[locale]
        lines.append("| %s | %d | %d | %d | %d | %d | %d |" % (
            locale, t["ui_first"], t["ui_stale"], t["sib_first"], t["sib_stale"],
            t["book_first"], t["book_stale"]))
    total = {k: sum(t[k] for t in tally.values()) for k in
             ("ui_first", "ui_stale", "sib_first", "sib_stale", "book_first", "book_stale")}
    lines += ["| **total** | %d | %d | %d | %d | %d | %d |" % (
        total["ui_first"], total["ui_stale"], total["sib_first"], total["sib_stale"],
        total["book_first"], total["book_stale"]), "",
        "Sibling-mod lines can only ever show `first`: those mods keep their English in their",
        "own repositories, so there is no English text here to compare a review against.", ""]
    (out / "SUMMARY.md").write_text("\n".join(lines), encoding="utf-8")


# ---------------------------------------------------------------- main

def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--locale", action="append", dest="locales",
                    help="restrict to this locale (repeatable; default: all)")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = ap.parse_args()

    authors = pio.load_authors(pio.DEFAULT_AUTHORS_FILE)
    all_ns = pio.namespaces()
    dt = all_ns[0]
    siblings = all_ns[1:]

    english = pio.load_lang(dt.lang_dir / "en_us.json")
    print("reading git history for en_us.json …")
    en_times = value_change_times(dt.lang_dir / "en_us.json")
    print(f"  {len(en_times)} keys dated")

    print("reading git history for the English books …")
    en_book_times = {
        p.relative_to(NARRATIVE_EN_DIR).with_suffix("").as_posix(): file_change_time(p)
        for p in sorted(NARRATIVE_EN_DIR.rglob("*.json"))
    }
    en_book_times = {k: v for k, v in en_book_times.items() if v}
    print(f"  {len(en_book_times)} books dated")

    wanted = set(args.locales) if args.locales else None
    tally = {}
    for locale in pio.locales(dt.lang_dir):
        if wanted and locale not in wanted:
            continue
        t = dict.fromkeys(("ui_first", "ui_stale", "sib_first", "sib_stale",
                           "book_first", "book_stale"), 0)
        dest = args.out / locale

        rows = ui_rows(dt, locale, authors, english, en_times)
        t["ui_first"] = sum(r["reason"] == NEEDS_FIRST for r in rows)
        t["ui_stale"] = len(rows) - t["ui_first"]
        if rows:
            write_csv(dest / "dungeontrain-ui.csv", COLUMNS, rows)

        sib_rows = []
        for ns in siblings:
            for r in ui_rows(ns, locale, authors, {}, {}):
                sib_rows.append({**r, "key": f"[{ns.name}] {r['key']}"})
        t["sib_first"] = sum(r["reason"] == NEEDS_FIRST for r in sib_rows)
        t["sib_stale"] = len(sib_rows) - t["sib_first"]
        if sib_rows:
            write_csv(dest / "siblings-ui.csv", COLUMNS, sib_rows)

        books = book_rows(locale, authors, en_book_times)
        t["book_first"] = sum(r["reason"] == NEEDS_FIRST for r in books)
        t["book_stale"] = len(books) - t["book_first"]
        if books:
            write_csv(dest / "narrative-books.csv", BOOK_COLUMNS, books)

        tally[locale] = t
        print(f"{locale}: ui {len(rows)} (stale {t['ui_stale']}) · "
              f"siblings {len(sib_rows)} · books {len(books)}")

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "README.md").write_text(README, encoding="utf-8")
    write_summary(args.out, tally)
    print(f"\nwrote {args.out.relative_to(REPO)}/ — README.md, SUMMARY.md and "
          f"{len(tally)} locale folders")


if __name__ == "__main__":
    main()
