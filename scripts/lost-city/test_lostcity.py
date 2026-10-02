#!/usr/bin/env python3
"""Unit tests for the Lost City generator: python3 scripts/lost-city/test_lostcity.py"""

import gzip
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "portal"))

from nbt import get, read  # noqa: E402

from lostcity import blocks, variantdoc  # noqa: E402
from lostcity.archetypes import ALL  # noqa: E402
from lostcity.canvas import Canvas  # noqa: E402
from lostcity.check import validate  # noqa: E402
from lostcity.datagen import PLAYER_BUILDING, render  # noqa: E402
from lostcity.floors import Facade, tower  # noqa: E402
from lostcity.nbt_out import DATA_VERSION, to_bytes  # noqa: E402
from lostcity.spec import ArchetypeSpec  # noqa: E402
from lostcity.variants import designs  # noqa: E402

# What LostCityStretchProcessor.isMass ignores: anything without a full collision cube.
NOT_FULL = ("slab", "stairs", "pane", "bars", "wall", "carpet", "vine", "chain", "ladder", "lantern", "button",
            "trapdoor", "rail", "door", "rod", "bell", "roots", "web", "pot", "bush", "grass", "fern", "azalea", "leaves",
            "cauldron", "chest", "hopper", "candle", "lectern", "banner")


class BlocksTest(unittest.TestCase):
    def test_every_palette_name_is_namespaced(self):
        for name in blocks.KNOWN:
            self.assertTrue(name.startswith("minecraft:"), name)

    def test_pavement_is_never_natural_ground(self):
        for state in blocks.PAVEMENT:
            self.assertNotIn(state.name, blocks.PAD_NATURAL)

    def test_props_are_sorted_and_stringified(self):
        state = blocks.block("oak_stairs", half="top", facing="north")
        self.assertEqual(state.props, (("facing", "north"), ("half", "top")))


class FloorsTest(unittest.TestCase):
    def test_floors_repeat_exactly(self):
        facade = Facade(pier=blocks.CONCRETE, window=blocks.GLASS, spandrel=blocks.CONCRETE_WHITE)
        cells = tower(0, 0, 8, 8, 1, 3, 5, 4, blocks.CONCRETE, facade)
        for (x, y, z), state in cells.items():
            if y + 5 <= 15:
                self.assertEqual(state, cells[(x, y + 5, z)], (x, y, z))

    def test_piers_on_bay_boundaries(self):
        facade = Facade(pier=blocks.CONCRETE, window=blocks.GLASS, spandrel=blocks.CONCRETE_WHITE)
        cells = tower(0, 0, 8, 8, 1, 1, 5, 4, blocks.CONCRETE, facade)
        self.assertEqual(cells[(4, 3, 0)], blocks.CONCRETE)
        self.assertEqual(cells[(5, 3, 0)], blocks.GLASS)
        self.assertEqual(cells[(5, 2, 0)], blocks.CONCRETE_WHITE)


class NbtTest(unittest.TestCase):
    def test_round_trip_and_determinism(self):
        canvas = Canvas((3, 2, 3))
        canvas.put((0, 0, 0), blocks.block("grass_block"))
        canvas.put((1, 1, 1), blocks.block("oak_stairs", facing="north"))
        first, second = to_bytes(canvas.freeze(), canvas.size), to_bytes(canvas.freeze(), canvas.size)
        self.assertEqual(first, second)
        root = read(gzip.decompress(first))[1]
        self.assertEqual(get(root, "DataVersion").value, DATA_VERSION)
        self.assertEqual([t.value for t in get(root, "size").value[1]], [3, 2, 3])
        self.assertEqual(len(get(root, "blocks").value[1]), 2)
        names = [get(p, "Name").value for p in get(root, "palette").value[1]]
        self.assertEqual(names, ["minecraft:grass_block", "minecraft:oak_stairs"])


class ArchetypesTest(unittest.TestCase):
    def test_every_archetype_validates(self):
        for archetype in ALL:
            self.assertEqual([], validate(archetype.render(), archetype.spec))

    def test_declared_periods_are_real_repeats(self):
        """Along each stretched axis the building's full-block mass must repeat at the declared period."""
        for archetype in ALL:
            spec, cells = archetype.spec, archetype.render()
            for axis, period in (("y", spec.floor_period), ("x", spec.bay_period_x), ("z", spec.bay_period_z)):
                if period:
                    self.assertTrue(_has_repeat(cells, axis, period), f"{spec.name} {axis} period {period}")

    def test_block_entity_nbt_only_on_spawners_and_loot(self):
        for archetype in ALL:
            cells = archetype.render()
            for pos, state in cells.items():
                if state.nbt:
                    self.assertIn(state.name, ("minecraft:spawner", "minecraft:chest"), f"{archetype.spec.name} {pos}")
            self.assertTrue(any(s.name == "minecraft:chest" for s in cells.values()), f"{archetype.spec.name} has no loot")

    def test_check_rejects_margin_breach(self):
        spec = ArchetypeSpec("t", (7, 3, 7), None, None, None, margin=2, seed=1)
        canvas = Canvas(spec.size)
        for z in range(7):
            for x in range(7):
                canvas.put((x, 0, z), blocks.block("grass_block"))
        canvas.put((1, 1, 3), blocks.CONCRETE)
        canvas.put((3, 2, 3), blocks.CONCRETE)
        self.assertTrue(any("margin" in e for e in validate(canvas.freeze(), spec)))


