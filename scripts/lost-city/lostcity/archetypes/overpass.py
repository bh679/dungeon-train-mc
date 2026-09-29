"""Elevated highway fragment: a two-lane concrete deck on paired piers every 12 blocks, guard rails,
lane markings, and a collapsed end with the rubble below."""

import random

from ..blocks import ASPHALT, COBBLESTONE, CONCRETE, CONCRETE_DARK, GRAVEL, ROAD_LINE, ROAD_LINE_YELLOW, STEEL_WALL
from ..canvas import Canvas
from ..shapes import box, column
from ..spec import Archetype, ArchetypeSpec
from .common import finish, footprint_pad

SPEC = ArchetypeSpec(name="overpass", size=(23, 15, 70), floor_period=None, bay_period_x=None, bay_period_z=12,
                     margin=3, seed=0x0BEA55, weight=4)

X0, X1 = 5, 17
DECK_Y = 12
Z0, Z1 = 4, 65
PIER_EVERY = 12
COLLAPSE_FROM = 56


def draw(canvas: Canvas) -> None:
    footprint_pad(canvas, SPEC, X0, Z0, X1, Z1, apron=2)
    for z in range(Z0, Z1 + 1):
        _deck_slice(canvas, z)
    for z in range(Z0 + 4, COLLAPSE_FROM, PIER_EVERY):
        _pier(canvas, z)
    _collapse(canvas)
    finish(canvas, SPEC, envelope=None, moss_chance=0.03)


def _deck_slice(canvas: Canvas, z: int) -> None:
    canvas.put_all(box(X0, DECK_Y, z, X1, DECK_Y, z, CONCRETE_DARK))
    canvas.put_all(box(X0, DECK_Y + 1, z, X1, DECK_Y + 1, z, ASPHALT))
    canvas.put((X0 + 1, DECK_Y + 1, z), ROAD_LINE)
    canvas.put((X1 - 1, DECK_Y + 1, z), ROAD_LINE)
    if (z - Z0) % 4 < 2:
        canvas.put((11, DECK_Y + 1, z), ROAD_LINE_YELLOW)
    canvas.put((X0, DECK_Y + 2, z), STEEL_WALL)
    canvas.put((X1, DECK_Y + 2, z), STEEL_WALL)


def _pier(canvas: Canvas, z: int) -> None:
    for x in (7, 8, 14, 15):
        canvas.put_all(column(x, z, 1, DECK_Y - 2, CONCRETE))
    canvas.put_all(box(X0 + 1, DECK_Y - 1, z, X1 - 1, DECK_Y - 1, z, CONCRETE))


def _collapse(canvas: Canvas) -> None:
    """The last stretch of deck has fallen: it is removed and heaped as rubble on the pad."""
    rng = random.Random(SPEC.seed)
    for z in range(COLLAPSE_FROM, Z1 + 1):
        for x in range(X0, X1 + 1):
            for y in (DECK_Y, DECK_Y + 1, DECK_Y + 2):
                canvas.clear((x, y, z))
            if rng.random() < 0.55:
                height = rng.randint(1, 3 if z < COLLAPSE_FROM + 5 else 1)
                canvas.put_all(box(x, 1, z, x, height, z, rng.choice((COBBLESTONE, CONCRETE_DARK, GRAVEL, ASPHALT))))


ARCHETYPE = Archetype(SPEC, draw)
