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


def run(state, now_epoch, extra_env=None):
    """Run should-fire.py in a subprocess.

    By default, sibling checks are neutralised by pointing GRADLE_PROPERTIES_FILE
    at a nonexistent path and setting both sibling release overrides to empty
    JSON arrays — pending_sibling_update() returns None and the script falls
    through to time-based phasing. Tests that exercise the sibling override
    must pass extra_env to provide a real gradle.properties path and override
    contents.
    """
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump(state, f)
        state_path = f.name
    try:
        env = {
            **os.environ,
            "STATE_FILE": state_path,
            "NOW_EPOCH": str(now_epoch),
            "GRADLE_PROPERTIES_FILE": "/nonexistent/gradle.properties",
            "AIN_RELEASES_OVERRIDE": "[]",
            "AIS_RELEASES_OVERRIDE": "[]",
        }
        env.pop("GITHUB_OUTPUT", None)
        env.pop("AUTO_RELEASE_MODE", None)
        if extra_env:
            env.update(extra_env)
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


def test_cascade_stopped_flag_short_circuits():
    # Even inside phase A (which would normally fire), the flag stops the cascade.
    state = make_state(T0, None)
    state["cascade_stopped"] = True
    expect(run(state, T0 + 1800), "false", "stopped", "cascade_stopped flag")


def test_cascade_stopped_false_is_ignored():
    # Explicit false should behave the same as the field being absent.
    state = make_state(T0, None)
    state["cascade_stopped"] = False
    expect(run(state, T0 + 1800), "true", "A", "cascade_stopped=false is no-op")


# ---------------------------------------------------------------------------
# Sibling-pending override tests
# ---------------------------------------------------------------------------


def make_gradle(ain="0.27.0", ais="0.10.0"):
    """Write a minimal gradle.properties to a temp file and return its path."""
    with tempfile.NamedTemporaryFile("w", suffix=".properties", delete=False) as f:
        if ain is not None:
            f.write(f"adventureitemnames_version={ain}\n")
        if ais is not None:
            f.write(f"adventureitemstats_version={ais}\n")
        return f.name


def sibling_env(gradle_path, ain_releases=None, ais_releases=None, mode=None):
    env = {"GRADLE_PROPERTIES_FILE": gradle_path}
    if ain_releases is not None:
        env["AIN_RELEASES_OVERRIDE"] = json.dumps(
            [{"tagName": f"v{v}"} for v in ain_releases]
        )
    if ais_releases is not None:
        env["AIS_RELEASES_OVERRIDE"] = json.dumps(
            [{"tagName": f"v{v}"} for v in ais_releases]
        )
    if mode is not None:
        env["AUTO_RELEASE_MODE"] = mode
    return env


def test_sibling_pending_overrides_phase_b():
    # Anchor 10h ago — time-based phase would be B. AIN has a pending update.
    # Last fire 1h ago, well past the 0.9*1h threshold → must fire as phase A.
    gradle = make_gradle(ain="0.27.0")
    try:
        last = T0 + int(9 * 3600)
        out = run(make_state(T0, last), T0 + int(10 * 3600),
                  extra_env=sibling_env(gradle, ain_releases=["0.28.0"]))
        expect(out, "true", "A", "AIN pending overrides B")
    finally:
        os.unlink(gradle)


def test_sibling_pending_overrides_phase_c():
    # Anchor 5d ago — time-based phase would be C. AIS has a pending update.
    gradle = make_gradle(ais="0.10.0")
    try:
        last = T0 + int(4.95 * 86400)
        out = run(make_state(T0, last), T0 + int(5 * 86400),
                  extra_env=sibling_env(gradle, ais_releases=["0.11.0"]))
        expect(out, "true", "A", "AIS pending overrides C")
    finally:
        os.unlink(gradle)


