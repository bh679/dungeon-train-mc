"""Tests for mirror-images-r2.py: which files go up, and how they are keyed. Stdlib only, no network.

  python3 scripts/release-notes/test_mirror_images_r2.py
"""
import hashlib
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

_SPEC = importlib.util.spec_from_file_location(
    "mirror_images_r2", Path(__file__).with_name("mirror-images-r2.py"))
mirror = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(mirror)

GITHUB = "https://raw.githubusercontent.com/bh679/dungeon-train-mc/main/.github/release-notes/images/"


class MirrorImagesTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def write(self, name: str, data: bytes, folder: Path | None = None) -> Path:
        p = (folder or self.dir) / name
        p.write_bytes(data)
        return p

    def test_local_files_keys_flat_media_only(self) -> None:
        self.write("b.jpg", b"b")
        self.write("a.loop.mp4", b"a")
        self.write("notes.txt", b"x")
        self.write(".DS_Store", b"x")
        (self.dir / "sub").mkdir()
        self.write("c.png", b"c", self.dir / "sub")
        keys = [k for k, _ in mirror.local_files(self.dir)]
        self.assertEqual(keys, ["release-notes/images/a.loop.mp4", "release-notes/images/b.jpg"])

    def test_plan_uploads_missing_and_changed_only(self) -> None:
        local = [("k/same.jpg", self.write("same.jpg", b"same")),
                 ("k/changed.jpg", self.write("changed.jpg", b"new")),
                 ("k/new.gif", self.write("new.gif", b"gif"))]
        remote = {"k/same.jpg": hashlib.md5(b"same").hexdigest(),
                  "k/changed.jpg": hashlib.md5(b"old").hexdigest()}
        self.assertEqual([k for k, _ in mirror.plan(local, remote)], ["k/changed.jpg", "k/new.gif"])

    def test_every_changelog_photo_is_in_the_mirrored_folder(self) -> None:
        doc = json.loads((mirror.REPO / ".github" / "release-notes" / "changelog.json").read_text(encoding="utf-8"))
        mirrored = {k for k, _ in mirror.local_files()}
        for e in doc["entries"]:
            for field in ("image", "loop"):
                url = e.get(field)
                if isinstance(url, str) and url.startswith(GITHUB):
                    self.assertIn(mirror.KEY_PREFIX + url[len(GITHUB):], mirrored, (e["id"], field))

    def test_missing_settings_exit_2(self) -> None:
        env = {k: v for k, v in os.environ.items() if k not in mirror.REQUIRED}
        with mock.patch.dict(os.environ, env, clear=True):
            self.assertEqual(mirror.main([]), 2)


if __name__ == "__main__":
    unittest.main()
