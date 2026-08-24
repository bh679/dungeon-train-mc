#!/usr/bin/env python3
"""Unit tests for wait-for-approval.py — the mod-file approval gate.

Covers the decision this script exists to make: given a reading of a file's status, does
the modpack build proceed or stop? Both source paths are exercised (authoritative API and
the cfwidget mirror), plus the fail-closed behaviour that keeps an unapproved file from
reaching a pack manifest.

Network fetchers are stubbed — these tests never touch CurseForge.

Run: python3 scripts/modpack/test_wait_for_approval.py   (or via pytest)
"""
import importlib.util
import os

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "wait_for_approval", os.path.join(HERE, "wait-for-approval.py"))
waiter = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(waiter)
cf_api = waiter.cf_api

PROJECT = 1527512
FILE_ID = 8715518


class _Stub:
    """Swaps cf_api.get_json for a canned response, and sets/clears the API key."""

    def __init__(self, payload, key=None):
        self.payload = payload
        self.key = key

    def __enter__(self):
        self._real = cf_api.get_json
        self._had_key = os.environ.get("CURSEFORGE_API_KEY")
        if self.key:
            os.environ["CURSEFORGE_API_KEY"] = self.key
        else:
            os.environ.pop("CURSEFORGE_API_KEY", None)
        payload = self.payload

        def fake(url, headers=None, **kwargs):
            if isinstance(payload, Exception):
                raise payload
            return payload

        cf_api.get_json = fake
        return self

    def __exit__(self, *exc):
        cf_api.get_json = self._real
        os.environ.pop("CURSEFORGE_API_KEY", None)
        if self._had_key is not None:
            os.environ["CURSEFORGE_API_KEY"] = self._had_key
        return False


def _wait():
    """Run the gate with no waiting — one poll, then the deadline."""
    return waiter.wait_for_approval(PROJECT, FILE_ID, timeout_minutes=0, poll_seconds=0)


def _api_file(**fields):
    return {"data": {"id": FILE_ID, "displayName": "Dungeon Train v0.632.0", **fields}}


# --- authoritative path (CURSEFORGE_API_KEY set) ---

def test_approved_file_lets_the_pack_build():
    with _Stub(_api_file(fileStatus=4, isAvailable=True), key="test-key"):
        assert _wait() is True


def test_file_still_under_review_stops_the_build():
    # The actual August 2026 failure: the upload succeeded, approval never came.
    with _Stub(_api_file(fileStatus=3), key="test-key"):
        assert _wait() is False


def test_rejected_file_stops_the_build():
    with _Stub(_api_file(fileStatus=5), key="test-key"):
        assert _wait() is False


def test_approved_but_unavailable_file_stops_the_build():
    # Approved yet withdrawn from public listing — referencing it still fails the pack.
    with _Stub(_api_file(fileStatus=4, isAvailable=False), key="test-key"):
        assert _wait() is False


def test_missing_status_is_treated_as_not_approved_not_a_crash():
    with _Stub(_api_file(), key="test-key"):
        assert _wait() is False


def test_status_name_is_reported_for_a_known_code():
    with _Stub(_api_file(fileStatus=3), key="test-key"):
        state, detail = waiter.status_via_api(PROJECT, FILE_ID)
    assert state == waiter.PENDING
    assert detail == "UnderReview", detail


# --- mirror path (no key) ---

def test_mirror_listing_the_file_proves_approval():
    listing = {"files": [{"id": FILE_ID, "display": "Dungeon Train v0.632.0"}]}
    with _Stub(listing):
        assert _wait() is True


def test_mirror_not_listing_the_file_fails_closed():
    # The mirror cannot prove absence, but we stop anyway: a needless re-dispatch is
    # cheaper than another silently rejected pack version.
    with _Stub({"files": [{"id": 1, "display": "Dungeon Train v0.1.0"}]}):
        assert _wait() is False


def test_empty_mirror_listing_fails_closed():
    with _Stub({}):
        assert _wait() is False


# --- transport faults ---

def test_lookup_failure_does_not_crash_the_gate():
    with _Stub(RuntimeError("cfwidget down"), key="test-key"):
        assert _wait() is False


# --- CLI ---

def test_main_exits_zero_only_when_approved():
    with _Stub(_api_file(fileStatus=4), key="test-key"):
        assert waiter.main(["--file-id", str(FILE_ID), "--timeout-minutes", "0"]) == 0
    with _Stub(_api_file(fileStatus=3), key="test-key"):
        assert waiter.main(["--file-id", str(FILE_ID), "--timeout-minutes", "0"]) == 1


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
