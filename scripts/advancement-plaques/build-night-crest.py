#!/usr/bin/env python3
"""Draw the Night Crest plaque textures: the card Advancement Plaques shows for the star-frame
advancements (each tab's tab-complete, the Everything Burrito, It's Not That Simple).

All of it is DT's own art, drawn here from scratch plus DT's star frame
(sprites/advancements/tab_complete_frame_obtained.png). Advancement Plaques is CC BY-NC-ND, so
nothing of its textures is copied or recoloured; the mixin only points its draw calls at these.

Writes into assets/dungeontrain/textures/gui/plaque/:
  night_crest.png         256x32  the plaque: star-frame icon box + starry navy bar, gold rim
  night_crest_points.png  288x48  star points off the bar's ends and top/bottom centre (drawn at -16,-8)
  night_crest_halo.png    320x80  soft gold glow behind the plaque (drawn at -32,-24)
  night_crest_flash.png   288x64  the bright gold flash as it appears (drawn at -16,-16, Plaques' effect box)
  sparkle.png             16x8    a small (5x5) and a big (7x7) four-point sparkle

Run: python3 scripts/advancement-plaques/build-night-crest.py [--check]
--check exits 1 if the committed PNGs differ from what this script draws.
"""
import io
import math
import random
import sys
from pathlib import Path

from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
TEX = ROOT / "src/main/resources/assets/dungeontrain/textures/gui"
STAR_FRAME = TEX / "sprites/advancements/tab_complete_frame_obtained.png"
OUT = TEX / "plaque"

NAVY = (20, 26, 64, 255)
NAVY_SHADE = (10, 12, 36, 255)
RIM_DARK = (90, 55, 0, 255)
RIM_GOLD = (214, 160, 30, 255)
RIM_LIGHT = (255, 226, 120, 255)
WELL = (40, 28, 16, 255)
SPECKS = [(255, 255, 255, 255), (255, 230, 150, 255), (170, 190, 255, 255)]
SPARK = (255, 244, 180, 255)

BAR_X0, BAR_X1 = 31, 256  # bar spans x in [31, 256)


def rounded_rect(img, x0, y0, x1, y1, colour, corner):
    """Fill [x0,x1)×[y0,y1) with the corners cut `corner` pixels diagonally."""
    px = img.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            dx = min(x - x0, x1 - 1 - x)
            dy = min(y - y0, y1 - 1 - y)
            if dx + dy >= corner:
                px[x, y] = colour


def plaque():
    img = Image.new("RGBA", (256, 32))
    # Bar: dark outline, gold rim, light bevel, then the navy well with a shaded inner edge.
    rounded_rect(img, BAR_X0, 0, BAR_X1, 32, RIM_DARK, 2)
    rounded_rect(img, BAR_X0 + 1, 1, BAR_X1 - 1, 31, RIM_GOLD, 2)
    rounded_rect(img, BAR_X0 + 1, 1, BAR_X1 - 1, 2, RIM_LIGHT, 0)
    rounded_rect(img, BAR_X0 + 2, 3, BAR_X1 - 3, 29, RIM_DARK, 1)
    rounded_rect(img, BAR_X0 + 3, 4, BAR_X1 - 4, 28, NAVY_SHADE, 0)
    rounded_rect(img, BAR_X0 + 3, 5, BAR_X1 - 4, 27, NAVY, 0)
    px = img.load()
    rng = random.Random(9)
    for _ in range(45):
        x, y = rng.randint(BAR_X0 + 5, BAR_X1 - 7), rng.randint(6, 25)
        px[x, y] = rng.choice(SPECKS)
    for x, y in [(60, 9), (230, 20), (150, 24)]:
        for dx, dy in [(0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)]:
            px[x + dx, y + dy] = SPECKS[0]
    # Icon box: DT's star frame, its gold face turned into the dark well the item sits in.
    star = Image.open(STAR_FRAME).convert("RGBA")
    face = max(set(p for p in star.getdata() if p[3]), key=list(star.getdata()).count)
    star.putdata([WELL if p == face else p for p in star.getdata()])
    img.paste((0, 0, 0, 0), (0, 0, BAR_X0, 32))
    img.alpha_composite(star, (3, 3))
    return img


def points():
    """Star points, in a 288x48 canvas whose (16,8) is the plaque's top-left."""
    img = Image.new("RGBA", (288, 48))
    px = img.load()
    ox, oy = 16, 8
    for side in (-1, 1):
        x0 = ox - 1 if side < 0 else ox + 256
        for k in range(8):
            half = max(0, 6 - k)
            for yy in range(16 - half, 16 + half + 1):
                px[x0 + side * k, oy + yy] = RIM_DARK if abs(yy - 16) == half else RIM_GOLD
    cx = ox + BAR_X0 + (BAR_X1 - BAR_X0) // 2
    for side in (-1, 1):
        for k in range(5):
            half = max(0, 4 - k)
            y0 = oy - 1 - k if side < 0 else oy + 32 + k
            for xx in range(cx - half, cx + half + 1):
                px[xx, y0] = RIM_DARK if abs(xx - cx) == half else RIM_GOLD
    return img


def glow(size, box, colour, alpha, blur):
    mask = Image.new("L", size, 0)
    mask.paste(alpha, box)
    mask = mask.filter(ImageFilter.GaussianBlur(blur))
    img = Image.new("RGBA", size, colour + (0,))
    img.putalpha(mask)
    return img


def halo():
    # Plaque sits at (32,24) in a 320x80 canvas.
    return glow((320, 80), (32 + 2, 24 - 8, 32 + 256 + 8, 24 + 32 + 8), (255, 210, 80), 220, 7)


def flash():
    # Plaques' effect box: plaque at (16,16) in a 288x64 canvas.
    base = glow((288, 64), (16, 16, 16 + 256, 16 + 32), (255, 236, 160), 255, 6)
    core = glow((288, 64), (16 + 4, 16 + 4, 16 + 252, 16 + 28), (255, 250, 225), 200, 3)
    base.alpha_composite(core)
    return base


def sparkle():
    img = Image.new("RGBA", (16, 8))
    px = img.load()
    for cx, cy, r in [(2, 2, 2), (11, 3, 3)]:
        for d in range(-r, r + 1):
            px[cx + d, cy] = SPARK
            px[cx, cy + d] = SPARK
        if r == 3:
            for d in (-1, 1):
                px[cx + d, cy + d] = (255, 250, 220, 160)
                px[cx + d, cy - d] = (255, 250, 220, 160)
    return img


def png_bytes(img):
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def main():
    check = "--check" in sys.argv
    outputs = {
        "night_crest.png": plaque(),
        "night_crest_points.png": points(),
        "night_crest_halo.png": halo(),
        "night_crest_flash.png": flash(),
        "sparkle.png": sparkle(),
    }
    OUT.mkdir(parents=True, exist_ok=True)
    stale = []
    for name, img in outputs.items():
        path = OUT / name
        if check:
            if not path.exists() or list(Image.open(path).convert("RGBA").getdata()) != list(img.getdata()):
                stale.append(name)
        else:
            path.write_bytes(png_bytes(img))
    if stale:
        print("Night Crest textures out of date, rerun build-night-crest.py: " + ", ".join(stale))
        sys.exit(1)
    print("ok" if check else f"wrote {len(outputs)} textures to {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
