#!/usr/bin/env python3
"""
MINOR bumps for a squash-merged PR, counted one per merged PR, nested PRs included.

A normal PR is one shipped change: one MINOR, exactly what version-bump.yml has always done.
An **integration PR** (a long-lived branch such as dev/perks that collected other PRs before
reaching main) carries several: every PR merged into its head branch counts once, recursively,
plus one for the branch's own work when it added a changelog entry no child added. Brennan's rule
(2026-10-25): main goes up once per merged PR and every entry survives.

The count comes from facts, never from the squashed gradle.properties:
  * children of P = PRs merged with base == P's head branch whose merge commit is an ancestor of
    P's head SHA (so an old, reused branch name can't pull in PRs it never carried);
  * a PR's changelog ids = ids at its merge commit minus ids at that commit's first parent.

On main the bumps start from main's version just BEFORE the squash (the commit's parent), so the
integration branch's own provisional numbers can't double-count, and main having moved on in the
meantime doesn't matter. Each unit becomes its own commit (+1 MINOR), so version-history's
"one MINOR change = one update" counter stays right, and each commit re-stamps the `version` of
that unit's unreleased changelog entries to the version it really got.

On an integration branch (a PR merged into a non-main base) the same units are bumped on that
branch, from max(branch, main) so its provisional numbers never reuse one main already shipped.

Usage:
  merge_bump.py main   --sha <squash sha> [--dry-run]
  merge_bump.py branch --pr <n> --branch <base ref> [--main-ref origin/main] [--dry-run]
  merge_bump.py plan   --pr <n>          # print the units of an open or merged PR

`main` prints `mode=legacy` (and changes nothing) for a PR with no children, so the workflow's
long-standing single-bump path handles it unchanged.
"""
import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts" / "release-notes"))
import changelog_io  # noqa: E402

CHANGELOG_PATH = ".github/release-notes/changelog.json"
GRADLE_PATH = "gradle.properties"
MAX_DEPTH = 6  # integration branches nest one or two deep; anything past this is a cycle


@dataclass(frozen=True)
class PullRequest:
    number: int
    head_ref: str
    head_sha: str
    merge_sha: str | None
    merged_at: str | None


@dataclass(frozen=True)
class Unit:
    """One shipped change: one MINOR bump."""
    pr: int
    via: tuple[str, ...]          # integration branches it travelled through, innermost first
    entry_ids: tuple[str, ...] = field(default_factory=tuple)


# ---------------------------------------------------------------------------- adapters (I/O)

