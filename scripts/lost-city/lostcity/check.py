"""Validation every rendered template must pass before it is written.

These mirror what LostCityTemplatesTest asserts on the committed files, so a broken recipe fails
here, at generation time, with a message naming the archetype.
"""

from .blocks import AIR, KNOWN, PAD_NATURAL, VALUABLE
from .canvas import Cells
from .spec import ArchetypeSpec

MAX_FOOTPRINT = 128   # chunk-reference radius under a 180° rotation, including stretch growth
MAX_HEIGHT = 120


def validate(cells: Cells, spec: ArchetypeSpec) -> list[str]:
    errors: list[str] = []
    sx, sy, sz = spec.size
    if sx > MAX_FOOTPRINT or sz > MAX_FOOTPRINT or sy > MAX_HEIGHT:
        errors.append(f"size {spec.size} exceeds {MAX_FOOTPRINT}×{MAX_HEIGHT}×{MAX_FOOTPRINT}")
    errors += _unknown_blocks(cells)
    errors += _pad(cells, sx, sz)
    errors += _margin(cells, spec)
    top = max(pos[1] for pos in cells)
    if top < sy - 4:
        errors.append(f"top {sy - 1 - top} layers are empty: size.y should be tight to the roof")
    errors += _gravity(cells)
    errors += _valuables(cells)
    return [f"{spec.name}: {e}" for e in errors]


def _gravity(cells: Cells) -> list[str]:
    from .canvas import Canvas
    from .gravity import falls, supported
    view = Canvas((10_000, 10_000, 10_000))
    view._cells = dict(cells)
    bad = [pos for pos, s in cells.items() if pos[1] >= 1 and falls(s) and not supported(view, pos)]
    return [f"{len(bad)} falling blocks without support, e.g. {bad[0]}"] if bad else []


def _valuables(cells: Cells) -> list[str]:
    names = {s.name for s in cells.values() if s.name in VALUABLE}
    return [f"valuable blocks used: {', '.join(sorted(names))}"] if names else []


def _unknown_blocks(cells: Cells) -> list[str]:
    from .materials import MIX_BLOCKS
    names = {state.name for state in cells.values()} - KNOWN - MIX_BLOCKS
    return [f"unknown block {n}" for n in sorted(names)]


def _pad(cells: Cells, sx: int, sz: int) -> list[str]:
    missing = sum(1 for z in range(sz) for x in range(sx) if (x, 0, z) not in cells)
    errors = [f"{missing} pad cells missing at y=0"] if missing else []
    natural = sum(1 for (x, y, z), s in cells.items() if y == 0 and s.name in PAD_NATURAL)
    if natural == 0:
        errors.append("no natural ground on the pad")
    return errors


def _margin(cells: Cells, spec: ArchetypeSpec) -> list[str]:
    sx, _, sz = spec.size
    m = spec.margin
    bad = [pos for pos, s in cells.items()
           if pos[1] >= 1 and s != AIR and (pos[0] < m or pos[2] < m or pos[0] >= sx - m or pos[2] >= sz - m)]
    return [f"{len(bad)} blocks inside the {m}-block margin, e.g. {bad[0]}"] if bad else []
