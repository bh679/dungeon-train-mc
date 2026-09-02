#!/usr/bin/env python3
"""Analyse a Dungeon Train run log for BACKWARD carriage-generation stalls.

Reads the ``[bwdgen]`` decision-trace lines emitted by
``games.brennan.dungeontrain.debug.BackwardGenTrace`` (armed in-game with
``/dungeontrain debug traingen on``, and on by default in a dev client) and
answers one question: *when did the train stop extending toward the tail, and
which gate stopped it?*

Usage::

    python3 scripts/traingen/analyse-bwdgen.py [run/logs/debug.log ...]

Read ``run/logs/debug.log``, NOT ``latest.log`` — the dev client and its
integrated server share the run directory and clobber ``latest.log``, while both
append to ``debug.log``.

Standard library only; no build step, no dependencies.
"""

from __future__ import annotations

import re
import sys
from collections import Counter
from pathlib import Path

# One [bwdgen] sample line. Fields are key=value, whitespace separated, so a
# single generic scan keeps the parser insensitive to field order or additions.
LINE_RE = re.compile(r"\[bwdgen\]\s+(?P<fields>tick=.*)$")
FIELD_RE = re.compile(r"(\w+)=(\S+)")

# Reasons that mean a group WAS added this tick.
PRODUCING = {"SPAWNED", "FILL_RUN"}

# What each blocking reason implicates, keyed to the hypotheses in the Gate 1 plan.
VERDICTS = {
    "EDGE_DEFER": (
        "H1 — the frontier anchor is registered and resident but never surfaces in findAll. "
        "A fresh spawn that never assembled, or a live sub-level Sable stopped listing. "
        "Check the [bwdgen] edgeSub field: a constant id means the same sub-level is stuck."),
    "EDGE_RELOAD": (
        "H1b — the frontier anchor is in Sable holding and never comes back after "
        "reloadFromHolding (a null serialization pointer, or a chunk that never loads). "
        "Check edgeSub: a constant id means the same sub-level is failing to reload."),
    "NO_NEED": (
        "Every anchor the window needs below the player is visible, so the lane has nothing to "
        "do. If the player still reached the end of the train, read tailGapX and target: the "
        "window itself is too small for the ride, not the lane."),
    "ANCHOR_KNOWN": (
        "Legacy reason from the registry-edge lane — should not appear on a frontier build."),
    "FRONTIER_REAP": (
        "The frontier anchor held a REMOVED ghost; it was reaped and spawns fresh next tick. "
        "Healthy as a one-tick event; sustained means something keeps removing fresh spawns."),
    "GATE_CULL_LATCH": (
        "H3 — the cull-clear latch is holding the lane shut. Expect latchAge to saw-tooth "
        "0→600 as the 30 s expiry lets one attempt through, each promptly culled."),
    "GATE_PENDING": (
        "H4 — the previous backward spawn never reached placedSuccessfully. ticksPending "
        "above ~200 means the 200-tick safety valve never fired either, i.e. the carriage "
        "is invisible to the placement tracker (out of Sable's findAll)."),
    "CHUNKGEN_DEFER": (
        "H6 — footprint chunk generation never completes for the trailing spawn site. "
        "Watch chunkWait climbing past the 100-tick backstop."),
    "NOT_NEAR": (
        "H0 — no player within NEAR_RADIUS (128) of any group AABB, and none on the line "
        "(corridor-near). If the player was aboard the whole time, this IS the bug: the near "
        "test measures group world AABBs, and a culled group reports a zero AABB."),
    "EDGE_FROZEN": (
        "The lane's reference group was DT-frozen (idle, untracked — a remote player is far "
        "away); it was thawed and the spawn waits one tick for a live pose. Healthy as a "
        "one-tick event; sustained means the freeze controller re-freezes it every tick."),
    "TOO_FAR": (
        "A player is on the line but further from the train than the remote catch-up cap "
        "(REMOTE_CATCH_UP_MAX_GROUPS) allows. The train stays culled, as before the feature. "
        "Check playerX against the visible tail."),
}

