#!/usr/bin/env python3
"""Pull the operator's relay-authored requirement values back into the advancement datapack.

A milestone's number — ``criteria.<c>.conditions.threshold*`` in
``data/dungeontrain/advancement/dungeon_train/<name>.json`` — can be rewritten from the explorer's
#/advancements page: the relay serves the override (dp-relay ``advancement-requirements.js`` →
``GET /<CAP>/advancement-requirements``), every server and client applies it at its next datapack
load (``AdvancementRequirementOverrides`` + ``ServerAdvancementManagerRequirementsMixin``), and the
description re-reads because its number is a translation argument fed from that value.

What that does NOT do is change this repo. Without this script the jar keeps shipping the old
number, and the relay row is the only copy of the balance players are actually playing — so a
database restore or a cleared override silently reverts it. This closes that gap the same way
``scripts/localization/import-english-edits.py`` closes it for the text: as a PR.

It will not:

* **Write outside the allowlist.** Ids under ``dungeontrain:dungeon_train/`` and the four numeric
  fields the mod knows — the relay refuses anything else at write time; this is the second list,
  in the repo that deploys separately.
* **Overwrite an edit made in git.** Every relay row records the jar value it replaced. When the
  JSON's current value is neither that nor the new one, somebody has edited the datapack since,
  and their change wins: the row is deferred and named, not clobbered.
* **Invent a field.** The value is written only where the JSON already carries that field on a
  criterion; an advancement without one is deferred.

The rewrite is text-level (the one number on its line), so the hand-formatted JSON keeps its
shape and the diff is one line per advancement.

Usage::

    DUNGEONTRAIN_RELAY_ADMIN_BASE='https://<host>/api/dp-relay/<admin cap>' \\
        python3 scripts/advancements/import-requirement-edits.py [--dry-run]
    python3 scripts/advancements/import-requirement-edits.py --from-file payload.json --dry-run
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

#: Shared with the localization importers — one relay, one secret; scrubbed from every message.
BASE_ENV = "DUNGEONTRAIN_RELAY_ADMIN_BASE"

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ADV_DIR = REPO_ROOT / "src/main/resources/data/dungeontrain/advancement/dungeon_train"

#: Mirrors dp-relay advancement-requirements.js ID_RE / FIELDS and the mod's RequirementField.
ADV_ID = re.compile(r"^dungeontrain:dungeon_train/([a-z0-9_]{1,64})$")
FIELDS = ("threshold", "thresholdReads", "thresholdMeters", "thresholdTicks")
MAX_VALUE = 1_000_000_000

REQUEST_TIMEOUT = 30


def redact(text, base: str) -> str:
    return str(text).replace(base, "<relay>") if base else str(text)


def fetch_units(base: str, cap: str) -> dict:
    url = f"{base.rstrip('/')}/advancement-requirements?{urllib.parse.urlencode({'cap': cap})}"
    try:
        with urllib.request.urlopen(url, timeout=REQUEST_TIMEOUT) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, ValueError, json.JSONDecodeError) as exc:
        sys.exit(f"error: could not read the requirement overrides — {redact(exc, base)}")
    return units_of(payload)


def units_of(payload) -> dict:
    """The override map out of the endpoint's envelope, or a bare map of the same shape."""
    if isinstance(payload, dict) and isinstance(payload.get("units"), dict):
        payload = payload["units"]
    if not isinstance(payload, dict):
        raise ValueError("expected an object of id -> {field, value, shipped}, or an envelope with one")
    return {k: v for k, v in payload.items() if isinstance(v, dict)}


def locate(adv: dict, field: str) -> tuple[str, int] | None:
    """``(criterion, current value)`` for the first criterion carrying ``field``, else None."""
    for name, crit in (adv.get("criteria") or {}).items():
        cond = crit.get("conditions") if isinstance(crit, dict) else None
        if isinstance(cond, dict) and isinstance(cond.get(field), int):
            return name, cond[field]
    return None


def set_value(path: Path, field: str, old: int, new: int) -> bool:
    """Rewrite the one ``"<field>": <old>`` on its line, preserving everything else. False if not exactly one match."""
    raw = path.read_bytes().decode("utf-8")
    pattern = re.compile(r'("' + re.escape(field) + r'"\s*:\s*)' + re.escape(str(old)) + r'(?!\d)')
    if len(pattern.findall(raw)) != 1:
        return False
    path.write_bytes(pattern.sub(lambda m: m.group(1) + str(new), raw, count=1).encode("utf-8"))
    return True


