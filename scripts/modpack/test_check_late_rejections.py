#!/usr/bin/env python3
"""Unit tests for check-late-rejections.py: release files rejected after verify stopped watching.

Covers what each file state leads to, the run-title contract the file ids come from, and the
alert-once rule. CurseForge and gh are stubbed: these tests never touch either.

Run: python3 scripts/modpack/test_check_late_rejections.py   (or via pytest)
"""
import contextlib
import importlib.util
import io
import os
import re
import subprocess
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "check_late_rejections", os.path.join(HERE, "check-late-rejections.py"))
late = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(late)
cf_api = late.cf_api

APPROVED = (cf_api.APPROVED, "Approved")
HELD = (cf_api.PENDING, "UnderManualReview")
REJECTED = (cf_api.REJECTED, "Rejected")


class FakeGitHub:
    """Stands in for gh: holds verify run titles and issues, and records what was opened."""

    def __init__(self, titles=(), issue_bodies=()):
        self.titles = list(titles)
        self.issue_bodies = list(issue_bodies)
        self.opened = []
        self.fail_on = None

    def __call__(self, *args):
        if self.fail_on == args[:2]:
            raise subprocess.CalledProcessError(1, "gh", stderr="gh failed")
        if args[:2] == ("run", "list"):
            return "\n".join(self.titles) + "\n"
        if args[:2] == ("issue", "list"):
            return "\n".join(self.issue_bodies) + "\n"
        if args[:2] == ("issue", "create"):
            flags = dict(zip(args[2::2], args[3::2]))
            self.opened.append(flags)
            self.issue_bodies.append(flags["--body"])
            return "https://github.com/example/issues/1\n"
        return ""


def _run(github, statuses, argv=(), key="test-key"):
    """Run main(). Returns (exit code, output, file ids read). statuses: {id: result or error}."""
    reads = []

    def file_status(_project, file_id):
        reads.append(file_id)
        result = statuses[file_id]
        if isinstance(result, Exception):
            raise result
        return result

    env = {"CURSEFORGE_API_KEY": key} if key else {}
    out = io.StringIO()
    with mock.patch.dict(os.environ, env, clear=False), \
            mock.patch.object(late, "gh", github), \
            mock.patch.object(cf_api, "file_status", file_status), \
            contextlib.redirect_stdout(out):
        if not key:
            os.environ.pop("CURSEFORGE_API_KEY", None)
        code = late.main(list(argv))
    return code, out.getvalue(), reads


def _github(*files, **kwargs):
    return FakeGitHub([f"Verify v0.{f}.0 file {f}" for f in files], **kwargs)


# --- run titles: where the file ids come from ---

def test_run_title_gives_tag_and_file_id():
    assert late.parse_run_titles(["Verify v0.1684.0 file 9470214"]) == {9470214: "v0.1684.0"}


def test_titles_without_a_file_id_are_skipped():
    assert late.parse_run_titles(["Verify v0.1.0 file ", "Verify v0.1.0 file", "Verify CurseForge Upload"]) == {}


def test_a_title_cannot_smuggle_text_into_the_issue():
    assert late.parse_run_titles(["Verify v0.1.0;@everyone file 1", "Verify v0.1.0 file 1 x"]) == {}


def test_the_verify_workflow_titles_runs_the_way_the_script_reads_them():
    path = os.path.join(HERE, "..", "..", ".github", "workflows", "verify-curseforge-upload.yml")
    with open(path, encoding="utf-8") as handle:
        run_name = re.search(r"^run-name: (.+)$", handle.read(), re.M).group(1)
    rendered = run_name.replace("${{ inputs.tag }}", "v0.1.0").replace("${{ inputs.file_id }}", "7")
    assert late.parse_run_titles([rendered]) == {7: "v0.1.0"}, rendered


# --- what each file state leads to ---

def test_approved_opens_nothing():
    github = _github(1)
    code, _, _ = _run(github, {1: APPROVED})
    assert (code, github.opened) == (0, [])


def test_hold_is_not_a_rejection():
    github = _github(1)
    code, _, _ = _run(github, {1: HELD})
    assert (code, github.opened) == (0, [])


def test_rejection_opens_an_issue_and_goes_red():
    github = _github(1)
    code, out, _ = _run(github, {1: REJECTED})
    assert code == 1
    assert len(github.opened) == 1
    assert github.opened[0]["--label"] == late.LABEL
    assert "cf-file:1" in github.opened[0]["--body"]
    assert "::error::" in out


def test_404_is_not_a_rejection():
    github = _github(1)
    code, out, _ = _run(github, {1: RuntimeError("HTTP 404")})
    assert (code, github.opened) == (0, [])
    assert "not a rejection" in out


