#!/usr/bin/env python3
"""Unit tests for check-pins.py — the dependency version-pin drift guard.

Covers both contracts the script enforces: the Sable version *chain* (exact equality) and
the un-bundled sibling mods' minimum-version *floors* (modpack pin >= declared floor).

Invokes the script as a CLI (matching test_check_relations.py) against isolated temp files,
plus one check against the real repo so the shipped pins can't silently drift.

Run: python3 scripts/modpack/test_check_pins.py   (or via pytest)
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-pins.py")
REPO_ROOT = os.path.dirname(os.path.dirname(HERE))


def gradle_props(sable_version=None, sable_mod_version=None, **extra):
    """A minimal gradle.properties fragment with (optionally) the two Sable keys.

    Extra keyword args become additional ``key=value`` lines — used to supply sibling
    ``<mod>_min_version`` floors.
    """
    lines = ["# Gradle / JVM", "org.gradle.daemon=true"]
    if sable_version is not None:
        lines.append(f"sable_version={sable_version}")
    if sable_mod_version is not None:
        lines.append(f"sable_mod_version={sable_mod_version}")
    lines.extend(f"{k}={v}" for k, v in extra.items())
    return "\n".join(lines) + "\n"


def sable_ok(**overrides):
    """A valid Sable block, so sibling-focused tests don't trip the Sable checks."""
    return {"version": "2.0.2+mc1.21.1", **overrides}


def run(props_text, config):
    """Run check-pins.py against an isolated temp gradle.properties + config."""
    ws = tempfile.mkdtemp(prefix="modpack-pins-test-")
    props_path = os.path.join(ws, "gradle.properties")
    config_path = os.path.join(ws, "modpack.config.json")
    with open(props_path, "w") as f:
        f.write(props_text)
    with open(config_path, "w") as f:
        json.dump(config, f)
    return subprocess.run(
        [sys.executable, SCRIPT, "--gradle-properties", props_path, "--config", config_path],
        capture_output=True, text=True,
    )


def test_consistent_pins_pass():
    proc = run(
        gradle_props("2.0.2+mc1.21.1", "2.0.2"),
        {"sable": {"version": "2.0.2+mc1.21.1", "file_id": 1, "modrinth_version": "x"}},
    )
    assert proc.returncode == 0, proc.stderr


def test_mod_version_mismatch_fails():
    """sable_mod_version that isn't sable_version's leading semver -> load-blocking lock."""
    proc = run(
        gradle_props("2.0.2+mc1.21.1", "2.0.1"),
        {"sable": {"version": "2.0.2+mc1.21.1"}},
    )
    assert proc.returncode != 0
    assert "sable_mod_version" in proc.stderr


def test_config_version_mismatch_fails():
    """modpack sable.version diverging from sable_version -> pack ships wrong Sable."""
    proc = run(
        gradle_props("2.0.2+mc1.21.1", "2.0.2"),
        {"sable": {"version": "1.2.1+mc1.21.1"}},
    )
    assert proc.returncode != 0
    assert "sable.version" in proc.stderr


def test_missing_sable_mod_version_fails():
    proc = run(
        gradle_props("2.0.2+mc1.21.1", None),
        {"sable": {"version": "2.0.2+mc1.21.1"}},
    )
    assert proc.returncode != 0
    assert "sable_mod_version" in proc.stderr


def test_missing_sable_version_fails():
    proc = run(
        gradle_props(None, "2.0.2"),
        {"sable": {"version": "2.0.2+mc1.21.1"}},
    )
    assert proc.returncode != 0
    assert "sable_version" in proc.stderr


def test_missing_sable_object_fails():
    proc = run(gradle_props("2.0.2+mc1.21.1", "2.0.2"), {})
    assert proc.returncode != 0
    assert "sable" in proc.stderr.lower()


def test_no_build_suffix_pass():
    """A Sable coordinate without a +mc suffix: semver == the whole string."""
    proc = run(
        gradle_props("2.0.2", "2.0.2"),
        {"sable": {"version": "2.0.2"}},
    )
    assert proc.returncode == 0, proc.stderr


# ── sibling minimum-version floors ───────────────────────────────────────────────────

def _sibling_config(pinned_version):
    """A config whose single sibling entry pins ``pinned_version`` against playermob's floor."""
    return {
        "sable": sable_ok(),
        "optional_mods": [{
            "name": "Interactive Player Mobs",
            "slug": "interactive-player-mobs",
            "version": pinned_version,
            "gradle_property": "playermob_min_version",
        }],
    }


def test_sibling_pin_equal_to_floor_passes():
    proc = run(
        gradle_props("2.0.2+mc1.21.1", "2.0.2", playermob_min_version="0.82.0"),
        _sibling_config("0.82.0"),
    )
    assert proc.returncode == 0, proc.stderr


def test_sibling_pin_newer_than_floor_passes():
    """A floor is a minimum, not equality — the pack may ship something newer."""
    proc = run(
        gradle_props("2.0.2+mc1.21.1", "2.0.2", playermob_min_version="0.82.0"),
        _sibling_config("0.90.0"),
    )
    assert proc.returncode == 0, proc.stderr


