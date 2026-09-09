#!/usr/bin/env python3
"""Apply the credited-name changes translators made at the relay to the repo's records.

A translator can rename themself from the in-game Credits page (``POST /translations/rename``).
The relay rewrites their rows on the spot, so every client is served the new name at once — but
three things in this repo still carry the old one: ``localization/authors.json``, the provenance
sidecars (``localization/provenance/**`` and ``localization/narrative_provenance/``) and the
generated credits built from them. This script reads the relay's rename log
(``GET /<ADMIN_CAP>/translations/renames``) and applies each entry, in order, to the registry
and the sidecars; ``stamp-provenance.py --sync`` then regenerates the shipped credit files.

Run BEFORE ``import-approved-translations.py`` in the import workflow: the relay's approved rows
already carry the new name, and importing them against a registry that still holds the old one
would register the new name as a second, unknown translator.

Two things it will not do, and names in its report instead:

* **Rename a name the repo never credited.** Nothing to apply — the translator's work has not
  landed here yet, and when it does it will arrive under the new name.
* **Rename onto a name already in ``authors.json``.** The relay refuses a rename onto a name
  another uuid submits under, but it cannot see translators who delivered a zip rather than
  using the editor. Merging two people's credit is not a call a script gets to make.

Usage::

  python3 scripts/localization/apply-translator-renames.py [--dry-run] [--report-out FILE]
  python3 scripts/localization/apply-translator-renames.py --from-file renames.json
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import provenance_io as pio  # noqa: E402

STAMP = HERE / "stamp-provenance.py"
BASE_ENV = "DUNGEONTRAIN_RELAY_ADMIN_BASE"
REQUEST_TIMEOUT = 30


# ---- fetching ----------------------------------------------------------------

def redact(text, base: str) -> str:
    """``text`` with the admin base URL replaced — urllib puts the failing URL in its errors."""
    return str(text).replace(base, "<relay>") if base else str(text)


def renames_of(payload) -> list[dict]:
    """The rename rows out of either the endpoint's envelope or a bare list of rows."""
    if isinstance(payload, dict):
        payload = payload.get("renames", [])
    if not isinstance(payload, list):
        raise ValueError("expected a JSON array of renames, or an object with a 'renames' array")
    return [row for row in payload if isinstance(row, dict)]


def fetch(base: str, cap: str) -> list[dict]:
    params = {"cap": cap}
    url = f"{base.rstrip('/')}/translations/renames?{urllib.parse.urlencode(params)}"
    try:
        with urllib.request.urlopen(url, timeout=REQUEST_TIMEOUT) as resp:
            return renames_of(json.loads(resp.read().decode("utf-8")))
    except (urllib.error.URLError, TimeoutError, ValueError, json.JSONDecodeError) as exc:
        sys.exit(f"error: could not read the rename log — {redact(exc, base)}")


def load_renames(args) -> list[dict]:
    if args.from_file:
        with open(args.from_file, encoding="utf-8") as f:
            return renames_of(json.load(f))
    base = args.relay_base or os.environ.get(BASE_ENV, "")
    if not base:
        sys.exit(f"error: set {BASE_ENV} to the relay's admin base URL, or pass --from-file")
    return fetch(base, args.cap)


def valid(row: dict) -> bool:
    src, dst = row.get("from"), row.get("to")
    return (isinstance(src, str) and isinstance(dst, str)
            and bool(src.strip()) and bool(dst.strip()) and src.strip() != dst.strip())


# ---- applying ----------------------------------------------------------------

def sidecar_paths(prov_dir: Path, narrative_prov_dir: Path | None) -> list[Path]:
    """Every provenance sidecar: dungeontrain's flat files, the sibling subdirs, the books."""
    paths = sorted(p for p in prov_dir.rglob("*.json") if p.is_file()) if prov_dir.is_dir() else []
    if narrative_prov_dir and narrative_prov_dir.is_dir():
        paths += sorted(p for p in narrative_prov_dir.glob("*.json") if p.is_file())
    return paths


def rename_in_sidecar(path: Path, src: str, dst: str, dry_run: bool) -> int:
    """Rewrite ``author``/``reviewer`` entries equal to ``src``; returns how many changed."""
    prov = pio.load_provenance(path)
    changed = 0
    out: dict = {}
    for key, entry in prov.items():
        if isinstance(entry, dict):
            new_entry = dict(entry)
            for field in pio.ENTRY_FIELDS:
                if new_entry.get(field) == src:
                    new_entry[field] = dst
                    changed += 1
            out[key] = new_entry
        else:
            out[key] = entry
    if changed and not dry_run:
        pio.write_provenance(path, out)
    return changed


