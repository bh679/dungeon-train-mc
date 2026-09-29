"""Snapped tower: a ten-floor office block that leant east until its top half tore away. Five floors
still stand, leaning, with rebar bristling from the ragged break. The upper half hangs hooked over the
stub's east lip, lying down the slope of its own fall, and the far end is smashed flat on the ground."""

import random

from ..blocks import (AIR, CONCRETE, CONCRETE_DARK, CONCRETE_WHITE, CRACKED_STONE_BRICKS, GLASS, STEEL_BARS, STEEL_DARK, block,
                      loot_chest)
from .. import furnish
from ..canvas import Canvas, Cells, Pos, envelope_air
from ..damage import LOOSE, ROOF_MASS, RUBBLE, rubble_field
from ..floors import Facade, roof_plate, tower
from ..shapes import box, walls
from ..spec import Archetype, ArchetypeSpec
from .common import finish, footprint_pad

SPEC = ArchetypeSpec(name="snapped_tower", size=(65, 42, 35), floor_period=None, bay_period_x=None, bay_period_z=None,
                     margin=4, seed=0x5A99, weight=4)

X0, Z0, X1, Z1 = 4, 10, 18, 24         # 15 wide, centred so the rubble can spill six blocks either side
PERIOD, FLOORS, BAY = 5, 10, 4
STUB_FLOORS = 5
BREAK = 1 + STUB_FLOORS * PERIOD        # 26: the plate it tore along
DRIFT = 6                               # layers per block of lean
CORE = (9, 15, 13, 19)
START = X1 + (BREAK - 1) // DRIFT - 2   # the fallen piece hooks three blocks over the stub's east lip
NEAR_LIFT = BREAK - 4                   # how high the hanging end sits
SMASH_FROM = 14                         # from here along the piece it is pancaked
WIDTH = X1 - X0 + 1


def lean(y: int) -> int:
    return (y - 1) // DRIFT


def tear(x: int, z: int) -> int:
    return BREAK + ((x * 5 + z * 7) % 4) - 2


def lift(px: int) -> int:
    return max(0, NEAR_LIFT - (px - START))


def draw(canvas: Canvas) -> None:
    footprint_pad(canvas, SPEC, X0, Z0, START + FLOORS * PERIOD - BREAK + 4, Z1)
    upright = _upright()
    canvas.put_all({(x + lean(y), y, z): s for (x, y, z), s in upright.items() if y <= tear(x, z)})
    canvas.put_all(_fallen({p: s for p, s in upright.items() if p[1] > BREAK}))
    _rebar(canvas)
    _smashed_end(canvas)
    end = START + FLOORS * PERIOD - BREAK + 4
    m = SPEC.margin
    rubble_field(canvas, SPEC.seed, (X1 - 2, Z0 + 2, START + SMASH_FROM - 1, Z1 - 2), 2, 8, m)   # under the break and the hanging half
    rubble_field(canvas, SPEC.seed + 1, (START + SMASH_FROM - 2, Z0 + 2, end + 1, Z1 - 2), 4, 9, m)   # where it came down, fading out past the end
    rubble_field(canvas, SPEC.seed + 2, (X0 + 1, Z0 + 1, X1 + 1, Z1 - 1), 1, 6, m)               # round the stub's foot
    for (px, pz, _) in TUMBLED:
        rubble_field(canvas, SPEC.seed + px, (px, pz, px + 3, pz + 3), 2, 3, m)                 # a low mound each piece is stuck in
    for f in range(STUB_FLOORS):
        y = 2 + f * PERIOD
        dx = lean(y)
        inside = (X0 + 1 + dx, Z0 + 1, X1 - 1 + dx, Z1 - 1)
        envelope_air(canvas, inside[0], y, inside[1], inside[2], min(y + PERIOD - 2, BREAK - 3), inside[3])
        furnish.kit(canvas, SPEC.seed + f, "office", inside, [y], 0.07, ruin={"rubble": 0.05, "moss": 0.2, "cobweb": 0.14})
        if f in (1, 3):
            furnish.spawners_and_loot(canvas, SPEC.seed + f, inside, [y], mobs=("zombie", "skeleton"), spawners=1, chests=1, tiers=(1, 2))
    finish(canvas, SPEC, vine_chance=0.4, vine_drop=(4, 12), moss_chance=0.28, roots_chance=0.15, leaf_clumps=36, gardens=8)
    _dangling_chunks(canvas)
    _prune_unrooted_bars(canvas)
    _tumbled_sections(canvas)
    _loot_in_the_wreck(canvas)


