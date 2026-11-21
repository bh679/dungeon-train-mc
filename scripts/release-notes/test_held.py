#!/usr/bin/env python3
"""Unit tests for held.py: hold a batch, keep it out of the notes, launch it."""
import json
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import changelog_io  # noqa: E402
import held  # noqa: E402


def _entry(entry_id: str, version: str = "0.10.0", released: bool = False) -> dict:
    return {"id": entry_id, "version": version, "type": "feat", "tags": ["feature"],
            "title": entry_id, "summary": "s", "date": "2026-11-20", "released": released}


def _workspace() -> tuple[str, str]:
    ws = tempfile.mkdtemp(prefix="held-test-")
    ledger = os.path.join(ws, "changelog.json")
    changelog_io.write_json(ledger, {"entries": [
        _entry("old", released=True), _entry("in-ledger"), _entry("stays")]})
    changelog_io.write_unreleased_entry(_entry("in-file"), ledger)
    return ws, ledger


def test_hold_takes_entries_out_of_the_notes() -> None:
    ws, ledger = _workspace()
    try:
        ids = held.hold("perks", ["in-ledger", "in-file"], 2, [11, 12], ledger)
        assert ids == ["in-file", "in-ledger"]
        unreleased = [e["id"] for e in changelog_io.unreleased_entries(changelog_io.load_changelog(ledger)["entries"])]
        assert unreleased == ["stays"]
        meta, entries = held.load_batch("perks", ledger)
        assert meta["bumps"] == 2 and meta["prs"] == [11, 12]
        assert {e["id"] for e in entries} == {"in-ledger", "in-file"}
    finally:
        shutil.rmtree(ws)


def test_hold_refuses_a_released_entry() -> None:
    ws, ledger = _workspace()
    try:
        try:
            held.hold("perks", ["old"], 1, [], ledger)
        except ValueError:
            return
        raise AssertionError("held a released entry")
    finally:
        shutil.rmtree(ws)


def test_launch_restamps_and_removes_the_batch() -> None:
    ws, ledger = _workspace()
    try:
        held.hold("perks", ["in-ledger", "in-file"], 21, [], ledger)
        version, ids = held.launch("perks", "0.1930.0", ledger)
        assert version == "0.1951.0"
        assert sorted(ids) == ["in-file", "in-ledger"]
        unreleased = {e["id"]: e for e in changelog_io.unreleased_entries(changelog_io.load_changelog(ledger)["entries"])}
        assert set(unreleased) == {"in-ledger", "in-file", "stays"}
        assert unreleased["in-file"]["version"] == "0.1951.0"
        assert not os.path.exists(held.held_root(ledger))
    finally:
        shutil.rmtree(ws)


def test_launch_version() -> None:
    assert held.launch_version("0.1921.3", 1) == "0.1922.0"
    assert held.launch_version("1.5.0", 21) == "1.26.0"


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
