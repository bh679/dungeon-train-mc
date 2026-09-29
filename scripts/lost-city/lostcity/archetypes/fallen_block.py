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

SPEC = ArchetypeSpec(name="fallen_block", size=(49, 22, 33), floor_period=None, bay_period_x=None, bay_period_z=4,
                     margin=3, seed=0xFA11, weight=4)

X0, Z0, X1, Z1 = 4, 4, 14, 28          # upright plan: 11 × 25, 6 bays of 4 along z
PERIOD, FLOORS, BAY = 4, 7, 4
ROOF = 1 + FLOORS * PERIOD              # 29
STUB_TOP = 1 + 2 * PERIOD               # where the toppled part was torn from
FAR_TOP, NEAR_TOP = 1 + 4 * PERIOD, 5   # the stub's tear runs from four floors on the west down to one on the east
GAP = 6
FALL_X = X1 + 1 + GAP                   # where the fallen part begins
FALL_H = X1 - X0 + 1                    # how tall the fallen shell lies
CRUSHED = ((5, 9, 4), (14, 17, 6))      # spans of the fallen part (from FALL_X) flattened to a height
TANK = block("waxed_exposed_copper")


def tear(x: int, z: int) -> int:
    """The stub's torn top at (x, z): a ragged diagonal, higher the further from the fall."""
    slope = FAR_TOP - (x - X0) * (FAR_TOP - NEAR_TOP) // (X1 - X0)
    return slope + ((x * 7 + z * 11) % 5) - 2


def draw(canvas: Canvas) -> None:
    footprint_pad(canvas, SPEC, X0, Z0, FALL_X + (ROOF + 3 - STUB_TOP), Z1)
    upright = _upright()
    canvas.put_all({p: s for p, s in upright.items() if p[1] <= tear(p[0], p[2])})
    canvas.put_all(_toppled({p: s for p, s in upright.items() if p[1] > STUB_TOP}))
    _torn_edges(canvas)
    _crushed_spans(canvas)
    _gap_rubble(canvas)
    _stub_air(canvas)
    stub = (X0 + 1, Z0 + 1, X1 - 1, Z1 - 1)
    furnish.kit(canvas, SPEC.seed, "apartment", stub, [2 + f * PERIOD for f in range(4)], 0.06, ruin={"rubble": 0.06, "moss": 0.2})
    furnish.spawners_and_loot(canvas, SPEC.seed, stub, [2, 2 + PERIOD], mobs=("zombie",), spawners=1, chests=1, tiers=(1,))
    inside = (FALL_X + 1, Z0 + 1, FALL_X + ROOF - STUB_TOP - 2, Z1 - 1)
    envelope_air(canvas, inside[0], 2, inside[1], inside[2], FALL_H - 2, inside[3])
    furnish.kit(canvas, SPEC.seed + 1, "apartment", inside, [2], 0.05, ruin={"rubble": 0.08, "moss": 0.22, "cobweb": 0.16, "vines": 0.1})
    furnish.spawners_and_loot(canvas, SPEC.seed + 1, inside, [2], mobs=("zombie", "skeleton"), spawners=1, chests=2, tiers=(1, 2))
    finish(canvas, SPEC, vine_chance=0.24)


def _stub_air(canvas: Canvas) -> None:
    """Explicit air inside the stub only below its torn top, so the sky shows through above the tear."""
    for z in range(Z0 + 1, Z1):
        for x in range(X0 + 1, X1):
            for y in range(2, tear(x, z)):
                if not canvas.has((x, y, z)):
                    canvas.put((x, y, z), AIR)


def _crushed_spans(canvas: Canvas) -> None:
    """Stretches of the fallen shell pancaked: everything above a ragged height goes, rubble on what is left."""
    for (a, b, h) in CRUSHED:
        for x in range(FALL_X + a, FALL_X + b + 1):
            for z in range(Z0, Z1 + 1):
                top = h + ((x * 3 + z * 5) % 3) - 1
                for y in range(top + 1, FALL_H + 4):
                    canvas.clear((x, y, z))
                if (x + z) % 2 == 0 and canvas.has((x, top, z)):
                    canvas.put((x, top + 1, z), RUBBLE[(x * z) % len(RUBBLE)])


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
    """Where it snapped: the fallen part's first plate is ragged, and the stub's torn lip is cracked."""
    for z in range(Z0, Z1 + 1):
        for x in range(X0, X1 + 1):
            lip = (x, tear(x, z), z)
            if canvas.has(lip) and (x + z) % 3 == 0:
                canvas.put(lip, CRACKED_STONE_BRICKS)
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
