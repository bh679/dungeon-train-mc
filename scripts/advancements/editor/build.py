#!/usr/bin/env python3
"""Build the advancement editor's data bundle from the working tree.

The bundle is everything the page draws: every advancement (Dungeon Train's from
``src/main/resources/data/dungeontrain/advancement/``, vanilla's from the client jar), its parent,
icon, required value and hint, the tab layout from ``src/main/resources/dungeontrain/advancement_tabs.json``,
and the GUI sprites. The working tree is the starting point, so the page always opens on the layout
the mod ships right now.

Usage::

    python3 scripts/advancements/editor/build.py --artifact out.html   # one self-contained page
    python3 scripts/advancements/editor/serve.py                       # local editor that saves to the repo
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import capstone_rules
from textures import Textures, png_uri, square

REPO = Path(__file__).resolve().parents[3]
RES = REPO / "src/main/resources"
ADV_DIR = RES / "data/dungeontrain/advancement"
TABS_FILE = RES / "dungeontrain/advancement_tabs.json"
LANG_FILE = RES / "assets/dungeontrain/lang/en_us.json"
WEB = Path(__file__).resolve().parent / "web"
#: Page scripts, concatenated in this order into one closure.
JS_FILES = ["model.js", "view.js", "menu.js", "panels.js"]

#: The numeric criterion fields the mod treats as a requirement (RequirementField).
REQ_FIELDS = {"threshold": "count", "thresholdReads": "count", "thresholdMeters": "metres", "thresholdTicks": "ticks"}
#: Vanilla tabs, in the order the game lists them.
VANILLA_ROOTS = ["minecraft:story/root", "minecraft:nether/root", "minecraft:end/root",
                 "minecraft:adventure/root", "minecraft:husbandry/root"]
#: Band-journey members: their parents are rewritten at load to follow the band order.
CHAIN = re.compile(r"^dungeontrain:dungeon_train/(reached_[a-z_]+|the_upside_down|reassembly_required|read_all_nether_starting_books)$")
#: Icons offered in the picker on top of every icon already in use.
EXTRA_ICONS = ["lectern", "chest_minecart", "tnt_minecart", "hopper_minecart", "furnace_minecart", "rail",
               "powered_rail", "map", "filled_map", "spyglass", "compass", "player_head", "armor_stand", "lead",
               "bell", "book", "writable_book", "written_book", "knowledge_book", "bookshelf", "painting",
               "item_frame", "glow_item_frame", "ender_eye", "totem_of_undying", "emerald", "diamond",
               "nether_star", "decorated_pot", "camera"]
#: Curated backgrounds shown as swatches; the "+" picker offers every block texture.
BACKGROUNDS = ["minecraft:textures/gui/advancements/backgrounds/stone.png",
               "minecraft:textures/gui/advancements/backgrounds/adventure.png",
               "minecraft:textures/gui/advancements/backgrounds/husbandry.png",
               "minecraft:textures/gui/advancements/backgrounds/nether.png",
               "minecraft:textures/gui/advancements/backgrounds/end.png"] + [
    f"minecraft:textures/block/{b}.png" for b in
    ["stone_bricks", "red_nether_bricks", "oak_planks", "spruce_planks", "deepslate_tiles", "black_concrete",
     "polished_blackstone_bricks", "chiseled_bookshelf_top", "moss_block", "sandstone"]]


def mc_version() -> str:
    m = re.search(r"^minecraft_version=(.+)$", (REPO / "gradle.properties").read_text(), re.M)
    return m.group(1).strip() if m else "1.21.1"


def load_tabs_file() -> dict:
    if TABS_FILE.is_file():
        return json.loads(TABS_FILE.read_text())
    return {"order": [], "tabNames": {}, "copies": {}}


def find_requirement(adv: dict):
    for name, crit in (adv.get("criteria") or {}).items():
        cond = crit.get("conditions") or {}
        for field, unit in REQ_FIELDS.items():
            v = cond.get(field)
            if isinstance(v, int) and not isinstance(v, bool):
                return {"field": field, "unit": unit, "n": v, "criterion": name}
    return None


class Lang:
    def __init__(self, *tables: dict):
        self.table = {}
        for t in tables:
            self.table.update(t)

    def text(self, comp, keep_args: bool = False) -> str:
        """A text component as English. ``keep_args`` leaves ``%s`` for a requirement value."""
        if comp is None:
            return ""
        if isinstance(comp, str):
            return comp
        if "translate" not in comp:
            return comp.get("text", "")
        s = self.table.get(comp["translate"], comp["translate"])
        args = [] if keep_args else [self.text(a) if isinstance(a, dict) else str(a) for a in comp.get("with", [])]
        it = iter(args)

        def rep(m):
            if m.group(0) == "%%":
                return "%"
            if m.group(1):
                k = int(m.group(1)) - 1
                return args[k] if k < len(args) else "%s"
            return next(it, "%s")
        return re.sub(r"§.", "", re.sub(r"%(?:(\d+)\$)?s|%%", rep, s))

    def hint(self, comp) -> str | None:
        key = (comp or {}).get("translate", "") if isinstance(comp, dict) else ""
        if key.endswith(".title"):
            h = self.table.get(key[: -len(".title")] + ".hint")
            return re.sub(r"§.", "", h) if h else None
        return None


def load_advancements(tex: Textures) -> dict[str, dict]:
    """id → raw advancement JSON, Dungeon Train's from the repo and vanilla's tab trees from the jar."""
    out = {}
    for f in sorted(ADV_DIR.rglob("*.json")):
        out["dungeontrain:" + f.relative_to(ADV_DIR).with_suffix("").as_posix()] = json.loads(f.read_text())
    tabs = ("story", "adventure", "nether", "end", "husbandry")
    for n in sorted(tex.names):
        m = re.fullmatch(r"data/minecraft/advancement/((?:%s)/.+)\.json" % "|".join(tabs), n)
        if m:
            out["minecraft:" + m.group(1)] = tex.mc_json(n)
    return out


def build_bundle() -> tuple[dict, list[str]]:
    tex = Textures(mc_version())
    lang = Lang(tex.mc_json("assets/minecraft/lang/en_us.json"), json.loads(LANG_FILE.read_text()))
    tabs_file = load_tabs_file()
    copies = tabs_file.get("copies", {})

    icon_ids, icon_index = [], {}

    def icon(item_id: str) -> int:
        if item_id not in icon_index:
            icon_index[item_id] = len(icon_ids)
            icon_ids.append(item_id)
        return icon_index[item_id]

    books = capstone_rules.book_paths()
    nodes, parents = {}, {}
    for aid, adv in load_advancements(tex).items():
        d = adv.get("display")
        if not d:
            continue  # display-less advancements never show
        req = find_requirement(adv)
        node = {"t": lang.text(d.get("title")), "d": lang.text(d.get("description"), keep_args=bool(req)),
                "f": d.get("frame", "task"), "i": icon(d["icon"]["id"]), "h": bool(d.get("hidden"))}
        hint = lang.hint(d.get("title"))
        if hint:
            node["hint"] = hint
        if req:
            node["req"] = req
        if aid in copies:
            node["copyOf"] = copies[aid]
        if aid.startswith("dungeontrain:"):
            required, reset = capstone_rules.effective(aid, copies, books, tabs_file)
            node["cap"] = {"req": required, "reset": reset, "ed": capstone_rules.is_editable(aid, copies)}
        if CHAIN.match(aid):
            node["chain"] = True
        if not adv.get("parent"):
            node["bg"] = d.get("background") or BACKGROUNDS[0]
        nodes[aid] = node
        parents[aid] = adv.get("parent") or ""
    for extra in EXTRA_ICONS:
        icon("exposure_polaroid:instant_camera" if extra == "camera" else "minecraft:" + extra)
    icon("exposure:photograph")

    dt_roots = [r for r, p in parents.items() if not p and r.startswith("dungeontrain:")]
    order = [r for r in tabs_file.get("order", []) if r in dt_roots]
    order += sorted(r for r in dt_roots if r not in order)
    tab_titles = {root: lang.table.get(key, key) for root, key in tabs_file.get("tabNames", {}).items()}

    bgs = {}
    for b in BACKGROUNDS + sorted({n["bg"] for n in nodes.values() if "bg" in n}):
        uri = tex.background(b)
        if uri:
            bgs[b] = uri
    more = {k: v for k, v in tex.block_backgrounds().items() if k not in bgs}

    window = tex.mc_img("assets/minecraft/textures/gui/advancements/window.png").crop((0, 0, 252, 140))
    spr = "assets/minecraft/textures/gui/sprites/advancements/"
    sprites = {n[len(spr):-4]: png_uri(tex.mc_img(n)) for n in tex.names if n.startswith(spr) and n.endswith(".png")}
    sprites.update(window=png_uri(window),
                   button=png_uri(tex.mc_img("assets/minecraft/textures/gui/sprites/widget/button.png")),
                   button_highlighted=png_uri(tex.mc_img("assets/minecraft/textures/gui/sprites/widget/button_highlighted.png")),
                   dirt=png_uri(square(tex.mc_img("assets/minecraft/textures/block/dirt.png"))))

    bundle = {
        "nodes": nodes, "gameParents": parents, "gameOrder": order + VANILLA_ROOTS,
        "baseline": {"created": {}, "parents": {}, "icons": {}, "tabTitles": tab_titles, "bgs": {}, "order": order},
        "icons": [tex.icon(i) for i in icon_ids], "iconIds": icon_ids,
        "iconNames": [i.split(":", 1)[1].replace("_", " ") for i in icon_ids],
        "bgs": bgs, "moreBgs": more, "sprites": sprites,
    }
    return bundle, tex.missing


def page_html(bundle: dict, mode: str) -> str:
    """The editor as one self-contained page. ``mode`` is 'artifact' (saves to its own db) or 'local'."""
    html = (WEB / "index.html").read_text()
    css = (WEB / "editor.css").read_text()
    js = "(function () {\n" + "\n".join((WEB / "js" / f).read_text() for f in JS_FILES) + "})();\n"
    data = "window.MOCK=" + json.dumps(bundle, separators=(",", ":")) + ";window.EDITOR_MODE=" + json.dumps(mode) + ";"
    return (html.replace("/*__CSS__*/", css).replace("/*__DATA__*/", data).replace("/*__JS__*/", js))


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--artifact", type=Path, required=True, help="write a self-contained page here")
    args = ap.parse_args(argv)
    bundle, missing = build_bundle()
    args.artifact.parent.mkdir(parents=True, exist_ok=True)
    args.artifact.write_text(page_html(bundle, "artifact"))
    print(f"wrote {args.artifact} ({args.artifact.stat().st_size // 1024} KB, {len(bundle['nodes'])} advancements)")
    if missing:
        print("icons not found (drawn magenta):", ", ".join(sorted(set(missing))), file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
