#!/usr/bin/env python3
"""Decide whether the auto-release cascade should fire this tick.

Reads .github/auto-release/state.json, computes phase from elapsed time
since schedule_anchor, and decides whether enough time has passed since
last_auto_release_at to fire.

Default phases (anchored on last real release):
  A — hourly:     h in (0, 4],    interval 1h    (4 fires)
  B — every 5h:   h in (4, 29],   interval 5h    (5 fires)
  C — daily:      h in (29, 341], interval 24h   (13 fires)
  stopped:        h > 341 (~14d)

Cadence override: when repo variable AUTO_RELEASE_CADENCE is set to a JSON
string of the shape:

  {"tiers": [{"name": "A", "interval_minutes": 60, "count": 4}, ...]}

the cadence is rebuilt from those tiers. Phase upper bounds are the running
cumulative sum of (count * interval_minutes / 60). Malformed JSON or schema
violations emit a ::warning:: annotation and fall back to defaults.

Sibling-pending override: when a sibling mod (AIN, AIS, or PMOB; filtered by
AUTO_RELEASE_MODE) has a GitHub release that DT is behind on, the cascade pins
to the first tier's interval regardless of elapsed time. The stopped boundary
(last tier's upper bound) still wins.

Writes outputs in GitHub Actions step output format (key=value) to stdout
AND to $GITHUB_OUTPUT if set.

Env overrides (for testing):
  STATE_FILE              default: .github/auto-release/state.json
  GRADLE_PROPERTIES_FILE  default: gradle.properties
  AUTO_RELEASE_MODE       default: always
  AUTO_RELEASE_CADENCE    default: unset; JSON cadence config (see above)
  AIN_RELEASES_OVERRIDE   default: unset; JSON array of {tagName: "v..."}
  AIS_RELEASES_OVERRIDE   default: unset; same shape
  PMOB_RELEASES_OVERRIDE  default: unset; same shape
  NOW_EPOCH               default: current UTC epoch
"""
import json
import os
import sys
import time
from datetime import datetime, timezone

from siblings import parse_mode, pending_sibling_update

STATE_FILE = os.environ.get("STATE_FILE", ".github/auto-release/state.json")

DEFAULT_TIERS = (
    ("A", 60, 4),
    ("B", 300, 5),
    ("C", 1440, 13),
)

THRESHOLD_FACTOR = 0.9


def _is_positive_int(v) -> bool:
    return isinstance(v, int) and not isinstance(v, bool) and v >= 1


def _validate_tier(t) -> bool:
    if not isinstance(t, dict):
        return False
    if not isinstance(t.get("name"), str) or not t["name"]:
        return False
    if not _is_positive_int(t.get("interval_minutes")):
        return False
    if not _is_positive_int(t.get("count")):
        return False
    return True


def _tiers_to_phases(tiers):
    """Convert (name, interval_minutes, count) tiers to (name, upper_h, interval_seconds).

    upper_h is the running cumulative sum so the phase-picking loop in main()
    can stay unchanged.
    """
    phases = []
    cumulative_h = 0.0
    for name, interval_minutes, count in tiers:
        cumulative_h += (count * interval_minutes) / 60.0
        phases.append((name, cumulative_h, interval_minutes * 60))
    return phases


def load_cadence():
    """Read AUTO_RELEASE_CADENCE and return phases in main()-ready shape.

    Falls back to DEFAULT_TIERS on unset / malformed / schema-invalid input,
    emitting a ::warning:: annotation for the malformed and schema-invalid
    cases.
    """
    raw = os.environ.get("AUTO_RELEASE_CADENCE", "").strip()
    if not raw:
        return _tiers_to_phases(DEFAULT_TIERS)

    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as e:
        print(f"::warning::AUTO_RELEASE_CADENCE is not valid JSON: {e} — using defaults",
              file=sys.stderr)
        return _tiers_to_phases(DEFAULT_TIERS)

    if not isinstance(parsed, dict):
        print("::warning::AUTO_RELEASE_CADENCE must be a JSON object — using defaults",
              file=sys.stderr)
        return _tiers_to_phases(DEFAULT_TIERS)

    raw_tiers = parsed.get("tiers")
    if not isinstance(raw_tiers, list) or not raw_tiers:
        print("::warning::AUTO_RELEASE_CADENCE.tiers must be a non-empty array — using defaults",
              file=sys.stderr)
        return _tiers_to_phases(DEFAULT_TIERS)

    for i, t in enumerate(raw_tiers):
        if not _validate_tier(t):
            print(f"::warning::AUTO_RELEASE_CADENCE.tiers[{i}] failed schema "
                  f"(needs name:str, interval_minutes:int>=1, count:int>=1) — using defaults",
                  file=sys.stderr)
            return _tiers_to_phases(DEFAULT_TIERS)

    tiers = tuple((t["name"], t["interval_minutes"], t["count"]) for t in raw_tiers)
    return _tiers_to_phases(tiers)


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
    phases = load_cadence()
    stopped_cap_h = phases[-1][1]

    try:
        with open(STATE_FILE) as f:
            state = json.load(f)
    except FileNotFoundError:
        write_output(fire="false", phase="uninitialized",
                     reason=f"state file not found at {STATE_FILE}")
        return 0

    if state.get("cascade_stopped"):
        write_output(fire="false", phase="stopped",
                     reason="cascade stopped pending next real release")
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

    # Stopped boundary (last tier's upper bound) wins over sibling override —
    # bounded cascade lifetime.
    if h > stopped_cap_h:
        write_output(fire="false", phase="stopped",
                     reason=f"cascade window elapsed (h={h:.2f} > {stopped_cap_h:.2f})")
        return 0

    # Sibling-pending override: pin to the first tier's interval while
    # AIN/AIS (filtered by mode) has a release DT is behind on.
    mode = parse_mode()
    pending = pending_sibling_update(mode)
    override_note = None
    if pending is not None:
        phase, interval = phases[0][0], phases[0][2]
        override_note = f"{pending} update pending"
    else:
        phase = interval = None
        for name, upper_h, ival in phases:
            if h <= upper_h:
                phase, interval = name, ival
                break

    last_fire = parse_iso(state.get("last_auto_release_at"))
    threshold = int(interval * THRESHOLD_FACTOR)

    if last_fire is None:
        reason = f"first fire in phase {phase} (h={h:.2f})"
        if override_note:
            reason = f"{override_note}; {reason}"
        write_output(fire="true", phase=phase, reason=reason)
        return 0

    elapsed = now - last_fire
    if elapsed >= threshold:
        reason = f"phase {phase}: elapsed={elapsed}s >= threshold={threshold}s"
        if override_note:
            reason = f"{override_note}; {reason}"
        write_output(fire="true", phase=phase, reason=reason)
    else:
        reason = f"phase {phase}: elapsed={elapsed}s < threshold={threshold}s"
        if override_note:
            reason = f"{override_note}; {reason}"
        write_output(fire="false", phase=phase, reason=reason)
    return 0


if __name__ == "__main__":
    sys.exit(main())
