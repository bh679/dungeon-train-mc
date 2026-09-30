"""Leaning tower: an office block whose foundations gave way on one side. Every four layers the whole
plan slides one block east, so the roof overhangs the base by eleven; the crushed east foot is buried
in rubble, the lifted west foot hangs over air. No repeat is declared — a sheared building has none."""

from ..blocks import AIR, CONCRETE, CONCRETE_DARK, CONCRETE_WHITE, CRACKED_STONE_BRICKS, GLASS, GLASS_CLEAR, STEEL_BARS, STEEL_DARK
from .. import furnish
from ..canvas import Canvas, Cells, envelope_air
from ..damage import RUBBLE
from ..floors import Facade, roof_plate, tower
from ..shapes import box, walls
from ..spec import Archetype, ArchetypeSpec
from .common import finish, footprint_pad

SPEC = ArchetypeSpec(name="leaning_tower", size=(37, 51, 23), floor_period=None, bay_period_x=None, bay_period_z=None,
                     margin=4, seed=0x1EA9, weight=4)

X0, Z0, X1, Z1 = 4, 4, 18, 18          # 15 wide at the base
PERIOD, FLOORS, BAY = 5, 9, 4
ROOF = 1 + FLOORS * PERIOD              # 46
DRIFT = 4                               # layers per block of lean
CORE = (9, 9, 13, 13)


def lean(y: int) -> int:
    return (y - 1) // DRIFT


def draw(canvas: Canvas) -> None:
    footprint_pad(canvas, SPEC, X0, Z0, X1 + lean(ROOF), Z1)
    canvas.put_all(_sheared(_upright()))
    for f in range(FLOORS):
        y = 2 + f * PERIOD
        dx = lean(y)
        inside = (X0 + 1 + dx, Z0 + 1, X1 - 1 + dx, Z1 - 1)
        envelope_air(canvas, inside[0], y, inside[1], inside[2], y + PERIOD - 2, inside[3])
        furnish.kit(canvas, SPEC.seed + f, "office", inside, [y], 0.07, ruin={"rubble": 0.05, "moss": 0.20, "cobweb": 0.14})
        if f in (1, 4, 7):
            furnish.spawners_and_loot(canvas, SPEC.seed + f, inside, [y], mobs=("zombie", "skeleton"), spawners=1, chests=1,
                                      tiers=(1 + f // 3,))
    _crushed_foot(canvas)
    _lifted_foot(canvas)
    # a tower this cracked is a trellis: vines pour off every face and hang long under the overhang
    finish(canvas, SPEC, vine_chance=0.5, vine_drop=(5, 16), moss_chance=0.3, moss_block_chance=0.16, roots_chance=0.2,
           leaf_clumps=44, gardens=12)


def _upright() -> Cells:
    facade = Facade(pier=CONCRETE, window=GLASS, spandrel=CONCRETE_WHITE, spandrel_layers=1)
    cells = dict(tower(X0, Z0, X1, Z1, 1, FLOORS, PERIOD, BAY, CONCRETE, facade))
    for layer in range(2, PERIOD):
        for x in range(X0 + 1, X1):
            if x % BAY != 0:
                cells[(x, layer, Z0)] = GLASS_CLEAR
    cells.update(walls(CORE[0], 1, CORE[1], CORE[2], ROOF - 1, CORE[3], CONCRETE_DARK))
    cells.update(roof_plate(X0, Z0, X1, Z1, ROOF, CONCRETE))
    cells.update(walls(X0, ROOF + 1, Z0, X1, ROOF + 1, Z1, STEEL_BARS))
    cells.update(box(9, ROOF + 1, 9, 13, ROOF + 3, 13, STEEL_DARK))
    return cells


def _sheared(cells: Cells) -> Cells:
    return {(x + lean(y), y, z): state for (x, y, z), state in cells.items()}


def _crushed_foot(canvas: Canvas) -> None:
    """The east side took the weight: cracked piers and a bank of rubble up the wall."""
    for z in range(Z0, Z1 + 1):
        for y in (1, 2):
            if canvas.get((X1, y, z)) == CONCRETE:
                canvas.put((X1, y, z), CRACKED_STONE_BRICKS)
        for i, x in enumerate(range(X1 + 1, X1 + 4)):
            if (z + i) % 2 == 0:
                canvas.put((x, 1, z), RUBBLE[(z + i) % len(RUBBLE)])
            if i == 0 and z % 3 == 0:
                canvas.put((x, 2, z), RUBBLE[z % 4])


def _lifted_foot(canvas: Canvas) -> None:
    """The west side rose: the ground floor's outer three blocks hang over air, rubble beneath."""
    for z in range(Z0, Z1 + 1):
        for x in range(X0, X0 + 3):
            if canvas.get((x, 1, z)) not in (None, AIR):
                canvas.put((x, 1, z), AIR)
            if (x + z) % 3 == 0 and x > X0:
                canvas.put((x - 1, 1, z), RUBBLE[(x + z) % len(RUBBLE)])
    for z in range(Z0 + 1, Z1):
        canvas.put((X0, 1, z), AIR if z % 4 else CRACKED_STONE_BRICKS)


ARCHETYPE = Archetype(SPEC, draw)
