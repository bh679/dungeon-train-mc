"""Petrol station: a flat canopy with a red fascia on four slender columns, two pump islands under it,
a small kiosk beside it, and asphalt all round."""

from ..blocks import (ASPHALT, CONCRETE, CONCRETE_DARK, CONCRETE_WHITE, GLASS_CLEAR, POLISHED_BLACKSTONE,
                      POLISHED_BLACKSTONE_BUTTON, RED_PAINT, SMOOTH_STONE_SLAB, STEEL_DARK)
from ..canvas import Canvas
from ..shapes import box, column, walls
from ..spec import Archetype, ArchetypeSpec
from .common import finish, footprint_pad

SPEC = ArchetypeSpec(name="petrol_station", size=(37, 9, 25), floor_period=None, bay_period_x=None,
                     bay_period_z=None, margin=3, seed=0x9A5, weight=4)

C0, CZ0, C1, CZ1 = 4, 4, 24, 18      # canopy
CANOPY_Y = 7
COLUMNS = ((8, 7), (20, 7), (8, 15), (20, 15))
KIOSK = (27, 6, 33, 18)
KIOSK_ROOF = 6


def draw(canvas: Canvas) -> None:
    footprint_pad(canvas, SPEC, C0, CZ0, KIOSK[2], KIOSK[3], apron=3, paving=(ASPHALT,))
    for (x, z) in COLUMNS:
        canvas.put_all(column(x, z, 1, CANOPY_Y - 1, STEEL_DARK))
    canvas.put_all(box(C0, CANOPY_Y, CZ0, C1, CANOPY_Y, CZ1, CONCRETE_WHITE))
    canvas.put_all(walls(C0, CANOPY_Y + 1, CZ0, C1, CANOPY_Y + 1, CZ1, RED_PAINT))
    canvas.put_all(box(C0 + 1, CANOPY_Y + 1, CZ0 + 1, C1 - 1, CANOPY_Y + 1, CZ1 - 1, CONCRETE))
    for x in (8, 20):
        _island(canvas, x)
    _kiosk(canvas)
    finish(canvas, SPEC, envelope=(KIOSK[0], 1, KIOSK[1], KIOSK[2], KIOSK_ROOF, KIOSK[3]), moss_chance=0.02)


def _island(canvas: Canvas, x: int) -> None:
    canvas.put_all(box(x - 1, 1, 9, x + 1, 1, 13, SMOOTH_STONE_SLAB))
    for z in (10, 12):
        canvas.put_all(column(x, z, 1, 2, POLISHED_BLACKSTONE))
        canvas.put((x + 1, 2, z), POLISHED_BLACKSTONE_BUTTON.with_props(face="wall", facing="east"))


def _kiosk(canvas: Canvas) -> None:
    x0, z0, x1, z1 = KIOSK
    canvas.put_all(walls(x0, 1, z0, x1, KIOSK_ROOF - 1, z1, CONCRETE))
    for z in range(z0 + 1, z1):
        if z not in (11, 12):
            canvas.put((x0, 2, z), GLASS_CLEAR)
            canvas.put((x0, 3, z), GLASS_CLEAR)
    for y in (1, 2):
        canvas.clear((x0, y, 11))
        canvas.clear((x0, y, 12))
    canvas.put_all(box(x0, KIOSK_ROOF, z0, x1, KIOSK_ROOF, z1, CONCRETE_DARK))


ARCHETYPE = Archetype(SPEC, draw)
