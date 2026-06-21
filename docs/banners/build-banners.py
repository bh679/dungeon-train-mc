#!/usr/bin/env python3
"""Generate question-title banners for the Modrinth description.

Four styles (screenshot / brutalist / modern / book) x the first two questions,
emitted as self-contained hosted SVGs. Answers stay as plain Markdown in the
description; these are just the section headers.

The screenshot style embeds `screenshot-bg.jpg` as a base64 data URI so it renders
inside an <img>-loaded SVG on Modrinth (external image refs don't load in that
context). Run:  python3 build-banners.py
"""

import base64
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SANS = "'Helvetica Neue',Arial,sans-serif"
SERIF = "Georgia,'Times New Roman',serif"

QUESTIONS = [
    {"slug": "q1", "idx": "01", "full": "What is the Dungeon Train?",
     "two": ["What is the", "Dungeon Train?"]},
    {"slug": "q2", "idx": "02", "full": "What does it want from me?",
     "two": ["What does it", "want from me?"]},
]


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def bg_data_uri():
    with open(os.path.join(HERE, "screenshot-bg.jpg"), "rb") as f:
        b64 = base64.b64encode(f.read()).decode("ascii")
    return f"data:image/jpeg;base64,{b64}"


def wrap(title, body):
    return (
        f'<svg width="100%" viewBox="0 0 680 150" role="img" xmlns="http://www.w3.org/2000/svg">\n'
        f'<title>{esc(title)}</title>\n<desc>Question banner.</desc>\n{body}\n</svg>'
    )


def style_screenshot(q, bg):
    body = f'''<defs><linearGradient id="scrim" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#000000" stop-opacity="0.05"/><stop offset="0.55" stop-color="#000000" stop-opacity="0.25"/><stop offset="1" stop-color="#000000" stop-opacity="0.82"/></linearGradient></defs>
<image href="{bg}" x="0" y="0" width="680" height="150" preserveAspectRatio="xMidYMid slice"/>
<rect x="0" y="0" width="680" height="150" fill="url(#scrim)"/>
<text x="29" y="121" font-family="{SANS}" font-size="26" font-weight="700" fill="#000000" opacity="0.55">{esc(q["full"])}</text>
<text x="28" y="120" font-family="{SANS}" font-size="26" font-weight="700" fill="#ffffff">{esc(q["full"])}</text>
<rect x="28" y="132" width="44" height="3" fill="#a78bfa"/>'''
    return wrap(q["full"], body)


def style_brutalist(q):
    body = f'''<rect x="0" y="0" width="680" height="150" fill="#ece8df"/>
<rect x="0" y="0" width="680" height="28" fill="#111111"/>
<text x="18" y="19" font-family="{SANS}" font-size="11" font-weight="700" letter-spacing="2" fill="#ece8df">THE DUNGEON TRAIN — A RIDER'S CATECHISM</text>
<text x="662" y="19" text-anchor="end" font-family="{SANS}" font-size="11" font-weight="700" fill="#e2402c">N&#186; {q["idx"]} / &#8734;</text>
<text x="556" y="138" font-family="{SANS}" font-size="104" font-weight="800" fill="#e2402c">{q["idx"]}</text>
<text x="24" y="92" font-family="{SANS}" font-size="36" font-weight="800" fill="#111111">{esc(q["two"][0])}</text>
<text x="24" y="126" font-family="{SANS}" font-size="36" font-weight="800" fill="#111111">{esc(q["two"][1])}</text>
<rect x="24" y="140" width="528" height="5" fill="#e2402c"/>'''
    return wrap(q["full"], body)


def style_modern(q):
    body = f'''<defs><linearGradient id="acc" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#8b5cf6"/><stop offset="1" stop-color="#22d3ee"/></linearGradient></defs>
<rect x="0" y="0" width="680" height="150" rx="10" fill="#0e0f14"/>
<rect x="32" y="34" width="118" height="26" rx="13" fill="#8b5cf61f" stroke="#8b5cf6" stroke-width="1"/>
<text x="50" y="51" font-family="{SANS}" font-size="11" font-weight="700" letter-spacing="1.5" fill="#b794f6">QUESTION {q["idx"]}</text>
<text x="32" y="104" font-family="{SANS}" font-size="30" font-weight="700" fill="#f3f3f7">{esc(q["full"])}</text>
<rect x="32" y="118" width="72" height="4" rx="2" fill="url(#acc)"/>'''
    return wrap(q["full"], body)


def style_modernshot(q, bg):
    """Modern panel on the left fading into the in-game screenshot on the right."""
    body = f'''<defs>
<linearGradient id="fade" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#0e0f14" stop-opacity="1"/><stop offset="0.40" stop-color="#0e0f14" stop-opacity="1"/><stop offset="0.92" stop-color="#0e0f14" stop-opacity="0"/></linearGradient>
<linearGradient id="acc" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#8b5cf6"/><stop offset="1" stop-color="#22d3ee"/></linearGradient>
<clipPath id="rnd"><rect x="0" y="0" width="680" height="150" rx="10"/></clipPath>
</defs>
<g clip-path="url(#rnd)">
<image href="{bg}" x="0" y="0" width="680" height="150" preserveAspectRatio="xMidYMid slice"/>
<rect x="0" y="0" width="680" height="150" fill="url(#fade)"/>
<rect x="0" y="0" width="680" height="150" fill="#0e0f14" opacity="0.12"/>
</g>
<rect x="32" y="34" width="118" height="26" rx="13" fill="#8b5cf61f" stroke="#8b5cf6" stroke-width="1"/>
<text x="50" y="51" font-family="{SANS}" font-size="11" font-weight="700" letter-spacing="1.5" fill="#b794f6">QUESTION {q["idx"]}</text>
<text x="33" y="105" font-family="{SANS}" font-size="30" font-weight="700" fill="#000000" opacity="0.6">{esc(q["full"])}</text>
<text x="32" y="104" font-family="{SANS}" font-size="30" font-weight="700" fill="#f3f3f7">{esc(q["full"])}</text>
<rect x="32" y="118" width="72" height="4" rx="2" fill="url(#acc)"/>'''
    return wrap(q["full"], body)


def style_book(q):
    body = f'''<rect x="0" y="0" width="680" height="150" rx="3" fill="#efe6cf"/>
<rect x="10" y="10" width="660" height="130" rx="2" fill="none" stroke="#cebd92" stroke-width="1"/>
<text x="340" y="74" text-anchor="middle" font-family="{SERIF}" font-size="28" font-weight="700" fill="#3b3020">{esc(q["full"])}</text>
<line x1="250" y1="98" x2="318" y2="98" stroke="#b8a87e" stroke-width="1"/>
<rect x="326" y="94" width="8" height="8" fill="#b8a87e" transform="rotate(45 330 98)"/>
<line x1="342" y1="98" x2="410" y2="98" stroke="#b8a87e" stroke-width="1"/>'''
    return wrap(q["full"], body)


def build():
    bg = bg_data_uri()
    styles = {
        "screenshot": lambda q: style_screenshot(q, bg),
        "modernshot": lambda q: style_modernshot(q, bg),
        "brutalist": style_brutalist,
        "modern": style_modern,
        "book": style_book,
    }
    written = []
    for q in QUESTIONS:
        for name, fn in styles.items():
            fname = f'{q["slug"]}-{name}.svg'
            with open(os.path.join(HERE, fname), "w") as f:
                f.write(fn(q))
            written.append(fname)
    print(f"Wrote {len(written)} banners:\n  " + "\n  ".join(written))


if __name__ == "__main__":
    build()
