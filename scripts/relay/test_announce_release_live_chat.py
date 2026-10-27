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


def _entry(id_, title, released_in="v0.1426.0", **kw):
    return {"id": id_, "title": title, "type": kw.pop("type", "feat"), "tags": kw.pop("tags", ["feature"]),
            "summary": kw.pop("summary", ""), "released": kw.pop("released", True), "released_in": released_in, **kw}


MAJOR_ENTRY = _entry("deeper", "A Deeper Darker Train", major=True,
                     summary="Deep down the line, a new stretch waits. It has its own mobs.")
NOTABLE_ENTRY = _entry("piglins", "New Faces in the Nether", type="content", major=True, notable=True,
                       summary="The **Nether's** piglins aren't travelling alone any more.\nMore text.")
FEAT = _entry("dig", "Mobs dig through to you", summary="Some mobs carry tools.")
FIX = _entry("fix", "A fix", type="fix")


def test_pick_ranks_like_the_in_game_notice():
    assert ar.pick_release([FIX, FEAT], "0.1426.0") == ("Mobs dig through to you", ar.NORMAL, "Some mobs carry tools.")
    assert ar.pick_release([FEAT, NOTABLE_ENTRY], "0.1426.0")[1] == ar.NOTABLE
    assert ar.pick_release([NOTABLE_ENTRY, FEAT, MAJOR_ENTRY], "0.1426.0")[:2] == ("A Deeper Darker Train", ar.MAJOR)
    # major.minor match, so a cascade tag still finds its release
    assert ar.pick_release([FEAT], "0.1426.7")[0] == "Mobs dig through to you"


def test_pick_skips_internal_unreleased_and_other_releases():
    internal = _entry("i", "Internal thing", major=True, tags=["internal"])
    unreleased = _entry("u", "Not yet", major=True, released=False, released_in=None)
    other = _entry("o", "Other release", major=True, released_in="v0.1425.0")
    assert ar.pick_release([internal, unreleased, other, FIX], "0.1426.0")[0] == "A fix"
    assert ar.pick_release([internal, unreleased, other], "0.1426.0") is None
    assert ar.pick_release([FEAT], "latest") is None


def test_short_summary_matches_release_name():
    assert ar.short_summary("Deep down. More.") == "Deep down."
    assert ar.short_summary("## Heading\n[Link](https://x) and *bold*\nnext") == "Link and bold"
    assert ar.short_summary("https://example.com/x") == ""
    long = ar.short_summary("word " * 60)
    assert len(long) <= ar.SUMMARY_MAX and long.endswith("...")


def test_major_line_has_banner_name_version_and_summary():
    body = ar.build_announcement("0.1426.0", NOTES, [FEAT, MAJOR_ENTRY])
    assert body["content"] == ("★ MAJOR UPDATE ★ A Deeper Darker Train (v0.1426.0). "
                               "Deep down the line, a new stretch waits. What's new")
    assert body["link"] == {"text": "What's new", "url": "https://brennan.games/dungeontrain/update/#v0.1426.0"}


def test_notable_line_has_name_version_and_summary():
    body = ar.build_announcement("0.1426.0", NOTES, [NOTABLE_ENTRY])
    assert body["content"] == ("New update: New Faces in the Nether (v0.1426.0). "
                               "The Nether's piglins aren't travelling alone any more. What's new")


def test_normal_line_is_name_and_version_only():
    body = ar.build_announcement("0.1426.0", NOTES, [FEAT])
    assert body["content"] == "Mobs dig through to you (v0.1426.0) is out! What's new"


def test_without_a_ledger_entry_the_release_heading_names_it():
    body = ar.build_announcement("0.1309.0", NOTES, [])
    assert body["content"] == "Twitch integration (v0.1309.0) is out! What's new"
    assert body["link"]["text"] in body["content"]


def test_unnamed_and_capped_and_no_long_dashes():
    assert ar.build_announcement("1.2.3", "## What's Changed")["content"] == "Version 1.2.3 is out! What's new"
    huge = _entry("h", "x" * 2000, released_in="v1.2.3", major=True, summary="y " * 400)
    for notes, entries in (("# " + "x" * 2000, []), ("", [huge])):
        line = ar.build_announcement("1.2.3", notes, entries)["content"]
        assert len(line) <= ar.MAX_CONTENT
        assert line.endswith(" What's new")
    for entries in ([MAJOR_ENTRY], [NOTABLE_ENTRY], [FEAT], []):
        line = ar.build_announcement("0.1426.0", NOTES, entries)["content"]
        assert "—" not in line and "–" not in line


def test_dry_run_prints_the_body_and_sends_nothing():
    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as f:
        f.write(NOTES)
    try:
        env = {k: v for k, v in os.environ.items() if k not in ar.ENVS}
        env["CHANGELOG_FILE"] = os.path.join(tempfile.gettempdir(), "no-such-changelog.json")
        out = subprocess.run([sys.executable, SCRIPT, "--tag", "v0.1309.0", "--notes-file", f.name, "--dry-run"],
                             capture_output=True, text=True, env=env, check=True)
    finally:
        os.unlink(f.name)
    assert json.loads(out.stdout)["content"].startswith("Twitch integration (v0.1309.0) is out!")


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_") and callable(fn):
            fn()
            print("ok", name)
