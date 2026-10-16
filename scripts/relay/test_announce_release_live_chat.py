#!/usr/bin/env python3
"""Unit tests for announce-release-live-chat.py (new release → the relay's live chat).

Runnable directly or by pytest. Local-only, like scripts/release-notes/test_*.py.
"""
import importlib.util
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "announce-release-live-chat.py")
_spec = importlib.util.spec_from_file_location("announce_release_live_chat", SCRIPT)
ar = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ar)

NOTES = (
    "# [Twitch integration](https://brennan.games/dungeontrain/update/#v0.1309.0)\n\n"
    "![Twitch integration](https://example.com/x.jpg)\n\n"
    "Dungeon Train TV can now connect to your Twitch.\n\n## Fixes\n- a fix\n"
)


def test_version_accepts_tags_and_refuses_junk():
    assert ar.normalise_version("v0.1310.0") == "0.1310.0"
    assert ar.normalise_version("0.1310.0") == "0.1310.0"
    for bad in ("v0.1310", "latest", "v0.1310.0-beta", ""):
        try:
            ar.normalise_version(bad)
        except ValueError:
            continue
        raise AssertionError(f"accepted {bad!r}")


def test_title_is_first_h1_without_its_link():
    assert ar.release_title(NOTES) == "Twitch integration"
    assert ar.release_title("## What's Changed\n* x") == ""
    assert ar.release_title("") == ""
    assert ar.release_title("# **Bold** `code`") == "Bold code"


def test_announcement_matches_in_game_wording_and_links_the_release():
    body = ar.build_announcement("0.1309.0", NOTES)
    assert body["content"] == "Version 0.1309.0 is out! Twitch integration — What's new"
    assert body["link"] == {"text": "What's new", "url": "https://brennan.games/dungeontrain/update/#v0.1309.0"}
    assert body["link"]["text"] in body["content"]


def test_announcement_without_a_title_and_capped():
    assert ar.build_announcement("1.2.3", "## What's Changed")["content"] == "Version 1.2.3 is out! — What's new"
    long = ar.build_announcement("1.2.3", "# " + "x" * 2000)["content"]
    assert len(long) <= ar.MAX_CONTENT
    assert long.endswith("— What's new")


def test_dry_run_prints_the_body_and_sends_nothing():
    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as f:
        f.write(NOTES)
    try:
        env = {k: v for k, v in os.environ.items() if k not in ar.ENVS}
        out = subprocess.run([sys.executable, SCRIPT, "--tag", "v0.1309.0", "--notes-file", f.name, "--dry-run"],
                             capture_output=True, text=True, env=env, check=True)
    finally:
        os.unlink(f.name)
    assert json.loads(out.stdout)["content"].startswith("Version 0.1309.0 is out! Twitch integration")


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_") and callable(fn):
            fn()
            print("ok", name)
