#!/usr/bin/env python3
"""Guard the per-line translation provenance sidecars against lang-file drift.

Every non-English locale in ``src/main/resources/assets/dungeontrain/lang/`` must have
a sidecar at ``localization/provenance/<locale>.json`` whose key set AND key order
exactly match that locale's lang file (each locale against its OWN lang file — zh_cn
and zh_tw legitimately carry 3 ``.modpack`` keys the other locales don't), and whose
entries are exactly ``{"author": non-empty str, "reviewer": str}``. This is what
forces every future translation wave to record who produced it: land new lang keys
without provenance and this check fails at PR time with the fix command.

en_us is the source language, not a translation — a sidecar for it is an error.

Every author name must be registered in ``localization/authors.json`` (name → "ai" |
"human"), and every non-empty reviewer must be a registered HUMAN — an AI cannot
human-review. The registry is what makes the headline metric computable: a line is
"AI-unreviewed" iff its author is registered as ai AND its reviewer is empty, and
``--report`` shows that count and percentage per locale.

Also cross-checks the shipped ``localization_credits/<locale>.json`` ``human_reviewed``
flag against per-line reviewer coverage. That is a WARNING, never an error: zh_cn
legitimately ships ``human_reviewed: true`` with partial line coverage (the #770
review predates the Opus-added keys), and failing on it would train everyone to
ignore the guard.

The GENERATED count fields in those same credit files (total_keys / ai_authored /
ai_unreviewed — the data behind the in-game AI-fraction ring) are held to a HARDER
standard: missing, malformed, or drifted counts are errors. Unlike the judgment-call
flag, the counts are machine-derived from the sidecars, so any mismatch means someone
forgot to run stamp-provenance.py (or hand-edited a generated field) and the jar
would ship a player-visible lie.

Usage:
  python3 scripts/localization/check-provenance.py
  python3 scripts/localization/check-provenance.py --report
  python3 scripts/localization/check-provenance.py --locale zh_cn --provenance-dir path
"""
import argparse
import json
import sys
from pathlib import Path

import provenance_io

FIX_HINT = "fix: python3 scripts/localization/stamp-provenance.py --sync --author '<who translated>'"
FIX_HINT_COUNTS = "fix: python3 scripts/localization/stamp-provenance.py --sync"


def check_file_coverage(lang_dir: Path, prov_dir: Path, targets: list[str]) -> list[str]:
    """Missing sidecars, orphan sidecars, and the en_us exemption."""
    errors: list[str] = []
    for locale in targets:
        if not (prov_dir / f"{locale}.json").is_file():
            errors.append(f"{locale}: no provenance sidecar at "
                          f"{prov_dir / (locale + '.json')} — {FIX_HINT}")
    if prov_dir.is_dir():
        lang_locales = set(provenance_io.locales(lang_dir))
        for path in sorted(prov_dir.glob("*.json")):
            if path.stem == provenance_io.SOURCE_LOCALE:
                errors.append(
                    f"{path.name}: en_us is the source language and is exempt from "
                    f"provenance — delete this file."
                )
            elif path.stem not in lang_locales:
                errors.append(f"{path.name}: orphan sidecar — no matching lang file.")
    return errors


def check_locale(locale: str, lang_dir: Path, prov_dir: Path) -> list[str]:
    """Structure + key-set + key-order checks for one locale's sidecar."""
    prov_path = prov_dir / f"{locale}.json"
    lang = provenance_io.load_lang(lang_dir / f"{locale}.json")
    try:
        prov = provenance_io.load_provenance(prov_path)
    except (json.JSONDecodeError, ValueError) as exc:
        return [f"{locale}: unparseable sidecar — {exc}"]

    errors = [f"{locale}: {err}" for err in provenance_io.validate_entries(prov)]

    missing = [k for k in lang if k not in prov]
    orphaned = [k for k in prov if k not in lang]
    if missing:
        errors.append(
            f"{locale}: {len(missing)} lang key(s) missing from provenance "
            f"(e.g. {', '.join(missing[:3])}) — {FIX_HINT}"
        )
    if orphaned:
        errors.append(
            f"{locale}: {len(orphaned)} provenance key(s) no longer in the lang file "
            f"(e.g. {', '.join(orphaned[:3])}) — {FIX_HINT}"
        )
    if not missing and not orphaned:
        lang_keys, prov_keys = list(lang), list(prov)
        if lang_keys != prov_keys:
            i = next(i for i, (a, b) in enumerate(zip(lang_keys, prov_keys)) if a != b)
            errors.append(
                f"{locale}: key order diverges from the lang file at index {i} "
                f"(lang: {lang_keys[i]!r}, provenance: {prov_keys[i]!r}) — {FIX_HINT}"
            )
    return errors


