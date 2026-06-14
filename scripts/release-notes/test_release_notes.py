#!/usr/bin/env python3
"""Unit tests for the changelog ledger scripts (append / render / mark).

Subprocess-based, mirroring scripts/auto-release/test_apply_change.py. Runnable
directly (`python3 scripts/release-notes/test_release_notes.py`) and discoverable
by pytest (bare test_* functions). Local-only — not CI-gated, matching the
auto-release test convention.
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(HERE))
APPEND = os.path.join(HERE, "append-entry.py")
RENDER = os.path.join(HERE, "render-unreleased.py")
MARK = os.path.join(HERE, "mark-released.py")
SCHEMA_FILE = os.path.join(
    REPO_ROOT, ".github/release-notes/schema/changelog.schema.json"
)
SHIPPED_CHANGELOG = os.path.join(REPO_ROOT, ".github/release-notes/changelog.json")


def make_workspace() -> str:
    ws = tempfile.mkdtemp(prefix="release-notes-test-")
    os.makedirs(os.path.join(ws, ".github/release-notes"), exist_ok=True)
    return ws


def changelog_path(ws: str) -> str:
    return os.path.join(ws, ".github/release-notes/changelog.json")


def write_gradle(ws: str, mod_version: str = "0.290.3") -> None:
    with open(os.path.join(ws, "gradle.properties"), "w") as f:
        f.write(
            f"mod_version={mod_version}\n"
            "adventureitemnames_version=0.25.0\n"
            "sable_version=1.2.1+mc1.21.1\n"
        )


def write_changelog(ws: str, data: dict) -> None:
    with open(changelog_path(ws), "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def read_changelog(ws: str) -> dict:
    with open(changelog_path(ws)) as f:
        return json.load(f)


def run(script: str, ws: str, *args: str, **extra_env: str) -> subprocess.CompletedProcess:
    env = {
        **os.environ,
        "CHANGELOG_FILE": changelog_path(ws),
        "GRADLE_PROPERTIES_FILE": os.path.join(ws, "gradle.properties"),
    }
    env.pop("GITHUB_OUTPUT", None)
    for k, v in extra_env.items():
        if v is None:
            env.pop(k, None)
        else:
            env[k] = v
    return subprocess.run(
        [sys.executable, script, *args], cwd=ws, env=env,
        capture_output=True, text=True,
    )


def append(ws: str, entry_id: str, *args: str, **extra_env: str) -> subprocess.CompletedProcess:
    base = [
        "--id", entry_id,
        "--type", "feat",
        "--title", f"Title {entry_id}",
        "--summary", f"Summary for {entry_id}.",
    ]
    return run(APPEND, ws, *base, *args, **extra_env)


# ---------------------------------------------------------------------------
# append-entry.py
# ---------------------------------------------------------------------------

def test_append_computes_minor_bump_when_patch_nonzero() -> None:
    ws = make_workspace()
    write_gradle(ws, "0.290.3")
    r = append(ws, "feature-a")
    assert r.returncode == 0, r.stderr
    entries = read_changelog(ws)["entries"]
    assert len(entries) == 1
    assert entries[0]["version"] == "0.291.0", entries[0]["version"]


def test_append_shares_version_when_patch_zero() -> None:
    # version-bump.yml skips when PATCH==0, so the feature shares the current MINOR.
    ws = make_workspace()
    write_gradle(ws, "0.291.0")
    r = append(ws, "feature-b")
    assert r.returncode == 0, r.stderr
    assert read_changelog(ws)["entries"][0]["version"] == "0.291.0"


def test_append_version_override() -> None:
    ws = make_workspace()
    write_gradle(ws, "0.290.3")
    r = append(ws, "feature-c", "--version", "1.4.0")
    assert r.returncode == 0, r.stderr
    assert read_changelog(ws)["entries"][0]["version"] == "1.4.0"


def test_append_records_all_fields() -> None:
    ws = make_workspace()
    write_gradle(ws, "0.290.3")
    r = run(
        APPEND, ws,
        "--id", "rich",
        "--type", "fix",
        "--title", "A fix",
        "--summary", "It fixes things.",
        "--highlight", "one",
        "--highlight", "two",
        "--pr", "360",
    )
    assert r.returncode == 0, r.stderr
    e = read_changelog(ws)["entries"][0]
    assert e["id"] == "rich"
    assert e["type"] == "fix"
    assert e["highlights"] == ["one", "two"]
    assert e["pr"] == 360
    assert e["released"] is False
    assert e["released_in"] is None
    assert e["released_at"] is None
    assert len(e["date"]) == 10 and e["date"][4] == "-"


def test_append_duplicate_id_refused() -> None:
    ws = make_workspace()
    write_gradle(ws, "0.290.3")
    assert append(ws, "dup").returncode == 0
    r2 = append(ws, "dup")
    assert r2.returncode == 1
    assert "already exists" in r2.stderr
    assert len(read_changelog(ws)["entries"]) == 1


def test_append_bad_version_override_rejected() -> None:
    ws = make_workspace()
    write_gradle(ws, "0.290.3")
    r = append(ws, "bad", "--version", "not-a-version")
    assert r.returncode == 1
    assert "X.Y.Z" in r.stderr


def test_append_creates_file_when_missing() -> None:
    ws = make_workspace()
    write_gradle(ws, "0.290.3")
    # No changelog.json written yet.
    assert not os.path.exists(changelog_path(ws))
    assert append(ws, "first").returncode == 0
    assert len(read_changelog(ws)["entries"]) == 1


# ---------------------------------------------------------------------------
# render-unreleased.py
# ---------------------------------------------------------------------------

def test_render_groups_by_version_newest_first() -> None:
    ws = make_workspace()
    write_gradle(ws, "0.290.3")
    append(ws, "older", "--version", "0.290.0")
    append(ws, "newer", "--version", "0.291.0")
    r = run(RENDER, ws)
    assert r.returncode == 0, r.stderr
    out = r.stdout
    assert "### 0.291.0" in out and "### 0.290.0" in out
    # Newer version section comes first.
    assert out.index("### 0.291.0") < out.index("### 0.290.0")
    assert "**Title newer**" in out and "**Title older**" in out


def test_render_only_unreleased() -> None:
    ws = make_workspace()
    write_changelog(ws, {"entries": [
        {"id": "shipped", "version": "0.289.0", "type": "feat", "title": "Shipped",
         "summary": "Already out.", "highlights": [], "date": "2026-06-01",
         "released": True, "released_in": "v0.289.0", "released_at": "2026-06-01T00:00:00Z"},
        {"id": "pending", "version": "0.290.0", "type": "feat", "title": "Pending",
         "summary": "Not yet.", "highlights": [], "date": "2026-06-10",
         "released": False, "released_in": None, "released_at": None},
    ]})
    r = run(RENDER, ws)
    assert r.returncode == 0, r.stderr
    assert "Pending" in r.stdout
    assert "Shipped" not in r.stdout
    assert "### 0.290.0" in r.stdout and "### 0.289.0" not in r.stdout


def test_render_empty_when_nothing_unreleased() -> None:
    ws = make_workspace()
    write_changelog(ws, {"entries": [
        {"id": "shipped", "version": "0.289.0", "type": "feat", "title": "Shipped",
         "summary": "Out.", "highlights": [], "date": "2026-06-01",
         "released": True, "released_in": "v0.289.0", "released_at": "2026-06-01T00:00:00Z"},
    ]})
    r = run(RENDER, ws)
    assert r.returncode == 0, r.stderr
    assert r.stdout == ""
    assert "No unreleased" in r.stderr


# ---------------------------------------------------------------------------
# mark-released.py
# ---------------------------------------------------------------------------

def test_mark_flips_and_stamps() -> None:
    ws = make_workspace()
    write_gradle(ws, "0.290.3")
    append(ws, "a", "--version", "0.290.0")
    append(ws, "b", "--version", "0.291.0")
    r = run(MARK, ws, "--released-in", "v0.291.0")
    assert r.returncode == 0, r.stderr
    assert "changed=true" in r.stdout
    assert "count=2" in r.stdout
    for e in read_changelog(ws)["entries"]:
        assert e["released"] is True
        assert e["released_in"] == "v0.291.0"
        assert e["released_at"].endswith("Z")


def test_mark_noop_when_nothing_unreleased() -> None:
    ws = make_workspace()
    write_changelog(ws, {"entries": [
        {"id": "shipped", "version": "0.289.0", "type": "feat", "title": "Shipped",
         "summary": "Out.", "highlights": [], "date": "2026-06-01",
         "released": True, "released_in": "v0.289.0", "released_at": "2026-06-01T00:00:00Z"},
    ]})
    before = read_changelog(ws)
    r = run(MARK, ws, "--released-in", "v0.290.0")
    assert r.returncode == 0, r.stderr
    assert "changed=false" in r.stdout
    assert "count=0" in r.stdout
    assert read_changelog(ws) == before  # untouched


def test_mark_leaves_already_released_alone() -> None:
    ws = make_workspace()
    write_changelog(ws, {"entries": [
        {"id": "old", "version": "0.289.0", "type": "feat", "title": "Old",
         "summary": "Out.", "highlights": [], "date": "2026-06-01",
         "released": True, "released_in": "v0.289.0", "released_at": "2026-06-01T00:00:00Z"},
        {"id": "new", "version": "0.290.0", "type": "feat", "title": "New",
         "summary": "Fresh.", "highlights": [], "date": "2026-06-10",
         "released": False, "released_in": None, "released_at": None},
    ]})
    r = run(MARK, ws, "--released-in", "v0.290.0")
    assert r.returncode == 0, r.stderr
    assert "count=1" in r.stdout
    entries = {e["id"]: e for e in read_changelog(ws)["entries"]}
    assert entries["old"]["released_in"] == "v0.289.0"  # unchanged
    assert entries["new"]["released_in"] == "v0.290.0"


# ---------------------------------------------------------------------------
# End-to-end + schema
# ---------------------------------------------------------------------------

def test_end_to_end_append_render_mark_render() -> None:
    ws = make_workspace()
    write_gradle(ws, "0.290.3")
    append(ws, "feat-one", "--version", "0.290.0")
    append(ws, "feat-two", "--version", "0.291.0")
    assert run(RENDER, ws).stdout.count("### ") == 2
    run(MARK, ws, "--released-in", "v0.291.0")
    # After release, unreleased set is empty.
    assert run(RENDER, ws).stdout == ""
    # A new merge after the release is still unreleased.
    append(ws, "feat-three", "--version", "0.292.0")
    out = run(RENDER, ws).stdout
    assert "feat-three" in out or "Title feat-three" in out
    assert "### 0.292.0" in out


def test_produced_and_shipped_changelog_match_schema() -> None:
    try:
        import jsonschema  # type: ignore
    except ImportError:
        print("  (skipped schema validation — jsonschema not installed)")
        return
    with open(SCHEMA_FILE) as f:
        schema = json.load(f)

    # Shipped ledger validates.
    with open(SHIPPED_CHANGELOG) as f:
        jsonschema.validate(json.load(f), schema)

    # A produced ledger validates (append + mark a couple of entries).
    ws = make_workspace()
    write_gradle(ws, "0.290.3")
    run(
        APPEND, ws, "--id", "schema-feat", "--type", "feat",
        "--title", "Schema feat", "--summary", "Validates.",
        "--highlight", "h1", "--pr", "42",
    )
    run(MARK, ws, "--released-in", "v0.291.0")
    jsonschema.validate(read_changelog(ws), schema)


def main() -> int:
    tests = [
        test_append_computes_minor_bump_when_patch_nonzero,
        test_append_shares_version_when_patch_zero,
        test_append_version_override,
        test_append_records_all_fields,
        test_append_duplicate_id_refused,
        test_append_bad_version_override_rejected,
        test_append_creates_file_when_missing,
        test_render_groups_by_version_newest_first,
        test_render_only_unreleased,
        test_render_empty_when_nothing_unreleased,
        test_mark_flips_and_stamps,
        test_mark_noop_when_nothing_unreleased,
        test_mark_leaves_already_released_alone,
        test_end_to_end_append_render_mark_render,
        test_produced_and_shipped_changelog_match_schema,
    ]
    failed = 0
    for t in tests:
        try:
            t()
        except AssertionError as e:
            print(f"FAIL {t.__name__}: {e}")
            failed += 1
        except Exception as e:  # noqa: BLE001 — surface unexpected errors per-test
            print(f"ERROR {t.__name__}: {type(e).__name__}: {e}")
            failed += 1
    print(f"\n{len(tests) - failed}/{len(tests)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
