#!/usr/bin/env python3
"""Decide whether the auto-release cascade should fire this tick.

Reads .github/auto-release/state.json, computes phase from elapsed time
since schedule_anchor, and decides whether enough time has passed since
last_auto_release_at to fire.

Phases (anchored on last real release):
  A — hourly:     h in (0, 4],   interval 1h
  B — every 5h:   h in (4, 28],  interval 5h
  C — daily:      h in (28, 336], interval 24h
  stopped:        h > 336 (14d)

Writes outputs in GitHub Actions step output format (key=value) to stdout
AND to $GITHUB_OUTPUT if set.

Env overrides (for testing):
  STATE_FILE   default: .github/auto-release/state.json
  NOW_EPOCH    default: current UTC epoch
"""
import json
import os
import sys
import time
from datetime import datetime, timezone

STATE_FILE = os.environ.get("STATE_FILE", ".github/auto-release/state.json")

PHASES = [
    ("A", 4, 3600),
    ("B", 28, 18000),
    ("C", 336, 86400),
]

THRESHOLD_FACTOR = 0.9


def parse_iso(s):
    if s is None:
        return None
    if isinstance(s, str) and s.endswith("Z"):
        s = s[:-1] + "+00:00"
    return int(datetime.fromisoformat(s).timestamp())


def write_output(**kv):
    gh_out = os.environ.get("GITHUB_OUTPUT")
    fh = open(gh_out, "a") if gh_out else None
    for k, v in kv.items():
        line = f"{k}={v}"
        print(line)
        if fh:
            fh.write(line + "\n")
    if fh:
        fh.close()


def main():
    try:
        with open(STATE_FILE) as f:
            state = json.load(f)
    except FileNotFoundError:
        write_output(fire="false", phase="uninitialized",
                     reason=f"state file not found at {STATE_FILE}")
        return 0

    now = int(os.environ.get("NOW_EPOCH", time.time()))
    anchor = parse_iso(state.get("schedule_anchor"))

    if anchor is None:
        write_output(fire="false", phase="uninitialized",
                     reason="schedule_anchor is null - waiting for first real release")
        return 0

    h = (now - anchor) / 3600.0

    if h <= 0:
        write_output(fire="false", phase="future",
                     reason=f"anchor is in the future (h={h:.2f})")
        return 0

    phase = interval = None
    for name, upper_h, ival in PHASES:
        if h <= upper_h:
            phase, interval = name, ival
            break

    if phase is None:
        write_output(fire="false", phase="stopped",
                     reason=f"cascade window elapsed (h={h:.2f} > 336)")
        return 0

    last_fire = parse_iso(state.get("last_auto_release_at"))
    threshold = int(interval * THRESHOLD_FACTOR)

    if last_fire is None:
        write_output(fire="true", phase=phase,
                     reason=f"first fire in phase {phase} (h={h:.2f})")
        return 0

    elapsed = now - last_fire
    if elapsed >= threshold:
        write_output(fire="true", phase=phase,
                     reason=f"phase {phase}: elapsed={elapsed}s >= threshold={threshold}s")
    else:
        write_output(fire="false", phase=phase,
                     reason=f"phase {phase}: elapsed={elapsed}s < threshold={threshold}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
