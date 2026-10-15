#!/usr/bin/env python3
"""Build Dungeon Train's colour emoji font from Twemoji (the set Discord draws).

Run by hand when the Twemoji or emojibase pin changes; the outputs are committed:

  assets/dungeontrain/textures/font/emoji/sheet_<n>.png   32x32 RGBA cells, 32x32 cells per sheet
  assets/dungeontrain/font/emoji.json                       one bitmap provider per sheet
  assets/dungeontrain/emoji/index.json                      sequence -> font codepoint, shortcode -> sequence
  assets/dungeontrain/emoji/LICENSE-TWEMOJI.txt             CC-BY 4.0 attribution

Every emoji (single codepoints too) gets its own plane-15 private-use codepoint (U+F0000 up), so a
ZWJ family, a skin tone or a flag is one glyph and nothing collides with vanilla's fonts.
client/chat/emoji/EmojiIndex reads the index; EmojiText swaps matched runs for those codepoints.

Usage: python3 scripts/emoji/build-emoji-font.py [--twemoji <tar.gz>] [--emojibase <tgz>]
(without the flags the pinned archives are downloaded to a temp dir.)
"""
from __future__ import annotations

import argparse
import io
import json
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

from PIL import Image

TWEMOJI_VERSION = "15.1.0"
TWEMOJI_URL = f"https://github.com/jdecked/twemoji/archive/refs/tags/v{TWEMOJI_VERSION}.tar.gz"
EMOJIBASE_VERSION = "15.3.2"
EMOJIBASE_URL = f"https://registry.npmjs.org/emojibase-data/-/emojibase-data-{EMOJIBASE_VERSION}.tgz"

CELL = 32
GRID = 32  # cells per sheet side -> 1024 glyphs per sheet
PUA_BASE = 0xF0000
FE0F = "fe0f"

REPO = Path(__file__).resolve().parents[2]
ASSETS = REPO / "src/main/resources/assets/dungeontrain"


def fetch(url: str, dest: Path) -> Path:
    print(f"downloading {url}", file=sys.stderr)
    with urllib.request.urlopen(url, timeout=300) as r, open(dest, "wb") as f:
        f.write(r.read())
    return dest


def load_twemoji(path: Path) -> tuple[dict[str, Image.Image], str]:
    images: dict[str, Image.Image] = {}
    license_text = ""
    with tarfile.open(path) as tar:
        for m in tar.getmembers():
            if not m.isfile():
                continue
            if m.name.endswith("/LICENSE-GRAPHICS"):
                license_text = tar.extractfile(m).read().decode("utf-8")
            if "/assets/72x72/" in m.name and m.name.endswith(".png"):
                seq = m.name.rsplit("/", 1)[1][:-4].lower()
                img = Image.open(io.BytesIO(tar.extractfile(m).read())).convert("RGBA")
                images[seq] = img.resize((CELL, CELL), Image.LANCZOS)
    return images, license_text


def load_text_default(path: Path) -> set[str]:
    """Single codepoints whose default presentation is text (emojibase type 0): ©, ❤, ↔, ⚠ … They only
    become emoji when followed by U+FE0F, so ordinary symbols in chat and UI text stay as they are."""
    with tarfile.open(path) as tar:
        data = json.loads(tar.extractfile(tar.getmember("package/en/data.json")).read())
    return {x["hexcode"].lower() for x in data if x.get("type") == 0 and "-" not in x["hexcode"]}


def load_shortcodes(path: Path) -> dict[str, list[str]]:
    """hexcode (upper, '-' joined, may include FE0F) -> list of shortcodes, github first then iamcal."""
    out: dict[str, list[str]] = {}
    with tarfile.open(path) as tar:
        for preset in ("github", "iamcal", "emojibase"):
            m = tar.getmember(f"package/en/shortcodes/{preset}.json")
            data = json.loads(tar.extractfile(m).read())
            for hexcode, codes in data.items():
                codes = codes if isinstance(codes, list) else [codes]
                bucket = out.setdefault(hexcode.lower(), [])
                for c in codes:
                    if c not in bucket:
                        bucket.append(c)
    return out


