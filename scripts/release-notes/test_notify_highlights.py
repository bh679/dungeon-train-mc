#!/usr/bin/env python3
"""Unit tests for notify-highlights.py (major releases → Discord #highlights).

Runnable directly or by pytest. Local-only, like test_release_notes.py.
"""
import importlib.util
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "notify-highlights.py")
sys.path.insert(0, HERE)
_spec = importlib.util.spec_from_file_location("notify_highlights", SCRIPT)
nh = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(nh)

MAJOR = {"id": "release-x", "title": "X", "summary": "s", "description": "d", "major": True,
         "image": "https://example.com/x.jpg", "released_in": "v1.2.0"}
MINOR = {"id": "feat-y", "title": "Y", "summary": "s", "released_in": "v1.2.0"}
OLD_MAJOR = {**MAJOR, "id": "release-old", "released_in": "v1.1.0"}


def _run(entries, *args, env_extra=None):
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "changelog.json")
        with open(path, "w") as f:
            json.dump({"entries": entries}, f)
        env = {**os.environ, "CHANGELOG_FILE": path, **(env_extra or {})}
        env.pop("DISCORD_HIGHLIGHTS_WEBHOOK_URL", None) if not env_extra else None
        return subprocess.run([sys.executable, SCRIPT, *args], capture_output=True, text=True, env=env)


def test_majors_for_picks_only_this_tags_majors():
    assert [e["id"] for e in nh.majors_for([MAJOR, MINOR, OLD_MAJOR], "v1.2.0")] == ["release-x"]


def test_message_is_markdown_heading_description_link_then_photo():
    payload = nh.build_payload(MAJOR, "v1.2.0")
    assert "embeds" not in payload
    assert payload["content"] == (
        "# X\n\nd\n\n"
        "[Read the full update](<https://brennan.games/dungeontrain/update/#v1.2.0>)\n"
        "https://example.com/x.jpg"
    )
    assert payload["allowed_mentions"] == {"parse": []}


def test_message_falls_back_to_summary_and_omits_missing_image():
    content = nh.build_content({"title": "Z", "summary": "only summary"}, "v1")
    assert content.startswith("# Z\n\nonly summary\n\n[Read the full update]")
    assert "example.com" not in content


def test_long_description_fits_discord_cap_and_keeps_photo():
    content = nh.build_content({**MAJOR, "description": "a" * 5000}, "v1")
    assert len(content) <= nh.CONTENT_LIMIT
    assert content.endswith("https://example.com/x.jpg")
    assert "…" in content


def test_no_major_posts_nothing():
    r = _run([MINOR], "--tag", "v1.2.0", env_extra={"DRY_RUN": "1"})
    assert r.returncode == 0 and "nothing to post" in r.stdout


def test_dry_run_prints_payload():
    r = _run([MAJOR, MINOR], "--tag", "v1.2.0", env_extra={"DRY_RUN": "1"})
    assert r.returncode == 0
    assert [p["content"].splitlines()[0] for p in json.loads(r.stdout)] == ["# X"]


def test_missing_webhook_skips_green():
    r = _run([MAJOR], "--tag", "v1.2.0")
    assert r.returncode == 0 and "not set" in r.stdout


def test_entry_flag_posts_a_released_major_by_id():
    r = _run([OLD_MAJOR], "--tag", "v1.2.0", "--entry", "release-old", env_extra={"DRY_RUN": "1"})
    assert r.returncode == 0 and json.loads(r.stdout)[0]["content"].startswith("# X")


def test_unknown_entry_errors():
    r = _run([MAJOR], "--tag", "v1.2.0", "--entry", "nope", env_extra={"DRY_RUN": "1"})
    assert r.returncode == 1


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_") and callable(fn):
            fn()
    print("ok")
