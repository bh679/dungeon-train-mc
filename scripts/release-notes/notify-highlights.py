#!/usr/bin/env python3
"""Post the major release(s) a tag shipped to the Discord #highlights channel.

Run by release.yml after "Mark changelog entries released", so every entry the
release shipped carries `released_in == <tag>`. Each of those marked `major`
becomes one embed: its title (linking to the update page), description (or
summary), and photo. A release with no major entry posts nothing.

Env:
  DISCORD_HIGHLIGHTS_WEBHOOK_URL  #highlights channel webhook (secret). Unset → skip, exit 0.
  DRY_RUN=1                       Print the payloads instead of posting.

Usage:
  python3 scripts/release-notes/notify-highlights.py --tag v0.1192.0
  python3 scripts/release-notes/notify-highlights.py --entry release-albums --tag v0.1192.0

`--entry` posts one entry regardless of its released_in — for re-posting or previewing.
Path honours the CHANGELOG_FILE env override.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request

import changelog_io

UPDATE_PAGE_URL = "https://brennan.games/dungeontrain/update/"
LOGO_URL = "https://raw.githubusercontent.com/bh679/dungeon-train-mc/main/src/main/resources/logo.png"
COLOR = 5763719  # 0x57F287, matches the stable-release embed in notify-discord.sh
DESCRIPTION_LIMIT = 4096  # Discord embed description cap
USER_AGENT = "DiscordBot (https://github.com/bh679/dungeon-train-mc, 1)"  # Python-urllib is blocked


def majors_for(entries: list[dict], tag: str) -> list[dict]:
    return [e for e in entries if e.get("major") and e.get("released_in") == tag]


def _truncate(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def build_payload(entry: dict, tag: str) -> dict:
    body = (entry.get("description") or entry.get("summary") or "").strip()
    embed = {
        "title": entry["title"],
        "url": f"{UPDATE_PAGE_URL}#{tag}",
        "description": _truncate(body, DESCRIPTION_LIMIT),
        "color": COLOR,
        "footer": {"text": f"Dungeon Train {tag}"},
    }
    image = (entry.get("image") or "").strip()
    if image:
        embed["image"] = {"url": image}
    return {"username": "Dungeon Train", "avatar_url": LOGO_URL, "embeds": [embed]}


def post(webhook: str, payload: dict) -> None:
    req = urllib.request.Request(
        webhook,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "User-Agent": USER_AGENT},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        resp.read()


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Post major releases to Discord #highlights.")
    p.add_argument("--tag", required=True, help="The release tag, e.g. v0.1192.0")
    p.add_argument("--entry", default=None, help="Post this entry id instead of the tag's majors.")
    args = p.parse_args(argv)

    entries = changelog_io.load_changelog()["entries"]
    if args.entry:
        entry = changelog_io.find_entry(entries, args.entry)
        if entry is None:
            print(f"::error::no changelog entry with id '{args.entry}'", file=sys.stderr)
            return 1
        targets = [entry]
    else:
        targets = majors_for(entries, args.tag)
    if not targets:
        print(f"No major release in {args.tag}; nothing to post to #highlights.")
        return 0

    payloads = [build_payload(e, args.tag) for e in targets]
    if os.environ.get("DRY_RUN") == "1":
        print(json.dumps(payloads, indent=2, ensure_ascii=False))
        return 0
    webhook = os.environ.get("DISCORD_HIGHLIGHTS_WEBHOOK_URL", "").strip()
    if not webhook:
        print("::warning::DISCORD_HIGHLIGHTS_WEBHOOK_URL not set — skipping #highlights post.")
        return 0
    try:
        for e, payload in zip(targets, payloads):
            post(webhook, payload)
            print(f"✓ Posted '{e['id']}' to #highlights")
    except urllib.error.HTTPError as err:
        print(f"::error::#highlights post failed: HTTP {err.code}", file=sys.stderr)
        return 1
    except urllib.error.URLError as err:
        print(f"::error::#highlights post failed: {err.reason}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
