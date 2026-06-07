#!/usr/bin/env python3
"""Unit tests for apply-change.py — queue pop + auto-balance clamping + safety."""
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "apply-change.py")


def make_workspace():
    ws = tempfile.mkdtemp(prefix="auto-release-test-")
    os.makedirs(os.path.join(ws, ".github/auto-release"), exist_ok=True)
    loot_dir = os.path.join(ws, "src/main/resources/data/dungeontrain/loot_table/chests")
    os.makedirs(loot_dir, exist_ok=True)
    return ws, loot_dir


def write_json(path, obj):
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)


def run(ws, now_epoch, **extra_env):
    env = {
        **os.environ,
        "STATE_FILE": os.path.join(ws, ".github/auto-release/state.json"),
        "QUEUE_FILE": os.path.join(ws, ".github/auto-release/queue.json"),
        "LOOT_FILE": os.path.join(ws, "src/main/resources/data/dungeontrain/loot_table/chests/auto_balancing.json"),
        "GRADLE_PROPERTIES_FILE": os.path.join(ws, "gradle.properties"),
        "NOW_EPOCH": str(now_epoch),
    }
    env.pop("GITHUB_OUTPUT", None)
    # Default tests to an empty sibling-mod release list so try_sibling_bump
    # never reaches `gh release list` (which would hit the network / require
    # auth). Tests that need sibling-mod behaviour override these explicitly.
    env.setdefault("AIN_RELEASES_OVERRIDE", "[]")
    env.setdefault("AIS_RELEASES_OVERRIDE", "[]")
    env.setdefault("PMOB_RELEASES_OVERRIDE", "[]")
    for k, v in extra_env.items():
        if v is None:
            env.pop(k, None)
        else:
            env[k] = v
    return subprocess.run([sys.executable, SCRIPT], cwd=ws, env=env,
                          capture_output=True, text=True)


def seed_workspace(ws, queue, loot_weight=10, ain_version="0.25.0", ais_version="0.1.0",
                   pmob_version="0.14.0"):
    write_json(os.path.join(ws, ".github/auto-release/state.json"),
               {"schedule_anchor": "2026-05-18T12:00:00Z", "last_auto_release_at": None,
                "last_anchor_source": "test"})
    write_json(os.path.join(ws, ".github/auto-release/queue.json"), queue)
    write_json(os.path.join(ws, "src/main/resources/data/dungeontrain/loot_table/chests/auto_balancing.json"),
               {"type": "minecraft:chest", "pools": [{"rolls": 1, "entries": [
                   {"type": "minecraft:item", "name": "minecraft:wheat", "weight": loot_weight}]}]})
    with open(os.path.join(ws, "gradle.properties"), "w") as f:
        f.write(
            f"mod_version=0.219.0\n"
            f"adventureitemnames_version={ain_version}\n"
            f"adventureitemstats_version={ais_version}\n"
            f"playermob_version={pmob_version}\n"
            f"sable_version=1.2.1+mc1.21.1\n"
        )


def ain_releases_json(*versions):
    return json.dumps([{"tagName": f"v{v}"} for v in versions])


def ais_releases_json(*versions):
    return json.dumps([{"tagName": f"v{v}"} for v in versions])


def pmob_releases_json(*versions):
    return json.dumps([{"tagName": f"v{v}"} for v in versions])


def test_queue_pop_two_items_then_auto_balance():
    ws, _ = make_workspace()
    try:
        queue = {"pending": [
            {"id": "book-a", "label": "Add A", "type": "new_file",
             "target": "src/main/resources/data/dungeontrain/narratives/random_books/book_a.json",
             "content": {"id": "book_a", "title": "A"},
             "commit_message": "content(narrative): add book A"},
            {"id": "book-b", "label": "Add B", "type": "new_file",
             "target": "src/main/resources/data/dungeontrain/narratives/random_books/book_b.json",
             "content": {"id": "book_b", "title": "B"},
             "commit_message": "content(narrative): add book B"},
        ], "applied": []}
        seed_workspace(ws, queue)

        r1 = run(ws, 1_700_000_000)
        assert r1.returncode == 0, f"r1 failed: {r1.stderr}"
        assert "commit_message=chore(auto): content(narrative): add book A" in r1.stdout, r1.stdout
        assert "change_kind=queue" in r1.stdout

        target_a = os.path.join(ws, "src/main/resources/data/dungeontrain/narratives/random_books/book_a.json")
        assert os.path.exists(target_a), "book A not written"

        with open(os.path.join(ws, ".github/auto-release/queue.json")) as f:
            q = json.load(f)
        assert len(q["pending"]) == 1 and q["pending"][0]["id"] == "book-b", f"pending: {q}"
        assert len(q["applied"]) == 1 and q["applied"][0]["id"] == "book-a", f"applied: {q}"

        r2 = run(ws, 1_700_003_600)
        assert r2.returncode == 0
        assert "commit_message=chore(auto): content(narrative): add book B" in r2.stdout

        # Third run: queue empty -> auto-balance
        r3 = run(ws, 1_700_007_200)
        assert r3.returncode == 0, f"r3 failed: {r3.stderr}"
        assert "change_kind=auto_balance" in r3.stdout, r3.stdout
        assert "commit_message=chore(auto): auto-balance wheat" in r3.stdout, r3.stdout

        print("OK  test_queue_pop_two_items_then_auto_balance")
    finally:
        shutil.rmtree(ws)


