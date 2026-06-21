#!/usr/bin/env python3
"""Generate the static "codex" open-book SVG spreads for the Modrinth page.

Three images:
  codex-1.svg  "What is the Dungeon Train?"        — single book, question on a
                                                     title page (left) + answer (right)
  codex-2.svg  "What does it want from me?" +
               "Who else rides the train?"          — one book, two questions; each
                                                     question is a title ABOVE its page,
                                                     one page each
  codex-3.svg  "Where is it going?"                 — one tall image holding TWO books
                                                     (the long answer paginated across
                                                     four pages)

Hostable on GitHub Pages (*.github.io is allow-listed by Modrinth's sanitizer and
serves image/svg+xml). Run:  python3 build-codex.py
"""

import glob
import os

SERIF = "Georgia, 'Times New Roman', serif"
DISCORD_LINE = 'They speak of "discord linking."'
MAX_LINES = 16          # answer lines per page
DY = 15                 # answer line spacing
LEFT_X = 66             # verse left margin on the left page (more padding)
RIGHT_X = 366           # verse left margin on the right page

Q1_TITLE = ["What is the", "Dungeon Train?"]
Q1_ANSWER = [
    "The train is a curious thing.",
    "Some call it multi-dimensional.",
    "A carriage adrift in liminal space and time.",
    "Others, a dungeon crawler with a view.",
    "An escape from reality.",
    "A madman once claimed it a physics mod.",
    "Sable-built. Endless.",
    "Dreamed up by an award-winning hand.",
    "Does the answer change the ride?",
]

Q2_Q = "What does it want from me?"
Q2_ANSWER = [
    "It wants, I think.",
    "Some say its loot —",
    "infinite, and never the same twice.",
    "Others say a lesson.",
    "A moral, drip-fed",
    "between the characters,",
    "the lore,",
    "the ever-harder enemies.",
    "Me?",
    "I think it only wants",
    "the right questions.",
]

Q3_Q = "Who else rides the train?"
Q3_ANSWER = [
    "Others.",
    "Or things shaped like them.",
    "They wear the faces of people —",
    "not quite.",
    "Some are kind.",
    "Some would see you gone.",
    "Some never know you were there at all.",
    "You will not meet the same one twice.",
    "",
    "The old riders call speak of echoes.",
    "Echoes of those who passed before.",
    "Echoes of strangers long gone.",
    "And sometimes —",
    "an echo of yourself.",
    "I try not to look too closely myself.",
]

Q4_TITLE = ["Where is it", "going?"]
Q4_ANSWER = [
    "Depends who you ask.",
    "Some speak of loved ones.",
    "Other treasure.",
    "Many simply green fields. Lush forests.",
    "Endless desert. The dark depths.",
    "Fewer speak of fire. Of voids without end.",
    "A Kingdom of Candy.",
    "Beyond the worlds boarder.",
    "Escape.",
    "",
    "Fewer still speak of the Creator —",
    "The request he makes,",
    "asked only in death.",
    "They say his passion will not let him rest.",
    "That he listens to anyone",
    "with a word to offer.",
    "",
    "Some practice ancient rituals",
    "in attempt to reach him.",
    DISCORD_LINE,
    "A summoning, of sorts.",
    "A gathering place",
    "beyond the train,",
    "where the living and the Creator",
    "trade words.",
    "",
    "They say he answers there.",
    "That he takes every offering —",
    "every idea, every grievance, every prayer —",
    "and folds it back into the train.",
    "",
    "If you have a word to give,",
    "speak it.",
    "",
    "Me?",
    "I stopped believing in the destination.",
    "Only the journey remains.",
    "Perhaps that was the point.",
]

ROMAN = ["", "i", "ii", "iii", "iv"]


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def paginate(lines):
    return [lines[i:i + MAX_LINES] for i in range(0, len(lines), MAX_LINES)] or [[]]


def cover(y, h):
    return f'<rect x="30" y="{y}" width="620" height="{h}" rx="10" fill="#241a36"/>'


def pages(page_top, page_h):
    return (
        f'<rect x="44" y="{page_top}" width="292" height="{page_h}" rx="3" fill="#efe6cf"/>'
        f'<rect x="344" y="{page_top}" width="292" height="{page_h}" rx="3" fill="#efe6cf"/>'
        f'<rect x="336" y="{page_top}" width="8" height="{page_h}" fill="#1c1530"/>'
        f'<rect x="318" y="{page_top}" width="18" height="{page_h}" fill="#000000" opacity="0.08"/>'
        f'<rect x="344" y="{page_top}" width="18" height="{page_h}" fill="#000000" opacity="0.08"/>'
    )


def answer(lines, x, y0):
    out = []
    y = y0
    for line in lines:
        if line == DISCORD_LINE:
            out.append(
                f'<text x="{x}" y="{y}" font-family="{SERIF}" font-size="12" fill="#4a3d28">'
                f'They speak of "<tspan fill="#2b50c8" text-decoration="underline">discord linking.</tspan>"</text>'
            )
        elif line:
            out.append(f'<text x="{x}" y="{y}" font-family="{SERIF}" font-size="12" fill="#4a3d28">{esc(line)}</text>')
        y += DY
    return "\n".join(out)


