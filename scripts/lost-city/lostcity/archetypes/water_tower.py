"""Water tower: a round tank on four braced steel legs with a conical cap and a ladder."""

from ..blocks import CONCRETE_WHITE, COPPER, LADDER, STEEL, STEEL_BARS, STEEL_DARK, WATER, loot_chest
from ..canvas import Canvas
from ..shapes import column, disc, ring
from ..spec import Archetype, ArchetypeSpec
from .common import finish, footprint_pad

SPEC = ArchetypeSpec(name="water_tower", size=(21, 38, 21), floor_period=None, bay_period_x=None, bay_period_z=None,
                     margin=2, seed=0x7A7E2, weight=3)

C = 10
LEGS = ((5, 5), (15, 5), (5, 15), (15, 15))
LEG_TOP, TANK_TOP = 20, 31


def draw(canvas: Canvas) -> None:
    footprint_pad(canvas, SPEC, 4, 4, 16, 16, apron=2)
    for (x, z) in LEGS:
        canvas.put_all(column(x, z, 1, LEG_TOP, STEEL_DARK))
    _bracing(canvas)
    canvas.put_all(disc(C, C, LEG_TOP, 7.5, STEEL_DARK))
    for y in range(LEG_TOP + 1, TANK_TOP + 1):
        canvas.put_all(ring(C, C, y, 7.5, CONCRETE_WHITE))
    canvas.put_all(disc(C, C, LEG_TOP + 1, 6.5, WATER))
    for i, y in enumerate(range(TANK_TOP + 1, TANK_TOP + 6)):
        canvas.put_all(disc(C, C, y, 7.5 - 1.5 * i, COPPER))
    canvas.put((C, TANK_TOP + 6, C), STEEL)
    for y in range(1, LEG_TOP):
        canvas.put((LEGS[0][0] + 1, y, LEGS[0][1]), LADDER.with_props(facing="east"))
    finish(canvas, SPEC, envelope=None, moss_chance=0.02)
    canvas.put((LEGS[1][0] - 1, 1, LEGS[1][1] + 1), loot_chest(1, "west"))   # dumped at the foot of a leg


def _bracing(canvas: Canvas) -> None:
    for y in range(4, LEG_TOP, 5):
        for z in (5, 15):
            for x in range(6, 15):
                canvas.put((x, y, z), STEEL_BARS)
        for x in (5, 15):
            for z in range(6, 15):
                canvas.put((x, y, z), STEEL_BARS)


ARCHETYPE = Archetype(SPEC, draw)
