#!/usr/bin/env python3
"""Unit tests for listing.py: the page rules, the sign-off guards and token handling.

No test talks to a store: `call` is replaced with a fake that records each request.

Run: python3 scripts/listing/test_listing.py   (or via pytest)
"""
import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
import urllib.parse
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import listing as tool  # noqa: E402

MOD = "https://modrinth.com/mod/dungeon-train"
PACK = "https://modrinth.com/modpack/bEFyz3ji"
FIRST_SECTION = (
    f"This mod is designed specifically for the [Dungeon Train modpack]({PACK}) / [mod]({MOD}), "
    "but can be used independently."
)
BODY = f"# Example\n\n{FIRST_SECTION}\n\n## What it does\n\nThings.\n"
SECRET = "mrp_secret_value_never_printed"


def base_listing(**overrides) -> dict:
    return {
        "slug": "example-mod",
        "title": "Example Mod",
        "summary": "Does an example thing.",
        "categories": ["utility"],
        "license_id": "MIT",
        "source_url": "https://github.com/bh679/example-mc",
        "issues_url": "https://dungeon.training/issues#example",
        "icon": "icon.png",
        "screenshots": [{"file": "one.png", "title": "One", "featured": True}],
        **overrides,
    }


def write_folder(folder: str, listing: dict, body: str = BODY) -> None:
    with open(os.path.join(folder, "listing.json"), "w", encoding="utf-8") as f:
        json.dump(listing, f)
    with open(os.path.join(folder, "LISTING.md"), "w", encoding="utf-8") as f:
        f.write(body)
    for name in ("icon.png", "one.png"):
        with open(os.path.join(folder, name), "wb") as f:
            f.write(b"\x89PNG")


class FakeStore:
    """Stands in for listing.call: answers from a table and records every request."""

    def __init__(self, project=None, versions=None):
        self.project = project
        self.versions = versions if versions is not None else []
        self.requests = []

    def __call__(self, method, url, headers=None, data=None):
        self.requests.append((method, url, headers or {}, data))
        if method == "GET" and url.endswith("/version"):
            return 200, self.versions
        if method == "POST" and "/gallery?" in url:
            title = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)["title"][0]
            self.project = self.project or {"gallery": []}
            self.project["gallery"].append({"title": title, "url": f"https://cdn/{title}.png"})
            return 204, None
        if method == "GET":
            return (200, self.project) if self.project else (404, None)
        if method == "POST" and url.endswith("/project"):
            return 200, {"id": "abc"}
        return 204, None

    def writes(self):
        return [r for r in self.requests if r[0] != "GET"]