def test_auto_balance_stays_in_bounds_over_many_iterations():
    ws, _ = make_workspace()
    try:
        seed_workspace(ws, {"pending": [], "applied": []}, loot_weight=20)
        loot_path = os.path.join(ws, "src/main/resources/data/dungeontrain/loot_table/chests/auto_balancing.json")
        for i in range(200):
            now = 1_700_000_000 + i * 3600
            r = run(ws, now)
            assert r.returncode == 0, f"iter {i} failed: {r.stderr}"
            with open(loot_path) as f:
                w = json.load(f)["pools"][0]["entries"][0]["weight"]
            assert 1 <= w <= 20, f"iter {i}: weight {w} out of [1,20]"
        print("OK  test_auto_balance_stays_in_bounds_over_many_iterations")
    finally:
        shutil.rmtree(ws)


def test_refuses_overwrite_existing_file():
    ws, _ = make_workspace()
    try:
        target = "src/main/resources/data/dungeontrain/narratives/random_books/exists.json"
        target_path = os.path.join(ws, target)
        os.makedirs(os.path.dirname(target_path), exist_ok=True)
        write_json(target_path, {"already": "here"})

        queue = {"pending": [{"id": "dupe", "label": "x", "type": "new_file",
                              "target": target, "content": {"x": 1},
                              "commit_message": "test: x"}], "applied": []}
        seed_workspace(ws, queue)

        r = run(ws, 1_700_000_000)
        assert r.returncode != 0, "should have failed but didn't"
        assert "already exists" in r.stderr, r.stderr
        print("OK  test_refuses_overwrite_existing_file")
    finally:
        shutil.rmtree(ws)


def test_refuses_target_outside_data_dir():
    ws, _ = make_workspace()
    try:
        queue = {"pending": [{"id": "bad", "label": "x", "type": "new_file",
                              "target": "src/main/java/evil.json", "content": {"x": 1},
                              "commit_message": "test: x"}], "applied": []}
        seed_workspace(ws, queue)
        r = run(ws, 1_700_000_000)
        assert r.returncode != 0, "should have failed but didn't"
        assert "not under" in r.stderr, r.stderr
        print("OK  test_refuses_target_outside_data_dir")
    finally:
        shutil.rmtree(ws)


def test_state_last_auto_release_updated():
    ws, _ = make_workspace()
    try:
        seed_workspace(ws, {"pending": [], "applied": []})
        r = run(ws, 1_700_000_000)
        assert r.returncode == 0
        with open(os.path.join(ws, ".github/auto-release/state.json")) as f:
            state = json.load(f)
        assert state["last_auto_release_at"] == "2023-11-14T22:13:20Z", \
            f"unexpected timestamp: {state['last_auto_release_at']}"
        print("OK  test_state_last_auto_release_updated")
    finally:
        shutil.rmtree(ws)


STORY_TARGET = "src/main/resources/data/dungeontrain/narratives/stories/test_story.json"
STORY_META = {"id": "test_story", "character": "Tester", "story": "The Test"}


def add_variant_item(item_id, letter_index, letter_label, variant_text, commit_suffix=""):
    return {
        "id": item_id,
        "label": f"Test {item_id}",
        "type": "add_variant",
        "target": STORY_TARGET,
        "story_meta": STORY_META,
        "letter": {"index": letter_index, "label": letter_label},
        "variant": variant_text,
        "commit_message": f"test: add variant {commit_suffix or item_id}",
    }


def test_add_variant_creates_file_on_first_call():
    ws, _ = make_workspace()
    try:
        queue = {"pending": [add_variant_item("v1", 1, "Letter One", "first text")], "applied": []}
        seed_workspace(ws, queue)
        r = run(ws, 1_700_000_000)
        assert r.returncode == 0, f"failed: {r.stderr}"
        with open(os.path.join(ws, STORY_TARGET)) as f:
            story = json.load(f)
        assert story["id"] == "test_story"
        assert story["character"] == "Tester"
        assert story["story"] == "The Test"
        assert len(story["letters"]) == 1
        assert story["letters"][0]["index"] == 1
        assert story["letters"][0]["label"] == "Letter One"
        assert story["letters"][0]["variants"] == ["first text"]
        print("OK  test_add_variant_creates_file_on_first_call")
    finally:
        shutil.rmtree(ws)


def test_add_variant_appends_to_existing_letter():
    ws, _ = make_workspace()
    try:
        queue = {"pending": [
            add_variant_item("v1", 1, "Letter One", "first text"),
            add_variant_item("v2", 1, "Letter One", "second text"),
        ], "applied": []}
        seed_workspace(ws, queue)
        assert run(ws, 1_700_000_000).returncode == 0
        assert run(ws, 1_700_003_600).returncode == 0
        with open(os.path.join(ws, STORY_TARGET)) as f:
            story = json.load(f)
        assert len(story["letters"]) == 1
        assert story["letters"][0]["variants"] == ["first text", "second text"]
        print("OK  test_add_variant_appends_to_existing_letter")
    finally:
        shutil.rmtree(ws)


def test_add_variant_creates_new_letter_and_keeps_sorted():
    ws, _ = make_workspace()
    try:
        queue = {"pending": [
            add_variant_item("v2", 2, "Letter Two", "two text"),
            add_variant_item("v1", 1, "Letter One", "one text"),
            add_variant_item("v3", 3, "Letter Three", "three text"),
        ], "applied": []}
        seed_workspace(ws, queue)
        for i in range(3):
            assert run(ws, 1_700_000_000 + i * 3600).returncode == 0
        with open(os.path.join(ws, STORY_TARGET)) as f:
            story = json.load(f)
        indices = [l["index"] for l in story["letters"]]
        assert indices == [1, 2, 3], f"letters not sorted: {indices}"
        print("OK  test_add_variant_creates_new_letter_and_keeps_sorted")
    finally:
        shutil.rmtree(ws)


