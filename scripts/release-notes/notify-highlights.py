#!/usr/bin/env python3
"""Post the major release(s) a tag shipped to the Discord #highlights channel.

Run by release.yml after "Mark changelog entries released", so every entry the
release shipped carries `released_in == <tag>`. Each of those marked `major`
becomes one plain markdown message (no embed card): `# title`, its description
(or summary), a link to the update page, and its photo uploaded as an attachment.
A release with no major entry posts nothing.

The photo is read from the checkout when its URL points into this repo, else
downloaded. If it can't be loaded (or is over Discord's upload cap) the message
falls back to carrying the photo URL as its last line, so it still shows.

Env:
  DISCORD_HIGHLIGHTS_WEBHOOK_URL  #highlights channel webhook (secret). Unset → skip, exit 0.
  DRY_RUN=1                       Print the payloads (and which photo would attach) instead of posting.

Usage:
  python3 scripts/release-notes/notify-highlights.py --tag v0.1192.0
  python3 scripts/release-notes/notify-highlights.py --entry release-albums --tag v0.1192.0

`--entry` posts one entry regardless of its released_in — for re-posting or previewing.
Path honours the CHANGELOG_FILE env override.
"""
import argparse
import json
import mimetypes
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

import changelog_io

UPDATE_PAGE_URL = "https://brennan.games/dungeontrain/update/"
LOGO_URL = "https://raw.githubusercontent.com/bh679/dungeon-train-mc/main/src/main/resources/logo.png"
CONTENT_LIMIT = 2000  # Discord message content cap
ATTACHMENT_LIMIT = 10 * 1024 * 1024  # Discord's default per-file upload cap for webhooks
USER_AGENT = "DiscordBot (https://github.com/bh679/dungeon-train-mc, 1)"  # Python-urllib is blocked
REPO_RAW_PREFIX = "https://raw.githubusercontent.com/bh679/dungeon-train-mc/"
REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))


def majors_for(entries: list[dict], tag: str) -> list[dict]:
    return [e for e in entries if e.get("major") and e.get("released_in") == tag]


def _truncate(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def _image_url(entry: dict) -> str:
    return (entry.get("image") or "").strip()


def build_content(entry: dict, tag: str, with_image_link: bool = False) -> str:
    """The message as plain Discord markdown: `# title`, the description, then a link to the
    update page (angle brackets stop it unfurling). The photo travels as an attachment; only when
    it couldn't be loaded does `with_image_link` put its URL last so Discord unfurls it instead.
    The description is trimmed so the whole message fits Discord's cap."""
    head = f"# {entry['title']}"
    tail = [f"[Read the full update](<{UPDATE_PAGE_URL}#{tag}>)"]
    image = _image_url(entry)
    if with_image_link and image:
        tail.append(image)
    tail_text = "\n".join(tail)
    room = CONTENT_LIMIT - len(head) - len(tail_text) - len("\n\n") * 2
    body = (entry.get("description") or entry.get("summary") or "").strip()
    return "\n\n".join(p for p in (head, _truncate(body, room), tail_text) if p)


def build_payload(entry: dict, tag: str, with_image_link: bool = False) -> dict:
    return {
        "username": "Dungeon Train",
        "avatar_url": LOGO_URL,
        "content": build_content(entry, tag, with_image_link),
        "allowed_mentions": {"parse": []},
    }


def attachment_filename(url: str) -> str:
    """The URL's basename, limited to characters Discord keeps as-is (its extension decides how
    the attachment renders — a .gif stays animated)."""
    name = os.path.basename(urllib.parse.urlparse(url).path)
    name = re.sub(r"[^A-Za-z0-9._-]", "_", name)
    return name or "image"


def _local_repo_path(url: str) -> str | None:
    """`<repo root>/<path>` for a raw.githubusercontent URL into this repo, if that file exists."""
    if not url.startswith(REPO_RAW_PREFIX):
        return None
    rest = urllib.parse.urlparse(url).path.split("/", 4)  # ['', owner, repo, ref, path]
    if len(rest) < 5 or not rest[4]:
        return None
    path = os.path.normpath(os.path.join(REPO_ROOT, urllib.parse.unquote(rest[4])))
    if not path.startswith(REPO_ROOT + os.sep) or not os.path.isfile(path):
        return None
    return path


def _read_capped(stream, limit: int) -> bytes | None:
    data = stream.read(limit + 1)
    return None if len(data) > limit else data


def load_image(url: str, limit: int = ATTACHMENT_LIMIT) -> tuple[str, bytes] | None:
    """(filename, bytes) for the entry's photo, or None (with a warning) if it can't be attached."""
    if not url:
        return None
    try:
        local = _local_repo_path(url)
        if local:
            with open(local, "rb") as f:
                data = _read_capped(f, limit)
        else:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=30) as resp:
                data = _read_capped(resp, limit)
    except (OSError, ValueError) as err:  # URLError/HTTPError are OSErrors
        print(f"::warning::couldn't load photo {url} ({err}) — posting its link instead.")
        return None
    if data is None:
        print(f"::warning::photo {url} is over {limit} bytes — posting its link instead.")
        return None
    return attachment_filename(url), data


def build_multipart(payload: dict, filename: str, data: bytes) -> tuple[bytes, str]:
    """A webhook body carrying `payload` plus one file: Discord's `payload_json` + `files[0]`
    form, with `attachments[0]` naming it. Returns (body, content type)."""
    boundary = f"----dungeontrain{uuid.uuid4().hex}"
    full = {**payload, "attachments": [{"id": 0, "filename": filename}]}
    mime = mimetypes.guess_type(filename)[0] or "application/octet-stream"
    parts = [
        f"--{boundary}\r\n"
        'Content-Disposition: form-data; name="payload_json"\r\n'
        "Content-Type: application/json\r\n\r\n".encode("utf-8")
        + json.dumps(full).encode("utf-8")
        + b"\r\n",
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="files[0]"; filename="{filename}"\r\n'
        f"Content-Type: {mime}\r\n\r\n".encode("utf-8")
        + data
        + b"\r\n",
        f"--{boundary}--\r\n".encode("utf-8"),
    ]
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def post(webhook: str, payload: dict, attachment: tuple[str, bytes] | None = None) -> None:
    if attachment:
        body, content_type = build_multipart(payload, *attachment)
    else:
        body, content_type = json.dumps(payload).encode("utf-8"), "application/json"
    req = urllib.request.Request(
        webhook,
        data=body,
        headers={"Content-Type": content_type, "User-Agent": USER_AGENT},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        resp.read()


def dry_run_preview(entry: dict, tag: str) -> dict:
    """What would be posted, without touching the network: the payload and the photo it attaches."""
    preview = build_payload(entry, tag)
    image = _image_url(entry)
    if image:
        preview["attachment"] = {"filename": attachment_filename(image), "source": image}
    return preview


def post_entry(webhook: str, entry: dict, tag: str) -> None:
    attachment = load_image(_image_url(entry))
    with_link = attachment is None and bool(_image_url(entry))
    post(webhook, build_payload(entry, tag, with_image_link=with_link), attachment)


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

    if os.environ.get("DRY_RUN") == "1":
        print(json.dumps([dry_run_preview(e, args.tag) for e in targets], indent=2, ensure_ascii=False))
        return 0
    webhook = os.environ.get("DISCORD_HIGHLIGHTS_WEBHOOK_URL", "").strip()
    if not webhook:
        print("::warning::DISCORD_HIGHLIGHTS_WEBHOOK_URL not set — skipping #highlights post.")
        return 0
    try:
        for e in targets:
            post_entry(webhook, e, args.tag)
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
