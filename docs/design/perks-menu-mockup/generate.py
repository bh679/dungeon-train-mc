#!/usr/bin/env python3
"""Draw the perks menu mockups as SVGs, in GUI pixels, using the web page's numbers (perks-menu.md section 1).

A synthetic ten-class tree in the editor's 800 x 800 space stands in for the published one. Run from
anywhere: python3 docs/design/perks-menu-mockup/generate.py
"""
from __future__ import annotations

import math
import random
from pathlib import Path

OUT = Path(__file__).parent
CX = CY = 400.0
CLASSES = [("Archivist", "#c9a7e0"), ("Lover", "#f08aa8"), ("Warlock", "#9f7fe0"), ("Knight", "#d9534f"),
           ("Tank", "#e8956a"), ("Crafter", "#c7b08a"), ("Speedster", "#7fb4f0"), ("Builder", "#b0b8c8"),
           ("Hoarder", "#8fc97a"), ("Trader", "#e0c070")]
CORE = {1: 3.8, 2: 5.5, 3: 6.5}
HALO = {1: 7.0, 2: 9.5, 3: 11.0}
FLOOR_CORE = {1: 2.5, 2: 3.5, 3: 4.5}
BG, FG, GOLD, MUTED = "#070b16", "#e8e4d6", "#e4b95a", "#8e94a8"


def tree():
    """Ten classes, four perks each on three rings, plus a few chords. Deterministic."""
    perks = {"root": dict(cls=None, name="The Engine", tier=3, x=CX, y=CY)}
    links = []
    rng = random.Random(7)
    sector = 2 * math.pi / len(CLASSES)
    for ci, (name, col) in enumerate(CLASSES):
        base = -math.pi / 2 + ci * sector
        ids = []
        for k, (ring, tier, da) in enumerate([(105, 1, 0), (215, 2, -0.16), (215, 2, 0.16), (325, 3, 0)]):
            pid = f"c{ci}p{k}"
            a = base + da
            r = ring + rng.choice((-14, 0, 14))
            perks[pid] = dict(cls=ci, name=["Stat", "Trick", "Knack", "Capstone"][k], tier=tier,
                              x=CX + r * math.cos(a), y=CY + r * math.sin(a))
            ids.append(pid)
        links += [("root", ids[0], "req"), (ids[0], ids[1], "req"), (ids[0], ids[2], "req"), (ids[1], ids[3], "req"),
                  (ids[2], ids[3], "req")]
    for a, b in [(0, 1), (2, 3), (4, 6), (5, 8), (7, 9), (1, 9)]:
        links.append((f"c{a}p2", f"c{b}p1", "cross"))
    return perks, links


def states(mode):
    """Perk id -> (state, points). 'fresh' = nothing bought; 'midrun' = Tank path active, neighbours reachable."""
    s = {"root": ("active", 0)}
    if mode == "midrun":
        s.update({"c4p0": ("active", 3), "c4p1": ("active", 1), "c4p2": ("unlocked", 0), "c6p0": ("unlocked", 0)})
    return s


def reachable(pid, perks, links, st):
    if pid in st and st[pid][0] != "locked":
        return True
    for a, b, kind in links:
        other = b if a == pid else a if b == pid else None
        if other and st.get(other, ("locked", 0))[0] != "locked":
            return True
    return False


def strong(hex_):
    r, g, b = (int(hex_[i:i + 2], 16) for i in (1, 3, 5))
    return "#%02x%02x%02x" % tuple(max(0, int(v * 0.8)) for v in (r, g, b))


