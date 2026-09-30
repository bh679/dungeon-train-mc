"""The y=0 pad every Lost City template stands on.

LostCityGroundProcessor reads the pad: natural-ground blocks (`PAD_NATURAL`) yield to the world's
own terrain, anything else is kept and propped up with footing. So the pad is natural ground across
the whole footprint, with a paved apron (never natural) hugging the building, so bite rubble and
facade ledges have somewhere to land and the plaza reads as built.
"""

import random

from .blocks import GROUND, PAVEMENT, BlockState
from .canvas import Pos


def pad(size_x: int, size_z: int, apron: tuple[int, int, int, int], seed: int,
        paving: tuple[BlockState, ...] = PAVEMENT, ground: tuple[BlockState, ...] = GROUND) -> dict[Pos, BlockState]:
    """Natural ground everywhere at y=0, paving inside the apron rectangle (x0, z0, x1, z1) inclusive."""
    rng = random.Random(seed)
    x0, z0, x1, z1 = apron
    cells: dict[Pos, BlockState] = {}
    for z in range(size_z):
        for x in range(size_x):
            paved = x0 <= x <= x1 and z0 <= z <= z1
            cells[(x, 0, z)] = rng.choice(paving if paved else ground)
    return cells


def apron_around(x0: int, z0: int, x1: int, z1: int, width: int) -> tuple[int, int, int, int]:
    """The apron rectangle `width` blocks out from a building footprint."""
    return x0 - width, z0 - width, x1 + width, z1 + width
