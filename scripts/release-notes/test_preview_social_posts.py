#!/usr/bin/env python3
"""Unit tests for preview-social-posts.py's song card, its --music / --vibe picks and an entry's own clip.

Run: python3 scripts/release-notes/test_preview_social_posts.py   (or via pytest)
"""
import importlib.util
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
spec = importlib.util.spec_from_file_location("preview_social_posts", os.path.join(HERE, "preview-social-posts.py"))
preview = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preview)

POST = {
    "id": "drifting-engine-block",
    "files": {"feed-video": "x/feed-video.mp4", "instagram": "x/instagram.jpg"},
    "music": {
        "id": "beginning_2", "title": "Beginning 2", "composer": "C418", "start": 58, "vibe": "adventure",
        "vibeFrom": "tags", "chosen": False, "credit": "Music: Beginning 2 by C418. Music courtesy of Mojang Studios.",
        "others": [{"id": "beginning_2", "title": "Beginning 2", "composer": "C418", "chance": 12.5},
                   {"id": "moog_city_2", "title": "Moog City 2", "composer": "C418", "chance": 14.1}],
    },
}


class Picks(unittest.TestCase):
    def test_entry_value_pairs(self):
        self.assertEqual(preview.parse_picks(["a=pigstep", "b=sweden"], "--music"), {"a": "pigstep", "b": "sweden"})

    def test_a_bad_pair_is_refused(self):
        for bad in ("pigstep", "=pigstep", "a="):
            with self.assertRaises(ValueError):
                preview.parse_picks([bad], "--music")


class SongStart(unittest.TestCase):
    def test_track_with_and_without_a_start(self):
        self.assertEqual(preview.split_start("lava_chicken@49.7"), ("lava_chicken", 49.7))
        self.assertEqual(preview.split_start("pigstep"), ("pigstep", None))

    def test_a_bad_start_is_refused(self):
        with self.assertRaises(ValueError):
            preview.split_start("pigstep@soon")


class SongCard(unittest.TestCase):
    def test_shows_the_song_its_start_and_how_to_pin_it(self):
        html = preview.music_block(POST)
        self.assertIn("Beginning 2", html)
        self.assertIn("from 0:58", html)
        self.assertIn("set-social-music.py --entry drifting-engine-block --track beginning_2", html)
        self.assertIn("Music courtesy of Mojang Studios", html)
        self.assertIn("Moog City 2", html, "the vibe's other tracks are listed")

    def test_no_song_no_card(self):
        self.assertEqual(preview.music_block({**POST, "music": None}), "")

    def test_the_video_plays_with_sound(self):
        video = preview.media_block(POST, "instagram")
        self.assertIn("controls", video)
        self.assertNotIn("muted", video)


CLIP_POST = {
    **POST,
    "files": {"feed-video": "x/feed-video.mp4", "instagram": "x/instagram.jpg", "story-video": "x/story-video.mp4",
              "story": "x/story.jpg", "tiktok-video": "x/tiktok-video.mp4"},
    "music": {**POST["music"], "clip": True},
}


class Clip(unittest.TestCase):
    def setUp(self):
        self.cwd = os.getcwd()
        self.dir = tempfile.mkdtemp()
        os.chdir(self.dir)
        os.makedirs(preview.IMAGES_DIR)
        for name in ("totems.gif", "totems.mp4"):
            with open(os.path.join(preview.IMAGES_DIR, name), "wb") as f:
                f.write(b"x")

    def tearDown(self):
        os.chdir(self.cwd)

    def entry(self, **extra):
        base = "https://raw.githubusercontent.com/bh679/dungeon-train-mc/main/.github/release-notes/images/"
        return {"id": "totems-with-a-catch", "title": "Dungeon Totems of Undying", "summary": "S", "major": True,
                "released": False, "date": "2026-11-13", "image": base + "totems.gif", **extra}

    def test_an_entry_with_a_clip_carries_the_local_clip_and_cover(self):
        base = "https://raw.githubusercontent.com/bh679/dungeon-train-mc/main/.github/release-notes/images/"
        [e] = preview.postable([self.entry(video=base + "totems.mp4", video_cover=4.5)], "0.1788.0")
        self.assertEqual(e["clipPath"], os.path.abspath(os.path.join(preview.IMAGES_DIR, "totems.mp4")))
        self.assertEqual(e["clipCover"], 4.5)

    def test_without_a_clip_nothing_changes(self):
        [e] = preview.postable([self.entry()], "0.1788.0")
        self.assertNotIn("clipPath", e)
        self.assertNotIn("clipCover", e)

    def test_description_and_mentions_reach_the_relay_entry(self):
        mentions = {"AshSeven": {"instagram": "@ashyseven7"}}
        e = preview.postable([self.entry(description="All of it.", mentions=mentions)], "0.1788.0")[0]
        self.assertEqual((e["description"], e["mentions"]), ("All of it.", mentions))
        self.assertNotIn("mentions", preview.postable([self.entry()], "0.1788.0")[0])

    def test_a_clip_missing_from_the_images_folder_is_an_error(self):
        with self.assertRaises(ValueError):
            preview.postable([self.entry(video="https://x.test/gone.mp4")], "0.1788.0")

    def test_the_story_and_tiktok_show_the_clip_videos(self):
        self.assertIn('src="x/story-video.mp4"', preview.media_block(CLIP_POST, "story", "story-video"))
        self.assertIn('src="x/tiktok-video.mp4"', preview.tiktok_block(CLIP_POST))
        self.assertNotIn("<video", preview.tiktok_block({**POST, "captions": {"tiktok": "Hi @ash"}}), "no clip: caption only")
        self.assertIn("Hi @ash", preview.tiktok_block({**POST, "captions": {"tiktok": "Hi @ash"}}))
        self.assertIn("10 dB under it", preview.music_block(CLIP_POST))
        page = preview.page("0.1788.0", [{**CLIP_POST, "title": "T", "captions": {"facebook": "f", "instagram": "i"}}],
                            [{"id": POST["id"], "title": "T", "summary": "S"}], "dev/x")
        self.assertIn("TikTok", page)
        self.assertIn("at dev/x", page)


