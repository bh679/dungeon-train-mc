#!/usr/bin/env python3
"""Draws the Live chat source icons: one 8x8 glyph each, in a strip, for the bitmap font
assets/dungeontrain/font/live_chat_icons.json (U+E000..U+E00A, in this order; append new ones, never reorder).

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
    "K": (83, 252, 24, 255),     # Kick green
    "b": (10, 10, 10, 255),      # Kick's black
    "c": (190, 190, 190, 255),   # bubble grey
    "Y": (255, 0, 51, 255),      # YouTube red
    "n": (37, 244, 238, 255),    # TikTok cyan
    "m": (254, 44, 85, 255),     # TikTok pink
    "F": (24, 119, 242, 255),    # Facebook blue
    "1": (131, 58, 180, 255),    # Instagram purple
    "2": (225, 48, 108, 255),    # Instagram pink
    "3": (247, 119, 55, 255),    # Instagram orange
    "4": (252, 175, 69, 255),    # Instagram yellow
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

# U+E005 Kick (its block "K" on Kick green)
KICK = [
    "KKKKKKKK",
    "KbbKKbbK",
    "KbbKbbKK",
    "KbbbbKKK",
    "KbbbbKKK",
    "KbbKbbKK",
    "KbbKKbbK",
    "KKKKKKKK",
]
# U+E006 chat bubble (a room this client doesn't know yet)
BUBBLE = [
    "........",
    ".cccccc.",
    "cccccccc",
    "ccWWWWcc",
    "cccccccc",
    ".cccccc.",
    ".cc.....",
    ".c......",
]

# U+E007 YouTube (the white play button on red)
YOUTUBE = [
    "........",
    ".YYYYYY.",
    "YYYWYYYY",
    "YYYWWYYY",
    "YYYWWWYY",
    "YYYWWYYY",
    "YYYWYYYY",
    ".YYYYYY.",
]
# U+E008 TikTok (the note, with its cyan and pink edges, on black)
TIKTOK = [
    "bbbbbbbb",
    "bbbbnWbb",
    "bbbbnWmb",
    "bbbbnWbm",
    "bbnWnWbb",
    "bnWbmWbb",
    "bbnWWmbb",
    "bbbbbbbb",
]
# U+E009 Facebook (the white "f" on blue)
FACEBOOK = [
    ".FFFFFF.",
    "FFFFWWWF",
    "FFFFWFFF",
    "FFWWWWWF",
    "FFFFWFFF",
    "FFFFWFFF",
    "FFFFWFFF",
    ".FFFWFF.",
]
# U+E00A Instagram (the camera outline on its purple-to-yellow gradient)
INSTAGRAM = [
    ".111122.",
    "1WWWWWW2",
    "1W1222W2",
    "1W2WW2W3",
    "2W2WW3W3",
    "2W233WW3",
    "2WWWWWW4",
    ".223344.",
]

GLYPHS = [EYE, TV, GLOBE, DISCORD, TWITCH, KICK, BUBBLE, YOUTUBE, TIKTOK, FACEBOOK, INSTAGRAM]


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
