#!/usr/bin/env python3
"""Tests for the advancement editor's apply.py — the change set the editor saves into the repo.

Run: python3 scripts/advancements/editor/test_apply.py   (or via pytest). Stdlib only: apply.py
needs no Pillow, so this runs in CI without it.
"""

from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import apply as MOD  # noqa: E402
import capstone_rules  # noqa: E402

DT = "dungeontrain:dungeon_train/"

ROOT = '''{
  "display": {
    "icon": { "id": "minecraft:minecart" },
    "title": { "translate": "advancements.dungeontrain.dungeon_train.root.title" },
    "frame": "task",
    "background": "minecraft:textures/gui/advancements/backgrounds/adventure.png",
    "hidden": false
  },
  "criteria": { "boarded": { "trigger": "dungeontrain:editor_action", "conditions": { "actionId": "boarded" } } },
  "requirements": [["boarded"]]
}
'''

CARTS = '''{
  "parent": "dungeontrain:dungeon_train/root",
  "display": {
    "icon": { "id": "minecraft:minecart" },
    "title": { "translate": "advancements.dungeontrain.dungeon_train.carts_100.title" },
    "description": { "translate": "advancements.dungeontrain.dungeon_train.carts_100.description" },
    "frame": "task",
    "hidden": true
  },
  "criteria": {
    "milestone": {
      "trigger": "dungeontrain:carts_in_run",
      "conditions": { "threshold": 100 }
    }
  },
  "requirements": [["milestone"]]
}
'''

LANG = '''{
  "advancements.dungeontrain.dungeon_train.root.title": "Dungeon Train",
  "gui.dungeontrain.other": "x"
}'''


class Workspace:
    def __init__(self):
        self.root = Path(tempfile.mkdtemp(prefix="adv-editor-apply-"))
        self.adv = self.root / "advancement"
        (self.adv / "dungeon_train").mkdir(parents=True)
        (self.adv / "dungeon_train" / "root.json").write_text(ROOT)
        (self.adv / "dungeon_train" / "carts_100.json").write_text(CARTS)
        self.tabs = self.root / "advancement_tabs.json"
        self.lang = self.root / "en_us.json"
        self.lang.write_text(LANG)

    def apply(self, changes: dict, dry_run: bool = False) -> MOD.Report:
        return MOD.apply_changes({"version": 1, **changes}, adv_dir=self.adv, tabs_file=self.tabs,
                                 lang_file=self.lang, dry_run=dry_run)

    def text(self, path: str) -> str:
        return (self.adv / (path + ".json")).read_text()


def test_value_edit_changes_one_line():
    ws = Workspace()
    ws.apply({"values": {DT + "carts_100": {"field": "threshold", "from": 100, "to": 50}}})
    assert ws.text("dungeon_train/carts_100") == CARTS.replace('"threshold": 100', '"threshold": 50')


def test_move_rewrites_only_the_parent():
    ws = Workspace()
    (ws.adv / "dungeon_train" / "other.json").write_text(CARTS)
    ws.apply({"parents": {DT + "carts_100": DT + "other"}})
    assert ws.text("dungeon_train/carts_100") == CARTS.replace('dungeon_train/root"', 'dungeon_train/other"')


def test_making_a_tab_drops_the_parent_and_adds_a_background():
    ws = Workspace()
    ws.apply({"parents": {DT + "carts_100": None}})
    data = json.loads(ws.text("dungeon_train/carts_100"))
    assert "parent" not in data
    assert data["display"]["background"] == MOD.DEFAULT_BG


def test_icon_and_background():
    ws = Workspace()
    ws.apply({"icons": {DT + "carts_100": "minecraft:furnace_minecart"},
              "backgrounds": {DT + "root": "minecraft:textures/block/stone_bricks.png"}})
    assert '"id": "minecraft:furnace_minecart"' in ws.text("dungeon_train/carts_100")
    assert json.loads(ws.text("dungeon_train/root"))["display"]["background"] == "minecraft:textures/block/stone_bricks.png"


def test_copy_tab_is_a_hidden_silent_mirror_and_is_recorded():
    ws = Workspace()
    copy = DT + "tab_explorer"
    report = ws.apply({"created": {copy: {"parent": None, "copyOf": DT + "carts_100", "icon": "minecraft:map",
                                          "background": "minecraft:textures/block/oak_planks.png"}},
                       "tabNames": {copy: "Explorer"}})
    data = json.loads(ws.text("dungeon_train/tab_explorer"))
    assert "parent" not in data
    assert data["display"]["hidden"] is True and data["display"]["show_toast"] is False
    assert data["display"]["title"] == json.loads(CARTS)["display"]["title"]
    assert data["display"]["icon"]["id"] == "minecraft:map"
    assert data["criteria"]["mirrored"]["trigger"] == "minecraft:impossible"
    tabs = json.loads(ws.tabs.read_text())
    assert tabs["copies"] == {copy: DT + "carts_100"}
    assert tabs["tabNames"] == {copy: "advancements.dungeontrain.tab.explorer"}
    assert json.loads(ws.lang.read_text())["advancements.dungeontrain.tab.explorer"] == "Explorer"
    assert any("Translate" in t for t in report.todo)


def test_copy_of_an_advancement_made_in_the_same_save():
    ws = Workspace()
    ws.apply({"created": {
        DT + "gate": {"parent": DT + "root", "copyOf": "dungeontrain:photos/root"},
        "dungeontrain:photos/root": {"parent": None, "title": "Photos", "description": "Take one", "icon": "minecraft:map"},
    }})
    gate = json.loads(ws.text("dungeon_train/gate"))
    assert gate["parent"] == DT + "root"
    assert gate["display"]["title"] == {"translate": "advancements.dungeontrain.photos.root.title"}


