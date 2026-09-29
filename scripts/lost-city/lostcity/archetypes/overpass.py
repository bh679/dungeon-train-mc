"""Fallen viaduct: a two-lane elevated road that has come down in its entirety. Every 12 blocks a pair
of snapped pier stubs still stands; between them the deck section that they carried lies tilted on
the ground, nose down, its guard rails bent along the edges, rubble in the gaps. 98 blocks long."""

from ..blocks import (ASPHALT, COBBLESTONE, CONCRETE, CONCRETE_DARK, CRACKED_STONE_BRICKS, ROAD_LINE, ROAD_LINE_YELLOW, STEEL_WALL,
                      loot_chest)
from ..canvas import Canvas
from ..shapes import box, column
from ..spec import Archetype, ArchetypeSpec
from .common import finish, footprint_pad

SPEC = ArchetypeSpec(name="overpass", size=(23, 9, 106), floor_period=None, bay_period_x=None, bay_period_z=None,
                     margin=3, seed=0x0BEA55, weight=4)

X0, X1 = 5, 17
Z0, Z1 = 4, 101
SEGMENT = 12
DECK_LEN = 9
STUB_HEIGHTS = (3, 4, 2, 3)          # the four columns of a pier, snapped at different heights
RUBBLE = (COBBLESTONE, CRACKED_STONE_BRICKS, CONCRETE_DARK)


def draw(canvas: Canvas) -> None:
    footprint_pad(canvas, SPEC, X0, Z0, X1, Z1, apron=2)
    for z in range(Z0, Z1 - SEGMENT + 2, SEGMENT):
        _pier_stubs(canvas, z)
        _fallen_deck(canvas, z + 2)
        _gap_rubble(canvas, z + 2 + DECK_LEN)
    finish(canvas, SPEC, envelope=None, moss_chance=0.25, weeds_chance=0.45)
    canvas.put((X0 - 1, 1, Z0 + 2 + SEGMENT * 3), loot_chest(1, "east"))     # a wrecked car's boot, beside the road


def _pier_stubs(canvas: Canvas, z: int) -> None:
    for (x, h) in zip((7, 8, 14, 15), STUB_HEIGHTS):
        canvas.put_all(column(x, z, 1, h, CONCRETE))
        if h < 4:
            canvas.put((x, h + 1, z), CRACKED_STONE_BRICKS)


def _fallen_deck(canvas: Canvas, z0: int) -> None:
    """A deck section lying on the pad: two blocks thick, its far end still propped a couple of blocks up."""
    for k in range(DECK_LEN):
        top = 1 + (DECK_LEN - 1 - k) // 3            # 3, 3, 3, 2, 2, 2, 1, 1, 1
        z = z0 + k
        if top >= 2:
            canvas.put_all(box(X0, top - 1, z, X1, top - 1, z, CONCRETE_DARK))
            if top - 2 >= 1:
                canvas.put_all(box(X0 + 1, top - 2, z, X1 - 1, top - 2, z, COBBLESTONE))
        canvas.put_all(box(X0, top, z, X1, top, z, ASPHALT))
        canvas.put((X0 + 1, top, z), ROAD_LINE)
        canvas.put((X1 - 1, top, z), ROAD_LINE)
        if k % 4 < 2:
            canvas.put((11, top, z), ROAD_LINE_YELLOW)
        if k % 3 != 1:
            canvas.put((X0, top + 1, z), STEEL_WALL)
            canvas.put((X1, top + 1, z), STEEL_WALL)


def _gap_rubble(canvas: Canvas, z0: int) -> None:
    for i, x in enumerate(range(X0 + 1, X1, 2)):
        canvas.put((x, 1, z0 + i % 2), RUBBLE[i % len(RUBBLE)])


ARCHETYPE = Archetype(SPEC, draw)
