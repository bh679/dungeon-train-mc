#!/usr/bin/env python3
"""Unit tests for catch-up.py — the CurseForge modpack's retry after a slow mod approval.

Covers the one decision the script makes: given the releases, what the pack already lists,
and which DT mod files CurseForge has approved, is there a pack version to publish now?
Plus the $GITHUB_OUTPUT contract the workflow's dispatch step gates on.

Network fetchers are stubbed — these tests never touch CurseForge or GitHub.

Run: python3 scripts/modpack/test_catch_up.py   (or via pytest)
"""
import contextlib
import importlib.util
import io
import os
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("catch_up", os.path.join(HERE, "catch-up.py"))
catch_up = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(catch_up)
reconcile = catch_up.reconcile
cf_api = catch_up.cf_api


def _releases(*versions):
    return [(v, f"2026-09-0{i + 1}T00:00:00Z") for i, v in enumerate(versions)]


# --- choose(): the decision ---

def test_newest_release_missing_and_approved_is_published():
    pick = catch_up.choose(_releases("0.828.0", "0.824.0"), {"0.794.0"},
                           {"0.828.0": 8845865, "0.824.0": 8838246})
    assert pick == ("0.828.0", 8845865), pick


def test_pack_already_current_publishes_nothing_even_with_older_gaps():
    # No backfill: the user chose "newest only". 0.824.0 is missing AND approved, but the
    # pack already has the newest release, so it must be left alone.
    pick = catch_up.choose(_releases("0.828.0", "0.824.0"), {"0.828.0", "0.794.0"},
                           {"0.828.0": 8845865, "0.824.0": 8838246})
    assert pick is None, pick


def test_newest_release_not_approved_yet_waits_rather_than_publishing_older():
    # 0.830.0 is out but its file is still under review; 0.829.0 is approved and newer than
    # the pack. Publishing 0.829.0 now would mean two uploads for one catch-up — wait.
    pick = catch_up.choose(_releases("0.830.0", "0.829.0"), {"0.828.0"},
                           {"0.829.0": 111, "0.828.0": 110})
    assert pick is None, pick


def test_no_releases_publishes_nothing():
    assert catch_up.choose([], set(), {}) is None


def test_empty_pack_listing_still_publishes_the_newest_release():
    # A brand-new pack (or a mirror that returned nothing) must not block the first publish.
    pick = catch_up.choose(_releases("0.828.0"), set(), {"0.828.0": 8845865})
    assert pick == ("0.828.0", 8845865), pick


# --- approved_mod_files(): the approval proof ---

class _Stub:
    """Swaps cf_api.get_json for a canned response and clears the API key (mirror path)."""

    def __init__(self, payload):
        self.payload = payload

    def __enter__(self):
        self._real = cf_api.get_json
        self._had_key = os.environ.pop("CURSEFORGE_API_KEY", None)
        payload = self.payload

        def fake(url, headers=None, **kwargs):
            if isinstance(payload, Exception):
                raise payload
            return payload

        cf_api.get_json = fake
        return self

    def __exit__(self, *exc):
        cf_api.get_json = self._real
        if self._had_key is not None:
            os.environ["CURSEFORGE_API_KEY"] = self._had_key
        return False


def test_mirror_listing_maps_display_names_to_file_ids():
    with _Stub({"files": [
        {"id": 8845865, "display": "Dungeon Train v0.828.0"},
        {"id": 8838246, "display": "Dungeon Train v0.824.0"},
        {"id": "not-an-int", "display": "Dungeon Train v0.1.0"},   # malformed → skipped
        {"id": 1, "display": ""},                                    # unnamed → skipped
    ]}):
        assert catch_up.approved_mod_files() == {"0.828.0": 8845865, "0.824.0": 8838246}


# --- run(): the $GITHUB_OUTPUT contract ---

@contextlib.contextmanager
def _fetchers(releases, published, approved):
    real = (reconcile.github_releases, reconcile.curseforge_versions, catch_up.approved_mod_files)
    reconcile.github_releases = lambda: releases
    reconcile.curseforge_versions = lambda: [(v, "") for v in published]
    catch_up.approved_mod_files = lambda: approved
    had = os.environ.pop("CURSEFORGE_API_KEY", None)
    try:
        yield
    finally:
        (reconcile.github_releases, reconcile.curseforge_versions,
         catch_up.approved_mod_files) = real
        if had is not None:
            os.environ["CURSEFORGE_API_KEY"] = had


@contextlib.contextmanager
def _github_output():
    had = os.environ.get("GITHUB_OUTPUT")
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as fh:
        path = fh.name
    os.environ["GITHUB_OUTPUT"] = path
    try:
        yield path
    finally:
        os.environ.pop("GITHUB_OUTPUT", None)
        if had is not None:
            os.environ["GITHUB_OUTPUT"] = had
        os.unlink(path)


def _run(dry_run=False):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = catch_up.run(dry_run)
    return code, out.getvalue()


def test_run_writes_tag_and_file_id_when_there_is_something_to_publish():
    with _fetchers(_releases("0.828.0"), {"0.794.0"}, {"0.828.0": 8845865}), \
            _github_output() as path:
        code, log = _run()
        assert code == 0
        assert open(path).read() == "tag=v0.828.0\ndt_file_id=8845865\n"
        assert "Publishing pack version v0.828.0" in log, log


def test_run_writes_nothing_when_current_so_the_dispatch_step_is_skipped():
    with _fetchers(_releases("0.828.0"), {"0.828.0"}, {"0.828.0": 8845865}), \
            _github_output() as path:
        code, log = _run()
        assert code == 0
        assert open(path).read() == ""
        assert "current" in log, log


def test_dry_run_reports_but_never_writes_output():
    with _fetchers(_releases("0.828.0"), {"0.794.0"}, {"0.828.0": 8845865}), \
            _github_output() as path:
        code, log = _run(dry_run=True)
        assert code == 0
        assert open(path).read() == ""
        assert "Would publish" in log, log


def test_unreadable_listing_exits_nonzero_rather_than_deciding_on_partial_data():
    def boom():
        raise RuntimeError("cfwidget down")
    with _fetchers(_releases("0.828.0"), {"0.794.0"}, {"0.828.0": 8845865}), \
            _github_output() as path:
        catch_up.approved_mod_files = boom
        code, log = _run()
        assert code == 1, code
        assert open(path).read() == ""
        assert "::error::" in log, log


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"ok   {name}")
            except AssertionError as exc:
                failures += 1
                print(f"FAIL {name}: {exc}")
    raise SystemExit(1 if failures else 0)
