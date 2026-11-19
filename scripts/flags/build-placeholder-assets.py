#!/usr/bin/env python3
"""Build the Dungeon Train Flag 1-8 placeholder assets: textures, block models, blockstates, item models.

The flags are editor-only stand-ins that roll into real banners when a room rolls
(block/flag/FlagPlaceholders). Each texture is a small dark flag on a pole with a light ring
(the logo) round the flag's number, so a builder can tell Flag 1 from Flag 2 at a glance.

Run from the repo root:  python3 scripts/flags/build-placeholder-assets.py [--check]
--check exits 1 if any generated file differs from what is committed.
"""
import io
import json
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/dungeontrain"
COUNT = 8

POLE = (122, 88, 52, 255)
POLE_DARK = (94, 66, 38, 255)
CLOTH = (29, 29, 33, 255)
CLOTH_EDGE = (16, 16, 18, 255)
RING = (157, 157, 151, 255)
RING_DARK = (71, 79, 82, 255)
DIGIT = (249, 255, 254, 255)

# 3x5 digits, rows top to bottom.
DIGITS = {
    1: ["010", "110", "010", "010", "111"],
    2: ["111", "001", "111", "100", "111"],
    3: ["111", "001", "011", "001", "111"],
    4: ["101", "101", "111", "001", "001"],
    5: ["111", "100", "111", "001", "111"],
    6: ["111", "100", "111", "101", "111"],
    7: ["111", "001", "010", "010", "010"],
    8: ["111", "101", "111", "101", "111"],
}

# The cloth: x 3..14, y 1..11 (12 x 11). A 9x9 ring centred on the cloth, broken into four
# carts like the logo; the digit sits inside it.
CLOTH_BOX = (3, 1, 14, 11)
RING_CELLS = [
    (6, 2), (7, 2), (10, 2), (11, 2),
    (4, 4), (4, 5), (13, 4), (13, 5),
    (4, 7), (4, 8), (13, 7), (13, 8),
    (6, 10), (7, 10), (10, 10), (11, 10),
    (5, 3), (12, 3), (5, 9), (12, 9),
]


def texture(n: int) -> Image.Image:
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y in range(0, 16):
        px[1, y] = POLE
        px[2, y] = POLE_DARK
    x0, y0, x1, y1 = CLOTH_BOX
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            edge = x in (x0, x1) or y in (y0, y1)
            px[x, y] = CLOTH_EDGE if edge else CLOTH
    for i, (x, y) in enumerate(RING_CELLS):
        px[x, y] = RING if i % 2 == 0 else RING_DARK
    rows = DIGITS[n]
    for dy, row in enumerate(rows):
        for dx, bit in enumerate(row):
            if bit == "1":
                px[8 + dx, 4 + dy] = DIGIT
    return img


def standing_model(n: int) -> dict:
    tex = f"dungeontrain:block/flag_{n}"
    cloth_uv = [3, 1, 15, 12]
    return {
        "parent": "block/block",
        "textures": {"flag": tex, "pole": "minecraft:block/oak_planks", "particle": tex},
        "elements": [
            {"from": [7, 0, 7], "to": [9, 16, 9],
             "faces": {f: {"uv": [7, 0, 9, 16], "texture": "#pole"} for f in ("north", "south", "east", "west")}
             | {"up": {"uv": [7, 7, 9, 9], "texture": "#pole"}, "down": {"uv": [7, 7, 9, 9], "texture": "#pole"}}},
            {"from": [2, 4, 9], "to": [14, 15, 10],
             "faces": {
                 "south": {"uv": cloth_uv, "texture": "#flag"},
                 "north": {"uv": [15, 1, 3, 12], "texture": "#flag"},
                 "east": {"uv": [3, 1, 4, 12], "texture": "#flag"},
                 "west": {"uv": [3, 1, 4, 12], "texture": "#flag"},
                 "up": {"uv": [3, 1, 15, 2], "texture": "#flag"},
                 "down": {"uv": [3, 11, 15, 12], "texture": "#flag"}}},
        ],
    }


def wall_model(n: int) -> dict:
    tex = f"dungeontrain:block/flag_{n}"
    return {
        "parent": "block/block",
        "textures": {"flag": tex, "pole": "minecraft:block/oak_planks", "particle": tex},
        "elements": [
            {"from": [1, 13, 14], "to": [15, 14, 16],
             "faces": {f: {"uv": [0, 0, 16, 1], "texture": "#pole"} for f in ("north", "south", "up", "down")}
             | {"east": {"uv": [0, 0, 2, 1], "texture": "#pole"}, "west": {"uv": [0, 0, 2, 1], "texture": "#pole"}}},
            {"from": [2, 2, 14.5], "to": [14, 13, 15.5],
             "faces": {
                 "north": {"uv": [3, 1, 15, 12], "texture": "#flag"},
                 "south": {"uv": [15, 1, 3, 12], "texture": "#flag"},
                 "east": {"uv": [3, 1, 4, 12], "texture": "#flag"},
                 "west": {"uv": [3, 1, 4, 12], "texture": "#flag"},
                 "up": {"uv": [3, 1, 15, 2], "texture": "#flag"},
                 "down": {"uv": [3, 11, 15, 12], "texture": "#flag"}}},
        ],
    }


def standing_state(n: int) -> dict:
    # Banner rotation 0 faces south (the model's front); every 4 steps is a quarter turn.
    return {"variants": {f"rotation={r}": {"model": f"dungeontrain:block/flag_{n}",
                                          **({"y": (round(r / 4) % 4) * 90} if round(r / 4) % 4 else {})}
                         for r in range(16)}}


def wall_state(n: int) -> dict:
    turns = {"north": 0, "east": 90, "south": 180, "west": 270}
    return {"variants": {f"facing={f}": {"model": f"dungeontrain:block/wall_flag_{n}", **({"y": y} if y else {})}
                         for f, y in turns.items()}}


def outputs() -> dict:
    files = {}
    for n in range(1, COUNT + 1):
        buf = io.BytesIO()
        texture(n).save(buf, format="PNG")
        files[f"textures/block/flag_{n}.png"] = buf.getvalue()
        files[f"models/block/flag_{n}.json"] = standing_model(n)
        files[f"models/block/wall_flag_{n}.json"] = wall_model(n)
        files[f"blockstates/flag_{n}.json"] = standing_state(n)
        files[f"blockstates/wall_flag_{n}.json"] = wall_state(n)
        files[f"models/item/flag_{n}.json"] = {"parent": "minecraft:item/generated",
                                               "textures": {"layer0": f"dungeontrain:block/flag_{n}"}}
    return {k: v if isinstance(v, bytes) else (json.dumps(v, indent=2) + "\n").encode() for k, v in files.items()}


def main() -> int:
    check = "--check" in sys.argv[1:]
    stale = []
    for rel, data in outputs().items():
        path = ASSETS / rel
        if check:
            if not path.exists() or (path.read_bytes() != data and not rel.endswith(".png")):
                stale.append(rel)
            elif rel.endswith(".png") and Image.open(path).convert("RGBA").tobytes() != Image.open(io.BytesIO(data)).tobytes():
                stale.append(rel)
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    if check and stale:
        print("Stale flag assets (run scripts/flags/build-placeholder-assets.py):\n  " + "\n  ".join(stale))
        return 1
    print("flag assets " + ("up to date" if check else f"written ({len(outputs())} files)"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
