#!/usr/bin/env python3
"""Generate the Drifting Engine Block's textures and its screen (design C, the coal stove).

Writes into src/main/resources/assets/dungeontrain/textures/:
  block/drifting_engine_block_{front,side,top}.png        lit, 6-frame animated strips (+ .mcmeta)
  block/drifting_engine_block_{front,side,top}_cold.png   0 coal
  block/drifting_engine_block_bottom.png
  gui/container/drifting_engine_block.png                 the coal screen

Only the fire animates: the iron has no randomness at all and the fire draws from its own seeded
stream, so every frame's iron is identical. Re-run after tuning a colour; the output is deterministic.

    python3 scripts/textures/drifting-engine-block.py
"""
import json
import math
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..",
                    "src", "main", "resources", "assets", "dungeontrain", "textures")
FRAMES = 6
FRAME_TICKS = 3

# Iron, lightest to darkest. Smooth on purpose: no per-pixel noise, only a soft gradient and a brushed grain.
IRON = ["#303038", "#2b2b32", "#26262c", "#212126", "#1c1c21"]
# Outline and seams are dark grey a step under the iron, not black, so they read as edges rather than ink.
EDGE, HI, RIVET, SLOT = "#1c1c21", "#3d3d46", "#55555f", "#1e1e23"
# Brushed grain, one entry per row: -1 a lighter streak, +1 a darker one.
BRUSH = [0, 0, -1, 0, 1, 0, 0, -1, 0, 0, 1, 0, -1, 0, 0, 0]
GLOW = ["#ffe28f", "#ffbf3c", "#f58a23", "#e0561c", "#a8321a"]
COLD = ["#0a0a0c", "#121012", "#1a1311"]


def rng(s):
    st = [s]
    def r():
        st[0] = (st[0] * 16807) % 2147483647
        return st[0] / 2147483647
    return r


def _iron(x: int, y: int, tone: int) -> str:
    """`tone` (0 lightest .. 4 darkest) plus the brushed grain, lit along the top-left inner
    edge and shaded along the bottom-right, as vanilla blocks are."""
    i = tone + BRUSH[y]
    if x == 1 or y == 1:
        i -= 1
    if x == 14 or y == 14:
        i += 1
    return IRON[max(0, min(4, i))]


def iron_px(x: int, y: int, r) -> str:
    """Front and sides: a soft gradient, lighter top-left to darker bottom-right, brushed across."""
    t = (x + y) / 30
    return _iron(x, y, 1 if t < .3 else 2 if t < .7 else 3)


def iron_flat(x: int, y: int, r) -> str:
    """Top and bottom: one even tone, brushed across."""
    return _iron(x, y, 2)


ANIM = [None]


def glow(r, lit, deep):
    r = ANIM[0]  # fire uses its own stream so the iron never changes between frames
    v = r()
    if not lit:
        return COLD[0 if v < .6 else 1 if r() < .7 else 2]
    if deep:
        return GLOW[2] if v < .3 else GLOW[3] if v < .75 else GLOW[4]
    return GLOW[0] if v < .2 else GLOW[1] if v < .55 else GLOW[2] if v < .85 else GLOW[3]


fr = lambda x, y: x in (0, 15) or y in (0, 15)
rivet = lambda x, y: x in (2, 13) and y in (2, 13)


def front(x, y, r, lit):
    if fr(x, y): return EDGE
    if rivet(x, y): return RIVET
    if 3 <= y <= 12 and 3 <= x <= 12:
        if y in (3, 12) or x in (3, 12): return SLOT
        # Two 2-px grate bars (rows 5-6, 9-10), lit top edge + shadowed bottom edge.
        if y in (5, 9): return "#44444d" if x % 3 else "#3a3a42"
        if y in (6, 10): return "#26262c" if x % 3 else "#202025"
        if lit and x in (4, 11):  # 1-px dimmer rim where the fire meets the frame
            return GLOW[3] if ANIM[0]() < .5 else GLOW[4]
        return glow(r, lit, y in (8, 11)) if lit else ("#0e0e10" if y in (7, 8) else "#0a0a0c")
    return iron_px(x, y, r)


def side(x, y, r, lit):
    if fr(x, y): return EDGE
    if rivet(x, y): return RIVET
    if y % 4 == 3 and y < 14 and 3 <= x <= 12:
        # Dimmer rims: none on the top vent, 1 px on the middle, 2 px on the bottom (which also burns deeper).
        if y == 11 and x in (3, 4, 11, 12):
            return GLOW[4] if lit else glow(r, lit, True)
        return glow(r, lit, y == 11 or (y == 7 and x in (3, 12)))
    if y % 4 == 2 and y < 14 and 3 <= x <= 12: return SLOT
    return iron_px(x, y, r)


def top(x, y, r, lit):
    if fr(x, y): return EDGE
    d = math.hypot(x - 7.5, y - 7.5)
    if d < 2.1: return (GLOW[3] if ANIM[0]() < .5 else GLOW[4]) if lit else "#08080a"
    if d < 3.2: return SLOT if d < 2.7 else HI
    if x in (4, 11) and 1 < y < 14: return SLOT
    return iron_flat(x, y, r)


