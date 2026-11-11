#!/usr/bin/env python3
"""Unit tests for preview-social-posts.py's song card and its --music / --vibe picks.

Run: python3 scripts/release-notes/test_preview_social_posts.py   (or via pytest)
"""
import importlib.util
import os
import sys
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


if __name__ == "__main__":
    unittest.main()
