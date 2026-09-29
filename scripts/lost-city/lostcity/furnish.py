"""Interiors: what each kind of building was for, left where it stood.

Kits are scattered over a floor's free cells in small clusters; rows (pews, benches) and fills (hay
in a silo) are placed deliberately. No block here carries a block entity, so templates stay free
of NBT and the unattached-block-entity crash can never reach them.
"""

import random
from typing import Callable

from .blocks import (AIR, BOOKSHELF, CAULDRON, COBWEB, CRAFTING_TABLE, DEAD_BUSH, FLOWER_POT, GRAY_CARPET, HAY, LANTERN,
                     LIGHT_GRAY_CARPET, LOOM, NOTE_BLOCK, OAK_SLAB, OAK_STAIRS, RED_CARPET, SMITHING_TABLE, SMOOTH_STONE_SLAB,
                     SPRUCE_SLAB, SPRUCE_STAIRS, STEEL_BARS, STEEL_CHAIN, WHITE_CARPET, BlockState)
from .canvas import Canvas, Pos
from .shapes import disc

Box = tuple[int, int, int, int]   # x0, z0, x1, z1
Prop = Callable[[Canvas, random.Random, Pos], None]
FACINGS = ("north", "south", "east", "west")


def _single(state: BlockState) -> Prop:
    return lambda canvas, rng, pos: canvas.put(pos, state)


def _facing(state: BlockState) -> Prop:
    return lambda canvas, rng, pos: canvas.put(pos, state.with_props(facing=rng.choice(FACINGS)))


def _desk(canvas: Canvas, rng: random.Random, pos: Pos) -> None:
    x, y, z = pos
    canvas.put(pos, SPRUCE_SLAB.with_props(type="top"))
    side = rng.choice(((1, 0), (-1, 0), (0, 1), (0, -1)))
    chair = (x + side[0], y, z + side[1])
    if _free(canvas, chair):
        canvas.put(chair, SPRUCE_STAIRS.with_props(facing=rng.choice(FACINGS)))


def _sofa(canvas: Canvas, rng: random.Random, pos: Pos) -> None:
    x, y, z = pos
    facing = rng.choice(FACINGS)
    along = (1, 0) if facing in ("north", "south") else (0, 1)
    for i in range(2):
        cell = (x + along[0] * i, y, z + along[1] * i)
        if _free(canvas, cell):
            canvas.put(cell, OAK_STAIRS.with_props(facing=facing))


def _bed(canvas: Canvas, rng: random.Random, pos: Pos) -> None:
    """A low slab frame with a carpet blanket — a bed without a block entity."""
    x, y, z = pos
    along = rng.choice(((1, 0), (0, 1)))
    blanket = rng.choice((WHITE_CARPET, LIGHT_GRAY_CARPET, GRAY_CARPET))
    for i in range(2):
        cell = (x + along[0] * i, y, z + along[1] * i)
        if _free(canvas, cell):
            canvas.put(cell, SMOOTH_STONE_SLAB)
            top = (cell[0], y + 1, cell[2])
            if _free(canvas, top) and i == 1:
                canvas.put(top, blanket)


def _shelf(canvas: Canvas, rng: random.Random, pos: Pos) -> None:
    x, y, z = pos
    canvas.put(pos, BOOKSHELF)
    if rng.random() < 0.6 and _free(canvas, (x, y + 1, z)):
        canvas.put((x, y + 1, z), BOOKSHELF)


def _partition(canvas: Canvas, rng: random.Random, pos: Pos) -> None:
    x, y, z = pos
    along = rng.choice(((1, 0), (0, 1)))
    for i in range(rng.randint(2, 3)):
        cell = (x + along[0] * i, y, z + along[1] * i)
        if _free(canvas, cell):
            canvas.put(cell, STEEL_BARS)
            if _free(canvas, (cell[0], y + 1, cell[2])):
                canvas.put((cell[0], y + 1, cell[2]), STEEL_BARS)


def _shop_shelf(canvas: Canvas, rng: random.Random, pos: Pos) -> None:
    x, y, z = pos
    canvas.put(pos, OAK_SLAB.with_props(type="top"))
    above = (x, y + 1, z)
    if _free(canvas, above) and rng.random() < 0.5:
        canvas.put(above, rng.choice((FLOWER_POT, DEAD_BUSH, COBWEB)))


def _lamp(canvas: Canvas, rng: random.Random, pos: Pos) -> None:
    """A chain and lantern hung from the ceiling over this cell, when there is a ceiling."""
    x, y, z = pos
    top = y
    while canvas.get((x, top + 1, z)) == AIR and top - y < 6:
        top += 1
    ceiling = canvas.get((x, top + 1, z))
    if ceiling is None or ceiling == AIR or top - y < 1:
        return
    canvas.put((x, top, z), STEEL_CHAIN)
    canvas.put((x, top - 1, z), LANTERN.with_props(hanging="true"))