def bottom(x, y, r, lit):
    return EDGE if fr(x, y) else iron_flat(x, y, r)


def grid(fn, seed, lit, k=0):
    r = rng(seed)
    ANIM[0] = rng(seed * 31 + 1000 + k)
    return [[fn(x, y, r, lit) for x in range(16)] for y in range(16)]


def textures(lit, k=0):
    return {"F": grid(front, 97, lit, k), "S": grid(side, 53, lit, k),
            "T": grid(top, 31, lit, k), "B": grid(bottom, 11, False)}




def rgb(c):
    return tuple(int(c[i:i + 2], 16) for i in (1, 3, 5))


def face_image(g):
    im = Image.new("RGB", (16, 16))
    for y in range(16):
        for x in range(16):
            im.putpixel((x, y), rgb(g[y][x]))
    return im


def write_block_textures(out):
    os.makedirs(out, exist_ok=True)
    for key, name in (("F", "front"), ("S", "side"), ("T", "top")):
        strip = Image.new("RGB", (16, 16 * FRAMES))
        for k in range(FRAMES):
            strip.paste(face_image(textures(True, k)[key]), (0, 16 * k))
        strip.save(os.path.join(out, f"drifting_engine_block_{name}.png"))
        with open(os.path.join(out, f"drifting_engine_block_{name}.png.mcmeta"), "w") as f:
            json.dump({"animation": {"frametime": FRAME_TICKS}}, f, indent=2)
            f.write("\n")
        face_image(textures(False)[key]).save(os.path.join(out, f"drifting_engine_block_{name}_cold.png"))
    face_image(textures(False)["B"]).save(os.path.join(out, "drifting_engine_block_bottom.png"))


# ---- the screen: vanilla container panel colours ----
PANEL, LIGHT, DARK, OUTLINE = (198, 198, 198), (255, 255, 255), (85, 85, 85), (0, 0, 0)
SLOT_BG, SLOT_DARK = (139, 139, 139), (55, 55, 55)


def panel(d, x0, y0, w, h):
    d.rectangle([x0, y0, x0 + w - 1, y0 + h - 1], fill=PANEL)
    d.line([x0 + 1, y0, x0 + w - 3, y0], fill=OUTLINE)
    d.line([x0 + 1, y0 + h - 1, x0 + w - 2, y0 + h - 1], fill=OUTLINE)
    d.line([x0, y0 + 1, x0, y0 + h - 3], fill=OUTLINE)
    d.line([x0 + w - 1, y0 + 2, x0 + w - 1, y0 + h - 2], fill=OUTLINE)
    d.rectangle([x0 + 1, y0 + 1, x0 + w - 4, y0 + 2], fill=LIGHT)
    d.rectangle([x0 + 1, y0 + 1, x0 + 2, y0 + h - 4], fill=LIGHT)
    d.rectangle([x0 + 3, y0 + h - 3, x0 + w - 2, y0 + h - 2], fill=DARK)
    d.rectangle([x0 + w - 3, y0 + 3, x0 + w - 2, y0 + h - 2], fill=DARK)


def slot(d, x, y, w=18, h=18):
    """A recessed slot/trough with its top-left frame pixel at (x, y)."""
    d.rectangle([x, y, x + w - 1, y + h - 1], fill=SLOT_BG)
    d.line([x, y, x + w - 2, y], fill=SLOT_DARK)
    d.line([x, y, x, y + h - 2], fill=SLOT_DARK)
    d.line([x + 1, y + h - 1, x + w - 1, y + h - 1], fill=LIGHT)
    d.line([x + w - 1, y + 1, x + w - 1, y + h - 1], fill=LIGHT)


def write_screen(path):
    """The panel, slots, gauge trough and gauge fill: the coal slot and the gauge share a row."""
    im = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    panel(d, 0, 0, 176, 166)
    slot(d, 12, 19)                       # coal slot: item at (13, 20)
    slot(d, 36, 24, 132, 7)               # gauge trough, centred on the slot: fill at (37, 25) 130x5
    for row in range(3):
        for col in range(9):
            slot(d, 7 + col * 18, 83 + row * 18)
    for col in range(9):
        slot(d, 7 + col * 18, 141)
    for x in range(130):                  # gauge fill sprite, ember gradient
        t = x / 129
        c = (int(168 + (255 - 168) * t), int(50 + (191 - 50) * t), int(26 + (60 - 26) * t))
        for y in range(5):
            d.point((x, 170 + y), fill=c if y not in (0, 4) else tuple(int(v * .8) for v in c))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path)


if __name__ == "__main__":
    write_block_textures(os.path.join(ROOT, "block"))
    write_screen(os.path.join(ROOT, "gui", "container", "drifting_engine_block.png"))
    print("wrote drifting engine block textures under", os.path.normpath(ROOT))
