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
        "H1 — registry-edge deadlock. The backward edge sub-level is neither visible, "
        "held, nor resident, so resolveEdgeReference defers forever. Ghost cleanup "
        "cannot rescue it: that only runs when a cull latch exists."),
    "EDGE_RELOAD": (
        "H1b — the backward edge is stuck in Sable holding and never surfaces after "
        "reloadFromHolding. Check the [bwdgen] edgeSub field: a constant id means the "
        "same sub-level is failing to come back."),
    "NO_NEED": (
        "H2 or H5 — the needed window stopped extending past the registry's min anchor. "
        "Read span: LARGE/growing means ghost anchors (H2, registry min far below the "
        "visible tail). span≈0 with growing skew, or a flat minNeeded while playerX "
        "keeps falling, means frame divergence (H5, the window is computed in the lead "
        "group's frame). Cross-check tailGapX: if it stays large the train IS behind the player "
        "and the complaint is not a generation stall at all."),
    "ANCHOR_KNOWN": (
        "H2 variant — the next backward anchor is already registered but not visible: "
        "a ghost sits exactly where the lane wants to spawn."),
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
        "H0 — no player within NEAR_RADIUS (128) of any group AABB. If the player was "
        "aboard the whole time, this IS the bug: the near test measures group world "
        "AABBs, and a culled group reports a zero AABB."),
}

INT_FIELDS = {
    "tick", "blockedFor", "playerPIdx", "skew", "minNeeded", "registryMin",
    "visibleTail", "span", "registryCount", "visibleCount", "anchor", "deficit",
    "ticksPending", "latchAge", "forceLoaded", "chunkWait", "target",
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

    print("\nstate series after the stall (deduped, last 24 changes):")
    for key in ("playerPIdx", "occupiedPIdx", "skew", "playerX", "minNeeded",
                "registryMin", "visibleTail", "span", "registryCount", "visibleCount",
                "deficit", "ticksPending", "latchAge", "forceLoaded", "chunkWait", "tailGapX"):
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
