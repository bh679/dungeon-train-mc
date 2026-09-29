"""Radio mast: a steel lattice on a small equipment hut, dishes part-way up, a red light on top.
The lattice repeats every 4 layers so a stretch can lengthen or shorten it."""

from ..blocks import CONCRETE_DARK, GLASS_DARK, RED_LIGHT, STEEL, STEEL_BARS, STEEL_DARK, STEEL_WALL, block
from .. import furnish
from ..canvas import Canvas, envelope_air
from ..shapes import box, column, disc, lattice, walls
from ..spec import Archetype, ArchetypeSpec
from .common import finish, footprint_pad

SPEC = ArchetypeSpec(name="radio_mast", size=(19, 93, 19), floor_period=4, bay_period_x=None, bay_period_z=None,
                     margin=4, seed=0x2AD10, weight=3)

HUT = (5, 5, 13, 13)
MAST = (7, 7, 11, 11)
PERIOD, TOP = 4, 86


def draw(canvas: Canvas) -> None:
    footprint_pad(canvas, SPEC, HUT[0], HUT[1], HUT[2], HUT[3], apron=2)
    canvas.put_all(walls(HUT[0], 1, HUT[1], HUT[2], 4, HUT[3], CONCRETE_DARK))
    canvas.put_all(box(HUT[0], 5, HUT[1], HUT[2], 5, HUT[3], CONCRETE_DARK))
    canvas.put((9, 2, HUT[1]), GLASS_DARK)
    canvas.put((HUT[0], 1, 9), block("iron_door", facing="east", half="lower"))
    canvas.put((HUT[0], 2, 9), block("iron_door", facing="east", half="upper"))
    canvas.put_all(lattice(MAST[0], MAST[1], MAST[2], MAST[3], 6, TOP - 1, STEEL, STEEL_DARK, PERIOD))
    _cross_bracing(canvas)
    _dishes(canvas)
    canvas.put_all(column(9, 9, TOP, TOP + 4, STEEL_WALL))
    canvas.put((9, TOP + 5, 9), RED_LIGHT)
    canvas.put((9, TOP + 6, 9), block("lightning_rod"))
    envelope_air(canvas, HUT[0] + 1, 1, HUT[1] + 1, HUT[2] - 1, 4, HUT[3] - 1)
    furnish.kit(canvas, SPEC.seed, "hut", (HUT[0] + 1, HUT[1] + 1, HUT[2] - 1, HUT[3] - 1), [1], 0.12)
    finish(canvas, SPEC, vine_chance=0.04)


def _cross_bracing(canvas: Canvas) -> None:
    """Bars between the braces on the open faces; not mass, so the lattice period stays exact."""
    for y in range(6, TOP - 1):
        if (y - 6) % PERIOD == 0:
            continue
        for (x, z) in ((9, MAST[1]), (9, MAST[3]), (MAST[0], 9), (MAST[2], 9)):
            canvas.put((x, y, z), STEEL_BARS)


def _dishes(canvas: Canvas) -> None:
    for (cx, cz, y) in ((6, 9, 40), (12, 9, 52), (9, 6, 64)):
        canvas.put_all(disc(cx, cz, y, 1.6, STEEL))
        canvas.put((cx, y, cz), STEEL_DARK)


ARCHETYPE = Archetype(SPEC, draw)
