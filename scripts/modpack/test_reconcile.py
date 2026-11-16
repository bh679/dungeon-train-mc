#!/usr/bin/env python3
"""Unit tests for reconcile.py — the published-modpack-version guard.

Covers the parts that decide whether a release is reported as missing: version
normalisation across the three sources' different formats, ordering, the --verify
poll outcome, and the drift-report exit code.

Network fetchers are stubbed — these tests never touch CurseForge, Modrinth or GitHub.

Run: python3 scripts/modpack/test_reconcile.py   (or via pytest)
"""
import contextlib
import datetime
import importlib.util
import os

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("reconcile", os.path.join(HERE, "reconcile.py"))
reconcile = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(reconcile)


def hours_ago(n):
    when = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(hours=n)
    return when.isoformat().replace("+00:00", "Z")


def test_normalize_handles_all_three_source_formats():
    # CurseForge displayName, GitHub tag, Modrinth version_number.
    assert reconcile.normalize("Dungeon Train v0.613.0") == "0.613.0"
    assert reconcile.normalize("v0.613.0") == "0.613.0"
    assert reconcile.normalize("0.613.0") == "0.613.0"
    assert reconcile.normalize("  Dungeon Train V0.7.1 ") == "0.7.1"


def test_version_key_orders_numerically_not_lexically():
    versions = ["0.9.0", "0.613.0", "0.72.0", "0.100.0"]
    ordered = sorted(versions, key=reconcile.version_key)
    assert ordered == ["0.9.0", "0.72.0", "0.100.0", "0.613.0"], ordered


def test_version_key_tolerates_junk():
    # Must not raise — a weird display name should sort last, not crash the run.
    assert reconcile.version_key("not-a-version") == (0,)


def _verify(version, authoritative):
    """Run verify() with the CurseForge source forced to API (authoritative) or mirror."""
    if authoritative:
        os.environ["CURSEFORGE_API_KEY"] = "test-key"
    else:
        os.environ.pop("CURSEFORGE_API_KEY", None)
    try:
        return reconcile.verify("curseforge", version, timeout_minutes=0, poll_seconds=0)
    finally:
        os.environ.pop("CURSEFORGE_API_KEY", None)


def test_verify_passes_when_version_is_listed():
    reconcile.PLATFORMS["curseforge"] = lambda: [("0.613.0", hours_ago(1))]
    assert _verify("v0.613.0", authoritative=True) == reconcile.PUBLISHED
    assert _verify("v0.613.0", authoritative=False) == reconcile.PUBLISHED


def test_verify_reports_missing_only_from_an_authoritative_source():
    reconcile.PLATFORMS["curseforge"] = lambda: [("0.592.0", hours_ago(20))]
    assert _verify("v0.613.0", authoritative=True) == reconcile.MISSING


def test_verify_is_inconclusive_when_only_the_cached_mirror_says_missing():
    # Regression guard for the 2026-08-22 live test: cfwidget still showed 260 files /
    # v0.592.0 while curseforge.com already listed v0.613.0. A cache cannot prove absence,
    # so this must NOT fail the release.
    reconcile.PLATFORMS["curseforge"] = lambda: [("0.592.0", hours_ago(20))]
    assert _verify("v0.613.0", authoritative=False) == reconcile.INCONCLUSIVE


def test_verify_does_not_poll_the_cached_mirror():
    # A cache can't fail the check, so waiting on it only slowed every release by its full
    # timeout (30 min). A long timeout must still answer from the first look, without sleeping.
    calls = []
    reconcile.PLATFORMS["curseforge"] = lambda: calls.append(1) or [("0.592.0", hours_ago(20))]
    os.environ.pop("CURSEFORGE_API_KEY", None)
    real_sleep = reconcile.time.sleep
    reconcile.time.sleep = lambda _s: (_ for _ in ()).throw(AssertionError("slept on the mirror"))
    try:
        outcome = reconcile.verify("curseforge", "v0.613.0", timeout_minutes=300, poll_seconds=300)
    finally:
        reconcile.time.sleep = real_sleep
    assert outcome == reconcile.INCONCLUSIVE
    assert len(calls) == 1