FLOAT_FIELDS = {"playerX", "tailGapX", "trainVelX", "laneRate", "playerRate", "outrun"}

INT_FIELDS = {
    "tick", "blockedFor", "playerPIdx", "skew", "minNeeded", "registryMin",
    "visibleTail", "span", "registryCount", "visibleCount", "anchor", "deficit",
    "ticksPending", "latchAge", "forceLoaded", "chunkWait", "target", "burstGroups",
    "maxNeeded", "occupiedPIdx",
}


def parse(paths: list[Path]) -> list[dict]:
    """Every [bwdgen] sample across the given logs, in file order."""
    samples: list[dict] = []
    for path in paths:
        if not path.exists():
            print(f"!! no such log: {path}", file=sys.stderr)
            continue
        with path.open(errors="replace") as fh:
            for raw in fh:
                m = LINE_RE.search(raw)
                if not m:
                    continue
                fields = dict(FIELD_RE.findall(m.group("fields")))
                sample = {}
                for key, value in fields.items():
                    if key in INT_FIELDS:
                        try:
                            sample[key] = int(value)
                        except ValueError:
                            sample[key] = None
                    elif key in FLOAT_FIELDS:
                        try:
                            sample[key] = float(value)
                        except ValueError:
                            sample[key] = None
                    else:
                        sample[key] = value
                sample["_raw"] = raw.rstrip("\n")
                samples.append(sample)
    return samples


def series(samples: list[dict], key: str) -> str:
    """Compact value list, eliding runs of the same value."""
    out: list[str] = []
    previous = object()
    for s in samples:
        value = s.get(key)
        if value != previous:
            out.append(str(value))
            previous = value
    return " ".join(out[-24:])


