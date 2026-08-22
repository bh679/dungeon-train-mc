#!/usr/bin/env python3
"""Unit tests for reconcile.py — the published-modpack-version guard.

Covers the parts that decide whether a release is reported as missing: version
normalisation across the three sources' different formats, ordering, the --verify
poll outcome, and the drift-report exit code.

Network fetchers are stubbed — these tests never touch CurseForge, Modrinth or GitHub.

Run: python3 scripts/modpack/test_reconcile.py   (or via pytest)
"""
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
    # Must report rather than raising — a flaky listing is not a crash.
    assert _verify("v0.1.0", authoritative=True) == reconcile.MISSING


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