def test_only_missing_exits_nonzero():
    reconcile.PLATFORMS["curseforge"] = lambda: [("0.592.0", hours_ago(20))]
    os.environ.pop("CURSEFORGE_API_KEY", None)
    assert reconcile.main(["--verify", "v0.613.0", "--timeout-minutes", "0"]) == 0
    os.environ["CURSEFORGE_API_KEY"] = "test-key"
    try:
        assert reconcile.main(["--verify", "v0.613.0", "--timeout-minutes", "0"]) == 1
    finally:
        os.environ.pop("CURSEFORGE_API_KEY", None)


def test_verify_survives_a_listing_fetch_error():
    def boom():
        raise RuntimeError("cfwidget down")
    reconcile.PLATFORMS["curseforge"] = boom
    # Must report rather than raising — a flaky listing is not a crash. And a listing that never
    # loaded proves nothing, so it must not read as "uploaded but not published".
    assert _verify("v0.1.0", authoritative=True) == reconcile.INCONCLUSIVE


# --- verify_file(): one file's own status on the official API ---

@contextlib.contextmanager
def _api(responses):
    """Serves each api.curseforge.com GET from `responses` in turn (the last one repeats), with
    a key set and sleep stubbed. An Exception in the list is raised. Yields the URLs requested."""
    real_get, real_sleep = reconcile.cf_api.get_json, reconcile.time.sleep
    remaining = list(responses)
    urls = []

    def fake(url, headers=None, **kwargs):
        urls.append(url)
        response = remaining.pop(0) if len(remaining) > 1 else remaining[0]
        if isinstance(response, Exception):
            raise response
        return response

    reconcile.cf_api.get_json = fake
    reconcile.time.sleep = lambda _s: None
    os.environ["CURSEFORGE_API_KEY"] = "test-key"
    try:
        yield urls
    finally:
        reconcile.cf_api.get_json, reconcile.time.sleep = real_get, real_sleep
        os.environ.pop("CURSEFORGE_API_KEY", None)


def _file(status, **fields):
    return {"data": {"id": 42, "fileStatus": status, **fields}}


def test_verify_file_passes_on_approved():
    with _api([_file(4, isAvailable=True)]):
        assert reconcile.verify_file(42, 0, 0) == reconcile.PUBLISHED


def test_verify_file_fails_at_once_on_every_rejected_status():
    # A long timeout must not delay the answer: these don't resolve by waiting.
    for status in (2, 5, 6, 7, 15):
        with _api([_file(status)]) as urls:
            assert reconcile.verify_file(42, 300, 300) == reconcile.REJECTED, status
            assert len(urls) == 1


def test_verify_file_keeps_polling_a_manual_review_hold():
    # The whole point: a held file gets watched, then settles.
    with _api([_file(18), _file(18), _file(4)]) as urls:
        assert reconcile.verify_file(42, 300, 300) == reconcile.PUBLISHED
        assert len(urls) == 3
    with _api([_file(18), _file(5)]):
        assert reconcile.verify_file(42, 300, 300) == reconcile.REJECTED


def test_verify_file_reports_a_hold_past_the_timeout_as_in_review():
    with _api([_file(18)]):
        assert reconcile.verify_file(42, 0, 0) == reconcile.IN_REVIEW


def test_verify_file_never_calls_an_unreadable_file_rejected():
    # The docs don't say whether a held file is visible, so a 404 must stay unknown.
    with _api([RuntimeError("HTTP Error 404")]):
        assert reconcile.verify_file(42, 0, 0) == reconcile.INCONCLUSIVE


def test_verify_exit_codes_with_a_file_id():
    argv = ["--verify", "v1.0.0", "--file-id", "42", "--timeout-minutes", "0"]
    for response, expected in [(_file(4), 0), (_file(5), 1), (_file(18), 0),
                               (RuntimeError("HTTP Error 404"), 0)]:
        with _api([response]):
            assert reconcile.main(argv) == expected, response


def test_a_refused_key_fails_the_run_on_both_paths():
    refused = reconcile.cf_api.AuthError("HTTP 403")
    with _api([refused]) as urls:
        assert reconcile.main(["--verify", "v1.0.0", "--file-id", "42"]) == 1
        assert len(urls) == 1

    def boom():
        raise refused
    reconcile.PLATFORMS["curseforge"] = boom
    with _api([{}]):
        assert reconcile.main(["--verify", "v1.0.0"]) == 1


def test_file_id_is_ignored_without_a_key():
    reconcile.PLATFORMS["curseforge"] = lambda: [("0.592.0", hours_ago(20))]
    os.environ.pop("CURSEFORGE_API_KEY", None)
    assert reconcile.main(["--verify", "v0.613.0", "--file-id", "42"]) == 0


