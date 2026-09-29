"""Light baked weathering: a few cracked blocks, missing windows, moss on ledges, vines down walls.

Deliberately light. The heavy ruin — bites, truncation, decay, overgrowth stripping — is rolled per
placement by the processors, so the shipped template only needs to stop reading as brand new. All
rolls come from one seeded RNG so a re-run is byte-identical.
"""

import random

from .blocks import AIR, MOSS_CARPET, BlockState, block
from .canvas import Canvas, Pos

SIDES = ((0, 1, "south"), (0, -1, "north"), (1, 0, "west"), (-1, 0, "east"))
NOT_FULL = ("slab", "stairs", "pane", "bars", "wall", "carpet", "vine", "chain", "ladder", "lantern", "button",
            "trapdoor", "rail", "bush", "grass", "leaves", "azalea", "bell", "cauldron", "hopper", "door", "rod")


def weather(canvas: Canvas, seed: int, cracked: dict[BlockState, BlockState], crack_chance: float = 0.06,
            moss_chance: float = 0.05, vine_chance: float = 0.02, min_y: int = 1, margin: int = 0) -> None:
    rng = random.Random(seed)
    cells = list(canvas.freeze().items())
    for pos, state in cells:
        if pos[1] >= min_y and state in cracked and rng.random() < crack_chance:
            canvas.put(pos, cracked[state])
    _moss(canvas, rng, cells, moss_chance)
    _vines(canvas, rng, cells, vine_chance, margin)


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
