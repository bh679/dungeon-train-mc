#!/usr/bin/env python3
"""Unit tests for merge_bump.py: unit counting, version math, re-stamping.

GitHub and git are faked, so the shapes below are the real ones from dev/perks without touching
the network. Run: python3 scripts/version/test_merge_bump.py
"""
import contextlib
import io
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import merge_bump as mb  # noqa: E402
from merge_bump import PullRequest  # noqa: E402


def pr(number, head_ref, merged_at=None, head_sha=None, merge_sha=None):
    return PullRequest(number=number, head_ref=head_ref, head_sha=head_sha or f"head{number}",
                       merge_sha=merge_sha if merge_sha is not None else
                       (f"merge{number}" if merged_at else None), merged_at=merged_at)


class FakeGitHub:
    def __init__(self, prs, bases):
        self.prs = {p.number: p for p in prs}
        self.bases = bases          # branch -> [pr numbers merged into it]
        self.by_commit = {}

    def pr(self, number):
        return self.prs[number]

    def merged_into(self, branch):
        return [self.prs[n] for n in self.bases.get(branch, [])]

    def pr_for_commit(self, sha):
        return self.by_commit.get(sha)


class FakeGit:
    def __init__(self, ancestry, ids, versions=None):
        self.ancestry = ancestry    # descendant sha -> set of ancestor shas
        self.ids = ids              # rev -> set of changelog ids
        self.versions = versions or {}

    def fetch_pr_head(self, number):
        pass

    def is_ancestor(self, ancestor, descendant):
        return ancestor in self.ancestry.get(descendant, set())

    def changelog_ids(self, rev):
        return set(self.ids.get(rev, set()))

    def mod_version(self, rev):
        return self.versions[rev]


def perks_world():
    """dev/perks as of 2026-10-25: three leaf PRs, then #2330 carrying #2316 plus its own entry."""
    prs = [
        pr(2303, "dev/perks-knight-combat", "2026-10-24T17:24:13Z"),
        pr(2302, "dev/perks-trader-tier2-3", "2026-10-24T17:44:56Z"),
        pr(2299, "dev/perks-archivist", "2026-10-24T18:05:37Z"),
        pr(2316, "dev/cosmic-apples", "2026-10-25T10:00:00Z"),
        pr(2330, "dev/perks-earning", "2026-10-25T15:36:34Z"),
        pr(9000, "dev/perks", "2026-10-26T00:00:00Z", merge_sha="squash"),
    ]
    gh = FakeGitHub(prs, {"dev/perks": [2303, 2302, 2299, 2330],
                          "dev/perks-earning": [2316]})
    all_merges = {"merge2303", "merge2302", "merge2299", "merge2330"}
    git = FakeGit(
        ancestry={"head9000": all_merges, "head2330": {"merge2316"}},
        ids={"merge2303": {"knight"}, "merge2302": {"trader"}, "merge2299": {"archivist"},
             "merge2316": {"cosmic"}, "merge2330": {"cosmic", "perk-points"},
             "squash": {"knight", "trader", "archivist", "cosmic", "perk-points", "old"},
             "squash^": {"old"}},
        versions={"squash^": "0.1530.3", "squash": "0.1534.0"})
    return gh, git


class UnitCounting(unittest.TestCase):
    def test_normal_pr_is_one_unit(self):
        gh = FakeGitHub([pr(1, "dev/fix", "t")], {})
        git = FakeGit({}, {})
        units = mb.units_of(gh, git, gh.pr(1), {"fix"})
        self.assertEqual([(u.pr, u.entry_ids) for u in units], [(1, ("fix",))])

    def test_nested_pr_counts_child_plus_own_entry(self):
        gh, git = perks_world()
        units = mb.units_of(gh, git, gh.pr(2330), mb.added_ids(git, gh.pr(2330)))
        self.assertEqual([(u.pr, u.entry_ids) for u in units],
                         [(2316, ("cosmic",)), (2330, ("perk-points",))])

    def test_integration_branch_to_main_counts_every_merged_pr_once(self):
        gh, git = perks_world()
        units = mb.units_of(gh, git, gh.pr(9000), mb.added_ids(git, gh.pr(9000)))
        self.assertEqual([u.pr for u in units], [2303, 2302, 2299, 2316, 2330])
        # The carrier itself added nothing its children didn't, so it doesn't count.
        self.assertNotIn(9000, [u.pr for u in units])
        self.assertEqual(units[3].via, ("dev/perks-earning", "dev/perks"))

    def test_reused_branch_name_ignores_prs_not_in_the_head(self):
        gh = FakeGitHub([pr(5, "dev/x", "2026-01-01"), pr(6, "dev/x", "t"),
                         pr(7, "dev/y", "t")], {"dev/x": [7]})
        git = FakeGit(ancestry={}, ids={})  # #7's merge commit is not in #6's head
        units = mb.units_of(gh, git, gh.pr(6), set())
        self.assertEqual([u.pr for u in units], [6])

    def test_cycle_is_refused(self):
        gh = FakeGitHub([pr(1, "dev/a", "t", head_sha="h1", merge_sha="m1"),
                         pr(2, "dev/a", "t", head_sha="h1", merge_sha="m2")],
                        {"dev/a": [2, 1]})
        git = FakeGit(ancestry={"h1": {"m1", "m2"}}, ids={})
        with self.assertRaises(RuntimeError):
            mb.units_of(gh, git, gh.pr(1), set())


