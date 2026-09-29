"""Apartment slab: a long brick block with balconies down both long sides, stair towers at each end
and water tanks on the roof. 13 × 45, 9 floors of 4 layers."""

from ..blocks import BRICK, CONCRETE_WHITE, GLASS_CLEAR, SMOOTH_STONE_SLAB, STEEL_BARS, STEEL_DARK, TERRACOTTA_WHITE, block
from .. import furnish
from ..canvas import Canvas, envelope_air
from ..floors import Facade, roof_plate, tower
from ..shapes import box, walls
from ..spec import Archetype, ArchetypeSpec
from .common import finish, footprint_pad

SPEC = ArchetypeSpec(name="apartment_block", size=(23, 42, 53), floor_period=4, bay_period_x=4, bay_period_z=4,
                     margin=4, seed=0xA9A97, weight=6)

X0, Z0, X1, Z1 = 5, 4, 17, 48          # 13 × 45: 3 × 11 bays of 4
PERIOD, FLOORS, BAY = 4, 9, 4
ROOF = 1 + FLOORS * PERIOD              # 37
TANK = block("waxed_exposed_copper")


def draw(canvas: Canvas) -> None:
    footprint_pad(canvas, SPEC, X0 - 1, Z0, X1 + 1, Z1)
    facade = Facade(pier=BRICK, window=GLASS_CLEAR, spandrel=BRICK, spandrel_layers=1)
    canvas.put_all(tower(X0, Z0, X1, Z1, 1, FLOORS, PERIOD, BAY, TERRACOTTA_WHITE, facade))
    _balconies(canvas)
    canvas.put_all(roof_plate(X0, Z0, X1, Z1, ROOF, TERRACOTTA_WHITE))
    _stair_towers(canvas)
    _tanks(canvas)
    envelope_air(canvas, X0, 1, Z0, X1, ROOF, Z1)
    furnish.kit(canvas, SPEC.seed, "apartment", (X0 + 1, Z0 + 1, X1 - 1, Z1 - 1), [2 + f * PERIOD for f in range(FLOORS)], 0.08)
    finish(canvas, SPEC)


def _balconies(canvas: Canvas) -> None:
    """A slab shelf and a bar railing outside every window bay on the long faces, at each floor."""
    for f in range(1, FLOORS):
        y = 1 + f * PERIOD
        for z in range(Z0, Z1 + 1):
            if (z - Z0) % BAY == 0:
                continue
            for x in (X0 - 1, X1 + 1):
                canvas.put((x, y, z), SMOOTH_STONE_SLAB.with_props(type="top"))
                canvas.put((x, y + 1, z), STEEL_BARS)


def _stair_towers(canvas: Canvas) -> None:
    for z0, z1 in ((Z0, Z0 + 4), (Z1 - 4, Z1)):
        canvas.put_all(walls(X0 + 4, 1, z0, X1 - 4, ROOF + 3, z1, STEEL_DARK))
        canvas.put_all(box(X0 + 4, ROOF + 4, z0, X1 - 4, ROOF + 4, z1, CONCRETE_WHITE))


def _tanks(canvas: Canvas) -> None:
    for z in (18, 26, 34):
        canvas.put_all(box(X0 + 3, ROOF + 1, z, X0 + 5, ROOF + 3, z + 2, TANK))
        canvas.put_all(box(X1 - 5, ROOF + 1, z, X1 - 3, ROOF + 3, z + 2, TANK))


ARCHETYPE = Archetype(SPEC, draw)