def check_registry(locale: str, prov: dict, authors: dict[str, str]) -> list[str]:
    """Errors for unregistered authors and non-human (or unregistered) reviewers."""
    errors: list[str] = []
    for key, entry in prov.items():
        if not isinstance(entry, dict):
            continue  # shape errors already reported by check_locale
        author = entry.get("author")
        if isinstance(author, str) and author.strip() and author not in authors:
            errors.append(
                f"{locale}: {key}: author {author!r} is not in localization/authors.json — "
                f"register the name (as \"ai\" or \"human\") first"
            )
        reviewer = entry.get("reviewer")
        if isinstance(reviewer, str) and reviewer:
            if reviewer not in authors:
                errors.append(
                    f"{locale}: {key}: reviewer {reviewer!r} is not in "
                    f"localization/authors.json — register the name first"
                )
            elif authors[reviewer] != "human":
                errors.append(
                    f"{locale}: {key}: reviewer {reviewer!r} is registered as "
                    f"{authors[reviewer]!r} — only a human can human-review"
                )
    return errors


def check_credit_counts(locale: str, prov: dict, authors: dict[str, str],
                        credits_dir: Path) -> list[str]:
    """Hard lockstep between the shipped generated count fields and the sidecar.

    Unlike cross_check_credits (advisory), these are ERRORS: the counts drive the
    AI-fraction ring in the language list, so stale shipped numbers mislead players.
    Every credit file matching the locale must carry all three fields, exactly.
    """
    fields = provenance_io.CREDIT_COUNT_FIELDS
    expected = dict(zip(fields, provenance_io.ai_counts(prov, authors)))

    def fmt(counts: dict) -> str:
        return ", ".join(f"{f}={counts[f]}" for f in fields)

    errors: list[str] = []
    for path in provenance_io.credit_paths_for_locale(credits_dir, locale):
        credit = provenance_io.load_credit(path)
        missing = [f for f in fields if f not in credit]
        if missing:
            errors.append(
                f"{locale}: {path.name}: missing generated count field(s) "
                f"{', '.join(missing)} — {FIX_HINT_COUNTS}"
            )
            continue
        values = {f: credit[f] for f in fields}
        bad = [f for f, v in values.items()
               if not isinstance(v, int) or isinstance(v, bool) or v < 0]
        if bad:
            errors.append(
                f"{locale}: {path.name}: count field(s) {', '.join(bad)} must be "
                f"non-negative integers — {FIX_HINT_COUNTS}"
            )
            continue
        if not values["ai_unreviewed"] <= values["ai_authored"] <= values["total_keys"]:
            errors.append(
                f"{locale}: {path.name}: inconsistent counts ({fmt(values)}) — need "
                f"ai_unreviewed <= ai_authored <= total_keys — {FIX_HINT_COUNTS}"
            )
            continue
        if values != expected:
            errors.append(
                f"{locale}: {path.name}: shipped counts ({fmt(values)}) don't match "
                f"provenance ({fmt(expected)}) — {FIX_HINT_COUNTS}"
            )
    return errors


def check_contributors(lang_dir: Path, prov_dir: Path, authors: dict[str, str],
                       urls: dict[str, str], contributors_file: Path) -> list[str]:
    """Hard lockstep between the shipped translator-credits file and the sidecars.

    The Credits page reads this file, so drift ships a player-visible lie (wrong names,
    languages, or %). Rebuilt from provenance + authors.json and compared exactly; a
    missing, unparseable, or drifted file is an error with the fix command.
    """
    built = provenance_io.build_contributors(lang_dir, prov_dir, authors, urls)
    if not contributors_file.is_file():
        return [f"{contributors_file.name}: missing generated translator-credits file — "
                f"{FIX_HINT_COUNTS}"]
    try:
        shipped = provenance_io.load_contributors(contributors_file)
    except (json.JSONDecodeError, ValueError) as exc:
        return [f"{contributors_file.name}: unparseable — {exc} — {FIX_HINT_COUNTS}"]
    if shipped != built:
        return [f"{contributors_file.name}: shipped translator credits are out of date with "
                f"the provenance data — {FIX_HINT_COUNTS}"]
    return []


def credits_human_reviewed(credits_dir: Path, locale: str) -> bool:
    """Whether any shipped localization credit marks ``locale`` human-reviewed.

    Mirrors LocalizationCreditRegistry.isHumanReviewed(): any credit file with a
    matching ``locale`` and ``human_reviewed: true`` counts. Malformed files are
    skipped, same as the runtime loader.
    """
    if not credits_dir.is_dir():
        return False
    for path in credits_dir.glob("*.json"):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            continue
        if isinstance(data, dict) and data.get("locale") == locale \
                and data.get("human_reviewed") is True:
            return True
    return False


def cross_check_credits(locale: str, prov: dict, credits_dir: Path) -> list[str]:
    """Advisory-only mismatches between the locale-level flag and line coverage."""
    reviewed = sum(1 for e in prov.values() if isinstance(e, dict) and e.get("reviewer"))
    total = len(prov)
    flagged = credits_human_reviewed(credits_dir, locale)
    if flagged and reviewed < total:
        return [
            f"{locale}: localization_credits says human_reviewed=true but only "
            f"{reviewed}/{total} lines carry a reviewer — lines added since the review "
            f"are unreviewed. (Advisory only.)"
        ]
    if not flagged and total and reviewed == total:
        return [
            f"{locale}: every line is human-reviewed but localization_credits still says "
            f"human_reviewed=false — consider flipping the flag. (Advisory only.)"
        ]
    return []


