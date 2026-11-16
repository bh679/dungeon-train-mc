#!/usr/bin/env python3
"""Draw the 12x8 pixel language flags shown on translated Live chat lines, in the currency flags' style.

Stdlib only. Writes one PNG per flag plus the bitmap font that maps a character to each, and with --web <dir>
the same PNGs for the website (dp-relay public/dungeontrain/live/flags), so game and website show identical flags.
CODES' order is the font's order: client/live/LanguageFlags.java must list them the same way (a test pins it).

    python3 scripts/language-flags/make-flags.py [--web ~/Projects/dp-relay/public/dungeontrain/live/flags]
"""
import argparse
import importlib.util
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/dungeontrain"
OUT = ASSETS / "textures/font/language_flags"
FONT = ASSETS / "font/language_flags.json"
FIRST_GLYPH = 0xE100

spec = importlib.util.spec_from_file_location("currency_flags", ROOT / "scripts/currency/make-flags.py")
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
W, H, canvas, rect, dot, png = base.W, base.H, base.canvas, base.rect, base.dot, base.png


def across(*bands):
    """Horizontal bands: (height, colour) from the top."""
    px = canvas(bands[0][1])
    y = 0
    for height, colour in bands:
        rect(px, 0, y, W, y + height, colour)
        y += height
    return px


def down(*bands):
    """Vertical bands: (width, colour) from the left."""
    px = canvas(bands[0][1])
    x = 0
    for width, colour in bands:
        rect(px, x, 0, x + width, H, colour)
        x += width
    return px


