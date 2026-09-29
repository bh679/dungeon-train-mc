"""Baked overgrowth: cracked blocks, missing windows, moss, vines, roof gardens, roots, weeds, bushes.

The originals are green: moss blocks on every ledge, vines down every wall, whole roofs gone to
soil and saplings. This pass does the same on ours, on top of the per-placement processors. All
rolls come from one seeded RNG so a re-run is byte-identical. Nothing placed here can fall.
"""

import random

from .blocks import (AIR, AZALEA, DEAD_BUSH, FERN, FLOWERING_AZALEA, HANGING_ROOTS, MOSS_BLOCK, MOSS_CARPET, OAK_LEAVES,
                     PAD_NATURAL, SHORT_GRASS, TALL_GRASS, BlockState, block)
from .canvas import Canvas, Pos

SIDES = ((0, 1, "south"), (0, -1, "north"), (1, 0, "west"), (-1, 0, "east"))
NOT_FULL = ("slab", "stairs", "pane", "bars", "wall", "carpet", "vine", "chain", "ladder", "lantern", "button",
            "trapdoor", "rail", "bush", "grass", "leaves", "azalea", "bell", "cauldron", "hopper", "door", "rod", "roots",
            "fern", "pot", "web", "table", "loom", "shelf", "hay", "note", "fence", "glass")
GRASS_BLOCK = block("grass_block")


