#!/usr/bin/env python3
"""Stamp the per-line translation provenance sidecars for a new wave or review pass.

The two recipes (composable in one run — sync happens first, then stamps):

  New translation wave (after adding lang keys):
      python3 scripts/localization/stamp-provenance.py --sync --author 'Opus 4.8 (Claude)'
    Adds every lang key missing from provenance as {author: NAME, reviewer: ""},
    deletes orphaned entries, and rewrites each sidecar in its lang file's key order.
    Existing entries are preserved untouched.

  Human review pass (after a translator reviews/revises lines):
      python3 scripts/localization/stamp-provenance.py --locale zh_cn \
          --author 阿世xAsh --reviewer 阿世xAsh --prefix gui.dungeontrain.support.
    Restamps author and/or reviewer on the selected existing keys. Selection is one
    of --keys / --prefix / --all. Restamping --author WITHOUT --reviewer resets
    reviewer to "" — a re-translated line invalidates its previous review.

Names passed to --author / --reviewer must exist in ``localization/authors.json``
(reviewers must be registered as human) — register a new model or translator there
first, so the AI-vs-human measurement in check-provenance.py stays computable.

Every run also refreshes the GENERATED count fields (total_keys / ai_authored /
ai_unreviewed) in the shipped ``localization_credits/<locale>.json`` assets — the
data behind the AI-fraction ring in the language list. check-provenance.py fails
hard when those counts drift from the sidecars, so never hand-edit them.

There is no --dry-run: sidecars are git-tracked, so ``git diff`` is the dry run.

Usage:
  python3 scripts/localization/stamp-provenance.py --sync --author NAME
  python3 scripts/localization/stamp-provenance.py [--locale L ...] \
      [--author NAME] [--reviewer NAME] (--keys K ... | --prefix P | --all)
"""
import argparse
import json
import sys
from pathlib import Path

import provenance_io


def sync_locale(lang: dict[str, str], prov: dict, author: str | None) -> tuple[dict, int, int]:
    """A new sidecar aligned to the lang file: (synced, added_count, removed_count)."""
    missing = [k for k in lang if k not in prov]
    if missing and not author:
        raise ValueError(
            f"{len(missing)} key(s) need provenance (e.g. {', '.join(missing[:3])}) — "
            f"pass --author '<who translated them>'"
        )
    synced = {
        key: dict(prov[key]) if key in prov else {"author": author, "reviewer": ""}
        for key in lang  # lang-file order; orphans drop out by construction
    }
    return synced, len(missing), len(set(prov) - set(lang))


def select_keys(prov: dict, keys: list[str] | None, prefix: str | None,
                select_all: bool) -> list[str]:
    """The existing keys a stamp operation applies to (raises on a bad selection)."""
    if select_all:
        return list(prov)
    if prefix is not None:
        matched = [k for k in prov if k.startswith(prefix)]
        if not matched:
            raise ValueError(f"--prefix {prefix!r} matched no keys")
        return matched
    absent = [k for k in keys if k not in prov]
    if absent:
        raise ValueError(
            f"--keys named {len(absent)} key(s) not in the sidecar "
            f"(e.g. {', '.join(absent[:3])}) — sync first if they are new"
        )
    return list(keys)


def stamp_locale(prov: dict, targets: list[str], author: str | None,
                 reviewer: str | None) -> dict:
    """A new sidecar with author/reviewer restamped on ``targets``."""
    stamped = {key: dict(entry) for key, entry in prov.items()}
    for key in targets:
        if author is not None:
            stamped[key]["author"] = author
            # A changed translation invalidates the previous review.
            stamped[key]["reviewer"] = reviewer if reviewer is not None else ""
        elif reviewer is not None:
            stamped[key]["reviewer"] = reviewer
    return stamped


def refresh_credit_counts(locale: str, prov: dict, authors: dict[str, str],
                          credits_dir: Path) -> list[Path]:
    """Stamp the generated count fields into every credit file for ``locale``.

    The locale-wide AI counts (total_keys / ai_authored / ai_unreviewed) are the
    same for every file; ``contributed_keys`` is per-file, derived from the credit's
    own ``name`` — present only when that person touched at least one key (so an
    AI-placeholder credit, whose name matches no author, carries no such field).

    Writes only when a file's fields would actually change (so untouched files stay
    byte-identical); returns the written paths. A locale with no credit file is a
    silent no-op — the ring is simply absent in game.
    """
    contrib_field = provenance_io.CREDIT_CONTRIB_FIELD
    counts = dict(zip(provenance_io.CREDIT_COUNT_FIELDS, provenance_io.ai_counts(prov, authors)))
    written: list[Path] = []
    for path in provenance_io.credit_paths_for_locale(credits_dir, locale):
        credit = provenance_io.load_credit(path)
        contributed = provenance_io.contributed_keys(prov, credit.get("name", ""))
        # New fields append after the hand-edited ones; existing counts update in place.
        updated = {**credit, **counts}
        if contributed > 0:
            updated[contrib_field] = contributed
        else:
            updated.pop(contrib_field, None)  # never a contributor, or no longer one
        if updated == credit:
            continue
        provenance_io.write_credit(path, updated)
        written.append(path)
    return written


