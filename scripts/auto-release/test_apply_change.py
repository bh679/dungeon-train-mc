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


def run(ws, now_epoch):
    env = {
        **os.environ,
        "STATE_FILE": os.path.join(ws, ".github/auto-release/state.json"),
        "QUEUE_FILE": os.path.join(ws, ".github/auto-release/queue.json"),
        "LOOT_FILE": os.path.join(ws, "src/main/resources/data/dungeontrain/loot_table/chests/auto_balancing.json"),
        "NOW_EPOCH": str(now_epoch),
    }
    env.pop("GITHUB_OUTPUT", None)
    return subprocess.run([sys.executable, SCRIPT], cwd=ws, env=env,
                          capture_output=True, text=True)


def seed_workspace(ws, queue, loot_weight=10):
    write_json(os.path.join(ws, ".github/auto-release/state.json"),
               {"schedule_anchor": "2026-05-18T12:00:00Z", "last_auto_release_at": None,
                "last_anchor_source": "test"})
    write_json(os.path.join(ws, ".github/auto-release/queue.json"), queue)
    write_json(os.path.join(ws, "src/main/resources/data/dungeontrain/loot_table/chests/auto_balancing.json"),
               {"type": "minecraft:chest", "pools": [{"rolls": 1, "entries": [
                   {"type": "minecraft:item", "name": "minecraft:wheat", "weight": loot_weight}]}]})


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