def strip_fe0f(seq: str) -> str:
    return "-".join(p for p in seq.split("-") if p != FE0F)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--twemoji", type=Path)
    ap.add_argument("--emojibase", type=Path)
    args = ap.parse_args()
    tmp = Path(tempfile.mkdtemp())
    tw = args.twemoji or fetch(TWEMOJI_URL, tmp / "twemoji.tgz")
    eb = args.emojibase or fetch(EMOJIBASE_URL, tmp / "emojibase.tgz")

    images, license_text = load_twemoji(tw)
    shortcodes = load_shortcodes(eb)
    text_default = load_text_default(eb)
    # Twemoji also draws a few private-use logos (U+E50A …): never emoji in text.
    images = {k: v for k, v in images.items() if not 0xE000 <= int(k.split("-")[0], 16) <= 0xF8FF}
    seqs = sorted(images, key=lambda s: [int(p, 16) for p in s.split("-")])
    print(f"{len(seqs)} emoji", file=sys.stderr)

    tex_dir = ASSETS / "textures/font/emoji"
    tex_dir.mkdir(parents=True, exist_ok=True)
    for old in tex_dir.glob("sheet_*.png"):
        old.unlink()

    per_sheet = GRID * GRID
    providers = []
    emoji_map: dict[str, str] = {}
    for sheet_no in range((len(seqs) + per_sheet - 1) // per_sheet):
        chunk = seqs[sheet_no * per_sheet:(sheet_no + 1) * per_sheet]
        rows = (len(chunk) + GRID - 1) // GRID
        sheet = Image.new("RGBA", (GRID * CELL, rows * CELL), (0, 0, 0, 0))
        chars = []
        for r in range(rows):
            row = []
            for c in range(GRID):
                i = r * GRID + c
                if i < len(chunk):
                    cp = PUA_BASE + sheet_no * per_sheet + i
                    sheet.paste(images[chunk[i]], (c * CELL, r * CELL))
                    emoji_map[chunk[i]] = f"{cp:X}"
                    row.append(chr(cp))
                else:
                    row.append("\u0000")
            chars.append("".join(row))
        sheet.save(tex_dir / f"sheet_{sheet_no}.png", optimize=True)
        providers.append({
            "type": "bitmap",
            "file": f"dungeontrain:font/emoji/sheet_{sheet_no}.png",
            "height": 8,
            "ascent": 7,
            "chars": chars,
        })

    font_dir = ASSETS / "font"
    font_dir.mkdir(parents=True, exist_ok=True)
    (font_dir / "emoji.json").write_text(
        json.dumps({"providers": providers}, ensure_ascii=True, indent=1) + "\n", encoding="utf-8")

    # Shortcodes: emojibase keys carry FE0F where Twemoji's file names may not, so match both ways.
    by_stripped = {strip_fe0f(s): s for s in seqs}
    sc_map: dict[str, str] = {}
    for hexcode, codes in sorted(shortcodes.items()):
        target = hexcode if hexcode in emoji_map else by_stripped.get(strip_fe0f(hexcode))
        if target is None:
            continue
        for code in codes:
            sc_map.setdefault(code.lower(), target)

    emoji_dir = ASSETS / "emoji"
    emoji_dir.mkdir(parents=True, exist_ok=True)
    index = {
        "_comment": f"GENERATED by scripts/emoji/build-emoji-font.py from Twemoji {TWEMOJI_VERSION} "
                    f"(CC-BY 4.0) and emojibase-data {EMOJIBASE_VERSION} shortcodes. Do not edit by hand.",
        "font": "dungeontrain:emoji",
        "emoji": emoji_map,
        "textDefault": sorted(k for k in emoji_map if k.lstrip("0") in {t.lstrip("0") for t in text_default}),
        "shortcodes": dict(sorted(sc_map.items())),
    }
    (emoji_dir / "index.json").write_text(json.dumps(index, ensure_ascii=True, separators=(",", ":")) + "\n",
                                          encoding="utf-8")
    (emoji_dir / "LICENSE-TWEMOJI.txt").write_text(
        f"Emoji graphics: Twemoji {TWEMOJI_VERSION}, Copyright 2019 Twitter, Inc and other contributors,\n"
        "https://github.com/jdecked/twemoji — licensed under CC-BY 4.0\n"
        "(https://creativecommons.org/licenses/by/4.0/). Resized to 32x32 and packed into font sheets.\n\n"
        + license_text, encoding="utf-8")
    print(f"wrote {len(providers)} sheets, {len(emoji_map)} emoji, {len(sc_map)} shortcodes", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
