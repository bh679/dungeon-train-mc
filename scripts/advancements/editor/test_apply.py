#!/usr/bin/env python3
"""Tests for the advancement editor's apply.py — the change set the editor saves into the repo.

Run: python3 scripts/advancements/editor/test_apply.py   (or via pytest). Stdlib only: apply.py
needs no Pillow, so this runs in CI without it.
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import apply as MOD  # noqa: E402
import capstone_rules  # noqa: E402
import commit as COMMIT  # noqa: E402

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
    assert not any("granted by" in t for t in report.todo), report.todo


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


def test_visibility_stored_only_when_not_default():
    ws = Workspace()
    ws.apply({"visibility": {DT + "carts_100": "always", DT + "root": "always"}})
    assert json.loads(ws.tabs.read_text())["visibility"] == {DT + "carts_100": "always"}, "root defaults to always"
    ws.apply({"visibility": {DT + "carts_100": "parent"}})
    assert "visibility" not in json.loads(ws.tabs.read_text())
    try:
        ws.apply({"visibility": {DT + "carts_100": "sometimes"}})
    except MOD.ApplyError:
        return
    raise AssertionError("accepted a bad mode")


def test_reword_existing_text():
    ws = Workspace()
    key = "advancements.dungeontrain.dungeon_train.carts_100."
    ws.lang.write_text('{\n  %s: "Dungeon Train Explorer",\n  %s: "Traverse %%s carriages.",\n  "gui.dungeontrain.other": "x"\n}'
                       % (json.dumps(key + "title"), json.dumps(key + "description")))
    report = ws.apply({"texts": {DT + "carts_100": {"description": "Ride %s carriages.", "hint": "Keep going."}}})
    lang = json.loads(ws.lang.read_text())
    assert lang[key + "description"] == "Ride %s carriages." and lang[key + "hint"] == "Keep going."
    assert lang[key + "title"] == "Dungeon Train Explorer"
    assert any("Re-translate" in t and key + "description" in t for t in report.todo), report.todo
    assert any("Translate the new" in t for t in report.todo), report.todo  # the hint is new
    for bad in ({"description": "Ride carriages."}, {"title": "  "}, {"colour": "red"}):
        try:
            ws.apply({"texts": {DT + "carts_100": bad}})
        except MOD.ApplyError:
            continue
        raise AssertionError(f"accepted {bad}")


def test_duplicate_is_a_child_earned_the_same_way():
    ws = Workspace()
    dup = DT + "a_thousand_carriages"
    report = ws.apply({"created": {dup: {"parent": DT + "carts_100", "duplicateOf": DT + "carts_100",
                                         "title": "A Thousand Carriages", "description": "Traverse %s carriages.",
                                         "hint": "Keep going.", "icon": "minecraft:chest_minecart", "value": 1000}}})
    data = json.loads(ws.text("dungeon_train/a_thousand_carriages"))
    assert data["parent"] == DT + "carts_100"
    assert data["criteria"]["milestone"]["trigger"] == "dungeontrain:carts_in_run"
    assert data["criteria"]["milestone"]["conditions"]["threshold"] == 1000
    assert data["display"]["icon"]["id"] == "minecraft:chest_minecart" and data["display"]["hidden"] is True
    key = "advancements.dungeontrain.dungeon_train.a_thousand_carriages."
    assert data["display"]["title"]["translate"] == key + "title"
    lang = json.loads(ws.lang.read_text())
    assert lang[key + "title"] == "A Thousand Carriages" and lang[key + "hint"] == "Keep going."
    assert ws.text("dungeon_train/carts_100") == CARTS, "the original is untouched"
    assert any("Translate the new" in t for t in report.todo)
    assert not any("granted by" in t for t in report.todo), report.todo


def test_duplicate_of_a_code_granted_advancement_says_so():
    ws = Workspace()
    (ws.adv / "dungeon_train" / "explored.json").write_text(json.dumps({
        "parent": DT + "root", "display": {"icon": {"id": "minecraft:map"}, "title": {"translate": "x.title"},
                                           "description": {"translate": "x.description"}, "frame": "challenge"},
        "criteria": {"explored": {"trigger": "minecraft:impossible"}}, "requirements": [["explored"]]}))
    report = ws.apply({"created": {DT + "explored_copy": {"parent": DT + "explored", "duplicateOf": DT + "explored",
                                                          "title": "Explored Again"}}})
    assert any("granted by code" in t for t in report.todo), report.todo
    assert COMMIT.blocking(report.todo), "Save & commit stops for it"
    ws2 = Workspace()
    (ws2.adv / "dungeon_train" / "melon.json").write_text(json.dumps({
        "parent": DT + "root", "display": {"icon": {"id": "minecraft:melon"}, "title": {"translate": "m.title"},
                                           "description": {"translate": "m.description"}},
        "criteria": {"challenge": {"trigger": "dungeontrain:code_granted", "conditions": {"threshold": 100}}},
        "requirements": [["challenge"]]}))
    report = ws2.apply({"created": {DT + "melons": {"parent": DT + "melon", "duplicateOf": DT + "melon",
                                                    "title": "More Melons", "value": 1000}}})
    assert any("granted by code" in t for t in report.todo), "a per-life challenge copy needs its code too"


# ---------- Save & commit (commit.py) ----------

def test_commit_pieces():
    text, version = COMMIT.bump_patch("a=1\nmod_version=0.1188.22\nb=2\n")
    assert version == "0.1188.23" and text == "a=1\nmod_version=0.1188.23\nb=2\n", text
    assert COMMIT.summary({"icons": {"a": 1, "b": 2}, "parents": {"c": None}}) == "1 move, 2 icons"
    assert COMMIT.summary({}) == "no changes"
    assert COMMIT.blocking(["Translate x into the other locales", "dungeontrain:a is on the band journey: …"]) == \
        ["Translate x into the other locales"]
    for branch in ("main", "master", "HEAD", ""):
        try:
            COMMIT.refuse_branch(branch)
        except COMMIT.CommitError:
            continue
        raise AssertionError(f"committed on {branch!r}")
    COMMIT.refuse_branch("dev/advancement-tabs")


def _git(cwd, *args):
    subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True)


def test_save_and_commit_commits_only_what_it_wrote_and_pushes():
    ws = Workspace()
    root = ws.root
    (root / "gradle.properties").write_text("mod_version=1.2.3\n")
    (root / "unrelated.txt").write_text("before\n")
    remote = Path(tempfile.mkdtemp(prefix="adv-editor-remote-")) / "r.git"
    _git(root.parent, "init", "-q", "--bare", str(remote))
    _git(root, "init", "-q", "-b", "dev/test")
    _git(root, "config", "user.email", "t@example.com")
    _git(root, "config", "user.name", "Test")
    _git(root, "add", "-A")
    _git(root, "commit", "-q", "-m", "base")
    _git(root, "remote", "add", "origin", str(remote))
    (root / "unrelated.txt").write_text("after, must not be committed\n")
    repo = COMMIT.Repo(root=root, adv_dir=ws.adv, tabs_file=ws.tabs, lang_file=ws.lang)
    res = COMMIT.save_and_commit({"version": 1, "icons": {DT + "carts_100": "minecraft:chest"}}, repo,
                                 run_tests=lambda r: None)
    assert res.committed and res.version == "1.2.4", res
    files = subprocess.run(["git", "show", "--name-only", "--format=", "HEAD"], cwd=root, capture_output=True,
                           text=True).stdout.split()
    assert sorted(files) == sorted(["advancement/dungeon_train/carts_100.json", "gradle.properties",
                                    "src/test/resources/advancement/burrito_required.txt"]), files
    assert "minecraft:chest" in ws.text("dungeon_train/carts_100")
    assert subprocess.run(["git", "status", "--porcelain", "unrelated.txt"], cwd=root, capture_output=True,
                          text=True).stdout.strip(), "the unrelated edit stays uncommitted"
    pushed = subprocess.run(["git", "rev-parse", "dev/test"], cwd=remote, capture_output=True, text=True).stdout.strip()
    assert pushed.startswith(res.commit), (pushed, res.commit)
    try:
        COMMIT.save_and_commit({"version": 1, "icons": {DT + "carts_100": "minecraft:chest"}}, repo,
                               run_tests=lambda r: None)
    except COMMIT.CommitError as exc:
        assert "Nothing to save" in str(exc)
    else:
        raise AssertionError("committed an empty save")


def test_save_and_commit_stops_for_translation():
    ws = Workspace()
    (ws.root / "gradle.properties").write_text("mod_version=1.2.3\n")
    _git(ws.root, "init", "-q", "-b", "dev/test")
    repo = COMMIT.Repo(root=ws.root, adv_dir=ws.adv, tabs_file=ws.tabs, lang_file=ws.lang)
    res = COMMIT.save_and_commit({"version": 1, "tabNames": {DT + "root": "Renamed"}}, repo, push=False,
                                 run_tests=lambda r: None)
    assert not res.committed and "need a person" in res.message, res
    assert (ws.root / "gradle.properties").read_text() == "mod_version=1.2.3\n", "no version bump without a commit"


def test_more_icons_are_real_drawable_items():
    """Needs Pillow and a built workspace (game jars in ~/.gradle); skipped where they are missing, as in CI."""
    try:
        import base64, io
        from PIL import Image
        import build
        bundle, _ = build.build_bundle()
    except (ImportError, SystemExit):
        print("     (skipped: no Pillow or game jars)")
        return
    more = build.more_icons(bundle)
    assert len(more) > 500, len(more)
    for item_id, uri in more.items():
        assert ":" in item_id and item_id not in bundle["iconIds"], item_id
        img = Image.open(io.BytesIO(base64.b64decode(uri.split(",", 1)[1]))).convert("RGBA")
        assert img.getextrema()[3][1] > 0, f"{item_id} draws nothing"
        assert img.getpixel((16, 16)) != (180, 60, 200, 255), f"{item_id} drew the missing-icon square"


GOLDEN = MOD.REPO / "src/test/resources/advancement/burrito_required.txt"


def burrito_set() -> str:
    return capstone_rules.golden_burrito(MOD.ADV_DIR, MOD.load_tabs(MOD.TABS_FILE))


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
