#!/usr/bin/env python3
"""Unit tests for import-english-edits.py — the operator's English, back into the repo.

Drives the script as a CLI against isolated temp fixtures (matching
test_import_approved_translations.py), feeding it saved payloads through --from-file so nothing
here touches the network. What is proved is what this script owns: which keys it will write, the
one case where it may add a line rather than rewrite one, and the two ways it refuses.

Run: python3 scripts/localization/test_import_english_edits.py   (or via pytest)
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "import-english-edits.py")

TITLE = "advancements.dungeontrain.dungeon_train.no_break_100.title"
DESC = "advancements.dungeontrain.dungeon_train.no_break_100.description"
HINT = "advancements.dungeontrain.dungeon_train.no_break_100.hint"
OTHER_TITLE = "advancements.dungeontrain.editor.made_pillar.title"

#: A lang file shaped like the real one: blank-line grouping the importer must not flatten, and an
#: advancement whose hint line does not exist yet (six really ship that way).
EN = {
    "gui.dungeontrain.settings.title": "Settings",
    TITLE: "Look, Don't Touch",
    DESC: "Travel 100 carriages without breaking a single block.",
    OTHER_TITLE: "Pillar of the Community",
}


def workspace(lang=EN):
    ws = tempfile.mkdtemp(prefix="import-english-test-")
    path = os.path.join(ws, "en_us.json")
    # Written the way the repo's own files are: two-space indent, a blank line between groups.
    body = ",\n".join(f'  {json.dumps(k)}: {json.dumps(v)}' for k, v in lang.items())
    with open(path, "w", encoding="utf-8") as f:
        f.write("{\n" + body + "\n}\n")
    return ws, path


def run(payload, lang_path, *extra):
    ws = os.path.dirname(lang_path)
    src = os.path.join(ws, "payload.json")
    with open(src, "w", encoding="utf-8") as f:
        json.dump({"units": payload}, f)
    proc = subprocess.run(
        [sys.executable, SCRIPT, "--from-file", src, "--lang-file", lang_path, *extra],
        capture_output=True, text=True)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    return proc.stdout


def read(lang_path):
    with open(lang_path, encoding="utf-8") as f:
        return json.load(f)


def test_rewrites_an_edited_title():
    ws, path = workspace()
    out = run({TITLE: {"value": "Not a scratch", "shipped": "Look, Don't Touch"}}, path)
    assert read(path)[TITLE] == "Not a scratch", out
    assert "rewrote" in out, out


def test_dry_run_writes_nothing():
    ws, path = workspace()
    out = run({TITLE: {"value": "Not a scratch", "shipped": "Look, Don't Touch"}}, path, "--dry-run")
    assert read(path)[TITLE] == "Look, Don't Touch", out
    assert "would rewrite" in out, out


def test_adds_a_first_hint_next_to_its_advancement():
    # The six deliberately-??? advancements have no .hint line at all. Writing the first one is an
    # ordinary edit, and it belongs beside the advancement it describes.
    ws, path = workspace()
    out = run({HINT: {"value": "The train is not yours to dismantle.", "shipped": ""}}, path)
    lang = read(path)
    assert lang[HINT] == "The train is not yours to dismantle.", out
    keys = list(lang)
    assert keys.index(HINT) == keys.index(TITLE) + 1, keys
    # Every other line survived the insert intact.
    assert lang[TITLE] == "Look, Don't Touch" and lang[DESC].startswith("Travel 100"), lang


def test_will_not_invent_a_title_for_an_advancement_that_is_not_here():
    ws, path = workspace()
    ghost = "advancements.dungeontrain.dungeon_train.not_a_thing.title"
    out = run({ghost: {"value": "Ghost", "shipped": ""}}, path)
    assert ghost not in read(path), out
    assert "DEFERRED" in out, out


def test_will_not_hang_a_hint_on_nothing():
    ws, path = workspace()
    ghost = "advancements.dungeontrain.dungeon_train.not_a_thing.hint"
    out = run({ghost: {"value": "Boo", "shipped": ""}}, path)
    assert ghost not in read(path), out
    assert "no advancement here to hang a hint on" in out, out


def test_a_repo_edit_since_the_relay_edit_wins():
    # The relay row says it replaced "Look, Don't Touch"; the file now says something else, so
    # somebody rewrote it in git. Overwriting that would silently undo their work.
    ws, path = workspace(dict(EN, **{TITLE: "Hands Off"}))
    out = run({TITLE: {"value": "Not a scratch", "shipped": "Look, Don't Touch"}}, path)
    assert read(path)[TITLE] == "Hands Off", out
    assert "has changed since this was written" in out, out


def test_a_key_outside_the_allowlist_is_refused():
    ws, path = workspace()
    out = run({"gui.dungeontrain.settings.title": {"value": "Options", "shipped": "Settings"}}, path)
    assert read(path)["gui.dungeontrain.settings.title"] == "Settings", out
    assert "not an advancement title, description or hint" in out, out


def test_an_already_imported_row_is_a_no_op():
    ws, path = workspace()
    out = run({TITLE: {"value": "Look, Don't Touch", "shipped": "Look, Don't Touch"}}, path)
    assert "already current" in out, out
    assert "rewrote" not in out, out


def test_deferrals_are_reported_to_a_file_for_the_pr_body():
    ws, path = workspace()
    out_path = os.path.join(ws, "deferred.json")
    run({"gui.dungeontrain.settings.title": {"value": "Options"}}, path, "--deferred-out", out_path)
    with open(out_path, encoding="utf-8") as f:
        deferred = json.load(f)
    assert len(deferred) == 1 and "gui.dungeontrain.settings.title" in deferred[0], deferred


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
    sys.exit(_main())
