#!/usr/bin/env python3
"""Serve the social posts the relay will make for the next release's major and notable updates.

docs/guides/release.md asks for this beside the updates-page preview. Every unreleased major or notable entry with a
picture goes through dp-relay's own builders at origin/main (socials-update-card.js, and socials-updates.js for the
captions): the Facebook post, the Instagram feed post (a 4:5 video when the picture is an animated GIF), the story
frame, and the Socials page's Copy post text. The picture is read from this checkout's
.github/release-notes/images/, so it works before the merge puts it on main. Nothing is posted and no file in either
repo changes.

Usage:
  python3 scripts/release-notes/preview-social-posts.py [--version 0.1746.0] [--port 8766]
                                                        [--relay ~/Projects/dp-relay]

Needs node and a dp-relay clone with its node_modules installed. Keeps serving until stopped. Exits 1 when no
unreleased major or notable entry has a picture.
"""
import argparse
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

IMAGES_DIR = os.path.join(".github", "release-notes", "images")
UPDATE_PAGE_URL = "https://dungeon.training/update/"

# Run inside the relay copy: renders one entry with the relay's own code. argv: entries.json, out dir.
DRIVER = r"""
'use strict';
const fs = require('fs');
const path = require('path');
const card = require('./socials-update-card');
const updates = require('./socials-updates');
const [list, out] = process.argv.slice(2);
(async () => {
  const done = [];
  for (const e of JSON.parse(fs.readFileSync(list, 'utf8'))) {
    const bytes = fs.readFileSync(e.picturePath);
    const dir = path.join(out, e.id);
    fs.mkdirSync(dir, { recursive: true });
    const log = (m) => console.log(e.id + ': ' + m);
    const files = {};
    const save = (name, buf) => { if (buf) { fs.writeFileSync(path.join(dir, name), buf); files[name.split('.')[0]] = e.id + '/' + name; } };
    if (card.isAnimated(bytes)) {
      const v = await card.buildUpdateVideo(e, bytes, { log });
      if (v) save('feed-video.mp4', v.mp4);
    }
    save('facebook.jpg', await card.pictureJpeg(bytes, { log }));
    save('instagram.jpg', await card.buildUpdateCard(e, bytes, { format: 'feed', log }));
    save('story.jpg', await card.buildUpdateCard(e, bytes, { format: 'story', isNew: true, log }));
    done.push({ id: e.id, title: e.title, files,
      captions: Object.fromEntries(['facebook', 'instagram'].map((p) => [p, updates.caption(e, p)])) });
  }
  fs.writeFileSync(path.join(out, 'posts.json'), JSON.stringify(done));
  process.exit(0);
})().catch((err) => { console.error(err); process.exit(1); });
"""


def read_version(path: str = changelog_io.GRADLE_PROPERTIES_FILE) -> str:
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("mod_version="):
                return line.split("=", 1)[1].strip()
    raise ValueError(f"no mod_version in {path}")


def postable(entries: list[dict], version: str) -> list[dict]:
    """The unreleased major/notable entries with a local picture, shaped as the relay's update entries."""
    out = []
    for e in changelog_io.unreleased_entries(entries):
        image = e.get("image") or ""
        local = os.path.join(IMAGES_DIR, image.rsplit("/", 1)[-1]) if image else ""
        if not e.get("major") or not local or not os.path.exists(local):
            continue
        out.append({
            "id": e["id"], "title": e["title"], "summary": e.get("summary", ""), "tags": e.get("tags", []),
            "version": f"v{version}", "released_at": e.get("date", "") + "T00:00:00Z",
            "notable": bool(e.get("notable")), "milestone": bool(e.get("milestone")),
            "picturePath": os.path.abspath(local),
        })
    return out


def extract_relay(relay: str, target: str) -> None:
    if not os.path.isdir(os.path.join(relay, "node_modules")):
        raise ValueError(f"{relay} has no node_modules; run npm ci there first")
    if subprocess.run(["git", "-C", relay, "fetch", "--quiet", "origin", "main"], check=False).returncode != 0:
        print("Warning: couldn't fetch dp-relay; using its last known origin/main", file=sys.stderr)
    archive_path = os.path.join(target, "relay.tar")
    subprocess.run(["git", "-C", relay, "archive", "--format=tar", "-o", archive_path, "origin/main"], check=True)
    with tarfile.open(archive_path) as archive:
        archive.extractall(target, filter="data")
    os.remove(archive_path)
    os.symlink(os.path.join(relay, "node_modules"), os.path.join(target, "node_modules"))


