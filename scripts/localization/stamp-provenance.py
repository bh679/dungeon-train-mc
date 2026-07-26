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
    """Stamp the generated AI-count fields into every credit file for ``locale``.

    Writes only when a file's counts would actually change (so untouched files stay
    byte-identical); returns the written paths. A locale with no credit file is a
    silent no-op — the ring is simply absent in game.
    """
    counts = dict(zip(provenance_io.CREDIT_COUNT_FIELDS, provenance_io.ai_counts(prov, authors)))
    written: list[Path] = []
    for path in provenance_io.credit_paths_for_locale(credits_dir, locale):
        credit = provenance_io.load_credit(path)
        if all(credit.get(field) == value for field, value in counts.items()):
            continue
        # New fields append after the hand-edited ones; existing counts update in place.
        provenance_io.write_credit(path, {**credit, **counts})
        written.append(path)
    return written


def refresh_contributors(lang_dir: Path, prov_dir: Path, authors: dict[str, str],
                         urls: dict[str, str], contributors_file: Path) -> bool:
    """Regenerate the shipped translator-credits file from ALL locales; True if it changed.

    Global (not per-locale), so it is rebuilt from every sidecar regardless of any
    ``--locale`` filter — the file must reflect the whole picture. Byte-identical writes
    are skipped so an unchanged file never churns.
    """
    built = provenance_io.build_contributors(lang_dir, prov_dir, authors, urls)
    if contributors_file.is_file():
        try:
            if provenance_io.load_contributors(contributors_file) == built:
                return False
        except (json.JSONDecodeError, ValueError):
            pass  # unparseable/absent -> rewrite
    provenance_io.write_contributors(contributors_file, built)
    return True


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


def run_namespace(ns: provenance_io.Namespace, args: argparse.Namespace,
                  authors: dict[str, str], urls: dict[str, str]) -> int:
    """Stamp one namespace's sidecars. Prints per-locale summaries; returns an exit code.

    Computes every target locale before writing any, so a bad --keys selection can't leave
    a namespace half-stamped. Credit/contributor refresh is skipped for namespaces that carry
    none (siblings: ``credits_dir``/``contributors_file`` are None).
    """
    if not ns.lang_dir.is_dir():
        print(f"ERROR: lang dir not found at {ns.lang_dir}", file=sys.stderr)
        return 2
    all_locales = provenance_io.locales(ns.lang_dir)
    if args.locale:
        unknown = sorted(set(args.locale) - set(all_locales))
        # In single-namespace mode an unknown locale is a usage error; across the namespace
        # table it just means "not present here" (e.g. zh_cn isn't in discordpresence).
        if unknown and args.single_namespace:
            print(f"ERROR: unknown locale(s): {', '.join(unknown)}", file=sys.stderr)
            return 2
        targets = [loc for loc in args.locale if loc in all_locales]
    else:
        targets = all_locales

    results: list[tuple[str, dict, str]] = []
    for locale in targets:
        try:
            prov, summary = process_locale(locale, ns.lang_dir, ns.prov_dir, args)
            results.append((locale, prov, summary))
        except ValueError as exc:
            label = locale if args.single_namespace else f"{ns.name}/{locale}"
            print(f"Provenance stamp FAILED:\n  - {label}: {exc}", file=sys.stderr)
            return 1
    for locale, prov, summary in results:
        provenance_io.write_provenance(ns.prov_dir / f"{locale}.json", prov)
        if ns.credits_dir is not None:
            stamped = refresh_credit_counts(locale, prov, authors, ns.credits_dir)
            if stamped:
                names = ", ".join(p.name for p in stamped)
                summary = f"{summary[:-1]}; counts refreshed in {names}."
        print(summary)

    # The translator-credits file is global — always rebuilt from every sidecar, whatever
    # the --locale filter, so it never goes stale relative to a review pass elsewhere.
    if ns.contributors_file is not None:
        if refresh_contributors(ns.lang_dir, ns.prov_dir, authors, urls, ns.contributors_file):
            print(f"OK: regenerated {ns.contributors_file.name}.")
    return 0


def resolve_namespaces(args: argparse.Namespace) -> list[provenance_io.Namespace] | int:
    """The namespaces to operate on, or an error code. Sets ``args.single_namespace``.

    Explicit --lang-dir/--provenance-dir → one ad-hoc namespace (used by the tests and for
    one-off runs). Otherwise the full namespace table, optionally filtered by --namespace.
    """
    if args.lang_dir is not None or args.provenance_dir is not None:
        args.single_namespace = True
        return [provenance_io.Namespace(
            "(explicit)",
            args.lang_dir or provenance_io.DEFAULT_LANG_DIR,
            args.provenance_dir or provenance_io.DEFAULT_PROVENANCE_DIR,
            args.credits_dir, args.contributors_file)]
    args.single_namespace = False
    table = provenance_io.namespaces()
    if args.namespace:
        unknown = sorted(set(args.namespace) - {n.name for n in table})
        if unknown:
            print(f"ERROR: unknown namespace(s): {', '.join(unknown)}", file=sys.stderr)
            return 2
        return [n for n in table if n.name in args.namespace]
    return table


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lang-dir", type=Path, default=None,
                        help="operate on one explicit lang dir (default: the namespace table)")
    parser.add_argument("--provenance-dir", type=Path, default=None)
    parser.add_argument("--authors-file", type=Path, default=provenance_io.DEFAULT_AUTHORS_FILE)
    parser.add_argument("--credits-dir", type=Path, default=None)
    parser.add_argument("--contributors-file", type=Path, default=None)
    parser.add_argument("--namespace", action="append",
                        help="restrict to namespace(s); default all "
                             "(dungeontrain + siblings)")
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

    ns_list = resolve_namespaces(args)
    if isinstance(ns_list, int):
        return ns_list

    urls = provenance_io.load_author_urls(args.authors_file)
    rc = 0
    for ns in ns_list:
        rc = run_namespace(ns, args, authors, urls) or rc
    return rc


if __name__ == "__main__":
    sys.exit(main())