def test_add_variant_rejects_story_meta_mismatch():
    ws, _ = make_workspace()
    try:
        bad = add_variant_item("v2", 1, "Letter One", "second")
        bad["story_meta"] = {"id": "different_story", "character": "Tester", "story": "The Test"}
        queue = {"pending": [
            add_variant_item("v1", 1, "Letter One", "first"),
            bad,
        ], "applied": []}
        seed_workspace(ws, queue)
        assert run(ws, 1_700_000_000).returncode == 0
        r2 = run(ws, 1_700_003_600)
        assert r2.returncode != 0
        assert "existing id" in r2.stderr, r2.stderr
        print("OK  test_add_variant_rejects_story_meta_mismatch")
    finally:
        shutil.rmtree(ws)


def test_add_variant_rejects_letter_label_mismatch():
    ws, _ = make_workspace()
    try:
        queue = {"pending": [
            add_variant_item("v1", 1, "Letter One", "first"),
            add_variant_item("v2", 1, "Letter ONE",  "second"),  # wrong label
        ], "applied": []}
        seed_workspace(ws, queue)
        assert run(ws, 1_700_000_000).returncode == 0
        r2 = run(ws, 1_700_003_600)
        assert r2.returncode != 0
        assert "label mismatch" in r2.stderr, r2.stderr
        print("OK  test_add_variant_rejects_letter_label_mismatch")
    finally:
        shutil.rmtree(ws)


RANDOM_BOOK_TARGET = "src/main/resources/data/dungeontrain/narratives/random_books/test_book.json"
RANDOM_BOOK_META = {"id": "test_book", "title": "Test Book", "author": "Tester"}


def add_random_book_variant_item(item_id, variant_text, commit_suffix="", book_meta=None):
    return {
        "id": item_id,
        "label": f"Test {item_id}",
        "type": "add_random_book_variant",
        "target": RANDOM_BOOK_TARGET,
        "random_book_meta": book_meta or RANDOM_BOOK_META,
        "variant": variant_text,
        "commit_message": f"test: add rb variant {commit_suffix or item_id}",
    }


def test_add_random_book_variant_creates_file_on_first_call():
    ws, _ = make_workspace()
    try:
        queue = {"pending": [add_random_book_variant_item("v1", "first text")], "applied": []}
        seed_workspace(ws, queue)
        r = run(ws, 1_700_000_000)
        assert r.returncode == 0, f"failed: {r.stderr}"
        with open(os.path.join(ws, RANDOM_BOOK_TARGET)) as f:
            book = json.load(f)
        assert book["id"] == "test_book"
        assert book["title"] == "Test Book"
        assert book["author"] == "Tester"
        assert book["generation"] == 0
        assert book["weight"] == 1
        assert book["variants"] == ["first text"]
        print("OK  test_add_random_book_variant_creates_file_on_first_call")
    finally:
        shutil.rmtree(ws)


def test_add_random_book_variant_appends_to_existing():
    ws, _ = make_workspace()
    try:
        queue = {"pending": [
            add_random_book_variant_item("v1", "first text"),
            add_random_book_variant_item("v2", "second text"),
        ], "applied": []}
        seed_workspace(ws, queue)
        assert run(ws, 1_700_000_000).returncode == 0
        assert run(ws, 1_700_003_600).returncode == 0
        with open(os.path.join(ws, RANDOM_BOOK_TARGET)) as f:
            book = json.load(f)
        assert book["variants"] == ["first text", "second text"]
        print("OK  test_add_random_book_variant_appends_to_existing")
    finally:
        shutil.rmtree(ws)


def test_add_random_book_variant_rejects_meta_mismatch():
    ws, _ = make_workspace()
    try:
        bad_meta = {"id": "test_book", "title": "DIFFERENT TITLE", "author": "Tester"}
        queue = {"pending": [
            add_random_book_variant_item("v1", "first"),
            add_random_book_variant_item("v2", "second", book_meta=bad_meta),
        ], "applied": []}
        seed_workspace(ws, queue)
        assert run(ws, 1_700_000_000).returncode == 0
        r2 = run(ws, 1_700_003_600)
        assert r2.returncode != 0
        assert "existing title" in r2.stderr, r2.stderr
        print("OK  test_add_random_book_variant_rejects_meta_mismatch")
    finally:
        shutil.rmtree(ws)


def test_add_random_book_variant_honours_custom_weight_and_generation():
    ws, _ = make_workspace()
    try:
        custom_meta = {"id": "test_book", "title": "Test Book", "author": "Tester",
                       "generation": 2, "weight": 3}
        item = add_random_book_variant_item("v1", "first", book_meta=custom_meta)
        seed_workspace(ws, {"pending": [item], "applied": []})
        assert run(ws, 1_700_000_000).returncode == 0
        with open(os.path.join(ws, RANDOM_BOOK_TARGET)) as f:
            book = json.load(f)
        assert book["generation"] == 2
        assert book["weight"] == 3
        print("OK  test_add_random_book_variant_honours_custom_weight_and_generation")
    finally:
        shutil.rmtree(ws)


def read_ain_version(ws):
    with open(os.path.join(ws, "gradle.properties")) as f:
        for line in f:
            if line.startswith("adventureitemnames_version="):
                return line.split("=", 1)[1].strip()
    return None


def read_state(ws):
    with open(os.path.join(ws, ".github/auto-release/state.json")) as f:
        return json.load(f)


