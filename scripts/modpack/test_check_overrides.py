#!/usr/bin/env python3
"""Unit tests for check-overrides.py — the modpack override allowlist guard.

Invokes the script as a CLI (matching test_check_relations.py) against isolated temp trees,
plus one check against the real modpack/overrides/ so a config file the mod holds to its
defaults can never be added to the pack unnoticed.

Run: python3 scripts/modpack/test_check_overrides.py   (or via pytest)
"""
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-overrides.py")
REPO_ROOT = os.path.dirname(os.path.dirname(HERE))
REAL_OVERRIDES = os.path.join(REPO_ROOT, "modpack", "overrides")

# The tree as it stands today: what a clean pack ships.
BASELINE = (
    ".gitkeep",
    "config/khi.toml",
    "config/smoothswapping.json",
    "resourcepacks/DungeonTrain-zh_cn-compat.zip",
)


def run(*rel_paths):
    """Run check-overrides.py against a temp overrides tree containing exactly these files."""
    ws = tempfile.mkdtemp(prefix="modpack-overrides-test-")
    for rel in rel_paths:
        path = os.path.join(ws, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write("")
    return subprocess.run(
        [sys.executable, SCRIPT, "--overrides", ws], capture_output=True, text=True
    )


def test_real_tree_is_clean():
    """The committed overrides tree must pass — this is the guard's day job."""
    proc = subprocess.run(
        [sys.executable, SCRIPT, "--overrides", REAL_OVERRIDES], capture_output=True, text=True
    )
    assert proc.returncode == 0, proc.stderr


def test_baseline_passes():
    proc = run(*BASELINE)
    assert proc.returncode == 0, proc.stderr


def test_ais_config_fails_loudly():
    """The live case: AisDataIntegrity would put every pack user into Free Play."""
    proc = run(*BASELINE, "config/adventureitemstats.properties")
    assert proc.returncode != 0
    assert "Free Play" in proc.stderr, proc.stderr
    assert "AisDataIntegrity" in proc.stderr, proc.stderr


def test_dt_server_config_fails_loudly():
    proc = run(*BASELINE, "config/dungeontrain-server.toml")
    assert proc.returncode != 0
    assert "Free Play" in proc.stderr, proc.stderr


def test_dt_common_config_fails_loudly():
    proc = run(*BASELINE, "config/dungeontrain-common.toml")
    assert proc.returncode != 0
    assert "Free Play" in proc.stderr, proc.stderr


def test_integrity_config_fails_wherever_it_sits():
    """Matched by basename, so burying it in another folder doesn't slip it past."""
    proc = run("defaultconfigs/adventureitemstats.properties")
    assert proc.returncode != 0
    assert "Free Play" in proc.stderr, proc.stderr


def test_unknown_override_fails_with_generic_message():
    """A companion mod's config is fine to ship — but only once someone says so here."""
    proc = run(*BASELINE, "config/somenewmod.json")
    assert proc.returncode != 0
    assert "ALLOWED" in proc.stderr, proc.stderr
    assert "Free Play" not in proc.stderr, proc.stderr


def test_new_locale_compat_pack_passes():
    """Locale packs are pattern-matched, so adding one needs no edit to the allowlist."""
    proc = run(*BASELINE, "resourcepacks/DungeonTrain-hi_in-compat.zip")
    assert proc.returncode == 0, proc.stderr


def test_options_txt_at_root_fails():
    """A shipped options.txt was deliberately rejected (see modpack/README.md)."""
    proc = run(*BASELINE, "options.txt")
    assert proc.returncode != 0


def test_empty_tree_passes():
    proc = run()
    assert proc.returncode == 0, proc.stderr


def test_missing_tree_passes():
    """The publish scripts ship an empty overrides/ when the folder is absent."""
    ws = tempfile.mkdtemp(prefix="modpack-overrides-test-")
    proc = subprocess.run(
        [sys.executable, SCRIPT, "--overrides", os.path.join(ws, "nope")],
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


def test_pack_may_never_write_into_the_player_data_root():
    """<instance>/dungeontrain/ holds the player's builds, advancements and backups.

    The data moved there precisely because config/ is replaced on every pack update; shipping an
    override into it would repeat the same data loss one folder over.
    """
    result = run(*BASELINE, "dungeontrain/backups/x.zip")
    assert result.returncode == 1
    assert "player-data root" in result.stderr


def test_forbidden_dir_beats_the_allowlist():
    """".gitkeep" is allowlisted everywhere, but not inside the player-data root."""
    result = run(*BASELINE, "dungeontrain/.gitkeep")
    assert result.returncode == 1
    assert "player-data root" in result.stderr