class Base(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.dir = temp.name
        self.tokens = os.path.join(self.dir, "tokens.env")
        with open(self.tokens, "w", encoding="utf-8") as f:
            f.write(f"# comment\nMODRINTH_TOKEN={SECRET}\n")
        patcher = mock.patch.object(tool, "TOKENS_FILE", self.tokens)
        patcher.start()
        self.addCleanup(patcher.stop)
        # load_tokens took its default when it was defined, so point that at the test file too.
        patcher = mock.patch.object(tool.load_tokens, "__defaults__", (self.tokens,))
        patcher.start()
        self.addCleanup(patcher.stop)

    def problems(self, listing=None, body=BODY, page='<tr id="example">'):
        write_folder(self.dir, listing or base_listing(), body)
        loaded = tool.load_listing(self.dir)
        return tool.page_problems(loaded, self.dir, fetch_page=lambda _url: page)

    def run_cli(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = tool.main(list(argv))
        return code, out.getvalue() + err.getvalue()


class PageRules(Base):
    def test_good_page_passes(self):
        self.assertEqual(self.problems(), [])

    def test_long_dashes_fail_everywhere(self):
        for dash in ("—", "–"):
            self.assertTrue(self.problems(body=BODY + f"Words {dash} more.\n"))
            self.assertTrue(self.problems(base_listing(summary=f"One {dash} two")))
            shot = {"file": "one.png", "title": f"One {dash} two", "featured": True}
            self.assertTrue(self.problems(base_listing(screenshots=[shot])))

    def test_ordinary_hyphen_passes(self):
        self.assertEqual(self.problems(base_listing(summary="A well-made mod - really")), [])

    def test_missing_sentence_fails(self):
        body = f"# Example\n\nA mod. [m]({MOD}) [p]({PACK})\n\n## More\n\n{FIRST_SECTION}\n"
        self.assertTrue(any("first section must say" in p for p in self.problems(body=body)))

    def test_missing_links_fail(self):
        plain = "This mod is designed specifically for the Dungeon Train modpack / mod, but can be used independently."
        found = self.problems(body=f"{plain}\n")
        self.assertTrue(any("mod page" in p for p in found))
        self.assertTrue(any("modpack page" in p for p in found))

    def test_dependent_mod_uses_its_own_sentence(self):
        body = (
            f"Built for [Dungeon Train]({MOD}) and only does something when Dungeon Train is "
            f"installed. Also in the [modpack]({PACK}).\n"
        )
        self.assertEqual(self.problems(base_listing(requires_dungeon_train=True), body=body), [])
        self.assertTrue(self.problems(base_listing(requires_dungeon_train=True)))

    def test_dungeon_trains_own_pages_skip_the_sentence(self):
        self.assertEqual(self.problems(base_listing(dungeon_train_itself=True), body="# DT\n\nA game.\n"), [])

    def test_github_issues_link_fails(self):
        listing = base_listing(issues_url="https://github.com/bh679/example-mc/issues")
        self.assertTrue(any("issues_url" in p for p in self.problems(listing)))

    def test_issues_anchor_must_exist(self):
        found = self.problems(page='<tr id="other">')
        self.assertTrue(any("no row" in p for p in found))

    def test_issues_anchor_found_in_the_relays_list(self):
        answers = {
            tool.ISSUES_API: json.dumps({"ok": True, "projects": [{"id": "example"}]}),
            tool.ISSUES_PAGE: "<html>built-in rows only</html>",
        }
        write_folder(self.dir, base_listing())
        found = tool.page_problems(tool.load_listing(self.dir), self.dir, fetch_page=answers.__getitem__)
        self.assertEqual(found, [])

    def test_issues_link_with_a_slash_before_the_anchor_fails(self):
        found = self.problems(base_listing(issues_url="https://dungeon.training/issues/#example"))
        self.assertTrue(any("issues_url" in p for p in found))

    def test_screenshots_are_required_and_must_exist(self):
        self.assertTrue(any("screenshots" in p for p in self.problems(base_listing(screenshots=[]))))
        shot = {"file": "gone.png", "title": "Gone", "featured": True}
        self.assertTrue(any("gone.png" in p for p in self.problems(base_listing(screenshots=[shot]))))

    def test_one_screenshot_must_be_featured(self):
        shot = {"file": "one.png", "title": "One"}
        self.assertTrue(any("featured" in p for p in self.problems(base_listing(screenshots=[shot]))))

    def test_oversized_icon_fails(self):
        write_folder(self.dir, base_listing())
        with open(os.path.join(self.dir, "icon.png"), "wb") as f:
            f.write(b"x" * (tool.MAX_ICON_BYTES + 1))
        found = tool.page_problems(tool.load_listing(self.dir), self.dir, offline=True)
        self.assertTrue(any("icon.png" in p for p in found))


class SignOff(Base):
    def live(self, **overrides):
        return {
            "slug": "example-mod",
            "title": "Example Mod",
            "description": "Does an example thing.",
            "body": BODY,
            "categories": ["utility"],
            "license": {"id": "MIT"},
            "source_url": "https://github.com/bh679/example-mc",
            "issues_url": "https://dungeon.training/issues#example",
            "gallery": [{"title": "One", "url": "https://cdn/one.png"}],
            **overrides,
        }

    def test_submit_refuses_without_confirmed(self):
        store = FakeStore(versions=[{"id": "v"}])
        with mock.patch.object(tool, "call", store):
            code, out = self.run_cli("submit", "example-mod")
        self.assertEqual(code, 1)
        self.assertIn("--confirmed", out)
        self.assertEqual(store.requests, [])

    def test_submit_refuses_a_draft_with_no_version(self):
        store = FakeStore(versions=[])
        with mock.patch.object(tool, "call", store):
            code, out = self.run_cli("submit", "example-mod", "--confirmed")
        self.assertEqual(code, 1)
        self.assertIn("no version", out)
        self.assertEqual(store.writes(), [])

    def test_submit_sets_processing(self):
        store = FakeStore(versions=[{"id": "v"}])
        with mock.patch.object(tool, "call", store):
            code, _ = self.run_cli("submit", "example-mod", "--confirmed")
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(store.writes()[0][3]), {"status": "processing"})

    def test_update_refuses_without_a_mode(self):
        write_folder(self.dir, base_listing())
        store = FakeStore(self.live())
        with mock.patch.object(tool, "call", store):
            code, out = self.run_cli("update", self.dir, "--offline")
        self.assertEqual(code, 1)
        self.assertIn("--dry-run", out)
        self.assertEqual(store.requests, [])

    def test_dry_run_writes_nothing_and_shows_the_change(self):
        write_folder(self.dir, base_listing(summary="A new summary."))
        store = FakeStore(self.live())
        with mock.patch.object(tool, "call", store):
            code, out = self.run_cli("update", self.dir, "--offline", "--dry-run")
        self.assertEqual(code, 0)
        self.assertIn("A new summary.", out)
        self.assertEqual(store.writes(), [])

    def test_update_sends_only_what_changed(self):
        write_folder(self.dir, base_listing(summary="A new summary."))
        store = FakeStore(self.live())
        with mock.patch.object(tool, "call", store):
            code, _ = self.run_cli("update", self.dir, "--offline", "--confirmed")
        self.assertEqual(code, 0)
        self.assertEqual(len(store.writes()), 1)
        self.assertEqual(json.loads(store.writes()[0][3]), {"description": "A new summary."})

    def test_update_refuses_a_page_that_breaks_the_rules(self):
        write_folder(self.dir, base_listing(summary="One — two"))
        store = FakeStore(self.live())
        with mock.patch.object(tool, "call", store):
            code, out = self.run_cli("update", self.dir, "--offline", "--confirmed")
        self.assertEqual(code, 1)
        self.assertIn("long dash", out)
        self.assertEqual(store.requests, [])

    def test_dry_run_of_a_page_breaking_the_rules_lists_problems_and_the_diff(self):
        write_folder(self.dir, base_listing(summary="A new summary. —"))
        store = FakeStore(self.live())
        with mock.patch.object(tool, "call", store):
            code, out = self.run_cli("update", self.dir, "--offline", "--dry-run")
        self.assertEqual(code, 0)
        self.assertIn("long dash", out)
        self.assertIn("A new summary.", out)
        self.assertEqual(store.writes(), [])

    def test_dry_run_reads_private_drafts_with_the_token(self):
        write_folder(self.dir, base_listing())
        store = FakeStore(self.live())
        with mock.patch.object(tool, "call", store):
            self.run_cli("update", self.dir, "--offline", "--dry-run")
        self.assertEqual(store.requests[0][2].get("Authorization"), SECRET)

    def test_screenshot_already_live_is_not_uploaded_again(self):
        shot = {
            "file": "one.png",
            "title": "A new caption",
            "featured": True,
            "live_url": "https://cdn/one.png",
        }
        write_folder(self.dir, base_listing(screenshots=[shot]))
        store = FakeStore(self.live())
        with mock.patch.object(tool, "call", store):
            code, _ = self.run_cli("update", self.dir, "--offline", "--confirmed")
        self.assertEqual(code, 0)
        self.assertEqual(len(store.writes()), 1)
        self.assertNotEqual(store.writes()[0][0], "POST")
        self.assertIn("/gallery?", store.writes()[0][1])
        self.assertIn("title=A+new+caption", store.writes()[0][1])

    def test_unchanged_live_screenshot_sends_nothing(self):
        shot = {
            "file": "one.png",
            "title": "One",
            "featured": True,
            "live_url": "https://cdn/one.png",
        }
        write_folder(self.dir, base_listing(screenshots=[shot]))
        gallery = [{"title": "One", "url": "https://cdn/one.png", "featured": True}]
        store = FakeStore(self.live(gallery=gallery))
        with mock.patch.object(tool, "call", store):
            code, _ = self.run_cli("update", self.dir, "--offline", "--confirmed")
        self.assertEqual(code, 0)
        self.assertEqual(store.writes(), [])

    def test_empty_screenshot_description_is_left_out(self):
        shot = {"file": "one.png", "title": "One", "description": "", "featured": True}
        write_folder(self.dir, base_listing(screenshots=[shot]))
        store = FakeStore()
        with mock.patch.object(tool, "call", store):
            self.run_cli("draft", self.dir, "--offline")
        self.assertNotIn("description=", store.writes()[1][1])

    def test_draft_records_where_each_screenshot_went(self):
        write_folder(self.dir, base_listing())
        with mock.patch.object(tool, "call", FakeStore()):
            self.run_cli("draft", self.dir, "--offline")
        saved = tool.load_listing(self.dir)["screenshots"][0]
        self.assertEqual(saved["live_url"], "https://cdn/One.png")

    def test_a_folder_made_by_draft_can_change_a_caption(self):
        write_folder(self.dir, base_listing())
        store = FakeStore()
        with mock.patch.object(tool, "call", store):
            self.run_cli("draft", self.dir, "--offline")
            listing = tool.load_listing(self.dir)
            shot = {**listing["screenshots"][0], "title": "New caption"}
            del listing["body"]
            write_folder(self.dir, {**listing, "screenshots": [shot]})
            store.project = {**self.live(), "gallery": store.project["gallery"]}
            store.requests.clear()
            code, _ = self.run_cli("update", self.dir, "--offline", "--confirmed")
        self.assertEqual(code, 0)
        self.assertEqual([r[0] for r in store.writes()], ["PATCH"])

    def test_draft_is_private_and_uploads_screenshots(self):
        write_folder(self.dir, base_listing())
        store = FakeStore()
        with mock.patch.object(tool, "call", store):
            code, _ = self.run_cli("draft", self.dir, "--offline")
        self.assertEqual(code, 0)
        create, gallery = store.writes()[:2]
        self.assertIn(b'"is_draft": true', create[3])
        self.assertIn("/gallery?", gallery[1])
        self.assertIn("featured=true", gallery[1])


class Tokens(Base):
    def test_modrinth_probe_accepts_a_token_refused_only_by_the_project(self):
        refused = {"error": "auth_error", "description": "You do not have permission to edit this project!"}
        with mock.patch.object(tool, "call", return_value=(401, refused)):
            self.assertEqual(tool.probe_modrinth(SECRET), "ok")

    def test_modrinth_probe_fails_a_bad_token(self):
        bad = {"error": "auth_error", "description": "authenticating API request"}
        with mock.patch.object(tool, "call", return_value=(401, bad)):
            self.assertEqual(tool.probe_modrinth(SECRET), "failed (HTTP 401)")

    def test_values_never_appear_in_output(self):
        with mock.patch.object(tool, "call", return_value=(404, None)):
            _, out = self.run_cli("tokens")
        self.assertNotIn(SECRET, out)

    def test_tokens_command_fails_when_one_is_missing(self):
        with mock.patch.object(tool, "call", return_value=(404, None)):
            code, out = self.run_cli("tokens")
        self.assertEqual(code, 1)
        self.assertIn("MODRINTH_TOKEN: ok", out)
        self.assertIn("CURSEFORGE_TOKEN: missing", out)

    def test_error_text_never_carries_the_token(self):
        write_folder(self.dir, base_listing())
        with mock.patch.object(tool, "call", return_value=(500, "server trouble")):
            code, out = self.run_cli("draft", self.dir, "--offline")
        self.assertEqual(code, 1)
        self.assertIn("HTTP 500", out)
        self.assertNotIn(SECRET, out)

    def test_missing_file_is_a_clear_error(self):
        os.remove(self.tokens)
        code, out = self.run_cli("tokens")
        self.assertEqual(code, 1)
        self.assertIn("no token file", out)


ADMIN_SECRET = "adminCapNeverPrinted0000"


class IssuesAdd(Base):
    def setUp(self):
        super().setUp()
        self.admin_file = os.path.join(self.dir, "relay-admin-url.txt")
        with open(self.admin_file, "w", encoding="utf-8") as f:
            f.write(f"https://brennan.games/api/dp-relay/{ADMIN_SECRET}\n")
        patcher = mock.patch.object(tool, "RELAY_ADMIN_URL_FILE", self.admin_file)
        patcher.start()
        self.addCleanup(patcher.stop)
        write_folder(self.dir, base_listing())

    def test_without_confirmed_it_only_shows_the_row(self):
        store = FakeStore()
        with mock.patch.object(tool, "call", store):
            code, out = self.run_cli("issues-add", self.dir)
        self.assertEqual(code, 0)
        self.assertIn("Example Mod (#example)", out)
        self.assertIn("--confirmed", out)
        self.assertEqual(store.requests, [])

    def test_confirmed_puts_the_row_to_the_relay(self):
        store = mock.Mock(return_value=(200, {"ok": True, "projects": []}))
        with mock.patch.object(tool, "call", store):
            code, out = self.run_cli("issues-add", self.dir, "--confirmed")
        self.assertEqual(code, 0)
        method, url = store.call_args.args
        self.assertEqual(method, "PUT")
        self.assertTrue(url.endswith(f"/{ADMIN_SECRET}/issues/projects"))
        sent = json.loads(store.call_args.kwargs["data"])
        self.assertEqual(sent, {"id": "example", "name": "Example Mod", "repo": "bh679/example-mc"})
        self.assertIn("https://dungeon.training/issues#example", out)
        self.assertNotIn(ADMIN_SECRET, out)

    def test_a_refusal_names_the_reason_but_never_the_admin_url(self):
        with mock.patch.object(tool, "call", return_value=(400, {"error": "repo_taken"})):
            code, out = self.run_cli("issues-add", self.dir, "--confirmed")
        self.assertEqual(code, 1)
        self.assertIn("repo_taken", out)
        self.assertNotIn(ADMIN_SECRET, out)

    def test_an_unreachable_relay_never_prints_the_admin_url(self):
        failing = mock.Mock(side_effect=tool.ListingError(f"PUT https://x/{ADMIN_SECRET} failed"))
        with mock.patch.object(tool, "call", failing):
            code, out = self.run_cli("issues-add", self.dir, "--confirmed")
        self.assertEqual(code, 1)
        self.assertNotIn(ADMIN_SECRET, out)

    def test_source_must_be_one_of_our_github_repos(self):
        write_folder(self.dir, base_listing(source_url="https://gitlab.com/someone/x"))
        code, out = self.run_cli("issues-add", self.dir, "--confirmed")
        self.assertEqual(code, 1)
        self.assertIn("source_url", out)


if __name__ == "__main__":
    unittest.main()
