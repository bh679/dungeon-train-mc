#!/usr/bin/env python3
"""Push the shipped-template fingerprints to the relay.

``./gradlew bundledFingerprints`` writes ``build/bundled-fingerprints.json`` — the relay-side hash
of every template the mod ships (``tools/BundledFingerprints``). The relay keeps those in its
``bundled_fingerprints`` table so a builder row that is an unmodified copy of a stock template is
left out of the builder search, another player's view of a profile, and the "N builds" count.
This is the feeder's second half: ``release.yml`` runs it after every release, and it can be run by
hand after a template change or with a git-history backfill.

    python3 scripts/relay/push-bundled-fingerprints.py                 # build/bundled-fingerprints.json
    python3 scripts/relay/push-bundled-fingerprints.py --file other.json
    python3 scripts/relay/push-bundled-fingerprints.py --dry-run       # count, send nothing

Cumulative on the relay side (a hash already known is left alone), so re-running is free. Batched
to the relay's per-POST cap, which is why a history backfill of a few thousand goes through the same
script unchanged.

The admin URL is read from ``$DUNGEONTRAIN_RELAY_ADMIN_BASE`` / ``$DUNGEONTRAIN_RELAY_ADMIN_URL`` or
``~/.config/dungeontrain/relay-admin-url.txt`` (first non-comment line) — the places ``RelayTarget``
and the sibling scripts look — and is never printed; an HTTP error is reported without its URL.
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FILE = ROOT / "build" / "bundled-fingerprints.json"
ENVS = ("DUNGEONTRAIN_RELAY_ADMIN_BASE", "DUNGEONTRAIN_RELAY_ADMIN_URL")
HOME_FILE = Path.home() / ".config" / "dungeontrain" / "relay-admin-url.txt"
# Mirrors the relay's MAX_FINGERPRINTS_PER_POST; kept below it so a bump there never breaks this.
BATCH = 2000
REQUIRED = ("hash", "kind", "name")


def admin_base():
    for env in ENVS:
        value = os.environ.get(env, "").strip()
        if value:
            return value.rstrip("/")
    if HOME_FILE.is_file():
        for line in HOME_FILE.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                return line.rstrip("/")
    return ""


def load(path):
    """The fingerprint list, validated at the boundary: every entry carries what the relay keys on."""
    with path.open(encoding="utf-8") as fh:
        doc = json.load(fh)
    entries = doc.get("fingerprints") if isinstance(doc, dict) else None
    if not isinstance(entries, list):
        raise ValueError(f"{path}: expected {{\"fingerprints\": [...]}}")
    for i, entry in enumerate(entries):
        if not isinstance(entry, dict) or any(not isinstance(entry.get(k), str) or not entry[k] for k in REQUIRED):
            raise ValueError(f"{path}: entry {i} is missing one of {REQUIRED}")
    return tuple(entries)


def post(base, batch):
    body = json.dumps({"fingerprints": list(batch)}).encode("utf-8")
    req = urllib.request.Request(
        f"{base}/carriages/bundled-fingerprints", data=body, method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--file", type=Path, default=DEFAULT_FILE, help="fingerprint JSON (default build/bundled-fingerprints.json)")
    ap.add_argument("--dry-run", action="store_true", help="validate and count, send nothing")
    args = ap.parse_args()

    try:
        entries = load(args.file)
    except (OSError, ValueError, json.JSONDecodeError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    if args.dry_run:
        print(f"{len(entries)} fingerprint(s) in {args.file} — dry run, nothing sent.")
        return 0

    base = admin_base()
    if not base:
        print(f"error: no relay admin URL — set {ENVS[0]} or {HOME_FILE}", file=sys.stderr)
        return 2

    added = skipped = total = 0
    for start in range(0, len(entries), BATCH):
        batch = entries[start:start + BATCH]
        try:
            r = post(base, batch)
        except urllib.error.HTTPError as e:
            # urllib puts the URL (and so the admin capability) in str(e); report the status only.
            print(f"error: relay answered HTTP {e.code} on batch starting at {start}", file=sys.stderr)
            return 1
        except (urllib.error.URLError, OSError) as e:
            print(f"error: could not reach the relay: {getattr(e, 'reason', e)}", file=sys.stderr)
            return 1
        added += int(r.get("added", 0))
        skipped += int(r.get("skipped", 0))
        total = int(r.get("total", total))
    print(f"Pushed {len(entries)} fingerprint(s): {added} new, {skipped} skipped, relay now holds {total}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
