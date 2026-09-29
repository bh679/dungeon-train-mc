"""Steps every archetype shares: the pad under its footprint and the finishing weathering pass."""

from ..blocks import (AIR, CONCRETE, CONCRETE_POWDER, CRACKED_STONE_BRICKS, GLASS, GLASS_CLEAR, STONE_BRICKS,
                      BlockState)
from ..canvas import Canvas, envelope_air
from ..damage import collapsed_corner, holes
from ..materials import texturize
from ..pad import apron_around, pad
from ..spec import ArchetypeSpec
from ..weather import weather

CRACKED_DEFAULT: dict[BlockState, BlockState] = {
    STONE_BRICKS: CRACKED_STONE_BRICKS,
    CONCRETE: CONCRETE_POWDER,
    GLASS: AIR,
    GLASS_CLEAR: AIR,
}


def footprint_pad(canvas: Canvas, spec: ArchetypeSpec, x0: int, z0: int, x1: int, z1: int, apron: int = 3,
                  paving=None) -> None:
    """Natural ground across the template, paving `apron` blocks out from the footprint (x0..x1, z0..z1)."""
    rect = apron_around(x0, z0, x1, z1, apron)
    sx, _, sz = spec.size
    clipped = (max(0, rect[0]), max(0, rect[1]), min(sx - 1, rect[2]), min(sz - 1, rect[3]))
    kwargs = {"paving": paving} if paving else {}
    canvas.put_all(pad(sx, sz, clipped, spec.seed, **kwargs))


def finish(canvas: Canvas, spec: ArchetypeSpec, envelope: tuple[int, int, int, int, int, int] | None = None,
           cracked: dict[BlockState, BlockState] | None = None, **weather_kwargs) -> None:
    """Explicit air inside the building envelope (x0, y0, z0, x1, y1, z1); then — once per canvas — the
    material mix, the baked damage (wall holes, a sheared corner on anything with a repeat), and the
    overgrowth pass. Archetypes that call this per part get the once-only steps on the first call."""
    if envelope is not None:
        envelope_air(canvas, *envelope)
    if not getattr(canvas, "_finished", False):
        canvas._finished = True
        texturize(canvas, spec.seed)
        _damage(canvas, spec)
    weather(canvas, spec.seed, cracked if cracked is not None else CRACKED_DEFAULT, margin=spec.margin, **weather_kwargs)


def _damage(canvas: Canvas, spec: ArchetypeSpec) -> None:
    cells = len(canvas)
    holes(canvas, spec.seed, count=(min(8, 2 + cells // 6000), min(10, 4 + cells // 3000)), margin=spec.margin)
    if spec.floor_period or spec.bay_period_x or spec.bay_period_z:
        top, box = _main_roof(canvas)
        if top is not None and top > 8:
            collapsed_corner(canvas, spec.seed, box, top, depth=max(4, top // 6), margin=spec.margin)


def _main_roof(canvas: Canvas):
    """The highest layer still carrying a quarter of the heaviest layer's mass, and its footprint."""
    layers: dict[int, list] = {}
    for (x, y, z), state in canvas.freeze().items():
        if y >= 1 and state != AIR and not any(p in state.name for p in ("slab", "bars", "wall", "pane", "carpet", "vine")):
            layers.setdefault(y, []).append((x, z))
    if not layers:
        return None, None
    largest = max(len(v) for v in layers.values())
    top = max(y for y, v in layers.items() if len(v) >= largest * 0.25)
    xs = [x for x, _ in layers[top]]
    zs = [z for _, z in layers[top]]
    return top, (min(xs), min(zs), max(xs), max(zs))
