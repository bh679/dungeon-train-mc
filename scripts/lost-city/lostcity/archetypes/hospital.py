"""Hospital: an H-plan block — two long wings joined by a link — five floors of 5 layers, a helipad on
the link roof and a red cross over the entrance."""

from ..blocks import CONCRETE, CONCRETE_WHITE, GLASS_CLEAR, RED_PAINT, ROAD_LINE, STEEL_BARS
from ..canvas import Canvas
from ..floors import Facade, roof_plate, tower
from ..shapes import box, ring, walls
from ..spec import Archetype, ArchetypeSpec
from .common import finish, footprint_pad

SPEC = ArchetypeSpec(name="hospital", size=(49, 28, 45), floor_period=5, bay_period_x=4, bay_period_z=4,
                     margin=4, seed=0x4051, weight=5)

WING_A = (4, 4, 16, 40)        # x0, z0, x1, z1 — 13 × 37
WING_B = (32, 4, 44, 40)
LINK = (16, 18, 32, 26)        # 17 × 9
PERIOD, FLOORS, BAY = 5, 5, 4
ROOF = 1 + FLOORS * PERIOD     # 26
PARTS = (WING_A, WING_B, LINK)


def draw(canvas: Canvas) -> None:
    footprint_pad(canvas, SPEC, WING_A[0], WING_A[1], WING_B[2], WING_B[3], apron=3)
    facade = Facade(pier=CONCRETE_WHITE, window=GLASS_CLEAR, spandrel=CONCRETE, spandrel_layers=1)
    for (x0, z0, x1, z1) in PARTS:
        canvas.put_all(tower(x0, z0, x1, z1, 1, FLOORS, PERIOD, BAY, CONCRETE_WHITE, facade))
        canvas.put_all(roof_plate(x0, z0, x1, z1, ROOF, CONCRETE_WHITE))
    for (x0, z0, x1, z1) in PARTS:
        canvas.put_all(walls(x0, ROOF + 1, z0, x1, ROOF + 1, z1, STEEL_BARS))
    _helipad(canvas)
    _entrance(canvas)
    for (x0, z0, x1, z1) in PARTS:
        finish(canvas, SPEC, envelope=(x0, 1, z0, x1, ROOF, z1), crack_chance=0.0, moss_chance=0.0, vine_chance=0.0)
    finish(canvas, SPEC, envelope=None)


def _helipad(canvas: Canvas) -> None:
    cx, cz = 24, 22
    canvas.put_all(ring(cx, cz, ROOF, 3.5, ROAD_LINE))
    canvas.put_all(box(cx - 1, ROOF, cz - 2, cx - 1, ROOF, cz + 2, ROAD_LINE))
    canvas.put_all(box(cx + 1, ROOF, cz - 2, cx + 1, ROOF, cz + 2, ROAD_LINE))
    canvas.put((cx, ROOF, cz), ROAD_LINE)


def _entrance(canvas: Canvas) -> None:
    z = LINK[1]
    for x in (23, 24, 25):
        for y in (1, 2):
            canvas.clear((x, y, z))
    canvas.put_all(box(23, 3, z, 25, 3, z, RED_PAINT))
    canvas.put((24, 4, z), RED_PAINT)


ARCHETYPE = Archetype(SPEC, draw)