def draw(w, h, mode, label, selected=None, debris=False):
    perks, links = tree()
    st = states(mode)
    header = 24
    zoom = (h - header) / 800.0
    ox, oy = w / 2 - CX * zoom, header + (h - header) / 2 - CY * zoom

    def S(x, y):
        return ox + x * zoom, oy + y * zoom

    col = lambda p: CLASSES[p["cls"]][1] if p["cls"] is not None else GOLD
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{w * 2}" height="{h * 2}" viewBox="0 0 {w} {h}" '
           f'font-family="monospace" shape-rendering="geometricPrecision">',
           f'<rect width="{w}" height="{h}" fill="{BG}"/>',
           f'<radialGradient id="glow"><stop offset="0" stop-color="{GOLD}" stop-opacity="0.07"/><stop offset="1" stop-color="{GOLD}" stop-opacity="0"/></radialGradient>',
           f'<circle cx="{w / 2}" cy="{header + (h - header) / 2}" r="{(h - header) * 0.45}" fill="url(#glow)"/>']
    rng = random.Random(3)
    for _ in range(140):
        out.append(f'<circle cx="{rng.uniform(0, w):.1f}" cy="{rng.uniform(header, h):.1f}" r="{rng.uniform(0.2, 0.9):.2f}" fill="{FG}" opacity="{rng.uniform(0.15, 0.65):.2f}"/>')

    def state(pid):
        s, pts = st.get(pid, ("locked", 0))
        if s == "locked" and reachable(pid, perks, links, st):
            s = "reachable"
        return s, pts

    # links
    for a, b, kind in links:
        A, B = perks[a], perks[b]
        sa, sb = state(a)[0], state(b)[0]
        locked = "locked" in (sa, sb) or "reachable" in (sa, sb)
        both_active = sa == "active" and sb == "active"
        x1, y1 = S(A["x"], A["y"]); x2, y2 = S(B["x"], B["y"])
        if kind == "cross":
            mx, my = (A["x"] + B["x"]) / 2, (A["y"] + B["y"]) / 2
            cx, cy = S(CX + (mx - CX) * 1.18, CY + (my - CY) * 1.18)
            stroke = MUTED if locked else GOLD
            op = 0.18 if locked else 0.5
            out.append(f'<path d="M{x1:.1f},{y1:.1f} Q{cx:.1f},{cy:.1f} {x2:.1f},{y2:.1f}" fill="none" stroke="{stroke}" stroke-opacity="{op}" stroke-width="1" stroke-dasharray="2 2"/>')
        else:
            gid = f"g{a}{b}"
            if locked:
                out.append(f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" stroke="{MUTED}" stroke-opacity="0.18" stroke-width="1"/>')
            else:
                out.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}"><stop offset="0" stop-color="{col(A)}"/><stop offset="1" stop-color="{col(B)}"/></linearGradient>')
                wd, op = (2.8, 0.95) if both_active else (max(1.0, 1.2 * zoom), 0.55)
                out.append(f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" stroke="url(#{gid})" stroke-opacity="{op}" stroke-width="{wd}"/>')
    # class titles
    tsize = 9 if w >= 640 else 7
    for ci, (name, c) in enumerate(CLASSES):
        ps = [p for p in perks.values() if p["cls"] == ci]
        first = perks[f"c{ci}p0"]
        a = math.atan2(first["y"] - CY, first["x"] - CX)
        r = max(math.hypot(p["x"] - CX, p["y"] - CY) for p in ps) + 40
        x, y = S(CX + r * math.cos(a), CY + r * math.sin(a))
        x = min(max(x, 30), w - 30)
        out.append(f'<text x="{x:.1f}" y="{y:.1f}" fill="{c}" font-size="{tsize}" text-anchor="middle" dominant-baseline="middle" letter-spacing="1" style="text-transform:uppercase">{name.upper()}</text>')
    # stars
    for pid, p in perks.items():
        s, pts = state(pid)
        root = pid == "root"
        t = p["tier"]
        x, y = S(p["x"], p["y"])
        c = col(p)
        if root:
            core, halo = max(14 * zoom, 9), max(26 * zoom, 16)
        else:
            core = max(CORE[t] * zoom, FLOOR_CORE[t])
            halo = core * HALO[t] / CORE[t]
        grey = s == "locked"
        op = 0.35 if grey else 0.55 if s == "reachable" else 1
        fill = "#7a7f8c" if grey else (strong(c) if t == 3 and not root else c)
        g = [f'<g opacity="{op}">']
        halo_col = GOLD if pid == selected else fill
        g.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{halo:.1f}" fill="none" stroke="{halo_col}" stroke-opacity="{1 if pid == selected else 0.35}" stroke-width="1"/>')
        if pid == selected:
            g.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{halo + 4:.1f}" fill="none" stroke="{GOLD}" stroke-opacity="0.5" stroke-width="1"/>')
        if s in ("unlocked", "reachable"):
            g.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{core:.1f}" fill="{BG}" stroke="{fill}" stroke-width="1.4"/>')
        else:
            g.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{core:.1f}" fill="{fill}"/>')
        if root or t == 3:
            g.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{(5 if root else 2) * max(zoom, 0.6):.1f}" fill="{BG}"/>')
        if s == "active" and not root and pts > 0:
            R = halo + 1.5
            gap = 0.3
            for i in range(min(10, pts)):
                a0 = -math.pi / 2 + i * 2 * math.pi / 10 + gap / 2
                a1 = -math.pi / 2 + (i + 1) * 2 * math.pi / 10 - gap / 2
                g.append(f'<path d="M{x + R * math.cos(a0):.1f},{y + R * math.sin(a0):.1f} A{R:.1f},{R:.1f} 0 0 1 {x + R * math.cos(a1):.1f},{y + R * math.sin(a1):.1f}" fill="none" stroke="{c}" stroke-width="2.6"/>')
        if debris and s in ("unlocked", "active") and not root and pts < 10:
            g.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{halo + 3:.1f}" fill="none" stroke="{GOLD}" stroke-opacity="0.45" stroke-width="1" stroke-dasharray="1 2"/>')
        g.append("</g>")
        out += g
        # names only for the selected star and its neighbours (zoom < 1)
        near = selected and (pid == selected or any(selected in (a, b) and pid in (a, b) for a, b, _ in links))
        if near and not root:
            out.append(f'<text x="{x:.1f}" y="{y + halo + 8:.1f}" fill="{FG}" font-size="7" text-anchor="middle" dominant-baseline="middle" stroke="{BG}" stroke-width="2" paint-order="stroke">{p["name"]}</text>')
    # header
    fs = 9 if w >= 640 else 7
    out.append(f'<text x="8" y="14" fill="{GOLD}" font-size="{fs}" dominant-baseline="middle">Perks</text>')
    if debris:
        out.append(f'<text x="{8 + 6 * fs}" y="14" fill="{GOLD}" font-size="{fs}" dominant-baseline="middle">1 point to spend</text>')
    else:
        up = 7 if mode == "midrun" else 2
        out.append(f'<text x="{8 + 6 * fs}" y="14" fill="{FG}" font-size="{fs}" dominant-baseline="middle">* {up} unlock points</text>')
        if mode == "midrun":
            out.append(f'<text x="{8 + 26 * fs}" y="14" fill="{FG}" font-size="{fs}" dominant-baseline="middle">o 1 point</text>')
    out.append(f'<text x="{w - 8}" y="14" fill="{MUTED}" font-size="{fs}" text-anchor="end" dominant-baseline="middle">x</text>')
    out.append(f'<text x="{w / 2}" y="{h - 4}" fill="{MUTED}" font-size="6" text-anchor="middle">{label}</text>')
    # card
    if selected:
        p = perks[selected]
        s, pts = state(selected)
        sx, sy = S(p["x"], p["y"])
        cw, ch = (150, 92) if w >= 640 else (120, 86)
        x = sx + 16 if sx + 16 + cw < w - 8 else sx - 16 - cw
        y = min(max(sy + 8, header + 4), h - ch - 8)
        c = col(p)
        cls = CLASSES[p["cls"]][0].upper()
        state_line = {"active": f"Active. {pts} points.", "unlocked": "Unlocked. 0 points.",
                      "reachable": "Locked. 7 unlock points.", "locked": "Locked. Needs Stat."}[s]
        btn = "Add point" if s in ("active", "unlocked") else ("Unlock" if s == "reachable" else None)
        out.append(f'<g font-size="7"><rect x="{x}" y="{y}" width="{cw}" height="{ch}" fill="#111a30" fill-opacity="0.94" stroke="#2a3350"/>')
        out.append(f'<text x="{x + 6}" y="{y + 11}" fill="{c}" font-size="9">{p["name"]}</text><text x="{x + cw - 6}" y="{y + 11}" fill="{MUTED}" text-anchor="end">x</text>')
        out.append(f'<text x="{x + 6}" y="{y + 21}" fill="{MUTED}" font-size="6" letter-spacing="1">{cls}</text>')
        out.append(f'<text x="{x + 6}" y="{y + 32}" fill="{FG}">Tier {p["tier"]}. Cost {[0, 1, 5, 10][p["tier"]]}.</text>')
        out.append(f'<text x="{x + 6}" y="{y + 42}" fill="{FG}">More max health.</text>')
        out.append(f'<text x="{x + 6}" y="{y + 56}" fill="{FG}">{state_line}</text>')
        if btn:
            out.append(f'<rect x="{x + 6}" y="{y + 62}" width="{cw - 12}" height="14" fill="#1b2340" stroke="{GOLD}"/>'
                       f'<text x="{x + cw / 2}" y="{y + 69}" fill="{GOLD}" text-anchor="middle" dominant-baseline="middle">{btn}</text>')
        out.append(f'<text x="{x + 6}" y="{y + ch - 6}" fill="{MUTED}" font-size="6">Links: The Engine, Trick, Knack</text></g>')
    out.append("</svg>")
    return "\n".join(out)


def main():
    frames = [
        ("1080p-scale2-fresh.svg", 960, 540, "fresh", "1920x1080, GUI scale 2 (960x540). New save: everything locked, Engine-linked perks reachable.", None, False),
        ("1080p-scale2-midrun.svg", 960, 540, "midrun", "1920x1080, GUI scale 2. Tank path active, Health selected with 3 points.", "c4p0", False),
        ("720p-scale3-midrun.svg", 426, 240, "midrun", "1280x720, GUI scale 3 (426x240). Same state, floor-sized stars, titles at 0.75.", "c4p0", False),
        ("1080p-scale2-debris.svg", 960, 540, "midrun", "Opened by eating cosmic debris: header says 1 point to spend; stars that can take it are marked.", None, True),
    ]
    for name, w, h, mode, label, sel, debris in frames:
        (OUT / name).write_text(draw(w, h, mode, label, sel, debris))
        print("wrote", name)


if __name__ == "__main__":
    main()