def test_three_unreadable_in_a_row_stops_the_pass():
    github = _github(1, 2, 3, 4)
    error = RuntimeError("down")
    code, _, reads = _run(github, {1: error, 2: error, 3: error, 4: error})
    assert (code, reads, github.opened) == (0, [1, 2, 3], [])


def test_a_good_read_resets_the_unreadable_count():
    github = _github(1, 2, 3, 4, 5)
    error = RuntimeError("down")
    _, _, reads = _run(github, {1: error, 2: error, 3: HELD, 4: error, 5: error})
    assert reads == [1, 2, 3, 4, 5]


def test_no_key_does_nothing():
    github = mock.Mock(side_effect=AssertionError("gh must not be called"))
    code, out, reads = _run(github, {}, key=None)
    assert (code, reads) == (0, [])
    assert "unset" in out


def test_refused_key_warns_without_going_red():
    github = _github(1, 2)
    code, out, reads = _run(github, {1: cf_api.AuthError("HTTP 403"), 2: REJECTED})
    assert (code, reads, github.opened) == (0, [1], [])
    assert "::warning::" in out


def test_refused_key_still_reports_what_it_already_found():
    github = _github(1, 2)
    code, _, _ = _run(github, {1: REJECTED, 2: cf_api.AuthError("HTTP 403")})
    assert code == 1
    assert "cf-file:1" in github.opened[0]["--body"]


def test_unreadable_runs_judge_nothing():
    github = _github(1)
    github.fail_on = ("run", "list")
    code, _, reads = _run(github, {1: REJECTED})
    assert (code, reads, github.opened) == (0, [], [])


def test_unreadable_issues_judge_nothing():
    # Without the issue list a known rejection would alert again every hour.
    github = _github(1)
    github.fail_on = ("issue", "list")
    code, _, reads = _run(github, {1: REJECTED})
    assert (code, reads, github.opened) == (0, [], [])


def test_an_issue_that_cannot_be_opened_goes_red_so_nothing_is_lost():
    github = _github(1)
    github.fail_on = ("issue", "create")
    code, out, _ = _run(github, {1: REJECTED})
    assert code == 1
    assert "could not be opened" in out


# --- alert once ---

def test_second_pass_opens_nothing_and_stays_green():
    github = _github(1)
    first, _, _ = _run(github, {1: REJECTED})
    second, _, reads = _run(github, {1: REJECTED})
    assert (first, second, reads, len(github.opened)) == (1, 0, [], 1)


def test_an_issue_of_any_state_counts_as_alerted():
    # The state filter is what stops a closed issue alerting again.
    github = _github(1, issue_bodies=["| `cf-file:1` |"])
    calls = []

    def spy(*args):
        calls.append(args)
        return github(*args)

    code, _, reads = _run(spy, {1: REJECTED})
    assert (code, reads, github.opened) == (0, [], [])
    issue_list = next(c for c in calls if c[:2] == ("issue", "list"))
    assert issue_list[issue_list.index("--state") + 1] == "all"


def test_many_rejections_share_one_issue():
    github = _github(1, 2, 3)
    code, _, _ = _run(github, {1: REJECTED, 2: REJECTED, 3: HELD})
    assert (code, len(github.opened)) == (1, 1)
    body = github.opened[0]["--body"]
    assert "cf-file:1" in body and "cf-file:2" in body and "cf-file:3" not in body


def test_only_the_new_rejection_is_alerted():
    github = _github(1, 2, issue_bodies=["`cf-file:1`"])
    code, _, reads = _run(github, {2: REJECTED})
    assert (code, reads) == (1, [2])
    assert "cf-file:1" not in github.opened[0]["--body"]


# --- dry run ---

def test_dry_run_reports_without_opening_and_stays_green():
    github = _github(1)
    code, out, _ = _run(github, {1: REJECTED}, ["--dry-run"])
    assert (code, github.opened) == (0, [])
    assert "Would open an issue" in out


def test_check_reads_the_named_files_instead_of_runs():
    github = _github(1)
    code, out, reads = _run(github, {9: REJECTED}, ["--dry-run", "--check", "v0.9.0=9"])
    assert (code, reads) == (0, [9])
    assert "v0.9.0" in out


def test_check_needs_dry_run():
    try:
        with contextlib.redirect_stderr(io.StringIO()):
            late.main(["--check", "v0.9.0=9"])
    except SystemExit as exc:
        assert exc.code == 2
    else:
        raise AssertionError("expected a usage error")


if __name__ == "__main__":
    failures = 0
    tests = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_")]
    for name, func in tests:
        try:
            func()
            print(f"PASS {name}")
        except AssertionError as exc:
            failures += 1
            print(f"FAIL {name}: {exc}")
    print(f"\n{len(tests) - failures}/{len(tests)} passed")
    raise SystemExit(1 if failures else 0)
