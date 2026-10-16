#!/usr/bin/env python3
"""Tell the live chat that a new Dungeon Train version is out.

    python3 scripts/relay/announce-release-live-chat.py --tag v0.1310.0
    python3 scripts/relay/announce-release-live-chat.py --tag v0.1310.0 --dry-run
    python3 scripts/relay/announce-release-live-chat.py --tag v0.1310.0 --notes-file notes.md

Calls ``POST <admin base>/live/chat/announce`` (dp-relay ``live-chat.js#announceRelease``). The line
reaches the website's live chat panel, the Discord live room and Twitch (while someone is on air). It
never reaches the game: in-game players already get the mod's own update notice
(``client/version/LiveUpdateNotice``), and the relay keeps it out of the game's read so they see it once.

The line mirrors that in-game notice ("Version %s is out!") plus the release's title — the first
``# `` heading of the GitHub Release body — and a "What's new" link to the release on the update page.

The admin URL is read from ``$DUNGEONTRAIN_RELAY_ADMIN_BASE`` / ``$DUNGEONTRAIN_RELAY_ADMIN_URL`` or
``~/.dungeontrain-relay-admin`` (same contract as retire-leaderboard-era.py). The capability is part
of that URL and is never printed.

Exit codes: 0 sent (or dry run), 1 the relay refused or was unreachable, 2 bad input / no URL / no release.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

ENVS = ("DUNGEONTRAIN_RELAY_ADMIN_BASE", "DUNGEONTRAIN_RELAY_ADMIN_URL")
HOME_FILE = Path.home() / ".dungeontrain-relay-admin"
REPO = "bh679/dungeon-train-mc"
UPDATE_PAGE_URL = "https://brennan.games/dungeontrain/update/"
TAG_RE = re.compile(r"^v?(\d{1,6}\.\d{1,6}\.\d{1,6})$")
HEADING_RE = re.compile(r"^#\s+(.+?)\s*$", re.MULTILINE)
MD_LINK_RE = re.compile(r"\[([^\]]+)\]\([^)]*\)")
LINK_TEXT = "What's new"
MAX_CONTENT = 500  # the relay's live-chat line cap
MAX_TITLE = 160


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
    """``v0.1310.0`` → ``0.1310.0``; anything that is not major.minor.patch is refused."""
    m = TAG_RE.match(raw.strip())
    if not m:
        raise ValueError(f"not a Dungeon Train release tag: {raw!r} (expected e.g. v0.1310.0)")
    return m.group(1)


def release_title(notes):
    """The first top-level heading of the notes, as plain text (its update-page link removed), or ''."""
    m = HEADING_RE.search(notes or "")
    if not m:
        return ""
    title = MD_LINK_RE.sub(r"\1", m.group(1)).replace("**", "").replace("`", "").strip()
    return title if len(title) <= MAX_TITLE else title[: MAX_TITLE - 1].rstrip() + "…"


def build_announcement(version, notes):
    """The relay request body: the line's text and its "What's new" link."""
    title = release_title(notes)
    head = f"Version {version} is out!"
    tail = f" — {LINK_TEXT}"
    if title:
        room = MAX_CONTENT - len(head) - len(tail) - 1
        head = f"{head} {title[:room]}"
    return {
        "content": head + tail,
        "link": {"text": LINK_TEXT, "url": f"{UPDATE_PAGE_URL}#v{version}"},
    }


def fetch_notes(tag):
    """The GitHub Release body for ``tag`` through the gh CLI, or None when there is no such release."""
    try:
        out = subprocess.run(
            ["gh", "release", "view", tag, "--repo", REPO, "--json", "body", "--jq", ".body"],
            check=True, capture_output=True, text=True, timeout=60)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, FileNotFoundError):
        return None
    return out.stdout


def post(base, body):
    req = urllib.request.Request(
        f"{base}/live/chat/announce", data=json.dumps(body).encode("utf-8"), method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--tag", required=True, help="the release tag, e.g. v0.1310.0")
    ap.add_argument("--notes-file", type=Path, help="read the release notes from here instead of the GitHub Release")
    ap.add_argument("--dry-run", action="store_true", help="print the line, send nothing")
    args = ap.parse_args()

    try:
        version = normalise_version(args.tag)
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    if args.notes_file:
        notes = args.notes_file.read_text(encoding="utf-8")
    else:
        notes = fetch_notes(f"v{version}")
        if notes is None:
            # No release, no announcement — the same refusal notify-discord.sh makes.
            print(f"error: no GitHub Release found for v{version} — not announcing.", file=sys.stderr)
            return 2
    body = build_announcement(version, notes)
    if args.dry_run or os.environ.get("DRY_RUN") == "1":
        print(json.dumps(body, ensure_ascii=False, indent=2))
        return 0

    base = admin_base()
    if not base:
        print(f"error: no relay admin URL — set {ENVS[0]} or {HOME_FILE}", file=sys.stderr)
        return 2
    try:
        r = post(base, body)
    except urllib.error.HTTPError as e:
        # urllib puts the URL (and so the admin capability) in str(e); report the status only.
        detail = ""
        try:
            detail = f" ({json.loads(e.read().decode('utf-8')).get('error')})"
        except Exception:  # noqa: BLE001 — the body is a courtesy, the status is the fact
            pass
        print(f"error: relay answered HTTP {e.code}{detail}", file=sys.stderr)
        return 1
    except (urllib.error.URLError, OSError) as e:
        print(f"error: could not reach the relay: {getattr(e, 'reason', e)}", file=sys.stderr)
        return 1
    where = "the website and the Discord live room" if r.get("discord") else "the website (the Discord live room post failed)"
    print(f"announced v{version} in the live chat — {where}; Twitch gets it while someone is on air.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
