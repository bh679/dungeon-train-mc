#!/usr/bin/env python3
"""Apply a translator's filled-in review CSV back into the repo.

The return leg of ``build-review-package.py``. Reads the ``verdict`` column:

  ok      the translation stands  -> reviewer stamped, ORIGINAL AUTHOR KEPT (the documented
                                     "confirmed as-is" case)
  fixed   ``new_translation`` is written into the locale's lang file -> the translator becomes
                                     the line's author AND its reviewer (a rewrite invalidates
                                     the old attribution)
  skip    nothing happens (also the effect of leaving verdict blank)

Lang values are written in place with a text-level edit, so the file's blank-line grouping and
line endings survive (``zh_cn.json`` is CRLF).

Provenance stamping is delegated to ``stamp-provenance.py`` rather than reimplemented, so the
authors-registry check, the reviewer-must-be-a-registered-human rule and the refresh of the
shipped credit counts all keep their single implementation.

Usage:
  python3 scripts/localization/apply-review-csv.py --reviewer 老本願 \\
      localization/review/zh_cn/dungeontrain-ui.csv [--dry-run]

The locale and namespace come from the CSV's path / its ``[namespace] key`` prefixes, so one
invocation handles a whole file. Narrative-book CSVs are not applied here — a reviewed book is
edited directly; the script prints the stamp command for those instead.
"""
import argparse
import csv
import json
import re
import subprocess
import sys
from pathlib import Path

import provenance_io as pio

HERE = Path(__file__).resolve().parent
STAMP = HERE / "stamp-provenance.py"
OK, FIXED, SKIP = "ok", "fixed", "skip"
NS_PREFIX = re.compile(r"^\[([a-z]+)\]\s+(.*)$")


def locale_from_path(path: Path) -> str:
    """The locale is the CSV's parent directory (localization/review/<locale>/…)."""
    return path.parent.name


def split_namespace(key: str) -> tuple[str, str]:
    """``"[playermob] some.key"`` -> ("playermob", "some.key"); bare keys -> dungeontrain."""
    m = NS_PREFIX.match(key)
    return (m.group(1), m.group(2)) if m else ("dungeontrain", key)


def set_lang_value(path: Path, key: str, value: str) -> bool:
    """Replace one key's value in a lang file, preserving formatting and line endings."""
    # newline="" on the READ too: without it Python translates CRLF to LF before we look, so
    # the probe below always says LF and zh_cn.json gets silently rewritten end to end.
    raw = path.read_text(encoding="utf-8", newline="")
    eol = "\r\n" if "\r\n" in raw else "\n"
    lines = raw.replace("\r\n", "\n").split("\n")
    needle = json.dumps(key, ensure_ascii=False) + ":"
    for i, line in enumerate(lines):
        if line.strip().startswith(needle):
            comma = "," if line.rstrip().endswith(",") else ""
            lines[i] = f"  {json.dumps(key, ensure_ascii=False)}: {json.dumps(value, ensure_ascii=False)}{comma}"
            path.write_text(eol.join(lines), encoding="utf-8", newline="")
            return True
    return False


def run_stamp(locale: str, namespace: str, keys: list[str], reviewer: str,
              author: str | None, dry_run: bool) -> None:
    """One stamp-provenance.py call per (namespace, verdict) group."""
    if not keys:
        return
    cmd = [sys.executable, str(STAMP), "--namespace", namespace, "--locale", locale,
           "--reviewer", reviewer]
    if author:
        cmd += ["--author", author]
    cmd += ["--keys", *keys]
    shown = " ".join(cmd[1:3] + cmd[3:9]) + f" … ({len(keys)} keys)"
    if dry_run:
        print(f"  would run: {shown}")
        return
    print(f"  {shown}")
    subprocess.run(cmd, check=True)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv_file", type=Path, help="a filled-in CSV from localization/review/")
    ap.add_argument("--reviewer", required=True,
                    help="the translator's credited name (must be a registered human)")
    ap.add_argument("--locale", help="override the locale inferred from the CSV's folder")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    authors = pio.load_authors(pio.DEFAULT_AUTHORS_FILE)
    if authors.get(args.reviewer) != "human":
        sys.exit(f"error: {args.reviewer!r} is not a registered human translator — add them to "
                 f"{pio.DEFAULT_AUTHORS_FILE.relative_to(pio.REPO_ROOT)} first "
                 "(a reviewer must be a person, and the registry is what keeps the "
                 "AI-vs-human counts honest).")

    locale = args.locale or locale_from_path(args.csv_file)
    with open(args.csv_file, encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))

    if rows and "book" in rows[0]:
        sys.exit("error: this is a narrative-book CSV. Books are edited directly in "
                 "narrative_localizations/; stamp them with:\n"
                 f"  python3 scripts/localization/stamp-narrative-provenance.py --locale {locale} "
                 f"--author '{args.reviewer}' --reviewer '{args.reviewer}' --files <book> …")

    ns_dirs = {ns.name: ns for ns in pio.namespaces()}
    confirmed: dict[str, list[str]] = {}
    revised: dict[str, list[str]] = {}
    unmatched, skipped = [], 0

    for row in rows:
        verdict = (row.get("verdict") or "").strip().lower()
        if verdict in ("", SKIP):
            skipped += 1
            continue
        namespace, key = split_namespace(row["key"])
        if verdict == OK:
            confirmed.setdefault(namespace, []).append(key)
        elif verdict == FIXED:
            new = row.get("new_translation") or ""
            if not new.strip():
                unmatched.append(f"{key}: verdict 'fixed' but new_translation is empty")
                continue
            lang_path = ns_dirs[namespace].lang_dir / f"{locale}.json"
            if args.dry_run:
                print(f"  would set {namespace}/{locale} {key} -> {new[:60]!r}")
            elif not set_lang_value(lang_path, key, new):
                unmatched.append(f"{key}: not found in {lang_path.name}")
                continue
            revised.setdefault(namespace, []).append(key)
        else:
            unmatched.append(f"{key}: unknown verdict {verdict!r} (use ok / fixed / skip)")

    print(f"{args.csv_file.name}: {sum(map(len, confirmed.values()))} confirmed, "
          f"{sum(map(len, revised.values()))} revised, {skipped} skipped/blank")

    for namespace in sorted(set(confirmed) | set(revised)):
        # Confirmed lines keep their original author; revised lines take the translator as both.
        run_stamp(locale, namespace, confirmed.get(namespace, []), args.reviewer, None, args.dry_run)
        run_stamp(locale, namespace, revised.get(namespace, []), args.reviewer,
                  args.reviewer, args.dry_run)

    if unmatched:
        print("\nrows that could not be applied:", file=sys.stderr)
        for problem in unmatched:
            print(f"  - {problem}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
