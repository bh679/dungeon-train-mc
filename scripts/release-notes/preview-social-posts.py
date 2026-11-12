#!/usr/bin/env python3
"""Serve the social posts the relay will make for the next release's major and notable updates.

docs/guides/release.md asks for this beside the updates-page preview. Every unreleased major or notable entry with a
picture goes through dp-relay's own builders at origin/main (socials-update-card.js, and socials-updates.js for the
captions): the Facebook post, the Instagram feed post (a 4:5 video when the picture is an animated GIF), the story
frame, and the Socials page's Copy post text. The picture is read from this checkout's
.github/release-notes/images/, so it works before the merge puts it on main. Nothing is posted and no file in either
repo changes.

An entry with its own clip (`video`, an .mp4 with sound) is shown as the relay posts it: the Instagram and Facebook
feed video, the story video and the TikTok video, all built from the clip with its own sound and the song under it,
and the clip's cover frame. The clip is read from the same images folder by its file name.

An animated picture's feed video carries its song, as the relay posts it (socials-music.js): the update's vibe (from
its tags, or --vibe) and a track from that vibe, picked the way the relay picks (seeded, so a rerun shows the same
one), or the one named with --music. The card shows the song, where it starts, the vibe's other tracks and their
chances, and the credit line the captions carry. Pin the song you settle on with set-social-music.py before the
release ships, so the relay posts that exact song. Tracks come from Mojang's asset server once and are kept in
~/.cache/dungeontrain/mc-music/.

Usage:
  python3 scripts/release-notes/preview-social-posts.py [--version 0.1746.0] [--port 8766]
                                                        [--relay ~/Projects/dp-relay] [--relay-ref origin/main]
                                                        [--music <entry-id>=<track-id>] [--vibe <entry-id>=<vibe>]

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
const music = require('./socials-music');
const [list, out] = process.argv.slice(2);

/** A repeatable 0..1 stream from the entry id, so a rerun shows the same song. */
function seeded(text) {
  let h = 2166136261;
  for (const c of text) h = Math.imul(h ^ c.charCodeAt(0), 16777619) >>> 0;
  return () => { h = (Math.imul(h ^ (h >>> 15), 2246822507) + 0x6d2b79f5) >>> 0; return h / 4294967296; };
}

/** The song this entry's video gets: the named track, else the relay's own pick from the vibe. */
function song(e) {
  const vibe = e.vibe || music.defaultVibe(e);
  const named = e.track ? music.track(e.track) : null;
  if (e.track && !named) throw new Error(`${e.id}: no track '${e.track}' (see the vibe lists on the preview page)`);
  const t = named || music.pickTrack(vibe, 'instagram', { random: seeded(e.id), record: false });
  const odds = music.chances(vibe);
  return {
    t, vibe, vibeFrom: e.vibe ? 'chosen' : 'tags', chosen: !!named,
    others: music.tracksIn(vibe).sort((a, b) => b.plays - a.plays)
      .map((x) => ({ id: x.id, title: x.title, composer: x.composer, chance: Math.round(odds.get(x.id) * 1000) / 10 })),
  };
}

/** An entry's own clip as the relay posts it: every video built from the clip, its frame the cover. */
async function clipPosts(e, save, log) {
  if (!card.buildClipVideo) throw new Error(`${e.id}: this dp-relay ref can't build clip videos (no buildClipVideo); pass --relay-ref`);
  const clip = fs.readFileSync(e.clipPath);
  const tune = song(e);
  const got = await music.trackFile(tune.t, { log });
  if (!got) throw new Error(`${e.id}: could not fetch ${tune.t.id} from Mojang's asset server`);
  const track = { id: tune.t.id, title: tune.t.title, composer: tune.t.composer };
  log(`clip, song ${track.title} by ${track.composer} (${tune.vibe}) under it, from ${got.start}s`);
  for (const [format, name] of [['feed', 'feed-video'], ['story', 'story-video'], ['tiktok', 'tiktok-video']]) {
    const v = await card.buildClipVideo(e, clip, { format, isNew: format === 'story', music: { ...got, track }, cover: e.clipCover, log });
    if (!v) throw new Error(`${e.id}: the ${format} clip video did not render`);
    save(`${name}.mp4`, v.mp4);
    if (format === 'feed') { save('facebook.jpg', v.still); save('instagram.jpg', v.still); }
    if (format === 'story') save('story.jpg', v.still);
  }
  tune.start = got.start;
  return { tune, track };
}

(async () => {
  const done = [];
  for (const e of JSON.parse(fs.readFileSync(list, 'utf8'))) {
    const bytes = fs.readFileSync(e.picturePath);
    const dir = path.join(out, e.id);
    fs.mkdirSync(dir, { recursive: true });
    const log = (m) => console.log(e.id + ': ' + m);
    const files = {};
    const save = (name, buf) => { if (buf) { fs.writeFileSync(path.join(dir, name), buf); files[name.split('.')[0]] = e.id + '/' + name; } };
    let tune = null;
    let track = null;
    if (e.clipPath) {
      ({ tune, track } = await clipPosts(e, save, log));
    } else if (card.isAnimated(bytes)) {
      tune = song(e);
      const got = await music.trackFile(tune.t, { log });
      if (!got) throw new Error(`${e.id}: could not fetch ${tune.t.id} from Mojang's asset server`);
      track = { id: tune.t.id, title: tune.t.title, composer: tune.t.composer };
      log(`song ${track.title} by ${track.composer} (${tune.vibe}), from ${got.start}s`);
      const v = await card.buildUpdateVideo(e, bytes, { log, music: { ...got, track } });
      if (v) save('feed-video.mp4', v.mp4);
      tune.start = got.start;
    }
    if (!e.clipPath) {
      save('facebook.jpg', await card.pictureJpeg(bytes, { log }));
      save('instagram.jpg', await card.buildUpdateCard(e, bytes, { format: 'feed', log }));
      save('story.jpg', await card.buildUpdateCard(e, bytes, { format: 'story', isNew: true, log }));
    }
    done.push({ id: e.id, title: e.title, files,
      music: tune && { clip: !!e.clipPath, id: tune.t.id, title: tune.t.title, composer: tune.t.composer, start: tune.start, vibe: tune.vibe,
        vibeFrom: tune.vibeFrom, chosen: tune.chosen, others: tune.others, credit: music.credit(track) },
      captions: Object.fromEntries(['facebook', 'instagram'].map((p) => [p, updates.caption(e, p, track)])) });
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


def parse_picks(values: list[str], flag: str) -> dict[str, str]:
    """`--music id=track` / `--vibe id=vibe` pairs as {entry id: value}."""
    out = {}
    for v in values:
        entry, sep, value = v.partition("=")
        if not sep or not entry or not value:
            raise ValueError(f"{flag} wants <entry-id>=<value>, got '{v}'")
        out[entry] = value
    return out


def local_file(url: str) -> str:
    """The file of that name in this checkout's release-notes images folder, or '' for no url."""
    return os.path.join(IMAGES_DIR, url.rsplit("/", 1)[-1]) if url else ""