def cross(field, *arms):
    """A Nordic cross: each arm (thickness, colour), widest first."""
    px = canvas(field)
    for thick, colour in arms:
        rect(px, 4 - thick // 2, 0, 4 - thick // 2 + thick, H, colour)
        rect(px, 0, 4 - thick // 2, W, 4 - thick // 2 + thick, colour)
    return px


def pt():
    px = down((5, "#006600"), (7, "#ff0000"))
    rect(px, 4, 3, 6, 5, "#ffcc00")
    return px


def vn():
    px = canvas("#da251d")
    for x, y in ((6, 2), (5, 3), (6, 3), (7, 3), (4, 4), (5, 4), (6, 4), (7, 4), (8, 4), (5, 5), (7, 5)):
        dot(px, x, y, "#ffff00")
    return px


def tw():
    px = canvas("#fe0000")
    rect(px, 0, 0, 6, 4, "#000095")
    rect(px, 2, 1, 4, 3, "#ffffff")
    return px


def my():
    px = canvas("#ffffff")
    for y in range(0, H, 2):
        rect(px, 0, y, W, y + 1, "#cc0001")
    rect(px, 0, 0, 6, 4, "#010066")
    rect(px, 2, 1, 4, 3, "#ffcc00")
    return px


def ph():
    px = across((4, "#0038a8"), (4, "#ce1126"))
    for y, width in enumerate((1, 2, 3, 4, 4, 3, 2, 1)):
        rect(px, 0, y, width, y + 1, "#ffffff")
    dot(px, 1, 3, "#fcd116")
    dot(px, 1, 4, "#fcd116")
    return px


def sa():
    px = canvas("#006c35")
    rect(px, 2, 2, 10, 4, "#ffffff")  # the script, as a smudge
    rect(px, 3, 5, 9, 6, "#ffffff")   # the sword
    return px


def tr():
    px = canvas("#e30a17")
    rect(px, 3, 2, 6, 6, "#ffffff")
    rect(px, 4, 3, 6, 5, "#e30a17")   # the crescent
    dot(px, 7, 3, "#ffffff")
    dot(px, 7, 4, "#ffffff")
    dot(px, 8, 3, "#ffffff")
    dot(px, 8, 4, "#ffffff")
    return px


def cz():
    px = across((4, "#ffffff"), (4, "#d7141a"))
    for y, width in enumerate((1, 2, 3, 4, 4, 3, 2, 1)):
        rect(px, 0, y, width, y + 1, "#11457e")
    return px


def gr():
    px = canvas("#ffffff")
    for y in range(0, H, 2):
        rect(px, 0, y, W, y + 1, "#0d5eaf")
    rect(px, 0, 0, 5, 4, "#0d5eaf")
    rect(px, 2, 0, 3, 4, "#ffffff")
    rect(px, 0, 2, 5, 3, "#ffffff")
    return px


def il():
    px = canvas("#ffffff")
    rect(px, 0, 1, W, 2, "#0038b8")
    rect(px, 0, 6, W, 7, "#0038b8")
    for x, y in ((5, 3), (6, 3), (5, 4), (6, 4), (4, 3), (7, 4)):
        dot(px, x, y, "#0038b8")
    return px


# code → drawing. Order is the font's: never reorder, only add at the end (LanguageFlags.java mirrors it).
FLAGS = {
    "gb": base.gbp,
    "es": lambda: across((2, "#aa151b"), (4, "#f1bf00"), (2, "#aa151b")),
    "mx": base.mxn,
    "de": lambda: across((3, "#000000"), (2, "#dd0000"), (3, "#ffce00")),
    "fr": lambda: down((4, "#0055a4"), (4, "#ffffff"), (4, "#ef4135")),
    "it": lambda: down((4, "#009246"), (4, "#ffffff"), (4, "#ce2b37")),
    "nl": lambda: across((3, "#ae1c28"), (2, "#ffffff"), (3, "#21468b")),
    "pl": base.pln,
    "pt": pt,
    "br": base.brl,
    "ro": lambda: down((4, "#002b7f"), (4, "#fcd116"), (4, "#ce1126")),
    "ru": lambda: across((3, "#ffffff"), (2, "#0039a6"), (3, "#d52b1e")),
    "ua": lambda: across((4, "#0057b7"), (4, "#ffd700")),
    "th": lambda: across((1, "#a51931"), (1, "#f4f5f8"), (4, "#2d2a4a"), (1, "#f4f5f8"), (1, "#a51931")),
    "vn": vn,
    "cn": base.cny,
    "tw": tw,
    "jp": base.jpy,
    "kr": base.krw,
    "id": lambda: across((4, "#ff0000"), (4, "#ffffff")),
    "my": my,
    "ph": ph,
    "sa": sa,
    "tr": tr,
    "in": base.inr,
    "se": lambda: cross("#006aa7", (2, "#fecc00")),
    "no": lambda: cross("#ba0c2f", (4, "#ffffff"), (2, "#00205b")),
    "dk": lambda: cross("#c8102e", (2, "#ffffff")),
    "fi": lambda: cross("#ffffff", (2, "#002f6c")),
    "cz": cz,
    "hu": lambda: across((3, "#ce2939"), (2, "#ffffff"), (3, "#477050")),
    "gr": gr,
    "il": il,
    "bg": lambda: across((3, "#ffffff"), (2, "#00966e"), (3, "#d62612")),
}


def font():
    providers = []
    for i, code in enumerate(FLAGS):
        providers.append({
            "type": "bitmap",
            "file": f"dungeontrain:font/language_flags/{code}.png",
            "ascent": 7,
            "height": 8,
            "chars": [chr(FIRST_GLYPH + i)],
        })
    return json.dumps({"providers": providers}, indent=2, ensure_ascii=True) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--web", type=pathlib.Path, help="also write the flags here (the website's Live page)")
    args = ap.parse_args()
    for out in [OUT] + ([args.web] if args.web else []):
        out.mkdir(parents=True, exist_ok=True)
        for code, draw in FLAGS.items():
            (out / f"{code}.png").write_bytes(png(draw()))
        print(f"wrote {len(FLAGS)} flags to {out}")
    FONT.write_text(font(), encoding="ascii")
    print(f"wrote {FONT}")


if __name__ == "__main__":
    main()
