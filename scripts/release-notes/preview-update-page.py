#!/usr/bin/env python3
"""Serve the updates page on localhost, showing the next release as if it had shipped.

docs/guides/release.md requires this before a release is confirmed. It reads dp-relay's page at
origin/main (never the clone's working tree, which is often on another branch), marks a local copy
of the changelog released, and patches the copy of the page to read it. Nothing is published and no
file in either repo changes.

Two pages:
  /update/        the updates page as players will see it
  /notes.html     the notes that go to GitHub, Modrinth and CurseForge

Usage:
  python3 scripts/release-notes/preview-update-page.py [--version 0.1705.0] [--port 8765]
                                                       [--relay ~/Projects/dp-relay]
                                                       [--allow-stale]

Keeps serving until stopped. Exits 1 when nothing is unreleased: there is nothing to preview. Also
exits 1 when this checkout isn't origin/main, because the notes come from the checkout's files and a
stale checkout would preview the wrong release (--allow-stale is for testing this script).
"""
import argparse
import datetime
import functools
import html
import http.server
import json
import os
import subprocess
import sys
import tarfile
import tempfile

import changelog_io

# The whole site, not a list of the page's files: that list keeps growing, and a missing script stops
# the page dead.
PAGE_ROOT = "public/dungeontrain"
LOCAL_CHANGELOG = "changelog.preview.json"
# What the copy of update.js needs changing. Each must match exactly once, so a changed page stops
# the preview instead of quietly showing the live changelog.
CHANGELOG_SOURCE = (
    "changelog: 'https://raw.githubusercontent.com/bh679/dungeon-train-mc/main/"
    ".github/release-notes/changelog.json',"
)
NEWEST_SOURCE = "const newest = listed || newestReleased(changelog);"


def read_version(path: str = changelog_io.GRADLE_PROPERTIES_FILE) -> str:
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("mod_version="):
                return line.split("=", 1)[1].strip()
    raise ValueError(f"no mod_version in {path}")


def patch_update_js(source: str) -> str:
    """The page's script, reading the local changelog and ignoring what the stores list as newest."""
    for needle in (CHANGELOG_SOURCE, NEWEST_SOURCE):
        if source.count(needle) != 1:
            raise ValueError(f"update.js has changed; can't find: {needle}")
    return source.replace(CHANGELOG_SOURCE, f"changelog: '{LOCAL_CHANGELOG}',").replace(
        NEWEST_SOURCE, "const newest = newestReleased(changelog);"
    )


def preview_changelog(data: dict, version: str, now: str) -> tuple[dict, int]:
    entries, count = changelog_io.mark_all_released(data["entries"], f"v{version}", now)
    return {**data, "entries": entries}, count


def notes_page(version: str, markdown: str) -> str:
    return (
        "<!doctype html><meta charset=utf-8><title>Release notes preview</title>"
        "<style>body{font:15px/1.5 system-ui;max-width:760px;margin:32px auto;padding:0 16px}"
        "pre{white-space:pre-wrap}</style>"
        f"<h1>v{html.escape(version)}: notes for GitHub, Modrinth and CurseForge</h1>"
        f"<pre>{html.escape(markdown)}</pre>"
    )


def checkout_matches_main() -> bool:
    subprocess.run(["git", "fetch", "--quiet", "origin", "main"], check=True)
    revisions = [
        subprocess.run(["git", "rev-parse", ref], check=True, capture_output=True, text=True).stdout
        for ref in ("HEAD", "origin/main")
    ]
    return revisions[0] == revisions[1]


def extract_page(relay: str, target: str) -> None:
    fetched = subprocess.run(["git", "-C", relay, "fetch", "--quiet", "origin", "main"], check=False)
    if fetched.returncode != 0:
        print("Warning: couldn't fetch dp-relay; using its last known origin/main", file=sys.stderr)
    archive_path = os.path.join(target, "page.tar")
    subprocess.run(
        ["git", "-C", relay, "archive", "--format=tar", "-o", archive_path, "origin/main", PAGE_ROOT],
        check=True,
    )
    with tarfile.open(archive_path) as archive:
        archive.extractall(target, filter="data")
    os.remove(archive_path)


def build_site(relay: str, version: str, target: str) -> int:
    """Write the preview into target and return how many changes it shows."""
    data = changelog_io.load_changelog()
    pending = changelog_io.unreleased_entries(data["entries"])
    markdown = changelog_io.render_markdown(pending)
    preview, count = preview_changelog(
        data, version, datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    )
    if count == 0:
        return 0
    extract_page(relay, target)
    site = os.path.join(target, PAGE_ROOT)
    script = os.path.join(site, "update", "update.js")
    with open(script, encoding="utf-8") as f:
        patched = patch_update_js(f.read())
    with open(script, "w", encoding="utf-8") as f:
        f.write(patched)
    with open(os.path.join(site, "update", LOCAL_CHANGELOG), "w", encoding="utf-8") as f:
        json.dump(preview, f, ensure_ascii=False)
    with open(os.path.join(site, "notes.html"), "w", encoding="utf-8") as f:
        f.write(notes_page(version, markdown))
    return count


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--version", help="version to show; defaults to mod_version")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--relay", default=os.path.expanduser("~/Projects/dp-relay"))
    parser.add_argument("--allow-stale", action="store_true", help="preview a checkout behind main")
    args = parser.parse_args(argv)
    try:
        if not args.allow_stale and not checkout_matches_main():
            print("This checkout isn't origin/main, so its notes may be wrong. See docs/guides/release.md.")
            return 1
        version = args.version or read_version()
        with tempfile.TemporaryDirectory(prefix="dt-update-preview-") as target:
            count = build_site(args.relay, version, target)
            if count == 0:
                print("No unreleased changelog entries, so there is nothing to preview.")
                return 1
            handler = functools.partial(
                http.server.SimpleHTTPRequestHandler,
                directory=os.path.join(target, PAGE_ROOT),
            )
            with http.server.ThreadingHTTPServer(("127.0.0.1", args.port), handler) as server:
                print(f"v{version}, {count} change(s)")
                print(f"Updates page: http://127.0.0.1:{args.port}/update/", flush=True)
                print(f"Store notes:  http://127.0.0.1:{args.port}/notes.html", flush=True)
                server.serve_forever()
    except KeyboardInterrupt:
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as e:
        print(f"preview failed: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