def _run(args, check=True):
    return subprocess.run(args, cwd=REPO, check=check, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


class GitHub:
    """`gh` CLI reads. Tests swap in a fake with the same three methods."""

    def pr(self, number: int) -> PullRequest:
        raw = json.loads(_run(["gh", "pr", "view", str(number), "--json",
                               "number,headRefName,headRefOid,mergeCommit,mergedAt"]))
        return _pr_from_json(raw)

    def merged_into(self, branch: str) -> list[PullRequest]:
        raw = json.loads(_run(["gh", "pr", "list", "--base", branch, "--state", "merged",
                               "--limit", "500", "--json",
                               "number,headRefName,headRefOid,mergeCommit,mergedAt"]))
        return [_pr_from_json(r) for r in raw]

    def pr_for_commit(self, sha: str) -> int | None:
        repo = os.environ.get("GITHUB_REPOSITORY") or _run(
            ["gh", "repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner"]).strip()
        out = _run(["gh", "api", f"repos/{repo}/commits/{sha}/pulls", "-q", ".[0].number"])
        return int(out) if out.strip().isdigit() else None


def _pr_from_json(raw) -> PullRequest:
    merge = raw.get("mergeCommit") or {}
    return PullRequest(number=raw["number"], head_ref=raw["headRefName"],
                       head_sha=raw["headRefOid"], merge_sha=merge.get("oid"),
                       merged_at=raw.get("mergedAt"))


class Git:
    """Repository reads. Tests swap in a fake with the same methods."""

    def fetch_pr_head(self, number: int) -> None:
        # The head branch is usually deleted after the merge; GitHub keeps refs/pull/N/head.
        _run(["git", "fetch", "-q", "origin", f"+refs/pull/{number}/head:refs/remotes/pr/{number}"],
             check=False)

    def is_ancestor(self, ancestor: str, descendant: str) -> bool:
        return subprocess.run(["git", "merge-base", "--is-ancestor", ancestor, descendant],
                              cwd=REPO, stdout=subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL).returncode == 0

    def changelog_ids(self, rev: str) -> set[str]:
        raw = _run(["git", "show", f"{rev}:{CHANGELOG_PATH}"], check=False)
        return ids_of(raw)

    def mod_version(self, rev: str) -> str:
        raw = _run(["git", "show", f"{rev}:{GRADLE_PATH}"])
        return version_of(raw)


def ids_of(raw_json: str) -> set[str]:
    if not raw_json.strip():
        return set()
    entries = json.loads(raw_json).get("entries", [])
    return {e["id"] for e in entries if isinstance(e, dict) and "id" in e}


def version_of(gradle_text: str) -> str:
    found = re.findall(r"^mod_version=(\S+)\s*$", gradle_text, re.MULTILINE)
    if len(found) != 1 or changelog_io.parse_semver(found[0]) is None:
        raise ValueError(f"expected exactly one strict X.Y.Z mod_version, found {found}")
    return found[0]


# ------------------------------------------------------------------------------ pure logic

def added_ids(git, pr: PullRequest) -> set[str]:
    """Changelog ids a merged PR brought into its base: the squash commit's own diff."""
    if not pr.merge_sha:
        return set()
    return git.changelog_ids(pr.merge_sha) - git.changelog_ids(f"{pr.merge_sha}^")


def children_of(gh, git, pr: PullRequest) -> list[PullRequest]:
    git.fetch_pr_head(pr.number)
    kids = [c for c in gh.merged_into(pr.head_ref)
            if c.merge_sha and c.number != pr.number and git.is_ancestor(c.merge_sha, pr.head_sha)]
    return sorted(kids, key=lambda c: (c.merged_at or "", c.number))


def units_of(gh, git, pr: PullRequest, own_ids: set[str], via=(), depth=0) -> list[Unit]:
    """
    The shipped changes PR `pr` carries, in merge order. `own_ids` = the changelog ids the PR
    added as a whole (its squash diff, or head-vs-base for an open PR).
    """
    if depth > MAX_DEPTH:
        raise RuntimeError(f"PR nesting deeper than {MAX_DEPTH} at #{pr.number}: cycle?")
    kids = children_of(gh, git, pr)
    if not kids:
        return [Unit(pr=pr.number, via=tuple(via), entry_ids=tuple(sorted(own_ids)))]
    inner_via = (pr.head_ref,) + tuple(via)
    units: list[Unit] = []
    claimed: set[str] = set()
    for kid in kids:
        kid_ids = added_ids(git, kid)
        claimed |= kid_ids
        units += units_of(gh, git, kid, kid_ids, inner_via, depth + 1)
    leftover = own_ids - claimed
    if leftover:
        # Work committed straight onto the integration branch, with its own entry.
        units.append(Unit(pr=pr.number, via=tuple(via), entry_ids=tuple(sorted(leftover))))
    return units


def next_versions(base: str, count: int, floor: str | None = None) -> list[str]:
    """`count` consecutive MINORs above max(base, floor), PATCH reset."""
    major, minor, _ = changelog_io.parse_semver(base)
    if floor is not None:
        f_major, f_minor, _ = changelog_io.parse_semver(floor)
        if (f_major, f_minor) > (major, minor):
            major, minor = f_major, f_minor
    return [f"{major}.{minor + i}.0" for i in range(1, count + 1)]


def commit_message(version: str, unit: Unit, target: str) -> str:
    how = "Gate 3 merge" if target == "main" else f"merge into {target}"
    if unit.via:
        how += f", #{unit.pr} via {' > '.join(unit.via)}"
    else:
        how += f", #{unit.pr}"
    return f"chore(version): bump to {version} (MINOR — {how}) [skip ci]"


def restamp(entries: list[dict], ids: set[str], version: str) -> list[dict]:
    """Unreleased entries in `ids` take `version`; everything else is reused untouched."""
    return [{**e, "version": version} if e.get("id") in ids and not e.get("released") else e
            for e in entries]


# ------------------------------------------------------------------------------ apply

def set_mod_version(text: str, version: str) -> str:
    return re.sub(r"^mod_version=.*$", f"mod_version={version}", text, count=1, flags=re.MULTILINE)


def apply(plan: list[tuple[str, Unit]], target: str, restamp_entries: bool) -> None:
    gradle = REPO / GRADLE_PATH
    changelog = REPO / CHANGELOG_PATH
    for version, unit in plan:
        gradle.write_text(set_mod_version(gradle.read_text(), version))
        paths = [GRADLE_PATH]
        if restamp_entries and unit.entry_ids:
            data = changelog_io.load_changelog(str(changelog))
            changelog_io.save_changelog(
                {**data, "entries": restamp(data["entries"], set(unit.entry_ids), version)},
                str(changelog))
            paths.append(CHANGELOG_PATH)
        if target == "main":
            # Same contract as the single-bump path: a stale index degrades, it never blocks.
            if subprocess.run([sys.executable, "scripts/version-history/collect.py"],
                              cwd=REPO).returncode == 0:
                paths.append("docs/version-history/index.json")
            else:
                print("::warning::version-history refresh failed; bump continues", file=sys.stderr)
        _run(["git", "add", *paths])
        _run(["git", "commit", "-q", "-m", commit_message(version, unit, target)])
        print(f"committed {version} for #{unit.pr}")


def print_plan(plan: list[tuple[str, Unit]], target: str) -> None:
    for version, unit in plan:
        print(f"  {commit_message(version, unit, target)}")
        if unit.entry_ids:
            print(f"      entries: {', '.join(unit.entry_ids)}")


# ------------------------------------------------------------------------------ commands

def cmd_main(args, gh, git) -> int:
    number = args.pr or gh.pr_for_commit(args.sha)
    if number is None:
        print("mode=legacy (no PR for this commit)")
        return 0
    pr = gh.pr(number)
    units = units_of(gh, git, pr, git.changelog_ids(args.sha) - git.changelog_ids(f"{args.sha}^"))
    if len(units) == 1 and units[0].pr == pr.number:
        print(f"mode=legacy (#{pr.number} carries no other PRs)")
        return 0
    base = git.mod_version(f"{args.sha}^")
    squashed = changelog_io.parse_semver(git.mod_version(args.sha))
    if squashed[0] > changelog_io.parse_semver(base)[0]:
        print(f"::error::#{pr.number} changes the MAJOR version; bump main by hand", file=sys.stderr)
        return 1
    plan = list(zip(next_versions(base, len(units)), units))
    print(f"mode=integration: #{pr.number} ({pr.head_ref}) carries {len(units)} merged PRs; "
          f"main {base} -> {plan[-1][0]}")
    print_plan(plan, "main")
    if not args.dry_run:
        apply(plan, "main", restamp_entries=True)
    return 0


def cmd_branch(args, gh, git) -> int:
    pr = gh.pr(args.pr)
    units = units_of(gh, git, pr, added_ids(git, pr))
    current = version_of((REPO / GRADLE_PATH).read_text())
    main_version = git.mod_version(args.main_ref)
    plan = list(zip(next_versions(current, len(units), floor=main_version), units))
    print(f"#{pr.number} merged into {args.branch}: {len(units)} MINOR bump(s), "
          f"{current} -> {plan[-1][0]} (main is {main_version})")
    print_plan(plan, args.branch)
    if not args.dry_run:
        apply(plan, args.branch, restamp_entries=False)
    return 0


def cmd_plan(args, gh, git) -> int:
    if args.branch:
        # What a PR of this branch into main would carry, before anyone opens one.
        head = f"origin/{args.branch}"
        pr = PullRequest(number=0, head_ref=args.branch,
                         head_sha=_run(["git", "rev-parse", head]).strip(),
                         merge_sha=None, merged_at=None)
        own = git.changelog_ids(head) - git.changelog_ids(args.base_ref)
        units = units_of(gh, git, pr, own)
        return _print_units(pr, units, args.from_version)
    pr = gh.pr(args.pr)
    own = added_ids(git, pr) if pr.merge_sha else (
        git.changelog_ids(f"refs/remotes/pr/{pr.number}") - git.changelog_ids(args.base_ref))
    return _print_units(pr, units_of(gh, git, pr, own), args.from_version)


def _print_units(pr: PullRequest, units: list[Unit], from_version: str | None) -> int:
    label = f"#{pr.number}" if pr.number else "a PR"
    print(f"{label} ({pr.head_ref}) carries {len(units)} unit(s):")
    for unit in units:
        via = f" via {' > '.join(unit.via)}" if unit.via else ""
        print(f"  #{unit.pr}{via}: {', '.join(unit.entry_ids) or '(no changelog entry)'}")
    if from_version:
        print(f"main at {from_version} would end at {next_versions(from_version, len(units))[-1]}")
    return 0


def main(argv=None, gh=None, git=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_main = sub.add_parser("main")
    p_main.add_argument("--sha", default="HEAD")
    p_main.add_argument("--pr", type=int, help="override the PR lookup (dry runs)")
    p_main.add_argument("--dry-run", action="store_true")
    p_branch = sub.add_parser("branch")
    p_branch.add_argument("--pr", type=int, required=True)
    p_branch.add_argument("--branch", required=True)
    p_branch.add_argument("--main-ref", default="origin/main")
    p_branch.add_argument("--dry-run", action="store_true")
    p_plan = sub.add_parser("plan")
    target = p_plan.add_mutually_exclusive_group(required=True)
    target.add_argument("--pr", type=int)
    target.add_argument("--branch", help="an integration branch with no PR to main yet")
    p_plan.add_argument("--base-ref", default="origin/main", help="open PRs: diff head against")
    p_plan.add_argument("--from-version", help="also show where a main at this version would end")
    args = parser.parse_args(argv)
    gh, git = gh or GitHub(), git or Git()
    return {"main": cmd_main, "branch": cmd_branch, "plan": cmd_plan}[args.cmd](args, gh, git)


if __name__ == "__main__":
    sys.exit(main())