class Result:
    def __init__(self):
        self.written: list[str] = []
        self.unchanged: list[str] = []
        self.deferred: list[str] = []

    @property
    def changed(self) -> int:
        return len(self.written)


def apply_units(units: dict, adv_dir: Path, dry_run: bool = False) -> Result:
    """Write every applicable override into its advancement JSON. Never raises on a bad row."""
    out = Result()
    for adv_id in sorted(units):
        unit = units[adv_id] or {}
        m = ADV_ID.match(adv_id)
        if not m:
            out.deferred.append(f"{adv_id}: not a dungeon_train advancement")
            continue
        field = unit.get("field")
        value = unit.get("value")
        if field not in FIELDS:
            out.deferred.append(f"{adv_id}: field {field!r} is not a requirement field")
            continue
        if not isinstance(value, int) or isinstance(value, bool) or not 1 <= value <= MAX_VALUE:
            out.deferred.append(f"{adv_id}: value {value!r} is not a positive integer")
            continue
        path = adv_dir / f"{m.group(1)}.json"
        if not path.is_file():
            out.deferred.append(f"{adv_id}: this repo has no such advancement")
            continue
        try:
            adv = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            out.deferred.append(f"{adv_id}: could not read {path.name} — {exc}")
            continue
        found = locate(adv, field)
        if found is None:
            out.deferred.append(f"{adv_id}: {path.name} carries no {field} on any criterion")
            continue
        _criterion, current = found
        if current == value:
            out.unchanged.append(adv_id)
            continue
        shipped = unit.get("shipped")
        if isinstance(shipped, int) and not isinstance(shipped, bool) and current != shipped:
            out.deferred.append(f"{adv_id}: {path.name} has changed since this was written "
                                f"(repo says {current}, the edit was against {shipped}) — the repo's "
                                "version wins; re-edit it on the relay to import it")
            continue
        if dry_run or set_value(path, field, current, value):
            out.written.append(f"{adv_id}: {field} {current} -> {value}")
        else:
            out.deferred.append(f"{adv_id}: could not rewrite the {field} line in {path.name}")
    return out


def load_units(args) -> dict:
    if args.from_file:
        try:
            return units_of(json.loads(Path(args.from_file).read_text(encoding="utf-8")))
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            sys.exit(f"error: could not read {args.from_file} — {exc}")
    base = args.relay_base or os.environ.get(BASE_ENV, "")
    if not base:
        sys.exit(f"error: set ${BASE_ENV} (or pass --relay-base) to the relay's admin base URL")
    return fetch_units(base, args.cap)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--from-file", type=Path,
                        help="read the overrides from a saved JSON payload instead of the relay")
    parser.add_argument("--relay-base", help=f"admin base URL (default: ${BASE_ENV})")
    parser.add_argument("--cap", default="live", help="relay cap label (default: live)")
    parser.add_argument("--adv-dir", type=Path, default=DEFAULT_ADV_DIR,
                        help="the dungeon_train advancement directory to write")
    parser.add_argument("--deferred-out", type=Path,
                        help="write the overrides that could not be imported to this JSON file")
    parser.add_argument("--dry-run", action="store_true",
                        help="report what would be imported, writing nothing")
    args = parser.parse_args(argv)

    if not args.adv_dir.is_dir():
        sys.exit(f"error: no advancement directory at {args.adv_dir}")

    result = apply_units(load_units(args), args.adv_dir, dry_run=args.dry_run)
    for line in result.written:
        print(f"  {'would rewrite' if args.dry_run else 'rewrote'} {line}")
    for line in result.deferred:
        print(f"  DEFERRED {line}")
    print(f"{result.changed} value(s) {'would change' if args.dry_run else 'changed'}, "
          f"{len(result.unchanged)} already current, {len(result.deferred)} deferred")
    if args.deferred_out and not args.dry_run:
        args.deferred_out.write_text(json.dumps(result.deferred, ensure_ascii=False, indent=2) + "\n",
                                     encoding="utf-8")
    # Deferrals are the normal state of this job, not a failure — see import-english-edits.py.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