def print_report(targets: list[str], prov_dir: Path, authors: dict[str, str]) -> None:
    """The at-a-glance per-locale table (validated data only).

    The headline column is %ai-unrev — how much of the locale is AI-generated text
    no human has reviewed.
    """
    print(f"{'locale':<8} {'keys':>5} {'ai':>5} {'ai-unrev':>8} {'%ai-unrev':>9} "
          f"{'reviewed':>8}  authors")
    tot = {"keys": 0, "ai": 0, "unrev": 0, "reviewed": 0}
    for locale in targets:
        prov = provenance_io.load_provenance(prov_dir / f"{locale}.json")
        buckets: dict[str, int] = {}
        for entry in prov.values():
            buckets[entry["author"]] = buckets.get(entry["author"], 0) + 1
        _, ai, unrev = provenance_io.ai_counts(prov, authors)
        reviewed = sum(1 for e in prov.values() if e["reviewer"])
        tot = {"keys": tot["keys"] + len(prov), "ai": tot["ai"] + ai,
               "unrev": tot["unrev"] + unrev, "reviewed": tot["reviewed"] + reviewed}
        pct = 100.0 * unrev / len(prov) if prov else 0.0
        names = ", ".join(f"{name}={n}"
                          for name, n in sorted(buckets.items(), key=lambda kv: -kv[1]))
        print(f"{locale:<8} {len(prov):>5} {ai:>5} {unrev:>8} {pct:>9.1f} "
              f"{reviewed:>8}  {names}")
    pct = 100.0 * tot["unrev"] / tot["keys"] if tot["keys"] else 0.0
    print(f"{'TOTAL':<8} {tot['keys']:>5} {tot['ai']:>5} {tot['unrev']:>8} {pct:>9.1f} "
          f"{tot['reviewed']:>8}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lang-dir", type=Path, default=provenance_io.DEFAULT_LANG_DIR)
    parser.add_argument("--provenance-dir", type=Path,
                        default=provenance_io.DEFAULT_PROVENANCE_DIR)
    parser.add_argument("--credits-dir", type=Path, default=provenance_io.DEFAULT_CREDITS_DIR)
    parser.add_argument("--contributors-file", type=Path,
                        default=provenance_io.DEFAULT_CONTRIBUTORS_FILE)
    parser.add_argument("--authors-file", type=Path, default=provenance_io.DEFAULT_AUTHORS_FILE)
    parser.add_argument("--locale", action="append",
                        help="restrict to specific locale(s); default all non-en_us")
    parser.add_argument("--report", action="store_true",
                        help="print the per-locale coverage table (after validating)")
    args = parser.parse_args(argv)

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
    all_locales = provenance_io.locales(args.lang_dir)
    targets = args.locale or all_locales
    unknown = sorted(set(targets) - set(all_locales))
    if unknown:
        print(f"ERROR: unknown locale(s): {', '.join(unknown)}", file=sys.stderr)
        return 2

    errors = check_file_coverage(args.lang_dir, args.provenance_dir, targets)
    checkable = [loc for loc in targets
                 if (args.provenance_dir / f"{loc}.json").is_file()]
    for locale in checkable:
        locale_errors = check_locale(locale, args.lang_dir, args.provenance_dir)
        try:
            prov = provenance_io.load_provenance(args.provenance_dir / f"{locale}.json")
        except (json.JSONDecodeError, ValueError):
            errors.extend(locale_errors)
            continue  # already reported by check_locale
        registry_errors = check_registry(locale, prov, authors)
        errors.extend(locale_errors)
        errors.extend(registry_errors)
        if not locale_errors and not registry_errors:
            # Counts computed from a structurally-broken sidecar would be noise.
            errors.extend(check_credit_counts(locale, prov, authors, args.credits_dir))

    # The translator-credits file is global; only meaningful once every sidecar parses cleanly
    # (build_contributors reads them all), so gate it on a clean run so far.
    if not errors:
        urls = provenance_io.load_author_urls(args.authors_file)
        errors.extend(check_contributors(args.lang_dir, args.provenance_dir, authors, urls,
                                         args.contributors_file))

    if errors:
        print("Provenance check FAILED:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1

    # Advisory only — deliberately does not affect the exit code (see module docstring).
    for locale in targets:
        prov = provenance_io.load_provenance(args.provenance_dir / f"{locale}.json")
        for warn in cross_check_credits(locale, prov, args.credits_dir):
            print(f"WARNING: {warn}", file=sys.stderr)

    if args.report:
        print_report(targets, args.provenance_dir, authors)
    else:
        for locale in targets:
            prov = provenance_io.load_provenance(args.provenance_dir / f"{locale}.json")
            reviewed = sum(1 for e in prov.values() if e["reviewer"])
            _, _, unrev = provenance_io.ai_counts(prov, authors)
            print(f"OK: {locale} — {len(prov)} keys aligned, {reviewed} human-reviewed, "
                  f"{unrev} AI-unreviewed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
