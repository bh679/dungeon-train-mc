#!/usr/bin/env python3
"""Guard the per-book narrative provenance sidecars against drift.

Every locale under ``src/main/resources/data/dungeontrain/narrative_localizations/`` must
have a sidecar at ``localization/narrative_provenance/<locale>.json`` whose key set AND key
order exactly match that locale's books (each book keyed by its path relative to the locale
dir, sans ``.json``), and whose entries are exactly ``{"author": non-empty str, "reviewer":
str}``. This is what forces every future narrative-translation wave to record who produced
it: land new book translations without provenance and this check fails at PR time with the
fix command.

Every author name must be registered in ``localization/authors.json`` (name → "ai" |
"human"), and every non-empty reviewer must be a registered HUMAN — an AI cannot
human-review. ``--report`` shows, per locale, how much is AI-authored and unreviewed.

This is the narrative counterpart of check-provenance.py; unlike the UI lang files, narrative
books have no shipped credit files / AI-ring, so there are no count fields to cross-check.

Usage:
  python3 scripts/localization/check-narrative-provenance.py
  python3 scripts/localization/check-narrative-provenance.py --report
  python3 scripts/localization/check-narrative-provenance.py --locale zh_cn
"""
import argparse
import json
import sys
from pathlib import Path

import provenance_io

FIX_HINT = ("fix: python3 scripts/localization/stamp-narrative-provenance.py --sync "
            "--author '<who translated>'")


def check_file_coverage(narrative_dir: Path, prov_dir: Path, targets: list[str]) -> list[str]:
    """Missing sidecars and orphan sidecars (a sidecar with no matching locale dir)."""
    errors: list[str] = []
    for locale in targets:
        if not (prov_dir / f"{locale}.json").is_file():
            errors.append(f"{locale}: no provenance sidecar at "
                          f"{prov_dir / (locale + '.json')} — {FIX_HINT}")
    if prov_dir.is_dir():
        locale_dirs = set(provenance_io.narrative_locales(narrative_dir))
        for path in sorted(prov_dir.glob("*.json")):
            if path.stem not in locale_dirs:
                errors.append(f"{path.name}: orphan sidecar — no matching locale dir.")
    return errors


def check_locale(locale: str, narrative_dir: Path, prov_dir: Path) -> list[str]:
    """Structure + book-set + book-order checks for one locale's sidecar."""
    prov_path = prov_dir / f"{locale}.json"
    books = provenance_io.narrative_books(narrative_dir / locale)
    try:
        prov = provenance_io.load_provenance(prov_path)
    except (json.JSONDecodeError, ValueError) as exc:
        return [f"{locale}: unparseable sidecar — {exc}"]

    errors = [f"{locale}: {err}" for err in provenance_io.validate_entries(prov)]

    missing = [b for b in books if b not in prov]
    orphaned = [b for b in prov if b not in books]
    if missing:
        errors.append(
            f"{locale}: {len(missing)} book(s) missing from provenance "
            f"(e.g. {', '.join(missing[:3])}) — {FIX_HINT}"
        )
    if orphaned:
        errors.append(
            f"{locale}: {len(orphaned)} provenance entry(ies) no longer a book "
            f"(e.g. {', '.join(orphaned[:3])}) — {FIX_HINT}"
        )
    if not missing and not orphaned:
        book_keys, prov_keys = list(books), list(prov)
        if book_keys != prov_keys:
            i = next(i for i, (a, b) in enumerate(zip(book_keys, prov_keys)) if a != b)
            errors.append(
                f"{locale}: book order diverges from the locale dir at index {i} "
                f"(books: {book_keys[i]!r}, provenance: {prov_keys[i]!r}) — {FIX_HINT}"
            )
    return errors


def check_registry(locale: str, prov: dict, authors: dict[str, str]) -> list[str]:
    """Errors for unregistered authors and non-human (or unregistered) reviewers."""
    errors: list[str] = []
    for book, entry in prov.items():
        if not isinstance(entry, dict):
            continue  # shape errors already reported by check_locale
        author = entry.get("author")
        if isinstance(author, str) and author.strip() and author not in authors:
            errors.append(
                f"{locale}: {book}: author {author!r} is not in localization/authors.json — "
                f"register the name (as \"ai\" or \"human\") first"
            )
        reviewer = entry.get("reviewer")
        if isinstance(reviewer, str) and reviewer:
            if reviewer not in authors:
                errors.append(
                    f"{locale}: {book}: reviewer {reviewer!r} is not in "
                    f"localization/authors.json — register the name first"
                )
            elif authors[reviewer] != "human":
                errors.append(
                    f"{locale}: {book}: reviewer {reviewer!r} is registered as "
                    f"{authors[reviewer]!r} — only a human can human-review"
                )
    return errors