class VersionMath(unittest.TestCase):
    def test_next_versions_from_patch(self):
        self.assertEqual(mb.next_versions("0.1530.3", 3), ["0.1531.0", "0.1532.0", "0.1533.0"])

    def test_floor_lifts_a_branch_behind_main(self):
        self.assertEqual(mb.next_versions("0.1509.0", 2, floor="0.1530.4"),
                         ["0.1531.0", "0.1532.0"])

    def test_floor_below_branch_is_ignored(self):
        self.assertEqual(mb.next_versions("0.1540.2", 1, floor="0.1530.0"), ["0.1541.0"])

    def test_commit_message_keeps_the_guard_prefix(self):
        unit = mb.Unit(pr=2316, via=("dev/perks-earning", "dev/perks"))
        msg = mb.commit_message("0.1534.0", unit, "main")
        self.assertTrue(msg.startswith("chore(version): bump to 0.1534.0 (MINOR — Gate 3 merge"))
        self.assertIn("#2316 via dev/perks-earning > dev/perks", msg)
        self.assertTrue(msg.endswith("[skip ci]"))

    def test_set_mod_version_touches_only_that_line(self):
        text = "a=1\nmod_version=0.1.5\nb=mod_version=x\n"
        self.assertEqual(mb.set_mod_version(text, "0.2.0"), "a=1\nmod_version=0.2.0\nb=mod_version=x\n")

    def test_version_of_rejects_conflict_garbage(self):
        with self.assertRaises(ValueError):
            mb.version_of("mod_version=0.1.0\nmod_version=0.2.0\n")


class Restamp(unittest.TestCase):
    def test_only_unreleased_listed_entries_move(self):
        entries = [{"id": "a", "version": "0.1.0", "released": False},
                   {"id": "b", "version": "0.1.0", "released": True},
                   {"id": "c", "version": "0.1.0", "released": False}]
        out = mb.restamp(entries, {"a", "b"}, "0.9.0")
        self.assertEqual([e["version"] for e in out], ["0.9.0", "0.1.0", "0.1.0"])
        self.assertIs(out[2], entries[2])
        self.assertEqual(entries[0]["version"], "0.1.0")  # input untouched


class MainCommand(unittest.TestCase):
    def run_main(self, gh, git, argv):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = mb.main(argv, gh=gh, git=git)
        return code, buf.getvalue()

    def test_integration_merge_ends_at_main_plus_one_minor_per_pr(self):
        gh, git = perks_world()
        gh.by_commit["squash"] = 9000
        code, out = self.run_main(gh, git, ["main", "--sha", "squash", "--dry-run"])
        self.assertEqual(code, 0)
        self.assertIn("main 0.1530.3 -> 0.1535.0", out)
        self.assertEqual(out.count("chore(version): bump to"), 5)

    def test_normal_merge_is_left_to_the_legacy_path(self):
        gh = FakeGitHub([pr(1, "dev/fix", "t", merge_sha="s")], {})
        gh.by_commit["s"] = 1
        code, out = self.run_main(gh, FakeGit({}, {}), ["main", "--sha", "s", "--dry-run"])
        self.assertEqual(code, 0)
        self.assertIn("mode=legacy", out)


if __name__ == "__main__":
    unittest.main()