KITS: dict[str, list[tuple[float, Prop]]] = {
    "office": [(5, _desk), (2, _shelf), (3, _single(COBWEB)), (1, _single(CRAFTING_TABLE)), (1, _single(FLOWER_POT)), (1, _lamp)],
    "apartment": [(3, _sofa), (3, _single(OAK_SLAB)), (2, _bed), (2, _shelf), (1, _single(CRAFTING_TABLE)), (1, _single(CAULDRON)),
                  (2, _single(RED_CARPET)), (2, _single(FLOWER_POT)), (2, _single(COBWEB)), (1, _single(NOTE_BLOCK)), (1, _lamp)],
    "hotel": [(4, _bed), (3, _single(RED_CARPET)), (2, _facing(OAK_STAIRS)), (1, _shelf), (1, _single(LOOM)), (1, _single(CAULDRON)),
              (2, _single(COBWEB)), (2, _lamp)],
    "hospital": [(4, _bed), (2, _partition), (2, _single(CAULDRON)), (1, _single(FLOWER_POT)), (2, _single(COBWEB)),
                 (1, _single(WHITE_CARPET)), (1, _lamp)],
    "shop": [(4, _shop_shelf), (1, _single(HAY)), (2, _single(FLOWER_POT)), (2, _single(GRAY_CARPET)), (3, _single(COBWEB)),
             (1, _single(LOOM)), (1, _single(SMITHING_TABLE)), (1, _single(CRAFTING_TABLE)), (1, _single(DEAD_BUSH))],
    "kiosk": [(4, _shop_shelf), (1, _single(CRAFTING_TABLE)), (1, _single(CAULDRON)), (2, _single(COBWEB))],
    "hut": [(2, _single(CRAFTING_TABLE)), (2, _single(COBWEB)), (1, _single(NOTE_BLOCK)), (1, _lamp)],
    "hall": [(2, _single(RED_CARPET)), (2, _single(COBWEB)), (1, _lamp), (1, _single(FLOWER_POT))],
    "station": [(2, _single(COBWEB)), (1, _single(OAK_SLAB)), (1, _single(DEAD_BUSH)), (1, _lamp)],
}


def _free(canvas: Canvas, pos: Pos) -> bool:
    return canvas.inside(pos) and canvas.get(pos) == AIR


def floor_cells(canvas: Canvas, box: Box, y: int) -> list[Pos]:
    """Cells at height y inside the box standing on something solid with air above."""
    x0, z0, x1, z1 = box
    out = []
    for z in range(z0, z1 + 1):
        for x in range(x0, x1 + 1):
            below = canvas.get((x, y - 1, z))
            if below is None or below == AIR or "slab" in below.name or "stairs" in below.name:
                continue
            if canvas.get((x, y, z)) == AIR and canvas.get((x, y + 1, z)) == AIR:
                out.append((x, y, z))
    return out


def kit(canvas: Canvas, seed: int, kind: str, box: Box, ys, density: float = 0.06) -> None:
    """Scatter a kit's props over the free floor cells of each floor height in `ys`, clustered."""
    rng = random.Random(seed ^ (sum(map(ord, kind)) & 0xFFFF))
    props = KITS[kind]
    weights = [w for w, _ in props]
    for y in ys:
        for cx, cy, cz in floor_cells(canvas, box, y):
            if rng.random() >= density:
                continue
            for _ in range(rng.randint(1, 3)):
                pos = (cx + rng.randint(-1, 1), cy, cz + rng.randint(-1, 1))
                if _free(canvas, pos) and canvas.get((pos[0], cy - 1, pos[2])) not in (None, AIR):
                    rng.choices(props, weights)[0][1](canvas, rng, pos)


def rows(canvas: Canvas, box: Box, y: int, facing: str, every: int, state: BlockState = OAK_STAIRS) -> None:
    """Rows of seats across the box every `every` blocks along z, an aisle down the middle."""
    x0, z0, x1, z1 = box
    aisle = (x0 + x1) // 2
    for z in range(z0 + 1, z1, every):
        for x in range(x0, x1 + 1):
            if abs(x - aisle) <= 1:
                continue
            pos = (x, y, z)
            if _free(canvas, pos) and canvas.get((x, y - 1, z)) not in (None, AIR):
                canvas.put(pos, state.with_props(facing=facing))


def benches(canvas: Canvas, seed: int, cells: list[Pos], facing: str, chance: float = 0.5) -> None:
    rng = random.Random(seed ^ 0xBE4C)
    for pos in cells:
        if rng.random() < chance and _free(canvas, pos):
            canvas.put(pos, SPRUCE_STAIRS.with_props(facing=facing))
            nxt = (pos[0], pos[1], pos[2] + 1) if facing in ("east", "west") else (pos[0] + 1, pos[1], pos[2])
            if _free(canvas, nxt):
                canvas.put(nxt, SPRUCE_STAIRS.with_props(facing=facing))


def hay_fill(canvas: Canvas, seed: int, cx: float, cz: float, r: float, y0: int, height: int) -> None:
    """A settled heap of hay in the bottom of a silo."""
    rng = random.Random(seed ^ 0x4A7)
    for y in range(y0, y0 + height):
        shrink = (y - y0) * 0.8 + rng.random() * 0.6
        for pos in disc(cx, cz, y, max(0.5, r - shrink), HAY):
            if canvas.inside(pos) and canvas.get(pos) in (None, AIR):
                canvas.put(pos, HAY.with_props(axis=rng.choice(("x", "y", "z"))))
