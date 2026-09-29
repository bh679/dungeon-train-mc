"""A voxel canvas: the one place cells are accumulated while a building is drawn.

Shape helpers (`shapes.py`, `floors.py`, `pad.py`) are pure — each returns the cells it describes as
`Cells`, a mapping of (x, y, z) → BlockState — and the archetype merges them into a `Canvas`. The
canvas is the deliberate exception to the no-mutation rule: a tower is ~100k cells, and rebuilding
an immutable map per helper call would make generation quadratic. Its mutation is confined to
`put`/`put_all`/`clear`; `freeze()` hands out an immutable view for the writer.
"""

from types import MappingProxyType
from typing import Iterable, Mapping

from .blocks import AIR, BlockState

Pos = tuple[int, int, int]
Cells = Mapping[Pos, BlockState]


class Canvas:
    def __init__(self, size: tuple[int, int, int]):
        self.size = size
        self._cells: dict[Pos, BlockState] = {}

    def inside(self, pos: Pos) -> bool:
        x, y, z = pos
        sx, sy, sz = self.size
        return 0 <= x < sx and 0 <= y < sy and 0 <= z < sz

    def put(self, pos: Pos, state: BlockState) -> None:
        if not self.inside(pos):
            raise ValueError(f"cell {pos} outside template size {self.size}")
        self._cells[pos] = state

    def put_all(self, cells: Cells | Iterable[tuple[Pos, BlockState]]) -> None:
        items = cells.items() if isinstance(cells, Mapping) else cells
        for pos, state in items:
            self.put(pos, state)

    def get(self, pos: Pos) -> BlockState | None:
        return self._cells.get(pos)

    def has(self, pos: Pos) -> bool:
        return pos in self._cells

    def clear(self, pos: Pos) -> None:
        """Remove a cell entirely (the world shows through), unlike placing AIR."""
        self._cells.pop(pos, None)

    def freeze(self) -> Cells:
        return MappingProxyType(dict(self._cells))

    def __len__(self) -> int:
        return len(self._cells)


def envelope_air(canvas: Canvas, x0: int, y0: int, z0: int, x1: int, y1: int, z1: int) -> None:
    """Fill every empty cell of a box with explicit air, so rooms stay clear of the world's trees.

    Only the building envelope gets this; the pad margin and the sky above the roof are left as
    absent cells so the biome's own plants can stand there and hills can lean in.
    """
    for y in range(y0, y1 + 1):
        for z in range(z0, z1 + 1):
            for x in range(x0, x1 + 1):
                if not canvas.has((x, y, z)):
                    canvas.put((x, y, z), AIR)


def bounds(cells: Cells) -> tuple[Pos, Pos]:
    xs = [p[0] for p in cells]
    ys = [p[1] for p in cells]
    zs = [p[2] for p in cells]
    return (min(xs), min(ys), min(zs)), (max(xs), max(ys), max(zs))