def report(train: str, samples: list[dict]) -> None:
    print(f"\n=== train {train} — {len(samples)} samples "
          f"(ticks {samples[0]['tick']}..{samples[-1]['tick']}) ===")

    producing = [s for s in samples if s.get("reason") in PRODUCING]
    print(f"backward groups added: {len(producing)}")
    if not producing:
        print("  NONE — the backward lane never produced a group in this log.")

    # The stall boundary: everything after the last producing sample.
    if producing:
        last = producing[-1]
        after = [s for s in samples if s["tick"] > last["tick"]]
        print(f"last backward spawn: tick={last['tick']} ({last['reason']}) "
              f"anchor={last['anchor']} playerPIdx={last['playerPIdx']}")
    else:
        last = None
        after = samples

    if not after:
        print("verdict: still generating at the end of the log — no stall captured.")
        return

    stalled_ticks = after[-1]["tick"] - after[0]["tick"]
    print(f"blocked since:       tick={after[0]['tick']} "
          f"(+{stalled_ticks} ticks ≈ {stalled_ticks / 20:.1f}s of log after it)")

    before = [s for s in samples if last and s["tick"] <= last["tick"]]
    if before:
        print(f"reasons BEFORE the stall: {dict(Counter(s['reason'] for s in before))}")
    counts = Counter(s["reason"] for s in after)
    print(f"reasons AFTER  the stall: {dict(counts)}")

    # The race. A lane can report no fault for an entire ride and still lose it: if the player
    # closes on the tail faster than the lane extends, they reach the end of the train regardless.
    # This is measured, not inferred, because the first instrumented ride looked healthy throughout.
    rated = [s for s in samples if s.get("outrun") is not None]
    if rated:
        outruns = sorted(s["outrun"] for s in rated)
        worst = max(rated, key=lambda s: s["outrun"])
        print(f"\nRACE (carriages/min toward the tail): outrun median "
              f"{outruns[len(outruns)//2]:+.1f}, worst {outruns[-1]:+.1f}")
        print(f"  worst at tick={worst['tick']}: player {worst.get('playerRate'):+.1f} vs "
              f"lane {worst.get('laneRate'):+.1f}  ({worst.get('reason')})")
        losing = [s for s in rated if s["outrun"] > 0]
        print(f"  player ahead of the lane in {len(losing)}/{len(rated)} samples "
              f"({100*len(losing)/len(rated):.0f}%)")

    gaps = [s for s in samples if s.get("tailGapX") is not None]
    if gaps:
        closest = min(gaps, key=lambda s: s["tailGapX"])
        print(f"\nTAIL GAP: closest approach {closest['tailGapX']:.1f} blocks at tick="
              f"{closest['tick']} (reason={closest.get('reason')}, deficit={closest.get('deficit')})")
        if closest["tailGapX"] > 32:
            print("  -> the player never got near the end; a 'train stopped' complaint from this "
                  "ride is NOT a generation stall.")
        else:
            print("  -> the player REACHED the end of the train; read the AT-TAIL line above it.")

    # The confirming check. The window that picks spawn targets ALSO picks what stays
    # force-loaded, so a window anchored away from the player leaves the groups they are standing
    # among unprotected — the train then ends underneath them with no fault reported anywhere.
    held = [s for s in samples if s.get("heldOccupied") in ("true", "false")]
    if held:
        exposed = [s for s in held if s["heldOccupied"] == "false"]
        print(f"\nHOLD WINDOW: player's own group NOT force-loaded in {len(exposed)}/{len(held)} "
              f"samples ({100*len(exposed)/len(held):.0f}%)")
        outside = [s for s in samples
                   if s.get("occupiedPIdx") is not None and s.get("maxNeeded") is not None
                   and s.get("minNeeded") is not None
                   and not (s["minNeeded"] <= s["occupiedPIdx"] <= s["maxNeeded"])]
        print(f"  player's ACTUAL group outside the window [minNeeded,maxNeeded] in "
              f"{len(outside)}/{len(samples)} samples")
        if exposed:
            w = exposed[-1]
            print(f"  last exposed: tick={w['tick']} occupied={w.get('occupiedPIdx')} "
                  f"window=[{w.get('minNeeded')},{w.get('maxNeeded')}] skew={w.get('skew')} "
                  f"reason={w.get('reason')}")
            print("  -> if this is common while mid-train, the hold window has drifted off the "
                  "player and THAT is the mechanism, not the spawn lane.")

    ondeck = Counter(s.get("onDeck") for s in samples if "onDeck" in s)
    if ondeck:
        print(f"\nride: onDeck={dict(ondeck)} "
              f"modes={dict(Counter(s.get('mode') for s in samples if 'mode' in s))} "
              f"burst={dict(Counter(s.get('burst') for s in samples if 'burst' in s))} "
              f"remote={dict(Counter(s.get('remote') for s in samples if 'remote' in s))}")

    print("\nstate series after the stall (deduped, last 24 changes):")
    for key in ("playerPIdx", "occupiedPIdx", "skew", "playerX", "minNeeded",
                "registryMin", "visibleTail", "span", "registryCount", "visibleCount",
                "deficit", "ticksPending", "latchAge", "forceLoaded", "chunkWait", "tailGapX",
                "outrun", "onDeck", "heldOccupied", "maxNeeded"):
        print(f"  {key:>13}: {series(after, key)}")

    dominant, hits = counts.most_common(1)[0]
    print(f"\nVERDICT ({hits}/{len(after)} post-stall samples = {dominant}):")
    print("  " + VERDICTS.get(dominant, "unmapped reason — read the raw lines below."))
    print("\nlast 3 raw samples:")
    for s in after[-3:]:
        print("  " + s["_raw"])


def main(argv: list[str]) -> int:
    paths = [Path(a) for a in argv[1:]] or [Path("run/logs/debug.log")]
    samples = parse(paths)
    if not samples:
        print("No [bwdgen] lines found. Is the trace on?  In game: "
              "/dungeontrain debug traingen on   (and read debug.log, not latest.log)")
        return 1

    by_train: dict[str, list[dict]] = {}
    for s in samples:
        by_train.setdefault(s.get("train", "?"), []).append(s)

    print(f"parsed {len(samples)} [bwdgen] samples across {len(by_train)} train(s) "
          f"from {', '.join(str(p) for p in paths)}")
    for train, train_samples in by_train.items():
        report(train, train_samples)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
