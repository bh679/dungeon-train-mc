"""Railway station: a track through the template, a long side platform under a copper canopy on iron
columns every 6 blocks, a brick ticket hall, and the stub of a footbridge over the line."""

from ..blocks import (BRICK, COBBLED_DEEPSLATE, COBBLESTONE, CONCRETE_DARK, COPPER, COPPER_SLAB, GLASS_CLEAR, RAIL, SMOOTH_STONE,
                      STEEL_BARS, STEEL_DARK, STONE_BRICKS)
from .. import furnish
from ..canvas import Canvas, envelope_air
from ..shapes import box, column, walls
from ..spec import Archetype, ArchetypeSpec
from .common import finish, footprint_pad

SPEC = ArchetypeSpec(name="railway_station", size=(45, 13, 57), floor_period=None, bay_period_x=None, bay_period_z=6,
                     margin=3, seed=0x7A11, weight=5)

TRACK_X = (8, 12)
PLAT = (13, 4, 24, 52)         # x0, z0, x1, z1
CANOPY_Y, COL_X, COL_EVERY = 9, 18, 6
HALL = (26, 20, 40, 36)
HALL_ROOF = 9
BRIDGE_Z = (30, 32)


def draw(canvas: Canvas) -> None:
    footprint_pad(canvas, SPEC, TRACK_X[0], PLAT[1], HALL[2], PLAT[3], apron=3)
    _track(canvas)
    _platform(canvas)
    _canopy(canvas)
    _hall(canvas)
    _footbridge(canvas)
    envelope_air(canvas, HALL[0], 1, HALL[1], HALL[2], HALL_ROOF, HALL[3])
    furnish.benches(canvas, SPEC.seed, [(21, 3, z) for z in range(PLAT[1] + 2, PLAT[3] - 2, 5)], "west", 0.7)
    furnish.kit(canvas, SPEC.seed, "station", (HALL[0] + 1, HALL[1] + 1, HALL[2] - 1, HALL[3] - 1), [1], 0.08)
    finish(canvas, SPEC)


def _track(canvas: Canvas) -> None:
    z0, z1 = SPEC.margin, SPEC.size[2] - 1 - SPEC.margin
    canvas.put_all(box(TRACK_X[0], 1, z0, TRACK_X[1], 1, z1, COBBLED_DEEPSLATE))   # ballast that cannot fall
    for z in range(z0, z1 + 1):
        canvas.put((10, 2, z), RAIL.with_props(shape="north_south"))


def _platform(canvas: Canvas) -> None:
    x0, z0, x1, z1 = PLAT
    canvas.put_all(box(x0, 1, z0, x1, 1, z1, STONE_BRICKS))
    canvas.put_all(box(x0, 2, z0, x1, 2, z1, SMOOTH_STONE))


def _canopy(canvas: Canvas) -> None:
    x0, z0, x1, z1 = PLAT
    for z in range(z0, z1 + 1, COL_EVERY):
        canvas.put_all(column(COL_X, z, 3, CANOPY_Y - 1, STEEL_DARK))
    canvas.put_all(box(x0 + 1, CANOPY_Y, z0, x1 - 1, CANOPY_Y, z1, COPPER))
    for z in range(z0, z1 + 1):
        canvas.put((x0, CANOPY_Y, z), COPPER_SLAB)
        canvas.put((x1, CANOPY_Y, z), COPPER_SLAB)


def _hall(canvas: Canvas) -> None:
    x0, z0, x1, z1 = HALL
    canvas.put_all(walls(x0, 1, z0, x1, HALL_ROOF - 1, z1, BRICK))
    for z in range(z0 + 2, z1 - 1, 3):
        canvas.put((x0, 3, z), GLASS_CLEAR)
        canvas.put((x0, 4, z), GLASS_CLEAR)
    for y in (1, 2):
        canvas.clear((x0, y, 28))
    canvas.put_all(box(x0, HALL_ROOF, z0, x1, HALL_ROOF, z1, CONCRETE_DARK))
    canvas.put_all(walls(x0, HALL_ROOF + 1, z0, x1, HALL_ROOF + 1, z1, BRICK))


def _footbridge(canvas: Canvas) -> None:
    """A walkway over the track, broken off short of the far side, its fallen end on the ground."""
    z0, z1 = BRIDGE_Z
    for z in (z0, z1):
        canvas.put_all(column(6, z, 1, CANOPY_Y + 1, STEEL_DARK))
    canvas.put_all(box(6, CANOPY_Y + 2, z0, PLAT[0], CANOPY_Y + 2, z1, STEEL_DARK))
    for z in (z0 - 1, z1 + 1):
        canvas.put_all(box(6, CANOPY_Y + 3, z, PLAT[0], CANOPY_Y + 3, z, STEEL_BARS))
    canvas.put_all(box(3, 1, z0, 5, 1, z1, COBBLESTONE))
    canvas.put_all(box(4, 2, z0, 4, 2, z1, STEEL_DARK))


ARCHETYPE = Archetype(SPEC, draw)
