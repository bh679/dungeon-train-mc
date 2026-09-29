"""Hotel: a wide two-storey podium with a porte-cochère, a slim tower rising from it, and a sign frame
on the roof. Podium 29 wide, tower 13 wide; both on 4-layer floors."""

from ..blocks import (CONCRETE_WHITE, GLASS, GLASS_CLEAR, RED_PAINT, SIGN_BOARD, SMOOTH_STONE_SLAB, STEEL_DARK,
                      STEEL_WALL, TERRACOTTA_ORANGE)
from ..canvas import Canvas
from ..floors import Facade, roof_plate, tower
from ..shapes import box, column
from ..spec import Archetype, ArchetypeSpec
from .common import finish, footprint_pad

SPEC = ArchetypeSpec(name="hotel", size=(37, 59, 37), floor_period=4, bay_period_x=4, bay_period_z=4,
                     margin=1, seed=0x407E1, weight=5)

P0, P1 = 4, 32                  # podium x/z: 29 = 7 bays
T0, T1 = 12, 24                 # tower x/z: 13 = 3 bays
PERIOD, BAY = 4, 4
PODIUM_FLOORS, TOWER_FLOORS = 2, 11
PODIUM_ROOF = 1 + PODIUM_FLOORS * PERIOD          # 9
TOWER_ROOF = PODIUM_ROOF + TOWER_FLOORS * PERIOD  # 53


def draw(canvas: Canvas) -> None:
    footprint_pad(canvas, SPEC, P0, P0, P1, P1, apron=3)
    podium = Facade(pier=CONCRETE_WHITE, window=GLASS_CLEAR, spandrel=TERRACOTTA_ORANGE, spandrel_layers=1)
    canvas.put_all(tower(P0, P0, P1, P1, 1, PODIUM_FLOORS, PERIOD, BAY, CONCRETE_WHITE, podium))
    canvas.put_all(roof_plate(P0, P0, P1, P1, PODIUM_ROOF, CONCRETE_WHITE))
    upper = Facade(pier=CONCRETE_WHITE, window=GLASS, spandrel=TERRACOTTA_ORANGE, spandrel_layers=1)
    canvas.put_all(tower(T0, T0, T1, T1, PODIUM_ROOF, TOWER_FLOORS, PERIOD, BAY, CONCRETE_WHITE, upper))
    canvas.put_all(roof_plate(T0, T0, T1, T1, TOWER_ROOF, CONCRETE_WHITE))
    _porte_cochere(canvas)
    _sign(canvas)
    finish(canvas, SPEC, envelope=(P0, 1, P0, P1, PODIUM_ROOF, P1))
    finish(canvas, SPEC, envelope=(T0, PODIUM_ROOF, T0, T1, TOWER_ROOF, T1), crack_chance=0.0, moss_chance=0.0,
           vine_chance=0.0)


def _porte_cochere(canvas: Canvas) -> None:
    for x in (T0, T1):
        canvas.put_all(column(x, 1, 1, 4, STEEL_DARK))
    canvas.put_all(box(T0 - 1, 5, 1, T1 + 1, 5, P0 - 1, SMOOTH_STONE_SLAB))
    for y in (1, 2):
        canvas.clear((18, y, P0))


def _sign(canvas: Canvas) -> None:
    for x in (T0 + 1, T1 - 1):
        canvas.put_all(column(x, 18, TOWER_ROOF + 1, TOWER_ROOF + 5, STEEL_WALL))
    canvas.put_all(box(T0 + 1, TOWER_ROOF + 3, 18, T1 - 1, TOWER_ROOF + 5, 18, SIGN_BOARD))
    for x in range(T0 + 3, T1 - 2, 2):
        canvas.put((x, TOWER_ROOF + 4, 18), RED_PAINT)


ARCHETYPE = Archetype(SPEC, draw)
