"""Baked structural damage: holes punched through walls with the rubble below, and a collapsed corner.

The processors bite and truncate per placement on top of this; what is baked here is the damage
every copy of the building carries, so even the "shipped" design reads as a ruin.
"""

import math
import random

from .blocks import AIR, COBBLESTONE, CRACKED_STONE_BRICKS, GRAVEL, BlockState, block
from .canvas import Canvas, Pos

RUBBLE = (COBBLESTONE, block("mossy_cobblestone"), GRAVEL, CRACKED_STONE_BRICKS, block("andesite"), block("tuff"))


def holes(canvas: Canvas, seed: int, count: tuple[int, int] = (3, 6), radius: tuple[float, float] = (1.5, 3.2),
          min_y: int = 2, rubble: tuple[BlockState, ...] = RUBBLE, margin: int = 0) -> None:
    """Punch `count` ragged holes through exterior walls, heaping rubble on whatever is below."""
    rng = random.Random(seed ^ 0xD0CA)
    exterior = _exterior_cells(canvas, min_y)
    if not exterior:
        return
    for _ in range(rng.randint(*count)):
        centre = rng.choice(exterior)
        removed = _carve(canvas, centre, rng.uniform(*radius), rng)
        _heap(canvas, removed, rng, rubble, margin)


def _exterior_cells(canvas: Canvas, min_y: int) -> list[Pos]:
    cells = canvas.freeze()
    out = []
    for (x, y, z), state in cells.items():
        if y < min_y or state == AIR:
            continue
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (x + dx, y, z + dz) not in cells:
                out.append((x, y, z))
                break
    return out


def _carve(canvas: Canvas, centre: Pos, r: float, rng: random.Random) -> list[Pos]:
    cx, cy, cz = centre
    removed = []
    span = int(math.ceil(r * 1.3)) + 1
    for y in range(max(1, cy - span), cy + span + 1):
        for z in range(cz - span, cz + span + 1):
            for x in range(cx - span, cx + span + 1):
                d = math.sqrt((x - cx) ** 2 + ((y - cy) / 1.3) ** 2 + (z - cz) ** 2)
                if d > r * rng.uniform(0.8, 1.15):
                    continue
                pos = (x, y, z)
                if canvas.inside(pos) and canvas.has(pos) and canvas.get(pos) != AIR:
                    canvas.put(pos, AIR)
                    removed.append(pos)
    return removed


def _heap(canvas: Canvas, removed: list[Pos], rng: random.Random, rubble: tuple[BlockState, ...], margin: int = 0) -> None:
    """Stack rubble on the first solid cell under each removed column, most where most fell. A piece only
    lands where something solid is directly beneath it, and never in the template's clear margin."""
    sx, _, sz = canvas.size
    columns: dict[tuple[int, int], list[int]] = {}
    for x, y, z in removed:
        columns.setdefault((x, z), []).append(y)
    for (x, z), ys in columns.items():
        if rng.random() > 0.6:
            continue
        floor_y = _floor_below(canvas, x, min(ys), z)
        if floor_y is None:
            continue
        px, pz = x + rng.randint(-1, 1), z + rng.randint(-1, 1)
        if not (margin <= px < sx - margin and margin <= pz < sz - margin):
            continue
        below = canvas.get((px, floor_y, pz))
        if below is None or below == AIR:
            continue
        for i in range(1, min(3, 1 + len(ys) // 3) + 1):
            pos = (px, floor_y + i, pz)
            if canvas.inside(pos) and pos[1] >= 1 and (not canvas.has(pos) or canvas.get(pos) == AIR):
                canvas.put(pos, rng.choice(rubble))


def _floor_below(canvas: Canvas, x: int, y: int, z: int) -> int | None:
    for yy in range(y - 1, -1, -1):
        state = canvas.get((x, yy, z))
        if state is not None and state != AIR:
            return yy
    return None


def collapsed_corner(canvas: Canvas, seed: int, box: tuple[int, int, int, int], top: int, depth: int, min_y: int = 2,
                     rubble: tuple[BlockState, ...] = RUBBLE, margin: int = 0) -> None:
    """Shear one corner off the top `depth` layers of the box (x0, z0, x1, z1): a diagonal wedge
    that widens toward the roof, with the fallen mass heaped below."""
    rng = random.Random(seed ^ 0xC04E)
    x0, z0, x1, z1 = box
    cx, cz = rng.choice(((x0, z0), (x1, z0), (x0, z1), (x1, z1)))
    removed = []
    for y in range(max(min_y, top - depth + 1), top + 1):
        reach = int((y - (top - depth)) / depth * (min(x1 - x0, z1 - z0) * 0.55)) + rng.randint(0, 1)
        for z in range(z0, z1 + 1):
            for x in range(x0, x1 + 1):
                if abs(x - cx) + abs(z - cz) < reach:
                    pos = (x, y, z)
                    if canvas.has(pos) and canvas.get(pos) != AIR:
                        canvas.put(pos, AIR)
                        removed.append(pos)
    _heap(canvas, removed, rng, rubble, margin)
