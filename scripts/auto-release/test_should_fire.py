#!/usr/bin/env python3
"""Unit tests for should-fire.py — exercise every phase boundary."""
import json
import os
import subprocess
import sys
import tempfile
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "should-fire.py")


def iso(epoch):
    return datetime.fromtimestamp(epoch, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def make_state(anchor_epoch=None, last_fire_epoch=None):
    return {
        "schedule_anchor": iso(anchor_epoch) if anchor_epoch else None,
        "last_auto_release_at": iso(last_fire_epoch) if last_fire_epoch else None,
        "last_anchor_source": "test",
    }


def run(state, now_epoch):
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump(state, f)
        state_path = f.name
    try:
        env = {**os.environ, "STATE_FILE": state_path, "NOW_EPOCH": str(now_epoch)}
        env.pop("GITHUB_OUTPUT", None)
        result = subprocess.run([sys.executable, SCRIPT], env=env,
                                capture_output=True, text=True, check=True)
        out = {}
        for line in result.stdout.strip().split("\n"):
            if "=" in line:
                k, v = line.split("=", 1)
                out[k] = v
        return out
    finally:
        os.unlink(state_path)


def expect(out, fire, phase, context):
    if out.get("fire") != fire:
        raise AssertionError(f"[{context}] expected fire={fire}, got fire={out.get('fire')} (full: {out})")
    if out.get("phase") != phase:
        raise AssertionError(f"[{context}] expected phase={phase}, got phase={out.get('phase')} (full: {out})")


T0 = 1_700_000_000  # arbitrary anchor epoch


def test_uninitialized():
    expect(run(make_state(), T0), "false", "uninitialized", "no anchor")


def test_anchor_in_future():
    expect(run(make_state(T0 + 3600), T0), "false", "future", "anchor in future")


def test_phase_a_first_fire():
    expect(run(make_state(T0), T0 + 1800), "true", "A", "phase A first")


def test_phase_a_repeated_fire_after_1h():
    expect(run(make_state(T0, T0 + 1800), T0 + 1800 + 3600), "true", "A", "phase A repeated")


def test_phase_a_skip_too_soon():
    expect(run(make_state(T0, T0 + 1800), T0 + 1800 + 1200), "false", "A", "phase A too soon")


def test_phase_a_fires_within_slack():
    # cron drift: only 0.9*1h = 54min elapsed, should still fire
    expect(run(make_state(T0, T0 + 1800), T0 + 1800 + int(3600 * 0.91)), "true", "A", "phase A within slack")


def test_phase_b_too_soon_after_a():
    last = T0 + int(3.5 * 3600)
    expect(run(make_state(T0, last), T0 + int(4.5 * 3600)), "false", "B", "B too soon after A")


def test_phase_b_fires_after_5h():
    last = T0 + int(3.5 * 3600)
    expect(run(make_state(T0, last), last + 5 * 3600), "true", "B", "B fires after 5h")


def test_phase_c_too_soon_after_b():
    last = T0 + 23 * 3600
    expect(run(make_state(T0, last), T0 + 29 * 3600), "false", "C", "C too soon after B")


def test_phase_c_fires_after_24h():
    last = T0 + 28 * 3600
    expect(run(make_state(T0, last), last + 24 * 3600), "true", "C", "C fires after 24h")


def test_stopped_after_14d():
    expect(
        run(make_state(T0, T0 + int(13.9 * 86400)), T0 + int(14.1 * 86400)),
        "false", "stopped", "stopped after 14d",
    )


def main():
    tests = [
        test_uninitialized,
        test_anchor_in_future,
        test_phase_a_first_fire,
        test_phase_a_repeated_fire_after_1h,
        test_phase_a_skip_too_soon,
        test_phase_a_fires_within_slack,
        test_phase_b_too_soon_after_a,
        test_phase_b_fires_after_5h,
        test_phase_c_too_soon_after_b,
        test_phase_c_fires_after_24h,
        test_stopped_after_14d,
    ]
    failed = 0
    for t in tests:
        try:
            t()
            print(f"OK  {t.__name__}")
        except AssertionError as e:
            print(f"FAIL {t.__name__}: {e}")
            failed += 1
    print(f"\n{len(tests) - failed}/{len(tests)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
