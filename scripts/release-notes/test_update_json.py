#!/usr/bin/env python3
"""Unit tests for build-update-json.py (the NeoForge update.json generator).

Subprocess-based, mirroring test_release_notes.py. Runnable directly
(`python3 scripts/release-notes/test_update_json.py`) and discoverable by pytest
(bare test_* functions). Local-only — not CI-gated, matching the convention.
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(HERE, "build-update-json.py")
DEFAULT_HOMEPAGE = "https://github.com/bh679/dungeon-train-mc"


def workspace_file() -> str:
    ws = tempfile.mkdtemp(prefix="update-json-test-")
    return os.path.join(ws, "update.json")


def write_json(path: str, data: dict) -> None:
    with open(path, "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def read_json(path: str) -> dict:
    with open(path) as f:
        return json.load(f)


def run(path: str, *args: str) -> subprocess.CompletedProcess:
    env = {**os.environ, "UPDATE_JSON_FILE": path}
    env.pop("GITHUB_OUTPUT", None)
    return subprocess.run(
        [sys.executable, BUILD, "--file", path, *args],
        env=env, capture_output=True, text=True,
    )


# ---------------------------------------------------------------------------
# fresh generation
# ---------------------------------------------------------------------------

def test_creates_file_when_absent() -> None:
    path = workspace_file()
    r = run(path, "--version", "0.485.0", "--mc", "1.21.1")
    assert r.returncode == 0, r.stderr
    data = read_json(path)
    assert data["homepage"] == DEFAULT_HOMEPAGE
    assert data["promos"]["1.21.1-latest"] == "0.485.0"
    assert data["promos"]["1.21.1-recommended"] == "0.485.0"
    assert "0.485.0" in data["1.21.1"]


def test_default_changelog_points_at_release_tag() -> None:
    path = workspace_file()
    r = run(path, "--version", "0.485.0", "--mc", "1.21.1")
    assert r.returncode == 0, r.stderr
    assert read_json(path)["1.21.1"]["0.485.0"].endswith("/releases/tag/v0.485.0")


def test_custom_changelog_used() -> None:
    path = workspace_file()
    r = run(path, "--version", "0.485.0", "--mc", "1.21.1", "--changelog", "New band added.")
    assert r.returncode == 0, r.stderr
    assert read_json(path)["1.21.1"]["0.485.0"] == "New band added."


def test_strips_leading_v() -> None:
    path = workspace_file()
    r = run(path, "--version", "v0.485.0", "--mc", "1.21.1")
    assert r.returncode == 0, r.stderr
    assert read_json(path)["promos"]["1.21.1-latest"] == "0.485.0"


# ---------------------------------------------------------------------------
# merge / promotion behaviour
# ---------------------------------------------------------------------------

def test_merge_preserves_prior_versions_and_repoints_promos() -> None:
    path = workspace_file()
    write_json(path, {
        "homepage": DEFAULT_HOMEPAGE,
        "1.21.1": {"0.484.0": "old"},
        "promos": {"1.21.1-latest": "0.484.0", "1.21.1-recommended": "0.484.0"},
    })
    r = run(path, "--version", "0.485.0", "--mc", "1.21.1")
    assert r.returncode == 0, r.stderr
    data = read_json(path)
    assert data["1.21.1"]["0.484.0"] == "old"       # prior preserved
    assert "0.485.0" in data["1.21.1"]              # new added
    assert data["promos"]["1.21.1-latest"] == "0.485.0"  # repointed


def test_other_mc_versions_untouched() -> None:
    path = workspace_file()
    write_json(path, {
        "homepage": DEFAULT_HOMEPAGE,
        "1.20.1": {"0.400.0": "legacy"},
        "promos": {"1.20.1-latest": "0.400.0", "1.20.1-recommended": "0.400.0"},
    })
    r = run(path, "--version", "0.485.0", "--mc", "1.21.1")
    assert r.returncode == 0, r.stderr
    data = read_json(path)
    assert data["1.20.1"] == {"0.400.0": "legacy"}
    assert data["promos"]["1.20.1-latest"] == "0.400.0"      # untouched
    assert data["promos"]["1.21.1-latest"] == "0.485.0"      # added


def test_preserves_existing_homepage() -> None:
    path = workspace_file()
    write_json(path, {"homepage": "https://example.com/custom", "promos": {}})
    r = run(path, "--version", "0.485.0", "--mc", "1.21.1", "--homepage", "https://ignored")
    assert r.returncode == 0, r.stderr
    assert read_json(path)["homepage"] == "https://example.com/custom"


def test_idempotent_rerun() -> None:
    path = workspace_file()
    run(path, "--version", "0.485.0", "--mc", "1.21.1")
    first = read_json(path)
    run(path, "--version", "0.485.0", "--mc", "1.21.1")
    assert read_json(path) == first


def test_cap_keeps_most_recent_newest_first() -> None:
    path = workspace_file()
    existing = {v: "x" for v in ["0.480.0", "0.481.0", "0.482.0"]}
    write_json(path, {"homepage": DEFAULT_HOMEPAGE, "1.21.1": existing, "promos": {}})
    r = run(path, "--version", "0.483.0", "--mc", "1.21.1", "--cap", "2")
    assert r.returncode == 0, r.stderr
    keys = list(read_json(path)["1.21.1"].keys())
    assert keys == ["0.483.0", "0.482.0"], keys  # capped to 2, newest-first


# ---------------------------------------------------------------------------
# validation
# ---------------------------------------------------------------------------

def test_rejects_non_semver_version() -> None:
    path = workspace_file()
    r = run(path, "--version", "1.2", "--mc", "1.21.1")
    assert r.returncode == 1
    assert not os.path.exists(path)  # nothing written on failure


def test_rejects_bad_cap() -> None:
    path = workspace_file()
    r = run(path, "--version", "0.485.0", "--mc", "1.21.1", "--cap", "0")
    assert r.returncode == 1


def main() -> int:
    tests = [
        test_creates_file_when_absent,
        test_default_changelog_points_at_release_tag,
        test_custom_changelog_used,
        test_strips_leading_v,
        test_merge_preserves_prior_versions_and_repoints_promos,
        test_other_mc_versions_untouched,
        test_preserves_existing_homepage,
        test_idempotent_rerun,
        test_cap_keeps_most_recent_newest_first,
        test_rejects_non_semver_version,
        test_rejects_bad_cap,
    ]
    failed = 0
    for t in tests:
        try:
            t()
        except AssertionError as e:
            print(f"FAIL {t.__name__}: {e}")
            failed += 1
        except Exception as e:  # noqa: BLE001 — surface unexpected errors per-test
            print(f"ERROR {t.__name__}: {type(e).__name__}: {e}")
            failed += 1
    print(f"\n{len(tests) - failed}/{len(tests)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
