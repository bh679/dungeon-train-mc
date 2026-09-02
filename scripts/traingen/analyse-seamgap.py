#!/usr/bin/env python3
"""Analyse a Dungeon Train run log for carriage-group seams that went wrong.

Reads the ``[seamgap]`` samples (armed in-game with
``/dungeontrain debug seamgap-trace on``) and, when present, the ``[bwdgen]``
trace's player position, and answers two questions per world session:

* did ANY seam ever open wider or overlap further than a block, and
* did it happen with the player inside Sable's tracking range of at least
  one of the two groups — i.e. next to a seam a client could see.

The second is the player-visible failure. A parked (soft-frozen) group whose
pose lags its slot shows up as a seam of hundreds of blocks with the player
standing right on it: the "group-sized hole in the train".

Usage::

    python3 scripts/traingen/analyse-seamgap.py run/logs/debug.log [--range 320] [--bad 1.0]

Read ``run/logs/debug.log``, NOT ``latest.log`` — the dev client and its
integrated server share the run directory and clobber ``latest.log``, while both
append to ``debug.log``.

Standard library only; no build step, no dependencies.
"""

from __future__ import annotations

import argparse
import bisect
import re
import sys
from collections import defaultdict
from pathlib import Path

SEAM_RE = re.compile(
    r"\[seamgap\] gameTick=(?P<tick>\d+) trainId=\S+ thisPIdx=(?P<a>-?\d+) nextPIdx=(?P<b>-?\d+) "
    r"distanceBlocks=(?P<d>-?[\d.]+) thisMaxX=(?P<amax>-?[\d.]+) nextMinX=(?P<bmin>-?[\d.]+) "
    r"thisForceLoaded=\w+ nextForceLoaded=\w+ thisPlaced=(?P<aplaced>\w+) nextPlaced=(?P<bplaced>\w+)"
)
BWD_RE = re.compile(r"\[bwdgen\] tick=(?P<tick>\d+) .*?playerX=(?P<x>-?[\d.]+)")

# A gameTick that runs backwards by more than this many ticks means a new world was opened.
SESSION_RESET_TICKS = 100


def parse(path: Path):
    """Split the log into world sessions of (player positions by tick, seam samples)."""
    sessions = defaultdict(lambda: {"player": {}, "seams": []})
    session = 0
    last_tick = -1
    with path.open(errors="replace") as handle:
        for line in handle:
            match = BWD_RE.search(line)
            if match:
                tick = int(match.group("tick"))
                if tick < last_tick - SESSION_RESET_TICKS:
                    session += 1
                last_tick = tick
                sessions[session]["player"][tick] = float(match.group("x"))
                continue
            match = SEAM_RE.search(line)
            if not match:
                continue
            tick = int(match.group("tick"))
            if tick < last_tick - SESSION_RESET_TICKS:
                session += 1
            last_tick = tick
            sessions[session]["seams"].append({
                "tick": tick,
                "a": int(match.group("a")),
                "b": int(match.group("b")),
                "d": float(match.group("d")),
                "amax": float(match.group("amax")),
                "bmin": float(match.group("bmin")),
                # Only a settled pair is a fair reading; a fresh spawn is still being nudged.
                "settled": match.group("aplaced") == "true" and match.group("bplaced") == "true",
            })
    return sessions


def nearest_player_x(ticks: list[int], player: dict[int, float], tick: int):
    if not ticks:
        return None
    i = bisect.bisect_left(ticks, tick)
    if i >= len(ticks):
        i = len(ticks) - 1
    if i > 0 and abs(ticks[i - 1] - tick) < abs(ticks[i] - tick):
        i -= 1
    return player[ticks[i]]


def report(index: int, data: dict, tracking_range: float, bad: float) -> int:
    seams = data["seams"]
    ticks = sorted(data["player"])
    if not seams:
        print(f"\n=== session {index}: no [seamgap] samples")
        return 0
    settled = [s for s in seams if s["settled"]]
    first, last = seams[0]["tick"], seams[-1]["tick"]
    print(f"\n=== session {index}: ticks {first}..{last}, {len(seams)} samples "
          f"({len(settled)} between settled groups), player positions: {len(ticks)}")

    worst = max(settled, key=lambda s: abs(s["d"]), default=None)
    bad_any = [s for s in settled if abs(s["d"]) > bad]
    print(f"worst settled seam: |d|={abs(worst['d']):.2f} at tick {worst['tick']} "
          f"({worst['a']}->{worst['b']})" if worst else "no settled seams")
    print(f"settled seams with |d| > {bad:g}: {len(bad_any)} of {len(settled)}")

    if not ticks:
        print("no [bwdgen] player positions — cannot tell visible from parked-out-of-range; "
              "arm the traingen trace for the in-range check")
        return len(bad_any)

    in_range = []
    for s in bad_any:
        px = nearest_player_x(ticks, data["player"], s["tick"])
        da, db = abs(s["amax"] - px), abs(s["bmin"] - px)
        # The nearer edge is what the player can see; the far edge of a hole is, by definition,
        # the group that is hundreds of blocks away.
        if min(da, db) < tracking_range:
            in_range.append((s, px, min(da, db)))
    print(f"...of which within {tracking_range:g} blocks of the player (visible): {len(in_range)}")

    by_seam = defaultdict(list)
    for s, px, dist in in_range:
        by_seam[(s["a"], s["b"])].append((s["tick"], s["d"], px, dist))
    for (a, b), rows in sorted(by_seam.items()):
        rows.sort()
        closest = min(rows, key=lambda r: r[3])
        print(f"  seam {a}->{b}: {len(rows)} sample(s), ticks {rows[0][0]}..{rows[-1][0]}, "
              f"d {min(r[1] for r in rows):+.1f}..{max(r[1] for r in rows):+.1f}; "
              f"closest at tick {closest[0]} d={closest[1]:+.1f} player {closest[3]:.0f} blocks away")
    if in_range:
        hole = [r for r in in_range if abs(r[0]["d"]) > 30 and r[2] < 40]
        if hole:
            print(f"  ** {len(hole)} sample(s) read like a HOLE: a seam wider than a group with the "
                  f"player standing on it (a parked group whose pose fell behind its slot)")
    return len(in_range)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("logs", nargs="+", type=Path)
    parser.add_argument("--range", type=float, default=320.0,
                        help="Sable SUB_LEVEL_TRACKING_RANGE in blocks (default 320)")
    parser.add_argument("--bad", type=float, default=1.0,
                        help="|distanceBlocks| above which a settled seam counts as bad (default 1.0)")
    args = parser.parse_args(argv)

    visible_bad = 0
    for path in args.logs:
        if not path.is_file():
            print(f"{path}: not a file", file=sys.stderr)
            return 2
        print(f"# {path}")
        for index, data in sorted(parse(path).items()):
            visible_bad += report(index, data, args.range, args.bad)
    print(f"\nvisible bad seams across all sessions: {visible_bad}")
    return 1 if visible_bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