def print_report(targets: list[str], prov_dir: Path, authors: dict[str, str]) -> None:
    """The at-a-glance per-locale table (validated data only)."""
    print(f"{'locale':<8} {'books':>5} {'ai':>5} {'ai-unrev':>8} {'%ai-unrev':>9} "
          f"{'reviewed':>8}  authors")
    tot = {"books": 0, "ai": 0, "unrev": 0, "reviewed": 0}
    for locale in targets:
        prov = provenance_io.load_provenance(prov_dir / f"{locale}.json")
        buckets: dict[str, int] = {}
        for entry in prov.values():
            buckets[entry["author"]] = buckets.get(entry["author"], 0) + 1
        _, ai, unrev = provenance_io.ai_counts(prov, authors)
        reviewed = sum(1 for e in prov.values() if e["reviewer"])
        tot = {"books": tot["books"] + len(prov), "ai": tot["ai"] + ai,
               "unrev": tot["unrev"] + unrev, "reviewed": tot["reviewed"] + reviewed}
        pct = 100.0 * unrev / len(prov) if prov else 0.0
        names = ", ".join(f"{name}={n}"
                          for name, n in sorted(buckets.items(), key=lambda kv: -kv[1]))
        print(f"{locale:<8} {len(prov):>5} {ai:>5} {unrev:>8} {pct:>9.1f} "
              f"{reviewed:>8}  {names}")
    pct = 100.0 * tot["unrev"] / tot["books"] if tot["books"] else 0.0
    print(f"{'TOTAL':<8} {tot['books']:>5} {tot['ai']:>5} {tot['unrev']:>8} {pct:>9.1f} "
          f"{tot['reviewed']:>8}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--narrative-dir", type=Path,
                        default=provenance_io.DEFAULT_NARRATIVE_DIR)
    parser.add_argument("--provenance-dir", type=Path,
                        default=provenance_io.DEFAULT_NARRATIVE_PROVENANCE_DIR)
    parser.add_argument("--authors-file", type=Path, default=provenance_io.DEFAULT_AUTHORS_FILE)
    parser.add_argument("--locale", action="append",
                        help="restrict to specific locale(s); default all")
    parser.add_argument("--report", action="store_true",
                        help="print the per-locale coverage table (after validating)")
    args = parser.parse_args(argv)

    if not args.narrative_dir.is_dir():
        print(f"ERROR: narrative dir not found at {args.narrative_dir}", file=sys.stderr)
        return 2
    if not args.authors_file.is_file():
        print(f"ERROR: author registry not found at {args.authors_file}", file=sys.stderr)
        return 2
    try:
        authors = provenance_io.load_authors(args.authors_file)
    except (json.JSONDecodeError, ValueError) as exc:
        print(f"ERROR: bad author registry — {exc}", file=sys.stderr)
        return 2
    all_locales = provenance_io.narrative_locales(args.narrative_dir)
    targets = args.locale or all_locales
    unknown = sorted(set(targets) - set(all_locales))
    if unknown:
        print(f"ERROR: unknown locale(s): {', '.join(unknown)}", file=sys.stderr)
        return 2

    errors = check_file_coverage(args.narrative_dir, args.provenance_dir, targets)
    checkable = [loc for loc in targets
                 if (args.provenance_dir / f"{loc}.json").is_file()]
    for locale in checkable:
        locale_errors = check_locale(locale, args.narrative_dir, args.provenance_dir)
        try:
            prov = provenance_io.load_provenance(args.provenance_dir / f"{locale}.json")
        except (json.JSONDecodeError, ValueError):
            errors.extend(locale_errors)
            continue  # already reported by check_locale
        errors.extend(locale_errors)
        errors.extend(check_registry(locale, prov, authors))

    if errors:
        print("Narrative provenance check FAILED:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1

    if args.report:
        print_report(checkable, args.provenance_dir, authors)
    else:
        for locale in checkable:
            prov = provenance_io.load_provenance(args.provenance_dir / f"{locale}.json")
            reviewed = sum(1 for e in prov.values() if e["reviewer"])
            _, _, unrev = provenance_io.ai_counts(prov, authors)
            print(f"OK: {locale} — {len(prov)} books aligned, {reviewed} human-reviewed, "
                  f"{unrev} AI-unreviewed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
