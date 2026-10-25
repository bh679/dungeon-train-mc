#!/usr/bin/env python3
"""Tell the live chat that a new Dungeon Train version is out.

    python3 scripts/relay/announce-release-live-chat.py --tag v0.1310.0
    python3 scripts/relay/announce-release-live-chat.py --tag v0.1310.0 --dry-run
    python3 scripts/relay/announce-release-live-chat.py --tag v0.1310.0 --notes-file notes.md

Calls ``POST <admin base>/live/chat/announce`` (dp-relay ``live-chat.js#announceRelease``). The line
reaches the website's live chat panel, the Discord live room and Twitch (while someone is on air). It
never reaches the game: in-game players already get the mod's own update notice
(``client/version/LiveUpdateNotice``), and the relay keeps it out of the game's read so they see it once.

The line mirrors that in-game notice (``client/version/ReleaseName``): the release is named after its
lead changelog entry (major, then notable, then feat / content / fix; ``internal`` entries never name
one), read from the local ledger, which release.yml has already stamped by this step. A notable release
adds its one-sentence summary and a major one a "★ MAJOR UPDATE ★" banner, as in game. With no ledger
entry the first ``# `` heading of the GitHub Release body names it. Every line ends with a "What's new"
link to the release on the update page.

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

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "release-notes"))
import changelog_io  # noqa: E402

ENVS = ("DUNGEONTRAIN_RELAY_ADMIN_BASE", "DUNGEONTRAIN_RELAY_ADMIN_URL")
HOME_FILE = Path.home() / ".dungeontrain-relay-admin"
REPO = "bh679/dungeon-train-mc"
UPDATE_PAGE_URL = "https://brennan.games/dungeontrain/update/"
LEDGER = Path(__file__).resolve().parents[2] / ".github" / "release-notes" / "changelog.json"
TAG_RE = re.compile(r"^v?(\d{1,6}\.\d{1,6}\.\d{1,6})$")
HEADING_RE = re.compile(r"^#\s+(.+?)\s*$", re.MULTILINE)
MD_LINK_RE = re.compile(r"\[([^\]]+)\]\([^)]*\)")
LINK_TEXT = "What's new"
MAX_CONTENT = 500  # the relay's live-chat line cap
MAX_TITLE = 160
SUMMARY_MAX = 140  # ReleaseName.SUMMARY_MAX

# How loudly a release is announced (ReleaseName.Tier).
MAJOR, NOTABLE, NORMAL = "major", "notable", "normal"
TYPE_ORDER = ("feat", "content", "fix")  # entry types in the order they lead a release
INTERNAL_TAG = "internal"


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


def _major_minor(version):
    m = re.match(r"^v?(\d+)\.(\d+)(?:\.\d+)?$", str(version or "").strip())
    return (int(m.group(1)), int(m.group(2))) if m else None


def _rank(entry):
    """Major first (a notable major after a full one), then TYPE_ORDER, then the rest."""
    if entry.get("major") is True:
        return 1 if entry.get("notable") is True else 0
    t = entry.get("type")
    return 2 + (TYPE_ORDER.index(t) if t in TYPE_ORDER else len(TYPE_ORDER))


def short_summary(summary):
    """The first sentence (or line) of a ledger summary as plain text, cut at SUMMARY_MAX with "...";
    '' when nothing readable is left or it is only a link (ReleaseName.shortSummary)."""
    text = re.sub(r"(?m)^\s*#{1,6}\s+.*$", "", summary or "")
    text = MD_LINK_RE.sub(r"\1", text)
    text = re.sub(r"[*_`]", "", text).strip()
    text = text.split("\n", 1)[0].strip()
    stop = re.search(r"[.!?] ", text)
    if stop:
        text = text[: stop.start() + 1]
    if not text or text.startswith(("http://", "https://")):
        return ""
    if len(text) > SUMMARY_MAX:
        cut = text.rfind(" ", 0, SUMMARY_MAX - 3)
        text = text[: cut if cut > 0 else SUMMARY_MAX - 3].strip() + "..."
    return text


def pick_release(entries, version):
    """The release's (name, tier, summary) from its lead ledger entry, or None (ReleaseName.pick).
    Entries match on major.minor, so a cascade tag still finds its release."""
    want = _major_minor(version)
    if want is None:
        return None
    best = None
    for e in entries or []:
        if not isinstance(e, dict) or not str(e.get("title") or "").strip():
            continue
        if e.get("released") is not True or _major_minor(e.get("released_in")) != want:
            continue
        if INTERNAL_TAG in (e.get("tags") or []):
            continue
        if best is None or _rank(e) < _rank(best):
            best = e
    if best is None:
        return None
    rank = _rank(best)
    tier = MAJOR if rank == 0 else NOTABLE if rank == 1 else NORMAL
    return best["title"].strip(), tier, short_summary(best.get("summary") or "")


def load_entries():
    """The ledger's entries ($CHANGELOG_FILE or the checkout's), or [] when it can't be read."""
    try:
        return changelog_io.load_changelog(os.environ.get("CHANGELOG_FILE") or str(LEDGER))["entries"]
    except (OSError, ValueError) as e:
        print(f"warning: changelog unreadable ({e}); naming the release from its notes.", file=sys.stderr)
        return []


def _clip(text, room):
    if room <= 0:
        return ""
    return text if len(text) <= room else text[: max(room - 1, 0)].rstrip() + "…"


def build_announcement(version, notes, entries=()):
    """The relay request body: one line shaped like the in-game notice, and its "What's new" link."""
    info = pick_release(entries, version)
    if info is None:
        title = release_title(notes)
        info = (title, NORMAL, "") if title else None
    tail = f" {LINK_TEXT}"
    if info is None:
        content = f"Version {version} is out!{tail}"
    else:
        name, tier, summary = info
        name = _clip(name, MAX_TITLE)
        if tier == MAJOR:
            head = f"★ MAJOR UPDATE ★ {name} (v{version})."
        elif tier == NOTABLE:
            head = f"New update: {name} (v{version})."
        else:
            head = f"{name} (v{version}) is out!"
            summary = ""
        summary = _clip(summary, MAX_CONTENT - len(head) - len(tail) - 1)
        content = f"{head} {summary}{tail}" if summary else head + tail
    return {
        "content": content,
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
    body = build_announcement(version, notes, load_entries())
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
