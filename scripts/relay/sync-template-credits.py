#!/usr/bin/env python3
"""Push every bundled template builder credit to the relay.

The credit itself lives in each kind's ``weights.json`` under ``src/main/resources/data/dungeontrain``
(``"builder": {"uuid": …, "name": …}`` — see ``TemplateMeta.builder()``) and ships in the jar. The
relay holds a mirror of it for one reader, the **Most Templates Built** leaderboard, and the mod
posts each credit there the moment it is set in the editor (``TemplateCreditClient``). That post
needs the relay admin URL, which only the developer's machine has, and it can be missed — no admin
URL in a fresh worktree, the relay down, a credit typed into weights.json by hand. This script is
the catch-up: it walks the bundled files and upserts every credit it finds, so the relay matches
what the jar says. Idempotent — the relay's row is keyed by (cap, kind, id) and an unchanged credit
is a no-op.

    python3 scripts/relay/sync-template-credits.py            # push to cap=live
    python3 scripts/relay/sync-template-credits.py --dry-run  # list what would be sent
    python3 scripts/relay/sync-template-credits.py --cap dev

The admin URL is read from ``$DUNGEONTRAIN_RELAY_ADMIN_URL`` or ``~/.config/dungeontrain/
relay-admin-url.txt`` (first non-comment line) — the same two places ``RelayTarget`` looks — and is
never printed.
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DATA = REPO / "src" / "main" / "resources" / "data" / "dungeontrain"

# Bundled weights file -> the kind the relay files the credit under. Matches TrackKind ids and
# the two carriage stores (see TemplateCreditClient / EditorBuilderCommands).
FILES = {
    "templates/weights.json": "carriage",
    "contents/weights.json": "contents",
    "tracks/weights.json": "tile",
    "tunnels/section/weights.json": "tunnel_section",
    "tunnels/portal/weights.json": "tunnel_portal",
    "pillars/top/weights.json": "pillar_top",
    "pillars/middle/weights.json": "pillar_middle",
    "pillars/bottom/weights.json": "pillar_bottom",
    "pillars/adjunct_stairs/weights.json": "adjunct_stairs",
    "pillars/adjunct_stairs_entrance/weights.json": "adjunct_stairs_entrance",
    "portals/room/weights.json": "portal_room",
}

ENV = "DUNGEONTRAIN_RELAY_ADMIN_URL"
HOME_FILE = Path.home() / ".config" / "dungeontrain" / "relay-admin-url.txt"


def admin_base():
    env = os.environ.get(ENV, "").strip()
    if env:
        return env.rstrip("/")
    if HOME_FILE.is_file():
        for line in HOME_FILE.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                return line.rstrip("/")
    return ""


def credits():
    """Yield (kind, id, uuid, name) for every bundled entry that credits somebody."""
    for rel, kind in FILES.items():
        path = DATA / rel
        if not path.is_file():
            continue
        with path.open(encoding="utf-8") as fh:
            entries = json.load(fh)
        if not isinstance(entries, dict):
            continue
        for tid, entry in entries.items():
            if not isinstance(entry, dict):
                continue  # a bare weight credits nobody
            builder = entry.get("builder")
            if not isinstance(builder, dict):
                continue
            uuid = str(builder.get("uuid") or "").replace("-", "").strip().lower()
            name = str(builder.get("name") or "").strip()
            if not uuid and not name:
                continue
            yield kind, tid.lower(), uuid, name


def post(base, cap, kind, tid, uuid, name):
    body = json.dumps({"kind": kind, "id": tid, "uuid": uuid or None, "name": name}).encode("utf-8")
    req = urllib.request.Request(
        f"{base}/templates/credit?cap={cap}", data=body, method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return resp.status


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--cap", default="live", choices=["live", "dev"], help="relay pool (default live)")
    ap.add_argument("--dry-run", action="store_true", help="print what would be sent, send nothing")
    args = ap.parse_args()

    rows = list(credits())
    if not rows:
        print("No bundled template credits to sync.")
        return 0
    for kind, tid, uuid, name in rows:
        print(f"  {kind}:{tid} -> {name or '(no name)'}{' ' + uuid if uuid else ' (no uuid)'}")
    if args.dry_run:
        print(f"{len(rows)} credit(s) — dry run, nothing sent.")
        return 0

    base = admin_base()
    if not base:
        print(f"error: no relay admin URL — set {ENV} or {HOME_FILE}", file=sys.stderr)
        return 2

    sent = failed = 0
    for kind, tid, uuid, name in rows:
        try:
            post(base, args.cap, kind, tid, uuid, name)
            sent += 1
        except urllib.error.HTTPError as e:  # the URL is a secret: report the status, not the request
            failed += 1
            print(f"  ✗ {kind}:{tid} — HTTP {e.code}", file=sys.stderr)
        except Exception as e:  # noqa: BLE001 — one bad row must not stop the rest
            failed += 1
            print(f"  ✗ {kind}:{tid} — {type(e).__name__}", file=sys.stderr)
    print(f"{sent} credit(s) synced to cap={args.cap}, {failed} failed.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
