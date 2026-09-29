"""Nothing in a template may fall when it is placed.

Vanilla updates every placed block's neighbours after a template stamp, so gravel, sand, concrete
powder or an anvil with nothing beneath drops out of the building on the first tick. The pass here
swaps any such block that lacks a solid cell directly under it for a stable stand-in of the same
colour, and is the last step of every archetype's finish.
"""

from .blocks import AIR, GRAVITY, GRAVITY_SUFFIX, BlockState, block
from .canvas import Canvas

STABLE = {
    "minecraft:gravel": block("cobblestone"),
    "minecraft:sand": block("sandstone"),
    "minecraft:red_sand": block("red_sandstone"),
    "minecraft:suspicious_sand": block("sandstone"),
    "minecraft:suspicious_gravel": block("cobblestone"),
    "minecraft:anvil": block("smithing_table"),
    "minecraft:chipped_anvil": block("smithing_table"),
    "minecraft:damaged_anvil": block("smithing_table"),
    "minecraft:scaffolding": block("oak_fence"),
}
UNSTABLE_BELOW = ("slab", "stairs", "pane", "bars", "wall", "carpet", "vine", "chain", "ladder", "lantern", "fence",
                  "trapdoor", "rail", "bush", "grass", "fern", "leaves", "azalea", "roots", "door", "rod", "flower_pot",
                  "torch", "button", "candle", "cobweb", "potted", "lectern", "banner")


def falls(state: BlockState) -> bool:
    return state.name in GRAVITY or state.name.endswith(GRAVITY_SUFFIX)


def stable_for(state: BlockState) -> BlockState:
    if state.name.endswith(GRAVITY_SUFFIX):
        return block(state.name.removeprefix("minecraft:").removesuffix(GRAVITY_SUFFIX) + "_terracotta")
    return STABLE.get(state.name, block("cobblestone"))


def supported(canvas: Canvas, pos) -> bool:
    below = canvas.get((pos[0], pos[1] - 1, pos[2]))
    return below is not None and below != AIR and not any(p in below.name for p in UNSTABLE_BELOW) and not falls(below)


FLOOR_ONLY = ("carpet", "flower_pot", "potted_", "torch", "candle", "pressure_plate", "rail", "snow")
THROUGH = ("vine", "grass", "fern", "bush", "web", "roots", "leaves", "azalea", "carpet")


def settle(canvas: Canvas) -> None:
    """Replace every falling block above the pad that has no solid cell beneath it, and bring every
    carpet, pot, torch and the like down onto the first block under it (or drop it altogether when
    there is nothing to sit on within reach) — the damage and drop passes take floors from under them."""
    for pos, state in list(canvas.freeze().items()):
        if pos[1] >= 1 and falls(state) and not supported(canvas, pos):
            canvas.put(pos, stable_for(state))
    for pos, state in list(canvas.freeze().items()):
        if pos[1] >= 1 and any(p in state.name for p in FLOOR_ONLY) and not supported(canvas, pos):
            _lower(canvas, pos, state)


def _lower(canvas: Canvas, pos, state: BlockState) -> None:
    x, y, z = pos
    landing = y
    while landing > 1 and _through(canvas.get((x, landing - 1, z))):
        landing -= 1
    indoors = canvas.get((x + 1, y, z)) == AIR or canvas.get((x - 1, y, z)) == AIR
    if indoors:
        canvas.put(pos, AIR)
    else:
        canvas.clear(pos)
    if landing != y and supported(canvas, (x, landing, z)):
        canvas.put((x, landing, z), state)


def _through(state: BlockState | None) -> bool:
    return state is None or state == AIR or any(p in state.name for p in THROUGH)
