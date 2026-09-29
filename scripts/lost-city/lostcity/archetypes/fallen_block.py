"""Fallen block: a brick apartment slab that toppled east. Its bottom two floors still stand as a stub;
the rest snapped off and lies on its side beyond a gap of rubble, floor plates now standing as walls
every four blocks along its length, the old east face its floor and the roof its far end. The window
bays still repeat every 4 along z, so a stretch can lengthen the whole wreck."""

from ..blocks import AIR, BRICK, CONCRETE_WHITE, CRACKED_STONE_BRICKS, GLASS_CLEAR, STEEL_DARK, TERRACOTTA_WHITE, block
from .. import furnish
from ..canvas import Canvas, Cells, envelope_air
from ..damage import RUBBLE
from ..floors import Facade, roof_plate, tower
from ..shapes import box, walls
from ..spec import Archetype, ArchetypeSpec
from .common import finish, footprint_pad

SPEC = ArchetypeSpec(name="fallen_block", size=(49, 17, 33), floor_period=None, bay_period_x=None, bay_period_z=4,
                     margin=3, seed=0xFA11, weight=4)

X0, Z0, X1, Z1 = 4, 4, 14, 28          # upright plan: 11 × 25, 6 bays of 4 along z
PERIOD, FLOORS, BAY = 4, 7, 4
ROOF = 1 + FLOORS * PERIOD              # 29
STUB_TOP = 1 + 2 * PERIOD               # the two floors still standing
GAP = 6
FALL_X = X1 + 1 + GAP                   # where the fallen part begins
TANK = block("waxed_exposed_copper")


def draw(canvas: Canvas) -> None:
    footprint_pad(canvas, SPEC, X0, Z0, FALL_X + (ROOF + 3 - STUB_TOP), Z1)
    upright = _upright()
    canvas.put_all({p: s for p, s in upright.items() if p[1] <= STUB_TOP})
    canvas.put_all(_toppled({p: s for p, s in upright.items() if p[1] > STUB_TOP}))
    _torn_edges(canvas)
    _gap_rubble(canvas)
    envelope_air(canvas, X0 + 1, 2, Z0 + 1, X1 - 1, STUB_TOP - 1, Z1 - 1)
    stub = (X0 + 1, Z0 + 1, X1 - 1, Z1 - 1)
    furnish.kit(canvas, SPEC.seed, "apartment", stub, [2, 2 + PERIOD], 0.06, ruin={"rubble": 0.06, "moss": 0.2})
    furnish.spawners_and_loot(canvas, SPEC.seed, stub, [2], mobs=("zombie",), spawners=1, chests=1, tiers=(1,))
    inside = (FALL_X + 1, Z0 + 1, FALL_X + ROOF - STUB_TOP - 2, Z1 - 1)
    envelope_air(canvas, inside[0], 2, inside[1], inside[2], X1 - X0 - 1, inside[3])
    furnish.kit(canvas, SPEC.seed + 1, "apartment", inside, [2], 0.05, ruin={"rubble": 0.08, "moss": 0.22, "cobweb": 0.16, "vines": 0.1})
    furnish.spawners_and_loot(canvas, SPEC.seed + 1, inside, [2], mobs=("zombie", "skeleton"), spawners=1, chests=2, tiers=(1, 2))
    finish(canvas, SPEC)


def _upright() -> Cells:
    facade = Facade(pier=BRICK, window=GLASS_CLEAR, spandrel=BRICK, spandrel_layers=1)
    cells = dict(tower(X0, Z0, X1, Z1, 1, FLOORS, PERIOD, BAY, TERRACOTTA_WHITE, facade))
    cells.update(roof_plate(X0, Z0, X1, Z1, ROOF, TERRACOTTA_WHITE))
    cells.update(walls(X0 + 3, ROOF + 1, Z0, X1 - 3, ROOF + 2, Z0 + 4, STEEL_DARK))
    cells.update(box(X0 + 3, ROOF + 3, Z0, X1 - 3, ROOF + 3, Z0 + 4, CONCRETE_WHITE))
    for z in (12, 20):
        cells.update(box(X0 + 2, ROOF + 1, z, X0 + 4, ROOF + 3, z + 2, TANK))
    return cells


def _toppled(cells: Cells) -> Cells:
    """Rotate the upper floors a quarter turn about z: height becomes length east, the east face the ground."""
    return {(FALL_X + (y - STUB_TOP - 1), 1 + (X1 - x), z): state for (x, y, z), state in cells.items()}


def _torn_edges(canvas: Canvas) -> None:
    """Where it snapped: the stub's top plate and the fallen part's first plate are ragged."""
    for z in range(Z0, Z1 + 1):
        for x in range(X0, X1 + 1):
            if (x * 7 + z * 3) % 5 < 2:
                canvas.put((x, STUB_TOP, z), AIR)
            elif (x + z) % 4 == 0:
                canvas.put((x, STUB_TOP, z), CRACKED_STONE_BRICKS)
        for y in range(1, X1 - X0 + 2):
            if (y * 5 + z * 3) % 4 < 2:
                canvas.put((FALL_X, y, z), AIR)


def _gap_rubble(canvas: Canvas) -> None:
    for z in range(Z0 - 1, Z1 + 2):
        for i, x in enumerate(range(X1 + 1, FALL_X)):
            if (x * 3 + z * 5) % 3 != 0:
                canvas.put((x, 1, z), RUBBLE[(x + z) % len(RUBBLE)])
            if 1 <= i <= 3 and (x + z) % 3 == 0:
                canvas.put((x, 2, z), RUBBLE[(x * z) % 4])


ARCHETYPE = Archetype(SPEC, draw)
