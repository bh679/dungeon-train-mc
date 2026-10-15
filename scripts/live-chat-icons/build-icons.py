#!/usr/bin/env python3
"""Draws the Live chat source icons: one 8x8 glyph each, in a strip, for the bitmap font
assets/dungeontrain/font/live_chat_icons.json (U+E000..U+E004, in this order).

Re-run after editing a glyph; commit the PNG. Needs Pillow.
"""
import sys
from pathlib import Path

from PIL import Image

OUT = Path(__file__).resolve().parents[2] / "src/main/resources/assets/dungeontrain/textures/font/live_chat_icons.png"

PALETTE = {
    ".": (0, 0, 0, 0),
    "W": (240, 240, 240, 255),   # white
    "g": (150, 150, 150, 255),   # grey
    "t": (40, 120, 110, 255),    # Live Eye lens teal
    "k": (15, 30, 30, 255),      # pupil
    "r": (225, 50, 50, 255),     # on-air red
    "s": (90, 170, 230, 255),    # TV screen blue
    "o": (50, 110, 210, 255),    # ocean
    "l": (90, 190, 80, 255),     # land
    "D": (88, 101, 242, 255),    # Discord blurple
    "P": (145, 70, 255, 255),    # Twitch purple
}

# U+E000 eye (the streamer, in the Live Eye)
EYE = [
    "........",
    "..WWWW..",
    ".WWttWW.",
    "WWtkktWW",
    "WWtkktWW",
    ".WWttWW.",
    "..WWWW..",
    "........",
]
# U+E001 TV (watching through a TV)
TV = [
    ".g...g..",
    "..g.g...",
    "gggggggg",
    "gssssssg",
    "gssWsssg",
    "gssssssg",
    "gggggggg",
    ".g....g.",
]
# U+E002 globe (the website)
GLOBE = [
    "..oooo..",
    ".olloooo",
    "olllooll",
    "ooloolll",
    "oooooolo",
    "oolloooo",
    ".ollloo.",
    "..oooo..",
]
# U+E003 Discord
DISCORD = [
    "........",
    ".DD..DD.",
    "DDDDDDDD",
    "DDWDDWDD",
    "DDWDDWDD",
    "DDDDDDDD",
    ".DD..DD.",
    "........",
]
# U+E004 Twitch
TWITCH = [
    ".PPPPPPP",
    "PWWWWWWP",
    "PWWPWPWP",
    "PWWPWPWP",
    "PWWWWWPP",
    "PPPWWPP.",
    "..PPP...",
    "..P.....",
]

GLYPHS = [EYE, TV, GLOBE, DISCORD, TWITCH]


def main() -> int:
    img = Image.new("RGBA", (8 * len(GLYPHS), 8))
    for i, glyph in enumerate(GLYPHS):
        assert len(glyph) == 8 and all(len(row) == 8 for row in glyph), f"glyph {i} is not 8x8"
        for y, row in enumerate(glyph):
            for x, ch in enumerate(row):
                img.putpixel((i * 8 + x, y), PALETTE[ch])
    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT)
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