def test_sibling_pin_older_than_floor_fails():
    """The load-blocking case: pack installs a sibling the mod refuses to run against."""
    proc = run(
        gradle_props("2.0.2+mc1.21.1", "2.0.2", playermob_min_version="0.82.0"),
        _sibling_config("0.50.0"),
    )
    assert proc.returncode != 0
    assert "Interactive Player Mobs" in proc.stderr


def test_cascade_bump_of_build_version_does_not_trip_guard():
    """Regression lock for WHY the floor exists at all.

    The auto-release cascade rewrites <mod>_version every tick. Only <mod>_min_version is
    checked, so a bumped build version must not invalidate the modpack's pin — otherwise
    CI would fail on ~22 ticks per release cycle.
    """
    proc = run(
        gradle_props(
            "2.0.2+mc1.21.1", "2.0.2",
            playermob_version="0.95.0",        # cascade moved this
            playermob_min_version="0.82.0",    # floor deliberately did not move
        ),
        _sibling_config("0.82.0"),
    )
    assert proc.returncode == 0, proc.stderr


def test_sibling_missing_floor_key_fails():
    proc = run(
        gradle_props("2.0.2+mc1.21.1", "2.0.2"),  # no playermob_min_version at all
        _sibling_config("0.82.0"),
    )
    assert proc.returncode != 0
    assert "playermob_min_version" in proc.stderr


def test_sibling_missing_version_field_fails():
    config = {
        "sable": sable_ok(),
        "optional_mods": [{
            "name": "Interactive Player Mobs",
            "gradle_property": "playermob_min_version",
        }],
    }
    proc = run(gradle_props("2.0.2+mc1.21.1", "2.0.2", playermob_min_version="0.82.0"), config)
    assert proc.returncode != 0
    assert "version" in proc.stderr


def test_non_semver_sibling_version_fails():
    proc = run(
        gradle_props("2.0.2+mc1.21.1", "2.0.2", playermob_min_version="0.82.0"),
        _sibling_config("latest"),
    )
    assert proc.returncode != 0
    assert "X.Y.Z" in proc.stderr


def test_third_party_entry_without_gradle_property_is_skipped():
    """AppleSkin et al. answer to no DT floor — absence of gradle_property must not error."""
    config = {
        "sable": sable_ok(),
        "optional_mods": [{"name": "AppleSkin", "slug": "appleskin", "required": True}],
    }
    proc = run(gradle_props("2.0.2+mc1.21.1", "2.0.2"), config)
    assert proc.returncode == 0, proc.stderr


# ── modpack-lag warning (advisory, must never fail CI) ───────────────────────────────

def test_modpack_behind_build_version_warns_but_passes():
    """The PR #796 case: a sibling bumped, but the pack still pins the old build.

    Must warn — modpack players won't get the change — but must NOT fail, because the
    cascade creates this gap ~22 times per release and a failing guard would be ignored.
    """
    proc = run(
        gradle_props(
            "2.0.2+mc1.21.1", "2.0.2",
            playermob_version="0.90.0",
            playermob_min_version="0.82.0",
        ),
        _sibling_config("0.82.0"),
    )
    assert proc.returncode == 0, proc.stderr
    assert "WARNING" in proc.stderr
    assert "0.90.0" in proc.stderr


def test_modpack_level_with_build_version_is_silent():
    proc = run(
        gradle_props(
            "2.0.2+mc1.21.1", "2.0.2",
            playermob_version="0.82.0",
            playermob_min_version="0.82.0",
        ),
        _sibling_config("0.82.0"),
    )
    assert proc.returncode == 0, proc.stderr
    assert "WARNING" not in proc.stderr


def test_floor_violation_still_fails_even_though_lag_is_only_a_warning():
    """The new advisory check must not soften the hard error it sits beside."""
    proc = run(
        gradle_props(
            "2.0.2+mc1.21.1", "2.0.2",
            playermob_version="0.90.0",
            playermob_min_version="0.85.0",
        ),
        _sibling_config("0.50.0"),   # below the floor AND behind the build version
    )
    assert proc.returncode != 0
    assert "at least 0.85.0" in proc.stderr


def test_real_repo_pins_are_consistent():
    """The shipped repo must satisfy both invariants — Sable chain and sibling floors."""
    proc = subprocess.run(
        [sys.executable, SCRIPT,
         "--gradle-properties", os.path.join(REPO_ROOT, "gradle.properties"),
         "--config", os.path.join(REPO_ROOT, "modpack", "modpack.config.json")],
        capture_output=True, text=True,
    )
    assert proc.returncode == 0, proc.stderr


def _main():
    funcs = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    failures = 0
    for fn in funcs:
        try:
            fn()
            print(f"ok   {fn.__name__}")
        except AssertionError as exc:
            failures += 1
            print(f"FAIL {fn.__name__}: {exc}")
    print(f"\n{len(funcs) - failures}/{len(funcs)} passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(_main())
