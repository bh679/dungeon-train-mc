#!/usr/bin/env python3
"""Unit tests for the version-history collector.

Two properties matter. First, an "update" is a MINOR bump: the cascade's patch ticks must not
inflate the figure the death screen shows. Second, the window rule — a five-month-old project
says "the last 5 months", not "this year" — has to round UP, or the first partial month's
updates vanish from a count that claims to cover everything.

Run: python3 scripts/version-history/test_collect.py   (or via pytest)
"""
import os
import sys
import unittest
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import importlib.util  # noqa: E402

_spec = importlib.util.spec_from_file_location("collect", os.path.join(HERE, "collect.py"))
collect = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(collect)


class ParseLogTest(unittest.TestCase):
    def test_pairs_each_version_with_its_commit_date(self):
        lines = [
            "\x002026-04-20", "diff --git a/gradle.properties b/gradle.properties",
            "+++ b/gradle.properties", "-mod_version=0.1.0", "+mod_version=0.2.0",
            "\x002026-04-21", "+mod_version=0.3.0",
        ]
        self.assertEqual(collect.parse_log(lines),
                         [("2026-04-20", "0.2.0"), ("2026-04-21", "0.3.0")])

    def test_ignores_a_commit_that_touched_the_file_without_changing_the_version(self):
        lines = ["\x002026-04-20", "+mod_version=0.2.0",
                 "\x002026-04-21", "+neo_version=21.1.228",
                 "\x002026-04-22", "+mod_version=0.2.0"]
        self.assertEqual(collect.parse_log(lines), [("2026-04-20", "0.2.0")])


class MinorBumpsTest(unittest.TestCase):
    def test_patch_bumps_are_not_updates(self):
        entries = [("2026-04-20", "0.1.0"), ("2026-04-20", "0.1.1"), ("2026-04-20", "0.1.2"),
                   ("2026-04-21", "0.2.0"), ("2026-04-21", "0.2.1")]
        self.assertEqual(collect.minor_bumps(entries),
                         [("2026-04-20", "0.1.0"), ("2026-04-21", "0.2.0")])

    def test_a_major_bump_is_an_update_even_at_the_same_minor(self):
        entries = [("2026-04-20", "0.9.0"), ("2026-04-21", "1.9.0")]
        self.assertEqual(len(collect.minor_bumps(entries)), 2)

    def test_unparseable_versions_are_skipped_not_fatal(self):
        entries = [("2026-04-20", "0.1.0"), ("2026-04-21", "banana"), ("2026-04-22", "0.2.0")]
        self.assertEqual(len(collect.minor_bumps(entries)), 2)

    def test_daily_counts_group_by_day(self):
        bumps = [("2026-04-20", "0.1.0"), ("2026-04-20", "0.2.0"), ("2026-04-21", "0.3.0")]
        self.assertEqual(collect.daily_counts(bumps), {"2026-04-20": 2, "2026-04-21": 1})


class WindowTest(unittest.TestCase):
    def test_partial_month_rounds_up_so_the_window_covers_everything(self):
        # The real case: first version 2026-04-20, four months and twelve days ago.
        months = collect.window_months(date(2026, 4, 20), date(2026, 9, 1))
        self.assertEqual(months, 5)
        self.assertEqual(collect.window_start(date(2026, 4, 20), date(2026, 9, 1)),
                         date(2026, 4, 1))

    def test_an_exact_month_boundary_does_not_round_up(self):
        self.assertEqual(collect.window_months(date(2026, 4, 20), date(2026, 8, 20)), 4)

    def test_a_day_old_project_still_claims_one_month(self):
        self.assertEqual(collect.window_months(date(2026, 4, 20), date(2026, 4, 21)), 1)

    def test_just_under_a_year_is_twelve_months(self):
        self.assertEqual(collect.window_months(date(2026, 4, 20), date(2027, 3, 25)), 12)

    def test_a_year_old_switches_to_the_calendar_year(self):
        self.assertEqual(collect.window_months(date(2026, 4, 20), date(2027, 4, 20)), 0)
        self.assertEqual(collect.window_start(date(2026, 4, 20), date(2027, 4, 20)),
                         date(2027, 1, 1))

    def test_months_ago_clamps_into_a_short_month(self):
        self.assertEqual(collect.months_ago(date(2026, 3, 31), 1), date(2026, 2, 28))


class CountSinceTest(unittest.TestCase):
    daily = {"2026-04-20": 8, "2026-05-01": 2, "2026-08-30": 5, "2026-09-01": 1}

    def test_counts_on_or_after_the_start_day(self):
        self.assertEqual(collect.count_since(self.daily, date(2026, 5, 1)), 8)
        self.assertEqual(collect.count_since(self.daily, date(2026, 4, 20)), 16)

    def test_an_empty_history_counts_zero(self):
        self.assertEqual(collect.count_since({}, date(2026, 5, 1)), 0)


if __name__ == "__main__":
    unittest.main()
