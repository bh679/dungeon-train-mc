"""Pure geometry helpers. Each returns the cells it describes; nothing here touches a canvas."""

import math
from typing import Iterator

from .blocks import BlockState
from .canvas import Pos


def box(x0: int, y0: int, z0: int, x1: int, y1: int, z1: int, state: BlockState) -> dict[Pos, BlockState]:
    """A filled box, inclusive of both corners."""
    return {(x, y, z): state
            for y in range(min(y0, y1), max(y0, y1) + 1)
            for z in range(min(z0, z1), max(z0, z1) + 1)
            for x in range(min(x0, x1), max(x0, x1) + 1)}


def walls(x0: int, y0: int, z0: int, x1: int, y1: int, z1: int, state: BlockState) -> dict[Pos, BlockState]:
    """The four side faces of a box (no floor, no ceiling)."""
    return {(x, y, z): state for (x, y, z) in box(x0, y0, z0, x1, y1, z1, state)
            if x in (x0, x1) or z in (z0, z1)}


def perimeter(x0: int, z0: int, x1: int, z1: int, y: int) -> Iterator[tuple[int, int, int, int]]:
    """Cells on the rectangle's edge at height y, with the distance along the edge.

    Yields (x, y, z, along) where `along` counts blocks from the rectangle's corner along the wall —
    what a bay pattern is keyed on."""
    for x in range(x0, x1 + 1):
        yield x, y, z0, x - x0
        yield x, y, z1, x - x0
    for z in range(z0 + 1, z1):
        yield x0, y, z, z - z0
        yield x1, y, z, z - z0


def column(x: int, z: int, y0: int, y1: int, state: BlockState) -> dict[Pos, BlockState]:
    return {(x, y, z): state for y in range(y0, y1 + 1)}


def disc(cx: float, cz: float, y: int, r: float, state: BlockState) -> dict[Pos, BlockState]:
    span = int(math.ceil(r)) + 1
    return {(x, y, z): state
            for z in range(int(cz) - span, int(cz) + span + 1)
            for x in range(int(cx) - span, int(cx) + span + 1)
            if math.hypot(x - cx, z - cz) <= r + 0.5}


def ring(cx: float, cz: float, y: int, r: float, state: BlockState, thick: float = 1.0) -> dict[Pos, BlockState]:
    """A circle outline `thick` blocks wide: the cells whose distance from the centre is within it."""
    span = int(math.ceil(r)) + 1
    return {(x, y, z): state
            for z in range(int(cz) - span, int(cz) + span + 1)
            for x in range(int(cx) - span, int(cx) + span + 1)
            if r - thick + 0.5 < math.hypot(x - cx, z - cz) <= r + 0.5}


def hyperboloid_radius(y: int, height: int, r_base: float, r_waist: float, waist_at: float = 0.7) -> float:
    """Radius of a cooling-tower shell at layer y: a parabola-shaped hyperbola from r_base at the
    foot through r_waist at `waist_at` of the height, flaring again slightly above."""
    t = y / max(1, height - 1)
    return r_waist + (r_base - r_waist) * ((t - waist_at) / waist_at) ** 2


def lattice(x0: int, z0: int, x1: int, z1: int, y0: int, y1: int, leg: BlockState, brace: BlockState,
            period: int) -> dict[Pos, BlockState]:
    """A square lattice mast: full-block legs at the corners, brace blocks joining them every `period`
    layers, so the mast repeats exactly and a floor-stretch can lengthen it."""
    cells: dict[Pos, BlockState] = {}
    for (x, z) in ((x0, z0), (x1, z0), (x0, z1), (x1, z1)):
        cells.update(column(x, z, y0, y1, leg))
    for y in range(y0, y1 + 1):
        if (y - y0) % period == 0:
            for (x, _, z, _) in perimeter(x0, z0, x1, z1, y):
                cells.setdefault((x, y, z), brace)
    return cells


def stairs_run(x: int, z: int, y: int, length: int, dx: int, dz: int, state: BlockState) -> dict[Pos, BlockState]:
    """`length` steps rising one block per step in direction (dx, dz)."""
    return {(x + i * dx, y + i, z + i * dz): state for i in range(length)}