def media_block(post: dict, platform: str) -> str:
    files = post["files"]
    cover = files.get(platform)
    still = f'<img src="{cover}" alt="">' if cover else '<p class="k">No picture rendered</p>'
    if "feed-video" not in files:
        return still
    video = f'<video src="{files["feed-video"]}" poster="{cover or ""}" controls autoplay muted loop playsinline></video>'
    return video + '<p class="k">Cover:</p>' + still


def page(version: str, posts: list[dict], entries: list[dict]) -> str:
    by_id = {e["id"]: e for e in entries}
    cards = []
    for post in posts:
        e = by_id[post["id"]]
        copy = "\n\n".join(x for x in (e["title"], e["summary"], f"{UPDATE_PAGE_URL}#v{version}") if x)
        cards.append(
            f"<h2>{html.escape(post['title'])}</h2><div class=grid>"
            f"<div class=card><h3>Facebook Page</h3>{media_block(post, 'facebook')}"
            f"<pre>{html.escape(post['captions']['facebook'])}</pre></div>"
            f"<div class=card><h3>Instagram feed</h3>{media_block(post, 'instagram')}"
            f"<pre>{html.escape(post['captions']['instagram'])}</pre></div>"
            f"<div class=card><h3>Story (Instagram and Facebook)</h3><img src=\"{post['files'].get('story', '')}\" alt=\"\"></div>"
            f"<div class=card><h3>Copy post (other platforms)</h3><pre>{html.escape(copy)}</pre></div></div>"
        )
    return (
        "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
        "<title>Social posts preview</title><style>"
        ":root{--bg:#f4f4f2;--card:#fff;--ink:#1c1c1a;--muted:#62625c;--line:#ddd}"
        "@media (prefers-color-scheme:dark){:root{--bg:#141513;--card:#1e1f1d;--ink:#e8e8e2;--muted:#9a9a92;--line:#333}}"
        "body{margin:0;padding:16px;background:var(--bg);color:var(--ink);font:15px/1.5 system-ui}"
        ".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:16px}"
        ".card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px}"
        "h3{margin:0 0 8px;font-size:15px}.k{color:var(--muted);font-size:13px}"
        "img,video{width:100%;border-radius:8px;display:block}"
        "pre{white-space:pre-wrap;font:13px/1.45 system-ui;background:var(--bg);border-radius:8px;padding:10px}</style>"
        f"<h1>v{html.escape(version)}: social posts</h1>"
        "<p class=k>Rendered by dp-relay's own code at origin/main. Hashtags rotate by the update's place in the "
        "live list, so the five shown here can differ from the ones that post.</p>" + "".join(cards)
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--version", help="version to show; defaults to mod_version")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--relay", default=os.path.expanduser("~/Projects/dp-relay"))
    args = parser.parse_args(argv)
    version = args.version or read_version()
    entries = postable(changelog_io.load_changelog()["entries"], version)
    if not entries:
        print("No unreleased major or notable entry with a picture, so there is nothing to preview.")
        return 1
    try:
        with tempfile.TemporaryDirectory(prefix="dt-social-preview-") as target:
            relay_copy = os.path.join(target, "relay")
            site = os.path.join(target, "site")
            os.makedirs(relay_copy)
            os.makedirs(site)
            extract_relay(args.relay, relay_copy)
            with open(os.path.join(relay_copy, "preview-driver.js"), "w", encoding="utf-8") as f:
                f.write(DRIVER)
            listing = os.path.join(target, "entries.json")
            with open(listing, "w", encoding="utf-8") as f:
                json.dump(entries, f)
            env = {**os.environ, "RELAY_DB_DIR": os.path.join(target, "db")}
            os.makedirs(env["RELAY_DB_DIR"])
            subprocess.run(["node", "preview-driver.js", listing, site], cwd=relay_copy, env=env, check=True)
            with open(os.path.join(site, "posts.json"), encoding="utf-8") as f:
                posts = json.load(f)
            with open(os.path.join(site, "index.html"), "w", encoding="utf-8") as f:
                f.write(page(version, posts, entries))
            handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=site)
            with http.server.ThreadingHTTPServer(("127.0.0.1", args.port), handler) as server:
                print(f"v{version}, {len(posts)} post(s)")
                print(f"Social posts: http://127.0.0.1:{args.port}/", flush=True)
                server.serve_forever()
    except KeyboardInterrupt:
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as e:
        print(f"social preview failed: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
