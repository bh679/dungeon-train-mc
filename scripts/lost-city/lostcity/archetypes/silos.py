"""Grain silos: four concrete cylinders in a row, a conveyor gallery along their tops and a tall
headhouse at one end. The silos repeat every 8 blocks along x, so a bay stretch adds or drops one."""

from ..blocks import CONCRETE, CONCRETE_DARK, CONCRETE_WHITE, GLASS_DARK, STEEL_DARK, block
from .. import furnish
from ..canvas import Canvas, envelope_air
from ..shapes import box, disc, ring, walls
from ..spec import Archetype, ArchetypeSpec
from .common import finish, footprint_pad

SPEC = ArchetypeSpec(name="silos", size=(45, 44, 29), floor_period=None, bay_period_x=8, bay_period_z=None,
                     margin=3, seed=0x51105, weight=4)

CENTRES = (8, 16, 24, 32)
CZ, R = 14, 3.8
SILO_TOP = 30
HEAD = (35, 8, 41, 20)      # x0, z0, x1, z1
HEAD_TOP = 40


def draw(canvas: Canvas) -> None:
    footprint_pad(canvas, SPEC, 4, 9, HEAD[2], HEAD[3])
    for cx in CENTRES:
        for y in range(1, SILO_TOP + 1):
            canvas.put_all(ring(cx, CZ, y, R, CONCRETE_WHITE, thick=1.2))
        canvas.put_all(disc(cx, CZ, SILO_TOP + 1, R, CONCRETE))
        canvas.put_all(disc(cx, CZ, SILO_TOP + 2, R - 2.5, CONCRETE_DARK))
    _gallery(canvas)
    _headhouse(canvas)
    for cx in CENTRES:
        furnish.hay_fill(canvas, SPEC.seed + cx, cx, CZ, R - 1.0, 1, 4)
    envelope_air(canvas, HEAD[0] + 1, 1, HEAD[1] + 1, HEAD[2] - 1, HEAD_TOP - 1, HEAD[3] - 1)
    inside = (HEAD[0] + 1, HEAD[1] + 1, HEAD[2] - 1, HEAD[3] - 1)
    furnish.kit(canvas, SPEC.seed, "hut", inside, [1], 0.08)
    furnish.spawners_and_loot(canvas, SPEC.seed, inside, [1], mobs=("zombie",), spawners=1, chests=1, tiers=(1, 2))
    finish(canvas, SPEC)


def _gallery(canvas: Canvas) -> None:
    canvas.put_all(box(4, SILO_TOP + 3, CZ - 2, HEAD[0], SILO_TOP + 3, CZ + 2, STEEL_DARK))
    canvas.put_all(walls(4, SILO_TOP + 4, CZ - 2, HEAD[0], SILO_TOP + 6, CZ + 2, block("gray_stained_glass_pane")))
    canvas.put_all(box(4, SILO_TOP + 7, CZ - 2, HEAD[0], SILO_TOP + 7, CZ + 2, STEEL_DARK))


def _headhouse(canvas: Canvas) -> None:
    x0, z0, x1, z1 = HEAD
    canvas.put_all(walls(x0, 1, z0, x1, HEAD_TOP - 1, z1, CONCRETE))
    canvas.put_all(box(x0, HEAD_TOP, z0, x1, HEAD_TOP, z1, CONCRETE_DARK))
    for y in range(3, HEAD_TOP - 2, 6):
        for z in range(z0 + 2, z1 - 1, 3):
            canvas.put((x1, y, z), GLASS_DARK)
            canvas.put((x1, y + 1, z), GLASS_DARK)
    canvas.put_all(box(x0 + 2, HEAD_TOP + 1, z0 + 4, x1 - 2, HEAD_TOP + 3, z1 - 4, CONCRETE_DARK))


ARCHETYPE = Archetype(SPEC, draw)
