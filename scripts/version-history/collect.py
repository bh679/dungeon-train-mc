#!/usr/bin/env python3
"""
Collect when each Dungeon Train version shipped, and write the snapshot the build bakes into
the jar and the relay serves to the death screen.

The unit is a **minor version bump** — one per Gate 3 merge, i.e. one shipped update. Patch
bumps (the auto-release cascade's ticks, chores) are deliberately excluded: they are the tail
of an update, not an update of their own, and counting them would treble the figure with
automation noise.

Why a committed snapshot instead of computing this at build time: CI clones shallowly and the
relay has no checkout at all, so the history has to arrive pre-computed. build.gradle unions
this snapshot with the LIVE history of whatever checkout is building (so a dev build is never
stale), and dp-relay's updates.js polls the committed copy over HTTP.

Everything the consumers need is derivable from `daily` + `firstVersionDate`, so the window a
consumer shows ("the last 5 months" today, "this year" once the project is a year old) rolls
forward on its own without regenerating this file.

Usage:  python3 scripts/version-history/collect.py [--out PATH] [--dry-run]
"""
import argparse
import json
import subprocess
import sys
import time
from datetime import date, timedelta
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPO / "docs/version-history/index.json"
SCHEMA_VERSION = "1.0"

# The author date, day-resolution: which day the update landed. `-p` over gradle.properties
# alone keeps the diff tiny — the only `+mod_version=` lines that can appear are that file's.
GIT_LOG = ["git", "log", "--reverse", "--format=%x00%ad", "--date=short",
           "-p", "--", "gradle.properties"]

# The rolling windows the card can show: a week by default, a month when the week is too thin.
RECENT_WEEK_DAYS = 7
RECENT_DAYS = 30
# The longest window the card offers (on hover), in months — "1 year" once the project is that old.
MAX_WINDOW_MONTHS = 12


def parse_log(lines):
    """
    ``git log`` output -> [(date_str, version)] in chronological order, one entry per commit
    that CHANGED mod_version. Lines that aren't a date marker or an added mod_version are
    ignored, so a malformed diff yields fewer entries rather than an exception.
    """
    out = []
    when = None
    seen = None
    for line in lines:
        if line.startswith("\x00"):
            when = line[1:].strip() or None
        elif line.startswith("+mod_version="):
            version = line[len("+mod_version="):].strip()
            if when and version and version != seen:
                out.append((when, version))
                seen = version
    return out


def minor_of(version):
    """``0.763.1`` -> ``(0, 763)``. Returns None for anything that isn't MAJOR.MINOR[.PATCH]."""
    parts = version.split(".")
    if len(parts) < 2:
        return None
    try:
        return int(parts[0]), int(parts[1])
    except ValueError:
        return None


def minor_bumps(entries):
    """
    Keep only the entries whose MAJOR.MINOR differs from the one before — the shipped updates.
    The first parseable version always counts (it is the first update). Unparseable versions
    are skipped without disturbing the run.
    """
    out = []
    previous = None
    for when, version in entries:
        key = minor_of(version)
        if key is None or key == previous:
            continue
        out.append((when, version))
        previous = key
    return out


def daily_counts(bumps):
    """[(date, version)] -> {"YYYY-MM-DD": how many updates landed that day}."""
    counts = {}
    for when, _ in bumps:
        counts[when] = counts.get(when, 0) + 1
    return dict(sorted(counts.items()))


# ---- the window rule (mirrored in build.gradle, UpdateStats.java and dp-relay's updates.js) ----

def window_months(first, today):
    """
    The longest window the card offers, in months: the project's own age, rounded UP, capped at
    a year. A five-month-old game says "in 5 months" rather than "in 1 year"; once it is old
    enough the answer is simply 12, which the card renders as "1 year".

    Rounding up rather than down keeps the window covering the whole history: an age of 4
    months and 12 days is "the last 5 months", which includes every update ever shipped.
    """
    whole = (today.year - first.year) * 12 + (today.month - first.month)
    if today.day < first.day:
        whole -= 1
    if today.day != first.day:
        whole += 1
    return max(1, min(MAX_WINDOW_MONTHS, whole))


