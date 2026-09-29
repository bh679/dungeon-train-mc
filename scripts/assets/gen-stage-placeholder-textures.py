#!/usr/bin/env python3
"""Generate the 16x16 textures for the stage placeholder blocks.

Deliberately flat, obviously-not-vanilla tiles so a placeholder that ever reaches a live train is
visible at a glance: a grey family for the solid / stairs / slab / button / plate slots with the
slot number stamped on (and a kind letter for the stone set), and a brown family for the wood set (planks, log side + end, door halves,
trapdoor), plus a green leaves tile. Full-cube placeholders get separate side / top / bottom tiles: a
up-arrow on the sides, a T on top and a B underneath, so the editor shows which way is up. Re-run after changing the palette or the catalogue in StagePlaceholderBlocks:

    python3 scripts/assets/gen-stage-placeholder-textures.py

Writes to src/main/resources/assets/dungeontrain/textures/block/ and textures/item/.
"""
from __future__ import annotations

import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except ImportError:  # pragma: no cover
    sys.exit("Pillow is required: pip install pillow")

ROOT = Path(__file__).resolve().parents[2]
BLOCK_DIR = ROOT / "src/main/resources/assets/dungeontrain/textures/block"
ITEM_DIR = ROOT / "src/main/resources/assets/dungeontrain/textures/item"

SIZE = 16

# 3x5 pixel digits, row-major, '#' = lit.
DIGITS = {
    "0": ["###", "#.#", "#.#", "#.#", "###"],
    "1": [".#.", "##.", ".#.", ".#.", "###"],
    "2": ["###", "..#", "###", "#..", "###"],
    "3": ["###", "..#", "###", "..#", "###"],
    "4": ["#.#", "#.#", "###", "..#", "..#"],
    "5": ["###", "#..", "###", "..#", "###"],
    "6": ["###", "#..", "###", "#.#", "###"],
    "7": ["###", "..#", "..#", "..#", "..#"],
    "8": ["###", "#.#", "###", "#.#", "###"],
    "9": ["###", "#.#", "###", "..#", "###"],
    "S": ["###", "#..", "###", "..#", "###"],
    "W": ["#.#", "#.#", "#.#", "###", "#.#"],
    "L": ["#..", "#..", "#..", "#..", "###"],
    "P": ["###", "#.#", "###", "#..", "#.."],
    "D": ["##.", "#.#", "#.#", "#.#", "##."],
    "T": ["###", ".#.", ".#.", ".#.", ".#."],
    "C": ["###", "#..", "#..", "#..", "###"],
    "B": ["##.", "#.#", "##.", "#.#", "##."],
    "K": ["#.#", "#.#", "##.", "#.#", "#.#"],
    "M": ["#.#", "###", "#.#", "#.#", "#.#"],
    "F": ["###", "#..", "##.", "#..", "#.."],
    "V": ["#.#", "#.#", "#.#", "#.#", ".#."],
}

GREY = (128, 132, 140)
GREY_DARK = (96, 100, 108)
GREY_LIGHT = (160, 164, 172)
INK = (24, 26, 30)

BROWN = (150, 110, 70)
BROWN_DARK = (110, 78, 46)
BROWN_LIGHT = (184, 142, 96)
GREEN = (84, 132, 62)
GREEN_DARK = (58, 96, 42)
GREEN_LIGHT = (116, 164, 88)

BARK = (92, 64, 40)
BARK_DARK = (70, 48, 30)


def base_tile(fill, dark, light) -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), fill + (255,))
    d = ImageDraw.Draw(img)
    # Checker-ish noise so the face doesn't read as a flat colour in-game.
    for y in range(SIZE):
        for x in range(SIZE):
            if (x * 7 + y * 13) % 11 == 0:
                d.point((x, y), dark + (255,))
            elif (x * 5 + y * 3) % 13 == 0:
                d.point((x, y), light + (255,))
    # 1px border.
    d.rectangle([0, 0, SIZE - 1, SIZE - 1], outline=dark + (255,))
    return img


