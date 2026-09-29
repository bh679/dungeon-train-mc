"""Shopping strip: a long low row of shopfronts under a signboard, awnings over the windows, and an
asphalt car park out front with marked bays. Shopfronts repeat every 4 along x."""

import random

from ..blocks import ASPHALT, BRICK, CONCRETE_DARK, COPPER_SLAB, GLASS_CLEAR, RED_PAINT, ROAD_LINE, SIGN_BOARD, block
from ..canvas import Canvas
from ..shapes import box, walls
from ..spec import Archetype, ArchetypeSpec
from .common import finish, footprint_pad

SPEC = ArchetypeSpec(name="shopping_strip", size=(57, 9, 27), floor_period=None, bay_period_x=4, bay_period_z=None,
                     margin=3, seed=0x5409, weight=5)

X0, Z0, X1, Z1 = 4, 12, 52, 22          # 49 wide = 12 bays of 4
BAY = 4
ROOF = 7
CARPARK = (3, 3, X1 + 1, Z0 - 1)         # x0, z0, x1, z1 at y=0
LETTERS = (RED_PAINT, block("blue_concrete"), block("yellow_concrete"), block("black_concrete"))


def draw(canvas: Canvas) -> None:
    footprint_pad(canvas, SPEC, X0, Z0, X1, Z1, apron=3)
    _carpark(canvas)
    canvas.put_all(walls(X0, 1, Z0, X1, ROOF - 1, Z1, BRICK))
    _shopfronts(canvas)
    canvas.put_all(box(X0, ROOF, Z0, X1, ROOF, Z1, CONCRETE_DARK))
    canvas.put_all(walls(X0, ROOF + 1, Z0, X1, ROOF + 1, Z1, BRICK))
    for x in range(X0 + 12, X1, 12):
        canvas.put_all(box(x, 1, Z0 + 1, x, ROOF - 1, Z1 - 1, BRICK))
    finish(canvas, SPEC, envelope=(X0, 1, Z0, X1, ROOF, Z1))


def _carpark(canvas: Canvas) -> None:
    x0, z0, x1, z1 = CARPARK
    canvas.put_all(box(x0, 0, z0, x1, 0, z1, ASPHALT))
    for x in range(X0, X1 + 1, BAY):
        canvas.put_all(box(x, 0, z0 + 2, x, 0, z0 + 5, ROAD_LINE))


def _shopfronts(canvas: Canvas) -> None:
    rng = random.Random(SPEC.seed)
    for x in range(X0, X1 + 1):
        pier = (x - X0) % BAY == 0
        canvas.put((x, 1, Z0), BRICK if pier else CONCRETE_DARK)
        for y in (2, 3, 4):
            canvas.put((x, y, Z0), BRICK if pier else GLASS_CLEAR)
        for y in (5, 6):
            canvas.put((x, y, Z0), SIGN_BOARD)
        if not pier and rng.random() < 0.3:
            canvas.put((x, 5 + rng.randint(0, 1), Z0), rng.choice(LETTERS))
        if not pier:
            canvas.put((x, 4, Z0 - 1), COPPER_SLAB.with_props(type="top"))


ARCHETYPE = Archetype(SPEC, draw)
