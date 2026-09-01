#!/usr/bin/env python3
"""Every translated book must carry every {placeholder} its English original has.

The gap this closes. A book's figures are ``{braces}``, substituted by ``DeathLoreStore.sub`` after
``narrative/BookFactory`` has already rendered the prose with ``Component.literal``. The two format
guards that landed on 2026-08-30 — ``lang_format.py`` on the import side, ``TranslationFormatCheck``
in the in-game editor — both check printf tokens, because that is what ``Component.translatable``
parses. Neither sees a brace. So a translation that drops one passes both, and is caught only by
whichever content test happens to assert on that particular file. Three books have such a test; the
other 57 have nothing.

It nearly shipped. In the import merged as #1221, five ru_ru epitaphs came back with
``{deaths_nth}`` replaced by ``{deaths}й`` — an adjective ending glued onto the CARDINAL, so the
epitaph read "2й, кто пал" where it should read "второй, кто пал". ``DeathLoreOrdinalTest`` caught
it by luck of scope. This asks the question of every book in every locale instead.

Two rules, on different sides:

* **English** may only name tokens the resolver knows (``book_format.RESOLVED``). ``sub`` is a chain
  of literal ``.replace`` calls — an unknown token is not substituted, not reported and not escaped,
  and reaches the player as the text ``{death}``.
* **Every translation** must name the same placeholders as its English original. Where they appear,
  how often and in what order are the translator's business — ru_ru legitimately says one of a
  repeated ``{deaths}`` as "столько же раз" ("as many times"). See ``book_format.tokens``.

The baseline, and why it is not a snooze button. Eighteen of twenty locales already replaced
``{deaths_nth}`` with ``{deaths}`` plus their own ordinal glue — "als {deaths}. gefallen",
"le {deaths}e à tomber", "第 {deaths} 次倒下". Those render CORRECTLY: ``LocaleNumberWords`` returns
a plain digit for de/fr/it/nl/pl/ro/ru/ja/ko, so "als 2. gefallen" is right German. They are the
same edit SHAPE as the ru_ru bug and none of its harm, and converting them is per-language prose
work — ``LocaleOrdinalWords.digits`` gives ja/zh a PREFIX ("第2"), so "{deaths}人目" cannot be
swapped mechanically. They are listed one by one in the baseline with their exact token lists, so an
entry excuses that divergence and nothing else: any other edit to those same fields still fails, and
an entry whose file has since been fixed is reported so the list shrinks rather than rots.

Usage:  check-book-placeholders.py [--write-baseline]
Exit code is non-zero if any book diverges outside the baseline, or the baseline has stale entries.
"""
import argparse
import json
import sys
from pathlib import Path

import book_format as bf
import provenance_io as pio

#: Both English trees live here: narratives/ (the books) and death_lore/ (the epitaph pool).
DEFAULT_ENGLISH_DIR = pio.DEFAULT_NARRATIVE_DIR.parent
DEFAULT_BASELINE = pio.REPO_ROOT / "localization" / "book-placeholder-baseline.json"

#: The one English tree that is not under narratives/ — see english_path.
DEATH_LORE = "death_lore"


def english_path(english_dir: Path, book_path: str) -> Path:
    """The English original for a locale-relative book path.

    The Python inverse of ``TranslationCatalog.bookPathFor``, which is what named these paths in
    the first place: the locale overlay flattens two English trees into one namespace, keeping
    ``death_lore/`` as its own category and dropping the ``narratives/`` prefix from everything
    else.
    """
    if book_path == DEATH_LORE or book_path.startswith(DEATH_LORE + "/"):
        return english_dir / f"{book_path}.json"
    return english_dir / "narratives" / f"{book_path}.json"


def fields_of(path: Path) -> dict[str, str] | None:
    """Every editable string field of the book at ``path``, keyed by dotted path.

    None when there is no book to read — absent, or not JSON. A malformed file is somebody else's
    failure to report (the loaders and ``check-narrative-provenance.py`` both see it first), and
    guessing at its placeholders here would only bury that.
    """
    if not path.is_file():
        return None
    try:
        book = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError):
        return None
    return {field: pio.book_field_value(book, field) or ""
            for field in pio.book_string_fields(book)}


def check_english(english_dir: Path) -> list[str]:
    """Problems with the English originals themselves — tokens nothing will substitute."""
    problems = []
    roots = [english_dir / "narratives", english_dir / DEATH_LORE]
    for root in roots:
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*.json")):
            for field, value in (fields_of(path) or {}).items():
                stray = bf.unresolved(value)
                if stray:
                    problems.append(
                        f"{named(path)}#{field}: {', '.join(stray)} — nothing substitutes this, so "
                        "it reaches the player as literal text (see book_format.RESOLVED)")
    return problems


