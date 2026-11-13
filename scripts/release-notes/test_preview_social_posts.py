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


if __name__ == "__main__":
    unittest.main()
