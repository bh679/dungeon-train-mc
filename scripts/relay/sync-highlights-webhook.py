#!/usr/bin/env python3
"""Hand the #highlights webhook to the relay, so its number milestones post where the major updates go.

The webhook lives only in this repo's ``DISCORD_HIGHLIGHTS_WEBHOOK_URL`` secret (``notify-highlights.py``
posts major updates through it), and GitHub never shows a secret again. The relay's milestone poster
(dp-relay ``socials-milestones.js``) needs the same one, so the ``sync-highlights-webhook`` workflow and
every real release run this to store it there (``POST <admin>/socials/milestones/discord-webhook``).
Re-running is free; a rotated webhook reaches the relay on the next run.

    DISCORD_HIGHLIGHTS_WEBHOOK_URL=... python3 scripts/relay/sync-highlights-webhook.py
    python3 scripts/relay/sync-highlights-webhook.py --clear     # forget the relay's copy

Neither the webhook nor the admin URL is ever printed. A missing secret or admin URL skips with a
``::warning::`` (exit 0), like the other relay steps, so a release never fails on it.
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
HOME_FILE = Path.home() / ".config" / "dungeontrain" / "relay-admin-url.txt"
WEBHOOK_ENV = "DISCORD_HIGHLIGHTS_WEBHOOK_URL"
# Mirrors the relay's WEBHOOK_RE: anything else would be refused there anyway.
WEBHOOK_RE = re.compile(r"^https://(discord\.com|discordapp\.com)/api/webhooks/\d+/[A-Za-z0-9_-]+$")


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


def post(base, url):
    body = json.dumps({"url": url}).encode("utf-8")
    req = urllib.request.Request(
        f"{base}/socials/milestones/discord-webhook", data=body, method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--clear", action="store_true", help="remove the relay's stored copy")
    args = ap.parse_args(argv)

    url = "" if args.clear else os.environ.get(WEBHOOK_ENV, "").strip()
    if not args.clear and not url:
        print(f"::warning::{WEBHOOK_ENV} not set — relay webhook not synced.")
        return 0
    if url and not WEBHOOK_RE.match(url):
        print(f"error: {WEBHOOK_ENV} is not a Discord webhook URL", file=sys.stderr)
        return 2
    base = admin_base()
    if not base:
        print(f"::warning::no relay admin URL ({ENVS[0]}) — relay webhook not synced.")
        return 0

    try:
        r = post(base, url)
    except urllib.error.HTTPError as e:
        # urllib puts the URL (and so the admin capability) in str(e); report the status only.
        print(f"error: relay answered HTTP {e.code}", file=sys.stderr)
        return 1
    except (urllib.error.URLError, OSError) as e:
        print(f"error: could not reach the relay: {getattr(e, 'reason', e)}", file=sys.stderr)
        return 1
    print("Relay #highlights webhook " + ("stored." if r.get("stored") else "cleared."))
    return 0


if __name__ == "__main__":
    sys.exit(main())