def weather(canvas: Canvas, seed: int, cracked: dict[BlockState, BlockState], crack_chance: float = 0.12,
            moss_chance: float = 0.18, vine_chance: float = 0.16, min_y: int = 1, margin: int = 0,
            moss_block_chance: float = 0.10, leaf_clumps: int | None = None, weeds_chance: float = 0.35,
            roots_chance: float = 0.06, gardens: int | None = None, vine_drop: tuple[int, int] = (2, 7)) -> None:
    rng = random.Random(seed)
    cells = list(canvas.freeze().items())
    size = len(cells)
    for pos, state in cells:
        if pos[1] >= min_y and state in cracked and rng.random() < crack_chance:
            canvas.put(pos, cracked[state])
    _moss_blocks(canvas, rng, cells, moss_block_chance)
    _roof_gardens(canvas, rng, cells, gardens if gardens is not None else max(2, min(10, size // 5000)), margin)
    cells = list(canvas.freeze().items())
    _moss(canvas, rng, cells, moss_chance)
    _vines(canvas, rng, cells, vine_chance, margin, vine_drop)
    _roots(canvas, rng, cells, roots_chance, margin)
    _weeds(canvas, rng, weeds_chance, margin)
    _leaves(canvas, rng, cells, leaf_clumps if leaf_clumps is not None else max(6, min(24, size // 2500)), margin)


def _moss_blocks(canvas: Canvas, rng: random.Random, cells, chance: float) -> None:
    """Outdoor full blocks turn to moss block — roof edges, sills, the tops of broken walls."""
    for pos, state in cells:
        if pos[1] >= 1 and state != AIR and _is_full(state) and _exposed_top(canvas, pos) and rng.random() < chance:
            canvas.put(pos, MOSS_BLOCK)


def _roof_gardens(canvas: Canvas, rng: random.Random, cells, count: int, margin: int) -> None:
    """Patches of soil that have gathered on the roofs and ledges, with grass and a bush on each."""
    sx, _, sz = canvas.size
    tops = [pos for pos, state in cells if pos[1] >= 4 and state != AIR and _is_full(state) and _exposed_top(canvas, pos)]
    if not tops:
        return
    for _ in range(count):
        cx, cy, cz = rng.choice(tops)
        r = rng.randint(2, 4)
        for z in range(cz - r, cz + r + 1):
            for x in range(cx - r, cx + r + 1):
                if (x - cx) ** 2 + (z - cz) ** 2 > r * r + rng.random() * 2 or not (margin <= x < sx - margin and margin <= z < sz - margin):
                    continue
                pos = (x, cy, z)
                state = canvas.get(pos)
                if state is None or state == AIR or not _is_full(state) or not _exposed_top(canvas, pos):
                    continue
                canvas.put(pos, MOSS_BLOCK if rng.random() < 0.7 else GRASS_BLOCK)
                above = (x, cy + 1, z)
                if canvas.inside(above) and not canvas.has(above) and rng.random() < 0.55:
                    canvas.put(above, rng.choice((SHORT_GRASS, SHORT_GRASS, FERN, TALL_GRASS, AZALEA, FLOWERING_AZALEA)))


def _weeds(canvas: Canvas, rng: random.Random, chance: float, margin: int) -> None:
    """Grass, ferns and bushes on the pad's natural ground, dead bushes and the odd fern on its paving."""
    sx, _, sz = canvas.size
    for z in range(margin, sz - margin):
        for x in range(margin, sx - margin):
            pad = canvas.get((x, 0, z))
            above = (x, 1, z)
            if pad is None or canvas.has(above) or rng.random() >= chance:
                continue
            if pad.name in PAD_NATURAL:
                canvas.put(above, rng.choice((SHORT_GRASS, SHORT_GRASS, SHORT_GRASS, FERN, TALL_GRASS, TALL_GRASS, AZALEA,
                                              FLOWERING_AZALEA, DEAD_BUSH)))
            else:
                canvas.put(above, rng.choice((DEAD_BUSH, DEAD_BUSH, SHORT_GRASS, FERN, MOSS_CARPET)))


def _leaves(canvas: Canvas, rng: random.Random, cells, clumps: int, margin: int) -> None:
    """Bushes of leaves against the walls and on the roofs, as the originals' saplings-gone-wild."""
    sx, _, sz = canvas.size
    tops = [pos for pos, state in cells if state != AIR and _is_full(state) and _exposed_top(canvas, pos)]
    if not tops:
        return
    for _ in range(clumps):
        cx, cy, cz = rng.choice(tops)
        r = rng.randint(1, 3)
        for y in range(cy + 1, cy + 2 + r):
            for z in range(cz - r, cz + r + 1):
                for x in range(cx - r, cx + r + 1):
                    pos = (x, y, z)
                    if abs(x - cx) + abs(y - cy - 1) + abs(z - cz) > r + 1 or not canvas.inside(pos) or canvas.has(pos):
                        continue
                    if margin <= x < sx - margin and margin <= z < sz - margin:
                        canvas.put(pos, OAK_LEAVES if rng.random() < 0.85 else AZALEA)


def _roots(canvas: Canvas, rng: random.Random, cells, chance: float, margin: int) -> None:
    """Hanging roots under ledges, floor edges and canopies where the soil above has crept through."""
    sx, _, sz = canvas.size
    for (x, y, z), state in cells:
        if y < 3 or state == AIR or not _is_full(state) or rng.random() >= chance:
            continue
        below = (x, y - 1, z)
        if canvas.has(below) or not (margin <= x < sx - margin and margin <= z < sz - margin):
            continue
        canvas.put(below, HANGING_ROOTS)


def _exposed_top(canvas: Canvas, pos: Pos) -> bool:
    """An outdoor top: nothing at all above it (interior air is an explicit AIR cell, so rooms stay clean)."""
    above = (pos[0], pos[1] + 1, pos[2])
    return canvas.inside(above) and not canvas.has(above)


def _moss(canvas: Canvas, rng: random.Random, cells, chance: float) -> None:
    for pos, state in cells:
        if pos[1] >= 1 and state != AIR and _is_full(state) and _exposed_top(canvas, pos) and rng.random() < chance:
            canvas.put((pos[0], pos[1] + 1, pos[2]), MOSS_CARPET)


def _vines(canvas: Canvas, rng: random.Random, cells, chance: float, margin: int, drop_range=(2, 7)) -> None:
    sx, _, sz = canvas.size
    for pos, state in cells:
        if pos[1] < 3 or state == AIR or not _is_full(state) or rng.random() >= chance:
            continue
        dx, dz, face = rng.choice(SIDES)
        outside = (pos[0] + dx, pos[1], pos[2] + dz)
        if not canvas.inside(outside) or canvas.has(outside) or _in_margin(outside, sx, sz, margin):
            continue
        for drop in range(rng.randint(*drop_range)):
            at = (outside[0], outside[1] - drop, outside[2])
            if at[1] < 1 or canvas.has(at):
                break
            canvas.put(at, block("vine", **{face: "true"}))


def _is_full(state: BlockState) -> bool:
    return not any(part in state.name for part in NOT_FULL)


def _in_margin(pos: Pos, sx: int, sz: int, margin: int) -> bool:
    return pos[0] < margin or pos[2] < margin or pos[0] >= sx - margin or pos[2] >= sz - margin
