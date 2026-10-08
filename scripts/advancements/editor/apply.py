#!/usr/bin/env python3
"""Write an advancement-editor change set into the repo.

The change set is what the editor's "Copy changes" button gives you, and what its "Save to repo"
button posts to ``serve.py``. Shape (every section optional)::

    {"version": 1,
     "parents":     {"<id>": "<parent id>" | null},        # null: it heads its own tab
     "icons":       {"<id>": "<item id>"},
     "values":      {"<id>": {"field": "threshold", "from": 100, "to": 50}},
     "backgrounds": {"<tab root id>": "minecraft:textures/block/stone_bricks.png"},
     "tabNames":    {"<tab root id>": "Train Explorer" | null},
     "order":       ["<tab root id>", ...],
     "created":     {"<id>": {"parent": ..., "copyOf": "<id>", "icon": ..., "background": ...}},
     "deleted":     ["<id>", ...],
     "capstone":    {"<id>": {"required": true, "reset": false}},  # Everything Burrito / It's Not That Simple
     "unlocks":     {"<tab root id>": "<id that unlocks it>" | null}}

A new tab (``created`` with no ``parent``) may carry ``"unlockedBy": "<id>"``: it is then earned whenever
that advancement is, so it needs no trigger of its own.

Ids never change: a move only rewrites ``parent``, so players keep everything they earned. Edits
to existing files are text-level (the one value on its line), so the diff is one line per change.
New files and new lang keys are written in the repo's own format. Anything that needs a person
(translations, a real trigger for a brand-new tab) is listed under "todo".

Usage::

    python3 scripts/advancements/editor/apply.py changes.json [--dry-run]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

import capstone_rules

REPO = Path(__file__).resolve().parents[3]
RES = REPO / "src/main/resources"
ADV_DIR = RES / "data/dungeontrain/advancement"
TABS_FILE = RES / "dungeontrain/advancement_tabs.json"
LANG_FILE = RES / "assets/dungeontrain/lang/en_us.json"

ID_RE = re.compile(r"^dungeontrain:(?!editor/)([a-z0-9_]+(?:/[a-z0-9_]+){1,3})$")
ITEM_RE = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_/.-]+$")
FIELDS = ("threshold", "thresholdReads", "thresholdMeters", "thresholdTicks")
DEFAULT_BG = "minecraft:textures/gui/advancements/backgrounds/stone.png"
#: The band-journey member whose JSON parent anchors the whole chain (BandAdvancements.anchorFrom).
CHAIN_ANCHOR_CARRIER = "dungeontrain:dungeon_train/reached_nether"
CHAIN = re.compile(r"^dungeontrain:dungeon_train/(reached_[a-z_]+|the_upside_down|reassembly_required|read_all_nether_starting_books)$")


class ApplyError(ValueError):
    """The change set is malformed or names something that does not exist."""


@dataclass
class Report:
    written: list[str] = field(default_factory=list)
    todo: list[str] = field(default_factory=list)

    def wrote(self, path: Path) -> None:
        rel = shown(path)
        if rel not in self.written:
            self.written.append(rel)


# ---------- paths ----------------------------------------------------------------------------

def shown(path: Path) -> str:
    """``path`` relative to the repo when it is inside it (it always is, outside tests)."""
    try:
        return path.relative_to(REPO).as_posix()
    except ValueError:
        return path.as_posix()


def adv_path(adv_id: str, adv_dir: Path = ADV_DIR) -> Path:
    m = ID_RE.match(adv_id or "")
    if not m:
        raise ApplyError(f"not an editable Dungeon Train advancement id: {adv_id!r}")
    return adv_dir / (m.group(1) + ".json")


def existing(adv_id: str, adv_dir: Path) -> Path:
    path = adv_path(adv_id, adv_dir)
    if not path.is_file():
        raise ApplyError(f"no advancement file for {adv_id} ({shown(path)})")
    return path


def lang_prefix(adv_id: str) -> str:
    return "advancements." + adv_id.replace(":", ".").replace("/", ".")


# ---------- text-level JSON edits ------------------------------------------------------------

def set_parent(text: str, parent: str | None) -> str:
    """Rewrite, add or remove the top-level ``"parent"`` line."""
    line = re.compile(r'^(\s*)"parent"\s*:\s*"[^"]*"\s*,?\s*\n', re.M)
    if parent is None:
        return line.sub("", text, count=1)
    if line.search(text):
        return re.sub(r'("parent"\s*:\s*)"[^"]*"', lambda m: f'{m.group(1)}"{parent}"', text, count=1)
    indent = re.search(r'^\{\s*\n(\s*)"', text)
    pad = indent.group(1) if indent else "  "
    return re.sub(r"^\{\s*\n", f'{{\n{pad}"parent": "{parent}",\n', text, count=1)


def set_icon(text: str, item_id: str) -> str:
    out, n = re.subn(r'("icon"\s*:\s*\{\s*"id"\s*:\s*)"[^"]*"', lambda m: f'{m.group(1)}"{item_id}"', text, count=1)
    if not n:
        raise ApplyError("no display.icon.id to rewrite")
    return out


def set_value(text: str, field_name: str, value: int) -> str:
    if field_name not in FIELDS:
        raise ApplyError(f"unknown requirement field {field_name!r}")
    out, n = re.subn(rf'("{field_name}"\s*:\s*)\d+', lambda m: f"{m.group(1)}{value}", text, count=1)
    if not n:
        raise ApplyError(f"no {field_name} to rewrite")
    return out


def set_background(text: str, background: str) -> str:
    if re.search(r'"background"\s*:', text):
        return re.sub(r'("background"\s*:\s*)"[^"]*"', lambda m: f'{m.group(1)}"{background}"', text, count=1)
    out, n = re.subn(r'^(\s*)("frame"\s*:\s*"[^"]*",?)\s*$',
                     lambda m: f'{m.group(1)}{m.group(2).rstrip(",")},\n{m.group(1)}"background": "{background}",',
                     text, count=1, flags=re.M)
    if not n:
        raise ApplyError("no display.frame to place the background after")
    return out


def has_background(text: str) -> bool:
    return re.search(r'"background"\s*:', text) is not None


# ---------- lang ------------------------------------------------------------------------------

def add_lang_keys(lang_file: Path, entries: dict[str, str]) -> bool:
    """Set keys in en_us.json, each new one inserted after the last key sharing its prefix."""
    text = lang_file.read_text()
    current = json.loads(text)
    lines = text.split("\n")
    changed = False
    for key, value in entries.items():
        encoded = json.dumps(value, ensure_ascii=False)
        if key in current:
            if current[key] == value:
                continue
            lines = [re.sub(rf'^(\s*"{re.escape(key)}"\s*:\s*).*?(,?)$', lambda m: f"{m.group(1)}{encoded}{m.group(2)}", ln)
                     if ln.lstrip().startswith(f'"{key}"') else ln for ln in lines]
        else:
            prefix = key.rsplit(".", 1)[0]
            while prefix and not any(k.startswith(prefix + ".") for k in current):
                prefix = prefix.rsplit(".", 1)[0] if "." in prefix else ""
            anchor = max((i for i, ln in enumerate(lines) if prefix and ln.lstrip().startswith(f'"{prefix}.')),
                         default=None)
            if anchor is None:  # nothing related: before the closing brace
                anchor = max(i for i, ln in enumerate(lines) if ln.strip() and ln.strip() != "}")
            if not lines[anchor].rstrip().endswith(","):
                lines[anchor] = lines[anchor].rstrip() + ","
            nxt_is_last = lines[anchor + 1].strip() == "}"
            lines.insert(anchor + 1, f'  {json.dumps(key)}: {encoded}' + ("" if nxt_is_last else ","))
        current[key] = value
        changed = True
    if changed:
        lang_file.write_text("\n".join(lines))
        json.loads(lang_file.read_text())  # never leave the lang file unparseable
    return changed


# ---------- new advancements -----------------------------------------------------------------

def copy_json(source: dict, parent: str | None, icon: str | None, background: str | None) -> dict:
    """A gateway copy: same title and description, hidden, silent, granted by TabGateways only."""
    display = dict(source["display"])
    display.update(hidden=True, show_toast=False, announce_to_chat=False)
    if icon:
        display["icon"] = {"id": icon}
    if parent:
        display.pop("background", None)
    else:
        display["background"] = background or display.get("background") or DEFAULT_BG
    out = {"parent": parent} if parent else {}
    out.update(display=display, criteria={"mirrored": {"trigger": "minecraft:impossible"}},
               requirements=[["mirrored"]])
    return out


def new_json(adv_id: str, spec: dict) -> dict:
    display = {"icon": {"id": spec.get("icon") or "minecraft:knowledge_book"},
               "title": {"translate": lang_prefix(adv_id) + ".title"},
               "description": {"translate": lang_prefix(adv_id) + ".description"},
               "frame": spec.get("frame") or "task"}
    if not spec.get("parent"):
        display["background"] = spec.get("background") or DEFAULT_BG
    if spec.get("unlockedBy"):  # a tab head that follows another advancement: hidden until then, silent
        display.update(show_toast=False, announce_to_chat=False, hidden=True)
        criteria = {"unlocked": {"trigger": "minecraft:impossible"}}
    else:
        display.update(show_toast=True, announce_to_chat=True, hidden=False)
        criteria = {"todo": {"trigger": "minecraft:impossible"}}
    out = {"parent": spec["parent"]} if spec.get("parent") else {}
    out.update(display=display, criteria=criteria, requirements=[list(criteria)])
    return out


def write_new(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")


# ---------- the change set -------------------------------------------------------------------

def load_tabs(tabs_file: Path) -> dict:
    data = json.loads(tabs_file.read_text()) if tabs_file.is_file() else {}
    return {"order": data.get("order", []), "tabNames": data.get("tabNames", {}), "copies": data.get("copies", {}),
            "unlockedBy": data.get("unlockedBy", {}),
            **{k: v for k, v in data.items() if k not in ("order", "tabNames", "copies", "unlockedBy")}}


def set_capstone(tabs: dict, adv_id: str, flags: dict, adv_dir: Path) -> None:
    """Record "counts towards the Everything Burrito" / "reset by It's Not That Simple" for one advancement.

    Only a value that differs from the mod's default is stored, so the tabs file lists the exceptions:
    ``burrito`` against the default rule, ``startAgainReset`` against "reset what the burrito needs".
    """
    adv_path(adv_id, adv_dir)
    copies = capstone_rules.linked(tabs)
    if not capstone_rules.is_editable(adv_id, copies):
        raise ApplyError(f"{adv_id}: its Everything Burrito / start-again settings are fixed")
    for key in ("required", "reset"):
        if key in flags and not isinstance(flags[key], bool):
            raise ApplyError(f"{adv_id}: {key} must be true or false")
    burrito, reset = tabs.setdefault("burrito", {}), tabs.setdefault("startAgainReset", {})
    default_req = capstone_rules.default_required(adv_id, copies, capstone_rules.book_paths())
    required = flags.get("required", burrito.get(adv_id, default_req))
    _store(burrito, adv_id, required, default_req)
    _store(reset, adv_id, flags.get("reset", reset.get(adv_id, required)), required)
    for table in ("burrito", "startAgainReset"):
        if not tabs[table]:
            del tabs[table]


def _store(table: dict, adv_id: str, value: bool, default: bool) -> None:
    if value == default:
        table.pop(adv_id, None)
    else:
        table[adv_id] = value


def check_source(source: str, adv_dir: Path, new_files: dict) -> None:
    """An unlock source must be a Dungeon Train advancement that exists (or is made in the same save)."""
    path = adv_path(source, adv_dir)
    if not (path.is_file() or path in new_files):
        raise ApplyError(f"unlock source {source} does not exist")


def tab_lang_key(root_id: str) -> str:
    """``dungeon_train/tab_train_explorer`` → ``…tab.train_explorer``; ``secrete_menu/root`` → ``…tab.secrete_menu``."""
    parts = ID_RE.match(root_id).group(1).split("/")
    name = parts[-2] if parts[-1] == "root" else parts[-1]
    return "advancements.dungeontrain.tab." + re.sub(r"^tab_", "", name)


def apply_changes(changes: dict, *, adv_dir: Path = ADV_DIR, tabs_file: Path = TABS_FILE,
                  lang_file: Path = LANG_FILE, dry_run: bool = False) -> Report:
    if not isinstance(changes, dict) or changes.get("version") != 1:
        raise ApplyError("expected a change set with \"version\": 1")
    report = Report()
    files: dict[Path, str] = {}

    def text_of(path: Path) -> str:
        if path not in files:
            files[path] = path.read_text()
        return files[path]

    tabs = load_tabs(tabs_file)
    tabs_dirty = False
    lang: dict[str, str] = {}
    new_files: dict[Path, dict] = {}

    created = changes.get("created") or {}
    # Originals before their copies, so a copy can mirror an advancement made in the same save.
    for adv_id in sorted(created, key=lambda i: bool(created[i].get("copyOf"))):
        spec = created[adv_id]
        path = adv_path(adv_id, adv_dir)
        if path.exists():
            raise ApplyError(f"{adv_id} already exists")
        if spec.get("parent"):
            adv_path(spec["parent"], adv_dir)
        if spec.get("copyOf"):
            src_path = adv_path(spec["copyOf"], adv_dir)
            source = new_files.get(src_path) or json.loads(existing(spec["copyOf"], adv_dir).read_text())
            new_files[path] = copy_json(source, spec.get("parent"), spec.get("icon"), spec.get("background"))
            tabs["copies"][adv_id] = spec["copyOf"]
            tabs_dirty = True
        else:
            new_files[path] = new_json(adv_id, spec)
            lang[lang_prefix(adv_id) + ".title"] = spec.get("title") or "New Tab"
            lang[lang_prefix(adv_id) + ".description"] = spec.get("description") or ""
            if spec.get("unlockedBy"):
                if spec.get("parent"):
                    raise ApplyError(f"{adv_id}: only a tab's first advancement can be unlocked by another")
                check_source(spec["unlockedBy"], adv_dir, new_files)
                tabs["unlockedBy"][adv_id] = spec["unlockedBy"]
                tabs_dirty = True
            else:
                report.todo.append(f"{adv_id} is granted by nothing yet: give it a real criterion in {shown(path)}.")

    for adv_id, parent in (changes.get("parents") or {}).items():
        path = existing(adv_id, adv_dir)
        if parent is not None and not (adv_path(parent, adv_dir).is_file() or adv_path(parent, adv_dir) in new_files):
            raise ApplyError(f"{adv_id}: parent {parent} does not exist")
        text = set_parent(text_of(path), parent)
        if parent is None and not has_background(text):
            text = set_background(text, DEFAULT_BG)
        files[path] = text
        if CHAIN.match(adv_id) and adv_id != CHAIN_ANCHOR_CARRIER:
            report.todo.append(f"{adv_id} is on the band journey: its parent is set by band order at load, "
                               "so this move only shows when no band layout is active.")

    for adv_id, item in (changes.get("icons") or {}).items():
        if not ITEM_RE.match(item or ""):
            raise ApplyError(f"{adv_id}: bad item id {item!r}")
        path = existing(adv_id, adv_dir)
        files[path] = set_icon(text_of(path), item)

    for adv_id, v in (changes.get("values") or {}).items():
        to = v.get("to") if isinstance(v, dict) else None
        if not isinstance(to, int) or to < 1:
            raise ApplyError(f"{adv_id}: required value must be a positive whole number")
        path = existing(adv_id, adv_dir)
        files[path] = set_value(text_of(path), v.get("field", ""), to)

    for root, bg in (changes.get("backgrounds") or {}).items():
        if not ITEM_RE.match(bg or "") or not bg.endswith(".png"):
            raise ApplyError(f"{root}: bad background {bg!r}")
        path = adv_path(root, adv_dir)
        if path in new_files:
            new_files[path]["display"]["background"] = bg
        else:
            files[path] = set_background(text_of(existing(root, adv_dir)), bg)

    for root, name in (changes.get("tabNames") or {}).items():
        adv_path(root, adv_dir)
        if name:
            key = tab_lang_key(root)
            lang[key] = name
            tabs["tabNames"][root] = key
            report.todo.append(f"Translate {key} into the other locales (merge-locale-keys.py), then stamp provenance.")
        else:
            tabs["tabNames"].pop(root, None)
        tabs_dirty = True

    for adv_id, flags in (changes.get("capstone") or {}).items():
        set_capstone(tabs, adv_id, flags, adv_dir)
        tabs_dirty = True

    for root, source in (changes.get("unlocks") or {}).items():
        path = existing(root, adv_dir)
        if '"parent"' in text_of(path):
            raise ApplyError(f"{root}: only a tab's first advancement can be unlocked by another")
        if root in tabs["copies"]:
            raise ApplyError(f"{root}: a tab copy already follows its original")
        if source:
            check_source(source, adv_dir, new_files)
            tabs["unlockedBy"][root] = source
        else:
            tabs["unlockedBy"].pop(root, None)
        tabs_dirty = True

    if changes.get("order"):
        for root in changes["order"]:  # the Editor tab may be ordered, never edited
            if not re.match(r"^dungeontrain:[a-z0-9_]+(/[a-z0-9_]+)+$", root or ""):
                raise ApplyError(f"bad tab id in order: {root!r}")
        tabs["order"] = list(changes["order"])
        tabs_dirty = True

    for adv_id in changes.get("deleted") or []:
        path = adv_path(adv_id, adv_dir)
        if adv_id not in tabs["copies"] and adv_id not in tabs["unlockedBy"]:
            raise ApplyError(f"{adv_id}: only tab copies and unlocked tab heads can be deleted here; remove others by hand")
        tabs["copies"].pop(adv_id, None)
        tabs["unlockedBy"].pop(adv_id, None)
        tabs_dirty = True
        if not dry_run and path.is_file():
            path.unlink()
        report.wrote(path)

    if lang and any(k.endswith((".title", ".description")) for k in lang):
        report.todo.append("Translate the new advancement text into the other locales (merge-locale-keys.py).")

    for path, text in files.items():
        if path.read_text() != text:
            if not dry_run:
                path.write_text(text)
            report.wrote(path)
    for path, data in new_files.items():
        if not dry_run:
            write_new(path, data)
        report.wrote(path)
    if tabs_dirty:
        if not tabs["unlockedBy"]:
            tabs.pop("unlockedBy")
        if not dry_run:
            tabs_file.parent.mkdir(parents=True, exist_ok=True)
            tabs_file.write_text(json.dumps(tabs, indent=2, ensure_ascii=False) + "\n")
        report.wrote(tabs_file)
    if lang:
        if dry_run or add_lang_keys(lang_file, lang):
            report.wrote(lang_file)
    return report


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Write an advancement-editor change set into the repo.")
    ap.add_argument("changes", type=Path, help="the JSON from the editor's Copy changes button")
    ap.add_argument("--dry-run", action="store_true", help="say what would change, write nothing")
    args = ap.parse_args(argv)
    try:
        report = apply_changes(json.loads(args.changes.read_text()), dry_run=args.dry_run)
    except (ApplyError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    verb = "would change" if args.dry_run else "changed"
    print(f"{verb} {len(report.written)} file(s):")
    for f in report.written:
        print(f"  {f}")
    for t in report.todo:
        print(f"todo: {t}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