def rename_author(raw: dict, src: str, dst: str) -> dict:
    """``raw`` with the ``src`` key renamed to ``dst`` in place, entry and order kept."""
    return {(dst if name == src else name): value for name, value in raw.items()}


def write_authors(path: Path, raw: dict) -> None:
    """The registry's layout is exactly json.dumps(indent=2) — see the round trip in the tests."""
    path.write_text(json.dumps(raw, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    pio.load_authors(path)  # a malformed write must fail here, not in CI


def apply(renames: list[dict], authors_file: Path, prov_dir: Path,
          narrative_prov_dir: Path | None, dry_run: bool) -> tuple[list[dict], list[dict]]:
    """Apply in order. Returns ``(applied, skipped)``, each a list of ``{from, to[, reason]}``."""
    with open(authors_file, encoding="utf-8") as f:
        raw = json.load(f)
    if not isinstance(raw, dict):
        sys.exit(f"error: {authors_file}: expected a JSON object")
    kinds = pio.load_authors(authors_file)
    applied: list[dict] = []
    skipped: list[dict] = []
    paths = sidecar_paths(prov_dir, narrative_prov_dir)
    for row in renames:
        src, dst = row["from"].strip(), row["to"].strip()
        entry = {"from": src, "to": dst}
        if src not in raw:
            skipped.append({**entry, "reason": "never credited in the repo — nothing to rename"})
            continue
        if dst in raw:
            skipped.append({**entry, "reason": "the new name is already registered in authors.json "
                                             "— merging two people's credit needs a human"})
            continue
        if kinds.get(src) == "ai":
            skipped.append({**entry, "reason": "registered as AI — a machine is not a translator"})
            continue
        touched = sum(rename_in_sidecar(p, src, dst, dry_run) for p in paths)
        raw = rename_author(raw, src, dst)
        kinds = {(dst if n == src else n): k for n, k in kinds.items()}
        applied.append({**entry, "entries": touched})
    if applied and not dry_run:
        write_authors(authors_file, raw)
    return applied, skipped


def regenerate(args) -> None:
    """Rebuild the shipped credit files from the renamed sidecars — stamp-provenance.py owns that."""
    cmd = [sys.executable, str(STAMP), "--authors-file", str(args.authors_file), "--sync"]
    if args.narrative_provenance_dir:
        cmd += ["--narrative-provenance-dir", str(args.narrative_provenance_dir)]
    print("regenerating credits:", " ".join(cmd[1:]))
    subprocess.run(cmd, check=True)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--from-file", type=Path,
                        help="read the renames from a saved JSON payload instead of the relay")
    parser.add_argument("--relay-base", help=f"admin base URL (default: ${BASE_ENV})")
    parser.add_argument("--cap", default="live", help="relay cap label (default: live)")
    parser.add_argument("--authors-file", type=Path, default=pio.DEFAULT_AUTHORS_FILE)
    parser.add_argument("--provenance-dir", type=Path, default=pio.DEFAULT_PROVENANCE_DIR,
                        help="root of the lang sidecars (sibling subdirs included)")
    parser.add_argument("--narrative-provenance-dir", type=Path,
                        default=pio.DEFAULT_NARRATIVE_PROVENANCE_DIR)
    parser.add_argument("--report-out", type=Path,
                        help="write {applied, skipped} to this JSON file for the PR body")
    parser.add_argument("--skip-stamp", action="store_true",
                        help="do not regenerate the shipped credit files afterwards (tests)")
    parser.add_argument("--dry-run", action="store_true",
                        help="report what would change, writing nothing")
    args = parser.parse_args(argv)

    rows = [r for r in load_renames(args) if valid(r)]
    if not rows:
        print("no renames to apply")
        if args.report_out and not args.dry_run:
            args.report_out.write_text('{"applied": [], "skipped": []}\n', encoding="utf-8")
        return 0

    applied, skipped = apply(rows, args.authors_file, args.provenance_dir,
                             args.narrative_provenance_dir, args.dry_run)
    verb = "would rename" if args.dry_run else "renamed"
    for a in applied:
        print(f"  {verb} {a['from']!r} -> {a['to']!r} ({a['entries']} provenance entr"
              f"{'y' if a['entries'] == 1 else 'ies'})")
    for s in skipped:
        print(f"  skipped {s['from']!r} -> {s['to']!r}: {s['reason']}")
    print(f"{len(rows)} rename(s): {len(applied)} applied, {len(skipped)} skipped")

    if args.report_out and not args.dry_run:
        args.report_out.write_text(
            json.dumps({"applied": applied, "skipped": skipped}, ensure_ascii=False, indent=2)
            + "\n", encoding="utf-8")
    if applied and not args.dry_run and not args.skip_stamp:
        regenerate(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