def clip_fields(e: dict) -> dict:
    """`clipPath` (and `clipCover`) for an entry with its own clip. A clip named but not in the images folder is an
    error: previewing the picture instead would show posts the relay won't make."""
    video = e.get("video") or ""
    if not video:
        return {}
    local = local_file(video)
    if not os.path.exists(local):
        raise ValueError(f"{e['id']}: its video {video} is not in {IMAGES_DIR}/")
    out = {"clipPath": os.path.abspath(local)}
    if e.get("video_cover") is not None:
        out["clipCover"] = e["video_cover"]
    return out


def postable(entries: list[dict], version: str) -> list[dict]:
    """The unreleased major/notable entries with a local picture, shaped as the relay's update entries."""
    out = []
    for e in changelog_io.unreleased_entries(entries):
        local = local_file(e.get("image") or "")
        if not e.get("major") or not local or not os.path.exists(local):
            continue
        out.append({
            "id": e["id"], "title": e["title"], "summary": e.get("summary", ""), "tags": e.get("tags", []),
            "version": f"v{version}", "released_at": e.get("date", "") + "T00:00:00Z",
            "notable": bool(e.get("notable")), "milestone": bool(e.get("milestone")),
            "picturePath": os.path.abspath(local),
            **clip_fields(e),
        })
    return out


