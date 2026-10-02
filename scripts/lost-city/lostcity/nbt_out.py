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
from nbt import BYTE, COMPOUND, DOUBLE, END, INT, LIST, STRING, Tag, write  # noqa: E402

from .canvas import Cells

DATA_VERSION = 3955


def _palette_entry(name: str, props: tuple) -> Tag:
    entries = [("Name", Tag(STRING, name))]
    if props:
        entries.insert(0, ("Properties", Tag(COMPOUND, [(k, Tag(STRING, v)) for k, v in props])))
    return Tag(COMPOUND, entries)


def _int_list(values) -> Tag:
    return Tag(LIST, (INT, [Tag(INT, int(v)) for v in values]))


def nbt_tag(value) -> Tag:
    """A block-entity tag from the generator's nested-tuple form: pairs → compound, list → list."""
    if isinstance(value, bool):
        return Tag(BYTE, int(value))
    if isinstance(value, int):
        return Tag(INT, value)
    if isinstance(value, float):
        return Tag(DOUBLE, value)
    if isinstance(value, str):
        return Tag(STRING, value)
    if isinstance(value, tuple):
        return Tag(COMPOUND, [(k, nbt_tag(v)) for k, v in value])
    if isinstance(value, list):
        items = [nbt_tag(v) for v in value]
        return Tag(LIST, (items[0].id if items else END, items))
    raise TypeError(f"cannot encode {value!r} as NBT")


def to_tag(cells: Cells, size: tuple[int, int, int]) -> Tag:
    ordered = sorted(cells.items(), key=lambda item: (item[0][1], item[0][2], item[0][0]))
    palette: dict[tuple, int] = {}
    blocks = []
    for (x, y, z), state in ordered:
        if not (0 <= x < size[0] and 0 <= y < size[1] and 0 <= z < size[2]):
            raise ValueError(f"cell {(x, y, z)} outside size {size}")
        index = palette.setdefault((state.name, state.props), len(palette))
        entry = [("pos", _int_list((x, y, z))), ("state", Tag(INT, index))]
        if state.nbt:
            entry.insert(0, ("nbt", nbt_tag(state.nbt)))
        blocks.append(Tag(COMPOUND, entry))
    return Tag(COMPOUND, [
        ("size", _int_list(size)),
        ("entities", Tag(LIST, (END, []))),
        ("blocks", Tag(LIST, (COMPOUND, blocks))),
        ("palette", Tag(LIST, (COMPOUND, [_palette_entry(n, p) for n, p in palette]))),
        ("DataVersion", Tag(INT, DATA_VERSION)),
    ])


def to_bytes(cells: Cells, size: tuple[int, int, int]) -> bytes:
    raw = write("", to_tag(cells, size))
    buffer = io.BytesIO()
    with gzip.GzipFile(fileobj=buffer, mode="wb", mtime=0) as out:
        out.write(raw)
    return buffer.getvalue()
