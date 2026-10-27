#!/usr/bin/env python3
"""Turn the constellation editor's JSON into the mod's bundled perks tree.

    python3 scripts/perks/tree-from-editor.py <editor.json> [--out src/main/resources/data/dungeontrain/perks/tree.json]
                                                           [--lang en_us-fragment.json] [--check]

The editor (docs/design/perks-constellation.html on the perk-list branch; the same JSON its "copy JSON"
button gives and the site's publish box takes) ids perks by generated slugs ("c7r1k0"). The game keys
everything by hand-written kebab ids (perks/PerkRegistry), so every perk gets `id` = kebab of its name,
with OVERRIDES for the spellings the registry chose. Links are rewritten to those ids. Editor-only fields
(`work`, `dev`, `reviewed`, `cost` prose, `savedAt`) are dropped; `point` (the per-point effect line) is
kept. `--check` exits 1 when the committed tree differs from what this run would write.

Positions stay in the editor's 800 x 800 space, centre 400,400: the menu draws them as they are.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

OVERRIDES = {
    "Stackable BookQill": "stackable-book-quill",
    "Gift liveEye": "gift-live-eye",
    "Shared Arua": "shared-aura",
}
# Perks taken out of the game: dropped with their links even while the editor still has them.
REMOVED = {"gift-live-eye"}
# Names the game shows differently from the editor (the editor keeps its own spelling).
NAMES = {
    "stackable-book-quill": "Stackable Book and Quill",
}
ROOT = "root"
CENTRE = 400.0


def kebab(name: str) -> str:
    s = re.sub(r"[^A-Za-z0-9]+", "-", name.strip()).strip("-").lower()
    return re.sub(r"-+", "-", s)


def perk_id(p: dict) -> str:
    if p["id"] == ROOT:
        return ROOT
    return OVERRIDES.get(p["name"], kebab(p["name"]))


def convert(editor: dict) -> dict:
    model = json.loads(editor["json"]) if isinstance(editor.get("json"), str) else editor
    ids: dict[str, str] = {}
    perks = []
    for p in model["perks"]:
        pid = perk_id(p)
        if pid in REMOVED:
            continue
        if pid in ids.values():
            raise SystemExit(f"duplicate id {pid!r} (name {p['name']!r})")
        ids[p["id"]] = pid
        out = {"id": pid, "cls": p.get("cls"), "name": NAMES.get(pid, p["name"].strip()), "desc": (p.get("desc") or "").strip(),
               "x": float(p["x"]), "y": float(p["y"])}
        if pid != ROOT:
            out["tier"] = int(p.get("tier") or (3 if p.get("cap") else 1))
        if p.get("point"):
            out["point"] = str(p["point"]).strip()
        perks.append(out)
    links = []
    seen = set()
    for l in model["links"]:
        a, b = ids.get(l["a"]), ids.get(l["b"])
        if not a or not b or a == b:
            continue
        key = (a, b, l.get("kind", "req"))
        if key in seen:
            continue
        seen.add(key)
        links.append({"a": a, "b": b, "kind": "cross" if l.get("kind") == "cross" else "req", "oneWay": bool(l.get("oneWay"))})
    used = {p["cls"] for p in perks if p.get("cls")}
    classes = [{"id": c["id"], "name": c["name"], "color": c["color"]} for c in model["classes"] if c["id"] in used]
    return {"v": int(model.get("v", 0)), "classes": classes, "perks": perks, "links": links}


def lang_fragment(tree: dict) -> dict:
    out = {}
    for p in tree["perks"]:
        if p["id"] == ROOT:
            continue
        out[f"perk.dungeontrain.{p['id']}.name"] = p["name"]
        # .desc is not written: the player text lives in en_us.json, hand-written. The tree's desc is
        # the editor's design note and stays in tree.json only.
        if p.get("point"):
            out[f"perk.dungeontrain.{p['id']}.point"] = p["point"]
    for c in tree["classes"]:
        out[f"perk.dungeontrain.class.{c['id']}"] = c["name"]
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("editor_json")
    ap.add_argument("--out", default="src/main/resources/data/dungeontrain/perks/tree.json")
    ap.add_argument("--lang", help="write the per-perk lang keys (an en_us fragment) here")
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    tree = convert(json.loads(Path(a.editor_json).read_text()))
    text = json.dumps(tree, indent=1, ensure_ascii=False) + "\n"
    out = Path(a.out)
    if a.check:
        if not out.exists() or out.read_text() != text:
            print(f"{out} is out of date; rerun tree-from-editor.py", file=sys.stderr)
            return 1
        print("tree.json up to date")
        return 0
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text)
    print(f"wrote {out}: {len(tree['perks'])} perks, {len(tree['links'])} links, {len(tree['classes'])} classes")
    if a.lang:
        Path(a.lang).write_text(json.dumps(lang_fragment(tree), indent=2, ensure_ascii=False) + "\n")
        print(f"wrote {a.lang}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