def test_no_sibling_update_falls_back_to_b():
    # Anchor 10h ago. Both siblings caught up. Should behave as the existing
    # B-phase logic: last fire 1h ago is below B's 0.9*5h threshold → no fire.
    gradle = make_gradle(ain="0.28.0", ais="0.11.0")
    try:
        last = T0 + int(9 * 3600)
        out = run(make_state(T0, last), T0 + int(10 * 3600),
                  extra_env=sibling_env(gradle, ain_releases=["0.28.0"], ais_releases=["0.11.0"]))
        expect(out, "false", "B", "no sibling update → B")
    finally:
        os.unlink(gradle)


def test_sibling_pending_respects_threshold():
    # AIN pending pins to phase A, but last fire was 30min ago — below the
    # 0.9*1h threshold. Override must not bypass the threshold.
    gradle = make_gradle(ain="0.27.0")
    try:
        last = T0 + 1800
        out = run(make_state(T0, last), T0 + 1800 + 1800,
                  extra_env=sibling_env(gradle, ain_releases=["0.28.0"]))
        expect(out, "false", "A", "sibling pending but threshold gates")
    finally:
        os.unlink(gradle)


def test_mode_ain_ignores_ais_pending():
    # In mode=ain, an AIS-only update must NOT trigger the override.
    # Anchor 10h ago → time-based phase B; last fire 1h ago → B threshold not
    # met → fire=false phase=B (existing behavior preserved).
    gradle = make_gradle(ain="0.28.0", ais="0.10.0")
    try:
        last = T0 + int(9 * 3600)
        out = run(make_state(T0, last), T0 + int(10 * 3600),
                  extra_env=sibling_env(gradle,
                                        ain_releases=["0.28.0"],
                                        ais_releases=["0.11.0"],
                                        mode="ain"))
        expect(out, "false", "B", "mode=ain ignores AIS pending")
    finally:
        os.unlink(gradle)


def test_mode_ais_ignores_ain_pending():
    # In mode=ais, an AIN-only update must NOT trigger the override.
    gradle = make_gradle(ain="0.27.0", ais="0.11.0")
    try:
        last = T0 + int(9 * 3600)
        out = run(make_state(T0, last), T0 + int(10 * 3600),
                  extra_env=sibling_env(gradle,
                                        ain_releases=["0.28.0"],
                                        ais_releases=["0.11.0"],
                                        mode="ais"))
        expect(out, "false", "B", "mode=ais ignores AIN pending")
    finally:
        os.unlink(gradle)


def test_sibling_check_skipped_when_stopped_past_14d():
    # 14d cap wins over sibling override — bounded cascade lifetime.
    gradle = make_gradle(ain="0.27.0")
    try:
        out = run(make_state(T0, T0 + int(13.9 * 86400)),
                  T0 + int(14.1 * 86400),
                  extra_env=sibling_env(gradle, ain_releases=["0.28.0"]))
        expect(out, "false", "stopped", "14d cap wins over sibling override")
    finally:
        os.unlink(gradle)


def test_network_error_falls_through():
    # Invalid override JSON → treated as no releases → no override → falls
    # back to time-based phasing. Anchor 10h ago, last fire 1h ago → B
    # threshold not met.
    gradle = make_gradle(ain="0.27.0")
    try:
        last = T0 + int(9 * 3600)
        out = run(make_state(T0, last), T0 + int(10 * 3600),
                  extra_env={"GRADLE_PROPERTIES_FILE": gradle,
                             "AIN_RELEASES_OVERRIDE": "not-json",
                             "AIS_RELEASES_OVERRIDE": "[]"})
        expect(out, "false", "B", "malformed override → no override")
    finally:
        os.unlink(gradle)


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
        test_cascade_stopped_flag_short_circuits,
        test_cascade_stopped_false_is_ignored,
        test_sibling_pending_overrides_phase_b,
        test_sibling_pending_overrides_phase_c,
        test_no_sibling_update_falls_back_to_b,
        test_sibling_pending_respects_threshold,
        test_mode_ain_ignores_ais_pending,
        test_mode_ais_ignores_ain_pending,
        test_sibling_check_skipped_when_stopped_past_14d,
        test_network_error_falls_through,
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
