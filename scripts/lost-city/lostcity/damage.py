"""Baked structural damage: holes punched through walls with the rubble below, and a collapsed corner.

The processors bite and truncate per placement on top of this; what is baked here is the damage
every copy of the building carries, so even the "shipped" design reads as a ruin.
"""

import math
import random

from .blocks import AIR, COBBLESTONE, CRACKED_STONE_BRICKS, BlockState, block
from .canvas import Canvas, Pos

RUBBLE = (COBBLESTONE, COBBLESTONE, block("mossy_cobblestone"), CRACKED_STONE_BRICKS, block("andesite"), block("tuff"),
          block("cobbled_deepslate"), block("mossy_stone_bricks"), block("cobblestone_slab"), block("stone_brick_stairs"))
ROOF_MASS = ("slab", "bars", "wall", "pane", "carpet", "vine", "stairs", "fence", "chain", "lantern", "leaves", "grass", "bush",
             "fern", "roots", "azalea", "web", "pot", "table", "cauldron", "loom", "shelf", "hay", "note")


LOOSE = frozenset(state.name for state in RUBBLE) | {"minecraft:mossy_cobblestone", "minecraft:cobbled_deepslate"}
NEIGHBOURS = ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, 0, 1), (0, 0, -1))


def drop_loose(canvas: Canvas) -> None:
    """Gravity for the rubble: any loose block with nothing beneath it falls until it lands on something
    (or the pad); a piece that lands on another loose block stacks. Runs bottom-up so heaps settle.
    The vacated cell stays explicit air inside a room and is cleared outdoors, so the world shows through."""
    loose = sorted((pos for pos, state in canvas.freeze().items() if state.name in LOOSE and pos[1] >= 1), key=lambda p: p[1])
    for (x, y, z) in loose:
        state = canvas.get((x, y, z))
        if _in_a_wall(canvas, (x, y, z)):
            continue
        landing = y
        while landing > 1 and _passable(canvas.get((x, landing - 1, z))):
            landing -= 1
        if landing == y:
            continue
        indoors = any(canvas.get((x + dx, y + dy, z + dz)) == AIR for dx, dy, dz in NEIGHBOURS)
        for yy in range(landing, y + 1):        # plants and roots in the way are torn out on the way down
            if indoors:
                canvas.put((x, yy, z), AIR)
            else:
                canvas.clear((x, yy, z))
        canvas.put((x, landing, z), state)


PASSABLE = ("roots", "vine", "grass", "fern", "bush", "web", "leaves", "azalea", "carpet", "torch")


def _passable(state: BlockState | None) -> bool:
    return state is None or state == AIR or any(p in state.name for p in PASSABLE)


def _fabric(state: BlockState | None) -> bool:
    return state is not None and state != AIR and state.name not in LOOSE and not any(p in state.name for p in ROOF_MASS)


def _in_a_wall(canvas: Canvas, pos: Pos) -> bool:
    """A loose-looking block that is really part of the fabric: solid fabric above it, or fabric on both
    sides of it along one axis (a wall course over a hole). Rubble leaning on a wall touches one side only."""
    x, y, z = pos
    if _fabric(canvas.get((x, y + 1, z))):
        return True
    return ((_fabric(canvas.get((x - 1, y, z))) and _fabric(canvas.get((x + 1, y, z))))
            or (_fabric(canvas.get((x, y, z - 1))) and _fabric(canvas.get((x, y, z + 1)))))


def rubble_field(canvas: Canvas, seed: int, box: tuple[int, int, int, int], density: float, height: int,
                 rubble: tuple[BlockState, ...] = RUBBLE) -> None:
    """A field of rubble over the box (x0, z0, x1, z1): heaps up to `height` on the pad and on top of
    whatever already lies there, thickest where two noise fields agree. Runs before the drop pass."""
    rng = random.Random(seed ^ 0xF1E1D)
    x0, z0, x1, z1 = box
    for z in range(z0, z1 + 1):
        for x in range(x0, x1 + 1):
            if rng.random() >= density:
                continue
            base = 1
            while canvas.get((x, base, z)) not in (None, AIR) and base < height + 8:
                base += 1
            for h in range(rng.randint(1, height)):
                pos = (x, base + h, z)
                if not canvas.inside(pos) or canvas.get(pos) not in (None, AIR):
                    break
                canvas.put(pos, rng.choice(rubble))


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
        if rng.random() > 0.85:
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


def _is_wall(canvas: Canvas, x: int, z: int) -> bool:
    """A structural column at (x, z): solid, non-decor cells at y = 1, 2 and 3."""
    for y in (1, 2, 3):
        state = canvas.get((x, y, z))
        if state is None or state == AIR or any(p in state.name for p in ROOF_MASS):
            return False
    return True


def skirt(canvas: Canvas, seed: int, margin: int, band: int = 4, density: float = 0.45,
          rubble: tuple[BlockState, ...] = RUBBLE) -> None:
    """Rubble heaped round the foot of every wall — thickest against it, thinning over `band` blocks — and
    scattered along the inside of the walls too. Every piece sits on a solid cell, never in the margin."""
    rng = random.Random(seed ^ 0x5C1B)
    sx, _, sz = canvas.size
    walls = {(x, z) for z in range(sz) for x in range(sx) if _is_wall(canvas, x, z)}
    if not walls:
        return
    frontier, dist = list(walls), {w: 0 for w in walls}
    while frontier:
        nxt = []
        for x, z in frontier:
            d = dist[(x, z)]
            if d >= band:
                continue
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                n = (x + dx, z + dz)
                if n not in dist and margin <= n[0] < sx - margin and margin <= n[1] < sz - margin:
                    dist[n] = d + 1
                    nxt.append(n)
        frontier = nxt
    for (x, z), d in dist.items():
        if d == 0:
            continue
        pos = (x, 1, z)
        below = canvas.get((x, 0, z))
        if below is None or below == AIR or rng.random() > density * (1 - (d - 1) / band):
            continue
        if canvas.has(pos) and canvas.get(pos) != AIR:
            continue
        canvas.put(pos, rng.choice(rubble))
        if d <= 2 and rng.random() < 0.3 and canvas.get((x, 2, z)) in (None, AIR):
            canvas.put((x, 2, z), rng.choice(rubble[:6]))


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