def test_lang_insert_keeps_the_file_valid_and_in_place():
    ws = Workspace()
    MOD.add_lang_keys(ws.lang, {"advancements.dungeontrain.dungeon_train.carts_100.title": "Explorer"})
    lines = ws.lang.read_text().split("\n")
    assert lines[2].strip().startswith('"advancements.dungeontrain.dungeon_train.carts_100.title"'), lines
    json.loads(ws.lang.read_text())


def test_dry_run_writes_nothing():
    ws = Workspace()
    report = ws.apply({"values": {DT + "carts_100": {"field": "threshold", "to": 5}}}, dry_run=True)
    assert report.written and ws.text("dungeon_train/carts_100") == CARTS


def test_rejects_bad_input():
    ws = Workspace()
    for bad in ({"parents": {DT + "missing": None}},
                {"parents": {DT + "carts_100": DT + "missing"}},
                {"values": {DT + "carts_100": {"field": "threshold", "to": 0}}},
                {"icons": {DT + "carts_100": "not an id"}},
                {"parents": {"dungeontrain:editor/root": None}}):
        try:
            ws.apply(bad)
        except MOD.ApplyError:
            continue
        raise AssertionError(f"accepted {bad}")
    assert ws.text("dungeon_train/carts_100") == CARTS


def test_capstone_override_is_stored_only_when_it_differs():
    ws = Workspace()
    ws.apply({"capstone": {DT + "carts_100": {"required": False}}})
    tabs = json.loads(ws.tabs.read_text())
    assert tabs["burrito"] == {DT + "carts_100": False}
    assert "startAgainReset" not in tabs, "reset follows the burrito unless set on its own"
    ws.apply({"capstone": {DT + "carts_100": {"required": True, "reset": False}}})
    tabs = json.loads(ws.tabs.read_text())
    assert "burrito" not in tabs, "back to the default: no override left"
    assert tabs["startAgainReset"] == {DT + "carts_100": False}


def test_capstone_refuses_fixed_advancements():
    ws = Workspace()
    (ws.adv / "dungeon_train" / "completionist.json").write_text(CARTS)
    for bad in ({DT + "completionist": {"required": False}}, {DT + "carts_100": {"required": "yes"}}):
        try:
            ws.apply({"capstone": bad})
        except MOD.ApplyError:
            continue
        raise AssertionError(f"accepted {bad}")


def test_new_tab_unlocked_by_needs_no_trigger():
    ws = Workspace()
    tab = DT + "tab_challenges"
    report = ws.apply({"created": {tab: {"parent": None, "title": "Challenges", "description": "Harder rules",
                                         "icon": "minecraft:iron_sword", "unlockedBy": DT + "carts_100"}}})
    data = json.loads(ws.text("dungeon_train/tab_challenges"))
    assert data["display"]["hidden"] is True and data["display"]["show_toast"] is False
    assert json.loads(ws.tabs.read_text())["unlockedBy"] == {tab: DT + "carts_100"}
    assert not any("granted by nothing" in t for t in report.todo), report.todo


def test_change_and_clear_a_tab_unlock():
    ws = Workspace()
    (ws.adv / "dungeon_train" / "tab_x.json").write_text(ROOT)
    ws.apply({"unlocks": {DT + "tab_x": DT + "carts_100"}})
    assert json.loads(ws.tabs.read_text())["unlockedBy"] == {DT + "tab_x": DT + "carts_100"}
    ws.apply({"unlocks": {DT + "tab_x": None}})
    assert "unlockedBy" not in json.loads(ws.tabs.read_text())
    for bad in ({DT + "carts_100": DT + "root"}, {DT + "tab_x": DT + "missing"}):
        try:
            ws.apply({"unlocks": bad})
        except MOD.ApplyError:
            continue
        raise AssertionError(f"accepted {bad}")


GOLDEN = MOD.REPO / "src/test/resources/advancement/burrito_required.txt"


def burrito_set() -> str:
    tabs = MOD.load_tabs(MOD.TABS_FILE)
    books = capstone_rules.book_paths()
    ids = sorted("dungeontrain:" + f.relative_to(MOD.ADV_DIR).with_suffix("").as_posix() for f in MOD.ADV_DIR.rglob("*.json"))
    return "\n".join(i for i in ids if capstone_rules.effective(i, capstone_rules.linked(tabs), books, tabs)[0]) + "\n"


def test_book_paths_parse():
    books = capstone_rules.book_paths()
    assert "dungeon_train/the_enchiridion" in books and "dungeon_train/taking_notes" in books, books


def test_burrito_mirror_matches_golden():
    """The Java side (AdvancementTabsTest) checks the same file, so the two rules can't drift apart."""
    assert burrito_set() == GOLDEN.read_text(), "run: python3 scripts/advancements/editor/test_apply.py --regen-golden"


def _main():
    funcs = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    failures = 0
    for fn in funcs:
        try:
            fn()
            print(f"ok   {fn.__name__}")
        except AssertionError as exc:
            failures += 1
            print(f"FAIL {fn.__name__}: {exc}")
    print(f"\n{len(funcs) - failures}/{len(funcs)} passed")
    return 1 if failures else 0


if __name__ == "__main__":
    if "--regen-golden" in sys.argv:
        GOLDEN.write_text(burrito_set())
        print(f"wrote {GOLDEN}")
        sys.exit(0)
    sys.exit(_main())
