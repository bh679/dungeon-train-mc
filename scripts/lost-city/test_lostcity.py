#!/usr/bin/env python3
"""Unit tests for the Lost City generator: python3 scripts/lost-city/test_lostcity.py"""

import gzip
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "portal"))

from nbt import get, read  # noqa: E402

from lostcity import blocks  # noqa: E402
from lostcity.archetypes import ALL  # noqa: E402
from lostcity.canvas import Canvas  # noqa: E402
from lostcity.check import validate  # noqa: E402
from lostcity.datagen import render  # noqa: E402
from lostcity.floors import Facade, tower  # noqa: E402
from lostcity.nbt_out import DATA_VERSION, to_bytes  # noqa: E402
from lostcity.spec import ArchetypeSpec  # noqa: E402
from lostcity.variants import designs  # noqa: E402

NOT_FULL = ("slab", "stairs", "pane", "bars", "wall", "carpet", "vine", "chain", "ladder", "lantern", "button",
            "trapdoor", "rail", "door", "rod", "bell")


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
        pools = {p: d for p, d in files.items() if "/template_pool/" in p}
        self.assertEqual(len(pools), len(ALL))
        for data in pools.values():
            self.assertNotIn(b"big_lost_city", data)
            self.assertIn(b'"location": "dungeontrain:lost_city/', data)

    def test_processors_are_ordered_stretch_first(self):
        files = render(ALL, designs(), {"structures": [], "placement": {}})
        for path, data in files.items():
            if "/processor_list/" in path and b"lost_city_stretch" in data and b"lost_city_bite" in data:
                self.assertLess(data.index(b"lost_city_stretch"), data.index(b"lost_city_bite"), path)


if __name__ == "__main__":
    unittest.main()
