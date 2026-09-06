#!/usr/bin/env python3
"""Pull the operator's relay-authored English back into ``en_us.json``.

An advancement's title, description and pre-earn hint are lang keys in this repo, but they can also
be rewritten from the explorer's #/advancements page: the relay serves the new text out of the
source pool and every English client installs it over the jar at its next launch (dp-relay
``translations.js`` ``authorSource`` → ``/translations/pool?locale=en_us`` → the mod's
``ApprovedTranslationsFetcher``). That is what makes a typo fix reach players who are already on a
shipped build.

What it does NOT do is change this repo. Without this script the jar keeps shipping the old wording
for good, every new locale is translated from text nobody is reading any more, and the relay row is
the only copy of the current English — so a database restore or a cleared override silently reverts
the game's text. This closes that gap the same way ``import-approved-translations.py`` closes it for
player translations: as a PR, because it is a change to shipped text and deserves a human read.

Deliberately a separate script from that one, not a flag on it. That importer's whole substance is
PROVENANCE — which human translated which line, credited on the Credits screen and stamped into the
sidecars. An English edit has no translator to credit: it is Dungeon Train writing its own source
text. Folding it in would mean threading "except when the locale is the source" through every one of
those steps.

It will not:

* **Write a key outside the allowlist.** Advancement ``.title`` / ``.description`` / ``.hint``, the
  same set the relay route accepts. Two lists, deliberately, in two repos that deploy separately —
  the relay's is what stops the text being written at all, this one is what stops a row written by
  some future widening of that route landing in the jar unreviewed.
* **Overwrite an edit made in git.** Every relay row records the jar text it replaced. When this
  repo's current value is neither that text nor the new one, somebody has edited ``en_us.json``
  since, and their change wins: the row is deferred and named, not clobbered.
* **Invent an advancement.** A missing ``.hint`` line is written (six advancements deliberately ship
  without one, and writing the first is an ordinary edit), but only next to a title key that is
  already there.

Usage::

    DUNGEONTRAIN_RELAY_ADMIN_BASE='https://<host>/api/dp-relay/<admin cap>' \\
        python3 scripts/localization/import-english-edits.py [--dry-run]
    python3 scripts/localization/import-english-edits.py --from-file payload.json --dry-run
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

import provenance_io as pio

#: Holds the admin capability in its path, so it is a secret: env only, and scrubbed from every
#: message this script prints. Shared with import-approved-translations.py — one relay, one secret.
BASE_ENV = "DUNGEONTRAIN_RELAY_ADMIN_BASE"

#: The keys this script may write. Mirrors dp-relay translations.js SOURCE_KEY_RE.
SOURCE_KEY = re.compile(
    r"^advancements\.dungeontrain\.[a-z0-9_]+\.[a-z0-9_]+\.(title|description|hint)$")

REQUEST_TIMEOUT = 30


def redact(text, base: str) -> str:
    """``text`` with the admin base URL replaced — urllib puts the failing URL in its errors."""
    return str(text).replace(base, "<relay>") if base else str(text)


def fetch_units(base: str, cap: str) -> dict:
    """``{key: {value, shipped, ts, by}}`` from the relay's source-override listing."""
    url = f"{base.rstrip('/')}/translations/source?{urllib.parse.urlencode({'cap': cap})}"
    try:
        with urllib.request.urlopen(url, timeout=REQUEST_TIMEOUT) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, ValueError, json.JSONDecodeError) as exc:
        sys.exit(f"error: could not read the English overrides — {redact(exc, base)}")
    return units_of(payload)


def units_of(payload) -> dict:
    """The override map out of the endpoint's envelope, or a bare map of the same shape."""
    if isinstance(payload, dict) and isinstance(payload.get("units"), dict):
        payload = payload["units"]
    if not isinstance(payload, dict):
        raise ValueError("expected an object of key -> {value, shipped}, or an envelope with one")
    return {k: v for k, v in payload.items() if isinstance(v, dict)}


def sibling_anchor(key: str) -> list[str]:
    """The keys a new ``.hint`` line may be written after, best first."""
    stem = key.rsplit(".", 1)[0]
    return [f"{stem}.title", f"{stem}.description"]


