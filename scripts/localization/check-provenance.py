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

Also cross-checks the shipped ``localization_credits/<locale>.json`` ``human_reviewed``
flag against per-line reviewer coverage. That is a WARNING, never an error: zh_cn
legitimately ships ``human_reviewed: true`` with partial line coverage (the #770
review predates the Opus-added keys), and failing on it would train everyone to
ignore the guard.

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


def print_report(targets: list[str], lang_dir: Path, prov_dir: Path) -> None:
    """The at-a-glance per-locale coverage table (validated data only)."""
    print(f"{'locale':<8} {'keys':>5} {'reviewed':>8} {'%rev':>6}  authors")
    total_keys = total_reviewed = 0
    for locale in targets:
        prov = provenance_io.load_provenance(prov_dir / f"{locale}.json")
        buckets: dict[str, int] = {}
        reviewed = 0
        for entry in prov.values():
            buckets[entry["author"]] = buckets.get(entry["author"], 0) + 1
            if entry["reviewer"]:
                reviewed += 1
        total_keys += len(prov)
        total_reviewed += reviewed
        pct = 100.0 * reviewed / len(prov) if prov else 0.0
        authors = ", ".join(f"{name}={n}"
                            for name, n in sorted(buckets.items(), key=lambda kv: -kv[1]))
        print(f"{locale:<8} {len(prov):>5} {reviewed:>8} {pct:>6.1f}  {authors}")
    pct = 100.0 * total_reviewed / total_keys if total_keys else 0.0
    print(f"{'TOTAL':<8} {total_keys:>5} {total_reviewed:>8} {pct:>6.1f}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lang-dir", type=Path, default=provenance_io.DEFAULT_LANG_DIR)
    parser.add_argument("--provenance-dir", type=Path,
                        default=provenance_io.DEFAULT_PROVENANCE_DIR)
    parser.add_argument("--credits-dir", type=Path, default=provenance_io.DEFAULT_CREDITS_DIR)
    parser.add_argument("--locale", action="append",
                        help="restrict to specific locale(s); default all non-en_us")
    parser.add_argument("--report", action="store_true",
                        help="print the per-locale coverage table (after validating)")
    args = parser.parse_args(argv)

    if not args.lang_dir.is_dir():
        print(f"ERROR: lang dir not found at {args.lang_dir}", file=sys.stderr)
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
        errors.extend(check_locale(locale, args.lang_dir, args.provenance_dir))

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
        print_report(targets, args.lang_dir, args.provenance_dir)
    else:
        for locale in targets:
            prov = provenance_io.load_provenance(args.provenance_dir / f"{locale}.json")
            reviewed = sum(1 for e in prov.values() if e["reviewer"])
            print(f"OK: {locale} — {len(prov)} keys aligned, {reviewed} human-reviewed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
