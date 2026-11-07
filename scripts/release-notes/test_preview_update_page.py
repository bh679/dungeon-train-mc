#!/usr/bin/env python3
"""Unit tests for preview-update-page.py: the page patch and the local changelog.

Run: python3 scripts/release-notes/test_preview_update_page.py   (or via pytest)
"""
import importlib.util
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
spec = importlib.util.spec_from_file_location(
    "preview_update_page", os.path.join(HERE, "preview-update-page.py")
)
preview = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preview)

PAGE = f"const SOURCES = {{\n    {preview.CHANGELOG_SOURCE}\n}};\n{preview.NEWEST_SOURCE}\n"


class PatchPage(unittest.TestCase):
    def test_reads_the_local_changelog(self):
        patched = preview.patch_update_js(PAGE)
        self.assertIn(preview.LOCAL_CHANGELOG, patched)
        self.assertNotIn("raw.githubusercontent.com", patched)
        self.assertIn("const newest = newestReleased(changelog);", patched)

    def test_a_changed_page_stops_the_preview(self):
        for needle in (preview.CHANGELOG_SOURCE, preview.NEWEST_SOURCE):
            with self.assertRaises(ValueError):
                preview.patch_update_js(PAGE.replace(needle, ""))


class LocalChangelog(unittest.TestCase):
    def test_marks_only_unreleased_entries(self):
        old = {"id": "old", "released": True, "released_in": "v1.0.0"}
        new = {"id": "new", "released": False}
        data = {"entries": [old, new]}
        result, count = preview.preview_changelog(data, "2.0.0", "2026-11-07T00:00:00Z")
        self.assertEqual(count, 1)
        self.assertEqual(result["entries"][0]["released_in"], "v1.0.0")
        self.assertEqual(result["entries"][1]["released_in"], "v2.0.0")
        self.assertFalse(new["released"], "the loaded data must not change")


class NotesPage(unittest.TestCase):
    def test_escapes_the_notes(self):
        self.assertIn("&lt;b&gt;", preview.notes_page("1.0.0", "<b>"))


if __name__ == "__main__":
    unittest.main()
