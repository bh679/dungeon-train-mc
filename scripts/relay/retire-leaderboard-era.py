#!/usr/bin/env python3
"""Retire the current one-life leaderboard era on the relay and open the next one.

    python3 scripts/relay/retire-leaderboard-era.py --min-version 0.1013.0 --label "After the balancing"
    python3 scripts/relay/retire-leaderboard-era.py --min-version 0.1013.0 --label "..." --dry-run

Calls ``POST <admin base>/leaderboard/era/retire`` (dp-relay ``leaderboard-eras.js``). The relay
keeps the retired era's board for good and keeps feeding it from jars still on that version; the
new era starts empty and holds only runs the new game produces. Idempotent by version — the relay
answers ``created: false`` when the era already exists — so a re-run release is safe. A 409
``not_newer`` means the version does not move past the current era's floor and nothing changed.

The admin URL is read from ``$DUNGEONTRAIN_RELAY_ADMIN_BASE`` / ``$DUNGEONTRAIN_RELAY_ADMIN_URL`` or
``~/.dungeontrain-relay-admin`` (same contract as push-bundled-fingerprints.py). The capability is
part of that URL and is never printed.

Exit codes: 0 sent (or dry run), 1 the relay refused or was unreachable, 2 bad input / no URL.
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ENVS = ("DUNGEONTRAIN_RELAY_ADMIN_BASE", "DUNGEONTRAIN_RELAY_ADMIN_URL")
HOME_FILE = Path.home() / ".dungeontrain-relay-admin"
VERSION_RE = re.compile(r"^\d+(\.\d+){0,3}$")
MAX_LABEL = 64


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


def normalise_version(raw):
    """``v0.1013.0`` → ``0.1013.0``; anything that is not a plain dotted version is refused."""
    v = raw.strip()
    if v.startswith(("v", "V")):
        v = v[1:]
    if not VERSION_RE.match(v):
        raise ValueError(f"not a Dungeon Train version: {raw!r} (expected e.g. 0.1013.0)")
    return v


def post(base, min_version, label):
    body = json.dumps({"minVersion": min_version, "label": label}).encode("utf-8")
    req = urllib.request.Request(
        f"{base}/leaderboard/era/retire", data=body, method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--min-version", required=True, help="lowest DT version of the NEW era (the release tag, with or without v)")
    ap.add_argument("--label", required=True, help=f"player-facing era name, up to {MAX_LABEL} chars")
    ap.add_argument("--dry-run", action="store_true", help="validate and print, send nothing")
    args = ap.parse_args()

    try:
        min_version = normalise_version(args.min_version)
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    label = " ".join(args.label.split())
    if not label or len(label) > MAX_LABEL:
        print(f"error: --label must be 1..{MAX_LABEL} characters", file=sys.stderr)
        return 2
    if args.dry_run:
        print(f"would retire the current era and open >= {min_version} \"{label}\" — dry run, nothing sent.")
        return 0

    base = admin_base()
    if not base:
        print(f"error: no relay admin URL — set {ENVS[0]} or {HOME_FILE}", file=sys.stderr)
        return 2
    try:
        r = post(base, min_version, label)
    except urllib.error.HTTPError as e:
        # urllib puts the URL (and so the admin capability) in str(e); report the status only.
        detail = ""
        try:
            doc = json.loads(e.read().decode("utf-8"))
            detail = f" ({doc.get('error')}" + (f", current era {doc['current'].get('id')} >= {doc['current'].get('minVersion')}" if doc.get("current") else "") + ")"
        except Exception:  # noqa: BLE001 — the body is a courtesy, the status is the fact
            pass
        print(f"error: relay answered HTTP {e.code}{detail}", file=sys.stderr)
        return 1
    except (urllib.error.URLError, OSError) as e:
        print(f"error: could not reach the relay: {getattr(e, 'reason', e)}", file=sys.stderr)
        return 1
    era = r.get("era") or {}
    if r.get("created"):
        print(f"retired the current leaderboard era; opened {era.get('id')} (>= {era.get('minVersion')}) \"{era.get('label')}\".")
    else:
        print(f"{era.get('id')} is already the current leaderboard era — nothing changed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
