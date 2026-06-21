#!/usr/bin/env python3
"""Generate the four question-title banners for the Modrinth description.

Style: "modernshot" — a modern dark panel on the left (a CARRIAGE id pill, the
question, and an accent underline) fading into a per-question in-game screenshot
on the right. Answers stay as plain Markdown in the description; these are the
section headers.

Each banner embeds its screenshot (bg-<slug>.jpg) as a base64 data URI so it
renders inside an <img>-loaded SVG on Modrinth (external image refs don't load in
that context). Run:  python3 build-banners.py
"""

import base64
import glob
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SANS = "'Helvetica Neue',Arial,sans-serif"

# carriage ids are fixed (random-looking) so rebuilds stay stable.
QUESTIONS = [
    {"slug": "q1", "carriage": "7F3A9C", "full": "What is the Dungeon Train?"},
    {"slug": "q2", "carriage": "2K8X4D", "full": "What does it want from me?"},
    {"slug": "q3", "carriage": "9B4Q7E", "full": "Who else rides the train?"},
    {"slug": "q4", "carriage": "3R6M1Z", "full": "Where is it going?"},
]


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def bg_data_uri(slug):
    with open(os.path.join(HERE, f"bg-{slug}.jpg"), "rb") as f:
        b64 = base64.b64encode(f.read()).decode("ascii")
    return f"data:image/jpeg;base64,{b64}"


def banner(q):
    bg = bg_data_uri(q["slug"])
    pill = f'CARRIAGE {q["carriage"]}'
    # pill widens to fit the longer label
    pill_w = 162
    body = f'''<defs>
<linearGradient id="fade" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#0e0f14" stop-opacity="1"/><stop offset="0.40" stop-color="#0e0f14" stop-opacity="1"/><stop offset="0.92" stop-color="#0e0f14" stop-opacity="0"/></linearGradient>
<linearGradient id="acc" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#8b5cf6"/><stop offset="1" stop-color="#22d3ee"/></linearGradient>
<clipPath id="rnd"><rect x="0" y="0" width="680" height="150" rx="10"/></clipPath>
</defs>
<g clip-path="url(#rnd)">
<image href="{bg}" x="170" y="0" width="680" height="150" preserveAspectRatio="xMidYMid slice"/>
<rect x="0" y="0" width="680" height="150" fill="url(#fade)"/>
<rect x="0" y="0" width="680" height="150" fill="#0e0f14" opacity="0.12"/>
</g>
<rect x="32" y="34" width="{pill_w}" height="26" rx="13" fill="#8b5cf61f" stroke="#8b5cf6" stroke-width="1"/>
<text x="48" y="51" font-family="{SANS}" font-size="11" font-weight="700" letter-spacing="1.5" fill="#b794f6">{esc(pill)}</text>
<text x="33" y="105" font-family="{SANS}" font-size="30" font-weight="700" fill="#000000" opacity="0.6">{esc(q["full"])}</text>
<text x="32" y="104" font-family="{SANS}" font-size="30" font-weight="700" fill="#f3f3f7">{esc(q["full"])}</text>
<rect x="32" y="118" width="72" height="4" rx="2" fill="url(#acc)"/>'''
    return (
        f'<svg width="100%" viewBox="0 0 680 150" role="img" xmlns="http://www.w3.org/2000/svg">\n'
        f'<title>{esc(q["full"])}</title>\n<desc>Question banner: modern panel fading into an in-game screenshot.</desc>\n'
        f'{body}\n</svg>'
    )


def build():
    for old in glob.glob(os.path.join(HERE, "*.svg")):
        os.remove(old)
    written = []
    for q in QUESTIONS:
        fname = f'{q["slug"]}-modernshot.svg'
        with open(os.path.join(HERE, fname), "w") as f:
            f.write(banner(q))
        written.append(fname)
    print(f"Wrote {len(written)} banners:\n  " + "\n  ".join(written))


if __name__ == "__main__":
    build()