END = START + FLOORS * PERIOD - BREAK + 4
TUMBLED = ((END - 3, Z0 - 4, "lean"), (END + 1, Z0 + 2, "corner"), (END + 4, Z0 - 3, "flat"), (END + 2, Z0 + 9, "lean"),
           (END + 6, Z0 + 5, "corner"), (END + 4, Z0 + 13, "flat"), (END - 1, Z1 - 1, "lean"))   # all clear of the margin
PIER, SPANDREL, PLATE = block("tuff"), block("polished_tuff"), block("andesite")


def _tumbled_sections(canvas: Canvas) -> None:
    """Pieces of the building that slid on past the wreck and dug in, standing proud of the rubble: bays of
    facade leaning at forty-five degrees, corners of two walls with a plate between, and floor plates
    lying flat with a stub of wall on one edge. Placed after the finish, in the mix's own blocks, so the
    gravity pass leaves them be."""
    rng = random.Random(SPEC.seed ^ 0x7B1D)
    makers = {"lean": _leaning_slice, "flat": _flat_slice, "corner": _corner_piece}
    for (px, pz, how) in TUMBLED:
        for pos, state in makers[how](px, pz, rng).items():
            if canvas.inside(pos) and SPEC.margin <= pos[0] < SPEC.size[0] - SPEC.margin and SPEC.margin <= pos[2] < SPEC.size[2] - SPEC.margin:
                canvas.put(pos, state)


def _corner_piece(px: int, pz: int, rng: random.Random) -> dict:
    """Two walls meeting at a pier, four courses high, a floor plate through them one course up, tipped
    so the whole thing sits a block lower along one edge."""
    out = {}
    for layer in range(5):
        for k in range(5):
            wall = _wall_cell(k, layer, rng)
            if wall is not None:
                out[(px + k, 2 + layer, pz)] = wall               # wall along x
                out[(px, 2 + layer, pz + k)] = wall               # wall along z
    for i in range(1, 5):
        for k in range(1, 5):
            out[(px + i, 3, pz + k)] = PLATE
    out[(px, 2, pz)] = PIER
    return out


def _wall_cell(k: int, layer: int, rng: random.Random):
    """One cell of a bay: piers at the ends, a spandrel course at the bottom, glass (half of it gone) above."""
    if k % 4 == 0:
        return PIER
    if layer == 0:
        return SPANDREL
    return GLASS if rng.random() < 0.5 else None


def _leaning_slice(px: int, pz: int, rng: random.Random) -> dict:
    """Seven wide along z, six tall, sheared one block east per layer: dug in at forty-five degrees."""
    out = {}
    for layer in range(6):
        for k in range(7):
            state = _wall_cell(k, layer, rng)
            if state is not None:
                out[(px + layer, 2 + layer, pz + k)] = state
    return out


def _flat_slice(px: int, pz: int, rng: random.Random) -> dict:
    """A floor plate lying on the mound with a stub of wall two courses high along one edge."""
    out = {(px + i, 2, pz + k): PLATE if (i + k) % 3 else PIER for i in range(6) for k in range(6)}
    for layer in range(3):
        for k in range(6):
            state = _wall_cell(k, layer, rng)
            if state is not None:
                out[(px, 3 + layer, pz + k)] = state
    return out


def _prune_unrooted_bars(canvas: Canvas) -> None:
    """Rebar the damage pass cut loose from its concrete goes; a bar must touch the building or another bar."""
    around = ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1))
    pruned = True
    while pruned:
        pruned = False
        for (x, y, z), state in list(canvas.freeze().items()):
            if state.name != "minecraft:iron_bars" or y < BREAK - 3:
                continue
            rooted = any(_is_fabric(canvas.get((x + a, y + b, z + c))) or
                         (canvas.get((x + a, y + b, z + c)) or AIR).name == "minecraft:iron_bars" for a, b, c in around)
            if not rooted:
                canvas.clear((x, y, z))
                pruned = True


CHUNK = (block("tuff"), block("andesite"), CRACKED_STONE_BRICKS, block("polished_tuff"), block("cobbled_deepslate"))


