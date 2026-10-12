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


REPO_IMAGE = ("https://raw.githubusercontent.com/bh679/dungeon-train-mc/main/"
              ".github/release-notes/images/albums.jpg")


def test_message_is_markdown_heading_description_then_link_without_photo_url():
    payload = nh.build_payload(MAJOR, "v1.2.0")
    assert "embeds" not in payload
    assert payload["content"] == (
        "# X\n\nd\n\n"
        "[Read the full update](<https://brennan.games/dungeontrain/update/#v1.2.0>)"
    )
    assert payload["allowed_mentions"] == {"parse": []}


def test_fallback_message_carries_photo_url_last():
    content = nh.build_content(MAJOR, "v1.2.0", with_image_link=True)
    assert content.endswith("(<https://brennan.games/dungeontrain/update/#v1.2.0>)\nhttps://example.com/x.jpg")


def test_message_falls_back_to_summary_and_omits_missing_image():
    content = nh.build_content({"title": "Z", "summary": "only summary"}, "v1", with_image_link=True)
    assert content.startswith("# Z\n\nonly summary\n\n[Read the full update]")
    assert "example.com" not in content


def test_long_description_fits_discord_cap_and_keeps_link():
    content = nh.build_content({**MAJOR, "description": "a" * 5000}, "v1", with_image_link=True)
    assert len(content) <= nh.CONTENT_LIMIT
    assert content.endswith("https://example.com/x.jpg")
    assert "…" in content
    assert nh.build_content({**MAJOR, "description": "a" * 5000}, "v1").endswith("#v1>)")


def test_repo_image_is_read_from_the_checkout():
    loaded = nh.load_image(REPO_IMAGE)
    assert loaded is not None
    name, data = loaded
    with open(os.path.join(nh.REPO_ROOT, ".github/release-notes/images/albums.jpg"), "rb") as f:
        assert name == "albums.jpg" and data == f.read()


def test_oversized_or_missing_image_is_not_attached():
    assert nh.load_image(REPO_IMAGE, limit=10) is None
    assert nh.load_image("file:///definitely/not/here.jpg") is None
    assert nh.load_image("") is None


def test_repo_url_outside_the_checkout_is_not_read_locally():
    assert nh._local_repo_path(nh.REPO_RAW_PREFIX + "main/../../../etc/passwd") is None


def test_attachment_filename_keeps_extension_and_drops_odd_characters():
    assert nh.attachment_filename("https://x.test/a/web site.gif?v=1") == "web_site.gif"
    assert nh.attachment_filename("https://x.test/") == "image"


def test_multipart_body_names_the_attachment():
    body, ctype = nh.build_multipart({"content": "hi"}, "x.jpg", b"\xff\xd8JPEG")
    boundary = ctype.split("boundary=", 1)[1]
    assert ctype.startswith("multipart/form-data; ")
    parts = body.split(f"--{boundary}".encode())
    payload_part = next(p for p in parts if b'name="payload_json"' in p)
    payload = json.loads(payload_part.split(b"\r\n\r\n", 1)[1].rstrip(b"\r\n"))
    assert payload == {"content": "hi", "attachments": [{"id": 0, "filename": "x.jpg"}]}
    file_part = next(p for p in parts if b'name="files[0]"' in p)
    assert b'filename="x.jpg"' in file_part and b"Content-Type: image/jpeg" in file_part
    assert file_part.split(b"\r\n\r\n", 1)[1] == b"\xff\xd8JPEG\r\n"
    assert body.endswith(f"--{boundary}--\r\n".encode())


def test_no_major_posts_nothing():
    r = _run([MINOR], "--tag", "v1.2.0", env_extra={"DRY_RUN": "1"})
    assert r.returncode == 0 and "nothing to post" in r.stdout


def test_dry_run_prints_payload():
    r = _run([MAJOR, MINOR], "--tag", "v1.2.0", env_extra={"DRY_RUN": "1"})
    assert r.returncode == 0
    previews = json.loads(r.stdout)
    assert [p["content"].splitlines()[0] for p in previews] == ["# X"]
    assert previews[0]["attachment"] == {"filename": "x.jpg", "source": "https://example.com/x.jpg"}
    assert "example.com" not in previews[0]["content"]


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
