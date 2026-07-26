#!/usr/bin/env python3
"""Stamp the per-book translation provenance sidecars for the narrative books/stories.

The long-form book translations under
``src/main/resources/data/dungeontrain/narrative_localizations/<locale>/`` are tracked one
entry PER BOOK (a book is translated as a unit), not per line — so each locale's sidecar at
``localization/narrative_provenance/<locale>.json`` is a flat map keyed by the book's path
relative to the locale dir (e.g. ``random_books/deathnote``):

    {"random_books/deathnote": {"author": "Opus 4.8 (Claude)", "reviewer": ""}}

This is the narrative counterpart of stamp-provenance.py (which does the flat-key UI lang
files). Same two recipes, same author-registry rules, same one-entry-per-line on-disk format
(``provenance_io.write_provenance``). Nothing here ships in the jar.

  New translation wave (after adding/translating books):
      python3 scripts/localization/stamp-narrative-provenance.py --sync \
          --author 'Opus 4.8 (Claude)'
    Adds every book missing from a sidecar as {author: NAME, reviewer: ""}, drops orphans,
    and rewrites each sidecar in book-path order. Existing entries are preserved untouched.

  Human review pass (after a translator reviews a book):
      python3 scripts/localization/stamp-narrative-provenance.py --locale zh_cn \
          --author 老本願 --reviewer 老本願 --files random_books/deathnote
    Restamps author and/or reviewer on the selected books. Restamping --author WITHOUT
    --reviewer resets reviewer to "" — a re-translated book invalidates its previous review.

Names passed to --author / --reviewer must exist in ``localization/authors.json`` (reviewers
must be registered as human), same as the lang provenance.
"""
import argparse
import json
import sys
from pathlib import Path

import provenance_io


def sync_locale(books: list[str], prov: dict, author: str | None) -> tuple[dict, int, int]:
    """A sidecar aligned to ``books`` (book-path order): (synced, added, removed)."""
    missing = [b for b in books if b not in prov]
    if missing and not author:
        raise ValueError(
            f"{len(missing)} book(s) need provenance (e.g. {', '.join(missing[:3])}) — "
            f"pass --author '<who translated them>'"
        )
    synced = {
        book: dict(prov[book]) if book in prov else {"author": author, "reviewer": ""}
        for book in books  # book order; orphans drop out by construction
    }
    return synced, len(missing), len(set(prov) - set(books))


def select_books(prov: dict, files: list[str] | None, prefix: str | None,
                 select_all: bool) -> list[str]:
    """The existing book keys a stamp operation applies to (raises on a bad selection)."""
    if select_all:
        return list(prov)
    if prefix is not None:
        matched = [b for b in prov if b.startswith(prefix)]
        if not matched:
            raise ValueError(f"--prefix {prefix!r} matched no books")
        return matched
    absent = [b for b in files if b not in prov]
    if absent:
        raise ValueError(
            f"--files named {len(absent)} book(s) not in the sidecar "
            f"(e.g. {', '.join(absent[:3])}) — sync first if they are new"
        )
    return list(files)


def stamp_books(prov: dict, targets: list[str], author: str | None,
                reviewer: str | None) -> dict:
    """A new sidecar with author/reviewer restamped on ``targets``."""
    stamped = {book: dict(entry) for book, entry in prov.items()}
    for book in targets:
        if author is not None:
            stamped[book]["author"] = author
            # A re-translated book invalidates the previous review.
            stamped[book]["reviewer"] = reviewer if reviewer is not None else ""
        elif reviewer is not None:
            stamped[book]["reviewer"] = reviewer
    return stamped


