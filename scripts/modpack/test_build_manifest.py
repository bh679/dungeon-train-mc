#!/usr/bin/env python3
"""Unit tests for build-manifest.py — CurseForge modpack manifest rendering.

Invokes the script as a CLI (matching scripts/auto-release/test_apply_change.py) against
an isolated temp config + gradle.properties, so it never depends on repo state.

Run: python3 scripts/modpack/test_build_manifest.py   (or via pytest)
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "build-manifest.py")

CONFIG = {
    "name": "Dungeon Train",
    "author": "Brennan Hatton",
    "dt_project_id": 1527512,
    "sable": {"project_id": 1312371, "file_id": 8003927, "version": "1.2.1+mc1.21.1"},
}
GRADLE_PROPS = "minecraft_version=1.21.1\nneo_version=21.1.228\nmod_version=0.293.0\n"


def run(args, gradle_props=GRADLE_PROPS, config=CONFIG):
    """Run build-manifest.py with isolated config + gradle.properties; return CompletedProcess."""
    ws = tempfile.mkdtemp(prefix="modpack-manifest-test-")
    config_path = os.path.join(ws, "modpack.config.json")
    gradle_path = os.path.join(ws, "gradle.properties")
    with open(config_path, "w") as f:
        json.dump(config, f)
    with open(gradle_path, "w") as f:
        f.write(gradle_props)
    return subprocess.run(
        [sys.executable, SCRIPT, "--config", config_path, "--gradle-properties", gradle_path, *args],
        capture_output=True, text=True,
    )


def test_happy_path_strips_v_and_builds_two_file_manifest():
    proc = run(["--dt-file-id", "8123456", "--version", "v0.293.0"])
    assert proc.returncode == 0, proc.stderr
    m = json.loads(proc.stdout)

    assert m["manifestType"] == "minecraftModpack"
    assert m["manifestVersion"] == 1
    assert m["name"] == "Dungeon Train"
    assert m["author"] == "Brennan Hatton"
    assert m["version"] == "0.293.0"  # leading "v" stripped
    assert m["overrides"] == "overrides"
    assert m["minecraft"]["version"] == "1.21.1"
    assert m["minecraft"]["modLoaders"] == [{"id": "neoforge-21.1.228", "primary": True}]

    assert m["files"] == [
        {"projectID": 1527512, "fileID": 8123456, "required": True},
        {"projectID": 1312371, "fileID": 8003927, "required": True},
    ]


def test_version_without_v_is_unchanged():
    proc = run(["--dt-file-id", "1", "--version", "1.0.0"])
    assert proc.returncode == 0, proc.stderr
    assert json.loads(proc.stdout)["version"] == "1.0.0"


def test_zero_file_id_rejected():
    proc = run(["--dt-file-id", "0", "--version", "0.1.0"])
    assert proc.returncode != 0
    assert "positive" in proc.stderr.lower()


def test_missing_neo_version_rejected():
    proc = run(["--dt-file-id", "5", "--version", "0.1.0"], gradle_props="minecraft_version=1.21.1\n")
    assert proc.returncode != 0
    assert "neo_version" in proc.stderr


def test_missing_sable_key_rejected():
    bad = {**CONFIG, "sable": {"project_id": 1312371, "version": "1.2.1+mc1.21.1"}}  # no file_id
    proc = run(["--dt-file-id", "5", "--version", "0.1.0"], config=bad)
    assert proc.returncode != 0
    assert "file_id" in proc.stderr


def test_optional_mods_appended_as_not_required():
    config = {
        **CONFIG,
        "optional_mods": [
            {"name": "Distant Horizons", "project_id": 508933, "file_id": 7375285},
            {"name": "Tectonic", "project_id": 686836, "file_id": 7903156},
        ],
    }
    proc = run(["--dt-file-id", "8123456", "--version", "0.293.0"], config=config)
    assert proc.returncode == 0, proc.stderr
    files = json.loads(proc.stdout)["files"]
    # DT + Sable are required; the two optional add-ons are appended as required=False.
    assert files[0] == {"projectID": 1527512, "fileID": 8123456, "required": True}
    assert files[1] == {"projectID": 1312371, "fileID": 8003927, "required": True}
    assert {"projectID": 508933, "fileID": 7375285, "required": False} in files
    assert {"projectID": 686836, "fileID": 7903156, "required": False} in files
    assert len(files) == 4


def test_malformed_optional_mod_rejected():
    bad = {**CONFIG, "optional_mods": [{"name": "X", "project_id": 1}]}  # no file_id
    proc = run(["--dt-file-id", "5", "--version", "0.1.0"], config=bad)
    assert proc.returncode != 0
    assert "file_id" in proc.stderr


def test_real_config_includes_appleskin_as_optional():
    """Guard: the shipped modpack.config.json keeps AppleSkin as an on-by-default Include.

    Renders the *real* repo config (not the synthetic CONFIG above) so AppleSkin can't be
    silently dropped from the pack. AppleSkin ships as an optional_mods entry -> required=False
    manifest file, which CurseForge surfaces as an "Include" relation.
    """
    repo_root = os.path.dirname(os.path.dirname(HERE))
    real_config = os.path.join(repo_root, "modpack", "modpack.config.json")
    real_gradle = os.path.join(repo_root, "gradle.properties")
    proc = subprocess.run(
        [sys.executable, SCRIPT, "--config", real_config, "--gradle-properties", real_gradle,
         "--dt-file-id", "8123456", "--version", "0.0.0"],
        capture_output=True, text=True,
    )
    assert proc.returncode == 0, proc.stderr
    files = json.loads(proc.stdout)["files"]
    assert {"projectID": 248787, "fileID": 7854442, "required": False} in files, files


def test_real_config_includes_ferritecore_as_optional():
    """Guard: the shipped modpack.config.json keeps FerriteCore as an on-by-default Include.

    Same contract as the AppleSkin guard above — renders the *real* repo config so FerriteCore
    (a memory-usage reducer) can't be silently dropped from the pack. Ships as an optional_mods
    entry -> required=False manifest file, which CurseForge surfaces as an "Include" relation.
    """
    repo_root = os.path.dirname(os.path.dirname(HERE))
    real_config = os.path.join(repo_root, "modpack", "modpack.config.json")
    real_gradle = os.path.join(repo_root, "gradle.properties")
    proc = subprocess.run(
        [sys.executable, SCRIPT, "--config", real_config, "--gradle-properties", real_gradle,
         "--dt-file-id", "8123456", "--version", "0.0.0"],
        capture_output=True, text=True,
    )
    assert proc.returncode == 0, proc.stderr
    files = json.loads(proc.stdout)["files"]
    assert {"projectID": 429235, "fileID": 7524151, "required": False} in files, files


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
