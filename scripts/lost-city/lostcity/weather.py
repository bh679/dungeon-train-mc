"""Light baked weathering: a few cracked blocks, missing windows, moss on ledges, vines down walls.

Deliberately light. The heavy ruin — bites, truncation, decay, overgrowth stripping — is rolled per
placement by the processors, so the shipped template only needs to stop reading as brand new. All
rolls come from one seeded RNG so a re-run is byte-identical.
"""

import random

from .blocks import (AIR, AZALEA, DEAD_BUSH, FERN, MOSS_BLOCK, MOSS_CARPET, OAK_LEAVES, PAD_NATURAL, SHORT_GRASS, BlockState,
                     block)
from .canvas import Canvas, Pos

SIDES = ((0, 1, "south"), (0, -1, "north"), (1, 0, "west"), (-1, 0, "east"))
NOT_FULL = ("slab", "stairs", "pane", "bars", "wall", "carpet", "vine", "chain", "ladder", "lantern", "button",
            "trapdoor", "rail", "bush", "grass", "leaves", "azalea", "bell", "cauldron", "hopper", "door", "rod")


def weather(canvas: Canvas, seed: int, cracked: dict[BlockState, BlockState], crack_chance: float = 0.10,
            moss_chance: float = 0.10, vine_chance: float = 0.07, min_y: int = 1, margin: int = 0,
            moss_block_chance: float = 0.06, leaf_clumps: int = 4, weeds_chance: float = 0.12) -> None:
    rng = random.Random(seed)
    cells = list(canvas.freeze().items())
    for pos, state in cells:
        if pos[1] >= min_y and state in cracked and rng.random() < crack_chance:
            canvas.put(pos, cracked[state])
    _moss_blocks(canvas, rng, cells, moss_block_chance)
    _moss(canvas, rng, cells, moss_chance)
    _vines(canvas, rng, cells, vine_chance, margin)
    _weeds(canvas, rng, weeds_chance, margin)
    _leaves(canvas, rng, cells, leaf_clumps, margin)


def _moss_blocks(canvas: Canvas, rng: random.Random, cells, chance: float) -> None:
    """Outdoor full blocks turn to moss block — roof edges, sills, the tops of broken walls."""
    for pos, state in cells:
        if pos[1] >= 1 and state != AIR and _is_full(state) and "glass" not in state.name and _exposed_top(canvas, pos) \
                and rng.random() < chance:
            canvas.put(pos, MOSS_BLOCK)


def _weeds(canvas: Canvas, rng: random.Random, chance: float, margin: int) -> None:
    """Grass and ferns on the pad's natural ground, dead bushes on its paving."""
    sx, _, sz = canvas.size
    for z in range(margin, sz - margin):
        for x in range(margin, sx - margin):
            pad = canvas.get((x, 0, z))
            above = (x, 1, z)
            if pad is None or canvas.has(above) or rng.random() >= chance:
                continue
            natural = pad.name in PAD_NATURAL
            canvas.put(above, rng.choice((SHORT_GRASS, SHORT_GRASS, FERN)) if natural else DEAD_BUSH)


def _leaves(canvas: Canvas, rng: random.Random, cells, clumps: int, margin: int) -> None:
    """A few bushes of leaves against the walls and on the roofs, as the originals' saplings-gone-wild."""
    sx, _, sz = canvas.size
    tops = [pos for pos, state in cells if state != AIR and _is_full(state) and _exposed_top(canvas, pos)]
    if not tops:
        return
    for _ in range(clumps):
        cx, cy, cz = rng.choice(tops)
        r = rng.randint(1, 2)
        for y in range(cy + 1, cy + 2 + r):
            for z in range(cz - r, cz + r + 1):
                for x in range(cx - r, cx + r + 1):
                    pos = (x, y, z)
                    if abs(x - cx) + abs(y - cy - 1) + abs(z - cz) > r + 1 or not canvas.inside(pos) or canvas.has(pos):
                        continue
                    if margin <= x < sx - margin and margin <= z < sz - margin:
                        canvas.put(pos, OAK_LEAVES if rng.random() < 0.8 else AZALEA)


def _exposed_top(canvas: Canvas, pos: Pos) -> bool:
    """An outdoor top: nothing at all above it (interior air is an explicit AIR cell, so rooms stay clean)."""
    above = (pos[0], pos[1] + 1, pos[2])
    return canvas.inside(above) and not canvas.has(above)


def _moss(canvas: Canvas, rng: random.Random, cells, chance: float) -> None:
    for pos, state in cells:
        if pos[1] >= 1 and state != AIR and _is_full(state) and _exposed_top(canvas, pos) and rng.random() < chance:
            canvas.put((pos[0], pos[1] + 1, pos[2]), MOSS_CARPET)


def _vines(canvas: Canvas, rng: random.Random, cells, chance: float, margin: int) -> None:
    sx, _, sz = canvas.size
    for pos, state in cells:
        if pos[1] < 3 or state == AIR or not _is_full(state) or rng.random() >= chance:
            continue
        dx, dz, face = rng.choice(SIDES)
        outside = (pos[0] + dx, pos[1], pos[2] + dz)
        if not canvas.inside(outside) or canvas.has(outside) or _in_margin(outside, sx, sz, margin):
            continue
        for drop in range(rng.randint(1, 4)):
            at = (outside[0], outside[1] - drop, outside[2])
            if at[1] < 1 or canvas.has(at):
                break
            canvas.put(at, block("vine", **{face: "true"}))


def _is_full(state: BlockState) -> bool:
    return not any(part in state.name for part in NOT_FULL)


def _in_margin(pos: Pos, sx: int, sz: int, margin: int) -> bool:
    return pos[0] < margin or pos[2] < margin or pos[0] >= sx - margin or pos[2] >= sz - margin
