#!/usr/bin/env python3
"""Unit tests for check-sable-pin.py — the Sable version-chain drift guard.

Invokes the script as a CLI (matching test_check_relations.py) against isolated temp files,
plus one check against the real repo so the shipped Sable pins can't silently drift.

Run: python3 scripts/modpack/test_check_sable_pin.py   (or via pytest)
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-sable-pin.py")
REPO_ROOT = os.path.dirname(os.path.dirname(HERE))


def gradle_props(sable_version=None, sable_mod_version=None):
    """A minimal gradle.properties fragment with (optionally) the two Sable keys."""
    lines = ["# Gradle / JVM", "org.gradle.daemon=true"]
    if sable_version is not None:
        lines.append(f"sable_version={sable_version}")
    if sable_mod_version is not None:
        lines.append(f"sable_mod_version={sable_mod_version}")
    return "\n".join(lines) + "\n"


def run(props_text, config):
    """Run check-sable-pin.py against an isolated temp gradle.properties + config."""
    ws = tempfile.mkdtemp(prefix="modpack-sable-pin-test-")
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


def test_real_repo_pins_are_consistent():
    """The shipped repo must satisfy the invariant — regression lock for the Sable chain."""
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