def _has_repeat(cells, axis, period, similarity=0.5) -> bool:
    """True when ≥ period consecutive slices each match the slice `period` further on.

    Matching is Jaccard similarity of the full-block footprints, as LostCityStretchProcessor.findBand
    does (its threshold is 0.45; a slice through a service core against one beside it scores ~0.56,
    which is why this is not stricter). LostCityTemplatesTest runs the real Java search."""
    idx = {"x": 0, "y": 1, "z": 2}[axis]
    slices: dict[int, set] = {}
    for pos, state in cells.items():
        if pos[1] == 0 or state == blocks.AIR or any(p in state.name for p in NOT_FULL):
            continue
        slices.setdefault(pos[idx], set()).add(tuple(v for i, v in enumerate(pos) if i != idx))
    run = 0
    for i in range(0, max(slices) + 1):
        a, b = slices.get(i, set()), slices.get(i + period, set())
        if a and b and len(a & b) / len(a | b) >= similarity:
            run += 1
            if run >= period:
                return True
        else:
            run = 0
    return False


class DatagenTest(unittest.TestCase):
    def test_every_design_places_a_dt_template(self):
        files = render(ALL, designs(), {"structures": [], "placement": {}})
        pools = {p: d for p, d in files.items() if "/template_pool/" in p and PLAYER_BUILDING not in p}
        self.assertEqual(len(pools), len(ALL))
        for data in pools.values():
            self.assertNotIn(b"big_lost_city", data)
            self.assertIn(b'"location": "dungeontrain:lost_city/', data)

    def test_processors_are_ordered_stretch_first(self):
        files = render(ALL, designs(), {"structures": [], "placement": {}})
        for path, data in files.items():
            if "/processor_list/" in path and b"lost_city_stretch" in data and b"lost_city_bite" in data:
                self.assertLess(data.index(b"lost_city_stretch"), data.index(b"lost_city_bite"), path)

    def test_variant_phases_only_for_buildings_with_a_document(self):
        files = render(ALL, designs(), {"structures": [], "placement": {}}, frozenset({"office_tower"}))
        for path, data in files.items():
            if "/processor_list/" not in path:
                continue
            kinds = [p["processor_type"] + "/" + p.get("phase", "") for p in json.loads(data)["processors"]]
            phases = [k for k in kinds if k.startswith("dungeontrain:lost_city_variants/")]
            if "/office_tower_" not in path:
                self.assertEqual(phases, [], path)
                continue
            self.assertEqual(phases, ["dungeontrain:lost_city_variants/mark", "dungeontrain:lost_city_variants/roll"], path)
            self.assertEqual(kinds.index(phases[0]), 1, f"{path}: mark must come straight after block_ignore")
            roll = kinds.index(phases[1])
            self.assertFalse(set(kinds[:roll]) & {"dungeontrain:lost_city_bite/", "dungeontrain:lost_city_facade/",
                                                  "dungeontrain:lost_city_truncate/"}, path)
            self.assertFalse(set(kinds[roll + 1:]) & {"dungeontrain:lost_city_stretch/", "dungeontrain:lost_city_swap/"}, path)


class VariantDocTest(unittest.TestCase):
    def test_every_archetype_seeds_a_valid_document(self):
        for archetype in ALL:
            cells = archetype.render()
            doc = variantdoc.seed(cells, archetype.spec)
            self.assertTrue(doc, f"{archetype.spec.name} has nothing to vary")
            self.assertLessEqual(len(doc), variantdoc.CAP)
            data = variantdoc.dumps(doc)
            self.assertEqual(data, variantdoc.dumps(variantdoc.seed(cells, archetype.spec)), "not deterministic")
            self.assertEqual([], variantdoc.validate(variantdoc.loads(data), cells, archetype.spec))
            self.assertNotIn(b"dungeontrain:stage_", data)

    def test_pools_keep_the_cells_own_block_first(self):
        archetype = ALL[0]
        cells = archetype.render()
        for pos, cell in variantdoc.seed(cells, archetype.spec).items():
            self.assertEqual(cell.entries[0].state, variantdoc.state_string(cells[pos]), pos)
            self.assertGreaterEqual(len(cell.entries), variantdoc.MIN_STATES)

    def test_the_cap_is_shared_across_rules(self):
        archetype = ALL[0]
        doc = variantdoc.seed(archetype.render(), archetype.spec, cap=40)
        self.assertEqual(len(doc), 40)
        self.assertGreater(len({cell.entries[0].state.split("[")[0] for cell in doc.values()}), 4)

    def test_validate_rejects_what_worldgen_cannot_place(self):
        spec = ArchetypeSpec("t", (3, 3, 3), None, None, None, margin=0, seed=1)
        cells = {(1, 1, 1): blocks.COBWEB, (1, 0, 1): blocks.block("grass_block")}
        bad = {
            (1, 0, 1): ["minecraft:stone", "minecraft:dirt"],
            (2, 2, 2): ["minecraft:stone", "minecraft:dirt"],
            (1, 1, 1): ["dungeontrain:stage_stone", {"entity": "minecraft:zombie"}],
        }
        errors = "\n".join(variantdoc.validate(bad, cells, spec))
        for expected in ("pad layer", "not a cell of the template", "stage placeholder", "mob entry"):
            self.assertIn(expected, errors)
        self.assertTrue(any("fewer than" in e for e in variantdoc.validate({(1, 1, 1): ["minecraft:stone"]}, cells, spec)))


if __name__ == "__main__":
    unittest.main()