VIBES_POST = {
    **POST,
    "music": {**POST["music"], "vibes": [
        {"id": "adventure", "label": "Adventure", "tracks": POST["music"]["others"]},
        {"id": "dark", "label": "Dark", "tracks": [{"id": "rubedo", "title": "Rubedo", "composer": "Lena Raine",
                                                     "chance": 16.6}]},
    ]},
}


class SongPicker(unittest.TestCase):
    def test_every_track_plays_and_others_can_be_used(self):
        card = preview.music_block(VIBES_POST)
        for track in ("beginning_2", "moog_city_2", "rubedo"):
            self.assertIn(f'data-act=play data-track="{track}"', card)
        self.assertIn('data-act=use data-entry="drifting-engine-block" data-track="moog_city_2"', card)
        self.assertNotIn('data-act=use data-entry="drifting-engine-block" data-track="beginning_2"', card,
                         "the song in use has no Use button")
        self.assertIn("in use", card)

    def test_pin_button_pins_the_current_song(self):
        self.assertIn('data-act=pin data-entry="drifting-engine-block" data-track="beginning_2"',
                      preview.music_block(VIBES_POST))

    def test_vibe_menu_shows_the_updates_vibe_first_and_hides_the_rest(self):
        card = preview.music_block(VIBES_POST)
        self.assertIn('<option value="adventure" selected>Adventure (this update)</option>', card)
        self.assertIn('<div class=tracks data-vibe="adventure">', card)
        self.assertIn('<div class=tracks data-vibe="dark" hidden>', card)

    def test_previews_are_kept_outside_the_worktree_keyed_by_start(self):
        posts = [{**VIBES_POST, "music": {**VIBES_POST["music"], "vibes": [
            {"id": "dark", "label": "Dark", "tracks": [{"id": "rubedo", "title": "R", "composer": "L", "chance": 1,
                                                         "start": 209}]}]}}]
        self.assertEqual(preview.track_start(posts, "rubedo"), 209)
        self.assertIsNone(preview.track_start(posts, "pigstep"))
        path = preview.preview_file("rubedo", 209)
        self.assertTrue(path.startswith(os.path.expanduser("~/.cache/dungeontrain/mc-music/previews/")))
        self.assertTrue(path.endswith("rubedo@209.m4a"))
        self.assertNotEqual(path, preview.preview_file("rubedo", 49.7), "a new start is a new preview")

    def test_an_old_driver_without_vibes_lists_the_updates_vibe(self):
        self.assertEqual(preview.track_ids([POST]), {"beginning_2", "moog_city_2"})
        self.assertEqual(preview.track_ids([VIBES_POST]), {"beginning_2", "moog_city_2", "rubedo"})

    def test_the_page_carries_the_button_script(self):
        page = preview.page("0.1.0", [{**VIBES_POST, "title": "T", "captions": {"facebook": "f", "instagram": "i"}}],
                            [{"id": POST["id"], "title": "T", "summary": "S"}])
        self.assertIn("/api/listen/", page)
        self.assertIn("confirm(", page, "pinning asks first")


class PickerServer(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        entry = {"id": POST["id"], "title": "T", "summary": "S", "start": 12.0}
        self.p = preview.Preview(self.dir, self.dir, self.dir, {}, [entry], "0.1.0", "origin/main")
        self.p.posts = [VIBES_POST]
        self.rendered = []
        self.p.render = lambda entries: self.rendered.append(entries) or []

    def test_use_re_renders_that_update_with_the_track_from_its_own_start(self):
        self.p.use(POST["id"], "rubedo")
        [[e]] = self.rendered
        self.assertEqual(e["track"], "rubedo")
        self.assertNotIn("start", e, "the old track's start doesn't carry over")
        self.assertEqual(self.p.entries[0]["track"], "rubedo")

    def test_unknown_updates_and_tracks_are_refused_before_anything_runs(self):
        for entry, track in (("nope", "rubedo"), (POST["id"], "../../etc/passwd"), (POST["id"], "not_a_track")):
            with self.assertRaises(ValueError):
                self.p.use(entry, track)
            with self.assertRaises(ValueError):
                self.p.pin(entry, track)
            with self.assertRaises(ValueError):
                self.p.listen(track) if entry == POST["id"] else self.p.entry(entry)
        self.assertEqual(self.rendered, [])


if __name__ == "__main__":
    unittest.main()