def insert_after(path: Path, anchor: str, key: str, value: str) -> bool:
    """Write ``key`` on the line after ``anchor``'s, preserving formatting and line endings.

    The write half of :func:`provenance_io.set_lang_value`, for the one case that function refuses:
    a key the file does not carry yet. Same text-level approach and for the same reason — the lang
    files use blank-line grouping a ``json.dump`` round trip would flatten.
    """
    raw = pio.read_verbatim(path)
    eol = "\r\n" if "\r\n" in raw else "\n"
    lines = raw.replace("\r\n", "\n").split("\n")
    needle = json.dumps(anchor, ensure_ascii=False) + ":"
    for i, line in enumerate(lines):
        if not line.strip().startswith(needle):
            continue
        # The anchor is mid-object (a lang file's last entry is followed by `}`), but say so out of
        # the file rather than assuming: a line with no trailing comma is the last pair, and the new
        # line has to take the comma over.
        if line.rstrip().endswith(","):
            lines.insert(i + 1, f"  {json.dumps(key, ensure_ascii=False)}: "
                                f"{json.dumps(value, ensure_ascii=False)},")
        else:
            lines[i] = line.rstrip() + ","
            lines.insert(i + 1, f"  {json.dumps(key, ensure_ascii=False)}: "
                                f"{json.dumps(value, ensure_ascii=False)}")
        pio.write_verbatim(path, eol.join(lines))
        return True
    return False


class Result:
    """What one run did, so the caller (and the PR body) can say so."""

    def __init__(self):
        self.written: list[str] = []
        self.created: list[str] = []
        self.unchanged: list[str] = []
        self.deferred: list[str] = []

    @property
    def changed(self) -> int:
        return len(self.written) + len(self.created)


def apply_units(units: dict, lang_path: Path, dry_run: bool = False) -> Result:
    """Write every applicable override into ``lang_path``. Never raises on a bad row."""
    out = Result()
    lang = json.loads(lang_path.read_text(encoding="utf-8"))

    for key in sorted(units):
        unit = units[key] or {}
        value = unit.get("value")
        if not isinstance(value, str) or not value.strip():
            out.deferred.append(f"{key}: the relay row carries no text")
            continue
        if not SOURCE_KEY.match(key):
            out.deferred.append(f"{key}: not an advancement title, description or hint")
            continue

        current = lang.get(key)
        if current == value:
            out.unchanged.append(key)          # already imported by an earlier run
            continue

        if current is None:
            if not key.endswith(".hint"):
                out.deferred.append(f"{key}: en_us.json has no line for this key — a title or "
                                    "description belongs to an advancement this repo does not have")
                continue
            anchor = next((a for a in sibling_anchor(key) if a in lang), None)
            if anchor is None:
                out.deferred.append(f"{key}: no advancement here to hang a hint on")
                continue
            if dry_run or insert_after(lang_path, anchor, key, value):
                out.created.append(key)
            else:
                out.deferred.append(f"{key}: could not place the new line after {anchor}")
            continue

        shipped = unit.get("shipped")
        if isinstance(shipped, str) and shipped and current != shipped:
            out.deferred.append(f"{key}: en_us.json has changed since this was written "
                                "(the repo's version wins) — re-edit it on the relay to import it")
            continue

        if dry_run or pio.set_lang_value(lang_path, key, value):
            out.written.append(key)
        else:
            out.deferred.append(f"{key}: could not rewrite the line in en_us.json")

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
    parser.add_argument("--lang-file", type=Path, default=pio.DEFAULT_LANG_DIR / "en_us.json",
                        help="the file to write (default: the mod's en_us.json)")
    parser.add_argument("--deferred-out", type=Path,
                        help="write the overrides that could not be imported to this JSON file, so "
                             "the PR body can name them")
    parser.add_argument("--dry-run", action="store_true",
                        help="report what would be imported, writing nothing")
    args = parser.parse_args(argv)

    if not args.lang_file.exists():
        sys.exit(f"error: no lang file at {args.lang_file}")

    result = apply_units(load_units(args), args.lang_file, dry_run=args.dry_run)

    for key in result.written:
        print(f"  {'would rewrite' if args.dry_run else 'rewrote'} {key}")
    for key in result.created:
        print(f"  {'would add' if args.dry_run else 'added'} {key}")
    for line in result.deferred:
        print(f"  DEFERRED {line}")
    print(f"{result.changed} line(s) {'would change' if args.dry_run else 'changed'}, "
          f"{len(result.unchanged)} already current, {len(result.deferred)} deferred")

    if args.deferred_out and not args.dry_run:
        args.deferred_out.write_text(json.dumps(result.deferred, ensure_ascii=False, indent=2) + "\n",
                                     encoding="utf-8")
    # Deferrals are the normal, expected state of this job — a row waiting on a repo edit is not a
    # failure, and exiting non-zero for one would train everybody to ignore a red run.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
