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

The Instagram feed card is shown both ways, crop (fill the 4:5 card) and fit (the whole picture over a blurred copy,
the entry's `"feed": "fit"`), the entry's own choice marked, with the set-major.py command that switches it.

Each card shows that platform's caption: an entry with a `description` carries the whole update as plain text, links
kept on Facebook and shown as their text, or the `mentions` handle, on Instagram and TikTok.

An animated picture's feed video carries its song, as the relay posts it (socials-music.js): the update's vibe (from
its tags, or --vibe) and a track from that vibe, picked the way the relay picks (seeded, so a rerun shows the same
one), or the one named with --music. The card shows the song, where it starts, the vibe's other tracks and their
chances, and the credit line the captions carry. On the card, ▶ plays any track's 12 s preview from where it starts
(the relay's own previewClip), the vibe menu lists every other vibe's tracks, Use re-renders that update's videos
with the track, and Pin to update runs set-social-music.py for it (the live relay, after a confirm), so the relay
posts that exact song. Pin before the release ships. Tracks come from Mojang's asset server once (every one is fetched
in the background at start) and are kept, with their previews, in
~/.cache/dungeontrain/mc-music/.

Usage:
  python3 scripts/release-notes/preview-social-posts.py [--version 0.1746.0] [--port 8766]
                                                        [--relay ~/Projects/dp-relay] [--relay-ref origin/main]
                                                        [--music <entry-id>=<track-id>] [--vibe <entry-id>=<vibe>]
                                                        [--id <entry-id>]

--id previews that entry (repeatable) whether or not it has been released, in place of the unreleased set: the posts
a released update will still make, after changing its picture or feed fit with set-major.py.

Needs node and a dp-relay clone with its node_modules installed. Keeps serving until stopped. Exits 1 when no
unreleased major or notable entry has a picture.
"""
import argparse
import html
import http.server
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import threading
import urllib.parse

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

/** Every vibe and its tracks as the relay's Socials page lists them, for the song card's vibe menu. */
function allVibes() {
  return music.describe().vibes.map((v) => ({ id: v.id, label: v.label || v.name || v.id,
    tracks: v.tracks.map((x) => ({ id: x.id, title: x.title, composer: x.composer, chance: x.chance, start: x.start })) }));
}

/** An entry's own clip as the relay posts it: every video built from the clip, its frame the cover. */
async function clipPosts(e, save, log) {
  if (!card.buildClipVideo) throw new Error(`${e.id}: this dp-relay ref can't build clip videos (no buildClipVideo); pass --relay-ref`);
  // A format's own clip (the entry's `videos`), else the one `video`, as the relay picks (socials-updates.js clipFor).
  const clipOf = (format) => {
    const file = (e.clipPaths && e.clipPaths[format]) || e.clipPath;
    return file ? fs.readFileSync(file) : null;
  };
  const tune = song(e);
  const fetched = await music.trackFile(tune.t, { log });
  if (!fetched) throw new Error(`${e.id}: could not fetch ${tune.t.id} from Mojang's asset server`);
  const got = Number.isFinite(e.start) ? { ...fetched, start: e.start } : fetched;
  const track = { id: tune.t.id, title: tune.t.title, composer: tune.t.composer };
  log(`clip, song ${track.title} by ${track.composer} (${tune.vibe}) under it, from ${got.start}s`);
  for (const [format, name] of [['feed', 'feed-video'], ['story', 'story-video'], ['tiktok', 'tiktok-video']]) {
    const clip = clipOf(format);
    if (!clip) { log(`no ${format} clip and no video: the relay posts the picture there`); continue; }
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
    const hasClip = !!(e.clipPath || e.clipPaths);
    if (hasClip) {
      ({ tune, track } = await clipPosts(e, save, log));
    } else if (card.isAnimated(bytes)) {
      tune = song(e);
      const fetched = await music.trackFile(tune.t, { log });
      if (!fetched) throw new Error(`${e.id}: could not fetch ${tune.t.id} from Mojang's asset server`);
      const got = Number.isFinite(e.start) ? { ...fetched, start: e.start } : fetched;
      track = { id: tune.t.id, title: tune.t.title, composer: tune.t.composer };
      log(`song ${track.title} by ${track.composer} (${tune.vibe}), from ${got.start}s`);
      const v = await card.buildUpdateVideo(e, bytes, { log, music: { ...got, track } });
      if (v) save('feed-video.mp4', v.mp4);
      tune.start = got.start;
    }
    if (!hasClip) {
      save('facebook.jpg', await card.pictureJpeg(bytes, { log }));
      // the feed card both ways (crop, fit), when this relay ref can fit; instagram.jpg is the entry's own choice
      const band = { top: 1000, h: 350 };
      const canFit = card.updateGraph('feed', band, { fit: true }) !== card.updateGraph('feed', band);
      for (const fit of canFit ? [false, true] : [false]) {
        const jpeg = await card.buildUpdateCard(e, bytes, { format: 'feed', fit, log });
        save(`instagram-${fit ? 'fit' : 'crop'}.jpg`, jpeg);
        if (fit === (canFit && e.feed === 'fit')) save('instagram.jpg', jpeg);
      }
      save('story.jpg', await card.buildUpdateCard(e, bytes, { format: 'story', isNew: true, log }));
    }
    done.push({ id: e.id, title: e.title, files,
      music: tune && { clip: hasClip, id: tune.t.id, title: tune.t.title, composer: tune.t.composer, start: tune.start, vibe: tune.vibe,
        vibeFrom: tune.vibeFrom, chosen: tune.chosen, others: tune.others, credit: music.credit(track), vibes: allVibes() },
      captions: Object.fromEntries(['facebook', 'instagram', 'tiktok'].map((p) => [p, updates.caption(e, p, track)])) });
  }
  fs.writeFileSync(path.join(out, 'posts.json'), JSON.stringify(done));
  process.exit(0);
})().catch((err) => { console.error(err); process.exit(1); });
"""

# Run inside the relay copy: the relay's own 12 s preview of one track, from where it starts. argv: track id, out file.
LISTEN_DRIVER = r"""
'use strict';
const fs = require('fs');
const music = require('./socials-music');
const [id, out] = process.argv.slice(2);
music.previewClip(id).then((buf) => {
  if (!buf) { console.error(`no preview for ${id}`); process.exit(1); }
  fs.writeFileSync(out, buf);
  process.exit(0);
}).catch((err) => { console.error(err.message || err); process.exit(1); });
"""

# The song card's buttons: one track plays at a time; Use and Pin post to the preview server and report back.
SONG_SCRIPT = r"""
<script>
(() => {
  const audio = new Audio();
  let playing = null;
  // Messages go below the track tables and only errors and results are written, so the buttons never move.
  const say = (card, msg) => { card.querySelector('.status').textContent = msg; };
  const rowOf = (b) => b.closest('.bar');
  const bar = (row, p) => row.style.setProperty('--p', `${p}%`);
  const stop = () => {
    if (!playing) return;
    const row = rowOf(playing);
    row.classList.remove('loading');
    bar(row, 0);
    playing.textContent = '▶';
    playing = null;
  };
  audio.addEventListener('ended', stop);
  audio.addEventListener('playing', () => { if (playing) rowOf(playing).classList.remove('loading'); });
  audio.addEventListener('timeupdate', () => {
    if (playing && audio.duration) bar(rowOf(playing), Math.min(100, (audio.currentTime / audio.duration) * 100));
  });
  try { const y = sessionStorage.getItem('dt-preview-y'); if (y) { scrollTo(0, +y); sessionStorage.removeItem('dt-preview-y'); } }
  catch (e) { /* no storage: start at the top */ }
  document.addEventListener('change', (ev) => {
    const menu = ev.target.closest('select[data-vibes]');
    if (!menu) return;
    menu.closest('.song').querySelectorAll('.tracks[data-vibe]').forEach((t) => { t.hidden = t.dataset.vibe !== menu.value; });
  });
  document.addEventListener('click', async (ev) => {
    const b = ev.target.closest('button[data-act]');
    if (!b) return;
    const card = b.closest('.song');
    const { act, track, entry, title } = b.dataset;
    if (act === 'play') {
      if (playing === b) { audio.pause(); stop(); return; }
      audio.pause(); stop();
      say(card, '');
      playing = b; b.textContent = '■';
      rowOf(b).classList.add('loading');
      audio.src = `/api/listen/${encodeURIComponent(track)}.m4a`;
      try { await audio.play(); }
      catch (e) { if (playing === b) { stop(); say(card, `Could not play ${title}: ${e.message}`); } }
      return;
    }
    if (act === 'pin' && !confirm(`Pin ${title} to this update on the live relay? Its posts will use this song.`)) return;
    audio.pause(); stop();
    say(card, '');
    const row = rowOf(b);
    const buttons = card.querySelectorAll('button, select');
    buttons.forEach((x) => { x.disabled = true; });
    row.classList.add('loading');
    try {
      const r = await fetch(`/api/${act}`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ entry, track }) });
      const j = await r.json().catch(() => ({}));
      if (!r.ok || j.error) throw new Error(j.error || `HTTP ${r.status}`);
      if (act === 'use') {
        try { sessionStorage.setItem('dt-preview-y', String(scrollY)); } catch (e) { /* reload at the top */ }
        location.reload();
        return;
      }
      say(card, `Pinned. ${j.output || ''}`);
    } catch (e) {
      say(card, `${act === 'use' ? 'Render' : 'Pin'} failed: ${e.message}`);
    } finally {
      row.classList.remove('loading');
      buttons.forEach((x) => { x.disabled = false; });
    }
  });
})();
</script>
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


def split_start(pick: str) -> tuple[str, float | None]:
    """`track@49.7` as (track, 49.7): where the song starts, as set-social-music.py --start pins it. Plain `track`
    starts where the relay would."""
    track, sep, start = pick.partition("@")
    if not sep:
        return track, None
    try:
        return track, float(start)
    except ValueError:
        raise ValueError(f"--music wants <entry-id>=<track>[@<seconds>], got start '{start}'")


def local_file(url: str) -> str:
    """The file of that name in this checkout's release-notes images folder, or '' for no url."""
    return os.path.join(IMAGES_DIR, url.rsplit("/", 1)[-1]) if url else ""


def clip_fields(e: dict) -> dict:
    """`clipPath`, `clipPaths` (a clip per format, the entry's `videos`) and `clipCover` for an entry with its own
    clip(s). A clip named but not in the images folder is an error: previewing the picture instead would show posts
    the relay won't make."""
    def local_clip(url: str) -> str:
        local = local_file(url)
        if not os.path.exists(local):
            raise ValueError(f"{e['id']}: its video {url} is not in {IMAGES_DIR}/")
        return os.path.abspath(local)

    video = e.get("video") or ""
    videos = e.get("videos") or {}
    if not video and not videos:
        return {}
    out: dict = {}
    if video:
        out["clipPath"] = local_clip(video)
    if videos:
        out["clipPaths"] = {fmt: local_clip(url) for fmt, url in videos.items()}
    if e.get("video_cover") is not None:
        out["clipCover"] = e["video_cover"]
    return out


def text_fields(e: dict) -> dict:
    """`description` and `mentions` as the relay keeps them: its captions carry the whole update, each platform's way."""
    return {k: e[k] for k in ("description", "mentions") if e.get(k)}


def postable(entries: list[dict], version: str, ids: list[str] | None = None) -> list[dict]:
    """The unreleased major/notable entries with a local picture, shaped as the relay's update entries; with `ids`,
    those entries instead, released or not (a released one keeps its own version and release time)."""
    if ids:
        missing = [i for i in ids if changelog_io.find_entry(entries, i) is None]
        if missing:
            raise ValueError(f"no changelog entry with id {', '.join(missing)}")
        chosen = [e for e in entries if e["id"] in ids]
    else:
        chosen = changelog_io.unreleased_entries(entries)
    out = []
    for e in chosen:
        local = local_file(e.get("image") or "")
        if not e.get("major") or not local or not os.path.exists(local):
            continue
        released = bool(e.get("released_in") and e.get("released_at"))
        out.append({
            "id": e["id"], "title": e["title"], "summary": e.get("summary", ""), "tags": e.get("tags", []),
            "version": e["released_in"] if released else f"v{version}",
            "released_at": e["released_at"] if released else e.get("date", "") + "T00:00:00Z",
            "notable": bool(e.get("notable")), "milestone": bool(e.get("milestone")),
            "feed": "fit" if e.get("feed") == "fit" else "crop",
            "picturePath": os.path.abspath(local),
            **clip_fields(e),
            **text_fields(e),
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


def feed_fit_block(post: dict, choice: str) -> str:
    """The Instagram feed card both ways, crop and fit, side by side: the entry's own choice marked, and the command
    that switches to the other. Empty when the relay ref can't fit (one variant only)."""
    files = post["files"]
    if "instagram-fit" not in files:
        return ""
    cells = []
    for variant in ("crop", "fit"):
        on = variant == choice
        label = f"{variant.capitalize()}{' (this entry)' if on else ''}"
        cells.append(f'<figure class="{"on" if on else ""}"><img src="{files["instagram-" + variant]}" alt="">'
                     f"<figcaption>{label}</figcaption></figure>")
    other = "fit" if choice == "crop" else "crop"
    switch = f"python3 scripts/release-notes/set-major.py --id {post['id']} --feed {other}"
    return (f"<p class=k>Feed card, crop or fit:</p><div class=pair>{''.join(cells)}</div>"
            f"<p class=k>Switch to {other}:</p><pre>{html.escape(switch)}</pre>")


def tiktok_block(post: dict) -> str:
    """The TikTok card: its caption (Brennan pastes it when he posts the draft), and the video for an entry with its
    own clip."""
    tiktok = post["files"].get("tiktok-video")
    video = f'<video src="{tiktok}" controls loop playsinline></video>' if tiktok else ""
    caption = post.get("captions", {}).get("tiktok", "")
    return f"<div class=card><h3>TikTok</h3>{video}<pre>{html.escape(caption)}</pre></div>"


def music_block(post: dict) -> str:
    """The song card: what plays, where from, the vibe's other tracks, and how to pin it."""
    m = post.get("music")
    if not m:
        return ""
    start = int(m["start"] or 0)
    how = "picked here (Use) or with --music" if m["chosen"] else "the relay's pick from the vibe (seeded)"
    vibe_from = "chosen with --vibe" if m["vibeFrom"] == "chosen" else "from the update's tags"
    entry = html.escape(post["id"], quote=True)
    pin = f"python3 scripts/release-notes/set-social-music.py --entry {post['id']} --track {m['id']}"
    return (
        "<div class='card song'><h3>Song</h3>"
        f"<p class='big bar'>{_play_button(m['id'], m['title'])} {html.escape(m['title'])} "
        f"<span class=k>by {html.escape(m['composer'])}, from {start // 60}:{start % 60:02d}</span></p>"
        f"<p class=k>{html.escape(how)}. Vibe: <b>{html.escape(m['vibe'])}</b>, {vibe_from}. "
        + ("Play the videos above with sound: the clip's own sound, the song 10 dB under it.</p>"
           if m.get("clip") else "Play the Instagram video above with sound to hear it.</p>")
        + f"<p class=k>Caption credit: {html.escape(m['credit'])}</p>"
        f"<p class=bar><button data-act=pin data-entry=\"{entry}\" data-track=\"{html.escape(m['id'], quote=True)}\" "
        f"data-title=\"{html.escape(m['title'], quote=True)}\">Pin to update</button> "
        "<span class=k>sets it on the live relay, as the command below does</span></p>"
        f"<pre>{html.escape(pin)}</pre>"
        f"{_vibe_tables(post['id'], m)}<p class='k status' aria-live=polite></p></div>"
    )


def _play_button(track_id: str, title: str) -> str:
    return (f"<button class=play data-act=play data-track=\"{html.escape(track_id, quote=True)}\" "
            f"data-title=\"{html.escape(title, quote=True)}\" aria-label=\"Play {html.escape(title, quote=True)}\">▶</button>")


def song_vibes(m: dict) -> list[dict]:
    """Every vibe with its tracks; a driver from before the vibe menu (no `vibes`) gives the update's own vibe only."""
    return m.get("vibes") or [{"id": m["vibe"], "label": m["vibe"], "tracks": m["others"]}]


def _vibe_tables(entry_id: str, m: dict) -> str:
    """The vibe menu and one table per vibe (only the update's own shown at first): ▶ to listen, Use to re-render."""
    vibes = song_vibes(m)
    entry = html.escape(entry_id, quote=True)
    options = "".join(
        f"<option value=\"{html.escape(v['id'], quote=True)}\"{' selected' if v['id'] == m['vibe'] else ''}>"
        f"{html.escape(v['label'])}{' (this update)' if v['id'] == m['vibe'] else ''}</option>" for v in vibes)
    tables = []
    for v in vibes:
        # Grid rows, not a <table>: a row's background (the loading and playback bar) paints across the whole row.
        rows = "".join(
            f"<div class='row bar{' on' if t['id'] == m['id'] else ''}'><span>{_play_button(t['id'], t['title'])}</span>"
            f"<span>{html.escape(t['title'])}</span><span>{html.escape(t['composer'])}</span><span>{t['chance']}%</span>"
            "<span>" + ("in use" if t["id"] == m["id"] else
                        f"<button data-act=use data-entry=\"{entry}\" data-track=\"{html.escape(t['id'], quote=True)}\" "
                        f"data-title=\"{html.escape(t['title'], quote=True)}\">Use</button>") + "</span></div>"
            for t in v["tracks"])
        hidden = "" if v["id"] == m["vibe"] else " hidden"
        tables.append(f"<div class=tracks data-vibe=\"{html.escape(v['id'], quote=True)}\"{hidden}>"
                      "<div class='row head'><span></span><span>Track</span><span>Composer</span><span>Chance</span>"
                      f"<span></span></div>{rows}</div>")
    return (f"<p class=k>Listen to any track, or Use one to re-render this update's videos with it:</p>"
            f"<p><select data-vibes aria-label=Vibe>{options}</select></p>{''.join(tables)}")


def track_ids(posts: list[dict]) -> set[str]:
    """Every track id the song cards list: the only ids the server will play or use."""
    return {t["id"] for p in posts if p.get("music") for v in song_vibes(p["music"]) for t in v["tracks"]}


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
            f"{feed_fit_block(post, e.get('feed', 'crop'))}"
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
        ".tracks{font-size:13px}.tracks[hidden]{display:none}"
        ".row{display:grid;grid-template-columns:40px minmax(0,1fr) minmax(0,1fr) 54px 46px;gap:4px;align-items:center;"
        "padding:4px 6px;border-bottom:1px solid var(--line);border-radius:6px}.row span{overflow-wrap:break-word;min-width:0}"
        ".row.head{font-weight:600}.row.on{font-weight:600}"
        "button,select{font:inherit;font-size:13px;color:var(--ink);background:var(--bg);border:1px solid var(--line);"
        "border-radius:6px;padding:3px 9px;cursor:pointer}button:disabled,select:disabled{opacity:.5;cursor:wait}"
        "button.play{min-width:32px}.status{min-height:1.5em;margin:8px 0 0}"
        # Loading and playback show as a bar behind the row, so nothing above the buttons moves.
        ".song .bar{--p:0%;background:linear-gradient(90deg,rgba(140,196,94,.28) var(--p),transparent var(--p))}"
        ".song .loading{background:linear-gradient(90deg,transparent 0,rgba(140,196,94,.3) 50%,transparent 100%)"
        " 0 0/40% 100% no-repeat;animation:dtbar 1.1s linear infinite}"
        "@keyframes dtbar{from{background-position:-40% 0}to{background-position:140% 0}}"
        ".pair{display:grid;grid-template-columns:1fr 1fr;gap:8px}.pair figure{margin:0;padding:3px;border-radius:10px;"
        "border:2px solid transparent}.pair figure.on{border-color:#8cc45e}figcaption{font-size:13px;text-align:center}"
        "pre{white-space:pre-wrap;font:13px/1.45 system-ui;background:var(--bg);border-radius:8px;padding:10px}</style>"
        f"<h1>v{html.escape(version)}: social posts</h1>"
        f"<p class=k>Rendered by dp-relay's own code at {html.escape(relay_ref)}. Hashtags rotate by the update's place in the "
        "live list, so the five shown here can differ from the ones that post.</p>" + "".join(cards) + SONG_SCRIPT
    )


SET_SOCIAL_MUSIC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "set-social-music.py")
MAX_BODY = 4096
# Outside every worktree: the downloaded tracks (the relay's mc-music folder) and their 12 s previews.
MUSIC_CACHE = os.path.expanduser("~/.cache/dungeontrain/mc-music")


def track_start(posts: list[dict], track_id: str) -> float | None:
    """Where the song card says a track starts, or None if it doesn't say."""
    for p in posts:
        for v in song_vibes(p.get("music") or {"vibe": "", "others": []}):
            for t in v["tracks"]:
                if t["id"] == track_id and t.get("start") is not None:
                    return t["start"]
    return None


def preview_file(track_id: str, start: float | None) -> str:
    """A track's cached 12 s preview; a new start point makes a new file."""
    at = "" if start is None else f"@{start:g}"
    return os.path.join(MUSIC_CACHE, "previews", f"{track_id}{at}.m4a")


class Preview:
    """The rendered site and what it was rendered from, so the song card can re-render one update or play a track."""

    def __init__(self, target: str, relay_copy: str, site: str, env: dict, entries: list[dict], version: str,
                 relay_ref: str) -> None:
        self.target, self.relay_copy, self.site, self.env = target, relay_copy, site, env
        self.entries, self.version, self.relay_ref = entries, version, relay_ref
        self.posts: list[dict] = []
        self.lock = threading.Lock()
        self.lock_guard = threading.Lock()
        self.listen_locks: dict[str, threading.Lock] = {}

    def render(self, entries: list[dict]) -> list[dict]:
        """Run the relay's builders over `entries`; their posts replace any earlier ones of the same id."""
        listing = os.path.join(self.target, "entries.json")
        with open(listing, "w", encoding="utf-8") as f:
            json.dump(entries, f)
        out = os.path.join(self.target, "render")
        os.makedirs(out, exist_ok=True)
        subprocess.run(["node", "preview-driver.js", listing, out], cwd=self.relay_copy, env=self.env, check=True)
        with open(os.path.join(out, "posts.json"), encoding="utf-8") as f:
            fresh = json.load(f)
        for e in entries:  # move each update's files into the site, replacing its old ones
            src, dst = os.path.join(out, e["id"]), os.path.join(self.site, e["id"])
            if os.path.isdir(src):
                shutil.rmtree(dst, ignore_errors=True)
                shutil.move(src, dst)
        self.posts = list({**{p["id"]: p for p in self.posts}, **{p["id"]: p for p in fresh}}.values())
        with open(os.path.join(self.site, "index.html"), "w", encoding="utf-8") as f:
            f.write(page(self.version, self.posts, self.entries, self.relay_ref))
        return fresh

    def entry(self, entry_id: str) -> dict:
        found = next((e for e in self.entries if e["id"] == entry_id), None)
        if found is None:
            raise ValueError(f"no update '{entry_id}' in this preview")
        return found

    def check_track(self, track_id: str) -> str:
        if track_id not in track_ids(self.posts):
            raise ValueError(f"no track '{track_id}' on the song card")
        return track_id

    def use(self, entry_id: str, track_id: str) -> None:
        """Re-render one update's posts with `track_id` as its song (from that track's own start)."""
        e, track = self.entry(entry_id), self.check_track(track_id)
        with self.lock:
            changed = {k: v for k, v in e.items() if k != "start"}
            changed["track"] = track
            self.entries = [changed if x["id"] == entry_id else x for x in self.entries]
            self.render([changed])

    def listen(self, track_id: str) -> bytes:
        """The relay's 12 s preview of a track. Kept in MUSIC_CACHE/previews beside the downloaded tracks, keyed by
        where the track starts, so it is made once on this machine, not once per run."""
        track = self.check_track(track_id)
        cache = preview_file(track, track_start(self.posts, track))
        with self.listen_lock(track):
            if not os.path.exists(cache):
                os.makedirs(os.path.dirname(cache), exist_ok=True)
                part = f"{cache}.part"
                run = subprocess.run(["node", "preview-listen.js", track, part], cwd=self.relay_copy, env=self.env,
                                     capture_output=True, text=True, timeout=120)
                if run.returncode != 0:
                    raise RuntimeError(run.stderr.strip() or f"no preview for {track}")
                os.replace(part, cache)  # whole or absent: a killed run never leaves half a file behind
        with open(cache, "rb") as f:
            return f.read()

    def listen_lock(self, track: str) -> threading.Lock:
        with self.lock_guard:
            return self.listen_locks.setdefault(track, threading.Lock())

    def prefetch(self) -> None:
        """Fetch every track on the card in the background, so ▶ never waits on Mojang's asset server."""
        missing = sorted(t for t in track_ids(self.posts)
                         if not os.path.exists(preview_file(t, track_start(self.posts, t))))
        if missing:
            print(f"Fetching {len(missing)} track(s) in the background into {MUSIC_CACHE}", flush=True)
        failed = []
        for t in missing:
            try:
                self.listen(t)
            except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as e:
                failed.append(f"{t} ({e})")
        if failed:
            print(f"Could not fetch: {', '.join(failed)}", file=sys.stderr, flush=True)
        elif missing:
            print("Every track is on this machine now.", flush=True)

    def pin(self, entry_id: str, track_id: str) -> str:
        """Pin the song on the live relay with set-social-music.py, exactly as running it by hand does."""
        self.entry(entry_id)
        track = self.check_track(track_id)
        run = subprocess.run([sys.executable, SET_SOCIAL_MUSIC, "--entry", entry_id, "--track", track],
                             capture_output=True, text=True, timeout=180)
        if run.returncode != 0:
            raise RuntimeError(run.stderr.strip() or run.stdout.strip() or "set-social-music.py failed")
        return run.stdout.strip()


def handler_for(preview: Preview) -> type:
    """Static files from the site, plus the song card's /api/listen, /api/use and /api/pin."""

    class Handler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs) -> None:
            super().__init__(*args, directory=preview.site, **kwargs)

        def end_headers(self) -> None:
            self.send_header("Cache-Control", "no-store")  # a re-render keeps the file names
            super().end_headers()

        def reply(self, status: int, body: bytes, kind: str) -> None:
            self.send_response(status)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def reply_json(self, status: int, obj: dict) -> None:
            self.reply(status, json.dumps(obj).encode(), "application/json")

        def do_GET(self) -> None:
            if not self.path.startswith("/api/listen/"):
                super().do_GET()
                return
            name = urllib.parse.unquote(self.path[len("/api/listen/"):].split("?", 1)[0])
            try:
                self.reply(200, preview.listen(name.removesuffix(".m4a")), "audio/mp4")
            except ValueError as e:
                self.reply_json(404, {"error": str(e)})
            except (RuntimeError, OSError, subprocess.TimeoutExpired) as e:
                print(f"listen {name} failed: {e}", file=sys.stderr)
                self.reply_json(500, {"error": f"could not make the preview: {e}"})

        def do_POST(self) -> None:
            action = {"/api/use": preview.use, "/api/pin": preview.pin}.get(self.path)
            if action is None:
                self.reply_json(404, {"error": "no such action"})
                return
            try:
                length = int(self.headers.get("Content-Length") or 0)
                if not 0 < length <= MAX_BODY:
                    raise ValueError("bad request body")
                body = json.loads(self.rfile.read(length))
                entry, track = body.get("entry"), body.get("track")
                if not isinstance(entry, str) or not isinstance(track, str):
                    raise ValueError("entry and track are needed")
                output = action(entry, track)
                self.reply_json(200, {"ok": True, "output": output or ""})
            except (ValueError, json.JSONDecodeError) as e:
                self.reply_json(400, {"error": str(e)})
            except (RuntimeError, OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
                print(f"{self.path} failed: {e}", file=sys.stderr)
                self.reply_json(500, {"error": str(e)})

    return Handler


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--version", help="version to show; defaults to mod_version")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--relay", default=os.path.expanduser("~/Projects/dp-relay"))
    parser.add_argument("--relay-ref", default="origin/main",
                        help="dp-relay commit to render with (default origin/main; a branch to preview its changes)")
    parser.add_argument("--music", action="append", default=[], metavar="ENTRY=TRACK[@SECONDS]",
                        help="preview this track on that entry's video, from SECONDS if given (repeatable)")
    parser.add_argument("--id", action="append", default=[], dest="ids", metavar="ENTRY",
                        help="preview this entry, released or not, in place of the unreleased set (repeatable)")
    parser.add_argument("--vibe", action="append", default=[], metavar="ENTRY=VIBE",
                        help="pick the song from this vibe instead of the one the tags give (repeatable)")
    args = parser.parse_args(argv)
    version = args.version or read_version()
    try:
        entries = postable(changelog_io.load_changelog()["entries"], version, args.ids or None)
        tracks, vibes = parse_picks(args.music, "--music"), parse_picks(args.vibe, "--vibe")
    except ValueError as e:
        print(e, file=sys.stderr)
        return 2
    for e in entries:
        if e["id"] in tracks:
            e["track"], e["start"] = split_start(tracks[e["id"]])
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
            with open(os.path.join(relay_copy, "preview-listen.js"), "w", encoding="utf-8") as f:
                f.write(LISTEN_DRIVER)
            env = {**os.environ, "RELAY_DB_DIR": os.path.join(target, "db")}
            os.makedirs(env["RELAY_DB_DIR"])
            # The relay keeps tracks in <RELAY_DB_DIR>/mc-music; keep them between runs instead of refetching.
            os.makedirs(MUSIC_CACHE, exist_ok=True)
            os.symlink(MUSIC_CACHE, os.path.join(env["RELAY_DB_DIR"], "mc-music"))
            preview = Preview(target, relay_copy, site, env, entries, version, args.relay_ref)
            posts = preview.render(entries)
            with http.server.ThreadingHTTPServer(("127.0.0.1", args.port), handler_for(preview)) as server:
                print(f"v{version}, {len(posts)} post(s)")
                print(f"Social posts: http://127.0.0.1:{args.port}/", flush=True)
                threading.Thread(target=preview.prefetch, name="prefetch-tracks", daemon=True).start()
                server.serve_forever()
    except KeyboardInterrupt:
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as e:
        print(f"social preview failed: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
