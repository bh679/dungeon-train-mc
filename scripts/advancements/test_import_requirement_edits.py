#!/usr/bin/env python3
"""Tests for import-requirement-edits.py — the value-side twin of test_import_english_edits.py.

Run: python3 scripts/advancements/test_import_requirement_edits.py   (or via pytest)
"""

from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "import-requirement-edits.py"
SPEC = importlib.util.spec_from_file_location("import_requirement_edits", SCRIPT)
MOD = importlib.util.module_from_spec(SPEC)
sys.modules["import_requirement_edits"] = MOD
SPEC.loader.exec_module(MOD)

ID = "dungeontrain:dungeon_train/carts_1000"

CARTS = '''{
  "parent": "dungeontrain:dungeon_train/carts_100",
  "display": {
    "title": { "translate": "advancements.dungeontrain.dungeon_train.carts_1000.title" }
  },
  "criteria": {
    "milestone": {
      "trigger": "dungeontrain:carts_in_run",
      "conditions": { "threshold": 1000 }
    }
  },
  "requirements": [["milestone"]]
}
'''


def workspace() -> Path:
    ws = Path(tempfile.mkdtemp(prefix="import-requirement-test-"))
    (ws / "carts_1000.json").write_text(CARTS, encoding="utf-8")
    (ws / "the_upside_down.json").write_text(
        '{"criteria": {"a": {"trigger": "dungeontrain:gameplay_action", "conditions": {"actionId": "x"}}}}\n',
        encoding="utf-8")
    return ws


def carts(ws: Path) -> str:
    return (ws / "carts_1000.json").read_text(encoding="utf-8")


def test_clean_import_rewrites_only_the_number():
    ws = workspace()
    r = MOD.apply_units({ID: {"field": "threshold", "value": 500, "shipped": 1000}}, ws)
    assert r.written == [f"{ID}: threshold 1000 -> 500"], r.written
    assert carts(ws) == CARTS.replace('"threshold": 1000', '"threshold": 500')


def test_already_current_is_unchanged():
    ws = workspace()
    r = MOD.apply_units({ID: {"field": "threshold", "value": 1000, "shipped": 1000}}, ws)
    assert r.unchanged == [ID] and not r.written and not r.deferred


def test_repo_edited_since_is_deferred():
    ws = workspace()
    (ws / "carts_1000.json").write_text(CARTS.replace("1000", "750"), encoding="utf-8")
    r = MOD.apply_units({ID: {"field": "threshold", "value": 500, "shipped": 1000}}, ws)
    assert not r.written
    assert "changed since this was written" in r.deferred[0], r.deferred
    assert "750" in carts(ws)


def test_no_shipped_recorded_still_imports():
    ws = workspace()
    r = MOD.apply_units({ID: {"field": "threshold", "value": 500}}, ws)
    assert r.written == [f"{ID}: threshold 1000 -> 500"]


def test_bad_rows_are_deferred_by_reason():
    ws = workspace()
    units = {
        "minecraft:story/root": {"field": "threshold", "value": 5},
        "dungeontrain:dungeon_train/nope": {"field": "threshold", "value": 5},
        "dungeontrain:dungeon_train/the_upside_down": {"field": "threshold", "value": 5},
        ID + "x": {"field": "count", "value": 5},
    }
    r = MOD.apply_units(units, ws)
    assert len(r.deferred) == 4 and not r.written, r.deferred
    r2 = MOD.apply_units({ID: {"field": "thresholdTicks", "value": 5}}, ws)
    assert "carries no thresholdTicks" in r2.deferred[0], r2.deferred
    for bad in (0, -1, 2.5, True, "500", MOD.MAX_VALUE + 1):
        assert MOD.apply_units({ID: {"field": "threshold", "value": bad}}, ws).deferred, bad
    assert carts(ws) == CARTS


def test_dry_run_writes_nothing():
    ws = workspace()
    r = MOD.apply_units({ID: {"field": "threshold", "value": 500, "shipped": 1000}}, ws, dry_run=True)
    assert r.written
    assert carts(ws) == CARTS


def test_units_of_accepts_envelope_or_bare_map():
    assert MOD.units_of({"ok": True, "units": {ID: {"value": 1}}}) == {ID: {"value": 1}}
    assert MOD.units_of({ID: {"value": 1}, "junk": 3}) == {ID: {"value": 1}}
    try:
        MOD.units_of([])
    except ValueError:
        pass
    else:
        raise AssertionError("a list is not an override map")


def test_cli_from_file_reports_and_writes_deferrals():
    ws = workspace()
    src = ws / "payload.json"
    src.write_text(json.dumps({"units": {
        ID: {"field": "threshold", "value": 500, "shipped": 1000},
        "minecraft:story/root": {"field": "threshold", "value": 5},
    }}), encoding="utf-8")
    out = ws / "deferred.json"
    proc = subprocess.run([sys.executable, str(SCRIPT), "--from-file", str(src), "--adv-dir", str(ws),
                           "--deferred-out", str(out)], capture_output=True, text=True)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert "rewrote dungeontrain:dungeon_train/carts_1000: threshold 1000 -> 500" in proc.stdout
    assert "1 value(s) changed, 0 already current, 1 deferred" in proc.stdout, proc.stdout
    deferred = json.loads(out.read_text(encoding="utf-8"))
    assert len(deferred) == 1 and "minecraft:story/root" in deferred[0]
    assert '"threshold": 500' in carts(ws)


def test_against_the_real_datapack_dry_run():
    """Every shipped requirement advancement is locatable — the allowlist and the datapack agree."""
    adv_dir = MOD.DEFAULT_ADV_DIR
    units = {}
    for path in adv_dir.glob("*.json"):
        adv = json.loads(path.read_text(encoding="utf-8"))
        for field in MOD.FIELDS:
            found = MOD.locate(adv, field)
            if found:
                units[f"dungeontrain:dungeon_train/{path.stem}"] = {
                    "field": field, "value": found[1] + 1, "shipped": found[1]}
    assert len(units) >= 35, len(units)
    r = MOD.apply_units(units, adv_dir, dry_run=True)
    assert not r.deferred, r.deferred
    assert len(r.written) == len(units)


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