def _dangling_chunks(canvas: Canvas) -> None:
    """Lumps of concrete still hanging by their rebar: from the underside of the hanging half and from the
    stub's torn lip, a bar one to three long with a clump of two to five blocks swinging at its end. Placed
    after the finish so the gravity pass never mistakes them for loose rubble."""
    rng = random.Random(SPEC.seed ^ 0xDA96)
    anchors = [pos for pos, state in canvas.freeze().items() if _is_fabric(state) and _hangs_free(canvas, pos)]
    rng.shuffle(anchors)
    for (x, y, z) in anchors[:14]:
        length = rng.randint(1, 3)
        if y - length - 2 < 2:
            continue
        for i in range(1, length + 1):
            canvas.put((x, y - i, z), STEEL_BARS)
        seed_pos = (x, y - length - 1, z)
        canvas.put(seed_pos, rng.choice(CHUNK))
        clump = [seed_pos]
        for _ in range(rng.randint(1, 4)):                      # each new block grows off one already there
            bx, by, bz = rng.choice(clump)
            dx, dy, dz = rng.choice(((1, 0, 0), (-1, 0, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)))
            pos = (bx + dx, by + dy, bz + dz)
            if canvas.inside(pos) and canvas.get(pos) in (None, AIR) and SPEC.margin <= pos[2] < SPEC.size[2] - SPEC.margin and pos[1] >= 2:
                canvas.put(pos, rng.choice(CHUNK))
                clump.append(pos)


def _is_fabric(state) -> bool:
    """Concrete, brick, glass — the building itself; never vines, leaves, roots, bars or the rubble."""
    return state not in (None, AIR) and state.name not in LOOSE and not any(p in state.name for p in ROOF_MASS)


def _hangs_free(canvas: Canvas, pos: Pos) -> bool:
    """A solid block on the underside of the hanging half or the stub's lip, with a long drop below it."""
    x, y, z = pos
    if not (START - 3 <= x < START + SMASH_FROM) or y < 6 or not (Z0 <= z <= Z1):
        return False
    return all(canvas.get((x, y - d, z)) in (None, AIR) for d in range(1, 5))


def _upright() -> Cells:
    facade = Facade(pier=CONCRETE, window=GLASS, spandrel=CONCRETE_WHITE, spandrel_layers=1)
    cells = dict(tower(X0, Z0, X1, Z1, 1, FLOORS, PERIOD, BAY, CONCRETE, facade))
    roof = 1 + FLOORS * PERIOD
    cells.update(walls(CORE[0], 1, CORE[1], CORE[2], roof - 1, CORE[3], CONCRETE_DARK))
    cells.update(roof_plate(X0, Z0, X1, Z1, roof, CONCRETE))
    cells.update(walls(X0, roof + 1, Z0, X1, roof + 1, Z1, STEEL_BARS))
    cells.update(box(9, roof + 1, 9, 13, roof + 3, 13, STEEL_DARK))
    return cells


def _fallen(cells: Cells) -> Cells:
    """The top half a quarter turn onto its east face, then dropped along its length: hanging at the
    stub end, on the ground at the far end."""
    out = {}
    for (x, y, z), state in cells.items():
        px = START + (y - BREAK - 1)
        out[(px, 1 + (X1 - x) + lift(px), z)] = state
    return out


def _rebar(canvas: Canvas) -> None:
    """Reinforcing bars left standing where the concrete tore away, on every other pier."""
    for z in range(Z0, Z1 + 1):
        for x in range(X0, X1 + 1):
            on_wall = x in (X0, X1) or z in (Z0, Z1)
            on_pier = (x - X0) % BAY == 0 or (z - Z0) % BAY == 0
            if not (on_wall and on_pier) or (x + z) % 2:
                continue
            top = tear(x, z)
            for y in range(top + 1, top + 2 + (x * z) % 3):
                canvas.put((x + lean(top), y, z), STEEL_BARS)        # one offset for the whole bar, so it stays rooted
            canvas.put((x + lean(top), top, z), CRACKED_STONE_BRICKS)


def _smashed_end(canvas: Canvas) -> None:
    """The far end hit the ground: everything above a ragged few blocks is gone, rubble strewn on top."""
    for px in range(START + SMASH_FROM, START + FLOORS * PERIOD - BREAK + 5):
        for z in range(Z0 - 1, Z1 + 2):
            cap = lift(px) + 3 + ((px * 3 + z * 5) % 4)
            for y in range(cap + 1, WIDTH + NEAR_LIFT + 4):
                canvas.clear((px, y, z))
            if (px + z) % 2 == 0 and canvas.has((px, cap, z)):
                canvas.put((px, cap + 1, z), RUBBLE[(px * z) % len(RUBBLE)])


def _loot_in_the_wreck(canvas: Canvas) -> None:
    """A chest under the smashed end, on the first solid block up its middle."""
    px, z = START + SMASH_FROM + 4, (Z0 + Z1) // 2
    for y in range(1, WIDTH + NEAR_LIFT):
        if canvas.get((px, y - 1, z)) not in (None, AIR) and canvas.get((px, y, z)) in (None, AIR):
            canvas.put((px, y, z), loot_chest(2, "west"))
            return


ARCHETYPE = Archetype(SPEC, draw)
