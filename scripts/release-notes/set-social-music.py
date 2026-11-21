#!/usr/bin/env python3
"""Pin the song a release's update videos get on Facebook, Instagram and TikTok, or show what the relay has.

docs/guides/release.md: pick the song in preview-social-posts.py, then pin it here before dispatching the release,
so the relay posts that exact song instead of drawing one from the update's vibe (socials-music.js trackFor). The
pin is keyed by changelog id, so it can be set before the release ships.

Talks to the live relay's Socials admin route over SSH, the way scripts/deploy-relay.sh in dp-relay reaches the box:
the admin cap is read on the box and never leaves it. Nothing here prints it.

Usage:
  python3 scripts/release-notes/set-social-music.py --entry <id> --show
  python3 scripts/release-notes/set-social-music.py --entry <id> --track <track-id> [--vibe <vibe>]
  python3 scripts/release-notes/set-social-music.py --entry <id> --track ''      # unpin: the vibe picks again
  python3 scripts/release-notes/set-social-music.py --entry <id> --track <track-id> --start 49.7
      # where that track starts, in seconds. The start belongs to the track, so it holds for every update that
      # plays it; --start '' clears it back to TikTok's clip start or the measured highlight.

Env: DEPLOY_HOST (bitnami@brennan.games), DEPLOY_SSH_KEY (~/.ssh/claude-lightsail), DEPLOY_PORT (3005).
"""
import argparse
import json
import os
import shlex
import subprocess
import sys

HOST = os.environ.get("DEPLOY_HOST", "bitnami@brennan.games")
KEY = os.path.expanduser(os.environ.get("DEPLOY_SSH_KEY", "~/.ssh/claude-lightsail"))
PORT = os.environ.get("DEPLOY_PORT", "3005")
ENV_FILE = "/home/bitnami/server/dp-relay/.env"


def relay(method: str, path: str, body: dict | None = None) -> dict:
    """One admin call on the box. The cap is read there from the relay's .env and used there."""
    cap = (f"C=$(grep -E '^(ADMIN_CAP|LOGS_ADMIN_CAP)=' {ENV_FILE} | head -1 | cut -d= -f2- | tr -d '\"[:space:]')")
    data = f" -H 'Content-Type: application/json' -d {shlex.quote(json.dumps(body))}" if body is not None else ""
    remote = f"{cap}; curl -s -m 60 -X {method}{data} \"http://127.0.0.1:{PORT}/$C/{path}\""
    out = subprocess.run(["ssh", "-i", KEY, "-o", "ConnectTimeout=20", HOST, remote],
                         capture_output=True, text=True, timeout=120)
    if out.returncode != 0:
        raise RuntimeError(f"ssh to {HOST} failed: {out.stderr.strip() or out.returncode}")
    try:
        reply = json.loads(out.stdout)
    except json.JSONDecodeError:
        raise RuntimeError(f"the relay did not answer with JSON ({out.stdout[:120]!r}); is it up?")
    if reply.get("error"):
        raise RuntimeError(f"the relay refused: {reply['error']}")
    return reply


def show(entry: str, page: dict) -> None:
    music = (page.get("updates") or {}).get("music") or page.get("music") or {}
    on = music.get("on", {})
    pinned = (music.get("trackFor") or {}).get(entry)
    tracks = {t["id"]: t for v in music.get("vibes", []) for t in v.get("tracks", [])}
    titles = {i: f"{t['title']} by {t['composer']}" for i, t in tracks.items()}
    print(f"entry:  {entry}")
    print(f"music:  facebook/instagram {'on' if on.get('meta') else 'OFF'}, tiktok {'on' if on.get('tiktok') else 'OFF'}")
    print(f"vibe:   {(music.get('vibeFor') or {}).get(entry) or '(from its tags)'}")
    if "trackFor" not in music:
        print("song:   (this relay has no per-update song yet; deploy dp-relay 0.330.0 or later)")
    else:
        print(f"song:   {titles.get(pinned, pinned) if pinned else '(not pinned: the vibe picks)'}")
        if pinned in tracks and "start" in tracks[pinned]:
            print(f"start:  {tracks[pinned]['start']} s ({tracks[pinned].get('from', '?')})")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--entry", required=True, help="changelog entry id")
    parser.add_argument("--track", help="track id from the preview's song card; '' unpins")
    parser.add_argument("--vibe", help="also set the entry's vibe")
    parser.add_argument("--start", help="with --track: where that track starts, in seconds; '' clears it")
    parser.add_argument("--show", action="store_true", help="print what the relay has for the entry")
    args = parser.parse_args(argv)
    if args.track is None and args.vibe is None and not args.show:
        parser.error("give --track, --vibe or --show")
    if args.start is not None and not args.track:
        parser.error("--start needs --track")
    try:
        start = None if args.start in (None, "") else float(args.start)
    except ValueError:
        parser.error("--start takes seconds, e.g. 49.7")
    try:
        page = None
        if args.vibe is not None:
            page = relay("PUT", "socials/music", {"entry": args.entry, "vibe": args.vibe})
        if args.track is not None:
            page = relay("PUT", "socials/music", {"entry": args.entry, "track": args.track})
        if args.start is not None:
            page = relay("PUT", "socials/music", {"track": args.track, "start": start})
        show(args.entry, page or relay("GET", "socials"))
    except (RuntimeError, subprocess.TimeoutExpired) as e:
        print(f"set-social-music: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