# --- cf_api.list_approved_files(): the official listing ---

def _page(index, total, files):
    return {"data": files, "pagination": {"index": index, "pageSize": 50,
                                          "resultCount": len(files), "totalCount": total}}


def _approved(n):
    return [{"id": i, "displayName": f"Dungeon Train v0.{i}.0", "fileStatus": 4}
            for i in range(n)]


def test_listing_walks_every_page_within_the_documented_limit():
    with _api([_page(0, 120, _approved(50)), _page(50, 120, _approved(50)),
               _page(100, 120, _approved(20))]) as urls:
        files = reconcile.cf_api.list_approved_files(7)
    assert len(files) == 120
    assert [u.split("?")[1] for u in urls] == [
        "index=0&pageSize=50", "index=50&pageSize=50", "index=100&pageSize=50"]


def test_listing_stops_on_an_exact_full_last_page():
    with _api([_page(0, 50, _approved(50)), RuntimeError("asked for a page past the end")]):
        assert len(reconcile.cf_api.list_approved_files(7)) == 50


def test_listing_without_a_total_stops_on_a_short_page():
    with _api([{"data": _approved(50)}, {"data": _approved(3)}]) as urls:
        assert len(reconcile.cf_api.list_approved_files(7)) == 53
        assert len(urls) == 2


def test_listing_never_reports_a_held_or_withdrawn_file_as_published():
    # Catch-up builds a pack from this list, and CurseForge rejects a pack that references an
    # unapproved file.
    files = [{"id": 1, "fileStatus": 4}, {"id": 2, "fileStatus": 18},
             {"id": 3, "fileStatus": 5}, {"id": 4, "fileStatus": 4, "isAvailable": False}]
    with _api([_page(0, 4, files)]):
        assert [f["id"] for f in reconcile.cf_api.list_approved_files(7)] == [1]


def test_listing_fails_rather_than_returning_part_of_the_files():
    with _api([_page(0, 120, _approved(50)), RuntimeError("page 2 failed")]):
        try:
            reconcile.cf_api.list_approved_files(7)
        except RuntimeError:
            return
    raise AssertionError("a partial list came back")


def _stub_sources(cf, mr, releases):
    reconcile.PLATFORMS["curseforge"] = lambda: cf
    reconcile.PLATFORMS["modrinth"] = lambda: mr
    reconcile.github_releases = lambda: releases


def test_drift_report_passes_when_curseforge_is_current():
    fresh = [("0.613.0", hours_ago(1))]
    _stub_sources(fresh, fresh, [("0.613.0", hours_ago(1))])
    assert reconcile.drift_report(fail_if_stale_hours=168) == 0


def test_drift_report_fails_on_a_prolonged_stall():
    _stub_sources(
        cf=[("0.592.0", hours_ago(300))],
        mr=[("0.613.0", hours_ago(1))],
        releases=[("0.613.0", hours_ago(1)), ("0.592.0", hours_ago(300))],
    )
    assert reconcile.drift_report(fail_if_stale_hours=168) == 1


def test_drift_report_tolerates_a_quiet_cascade_stretch():
    # CurseForge is behind, but only by 20h — since the cascade no longer publishes
    # CurseForge packs, that is a normal gap and must NOT fail.
    _stub_sources(
        cf=[("0.592.0", hours_ago(20))],
        mr=[("0.613.0", hours_ago(1))],
        releases=[("0.613.0", hours_ago(1)), ("0.592.0", hours_ago(20))],
    )
    assert reconcile.drift_report(fail_if_stale_hours=168) == 0


def test_drift_report_is_report_only_when_threshold_is_zero():
    _stub_sources(
        cf=[("0.592.0", hours_ago(900))],
        mr=[("0.613.0", hours_ago(1))],
        releases=[("0.613.0", hours_ago(1))],
    )
    assert reconcile.drift_report(fail_if_stale_hours=0) == 0


def test_age_hours_parses_both_timestamp_shapes():
    # CurseForge sends fractional seconds, GitHub sends plain Z.
    assert 0.9 < reconcile._age_hours(hours_ago(1)) < 1.1
    assert reconcile._age_hours("2026-08-21T11:24:23.243Z") > 0
    assert reconcile._age_hours("nonsense") is None


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
