#!/usr/bin/env python3
"""Unit tests for the development-hours collector.

The property that matters here is de-duplication: the figure on the Contribute screen is a
count of CLOCK HOURS, so two repos committed to in the same hour must collapse to one. These
tests build throwaway git repos with fixed commit dates and assert the union — plus the
delta-encoding round-trip, since the committed index stores hours in that form.

Run: python3 scripts/dev-hours/test_collect.py   (or via pytest)
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import importlib.util  # noqa: E402

_spec = importlib.util.spec_from_file_location("collect", os.path.join(HERE, "collect.py"))
collect = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(collect)


def make_repo(root, commit_dates):
    """A git repo with one commit per given ISO date string (``2026-08-26T14:30:00``)."""
    root.mkdir(parents=True, exist_ok=True)
    env = {
        **os.environ,
        "GIT_AUTHOR_NAME": "Test", "GIT_AUTHOR_EMAIL": "t@example.com",
        "GIT_COMMITTER_NAME": "Test", "GIT_COMMITTER_EMAIL": "t@example.com",
    }
    subprocess.run(["git", "init", "-q"], cwd=root, check=True, env=env)
    for i, when in enumerate(commit_dates):
        (root / "f.txt").write_text(str(i))
        stamp = f"{when} +0000"
        subprocess.run(["git", "add", "f.txt"], cwd=root, check=True, env=env)
        subprocess.run(
            ["git", "commit", "-q", "-m", f"c{i}"], cwd=root, check=True,
            env={**env, "GIT_AUTHOR_DATE": stamp, "GIT_COMMITTER_DATE": stamp},
        )
    return root


class HourKeyTest(unittest.TestCase):
    def test_same_hour_different_minutes_is_one_bucket(self):
        self.assertEqual(collect.hour_key("2026-08-26T14"), collect.hour_key("2026-08-26T14"))

    def test_adjacent_hours_are_adjacent_keys(self):
        self.assertEqual(
            collect.hour_key("2026-08-26T15") - collect.hour_key("2026-08-26T14"), 1)

    def test_garbage_lines_are_dropped(self):
        self.assertIsNone(collect.hour_key("not-a-date"))
        self.assertEqual(collect.hours_from_lines(["", "  ", "nope"]), set())

    def test_duplicate_lines_collapse(self):
        lines = ["2026-08-26T14", "2026-08-26T14", "2026-08-26T15"]
        self.assertEqual(len(collect.hours_from_lines(lines)), 2)


class DeltaCodecTest(unittest.TestCase):
    def test_round_trip(self):
        hours = {492000, 492001, 492010, 500000}
        self.assertEqual(set(collect.delta_decode(collect.delta_encode(hours))), hours)

    def test_encoding_is_first_then_gaps(self):
        self.assertEqual(collect.delta_encode({10, 12, 13}), [10, 2, 1])

    def test_empty(self):
        self.assertEqual(collect.delta_encode(set()), [])
        self.assertEqual(collect.delta_decode([]), [])


class UnionTest(unittest.TestCase):
    def test_overlapping_hours_count_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            # Two repos share the 14:00 hour and each hold one hour of their own.
            make_repo(tmp / "a", ["2026-08-26T14:05:00", "2026-08-26T15:00:00"])
            make_repo(tmp / "b", ["2026-08-26T14:55:00", "2026-08-26T16:00:00"])
            index = collect.build_index({"repos": [
                {"name": "a", "path": str(tmp / "a")},
                {"name": "b", "path": str(tmp / "b")},
            ]})
        self.assertEqual([r["hours"] for r in index["repos"]], [2, 2])
        self.assertEqual(index["totalHours"], 3, "the shared 14:00 hour must count once")

    def test_many_commits_in_one_hour_are_one_hour(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp) / "a", [f"2026-08-26T14:{m:02d}:00" for m in range(0, 50, 5)])
            index = collect.build_index({"repos": [{"name": "a", "path": str(root)}]})
        self.assertEqual(index["totalHours"], 1)

    def test_missing_repo_is_skipped_not_fatal(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            make_repo(tmp / "a", ["2026-08-26T14:00:00"])
            index = collect.build_index({"repos": [
                {"name": "a", "path": str(tmp / "a")},
                {"name": "gone", "path": str(tmp / "does-not-exist")},
            ]})
        self.assertEqual(index["totalHours"], 1)
        self.assertTrue(index["repos"][1]["skipped"])
        self.assertIsNone(index["repos"][1]["lastCommit"])


class SourceTest(unittest.TestCase):
    def test_local_path_used_without_clone_dir(self):
        path, error = collect.resolve_source({"name": "a", "repo": "o/a", "path": "/x/a"})
        self.assertEqual((path, error), (Path("/x/a"), None))

    def test_repo_only_entry_without_clone_dir_is_skipped(self):
        path, error = collect.resolve_source({"name": "a", "repo": "o/a"})
        self.assertIsNone(path)
        self.assertIn("--clone-dir", error)

    def test_clone_dir_reads_a_bare_clone(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            src = make_repo(tmp / "src", ["2026-08-26T14:00:00", "2026-08-26T16:00:00"])
            bare = tmp / "bare"
            subprocess.run(["git", "clone", "-q", "--bare", str(src), str(bare)], check=True)
            hours, _, error = collect.collect_repo("a", bare)
        self.assertIsNone(error)
        self.assertEqual(len(hours), 2)

    def test_failed_clone_is_skipped_not_fatal(self):
        with tempfile.TemporaryDirectory() as tmp:
            index = collect.build_index(
                {"repos": [{"name": "nope", "repo": "bh679/does-not-exist-xyz"}]},
                clone_dir=Path(tmp) / "clones", token=None)
        self.assertEqual(index["totalHours"], 0)
        self.assertTrue(index["repos"][0]["skipped"])

    def test_token_rides_in_env_not_args(self):
        env = collect.auth_env("secret-token")
        self.assertIn("extraheader", env["GIT_CONFIG_KEY_0"])
        self.assertNotIn("secret-token", env["GIT_CONFIG_VALUE_0"], "token must be base64'd")
        self.assertIsNone(collect.auth_env(None))


class ShrinkGuardTest(unittest.TestCase):
    def test_lower_total_is_refused(self):
        self.assertIsNotNone(collect.check_not_shrinking(2600, 2000))

    def test_equal_or_higher_total_passes(self):
        self.assertIsNone(collect.check_not_shrinking(2600, 2600))
        self.assertIsNone(collect.check_not_shrinking(2600, 2700))

    def test_no_previous_index_passes(self):
        self.assertIsNone(collect.check_not_shrinking(None, 1))


class IndexShapeTest(unittest.TestCase):
    def test_committed_index_is_self_consistent(self):
        path = Path(HERE).parents[1] / "docs/dev-hours/hour-index.json"
        index = json.loads(path.read_text(encoding="utf-8"))
        decoded = collect.delta_decode(index["hours"])
        self.assertEqual(len(decoded), index["totalHours"])
        self.assertEqual(len(set(decoded)), index["totalHours"], "hour set must be unique")
        self.assertEqual(decoded, sorted(decoded))
        self.assertEqual(index["schemaVersion"], collect.SCHEMA_VERSION)


if __name__ == "__main__":
    unittest.main()
