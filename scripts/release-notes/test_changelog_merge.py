#!/usr/bin/env python3
"""Unit tests for merge-changelog.py (union by id) and check-changelog-ids.py (id-loss guard).

Run: python3 scripts/release-notes/test_changelog_merge.py
"""
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent


def _load(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), HERE / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


merge = _load("merge-changelog")
check = _load("check-changelog-ids")


def e(eid, **kw):
    return {"id": eid, "version": "0.1.0", "released": False, **kw}


class UnionMerge(unittest.TestCase):
    def test_both_sides_appends_survive(self):
        base = [e("a")]
        ours = [e("a"), e("main-1"), e("main-2")]
        theirs = [e("a"), e("perks-1")]
        out = merge.merge_entries(base, ours, theirs)
        self.assertEqual([x["id"] for x in out], ["a", "main-1", "main-2", "perks-1"])

    def test_released_stamp_wins_over_a_stale_copy(self):
        base = [e("a")]
        ours = [e("a", title="edited")]
        theirs = [e("a", released=True, released_in="v0.2.0")]
        self.assertTrue(merge.merge_entries(base, ours, theirs)[0]["released"])

    def test_one_sided_edit_is_kept(self):
        base = [e("a")]
        self.assertEqual(merge.merge_entries(base, [e("a")], [e("a", title="x")])[0]["title"], "x")

    def test_deliberate_delete_is_honoured(self):
        base = [e("a"), e("b")]
        out = merge.merge_entries(base, [e("a")], [e("a"), e("b")])
        self.assertEqual([x["id"] for x in out], ["a"])

    def test_driver_form_writes_ours(self):
        with tempfile.TemporaryDirectory() as tmp:
            paths = []
            for name, entries in (("base", [e("a")]), ("ours", [e("a"), e("o")]),
                                  ("theirs", [e("a"), e("t")])):
                path = os.path.join(tmp, name)
                Path(path).write_text(json.dumps({"entries": entries}))
                paths.append(path)
            subprocess.run([sys.executable, str(HERE / "merge-changelog.py"), *paths], check=True)
            ids = [x["id"] for x in json.loads(Path(paths[1]).read_text())["entries"]]
            self.assertEqual(ids, ["a", "o", "t"])


class IdLoss(unittest.TestCase):
    def test_taking_mains_file_loses_the_branch_entries(self):
        lost = check.lost_ids(base={"a"}, ours={"a", "m"}, theirs={"a", "p"}, merged={"a", "m"})
        self.assertEqual(lost, {"p"})

    def test_clean_union_loses_nothing(self):
        self.assertEqual(check.lost_ids({"a"}, {"a", "m"}, {"a", "p"}, {"a", "m", "p"}), set())

    def test_a_side_removing_an_entry_on_purpose_is_allowed(self):
        self.assertEqual(check.lost_ids({"a", "b"}, {"a"}, {"a", "b"}, {"a"}), set())


if __name__ == "__main__":
    unittest.main()
