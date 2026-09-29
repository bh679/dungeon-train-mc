"""Interiors: what each kind of building was for, left where it stood.

Kits are scattered over a floor's free cells in small clusters; rows (pews, benches) and fills (hay
in a silo) are placed deliberately. No block here carries a block entity, so templates stay free
of NBT and the unattached-block-entity crash can never reach them.
"""

import random
from typing import Callable

from .blocks import (AIR, BARREL, BOOKSHELF, CAULDRON, CHISELED_BOOKSHELF, COBBLESTONE, COBWEB, CRACKED_STONE_BRICKS,
                     CRAFTING_TABLE, DEAD_BUSH, FERN, FLOWER_POT, GRAY_CARPET, HANGING_ROOTS, HAY, LANTERN, LECTERN,
                     LIGHT_GRAY_CARPET, LOOM, MOSS_CARPET, MOSSY_COBBLESTONE, NOTE_BLOCK, OAK_SLAB, OAK_STAIRS, POTTED_DEAD_BUSH,
                     POTTED_FERN, RED_CARPET, RED_WALL_BANNER, SHORT_GRASS, SMITHING_TABLE, SMOOTH_STONE_SLAB, SPRUCE_FENCE,
                     SPRUCE_PLANKS_STAGE, SPRUCE_SLAB, SPRUCE_STAIRS, STEEL_BARS, STEEL_CHAIN, TUFF, WHITE_CARPET, BlockState, block,
                     loot_chest, spawner)
from .canvas import Canvas, Pos
from .shapes import box as fill_box, disc, walls as wall_box

RUBBLE_IN = (COBBLESTONE, MOSSY_COBBLESTONE, CRACKED_STONE_BRICKS, TUFF)
SOLID_NOT = ("slab", "stairs", "pane", "bars", "carpet", "vine", "chain", "ladder", "lantern", "fence", "trapdoor", "door",
             "bush", "grass", "fern", "leaves", "azalea", "roots", "pot", "web", "glass", "lectern", "banner")

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


def kit(canvas: Canvas, seed: int, kind: str, box: Box, ys, density: float = 0.06, ruin: dict | None = None) -> None:
    """Scatter a kit's props over the free floor cells of each floor height in `ys`, clustered, then let the
    rooms go to ruin (`ruin_interior`, with any overrides in `ruin`)."""
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
    ruin_interior(canvas, seed, box, ys, **(ruin or {}))


def _solid(state: BlockState | None) -> bool:
    return state is not None and state != AIR and not any(p in state.name for p in SOLID_NOT)


def _wall_beside(canvas: Canvas, pos: Pos) -> str | None:
    """The side on which a solid wall touches this cell, as the vine property that hangs on it."""
    x, y, z = pos
    for dx, dz, face in ((1, 0, "east"), (-1, 0, "west"), (0, 1, "south"), (0, -1, "north")):
        if _solid(canvas.get((x + dx, y, z + dz))):
            return face
    return None


def ruin_interior(canvas: Canvas, seed: int, box: Box, ys, cobweb: float = 0.10, moss: float = 0.12, rubble: float = 0.03,
                  vines: float = 0.06, roots: float = 0.05, weeds: float = 0.04) -> None:
    """Every room broken in: moss and weeds on the floor, rubble against the walls, cobwebs in the ceiling
    corners, vines down the inside of the walls, roots through the ceiling."""
    rng = random.Random(seed ^ 0x2E1D)
    x0, z0, x1, z1 = box
    for y in ys:
        for z in range(z0, z1 + 1):
            for x in range(x0, x1 + 1):
                _ruin_column(canvas, rng, (x, y, z), cobweb, moss, rubble, vines, roots, weeds)


def _ruin_column(canvas: Canvas, rng: random.Random, pos: Pos, cobweb: float, moss: float, rubble: float, vines: float,
                 roots: float, weeds: float) -> None:
    x, y, z = pos
    if canvas.get(pos) != AIR:
        return
    if _solid(canvas.get((x, y - 1, z))):
        r = rng.random()
        wall = _wall_beside(canvas, pos)
        if r < moss:
            canvas.put(pos, MOSS_CARPET)
        elif r < moss + weeds:
            canvas.put(pos, rng.choice((SHORT_GRASS, FERN, DEAD_BUSH)))
        elif r < moss + weeds + rubble * (3 if wall else 1):
            canvas.put(pos, rng.choice(RUBBLE_IN))
    top = y
    while canvas.get((x, top + 1, z)) == AIR and top - y < 8:
        top += 1
    if not _solid(canvas.get((x, top + 1, z))) or top == y:
        return
    wall = _wall_beside(canvas, (x, top, z))
    r = rng.random()
    if wall and r < cobweb:
        canvas.put((x, top, z), COBWEB)
    elif r < cobweb + roots:
        canvas.put((x, top, z), HANGING_ROOTS)
    elif wall and r < cobweb + roots + vines:
        for yy in range(top, max(y, top - rng.randint(1, 4)) - 1, -1):
            if canvas.get((x, yy, z)) == AIR:
                canvas.put((x, yy, z), block("vine", **{wall: "true"}))