def stamp(img: Image.Image, text: str, colour=INK, y0: int = (SIZE - 5) // 2) -> None:
    d = ImageDraw.Draw(img)
    width = len(text) * 4 - 1
    x0 = (SIZE - width) // 2
    for i, ch in enumerate(text):
        glyph = DIGITS[ch]
        for gy, row in enumerate(glyph):
            for gx, cell in enumerate(row):
                if cell == "#":
                    d.point((x0 + i * 4 + gx, y0 + gy), colour + (255,))


def arrow(img: Image.Image, fill, colour, y: int = 1) -> None:
    """Up-pointing triangle, top-centred at row ``y`` (rows y..y+2): which way is up. The noise
    under it is cleared first so the mark reads cleanly."""
    d = ImageDraw.Draw(img)
    d.rectangle([4, y, 11, y + 2], fill=fill + (255,))
    d.line([(7, y), (8, y)], fill=colour + (255,))
    d.line([(6, y + 1), (9, y + 1)], fill=colour + (255,))
    d.line([(5, y + 2), (10, y + 2)], fill=colour + (255,))


def glyph(img: Image.Image, letter: str, x0: int, y0: int, fill, colour) -> None:
    """A 3x5 glyph at (x0, y0) on a cleared 1px margin (kept inside the tile's 1px border)."""
    d = ImageDraw.Draw(img)
    d.rectangle([max(x0 - 1, 1), max(y0 - 1, 1), min(x0 + 3, SIZE - 2), min(y0 + 5, SIZE - 2)],
                fill=fill + (255,))
    for gy, row in enumerate(DIGITS[letter]):
        for gx, cell in enumerate(row):
            if cell == "#":
                d.point((x0 + gx, y0 + gy), colour + (255,))


def corner(img: Image.Image, letter: str, fill, colour) -> None:
    """A 3x5 glyph in the top-right corner, just inside the 1px border."""
    glyph(img, letter, SIZE - 4, 2, fill, colour)


# Tile family → (maker, base fill, mark colour). Marks are stamped like the slot labels: ink,
# except on dark bark where the label itself is light.
FAMILIES = {
    "grey": (lambda label: grey(label), GREY, INK),
    "brown": (lambda label: brown(label), BROWN, INK),
    "green": (lambda label: green(label), GREEN, INK),
    "bark": (lambda label: bark(label), BARK, BROWN_LIGHT),
    "log_end": (lambda label: log_top(), BROWN_LIGHT, INK),
}


def top_bottom_faces(name: str, family: str, label: str) -> None:
    """``<name>_top`` (label + T in the top-right corner) and ``<name>_bottom`` (label + B)."""
    make, fill, ink = FAMILIES[family]
    for suffix, letter in (("top", "T"), ("bottom", "B")):
        face = make(label)
        corner(face, letter, fill, ink)
        write(face, BLOCK_DIR / f"{name}_{suffix}.png")


def cube_faces(name: str, family: str, label: str) -> None:
    """Side / top / bottom tiles for a full-cube placeholder (model parent cube_bottom_top):
    sides carry an up-arrow, the top a T and the bottom a B, all keeping the slot label."""
    make, fill, ink = FAMILIES[family]
    side = make(label)
    arrow(side, fill, ink)
    write(side, BLOCK_DIR / f"{name}_side.png")
    top_bottom_faces(name, family, label)


def half_side(name: str, family: str) -> None:
    """``<name>_half_side`` for slabs and stairs: a slab / step side shows only one 8px half of the
    tile, so each half carries its own arrow (no label — it stays on the top and bottom)."""
    make, fill, ink = FAMILIES[family]
    side = make("")
    arrow(side, fill, ink, y=1)
    arrow(side, fill, ink, y=9)
    write(side, BLOCK_DIR / f"{name}_half_side.png")


def wall_faces(name: str, family: str, label: str) -> None:
    """``<name>_wall_side/_top/_bottom``. A low wall's sides start at row 2, so the arrow sits a
    little lower (label below it); the wall top is only 6-8px wide, so T / B are centred."""
    make, fill, ink = FAMILIES[family]
    side = make("")
    arrow(side, fill, ink, y=3)
    stamp(side, label, y0=8)
    write(side, BLOCK_DIR / f"{name}_wall_side.png")
    for suffix, letter in (("top", "T"), ("bottom", "B")):
        face = make("")
        glyph(face, letter, 6, 5, fill, ink)
        write(face, BLOCK_DIR / f"{name}_wall_{suffix}.png")


def grey(label: str) -> Image.Image:
    img = base_tile(GREY, GREY_DARK, GREY_LIGHT)
    stamp(img, label)
    return img


def brown(label: str) -> Image.Image:
    img = base_tile(BROWN, BROWN_DARK, BROWN_LIGHT)
    stamp(img, label)
    return img


def green(label: str) -> Image.Image:
    img = base_tile(GREEN, GREEN_DARK, GREEN_LIGHT)
    stamp(img, label)
    return img


def bark(label: str) -> Image.Image:
    img = base_tile(BARK, BARK_DARK, BROWN_DARK)
    d = ImageDraw.Draw(img)
    for x in range(2, SIZE, 4):
        d.line([(x, 1), (x, SIZE - 2)], fill=BARK_DARK + (255,))
    stamp(img, label, colour=BROWN_LIGHT)
    return img


def door(top: bool) -> Image.Image:
    """One half of a panelled door: frame, an inset raised panel, a window on the top half and a
    handle on the bottom half — so the two tiles read as a single tall door."""
    img = Image.new("RGBA", (SIZE, SIZE), BROWN + (255,))
    d = ImageDraw.Draw(img)
    # Frame: left/right stiles always; top rail on the upper tile, bottom rail on the lower.
    d.rectangle([0, 0, 1, SIZE - 1], fill=BROWN_DARK + (255,))
    d.rectangle([SIZE - 2, 0, SIZE - 1, SIZE - 1], fill=BROWN_DARK + (255,))
    if top:
        d.rectangle([0, 0, SIZE - 1, 1], fill=BROWN_DARK + (255,))
    else:
        d.rectangle([0, SIZE - 2, SIZE - 1, SIZE - 1], fill=BROWN_DARK + (255,))
    # Raised panel: light top/left edge, dark bottom/right edge.
    y0, y1 = (4, SIZE - 2) if top else (1, SIZE - 5)
    d.rectangle([4, y0, SIZE - 5, y1], fill=BROWN_LIGHT + (255,))
    d.line([(4, y0), (SIZE - 5, y0)], fill=BROWN_LIGHT + (255,))
    d.line([(4, y1), (SIZE - 5, y1)], fill=BARK + (255,))
    d.line([(SIZE - 5, y0), (SIZE - 5, y1)], fill=BARK + (255,))
    d.rectangle([5, y0 + 1, SIZE - 6, y1 - 1], fill=BROWN + (255,))
    if top:
        # Small window.
        d.rectangle([6, 6, 9, 9], fill=(150, 190, 210, 255))
        d.rectangle([6, 6, 9, 9], outline=BARK_DARK + (255,))
    else:
        # Handle on the right.
        d.rectangle([11, 3, 12, 5], fill=(210, 190, 90, 255))
    return img


def log_top() -> Image.Image:
    img = base_tile(BROWN_LIGHT, BROWN, BROWN_LIGHT)
    d = ImageDraw.Draw(img)
    for r in (2, 5):
        d.rectangle([r, r, SIZE - 1 - r, SIZE - 1 - r], outline=BROWN_DARK + (255,))
    d.rectangle([0, 0, SIZE - 1, SIZE - 1], outline=BARK + (255,))
    return img


def write(img: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)
    print(f"wrote {path.relative_to(ROOT)}")


def main() -> None:
    for i in range(1, 11):
        write(grey(str(i)), BLOCK_DIR / f"stage_block_{i}.png")
    for i in (1, 2):
        write(grey(f"S{i}"), BLOCK_DIR / f"stage_stairs_{i}.png")
        write(grey(f"L{i}"), BLOCK_DIR / f"stage_slab_{i}.png")
    write(grey("0"), BLOCK_DIR / "stage_fitting.png")  # button + pressure plate face

    write(brown("P"), BLOCK_DIR / "stage_planks.png")
    write(bark("L"), BLOCK_DIR / "stage_log.png")
    write(brown("SL"), BLOCK_DIR / "stage_stripped_log.png")
    write(log_top(), BLOCK_DIR / "stage_log_top.png")
    write(door(True), BLOCK_DIR / "stage_door_top.png")
    write(door(False), BLOCK_DIR / "stage_door_bottom.png")
    write(green("LV"), BLOCK_DIR / "stage_leaves.png")

    # Stone set: one tile per kind, shared by its stairs / slab / wall.
    for kind, letter in (("cobbled", "C"), ("stone", "S"), ("bricks", "B"), ("polished", "P"),
                         ("cracked", "K"), ("mossy", "M"), ("feature", "F")):
        name = "stage_stone" if kind == "stone" else f"stage_stone_{kind}"
        write(grey(letter), BLOCK_DIR / f"{name}.png")  # stairs / slab / wall
        cube_faces(name, "grey", letter)
        half_side(name, "grey")
        wall_faces(name, "grey", letter)

    # Full cubes get oriented faces (the plain tiles above stay for any shape still using them).
    for i in range(1, 11):
        cube_faces(f"stage_block_{i}", "grey", str(i))
    cube_faces("stage_planks", "brown", "P")
    cube_faces("stage_leaves", "green", "LV")

    # Slabs + stairs: per-half arrows on the sides, labelled T / B tiles on top and bottom.
    for i in (1, 2):
        for shape, letter in (("slab", "L"), ("stairs", "S")):
            half_side(f"stage_{shape}_{i}", "grey")
            top_bottom_faces(f"stage_{shape}_{i}", "grey", f"{letter}{i}")
    half_side("stage_planks", "brown")

    # Logs / wood: arrow along the bark, T / B on the ends.
    cube_faces("stage_wood", "bark", "L")
    cube_faces("stage_stripped_wood", "brown", "SL")
    top_bottom_faces("stage_log_end", "log_end", "")

    # Trapdoor (labelled TD so the T mark reads as "top") and the pressure-plate face.
    write(brown("TD"), BLOCK_DIR / "stage_trapdoor.png")
    top_bottom_faces("stage_trapdoor", "brown", "TD")
    top_bottom_faces("stage_fitting", "grey", "0")

    # Flat item icon for the door (vanilla doors use item/generated with their own sprite).
    # Item icon: the whole door squeezed into one tile (top half over bottom half).
    icon = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    icon.paste(door(True).resize((10, 8)), (3, 0))
    icon.paste(door(False).resize((10, 8)), (3, 8))
    write(icon, ITEM_DIR / "stage_door.png")


if __name__ == "__main__":
    main()
