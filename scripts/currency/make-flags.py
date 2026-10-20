#!/usr/bin/env python3
"""Draw the 12x8 pixel flag sprites for the currency button (client/support/currency).

Stdlib only. Writes one PNG per display currency into the mod's GUI sprite atlas and, with
--web <dir>, the same files for the website support page (dp-relay public/dungeontrain/support/flags),
so both show identical flags. Flags are simplified to read at 12x8; re-run after editing a drawing.

    python3 scripts/currency/make-flags.py [--web ~/Projects/dp-relay/public/dungeontrain/support/flags]
"""
import argparse
import pathlib
import struct
import zlib

W, H = 12, 8
OUT = pathlib.Path(__file__).resolve().parents[2] / "src/main/resources/assets/dungeontrain/textures/gui/sprites/flag"


def hexrgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def canvas(c):
    return [[hexrgb(c) for _ in range(W)] for _ in range(H)]


def rect(px, x0, y0, x1, y1, c):
    for y in range(max(0, y0), min(H, y1)):
        for x in range(max(0, x0), min(W, x1)):
            px[y][x] = hexrgb(c)


def dot(px, x, y, c):
    rect(px, x, y, x + 1, y + 1, c)


CANTON = ("WbRRbW",
          "RRRRRR",
          "bWRRWb",
          "WbRRbW")
CANTON_COLOURS = {"W": "#ffffff", "R": "#c8102e", "b": "#012169"}


def union_jack(px):
    """The Union Jack, hand-drawn for a 6x4 canton."""
    for y, row in enumerate(CANTON):
        for x, ch in enumerate(row):
            dot(px, x, y, CANTON_COLOURS[ch])


def aud():
    px = canvas("#012169")
    union_jack(px)
    dot(px, 2, 6, "#ffffff")          # Commonwealth star
    for x, y in ((9, 1), (8, 3), (10, 4), (9, 6)):
        dot(px, x, y, "#ffffff")      # Southern Cross
    return px


def nzd():
    px = canvas("#012169")
    union_jack(px)
    for x, y in ((9, 1), (8, 3), (10, 4), (9, 6)):
        dot(px, x, y, "#c8102e")
    return px


def usd():
    px = canvas("#ffffff")
    for y in range(0, H, 2):
        rect(px, 0, y, W, y + 1, "#b22234")
    rect(px, 0, 0, 5, 4, "#3c3b6e")
    for x, y in ((1, 1), (3, 1), (2, 2), (1, 3), (3, 3)):
        dot(px, x, y, "#ffffff")
    return px


def eur():
    px = canvas("#003399")
    for x, y in ((4, 1), (7, 1), (3, 3), (8, 3), (3, 4), (8, 4), (4, 6), (7, 6)):
        dot(px, x, y, "#ffcc00")      # the ring of stars, thinned to read at 12x8
    return px


def gbp():
    px = canvas("#012169")
    for x in range(W):
        y = round(x * (H - 1) / (W - 1))
        dot(px, x, y, "#ffffff")
        dot(px, x, H - 1 - y, "#ffffff")
    rect(px, 0, 3, W, 5, "#ffffff")
    rect(px, 5, 0, 7, H, "#ffffff")
    rect(px, 0, 3, W, 5, "#c8102e")
    rect(px, 5, 0, 7, H, "#c8102e")
    return px


def cad():
    px = canvas("#ffffff")
    rect(px, 0, 0, 3, H, "#d52b1e")
    rect(px, 9, 0, W, H, "#d52b1e")
    for x, y in ((5, 2), (6, 2), (4, 3), (5, 3), (6, 3), (7, 3), (5, 4), (6, 4), (4, 5), (7, 5), (5, 5), (6, 5), (5, 6), (6, 6)):
        dot(px, x, y, "#d52b1e")      # maple leaf
    return px


def cny():
    px = canvas("#de2910")
    for x, y in ((1, 1), (2, 1), (3, 1), (2, 0), (2, 2), (1, 2), (3, 2)):
        dot(px, x, y, "#ffde00")      # big star
    for x, y in ((5, 0), (6, 1), (6, 3), (5, 4)):
        dot(px, x, y, "#ffde00")
    return px


def jpy():
    px = canvas("#ffffff")
    rect(px, 5, 2, 7, 6, "#bc002d")
    rect(px, 4, 3, 8, 5, "#bc002d")
    return px


def krw():
    px = canvas("#ffffff")
    rect(px, 5, 2, 7, 4, "#cd2e3a")
    rect(px, 4, 3, 5, 4, "#cd2e3a")
    rect(px, 5, 4, 7, 6, "#0047a0")
    rect(px, 7, 4, 8, 5, "#0047a0")
    for x, y in ((1, 1), (2, 2), (10, 1), (9, 2), (1, 6), (2, 5), (10, 6), (9, 5)):
        dot(px, x, y, "#000000")      # trigrams
    return px


def brl():
    px = canvas("#009c3b")
    for y in range(1, H - 1):
        half = 5 - abs(y * 2 - (H - 1)) * 5 // (H - 2)
        rect(px, 6 - half, y, 6 + half, y + 1, "#ffdf00")
    rect(px, 5, 3, 7, 5, "#002776")
    rect(px, 4, 3, 8, 4, "#002776")
    rect(px, 4, 4, 8, 5, "#002776")
    return px


def mxn():
    px = canvas("#ffffff")
    rect(px, 0, 0, 4, H, "#006847")
    rect(px, 8, 0, W, H, "#ce1126")
    rect(px, 5, 3, 7, 5, "#8c6a3b")   # the eagle, as a smudge
    return px


def pln():
    px = canvas("#ffffff")
    rect(px, 0, 4, W, H, "#dc143c")
    return px


def inr():
    px = canvas("#ffffff")
    rect(px, 0, 0, W, 3, "#ff9933")
    rect(px, 0, 5, W, H, "#138808")
    rect(px, 5, 3, 7, 5, "#000080")
    return px


FLAGS = {
    "aud": aud, "usd": usd, "eur": eur, "gbp": gbp, "cad": cad, "nzd": nzd, "cny": cny,
    "jpy": jpy, "krw": krw, "brl": brl, "mxn": mxn, "pln": pln, "inr": inr,
}


def png(px):
    raw = b"".join(b"\x00" + bytes(c for p in row for c in p) for row in px)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--web", type=pathlib.Path, help="also write the flags here (website support page)")
    args = ap.parse_args()
    outs = [OUT] + ([args.web] if args.web else [])
    for out in outs:
        out.mkdir(parents=True, exist_ok=True)
        for code, draw in FLAGS.items():
            (out / f"{code}.png").write_bytes(png(draw()))
        print(f"wrote {len(FLAGS)} flags to {out}")


if __name__ == "__main__":
    main()
