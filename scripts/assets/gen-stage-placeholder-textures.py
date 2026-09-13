#!/usr/bin/env python3
"""Generate the 16x16 textures for the stage placeholder blocks.

Deliberately flat, obviously-not-vanilla tiles so a placeholder that ever reaches a live train is
visible at a glance: a grey family for the solid / stairs / slab / button / plate slots with the
slot number stamped on (and a kind letter for the stone set), and a brown family for the wood set (planks, log side + end, door halves,
trapdoor). Re-run after changing the palette or the catalogue in StagePlaceholderBlocks:

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
}

GREY = (128, 132, 140)
GREY_DARK = (96, 100, 108)
GREY_LIGHT = (160, 164, 172)
INK = (24, 26, 30)

BROWN = (150, 110, 70)
BROWN_DARK = (110, 78, 46)
BROWN_LIGHT = (184, 142, 96)
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


def stamp(img: Image.Image, text: str, colour=INK) -> None:
    d = ImageDraw.Draw(img)
    width = len(text) * 4 - 1
    x0 = (SIZE - width) // 2
    y0 = (SIZE - 5) // 2
    for i, ch in enumerate(text):
        glyph = DIGITS[ch]
        for gy, row in enumerate(glyph):
            for gx, cell in enumerate(row):
                if cell == "#":
                    d.point((x0 + i * 4 + gx, y0 + gy), colour + (255,))


def grey(label: str) -> Image.Image:
    img = base_tile(GREY, GREY_DARK, GREY_LIGHT)
    stamp(img, label)
    return img


def brown(label: str) -> Image.Image:
    img = base_tile(BROWN, BROWN_DARK, BROWN_LIGHT)
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
    write(brown("T"), BLOCK_DIR / "stage_trapdoor.png")

    # Stone set: one tile per kind, shared by its stairs / slab / wall.
    for kind, letter in (("cobbled", "C"), ("stone", "S"), ("bricks", "B"), ("polished", "P"),
                         ("cracked", "K"), ("mossy", "M"), ("feature", "F")):
        name = "stage_stone" if kind == "stone" else f"stage_stone_{kind}"
        write(grey(letter), BLOCK_DIR / f"{name}.png")

    # Flat item icon for the door (vanilla doors use item/generated with their own sprite).
    # Item icon: the whole door squeezed into one tile (top half over bottom half).
    icon = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    icon.paste(door(True).resize((10, 8)), (3, 0))
    icon.paste(door(False).resize((10, 8)), (3, 8))
    write(icon, ITEM_DIR / "stage_door.png")


if __name__ == "__main__":
    main()
