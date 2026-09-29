"""Canvas → Minecraft structure NBT, written byte-identically on every run.

Layout (1.21.1, DataVersion 3955): root compound `size` [x, y, z], `blocks` [{pos, state}],
`palette` [{Name, Properties?}], `entities` [] and `DataVersion`. Entries are sorted by (y, z, x) and
the palette is in first-seen order over that sorted list, so the same canvas always gives the same
bytes; gzip runs with mtime 0 for the same reason (CI's --check diffs the files).
"""

import gzip
import io
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "portal"))
from nbt import COMPOUND, END, INT, LIST, STRING, Tag, write  # noqa: E402

from .blocks import BlockState
from .canvas import Cells

DATA_VERSION = 3955


def _palette_entry(state: BlockState) -> Tag:
    entries = [("Name", Tag(STRING, state.name))]
    if state.props:
        entries.insert(0, ("Properties", Tag(COMPOUND, [(k, Tag(STRING, v)) for k, v in state.props])))
    return Tag(COMPOUND, entries)


def _int_list(values) -> Tag:
    return Tag(LIST, (INT, [Tag(INT, int(v)) for v in values]))


def to_tag(cells: Cells, size: tuple[int, int, int]) -> Tag:
    ordered = sorted(cells.items(), key=lambda item: (item[0][1], item[0][2], item[0][0]))
    palette: dict[BlockState, int] = {}
    blocks = []
    for (x, y, z), state in ordered:
        if not (0 <= x < size[0] and 0 <= y < size[1] and 0 <= z < size[2]):
            raise ValueError(f"cell {(x, y, z)} outside size {size}")
        index = palette.setdefault(state, len(palette))
        blocks.append(Tag(COMPOUND, [("pos", _int_list((x, y, z))), ("state", Tag(INT, index))]))
    return Tag(COMPOUND, [
        ("size", _int_list(size)),
        ("entities", Tag(LIST, (END, []))),
        ("blocks", Tag(LIST, (COMPOUND, blocks))),
        ("palette", Tag(LIST, (COMPOUND, [_palette_entry(s) for s in palette]))),
        ("DataVersion", Tag(INT, DATA_VERSION)),
    ])


def to_bytes(cells: Cells, size: tuple[int, int, int]) -> bytes:
    raw = write("", to_tag(cells, size))
    buffer = io.BytesIO()
    with gzip.GzipFile(fileobj=buffer, mode="wb", mtime=0) as out:
        out.write(raw)
    return buffer.getvalue()
