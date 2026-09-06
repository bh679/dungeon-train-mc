#!/usr/bin/env python3
"""Build one contact sheet per site from the sweep's screenshots.

The sweep leaves run/screenshots/sweep-<pack>-<site>.png — a dozen packs times eight sites. Read
one at a time, the packs cannot be compared; the question the matrix asks is always "what did the
other eleven do at this same site", so the sheet is per SITE, packs tiled within it, control first.

    python3 scripts/shaders/contact-sheet.py [out_dir] [--brighten N]

Pass --brighten for the dark sites. The End band, the Nether band and the carriage interiors all
sit near black — the control's End sky measures a mean luma of 10 out of 255 — so at true exposure
every pack looks like an unlit rectangle and the sheet says nothing. A fixed multiplier applied
equally to every tile keeps the comparison honest while making it visible. Each tile is labelled
with its real mean luma so the number, not the boosted picture, is what a verdict rests on.
"""
import sys
from collections import defaultdict
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance

SHOTS = Path("run/screenshots")
# Two wide columns rather than four narrow ones: at four across, a tile came out ~350px
# and the question "is there a starfield or is it black" was a guess. The sheet is for
# judging an image, not for fitting the most packs on screen.
TILE_W = 900
LABEL_H = 26
COLUMNS = 2


def parse(path):
    """sweep-<pack>-<NN-site>.png -> (site, pack). Site keeps its NN so sheets sort in visit order."""
    stem = path.stem
    if not stem.startswith("sweep-"):
        return None
    rest = stem[len("sweep-"):]
    # The site suffix always begins with the two-digit index, and a pack id never contains "-<digit><digit>-".
    for i in range(len(rest) - 3):
        if rest[i] == "-" and rest[i + 1 : i + 3].isdigit() and rest[i + 3] == "-":
            return rest[i + 1 :], rest[:i]
    return None


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    brighten = 1.0
    gamma = 1.0
    for a in sys.argv[1:]:
        if a.startswith("--brighten"):
            brighten = float(a.split("=", 1)[1]) if "=" in a else 6.0
        elif a.startswith("--gamma"):
            gamma = float(a.split("=", 1)[1]) if "=" in a else 2.2
    out_dir = Path(args[0]) if args else Path("run/contact-sheets")
    out_dir.mkdir(parents=True, exist_ok=True)

    by_site = defaultdict(list)
    for png in sorted(SHOTS.glob("sweep-*.png")):
        parsed = parse(png)
        if parsed:
            by_site[parsed[0]].append((parsed[1], png))
    if not by_site:
        print(f"no sweep-*.png in {SHOTS}", file=sys.stderr)
        return 1

    for site, entries in sorted(by_site.items()):
        # Control first: every other tile is read as a difference from it.
        entries.sort(key=lambda e: (e[0] != "none", e[0]))
        thumbs = []
        for pack, png in entries:
            with Image.open(png) as im:
                im = im.convert("RGB")
                grey = im.convert("L")
                luma = sum(grey.getdata()) / (im.width * im.height)
                # Gamma first, then any linear boost. These scenes sit around luma 10-20 of 255 —
                # the void, the Nether's #330808, the underside of the upside-down band — so at
                # true exposure every tile is a black rectangle and the sheet decides nothing. The
                # label carries the untouched mean so the verdict rests on the number.
                if gamma != 1.0:
                    lut = [min(255, round(((i / 255.0) ** (1.0 / gamma)) * 255)) for i in range(256)]
                    im = im.point(lut * 3)
                if brighten != 1.0:
                    im = ImageEnhance.Brightness(im).enhance(brighten)
                h = max(1, round(im.height * TILE_W / im.width))
                label = f"{pack}  (mean luma {luma:.1f})"
                thumbs.append((label, im.resize((TILE_W, h), Image.LANCZOS)))

        tile_h = max(t.height for _, t in thumbs)
        cols = min(COLUMNS, len(thumbs))
        rows = (len(thumbs) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * TILE_W, rows * (tile_h + LABEL_H)), (16, 16, 16))
        draw = ImageDraw.Draw(sheet)
        for i, (label, thumb) in enumerate(thumbs):
            x = (i % cols) * TILE_W
            y = (i // cols) * (tile_h + LABEL_H)
            text = label.replace("none ", "none (CONTROL) ", 1) if label.startswith("none ") else label
            draw.text((x + 6, y + 7), text, fill=(255, 255, 255))
            sheet.paste(thumb, (x, y + LABEL_H))

        marks = []
        if gamma != 1.0:
            marks.append(f"gamma {gamma:g}")
        if brighten != 1.0:
            marks.append(f"x{brighten:g}")
        suffix = f" [{', '.join(marks)}]" if marks else ""
        out = out_dir / f"site-{site}.png"
        sheet.save(out)
        print(f"{out}  ({len(thumbs)} packs){suffix}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