def process_locale(locale: str, lang_dir: Path, prov_dir: Path,
                   args: argparse.Namespace) -> tuple[dict, str]:
    """Compute one locale's updated sidecar; returns (new sidecar, summary line).

    Pure — the caller writes only after every locale computes cleanly, so a bad
    --keys selection on locale N can't leave locales 1..N-1 half-stamped on disk.
    """
    lang = provenance_io.load_lang(lang_dir / f"{locale}.json")
    prov_path = prov_dir / f"{locale}.json"
    prov = provenance_io.load_provenance(prov_path) if prov_path.is_file() else {}

    added = removed = 0
    if args.sync:
        prov, added, removed = sync_locale(lang, prov, args.author)

    stamped = 0
    if args.selecting:
        targets = select_keys(prov, args.keys, args.prefix, args.all)
        prov = stamp_locale(prov, targets, args.author, args.reviewer)
        stamped = len(targets)

    parts = []
    if args.sync:
        parts.append(f"added {added}, removed {removed}, order synced")
    if args.selecting:
        what = " + ".join(
            f for f, v in (("author", args.author), ("reviewer", args.reviewer)) if v is not None
        )
        parts.append(f"stamped {what} on {stamped} key(s)")
    return prov, f"OK: {locale} — {'; '.join(parts)}."


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lang-dir", type=Path, default=provenance_io.DEFAULT_LANG_DIR)
    parser.add_argument("--provenance-dir", type=Path,
                        default=provenance_io.DEFAULT_PROVENANCE_DIR)
    parser.add_argument("--authors-file", type=Path, default=provenance_io.DEFAULT_AUTHORS_FILE)
    parser.add_argument("--credits-dir", type=Path, default=provenance_io.DEFAULT_CREDITS_DIR)
    parser.add_argument("--locale", action="append",
                        help="restrict to specific locale(s); default all non-en_us")
    parser.add_argument("--sync", action="store_true",
                        help="align each sidecar's key set + order to its lang file")
    parser.add_argument("--author", help="author name: fills new keys under --sync, "
                                         "restamps selected keys with --keys/--prefix/--all")
    parser.add_argument("--reviewer", help="reviewer name for the selected keys")
    parser.add_argument("--keys", nargs="+", help="explicit keys to stamp")
    parser.add_argument("--prefix", help="stamp every key starting with this prefix")
    parser.add_argument("--all", action="store_true", help="stamp every key")
    args = parser.parse_args(argv)

    selections = [s for s in (args.keys is not None, args.prefix is not None, args.all) if s]
    args.selecting = bool(selections)
    if len(selections) > 1:
        print("ERROR: --keys, --prefix and --all are mutually exclusive", file=sys.stderr)
        return 2
    if args.selecting and args.author is None and args.reviewer is None:
        print("ERROR: a selection needs --author and/or --reviewer to stamp", file=sys.stderr)
        return 2
    if args.reviewer is not None and not args.selecting:
        print("ERROR: --reviewer needs a selection (--keys / --prefix / --all)", file=sys.stderr)
        return 2
    if not args.sync and not args.selecting:
        print("ERROR: nothing to do — pass --sync and/or a stamp selection", file=sys.stderr)
        return 2

    if not args.lang_dir.is_dir():
        print(f"ERROR: lang dir not found at {args.lang_dir}", file=sys.stderr)
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
    all_locales = provenance_io.locales(args.lang_dir)
    targets = args.locale or all_locales
    unknown = sorted(set(targets) - set(all_locales))
    if unknown:
        print(f"ERROR: unknown locale(s): {', '.join(unknown)}", file=sys.stderr)
        return 2

    results: list[tuple[str, dict, str]] = []
    for locale in targets:
        try:
            prov, summary = process_locale(locale, args.lang_dir, args.provenance_dir, args)
            results.append((locale, prov, summary))
        except ValueError as exc:
            print(f"Provenance stamp FAILED:\n  - {locale}: {exc}", file=sys.stderr)
            return 1
    for locale, prov, summary in results:
        provenance_io.write_provenance(args.provenance_dir / f"{locale}.json", prov)
        stamped = refresh_credit_counts(locale, prov, authors, args.credits_dir)
        if stamped:
            names = ", ".join(p.name for p in stamped)
            summary = f"{summary[:-1]}; counts refreshed in {names}."
        print(summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
