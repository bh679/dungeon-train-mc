"""Office tower: glass curtain wall between concrete floor plates, a central service core, a plant
room and a mast on the roof. 21 wide, 13 floors of 5 layers."""

from ..blocks import CONCRETE, CONCRETE_DARK, CONCRETE_WHITE, GLASS, GLASS_CLEAR, GLASS_CLEAR_PANE, STEEL, STEEL_BARS, STEEL_DARK
from ..canvas import Canvas
from ..floors import Facade, roof_plate, tower
from ..shapes import box, column, walls
from ..spec import Archetype, ArchetypeSpec
from .common import finish, footprint_pad

SPEC = ArchetypeSpec(name="office_tower", size=(29, 78, 29), floor_period=5, bay_period_x=4, bay_period_z=4,
                     margin=4, seed=0x0FF1CE, weight=6)

X0, Z0, X1, Z1 = 4, 4, 24, 24          # 21 wide: 5 bays of 4
PERIOD, FLOORS, BAY = 5, 13, 4
ROOF = 1 + FLOORS * PERIOD              # 66
CORE = (12, 12, 16, 16)                 # 5×5 core, edges on the piers at x/z = 12 and 16


def draw(canvas: Canvas) -> None:
    footprint_pad(canvas, SPEC, X0, Z0, X1, Z1)
    facade = Facade(pier=CONCRETE, window=GLASS, spandrel=CONCRETE_WHITE, spandrel_layers=1)
    canvas.put_all(tower(X0, Z0, X1, Z1, 1, FLOORS, PERIOD, BAY, CONCRETE, facade))
    _lobby(canvas)
    canvas.put_all(walls(CORE[0], 1, CORE[1], CORE[2], ROOF - 1, CORE[3], CONCRETE_DARK))
    canvas.put_all(roof_plate(X0, Z0, X1, Z1, ROOF, CONCRETE))
    _roof(canvas)
    finish(canvas, SPEC, envelope=(X0, 1, Z0, X1, ROOF, Z1))


def _lobby(canvas: Canvas) -> None:
    """Ground floor: clear glass and an entrance on the south face."""
    for layer in range(2, PERIOD):
        for x in range(X0 + 1, X1):
            if x % BAY != 0:
                canvas.put((x, layer, Z0), GLASS_CLEAR)
    for y in (1, 2):
        canvas.put((13, y, Z0), GLASS_CLEAR_PANE)
        canvas.put((15, y, Z0), GLASS_CLEAR_PANE)
        canvas.clear((14, y, Z0))


def _roof(canvas: Canvas) -> None:
    canvas.put_all(box(9, ROOF + 1, 9, 19, ROOF + 5, 19, STEEL_DARK))
    canvas.put_all(box(10, ROOF + 1, 10, 18, ROOF + 4, 18, CONCRETE_DARK))
    canvas.put_all(walls(X0, ROOF + 1, Z0, X1, ROOF + 1, Z1, STEEL_BARS))
    canvas.put_all(column(14, 14, ROOF + 6, ROOF + 11, STEEL))
    canvas.put((14, ROOF + 6, 12), STEEL_BARS)
    canvas.put((14, ROOF + 6, 16), STEEL_BARS)


ARCHETYPE = Archetype(SPEC, draw)
