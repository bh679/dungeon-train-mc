#!/usr/bin/env python3
"""Unit tests for check-relations.py — modpack Include <-> mod optional-dependency guard.

Invokes the script as a CLI (matching test_build_manifest.py) against isolated temp files,
plus one check against the real repo so AppleSkin can't silently lose its mod dependency.

Run: python3 scripts/modpack/test_check_relations.py   (or via pytest)
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-relations.py")
REPO_ROOT = os.path.dirname(os.path.dirname(HERE))


def release_yml(*relations):
    """A minimal release.yml fragment with a curseforge-dependencies literal block."""
    body = "\n".join(f"            {r}" for r in relations)
    return (
        "jobs:\n"
        "  publish:\n"
        "    steps:\n"
        "      - uses: example/mc-publish\n"
        "        with:\n"
        "          curseforge-dependencies: |\n"
        f"{body}\n"
        "          java: 21\n"
    )


def run(config, release_text):
    """Run check-relations.py against an isolated temp config + release.yml."""
    ws = tempfile.mkdtemp(prefix="modpack-relations-test-")
    config_path = os.path.join(ws, "modpack.config.json")
    yml_path = os.path.join(ws, "release.yml")
    with open(config_path, "w") as f:
        json.dump(config, f)
    with open(yml_path, "w") as f:
        f.write(release_text)
    return subprocess.run(
        [sys.executable, SCRIPT, "--config", config_path, "--release-yml", yml_path],
        capture_output=True, text=True,
    )


def test_consistent_include_passes():
    config = {"optional_mods": [{"name": "AppleSkin", "slug": "appleskin", "project_id": 1, "file_id": 2}]}
    proc = run(config, release_yml("sable(required)", "appleskin(optional)", "jade(optional)"))
    assert proc.returncode == 0, proc.stderr


def test_missing_relation_fails():
    """An Include with no matching curseforge-dependencies entry is the PR #390 bug."""
    config = {"optional_mods": [{"name": "AppleSkin", "slug": "appleskin", "project_id": 1, "file_id": 2}]}
    proc = run(config, release_yml("sable(required)", "jade(optional)"))
    assert proc.returncode != 0


def _sibling(**overrides):
    """An un-bundled sibling mod entry: DT hard-depends on it, so it must be `required`."""
    return {
        "name": "Interactive Player Mobs",
        "slug": "interactive-player-mobs",
        "project_id": 1559379,
        "file_id": 8452691,
        "required": True,
        "dependency_type": "required",
        **overrides,
    }


def test_sibling_declared_required_passes():
    proc = run(
        {"optional_mods": [_sibling()]},
        release_yml("sable(required)", "interactive-player-mobs(required)"),
    )
    assert proc.returncode == 0, proc.stderr


def test_sibling_declared_optional_fails():
    """A hard dependency declared merely `optional` wouldn't be auto-installed by the apps."""
    proc = run(
        {"optional_mods": [_sibling()]},
        release_yml("sable(required)", "interactive-player-mobs(optional)"),
    )
    assert proc.returncode != 0
    assert "required" in proc.stderr


def test_required_pack_flag_alone_does_not_imply_required_dependency():
    """`required: true` means 'enabled by default in the pack', NOT 'the mod needs it'.

    AppleSkin ships switched on yet DT runs fine without it, so it stays an optional
    dependency. Guards against re-deriving the relation type from the wrong flag.
    """
    config = {"optional_mods": [
        {"name": "AppleSkin", "slug": "appleskin", "project_id": 1, "file_id": 2, "required": True}
    ]}
    proc = run(config, release_yml("appleskin(optional)"))
    assert proc.returncode == 0, proc.stderr


def test_wrong_relation_type_fails():
    config = {"optional_mods": [{"name": "AppleSkin", "slug": "appleskin", "project_id": 1, "file_id": 2}]}
    proc = run(config, release_yml("appleskin(required)"))
    assert proc.returncode != 0
    assert "optional" in proc.stderr.lower()


def test_missing_slug_fails():
    """optional_mods entries must carry a slug so the cross-check has something to match."""
    config = {"optional_mods": [{"name": "AppleSkin", "project_id": 1, "file_id": 2}]}
    proc = run(config, release_yml("appleskin(optional)"))
    assert proc.returncode != 0
    assert "slug" in proc.stderr.lower()


def test_no_optional_mods_passes():
    proc = run({}, release_yml("sable(required)"))
    assert proc.returncode == 0, proc.stderr


def test_multiple_includes_one_missing_fails():
    config = {
        "optional_mods": [
            {"name": "AppleSkin", "slug": "appleskin", "project_id": 1, "file_id": 2},
            {"name": "Other", "slug": "other-mod", "project_id": 3, "file_id": 4},
        ]
    }
    proc = run(config, release_yml("appleskin(optional)"))  # other-mod missing
    assert proc.returncode != 0
    assert "other-mod" in proc.stderr.lower()


def test_real_repo_config_is_consistent():
    """The shipped repo must satisfy the invariant — regression lock for AppleSkin."""
    proc = subprocess.run(
        [sys.executable, SCRIPT,
         "--config", os.path.join(REPO_ROOT, "modpack", "modpack.config.json"),
         "--release-yml", os.path.join(REPO_ROOT, ".github", "workflows", "release.yml")],
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
