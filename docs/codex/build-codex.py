#!/usr/bin/env python3
"""Generate the static "rider's codex" open-book SVG spreads for the Modrinth page.

One question per chapter: the question sits on the left page, its verse answer on
the right page. Long answers paginate across extra spreads (continuation pages flow
left->right). Each spread is a self-contained static SVG, hostable on GitHub Pages
(*.github.io is allow-listed by Modrinth's sanitizer and serves image/svg+xml).

Run:  python3 build-codex.py   ->  writes codex-1.svg, codex-2.svg, ...
"""

import os

MAX_LINES = 16          # answer lines per page
LINE_DY = 15            # vertical spacing between answer lines
ANSWER_Y0 = 102         # first answer-line baseline
MARGIN = 10             # text inset from page edge
DISCORD_LINE = 'They speak of "discord linking."'

QUESTIONS = [
    {
        "title": ["What is the", "Dungeon Train?"],
        "answer": [
            "The train is a curious thing.",
            "Some call it multi-dimensional.",
            "A carriage adrift in liminal space and time.",
            "Others, a dungeon crawler with a view.",
            "An escape from reality.",
            "A madman once claimed it a physics mod.",
            "Sable-built. Endless.",
            "Dreamed up by an award-winning hand.",
            "Does the answer change the ride?",
        ],
    },
    {
        "title": ["What does it", "want from me?"],
        "answer": [
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
        ],
    },
    {
        "title": ["Who else rides", "the train?"],
        "answer": [
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
        ],
    },
    {
        "title": ["Where is it", "going?"],
        "answer": [
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
        ],
    },
]

SERIF = "Georgia, 'Times New Roman', serif"
ROMAN = ["", "i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x"]


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def paginate(lines):
    return [lines[i:i + MAX_LINES] for i in range(0, len(lines), MAX_LINES)] or [[]]


def answer_lines_svg(lines, text_x):
    out = []
    y = ANSWER_Y0
    for line in lines:
        if line == DISCORD_LINE:
            out.append(
                f'<text x="{text_x}" y="{y}" font-family="{SERIF}" font-size="12" fill="#4a3d28">'
                f'They speak of "'
                f'<tspan fill="#2b50c8" text-decoration="underline">discord linking.</tspan>"'
                f'</text>'
            )
        elif line:
            out.append(
                f'<text x="{text_x}" y="{y}" font-family="{SERIF}" font-size="12" fill="#4a3d28">{esc(line)}</text>'
            )
        y += LINE_DY
    return "\n".join(out)


def title_page_svg(title_lines):
    t = []
    cx = 190
    ys = [196, 224] if len(title_lines) == 2 else [210]
    for line, y in zip(title_lines, ys):
        t.append(
            f'<text x="{cx}" y="{y}" text-anchor="middle" font-family="{SERIF}" '
            f'font-size="22" font-weight="500" fill="#3b3020">{esc(line)}</text>'
        )
    t.append('<line x1="120" y1="252" x2="178" y2="252" stroke="#b8a87e" stroke-width="1"/>')
    t.append('<rect x="186" y="248" width="8" height="8" fill="#b8a87e" transform="rotate(45 190 252)"/>')
    t.append('<line x1="202" y1="252" x2="260" y2="252" stroke="#b8a87e" stroke-width="1"/>')
    return "\n".join(t)


def page_number_svg(roman, side):
    if not roman:
        return ""
    if side == "left":
        return (f'<text x="60" y="336" font-family="{SERIF}" font-size="11" '
                f'font-style="italic" fill="#8a7a52">{roman}</text>')
    return (f'<text x="620" y="336" text-anchor="end" font-family="{SERIF}" font-size="11" '
            f'font-style="italic" fill="#8a7a52">{roman}</text>')


def spread_svg(header, left_kind, left_data, right_lines, left_roman, right_roman):
    if left_kind == "title":
        left_content = title_page_svg(left_data)
    else:
        left_content = answer_lines_svg(left_data, 54)
    right_content = answer_lines_svg(right_lines, 354) if right_lines else ""
    return f'''<svg width="100%" viewBox="0 0 680 380" role="img" xmlns="http://www.w3.org/2000/svg">
<title>The rider's codex — {esc(header)}</title>
<desc>An open book on a dark surface. The left page is a chapter heading; the right page holds a verse answer.</desc>
<text x="340" y="40" text-anchor="middle" font-family="{SERIF}" font-size="15" font-style="italic" fill="#9a8fc4">~ the rider's codex ~</text>
<rect x="30" y="60" width="620" height="300" rx="10" fill="#241a36"/>
<rect x="44" y="72" width="292" height="276" rx="3" fill="#efe6cf"/>
<rect x="344" y="72" width="292" height="276" rx="3" fill="#efe6cf"/>
<rect x="336" y="72" width="8" height="276" fill="#1c1530"/>
<rect x="318" y="72" width="18" height="276" fill="#000000" opacity="0.08"/>
<rect x="344" y="72" width="18" height="276" fill="#000000" opacity="0.08"/>
{left_content}
{right_content}
{page_number_svg(left_roman, "left")}
{page_number_svg(right_roman, "right")}
</svg>'''


def build():
    here = os.path.dirname(os.path.abspath(__file__))
    spreads = []
    for q in QUESTIONS:
        header = " ".join(q["title"]).replace("  ", " ")
        pages = paginate(q["answer"])
        # First spread: title page + first answer page.
        spreads.append(dict(header=header, left_kind="title", left=q["title"],
                            right=pages[0], lr="", rr=ROMAN[1]))
        # Continuation spreads: answer pages flow left->right.
        rest = pages[1:]
        page_no = 2
        for i in range(0, len(rest), 2):
            lp = rest[i]
            rp = rest[i + 1] if i + 1 < len(rest) else None
            spreads.append(dict(header=header, left_kind="answer", left=lp,
                                right=rp, lr=ROMAN[page_no], rr=ROMAN[page_no + 1] if rp else ""))
            page_no += 2

    manifest = []
    for idx, s in enumerate(spreads, 1):
        svg = spread_svg(s["header"], s["left_kind"], s["left"], s["right"], s["lr"], s["rr"])
        fname = f"codex-{idx}.svg"
        with open(os.path.join(here, fname), "w") as f:
            f.write(svg)
        manifest.append((fname, s["header"]))

    print(f"Wrote {len(manifest)} spreads:")
    for fname, header in manifest:
        print(f"  {fname}  —  {header}")


if __name__ == "__main__":
    build()