def extract_relay(relay: str, target: str, ref: str = "origin/main") -> None:
    if not os.path.isdir(os.path.join(relay, "node_modules")):
        raise ValueError(f"{relay} has no node_modules; run npm ci there first")
    if subprocess.run(["git", "-C", relay, "fetch", "--quiet", "origin", "main"], check=False).returncode != 0:
        print("Warning: couldn't fetch dp-relay; using its last known origin/main", file=sys.stderr)
    archive_path = os.path.join(target, "relay.tar")
    subprocess.run(["git", "-C", relay, "archive", "--format=tar", "-o", archive_path, ref], check=True)
    with tarfile.open(archive_path) as archive:
        archive.extractall(target, filter="data")
    os.remove(archive_path)
    os.symlink(os.path.join(relay, "node_modules"), os.path.join(target, "node_modules"))


def media_block(post: dict, platform: str, video_key: str = "feed-video") -> str:
    files = post["files"]
    cover = files.get(platform)
    still = f'<img src="{cover}" alt="">' if cover else '<p class="k">No picture rendered</p>'
    if video_key not in files:
        return still
    # Not muted: the song is part of what is being checked. Browsers won't autoplay sound, so press play.
    video = f'<video src="{files[video_key]}" poster="{cover or ""}" controls loop playsinline></video>'
    return video + '<p class="k">Cover:</p>' + still


def tiktok_block(post: dict) -> str:
    """The TikTok video card, for an entry with its own clip."""
    tiktok = post["files"].get("tiktok-video")
    if not tiktok:
        return ""
    return f'<div class=card><h3>TikTok</h3><video src="{tiktok}" controls loop playsinline></video></div>'


def music_block(post: dict) -> str:
    """The song card: what plays, where from, the vibe's other tracks, and how to pin it."""
    m = post.get("music")
    if not m:
        return ""
    start = int(m["start"] or 0)
    how = "named with --music" if m["chosen"] else "the relay's pick from the vibe (seeded)"
    vibe_from = "chosen with --vibe" if m["vibeFrom"] == "chosen" else "from the update's tags"
    rows = "".join(
        f"<tr{' class=on' if t['id'] == m['id'] else ''}><td>{html.escape(t['title'])}</td>"
        f"<td>{html.escape(t['composer'])}</td><td>{t['chance']}%</td><td><code>{html.escape(t['id'])}</code></td></tr>"
        for t in m["others"])
    pin = f"python3 scripts/release-notes/set-social-music.py --entry {post['id']} --track {m['id']}"
    return (
        "<div class='card song'><h3>Song</h3>"
        f"<p class=big>{html.escape(m['title'])} <span class=k>by {html.escape(m['composer'])}, "
        f"from {start // 60}:{start % 60:02d}</span></p>"
        f"<p class=k>{html.escape(how)}. Vibe: <b>{html.escape(m['vibe'])}</b>, {vibe_from}. "
        + ("Play the videos above with sound: the clip's own sound, the song 10 dB under it.</p>"
           if m.get("clip") else "Play the Instagram video above with sound to hear it.</p>")
        +
        f"<p class=k>Caption credit: {html.escape(m['credit'])}</p>"
        f"<p class=k>Pin it before the release ships:</p><pre>{html.escape(pin)}</pre>"
        f"<details><summary>Other tracks in {html.escape(m['vibe'])}</summary><table>"
        "<tr><th>Track</th><th>Composer</th><th>Chance</th><th>Id</th></tr>"
        f"{rows}</table><p class=k>Preview another: <code>--music {html.escape(post['id'])}=&lt;id&gt;</code></p>"
        "</details></div>"
    )