def named(path: Path) -> str:
    """``path`` as a repo-relative posix path, or as given when it is outside the repo (tests)."""
    try:
        return path.relative_to(pio.REPO_ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def divergences(narrative_dir: Path, english_dir: Path) -> dict[str, dict]:
    """Every translated field whose tokens differ from its English original's, keyed for baselining.

    The key is ``<locale>/<book>#<field>`` — the same shape the relay's unit ids use, so a
    divergence reported here can be pasted straight into a search of the review queue.
    """
    found: dict[str, dict] = {}
    for locale in pio.narrative_locales(narrative_dir):
        locale_dir = narrative_dir / locale
        for book_path in pio.narrative_books(locale_dir):
            english = fields_of(english_path(english_dir, book_path))
            if english is None:
                continue        # a locale-only book has no original to be measured against
            for field, value in (fields_of(locale_dir / f"{book_path}.json") or {}).items():
                if field not in english:
                    continue    # a field the English does not have: structure drift, not ours
                if bf.mismatch(value, english[field]) is None:
                    continue
                found[f"{locale}/{book_path}#{field}"] = {
                    "english": bf.tokens(english[field]),
                    "translated": bf.tokens(value),
                }
    return found


def load_baseline(path: Path) -> dict[str, dict]:
    """The tolerated divergences. A missing file means none are — the guard is strict by default."""
    if not path.is_file():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    return {k: v for k, v in data.items() if not k.startswith("_")}


def matches(entry: dict, actual: dict) -> bool:
    """Whether a baseline entry excuses THIS divergence, rather than the field in general."""
    return (entry.get("english") == actual["english"]
            and entry.get("translated") == actual["translated"])


def report(found: dict[str, dict], baseline: dict[str, dict]) -> list[str]:
    """Everything worth failing over: unexcused divergences, then entries that no longer apply."""
    problems = []
    for key, actual in sorted(found.items()):
        entry = baseline.get(key)
        if entry is not None and matches(entry, actual):
            continue
        why = ("does not match its baseline entry" if entry is not None
               else "drops or invents a placeholder")
        problems.append(f"{key}: {why} — English has {actual['english']}, "
                        f"the translation has {actual['translated']}")
    for key, entry in sorted(baseline.items()):
        actual = found.get(key)
        if actual is None or not matches(entry, actual):
            problems.append(f"{key}: baseline entry is stale — remove it from "
                            f"{DEFAULT_BASELINE.name}")
    return problems


def baseline_document(found: dict[str, dict]) -> dict:
    """``found`` as a baseline file, with the header that explains why it is allowed to exist."""
    doc = {
        "_comment": [
            "Book placeholder divergences that are tolerated. See check-book-placeholders.py.",
            "Every entry here is a death_lore epitaph where a locale replaced {deaths_nth} with",
            "{deaths} plus its own ordinal glue. They render CORRECTLY — LocaleNumberWords gives",
            "these languages a plain digit, so 'als {deaths}. gefallen' is right German — but they",
            "are the same edit shape as the ru_ru bug this guard exists to stop, so they are named",
            "one by one rather than excused by a rule.",
            "An entry excuses exactly these token lists and nothing else. Fixing a file makes its",
            "entry stale, which FAILS: the list is meant to shrink.",
            "Follow-up: convert these to {deaths_nth}. Roughly twelve are output-identical under",
            "LocaleOrdinalWords.digits; ja/zh_cn/zh_tw/th/it/es_mx/pt_br need a translator because",
            "digits prefixes or wraps ('第2', 'thứ 2') rather than suffixing.",
        ],
    }
    doc.update({key: found[key] for key in sorted(found)})
    return doc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--narrative-dir", type=Path, default=pio.DEFAULT_NARRATIVE_DIR)
    parser.add_argument("--english-dir", type=Path, default=DEFAULT_ENGLISH_DIR)
    parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    parser.add_argument("--write-baseline", action="store_true",
                        help="rewrite the baseline from what is on disk now — for the commit that "
                             "introduces this guard, and for a deliberate, reviewed re-baselining")
    args = parser.parse_args(argv)

    found = divergences(args.narrative_dir, args.english_dir)

    if args.write_baseline:
        args.baseline.parent.mkdir(parents=True, exist_ok=True)
        args.baseline.write_text(
            json.dumps(baseline_document(found), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8")
        print(f"wrote {len(found)} entrie(s) to {args.baseline}")
        return 0

    problems = check_english(args.english_dir)
    problems += report(found, load_baseline(args.baseline))
    if problems:
        print(f"{len(problems)} book placeholder problem(s):")
        for line in problems:
            print(f"  {line}")
        return 1

    excused = len(load_baseline(args.baseline))
    print(f"every translated book keeps its English placeholders "
          f"({excused} baselined divergence(s) tolerated)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
