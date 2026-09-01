#!/usr/bin/env python3
"""Build one contact sheet per site from the sweep's screenshots.

The sweep leaves run/screenshots/sweep-<pack>-<site>.png — a dozen packs times eight sites. Read
one at a time, the packs cannot be compared; the question the matrix asks is always "what did the
other eleven do at this same site", so the sheet is per SITE, packs tiled within it, control first.

    python3 scripts/shaders/contact-sheet.py [out_dir]
"""
import sys
from collections import defaultdict
from pathlib import Path

from PIL import Image, ImageDraw

SHOTS = Path("run/screenshots")
TILE_W = 640
LABEL_H = 22
COLUMNS = 4


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
    out_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("run/contact-sheets")
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
                h = max(1, round(im.height * TILE_W / im.width))
                thumbs.append((pack, im.resize((TILE_W, h), Image.LANCZOS)))

        tile_h = max(t.height for _, t in thumbs)
        cols = min(COLUMNS, len(thumbs))
        rows = (len(thumbs) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * TILE_W, rows * (tile_h + LABEL_H)), (16, 16, 16))
        draw = ImageDraw.Draw(sheet)
        for i, (pack, thumb) in enumerate(thumbs):
            x = (i % cols) * TILE_W
            y = (i // cols) * (tile_h + LABEL_H)
            draw.text((x + 6, y + 5), pack if pack != "none" else "none (CONTROL)", fill=(255, 255, 255))
            sheet.paste(thumb, (x, y + LABEL_H))

        out = out_dir / f"site-{site}.png"
        sheet.save(out)
        print(f"{out}  ({len(thumbs)} packs)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