def spawners_and_loot(canvas: Canvas, seed: int, box: Box, ys, mobs=("zombie",), spawners: int = 1, chests: int = 2,
                      tiers=(1, 2)) -> None:
    """Spawners in the corners with cobwebs round them, loot chests against the walls, as the originals do."""
    rng = random.Random(seed ^ 0x100E)
    cells = [c for y in ys for c in floor_cells(canvas, box, y)]
    by_wall = [c for c in cells if _wall_beside(canvas, c)]
    pool = by_wall if len(by_wall) >= spawners + chests else cells
    if not pool:
        return
    rng.shuffle(pool)
    for pos in pool[:spawners]:
        canvas.put(pos, spawner(rng.choice(mobs)))
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            web = (pos[0] + dx, pos[1] + rng.randint(0, 1), pos[2] + dz)
            if _free(canvas, web) and rng.random() < 0.6:
                canvas.put(web, COBWEB)
    for pos in pool[spawners:spawners + chests]:
        if _free(canvas, pos):
            canvas.put(pos, loot_chest(rng.choice(tiers), rng.choice(FACINGS)))


def office_room(canvas: Canvas, seed: int, box: Box, y: int, wall: BlockState, door_side: str = "east") -> None:
    """A walled room three high with a doorway in the middle of `door_side`, a desk, a chair and shelves."""
    x0, z0, x1, z1 = box
    canvas.put_all(wall_box(x0, y, z0, x1, y + 2, z1, wall))
    door = {"east": (x1, (z0 + z1) // 2), "west": (x0, (z0 + z1) // 2), "south": ((x0 + x1) // 2, z1), "north": ((x0 + x1) // 2, z0)}[door_side]
    for dy in (0, 1):
        canvas.put((door[0], y + dy, door[1]), AIR)
    canvas.put_all(fill_box(x0 + 1, y, z0 + 1, x1 - 1, y + 2, z1 - 1, AIR))
    rng = random.Random(seed ^ 0x0FF)
    _desk(canvas, rng, (x0 + 2, y, z0 + 2))
    for z in range(z0 + 1, z1):
        if z != door[1] and rng.random() < 0.6 and _free(canvas, (x0 + 1, y, z)):
            canvas.put((x0 + 1, y, z), BOOKSHELF)


def shelf_rows(canvas: Canvas, seed: int, xs, z0: int, z1: int, y: int) -> None:
    """Shop shelving in rows along z: bookshelf bases with chiselled shelves and upturned stairs above."""
    rng = random.Random(seed ^ 0x5E1F)
    for x in xs:
        for z in range(z0, z1 + 1):
            base = (x, y, z)
            if not _free(canvas, base):
                continue
            canvas.put(base, BOOKSHELF if rng.random() < 0.5 else CHISELED_BOOKSHELF.with_props(facing=rng.choice(("east", "west"))))
            top = (x, y + 1, z)
            if _free(canvas, top):
                r = rng.random()
                if r < 0.4:
                    canvas.put(top, OAK_STAIRS.with_props(facing=rng.choice(("east", "west")), half="top"))
                elif r < 0.7:
                    canvas.put(top, CHISELED_BOOKSHELF.with_props(facing=rng.choice(("east", "west"))))
                elif r < 0.85:
                    canvas.put(top, COBWEB)


def stage(canvas: Canvas, box: Box, y: int, facing: str = "south") -> None:
    """A raised platform with steps along its front (the side that faces `facing`'s opposite), a lectern at its
    centre, a red runner up to it, floor lamps at the front corners and potted plants at the back ones, with
    banners on the wall behind and a barrel and plant on the floor at either side."""
    x0, z0, x1, z1 = box
    front, back = (z0 - 1, z1 + 1) if facing == "south" else (z1 + 1, z0 - 1)
    canvas.put_all(fill_box(x0, y, z0, x1, y, z1, SPRUCE_PLANKS_STAGE))
    for x in range(x0 + 1, x1):
        canvas.put((x, y, front), SPRUCE_STAIRS.with_props(facing=facing))
    mid = (x0 + x1) // 2
    for z in range(z0, z1 + 1):
        canvas.put((mid, y + 1, z), RED_CARPET)
    canvas.put((mid, y + 1, (z0 + z1) // 2), LECTERN.with_props(facing=facing))
    _stage_flanks(canvas, box, y, front, back)


def _stage_flanks(canvas: Canvas, box: Box, y: int, front: int, back: int) -> None:
    x0, z0, x1, z1 = box
    front_z, back_z = (z0, z1) if front < back else (z1, z0)
    for x in (x0, x1):
        canvas.put((x, y + 1, front_z), SPRUCE_FENCE)
        canvas.put((x, y + 2, front_z), LANTERN)
        canvas.put((x, y + 1, back_z), POTTED_FERN if x == x0 else POTTED_DEAD_BUSH)
    banner_facing = "north" if front < back else "south"
    for x in range(x0 + 2, x1 - 1, 4):
        if canvas.get((x, y + 3, back + 1)) not in (None, AIR):
            canvas.put((x, y + 3, back), RED_WALL_BANNER.with_props(facing=banner_facing))
    for x, plant in ((x0 - 1, POTTED_DEAD_BUSH), (x1 + 1, POTTED_FERN)):
        canvas.put((x, y, back_z), BARREL.with_props(facing="up"))
        canvas.put((x, y, back_z - 2 if front < back else back_z + 2), plant)


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
