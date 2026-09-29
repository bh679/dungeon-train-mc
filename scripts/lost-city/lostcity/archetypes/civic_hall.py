"""Civic hall: a stone hall with tall windows behind a columned portico and steps, and a clock tower
with an open belfry to one side. The hall's bays repeat every 5 along x."""

from ..blocks import (BELL, CONCRETE_DARK, COPPER, GLASS_CLEAR, QUARTZ, QUARTZ_PILLAR, QUARTZ_SLAB, STONE_BRICK_STAIRS,
                      STONE_BRICKS, TARGET)
from .. import furnish
from ..canvas import Canvas, envelope_air
from ..shapes import box, column, walls
from ..spec import Archetype, ArchetypeSpec
from .common import finish, footprint_pad

SPEC = ArchetypeSpec(name="civic_hall", size=(47, 38, 33), floor_period=None, bay_period_x=5, bay_period_z=None,
                     margin=3, seed=0xC1B1C, weight=4)

HX0, HZ0, HX1, HZ1 = 4, 8, 34, 28      # hall: 31 wide = 6 bays of 5
HALL_TOP = 14
PZ0, PZ1 = 4, 7                          # portico strip in front (south)
TX0, TZ0, TX1, TZ1 = 37, 11, 43, 17      # clock tower
TOWER_TOP = 34


def draw(canvas: Canvas) -> None:
    footprint_pad(canvas, SPEC, HX0, PZ0, TX1, HZ1, apron=3)
    canvas.put_all(box(HX0, 1, PZ0, HX1, 1, HZ1, STONE_BRICKS))          # plinth
    canvas.put_all(walls(HX0, 2, HZ0, HX1, HALL_TOP - 1, HZ1, STONE_BRICKS))
    _windows(canvas)
    canvas.put_all(box(HX0, HALL_TOP, HZ0, HX1, HALL_TOP, HZ1, CONCRETE_DARK))
    canvas.put_all(walls(HX0, HALL_TOP + 1, HZ0, HX1, HALL_TOP + 1, HZ1, STONE_BRICKS))
    _portico(canvas)
    _steps(canvas)
    _tower(canvas)
    envelope_air(canvas, HX0, 2, HZ0, HX1, HALL_TOP, HZ1)
    furnish.rows(canvas, (HX0 + 2, HZ0 + 3, HX1 - 2, HZ1 - 4), 2, "north", 3)
    furnish.kit(canvas, SPEC.seed, "hall", (HX0 + 1, HZ0 + 1, HX1 - 1, HZ1 - 1), [2], 0.05)
    finish(canvas, SPEC)


def _windows(canvas: Canvas) -> None:
    for x in range(HX0 + 1, HX1):
        if (x - HX0) % 5 in (2, 3):
            for y in range(4, 11):
                canvas.put((x, y, HZ0), GLASS_CLEAR)
                canvas.put((x, y, HZ1), GLASS_CLEAR)


def _portico(canvas: Canvas) -> None:
    for x in range(HX0, HX1 + 1, 5):
        canvas.put_all(column(x, PZ0 + 1, 2, HALL_TOP - 2, QUARTZ_PILLAR))
    canvas.put_all(box(HX0, HALL_TOP - 1, PZ0, HX1, HALL_TOP, PZ1, QUARTZ))
    canvas.put_all(box(HX0 + 2, HALL_TOP + 1, PZ0 + 1, HX1 - 2, HALL_TOP + 1, PZ1, QUARTZ_SLAB))


def _steps(canvas: Canvas) -> None:
    for x in range(HX0 + 8, HX1 - 7):
        canvas.put((x, 1, PZ0 - 1), STONE_BRICK_STAIRS.with_props(facing="south"))


def _tower(canvas: Canvas) -> None:
    canvas.put_all(walls(TX0, 1, TZ0, TX1, TOWER_TOP - 5, TZ1, STONE_BRICKS))
    for (x, z) in ((TX0, TZ0), (TX1, TZ0), (TX0, TZ1), (TX1, TZ1)):
        canvas.put_all(column(x, z, 1, TOWER_TOP - 1, QUARTZ_PILLAR))
    cx, cz = (TX0 + TX1) // 2, (TZ0 + TZ1) // 2
    for pos in ((cx, TOWER_TOP - 7, TZ0), (cx, TOWER_TOP - 7, TZ1), (TX0, TOWER_TOP - 7, cz), (TX1, TOWER_TOP - 7, cz)):
        canvas.put(pos, TARGET)
    canvas.put_all(box(TX0, TOWER_TOP - 4, TZ0, TX1, TOWER_TOP - 4, TZ1, QUARTZ))
    canvas.put((cx, TOWER_TOP - 2, cz), BELL.with_props(attachment="ceiling"))
    canvas.put_all(box(TX0, TOWER_TOP, TZ0, TX1, TOWER_TOP, TZ1, QUARTZ))
    for i in range(1, 4):
        canvas.put_all(box(TX0 + i, TOWER_TOP + i, TZ0 + i, TX1 - i, TOWER_TOP + i, TZ1 - i, COPPER))


ARCHETYPE = Archetype(SPEC, draw)
