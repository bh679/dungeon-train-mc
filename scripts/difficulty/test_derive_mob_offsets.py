#!/usr/bin/env python3
"""Unit tests for derive-mob-offsets.py, on synthetic carriage contents."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location(
    "derive_mob_offsets", Path(__file__).resolve().parent / "derive-mob-offsets.py")
dmo = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(dmo)

STAGES = {"early": {"minLevel": 0}, "mid": {"minLevel": 12}, "late": {"minLevel": 30}}
HOSTILE = {e: "hostile" for e in (
    "minecraft:zombie", "minecraft:witch", "minecraft:warden", "minecraft:husk",
    "minecraft:shulker", "minecraft:evoker", *dmo.POT_MOBS)}
HOSTILE["minecraft:cow"] = "animal"


def _sidecar(cells: dict) -> dict:
    return {"schemaVersion": 8, "variants": cells}


class DeriveTest(unittest.TestCase):
    def _run(self, weights: dict, groups: dict, sidecars: dict, baked: dict | None = None):
        baked = baked or {}
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "weights.json").write_text(json.dumps(weights))
            for name, group in groups.items():
                (root / f"{name}.group.json").write_text(json.dumps(group))
            for name, cells in sidecars.items():
                (root / f"{name}.variants.json").write_text(json.dumps(_sidecar(cells)))
            for name in baked:
                (root / f"{name}.nbt").write_bytes(b"")
            derivation = dmo.Derivation(root, STAGES, lambda path: baked[path.stem]).run()
            return dmo.derive(HOSTILE, derivation)

    def test_cell_band_raises_room_gate(self):
        table = self._run(
            {"room": {"weight": 1, "minLevel": 4}}, {},
            {"room": {"0,0,0": [{"entity": "minecraft:witch", "difficulty": {"min": 9, "max": "all"}},
                                {"entity": "minecraft:husk", "difficulty": {"min": 1, "max": 3}}]}})
        self.assertEqual(9, table["minecraft:witch"])
        self.assertEqual(4, table["minecraft:husk"], "a band below the room gate counts at the gate")

    def test_lowest_room_wins_and_level_zero_is_dropped(self):
        table = self._run(
            {"a": 1, "b": {"weight": 1, "minLevel": 7}}, {},
            {"a": {"0,0,0": [{"entity": "minecraft:zombie"}]},
             "b": {"0,0,0": [{"entity": "minecraft:zombie"}, {"entity": "minecraft:witch"}]}})
        self.assertNotIn("minecraft:zombie", table)
        self.assertEqual(7, table["minecraft:witch"])

    def test_group_member_gate_and_stage_minimum(self):
        table = self._run(
            {"enemies": {"weight": 1, "minLevel": 2}},
            {"enemies": {"selfWeight": 0, "variants": [
                {"id": "lowroom", "weight": 1},
                {"id": "stagedroom", "weight": 1, "stages": ["late", "mid"], "minLevel": 99},
                {"id": "offroom", "weight": 0, "minLevel": 0}]}},
            {"enemies": {"0,0,0": [{"entity": "minecraft:warden"}]},
             "lowroom": {"0,0,0": [{"entity": "minecraft:witch"}]},
             "stagedroom": {"0,0,0": [{"entity": "minecraft:shulker"}]},
             "offroom": {"0,0,0": [{"entity": "minecraft:evoker"}]}})
        self.assertNotIn("minecraft:warden", table, "selfWeight 0 never places the parent")
        self.assertEqual(2, table["minecraft:witch"], "member inherits the parent's gate")
        self.assertEqual(12, table["minecraft:shulker"], "stage links replace the inline gate, lowest wins")
        self.assertNotIn("minecraft:evoker", table, "weight 0 members never spawn")

    def test_group_children_are_not_top_level(self):
        table = self._run(
            {"enemies": {"weight": 1, "minLevel": 5}, "child": 1},
            {"enemies": {"selfWeight": 1, "variants": [{"id": "child", "weight": 1}]}},
            {"child": {"0,0,0": [{"entity": "minecraft:witch"}]}})
        self.assertEqual(5, table["minecraft:witch"])

    def test_vase_and_baked_and_non_hostile(self):
        table = self._run(
            {"room": {"weight": 1, "minLevel": 3}}, {},
            {"room": {"1,0,0": [{"state": "deeperdarker:gloomslate_pot", "lootPrefab": "ancient_vase",
                                 "difficulty": {"min": 8}},
                                {"entity": "minecraft:cow"},
                                {"entity": "minecraft:evoker", "weight": 0}]}},
            {"room": {"entities": [{"nbt": {"id": "minecraft:husk",
                                            "Passengers": [{"id": "minecraft:shulker"}]}}]}})
        for pot in dmo.POT_MOBS:
            self.assertEqual(8, table[pot])
        self.assertEqual(3, table["minecraft:husk"])
        self.assertEqual(3, table["minecraft:shulker"], "passengers count too")
        self.assertNotIn("minecraft:cow", table)
        self.assertNotIn("minecraft:evoker", table, "weight 0 candidates never spawn")

    def test_java_table_round_trip(self):
        java = ("class X {\n    private static final Map<String, Integer> OFFSETS = Map.ofEntries(\n"
                '            Map.entry("minecraft:old", 9)\n    );\n}\n')
        written = dmo.write_java_table(java, {"minecraft:b": 4, "minecraft:a": 4, "minecraft:c": 1})
        self.assertEqual({"minecraft:a": 4, "minecraft:b": 4, "minecraft:c": 1}, dmo.read_java_table(written))
        self.assertLess(written.index("minecraft:c"), written.index("minecraft:a"))
        self.assertIn('            Map.entry("minecraft:c", 1),\n', written)
        self.assertTrue(written.endswith("    );\n}\n"))


if __name__ == "__main__":
    unittest.main()