def test_ain_bump_steps_to_next_version_skipping_queue():
    ws, _ = make_workspace()
    try:
        queue = {"pending": [{"id": "should-not-run", "label": "x", "type": "new_file",
                              "target": "src/main/resources/data/dungeontrain/narratives/random_books/x.json",
                              "content": {"x": 1}, "commit_message": "test: x"}], "applied": []}
        seed_workspace(ws, queue, ain_version="0.20.0")
        r = run(ws, 1_700_000_000,
                AIN_RELEASES_OVERRIDE=ain_releases_json("0.18.0", "0.21.0", "0.22.0", "0.25.0"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=ain_bump" in r.stdout, r.stdout
        assert "commit_message=chore(auto): bump AIN 0.20.0 -> 0.21.0" in r.stdout, r.stdout
        assert "ain_from=0.20.0" in r.stdout
        assert "ain_to=0.21.0" in r.stdout
        assert read_ain_version(ws) == "0.21.0"
        # Queue must be untouched — AIN ticks don't consume queue items.
        with open(os.path.join(ws, ".github/auto-release/queue.json")) as f:
            q = json.load(f)
        assert len(q["pending"]) == 1 and q["pending"][0]["id"] == "should-not-run"
        print("OK  test_ain_bump_steps_to_next_version_skipping_queue")
    finally:
        shutil.rmtree(ws)


def test_ain_at_latest_falls_through_to_queue_in_always_mode():
    ws, _ = make_workspace()
    try:
        queue = {"pending": [{"id": "book-a", "label": "Add A", "type": "new_file",
                              "target": "src/main/resources/data/dungeontrain/narratives/random_books/a.json",
                              "content": {"id": "a"}, "commit_message": "test: a"}], "applied": []}
        seed_workspace(ws, queue, ain_version="0.25.0")
        r = run(ws, 1_700_000_000,
                AIN_RELEASES_OVERRIDE=ain_releases_json("0.24.0", "0.25.0"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=queue" in r.stdout, r.stdout
        assert read_ain_version(ws) == "0.25.0", "AIN should not have moved"
        print("OK  test_ain_at_latest_falls_through_to_queue_in_always_mode")
    finally:
        shutil.rmtree(ws)


def test_mode_ain_stops_when_no_update_available():
    ws, _ = make_workspace()
    try:
        queue = {"pending": [{"id": "book-a", "label": "Add A", "type": "new_file",
                              "target": "src/main/resources/data/dungeontrain/narratives/random_books/a.json",
                              "content": {"id": "a"}, "commit_message": "test: a"}], "applied": []}
        seed_workspace(ws, queue, ain_version="0.25.0")
        r = run(ws, 1_700_000_000, AUTO_RELEASE_MODE="ain",
                AIN_RELEASES_OVERRIDE=ain_releases_json("0.25.0"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=stopped" in r.stdout, r.stdout
        state = read_state(ws)
        assert state.get("cascade_stopped") is True, f"flag not set: {state}"
        # Queue item must NOT have been consumed.
        with open(os.path.join(ws, ".github/auto-release/queue.json")) as f:
            q = json.load(f)
        assert len(q["pending"]) == 1, "queue should be untouched in ain mode"
        print("OK  test_mode_ain_stops_when_no_update_available")
    finally:
        shutil.rmtree(ws)


def test_mode_ain_still_bumps_when_update_available():
    ws, _ = make_workspace()
    try:
        seed_workspace(ws, {"pending": [], "applied": []}, ain_version="0.20.0")
        r = run(ws, 1_700_000_000, AUTO_RELEASE_MODE="ain",
                AIN_RELEASES_OVERRIDE=ain_releases_json("0.21.0", "0.22.0"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=ain_bump" in r.stdout, r.stdout
        assert read_ain_version(ws) == "0.21.0"
        state = read_state(ws)
        assert not state.get("cascade_stopped"), "should not have stopped"
        print("OK  test_mode_ain_still_bumps_when_update_available")
    finally:
        shutil.rmtree(ws)


def test_mode_with_content_stops_when_queue_empty_and_ain_latest():
    ws, _ = make_workspace()
    try:
        seed_workspace(ws, {"pending": [], "applied": []}, ain_version="0.25.0")
        r = run(ws, 1_700_000_000, AUTO_RELEASE_MODE="with-content",
                AIN_RELEASES_OVERRIDE=ain_releases_json("0.25.0"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=stopped" in r.stdout, r.stdout
        assert read_state(ws).get("cascade_stopped") is True
        # Loot file must NOT have been nudged.
        loot_path = os.path.join(ws, "src/main/resources/data/dungeontrain/loot_table/chests/auto_balancing.json")
        with open(loot_path) as f:
            assert json.load(f)["pools"][0]["entries"][0]["weight"] == 10
        print("OK  test_mode_with_content_stops_when_queue_empty_and_ain_latest")
    finally:
        shutil.rmtree(ws)


def test_mode_with_content_applies_queue():
    ws, _ = make_workspace()
    try:
        queue = {"pending": [{"id": "book-a", "label": "Add A", "type": "new_file",
                              "target": "src/main/resources/data/dungeontrain/narratives/random_books/a.json",
                              "content": {"id": "a"}, "commit_message": "test: a"}], "applied": []}
        seed_workspace(ws, queue, ain_version="0.25.0")
        r = run(ws, 1_700_000_000, AUTO_RELEASE_MODE="with-content",
                AIN_RELEASES_OVERRIDE=ain_releases_json("0.25.0"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=queue" in r.stdout, r.stdout
        assert not read_state(ws).get("cascade_stopped")
        print("OK  test_mode_with_content_applies_queue")
    finally:
        shutil.rmtree(ws)


def test_mode_always_falls_through_to_auto_balance():
    ws, _ = make_workspace()
    try:
        seed_workspace(ws, {"pending": [], "applied": []}, ain_version="0.25.0")
        r = run(ws, 1_700_000_000, AUTO_RELEASE_MODE="always",
                AIN_RELEASES_OVERRIDE=ain_releases_json("0.25.0"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=auto_balance" in r.stdout, r.stdout
        assert not read_state(ws).get("cascade_stopped")
        print("OK  test_mode_always_falls_through_to_auto_balance")
    finally:
        shutil.rmtree(ws)


def test_skip_ain_bypasses_ain_check():
    ws, _ = make_workspace()
    try:
        seed_workspace(ws, {"pending": [], "applied": []}, ain_version="0.20.0")
        r = run(ws, 1_700_000_000, SKIP_AIN="1",
                AIN_RELEASES_OVERRIDE=ain_releases_json("0.21.0", "0.22.0"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        # SKIP_AIN should prevent the bump even though one is available.
        assert "change_kind=ain_bump" not in r.stdout, r.stdout
        assert "change_kind=auto_balance" in r.stdout, r.stdout
        assert read_ain_version(ws) == "0.20.0", "AIN should not have moved"
        print("OK  test_skip_ain_bypasses_ain_check")
    finally:
        shutil.rmtree(ws)


def test_unknown_mode_defaults_to_always():
    ws, _ = make_workspace()
    try:
        seed_workspace(ws, {"pending": [], "applied": []}, ain_version="0.25.0")
        r = run(ws, 1_700_000_000, AUTO_RELEASE_MODE="bogus-mode",
                AIN_RELEASES_OVERRIDE=ain_releases_json("0.25.0"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=auto_balance" in r.stdout, r.stdout
        print("OK  test_unknown_mode_defaults_to_always")
    finally:
        shutil.rmtree(ws)


def test_ain_bump_handles_v_prefixed_and_invalid_tags():
    ws, _ = make_workspace()
    try:
        seed_workspace(ws, {"pending": [], "applied": []}, ain_version="0.20.0")
        # Mix of valid + invalid tags. Invalid (latest-snapshot) is filtered out.
        override = json.dumps([
            {"tagName": "v0.21.0"},
            {"tagName": "latest-snapshot"},
            {"tagName": "v0.22.0"},
            {"tagName": "0.23.0"},
        ])
        r = run(ws, 1_700_000_000, AIN_RELEASES_OVERRIDE=override)
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=ain_bump" in r.stdout
        assert "ain_to=0.21.0" in r.stdout
        print("OK  test_ain_bump_handles_v_prefixed_and_invalid_tags")
    finally:
        shutil.rmtree(ws)


# ---------------------------------------------------------------------------
# AIS (sibling-mod) tests — parallel to the AIN tests above.
# ---------------------------------------------------------------------------


def read_ais_version(ws):
    with open(os.path.join(ws, "gradle.properties")) as f:
        for line in f:
            if line.startswith("adventureitemstats_version="):
                return line.split("=", 1)[1].strip()
    return None


def test_ais_bump_when_ain_at_latest():
    """AIN current, AIS behind → AIS bump applies."""
    ws, _ = make_workspace()
    try:
        queue = {"pending": [{"id": "should-not-run", "label": "x", "type": "new_file",
                              "target": "src/main/resources/data/dungeontrain/narratives/random_books/x.json",
                              "content": {"x": 1}, "commit_message": "test: x"}], "applied": []}
        seed_workspace(ws, queue, ain_version="0.25.0", ais_version="0.1.0")
        r = run(ws, 1_700_000_000,
                AIN_RELEASES_OVERRIDE=ain_releases_json("0.25.0"),
                AIS_RELEASES_OVERRIDE=ais_releases_json("0.1.0", "0.1.1", "0.1.2"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=ais_bump" in r.stdout, r.stdout
        assert "commit_message=chore(auto): bump AIS 0.1.0 -> 0.1.1" in r.stdout, r.stdout
        assert "ais_from=0.1.0" in r.stdout
        assert "ais_to=0.1.1" in r.stdout
        assert read_ais_version(ws) == "0.1.1"
        # Queue must be untouched — sibling-mod ticks don't consume queue items.
        with open(os.path.join(ws, ".github/auto-release/queue.json")) as f:
            q = json.load(f)
        assert len(q["pending"]) == 1 and q["pending"][0]["id"] == "should-not-run"
        print("OK  test_ais_bump_when_ain_at_latest")
    finally:
        shutil.rmtree(ws)


def test_ain_takes_priority_when_both_behind():
    """When both sibling mods are behind, AIN wins this tick (AIS untouched)."""
    ws, _ = make_workspace()
    try:
        seed_workspace(ws, {"pending": [], "applied": []},
                       ain_version="0.20.0", ais_version="0.1.0")
        r = run(ws, 1_700_000_000,
                AIN_RELEASES_OVERRIDE=ain_releases_json("0.21.0"),
                AIS_RELEASES_OVERRIDE=ais_releases_json("0.1.5"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=ain_bump" in r.stdout, r.stdout
        assert read_ain_version(ws) == "0.21.0", "AIN should have moved"
        assert read_ais_version(ws) == "0.1.0", "AIS must NOT move when AIN bumps"
        print("OK  test_ain_takes_priority_when_both_behind")
    finally:
        shutil.rmtree(ws)


def test_mode_ais_stops_when_no_ais_update():
    """mode=ais + AIS at latest → cascade stops (queue not consumed)."""
    ws, _ = make_workspace()
    try:
        queue = {"pending": [{"id": "book-a", "label": "Add A", "type": "new_file",
                              "target": "src/main/resources/data/dungeontrain/narratives/random_books/a.json",
                              "content": {"id": "a"}, "commit_message": "test: a"}], "applied": []}
        seed_workspace(ws, queue, ain_version="0.20.0", ais_version="0.1.2")
        r = run(ws, 1_700_000_000, AUTO_RELEASE_MODE="ais",
                AIS_RELEASES_OVERRIDE=ais_releases_json("0.1.2"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=stopped" in r.stdout, r.stdout
        assert read_state(ws).get("cascade_stopped") is True
        # Queue untouched.
        with open(os.path.join(ws, ".github/auto-release/queue.json")) as f:
            q = json.load(f)
        assert len(q["pending"]) == 1, "queue should be untouched in ais mode"
        print("OK  test_mode_ais_stops_when_no_ais_update")
    finally:
        shutil.rmtree(ws)


def test_mode_ais_ignores_ain_update():
    """mode=ais: even if AIN has an update available, AIN must not bump
    (and AIS at latest → stop)."""
    ws, _ = make_workspace()
    try:
        seed_workspace(ws, {"pending": [], "applied": []},
                       ain_version="0.20.0", ais_version="0.1.2")
        r = run(ws, 1_700_000_000, AUTO_RELEASE_MODE="ais",
                AIN_RELEASES_OVERRIDE=ain_releases_json("0.21.0", "0.22.0"),
                AIS_RELEASES_OVERRIDE=ais_releases_json("0.1.2"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=stopped" in r.stdout, r.stdout
        assert read_ain_version(ws) == "0.20.0", "AIN must not move under mode=ais"
        print("OK  test_mode_ais_ignores_ain_update")
    finally:
        shutil.rmtree(ws)


def test_mode_ain_ignores_ais_update():
    """mode=ain: even if AIS has an update available, AIS must not bump
    (and AIN at latest → stop). Back-compat guarantee for existing mode=ain
    users who don't expect AIS to participate."""
    ws, _ = make_workspace()
    try:
        seed_workspace(ws, {"pending": [], "applied": []},
                       ain_version="0.25.0", ais_version="0.1.0")
        r = run(ws, 1_700_000_000, AUTO_RELEASE_MODE="ain",
                AIN_RELEASES_OVERRIDE=ain_releases_json("0.25.0"),
                AIS_RELEASES_OVERRIDE=ais_releases_json("0.1.1", "0.1.2"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=stopped" in r.stdout, r.stdout
        assert read_ais_version(ws) == "0.1.0", "AIS must not move under mode=ain"
        print("OK  test_mode_ain_ignores_ais_update")
    finally:
        shutil.rmtree(ws)


def test_skip_ais_bypasses_ais_check():
    """SKIP_AIS=1 prevents AIS bump even when one is available — used by the
    workflow's revert-after-build-failure path. AIN bump still considered."""
    ws, _ = make_workspace()
    try:
        seed_workspace(ws, {"pending": [], "applied": []},
                       ain_version="0.25.0", ais_version="0.1.0")
        r = run(ws, 1_700_000_000, SKIP_AIS="1",
                AIN_RELEASES_OVERRIDE=ain_releases_json("0.25.0"),
                AIS_RELEASES_OVERRIDE=ais_releases_json("0.1.1", "0.1.2"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        # SKIP_AIS prevents the AIS bump even though one is available;
        # AIN is at latest, so falls through to auto_balance.
        assert "change_kind=ais_bump" not in r.stdout, r.stdout
        assert "change_kind=auto_balance" in r.stdout, r.stdout
        assert read_ais_version(ws) == "0.1.0", "AIS should not have moved"
        print("OK  test_skip_ais_bypasses_ais_check")
    finally:
        shutil.rmtree(ws)


def test_rerun_after_reset_pops_same_queue_item():
    """Models the workflow's race-recovery path: apply-change runs, then the
    push is rejected, so we 'git reset --hard' (revert queue.json + state.json
    to seed state) and re-run apply-change against fresh state. The second
    run must pop the SAME queue item and produce the SAME applied_id."""
    ws, _ = make_workspace()
    try:
        # Seed two pending items; we only care about pending[0].
        queue = {"pending": [
            {"id": "book-a", "label": "Add A", "type": "new_file",
             "target": "src/main/resources/data/dungeontrain/narratives/random_books/book_a.json",
             "content": {"id": "book_a", "title": "A"},
             "commit_message": "content(narrative): add book A"},
            {"id": "book-b", "label": "Add B", "type": "new_file",
             "target": "src/main/resources/data/dungeontrain/narratives/random_books/book_b.json",
             "content": {"id": "book_b", "title": "B"},
             "commit_message": "content(narrative): add book B"},
        ], "applied": []}
        seed_workspace(ws, queue)

        # Snapshot the seed state.json/queue.json (the post-reset baseline).
        state_path = os.path.join(ws, ".github/auto-release/state.json")
        queue_path = os.path.join(ws, ".github/auto-release/queue.json")
        with open(state_path) as f:
            seed_state = json.load(f)
        with open(queue_path) as f:
            seed_queue = json.load(f)

        # First run: pops book-a.
        r1 = run(ws, 1_700_000_000)
        assert r1.returncode == 0, f"r1 failed: {r1.stderr}"
        assert "commit_message=chore(auto): content(narrative): add book A" in r1.stdout, r1.stdout
        assert "applied_id=book-a" in r1.stdout, r1.stdout

        # Simulate `git reset --hard origin/main`: revert state.json and
        # queue.json to seed state, and remove the new file written by apply.
        write_json(state_path, seed_state)
        write_json(queue_path, seed_queue)
        target_a = os.path.join(ws, "src/main/resources/data/dungeontrain/narratives/random_books/book_a.json")
        if os.path.exists(target_a):
            os.unlink(target_a)

        # Second run: must pop the SAME book-a item.
        r2 = run(ws, 1_700_000_005)
        assert r2.returncode == 0, f"r2 failed: {r2.stderr}"
        assert "commit_message=chore(auto): content(narrative): add book A" in r2.stdout, \
            f"retry did not produce same commit_message: {r2.stdout}"
        assert "applied_id=book-a" in r2.stdout, \
            f"retry did not produce same applied_id: {r2.stdout}"
        assert "change_kind=queue" in r2.stdout, r2.stdout
        assert os.path.exists(target_a), "retry did not re-write book A"
        print("OK  test_rerun_after_reset_pops_same_queue_item")
    finally:
        shutil.rmtree(ws)


def test_rerun_after_reset_redoes_same_ain_bump():
    """Race-recovery variant for sibling-mod bumps: when the original tick
    bumped AIN, the retry must produce the SAME bump (since gradle.properties
    on fresh main still has the same AIN version — Gate 3 PRs don't touch it)."""
    ws, _ = make_workspace()
    try:
        seed_workspace(ws, {"pending": [], "applied": []}, ain_version="0.20.0")

        gradle_path = os.path.join(ws, "gradle.properties")
        state_path = os.path.join(ws, ".github/auto-release/state.json")
        with open(gradle_path) as f:
            seed_gradle = f.read()
        with open(state_path) as f:
            seed_state = json.load(f)

        # First run: bumps AIN 0.20.0 -> 0.21.0
        r1 = run(ws, 1_700_000_000,
                 AIN_RELEASES_OVERRIDE=ain_releases_json("0.21.0", "0.22.0"))
        assert r1.returncode == 0, f"r1 failed: {r1.stderr}"
        assert "change_kind=ain_bump" in r1.stdout, r1.stdout
        assert "ain_to=0.21.0" in r1.stdout
        assert read_ain_version(ws) == "0.21.0"

        # Reset to seed state.
        with open(gradle_path, "w") as f:
            f.write(seed_gradle)
        write_json(state_path, seed_state)

        # Second run: must redo the SAME bump.
        r2 = run(ws, 1_700_000_005,
                 AIN_RELEASES_OVERRIDE=ain_releases_json("0.21.0", "0.22.0"))
        assert r2.returncode == 0, f"r2 failed: {r2.stderr}"
        assert "change_kind=ain_bump" in r2.stdout, r2.stdout
        assert "ain_from=0.20.0" in r2.stdout
        assert "ain_to=0.21.0" in r2.stdout
        assert read_ain_version(ws) == "0.21.0"
        print("OK  test_rerun_after_reset_redoes_same_ain_bump")
    finally:
        shutil.rmtree(ws)


# ---------------------------------------------------------------------------
# PlayerMob (PMOB) — third sibling, declared after AIN and AIS.
# ---------------------------------------------------------------------------


def read_pmob_version(ws):
    with open(os.path.join(ws, "gradle.properties")) as f:
        for line in f:
            if line.startswith("playermob_version="):
                return line.split("=", 1)[1].strip()
    return None


def test_pmob_bump_when_ain_and_ais_at_latest():
    """AIN + AIS current, PMOB behind → PMOB bump applies (PMOB is reached
    only after the first two siblings are up to date)."""
    ws, _ = make_workspace()
    try:
        queue = {"pending": [{"id": "should-not-run", "label": "x", "type": "new_file",
                              "target": "src/main/resources/data/dungeontrain/narratives/random_books/x.json",
                              "content": {"x": 1}, "commit_message": "test: x"}], "applied": []}
        seed_workspace(ws, queue, ain_version="0.25.0", ais_version="0.1.0",
                       pmob_version="0.14.0")
        r = run(ws, 1_700_000_000,
                AIN_RELEASES_OVERRIDE=ain_releases_json("0.25.0"),
                AIS_RELEASES_OVERRIDE=ais_releases_json("0.1.0"),
                PMOB_RELEASES_OVERRIDE=pmob_releases_json("0.14.0", "0.15.0", "0.16.0"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=pmob_bump" in r.stdout, r.stdout
        assert "commit_message=chore(auto): bump PMOB 0.14.0 -> 0.15.0" in r.stdout, r.stdout
        assert "pmob_from=0.14.0" in r.stdout
        assert "pmob_to=0.15.0" in r.stdout
        assert read_pmob_version(ws) == "0.15.0"
        # Queue must be untouched — sibling-mod ticks don't consume queue items.
        with open(os.path.join(ws, ".github/auto-release/queue.json")) as f:
            q = json.load(f)
        assert len(q["pending"]) == 1 and q["pending"][0]["id"] == "should-not-run"
        print("OK  test_pmob_bump_when_ain_and_ais_at_latest")
    finally:
        shutil.rmtree(ws)


def test_ais_takes_priority_over_pmob():
    """When AIS and PMOB are both behind (AIN current), AIS wins this tick —
    PMOB is declared last, so it must not move."""
    ws, _ = make_workspace()
    try:
        seed_workspace(ws, {"pending": [], "applied": []},
                       ain_version="0.25.0", ais_version="0.1.0", pmob_version="0.14.0")
        r = run(ws, 1_700_000_000,
                AIN_RELEASES_OVERRIDE=ain_releases_json("0.25.0"),
                AIS_RELEASES_OVERRIDE=ais_releases_json("0.1.0", "0.1.1", "0.1.2"),
                PMOB_RELEASES_OVERRIDE=pmob_releases_json("0.15.0"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=ais_bump" in r.stdout, r.stdout
        assert read_ais_version(ws) == "0.1.1", "AIS should have moved one step"
        assert read_pmob_version(ws) == "0.14.0", "PMOB must NOT move when AIS bumps"
        print("OK  test_ais_takes_priority_over_pmob")
    finally:
        shutil.rmtree(ws)


def test_mode_ain_ignores_pmob_update():
    """mode=ain: PMOB update available but must not bump (AIN at latest → stop)."""
    ws, _ = make_workspace()
    try:
        seed_workspace(ws, {"pending": [], "applied": []},
                       ain_version="0.25.0", pmob_version="0.14.0")
        r = run(ws, 1_700_000_000, AUTO_RELEASE_MODE="ain",
                AIN_RELEASES_OVERRIDE=ain_releases_json("0.25.0"),
                PMOB_RELEASES_OVERRIDE=pmob_releases_json("0.15.0"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=stopped" in r.stdout, r.stdout
        assert read_pmob_version(ws) == "0.14.0", "PMOB must not move under mode=ain"
        print("OK  test_mode_ain_ignores_pmob_update")
    finally:
        shutil.rmtree(ws)


def test_mode_ais_ignores_pmob_update():
    """mode=ais: PMOB update available but must not bump (AIS at latest → stop)."""
    ws, _ = make_workspace()
    try:
        seed_workspace(ws, {"pending": [], "applied": []},
                       ais_version="0.1.2", pmob_version="0.14.0")
        r = run(ws, 1_700_000_000, AUTO_RELEASE_MODE="ais",
                AIS_RELEASES_OVERRIDE=ais_releases_json("0.1.2"),
                PMOB_RELEASES_OVERRIDE=pmob_releases_json("0.15.0"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=stopped" in r.stdout, r.stdout
        assert read_pmob_version(ws) == "0.14.0", "PMOB must not move under mode=ais"
        print("OK  test_mode_ais_ignores_pmob_update")
    finally:
        shutil.rmtree(ws)


def test_mode_with_content_bumps_pmob():
    """mode=with-content: PMOB participates like AIN/AIS — a pending PMOB
    update bumps even though only AIN was historically the 'with-content' driver."""
    ws, _ = make_workspace()
    try:
        seed_workspace(ws, {"pending": [], "applied": []},
                       ain_version="0.25.0", ais_version="0.1.0", pmob_version="0.14.0")
        r = run(ws, 1_700_000_000, AUTO_RELEASE_MODE="with-content",
                AIN_RELEASES_OVERRIDE=ain_releases_json("0.25.0"),
                AIS_RELEASES_OVERRIDE=ais_releases_json("0.1.0"),
                PMOB_RELEASES_OVERRIDE=pmob_releases_json("0.15.0"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=pmob_bump" in r.stdout, r.stdout
        assert read_pmob_version(ws) == "0.15.0"
        print("OK  test_mode_with_content_bumps_pmob")
    finally:
        shutil.rmtree(ws)


def test_skip_pmob_bypasses_pmob_check():
    """SKIP_PMOB=1 prevents the PMOB bump even when one is available — used by
    the workflow's revert-after-build-failure path. With AIN/AIS at latest and
    no queue, the tick falls through to auto_balance."""
    ws, _ = make_workspace()
    try:
        seed_workspace(ws, {"pending": [], "applied": []},
                       ain_version="0.25.0", ais_version="0.1.0", pmob_version="0.14.0")
        r = run(ws, 1_700_000_000, SKIP_PMOB="1",
                AIN_RELEASES_OVERRIDE=ain_releases_json("0.25.0"),
                AIS_RELEASES_OVERRIDE=ais_releases_json("0.1.0"),
                PMOB_RELEASES_OVERRIDE=pmob_releases_json("0.15.0"))
        assert r.returncode == 0, f"failed: {r.stderr}"
        assert "change_kind=pmob_bump" not in r.stdout, r.stdout
        assert "change_kind=auto_balance" in r.stdout, r.stdout
        assert read_pmob_version(ws) == "0.14.0", "PMOB should not have moved"
        print("OK  test_skip_pmob_bypasses_pmob_check")
    finally:
        shutil.rmtree(ws)


def main():
    tests = [
        test_queue_pop_two_items_then_auto_balance,
        test_auto_balance_stays_in_bounds_over_many_iterations,
        test_refuses_overwrite_existing_file,
        test_refuses_target_outside_data_dir,
        test_state_last_auto_release_updated,
        test_add_variant_creates_file_on_first_call,
        test_add_variant_appends_to_existing_letter,
        test_add_variant_creates_new_letter_and_keeps_sorted,
        test_add_variant_rejects_story_meta_mismatch,
        test_add_variant_rejects_letter_label_mismatch,
        test_add_random_book_variant_creates_file_on_first_call,
        test_add_random_book_variant_appends_to_existing,
        test_add_random_book_variant_rejects_meta_mismatch,
        test_add_random_book_variant_honours_custom_weight_and_generation,
        test_ain_bump_steps_to_next_version_skipping_queue,
        test_ain_at_latest_falls_through_to_queue_in_always_mode,
        test_mode_ain_stops_when_no_update_available,
        test_mode_ain_still_bumps_when_update_available,
        test_mode_with_content_stops_when_queue_empty_and_ain_latest,
        test_mode_with_content_applies_queue,
        test_mode_always_falls_through_to_auto_balance,
        test_skip_ain_bypasses_ain_check,
        test_unknown_mode_defaults_to_always,
        test_ain_bump_handles_v_prefixed_and_invalid_tags,
        test_ais_bump_when_ain_at_latest,
        test_ain_takes_priority_when_both_behind,
        test_mode_ais_stops_when_no_ais_update,
        test_mode_ais_ignores_ain_update,
        test_mode_ain_ignores_ais_update,
        test_skip_ais_bypasses_ais_check,
        test_rerun_after_reset_pops_same_queue_item,
        test_rerun_after_reset_redoes_same_ain_bump,
        test_pmob_bump_when_ain_and_ais_at_latest,
        test_ais_takes_priority_over_pmob,
        test_mode_ain_ignores_pmob_update,
        test_mode_ais_ignores_pmob_update,
        test_mode_with_content_bumps_pmob,
        test_skip_pmob_bypasses_pmob_check,
    ]
    failed = 0
    for t in tests:
        try:
            t()
        except AssertionError as e:
            print(f"FAIL {t.__name__}: {e}")
            failed += 1
    print(f"\n{len(tests) - failed}/{len(tests)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