def flourish(cx, y):
    return (
        f'<line x1="{cx-70}" y1="{y}" x2="{cx-12}" y2="{y}" stroke="#b8a87e" stroke-width="1"/>'
        f'<rect x="{cx-4}" y="{y-4}" width="8" height="8" fill="#b8a87e" transform="rotate(45 {cx} {y})"/>'
        f'<line x1="{cx+12}" y1="{y}" x2="{cx+70}" y2="{y}" stroke="#b8a87e" stroke-width="1"/>'
    )


def title_page(title_lines, cx, page_top, page_h):
    cy = page_top + page_h // 2
    out = []
    if len(title_lines) == 2:
        ys = [cy - 16, cy + 12]
    else:
        ys = [cy]
    for line, y in zip(title_lines, ys):
        out.append(
            f'<text x="{cx}" y="{y}" text-anchor="middle" font-family="{SERIF}" '
            f'font-size="22" font-weight="500" fill="#3b3020">{esc(line)}</text>'
        )
    out.append(flourish(cx, cy + 42))
    return "\n".join(out)


def page_heading(text, cx, y):
    """Question rendered ON the parchment page (ink), with a rule beneath."""
    return (
        f'<text x="{cx}" y="{y}" text-anchor="middle" font-family="{SERIF}" '
        f'font-size="15" font-weight="500" fill="#3b3020">{esc(text)}</text>'
        f'<line x1="{cx-62}" y1="{y+10}" x2="{cx+62}" y2="{y+10}" stroke="#c9bb96" stroke-width="1"/>'
    )


def page_num(roman, side, y):
    if not roman:
        return ""
    if side == "left":
        return f'<text x="60" y="{y}" font-family="{SERIF}" font-size="11" font-style="italic" fill="#8a7a52">{roman}</text>'
    return f'<text x="620" y="{y}" text-anchor="end" font-family="{SERIF}" font-size="11" font-style="italic" fill="#8a7a52">{roman}</text>'


def svg(width, height, title, body):
    return (
        f'<svg width="100%" viewBox="0 0 {width} {height}" role="img" xmlns="http://www.w3.org/2000/svg">\n'
        f'<title>{esc(title)}</title>\n'
        f'<desc>An open book on a dark surface, ink verse on parchment pages.</desc>\n'
        f'{body}\n</svg>'
    )


def img_q1():
    cy, ch, pt, ph = 40, 300, 52, 276
    body = "\n".join([
        cover(cy, ch),
        pages(pt, ph),
        title_page(Q1_TITLE, 190, pt, ph),
        answer(Q1_ANSWER, RIGHT_X, pt + 30),
    ])
    return svg(680, 360, "What is the Dungeon Train?", body)


def img_q2q3():
    cy, ch, pt, ph = 40, 300, 52, 276
    body = "\n".join([
        cover(cy, ch),
        pages(pt, ph),
        page_heading(Q2_Q, 190, pt + 24),
        page_heading(Q3_Q, 490, pt + 24),
        answer(Q2_ANSWER, LEFT_X, pt + 54),
        answer(Q3_ANSWER, RIGHT_X, pt + 54),
    ])
    return svg(680, 360, "What does it want from me? / Who else rides the train?", body)


def img_q4a():
    cy, ch, pt, ph = 40, 300, 52, 276
    pg = paginate(Q4_ANSWER)            # [16, 16, 6]
    body = "\n".join([
        cover(cy, ch),
        pages(pt, ph),
        title_page(Q4_TITLE, 190, pt, ph),
        answer(pg[0], RIGHT_X, pt + 30),
        page_num(ROMAN[1], "right", pt + ph - 12),
    ])
    return svg(680, 360, "Where is it going?", body)


def img_q4b():
    cy, ch, pt, ph = 40, 300, 52, 276
    pg = paginate(Q4_ANSWER)
    pg += [[]] * (3 - len(pg))
    body = "\n".join([
        cover(cy, ch),
        pages(pt, ph),
        answer(pg[1], LEFT_X, pt + 30),
        answer(pg[2], RIGHT_X, pt + 30),
        page_num(ROMAN[2], "left", pt + ph - 12),
        page_num(ROMAN[3], "right", pt + ph - 12),
    ])
    return svg(680, 360, "Where is it going? (continued)", body)


def build():
    here = os.path.dirname(os.path.abspath(__file__))
    for old in glob.glob(os.path.join(here, "codex-*.svg")):
        os.remove(old)
    outputs = [
        ("codex-1.svg", img_q1()),
        ("codex-2.svg", img_q2q3()),
        ("codex-3.svg", img_q4a()),
        ("codex-4.svg", img_q4b()),
    ]
    for fname, content in outputs:
        with open(os.path.join(here, fname), "w") as f:
            f.write(content)
    print(f"Wrote {len(outputs)} images: " + ", ".join(f for f, _ in outputs))


if __name__ == "__main__":
    build()