def page(version: str, posts: list[dict], entries: list[dict], relay_ref: str = "origin/main") -> str:
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
            f"<div class=card><h3>Story (Instagram and Facebook)</h3>{media_block(post, 'story', 'story-video')}</div>"
            f"{tiktok_block(post)}"
            f"<div class=card><h3>Copy post (other platforms)</h3><pre>{html.escape(copy)}</pre></div>"
            f"{music_block(post)}</div>"
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
        ".song .big{font-size:18px;font-weight:600;margin:0 0 6px}"
        "table{width:100%;border-collapse:collapse;font-size:13px;table-layout:fixed}"
        "td,th{text-align:left;padding:3px 6px;border-bottom:1px solid var(--line);overflow-wrap:anywhere}"
        "tr.on td{font-weight:600}"
        "pre{white-space:pre-wrap;font:13px/1.45 system-ui;background:var(--bg);border-radius:8px;padding:10px}</style>"
        f"<h1>v{html.escape(version)}: social posts</h1>"
        f"<p class=k>Rendered by dp-relay's own code at {html.escape(relay_ref)}. Hashtags rotate by the update's place in the "
        "live list, so the five shown here can differ from the ones that post.</p>" + "".join(cards)
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--version", help="version to show; defaults to mod_version")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--relay", default=os.path.expanduser("~/Projects/dp-relay"))
    parser.add_argument("--relay-ref", default="origin/main",
                        help="dp-relay commit to render with (default origin/main; a branch to preview its changes)")
    parser.add_argument("--music", action="append", default=[], metavar="ENTRY=TRACK",
                        help="preview this track on that entry's video (repeatable)")
    parser.add_argument("--vibe", action="append", default=[], metavar="ENTRY=VIBE",
                        help="pick the song from this vibe instead of the one the tags give (repeatable)")
    args = parser.parse_args(argv)
    version = args.version or read_version()
    try:
        entries = postable(changelog_io.load_changelog()["entries"], version)
        tracks, vibes = parse_picks(args.music, "--music"), parse_picks(args.vibe, "--vibe")
    except ValueError as e:
        print(e, file=sys.stderr)
        return 2
    for e in entries:
        if e["id"] in tracks:
            e["track"] = tracks[e["id"]]
        if e["id"] in vibes:
            e["vibe"] = vibes[e["id"]]
    if not entries:
        print("No unreleased major or notable entry with a picture, so there is nothing to preview.")
        return 1
    try:
        with tempfile.TemporaryDirectory(prefix="dt-social-preview-") as target:
            relay_copy = os.path.join(target, "relay")
            site = os.path.join(target, "site")
            os.makedirs(relay_copy)
            os.makedirs(site)
            extract_relay(args.relay, relay_copy, args.relay_ref)
            with open(os.path.join(relay_copy, "preview-driver.js"), "w", encoding="utf-8") as f:
                f.write(DRIVER)
            listing = os.path.join(target, "entries.json")
            with open(listing, "w", encoding="utf-8") as f:
                json.dump(entries, f)
            env = {**os.environ, "RELAY_DB_DIR": os.path.join(target, "db")}
            os.makedirs(env["RELAY_DB_DIR"])
            # The relay keeps tracks in <RELAY_DB_DIR>/mc-music; keep them between runs instead of refetching.
            cache = os.path.expanduser("~/.cache/dungeontrain/mc-music")
            os.makedirs(cache, exist_ok=True)
            os.symlink(cache, os.path.join(env["RELAY_DB_DIR"], "mc-music"))
            subprocess.run(["node", "preview-driver.js", listing, site], cwd=relay_copy, env=env, check=True)
            with open(os.path.join(site, "posts.json"), encoding="utf-8") as f:
                posts = json.load(f)
            with open(os.path.join(site, "index.html"), "w", encoding="utf-8") as f:
                f.write(page(version, posts, entries, args.relay_ref))
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