def months_ago(today, months):
    """``today`` minus ``months`` calendar months, clamping the day into a short month."""
    total = (today.year * 12 + today.month - 1) - months
    year, month = divmod(total, 12)
    month += 1
    day = min(today.day, [31, 29 if year % 4 == 0 and (year % 100 or year % 400 == 0) else 28,
                          31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1])
    return date(year, month, day)


def window_start(first, today):
    """The first day the card's long count includes, for the window :func:`window_months` picked."""
    return months_ago(today, window_months(first, today))


def count_since(daily, start):
    """Sum the daily buckets on or after ``start`` (a ``date``)."""
    key = start.isoformat()
    return sum(n for day, n in daily.items() if day >= key)


def build_index(bumps, today):
    daily = daily_counts(bumps)
    first = date.fromisoformat(bumps[0][0]) if bumps else today
    return {
        "comment": "GENERATED by scripts/version-history/collect.py — do not hand-edit. "
                   "One entry per shipped update (MINOR version bump); `daily` maps the day it "
                   "landed to how many landed that day.",
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "firstVersionDate": bumps[0][0] if bumps else None,
        "latestVersion": bumps[-1][1] if bumps else None,
        "latestVersionDate": bumps[-1][0] if bumps else None,
        "totalUpdates": len(bumps),
        "daily": daily,
    }


def working_tree_version(root):
    """The `mod_version` currently in gradle.properties, or None if it can't be read."""
    try:
        for line in (root / "gradle.properties").read_text(encoding="utf-8").splitlines():
            if line.startswith("mod_version="):
                return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return None


def with_working_tree(bumps, root, today):
    """
    Append the version sitting in gradle.properties when it is a MINOR ahead of anything in the
    history. This snapshot is regenerated inside the auto-bump commit itself, and that commit's
    own version is not in `git log` yet — without this the index would forever lag one update
    behind the version it ships alongside.
    """
    version = working_tree_version(root)
    key = minor_of(version) if version else None
    if key is None or (bumps and minor_of(bumps[-1][1]) == key):
        return bumps
    return bumps + [(today.isoformat(), version)]


def read_history(root):
    """This checkout's version history, or an empty list when git can't be read."""
    try:
        raw = subprocess.run(GIT_LOG, cwd=root, check=True, timeout=300,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True).stdout
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as exc:
        # A history we can't read is a missing number, not a failed run — same contract as
        # scripts/dev-hours/collect.py.
        print(f"WARNING: could not read git history — {exc}", file=sys.stderr)
        return []
    return minor_bumps(parse_log(raw.splitlines()))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--dry-run", action="store_true", help="report the totals, write nothing")
    args = ap.parse_args(argv)

    today = date.today()
    bumps = with_working_tree(read_history(REPO), REPO, today)
    index = build_index(bumps, today)

    if bumps:
        first = date.fromisoformat(index["firstVersionDate"])
        months = window_months(first, today)
        window = "1 year" if months == MAX_WINDOW_MONTHS else f"{months} month{'s' if months > 1 else ''}"
        recent = count_since(index["daily"], today - timedelta(days=RECENT_DAYS))
        week = count_since(index["daily"], today - timedelta(days=RECENT_WEEK_DAYS))
        day = index["daily"].get(today.isoformat(), 0)
        year = count_since(index["daily"], date(today.year, 1, 1))
        print(f"  first update        {index['firstVersionDate']}")
        print(f"  latest update       {index['latestVersion']} ({index['latestVersionDate']})")
        print(f"  updates, all time   {index['totalUpdates']}")
        print(f"  updates, today      {day}")
        print(f"  updates, last {RECENT_WEEK_DAYS}d    {week}")
        print(f"  updates, last {RECENT_DAYS}d   {recent}")
        print(f"  updates in {window:<9} {count_since(index['daily'], window_start(first, today))}")
        print(f"  updates, {today.year} so far {year}")
    else:
        print("  no version history found — the death screen will omit the line")

    if args.dry_run:
        return 0
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(index, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {args.out.relative_to(REPO) if args.out.is_relative_to(REPO) else args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
