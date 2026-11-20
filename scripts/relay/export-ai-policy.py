#!/usr/bin/env python3
"""Export the in-game AI Policy as JSON, for the website copy at dungeon.training/ai-policy/.

The AI Policy exists twice: the in-game screen (client/policy/AiPolicyScreen, content in
AiPolicyContent.java, English in en_us.json) and the website page (bh679/dp-relay
public/dungeontrain/ai-policy/index.html). The game is the source of truth: its English has 21
translations hanging off it. This script turns it into policy.json, which the relay's
scripts/build-ai-policy.js renders into the page. sync-ai-policy.sh carries it to the relay.

So to change the policy, edit en_us.json (and AiPolicyContent.java for order, new lines or
sections). Never edit the website page by hand: the relay's ai-policy-sync.test.js fails on it.

Text is exported as segments, [{"text": ...}, {"text": ..., "href": ...}], so links survive:
  * used.3's %s becomes the "#minor-updates on Discord" link, the same URL as the screen's
    UPDATES_URL (read from AiPolicyScreen.java);
  * WEB_LINKS adds links the game shows as plain text (a phrase inside a line, linked on the web).

Usage:
  python3 scripts/relay/export-ai-policy.py                 # print the JSON
  python3 scripts/relay/export-ai-policy.py --out FILE      # write it
  python3 scripts/relay/export-ai-policy.py --check         # fail if any referenced key is missing
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
POLICY_DIR = ROOT / "src/main/java/games/brennan/dungeontrain/client/policy"
CONTENT_JAVA = POLICY_DIR / "AiPolicyContent.java"
SCREEN_JAVA = POLICY_DIR / "AiPolicyScreen.java"
EN_US = ROOT / "src/main/resources/assets/dungeontrain/lang/en_us.json"

PREFIX = "gui.dungeontrain.ai_policy."
SCHEMA = 1

# Links the website adds that the game shows as plain text: key -> (phrase inside the line, href).
# The phrase must appear in the English exactly once, or the export fails.
WEB_LINKS = {
    PREFIX + "not_ai.6": ("from a program", "https://dungeon.training/blog/names-without-ai/"),
    PREFIX + "live_chat.2": ("Google Translate", "https://cloud.google.com/translate"),
}

# The line whose %s is the Discord link, and the key of the link's text (AiPolicyScreen#bulletText).
LINKED_PLACEHOLDER = {PREFIX + "used.3": PREFIX + "used.3.link"}

_SECTION = re.compile(r'Section\.(list|paragraph)\(\s*"([^"]+)"\s*,\s*\w+\s*(?:,\s*"([^"]+)")?')
_BULLET = re.compile(r'new Bullet\(\s*"([^"]+)"')
_EXPLAINER = re.compile(r'new Explainer\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"')
_UPDATES_URL = re.compile(r'UPDATES_URL\s*=\s*"([^"]+)"')


class ExportError(Exception):
    pass


def _block(src, name):
    """The source of `private static final List<...> NAME = List.of(...);`."""
    start = src.find(f" {name} = List.of(")
    if start < 0:
        raise ExportError(f"AiPolicyContent.java has no {name} list")
    end = src.find(");\n", start)
    return src[start:end]


def parse_layout(content_src):
    """Sections (in order, with their bullet keys or paragraph body key) and explainers."""
    sections = []
    block = _block(content_src, "SECTIONS")
    tokens = sorted([(m.start(), "section", m) for m in _SECTION.finditer(block)]
                    + [(m.start(), "bullet", m) for m in _BULLET.finditer(block)], key=lambda t: t[0])
    for _, kind, m in tokens:
        if kind == "section":
            if m.group(1) == "paragraph":
                if not m.group(3):
                    raise ExportError(f"paragraph section {m.group(2)} has no body key")
                sections.append({"header": m.group(2), "kind": "paragraph", "body": m.group(3)})
            else:
                sections.append({"header": m.group(2), "kind": "list", "items": []})
        else:
            if not sections or sections[-1]["kind"] != "list":
                raise ExportError(f"bullet {m.group(1)} is outside a list section")
            sections[-1]["items"].append(m.group(1))
    if not sections:
        raise ExportError("AiPolicyContent.java: no sections found")
    for s in sections:
        if s["kind"] == "list" and not s["items"]:
            raise ExportError(f"list section {s['header']} has no bullets")
    explainers = [{"title": m.group(1), "desc": m.group(2), "url": m.group(3)}
                  for m in _EXPLAINER.finditer(_block(content_src, "EXPLAINERS"))]
    return sections, explainers


def _plain(lang, key):
    if key not in lang:
        raise ExportError(f"en_us.json has no {key}")
    return lang[key].replace("%%", "%")


def segments(lang, key, updates_url):
    """A line as text segments, with its links."""
    if key in LINKED_PLACEHOLDER:
        raw = lang.get(key)
        if raw is None or raw.count("%s") != 1:
            raise ExportError(f"{key} must hold exactly one %s")
        before, after = raw.split("%s")
        segs = [{"text": before.replace("%%", "%")},
                {"text": _plain(lang, LINKED_PLACEHOLDER[key]), "href": updates_url},
                {"text": after.replace("%%", "%")}]
        return [s for s in segs if s["text"]]
    text = _plain(lang, key)
    if key in WEB_LINKS:
        phrase, href = WEB_LINKS[key]
        if text.count(phrase) != 1:
            raise ExportError(f'{key}: web link phrase "{phrase}" must appear exactly once')
        before, after = text.split(phrase)
        segs = [{"text": before}, {"text": phrase, "href": href}, {"text": after}]
        return [s for s in segs if s["text"]]
    return [{"text": text}]


def build(content_src, screen_src, lang):
    m = _UPDATES_URL.search(screen_src)
    if not m:
        raise ExportError("AiPolicyScreen.java has no UPDATES_URL")
    updates_url = m.group(1)
    sections, explainers = parse_layout(content_src)
    intro, n = [], 1
    while PREFIX + f"intro.{n}" in lang:
        intro.append(segments(lang, PREFIX + f"intro.{n}", updates_url))
        n += 1
    if not intro:
        raise ExportError("en_us.json has no intro lines")
    out_sections = []
    for s in sections:
        entry = {"header": _plain(lang, s["header"]), "kind": s["kind"]}
        if s["kind"] == "list":
            entry["items"] = [segments(lang, k, updates_url) for k in s["items"]]
        else:
            entry["body"] = segments(lang, s["body"], updates_url)
        out_sections.append(entry)
    return {
        "schema": SCHEMA,
        "_note": "GENERATED from bh679/dungeon-train-mc (en_us.json + AiPolicyContent.java) by "
                 "scripts/relay/export-ai-policy.py. Do not edit: change the mod and let "
                 "sync-ai-policy.sh carry it here.",
        "title": _plain(lang, PREFIX + "title"),
        "subtitle": _plain(lang, PREFIX + "subtitle"),
        "intro": intro,
        "sections": out_sections,
        "explainersHeader": _plain(lang, PREFIX + "explainers.header"),
        "explainers": [{"title": _plain(lang, e["title"]), "desc": _plain(lang, e["desc"]),
                        "url": e["url"]} for e in explainers],
        "closing": _plain(lang, PREFIX + "closing"),
        "signature": _plain(lang, PREFIX + "signature"),
    }


def export():
    return build(CONTENT_JAVA.read_text(encoding="utf-8"), SCREEN_JAVA.read_text(encoding="utf-8"),
                 json.loads(EN_US.read_text(encoding="utf-8")))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", help="write the JSON here instead of stdout")
    ap.add_argument("--check", action="store_true", help="only verify the export builds")
    args = ap.parse_args(argv)
    try:
        policy = export()
    except ExportError as e:
        print(f"export-ai-policy: {e}", file=sys.stderr)
        return 1
    text = json.dumps(policy, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        n = sum(len(s.get("items", [])) or 1 for s in policy["sections"])
        print(f"export-ai-policy: OK, {len(policy['sections'])} sections, {n} lines, "
              f"{len(policy['explainers'])} explainer(s)")
    elif args.out:
        Path(args.out).write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
