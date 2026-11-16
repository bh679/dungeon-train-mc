#!/usr/bin/env python3
"""Unit tests for check-cf-game-versions.py — the CurseForge pin game-version guard.

No network: the lookup is stubbed. Covers the 2026-11 PlayerMob case (a 26.2 file pinned
into the 1.21.1 pack), a wrong loader, the mirror's unlisted-file warning, and which
entries are checked at all.

Run: python3 scripts/modpack/test_check_cf_game_versions.py   (or via pytest)
"""
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
_spec = importlib.util.spec_from_file_location("check_cf_game_versions",
                                               os.path.join(HERE, "check-cf-game-versions.py"))
mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mod)

GOOD = ["Client", "1.21.1", "NeoForge", "Server"]


def stub(table):
    return lambda pid, fid: table.get(fid)


def test_1_21_1_neoforge_passes():
    errors, warnings = mod.check([("PlayerMob", 1559379, 9615243)], stub({9615243: GOOD}))
    assert errors == [] and warnings == []


def test_26_2_file_fails():
    errors, _ = mod.check([("PlayerMob", 1559379, 9615248)],
                          stub({9615248: ["Client", "NeoForge", "Server", "26.2"]}))
    assert len(errors) == 1
    assert "9615248" in errors[0] and "missing 1.21.1" in errors[0]


def test_wrong_loader_fails():
    errors, _ = mod.check([("PlayerMob", 1559379, 9615241)],
                          stub({9615241: ["Client", "Forge", "1.21.1", "Server"]}))
    assert len(errors) == 1 and "missing NeoForge" in errors[0]


def test_unlisted_file_warns_only():
    errors, warnings = mod.check([("Punchy", 1, 8697205)], stub({}))
    assert errors == []
    assert len(warnings) == 1 and "8697205" in warnings[0]


def test_pinned_files_skips_modrinth_only_and_unpinned():
    config = {
        "sable": {"project_id": 10, "file_id": 20},
        "optional_mods": [
            {"name": "A", "project_id": 1, "file_id": 2},
            {"name": "ModrinthOnly", "project_id": 3, "file_id": 4, "modrinth_only": True},
            {"name": "NoFile", "project_id": 5},
        ],
    }
    assert mod.pinned_files(config) == [("Sable", 10, 20), ("A", 1, 2)]


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"PASS {name}")
            except AssertionError as exc:
                failures += 1
                print(f"FAIL {name}: {exc}")
    sys.exit(1 if failures else 0)