def process_locale(locale: str, narrative_dir: Path, prov_dir: Path,
                   args: argparse.Namespace) -> tuple[dict, str]:
    """Compute one locale's updated sidecar; returns (new sidecar, summary line). Pure."""
    books = provenance_io.narrative_books(narrative_dir / locale)
    prov_path = prov_dir / f"{locale}.json"
    prov = provenance_io.load_provenance(prov_path) if prov_path.is_file() else {}

    added = removed = 0
    if args.sync:
        prov, added, removed = sync_locale(books, prov, args.author)

    stamped = 0
    if args.selecting:
        targets = select_books(prov, args.files, args.prefix, args.all)
        prov = stamp_books(prov, targets, args.author, args.reviewer)
        stamped = len(targets)

    parts = []
    if args.sync:
        parts.append(f"added {added}, removed {removed}, order synced")
    if args.selecting:
        what = " + ".join(
            f for f, v in (("author", args.author), ("reviewer", args.reviewer)) if v is not None
        )
        parts.append(f"stamped {what} on {stamped} book(s)")
    return prov, f"OK: {locale} — {'; '.join(parts)}."


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--narrative-dir", type=Path,
                        default=provenance_io.DEFAULT_NARRATIVE_DIR)
    parser.add_argument("--provenance-dir", type=Path,
                        default=provenance_io.DEFAULT_NARRATIVE_PROVENANCE_DIR)
    parser.add_argument("--authors-file", type=Path, default=provenance_io.DEFAULT_AUTHORS_FILE)
    parser.add_argument("--locale", action="append",
                        help="restrict to specific locale(s); default all")
    parser.add_argument("--sync", action="store_true",
                        help="align each sidecar's book set + order to the locale dir")
    parser.add_argument("--author", help="author name: fills new books under --sync, "
                                         "restamps selected books with --files/--prefix/--all")
    parser.add_argument("--reviewer", help="reviewer name for the selected books")
    parser.add_argument("--files", nargs="+",
                        help="explicit book keys to stamp (e.g. random_books/deathnote)")
    parser.add_argument("--prefix", help="stamp every book whose key starts with this prefix")
    parser.add_argument("--all", action="store_true", help="stamp every book")
    args = parser.parse_args(argv)

    selections = [s for s in (args.files is not None, args.prefix is not None, args.all) if s]
    args.selecting = bool(selections)
    if len(selections) > 1:
        print("ERROR: --files, --prefix and --all are mutually exclusive", file=sys.stderr)
        return 2
    if args.selecting and args.author is None and args.reviewer is None:
        print("ERROR: a selection needs --author and/or --reviewer to stamp", file=sys.stderr)
        return 2
    if args.reviewer is not None and not args.selecting:
        print("ERROR: --reviewer needs a selection (--files / --prefix / --all)", file=sys.stderr)
        return 2
    if not args.sync and not args.selecting:
        print("ERROR: nothing to do — pass --sync and/or a stamp selection", file=sys.stderr)
        return 2

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
    if args.author is not None and args.author not in authors:
        print(f"ERROR: author {args.author!r} is not in {args.authors_file} — register "
              f"the name (as \"ai\" or \"human\") first", file=sys.stderr)
        return 1
    if args.reviewer is not None:
        if args.reviewer not in authors:
            print(f"ERROR: reviewer {args.reviewer!r} is not in {args.authors_file} — "
                  f"register the name first", file=sys.stderr)
            return 1
        if authors[args.reviewer] != "human":
            print(f"ERROR: reviewer {args.reviewer!r} is registered as "
                  f"{authors[args.reviewer]!r} — only a human can human-review",
                  file=sys.stderr)
            return 1

    all_locales = provenance_io.narrative_locales(args.narrative_dir)
    targets = args.locale or all_locales
    unknown = sorted(set(targets) - set(all_locales))
    if unknown:
        print(f"ERROR: unknown locale(s): {', '.join(unknown)}", file=sys.stderr)
        return 2

    results: list[tuple[str, dict, str]] = []
    for locale in targets:
        try:
            prov, summary = process_locale(locale, args.narrative_dir, args.provenance_dir, args)
            results.append((locale, prov, summary))
        except ValueError as exc:
            print(f"Narrative provenance stamp FAILED:\n  - {locale}: {exc}", file=sys.stderr)
            return 1
    for locale, prov, summary in results:
        provenance_io.write_provenance(args.provenance_dir / f"{locale}.json", prov)
        print(summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
