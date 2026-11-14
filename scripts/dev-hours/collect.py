#!/usr/bin/env python3
"""
Collect the set of clock hours in which Dungeon Train work was committed, across every
repo the project spans, and write the snapshot the build bakes into the jar.

The unit is a **clock hour, not a commit and not a repo-hour**: an hour in which commits
landed in dungeon-train-mc *and* playermob-mc *and* dp-relay is ONE hour. Everything here
therefore works on a set of hour buckets and unions them — nothing ever adds two counts
together. (Naively summing per-repo counts overstates the total by ~30%.)

Every commit counts, bots included: the auto-release cascade's commits are the tail of
release work that was itself done by hand.

Why a committed snapshot instead of computing this at build time: the sibling repos are
not present when Dungeon Train builds — not in CI, not on another machine. So this script
commits its output, and that one file is the figure every surface reads: build.gradle unions
it with the LIVE git history of the Dungeon Train checkout for the jar, and the website reads
its `totalHours` straight off main. `.github/workflows/dev-hours.yml` re-runs it weekly with
`--clone-dir`, which reads every repo from GitHub instead of a local checkout.

The total never shrinks: hours already worked cannot un-happen, so a lower total means a repo
failed to clone, and the run refuses to write rather than publish a smaller number.

Usage:  python3 scripts/dev-hours/collect.py [--config PATH] [--out PATH] [--clone-dir DIR]
                                             [--dry-run] [--allow-shrink]
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import time
import calendar
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = REPO / "scripts/dev-hours/repos.json"
DEFAULT_OUT = REPO / "docs/dev-hours/hour-index.json"
SCHEMA_VERSION = "1.1"
GITHUB = "https://github.com/"

# `%ad` is the AUTHOR date in the author's own timezone — the wall clock the work actually
# happened on, which is what "hours put in" means. The bucket string is then read back as if
# it were UTC purely to get a stable integer key; no timezone maths is intended or wanted.
HOUR_FORMAT = "%Y-%m-%dT%H"
GIT_LOG = ["git", "log", "--all", "--pretty=%ad", f"--date=format:{HOUR_FORMAT}"]


def hour_key(stamp):
    """``2026-08-26T14`` -> epoch-hour int. Returns None for anything unparseable."""
    try:
        return calendar.timegm(time.strptime(stamp.strip(), HOUR_FORMAT)) // 3600
    except (ValueError, AttributeError):
        return None


def hours_from_lines(lines):
    """Set of epoch-hour ints from ``git log`` output lines. Blank/garbage lines are dropped."""
    keys = (hour_key(line) for line in lines)
    return {k for k in keys if k is not None}


def delta_encode(hours):
    """Sorted hour set -> [first, gap, gap, …]. Keeps the committed index small and diffable."""
    ordered = sorted(hours)
    if not ordered:
        return []
    out = [ordered[0]]
    out.extend(b - a for a, b in zip(ordered, ordered[1:]))
    return out


def delta_decode(encoded):
    """Inverse of :func:`delta_encode`."""
    out = []
    running = 0
    for i, step in enumerate(encoded):
        running = step if i == 0 else running + step
        out.append(running)
    return out


def _git(args, cwd, env=None):
    return subprocess.run(
        args, cwd=cwd, check=True, timeout=600, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    ).stdout


def auth_env(token):
    """Environment that authenticates git to github.com with ``token``, or None without one.

    Passed as GIT_CONFIG_* variables rather than ``-c`` so the token never appears in a process
    listing or a logged command line.
    """
    if not token:
        return None
    basic = base64.b64encode(f"x-access-token:{token}".encode()).decode()
    return {
        **os.environ,
        "GIT_TERMINAL_PROMPT": "0",
        "GIT_CONFIG_COUNT": "1",
        "GIT_CONFIG_KEY_0": f"http.{GITHUB}.extraheader",
        "GIT_CONFIG_VALUE_0": f"AUTHORIZATION: basic {basic}",
    }


def ensure_clone(repo, clone_dir, token=None):
    """Blobless bare clone of ``owner/name`` under ``clone_dir`` (fetched if already there).

    History is all this script reads, so no file contents are downloaded. Raises on failure.
    """
    dest = Path(clone_dir) / repo.replace("/", "__")
    env = auth_env(token) or {**os.environ, "GIT_TERMINAL_PROMPT": "0"}
    if dest.exists():
        _git(["git", "fetch", "-q", "--prune", "origin", "+refs/heads/*:refs/heads/*"], dest, env)
    else:
        dest.parent.mkdir(parents=True, exist_ok=True)
        _git(["git", "clone", "-q", "--bare", "--filter=blob:none", f"{GITHUB}{repo}.git", str(dest)],
             None, env)
    return dest


def is_git_dir(root):
    """A working checkout (``.git``) or a bare repository (``HEAD`` at the top)."""
    return (root / ".git").exists() or (root / "HEAD").is_file()


def resolve_source(entry, clone_dir=None, token=None):
    """Where to read one config entry's history. Returns (path_or_None, error_or_None).

    With ``clone_dir`` an entry's GitHub ``repo`` wins; otherwise its local ``path``.
    """
    repo, path = entry.get("repo"), entry.get("path")
    if clone_dir and repo:
        try:
            return ensure_clone(repo, clone_dir, token), None
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as exc:
            stderr = getattr(exc, "stderr", "") or ""
            return None, f"clone of {repo} failed: {stderr.strip() or exc}"
    if path:
        return Path(os.path.expanduser(path)), None
    return None, "no local path (run with --clone-dir to read it from GitHub)"


def check_not_shrinking(previous_total, new_total):
    """Error text when the new total is below the committed one, else None."""
    if previous_total is not None and new_total < previous_total:
        return (f"total fell from {previous_total} to {new_total} hours — a repo was probably "
                f"skipped; refusing to write (pass --allow-shrink if this is intended)")
    return None


def collect_repo(name, path):
    """Read one repo's hour set. Returns (hours, last_commit_date, error_or_None)."""
    root = Path(os.path.expanduser(path))
    if not is_git_dir(root):
        return set(), None, f"not a git repository: {root}"
    try:
        hours = hours_from_lines(_git(GIT_LOG, root).splitlines())
        last = _git(["git", "log", "--all", "-1", "--pretty=%ad", "--date=short"], root).strip()
        return hours, (last or None), None
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as exc:
        # A repo we can't read is a gap in the number, not a reason to fail the run — this
        # script has to work on a machine that only has Dungeon Train checked out.
        return set(), None, f"git failed: {exc}"


def build_index(config, clone_dir=None, token=None):
    """Union every configured repo's hours into the index document."""
    union = set()
    repos = []
    for entry in config["repos"]:
        name = entry["name"]
        path, error = resolve_source(entry, clone_dir, token)
        hours, last = set(), None
        if path is not None:
            hours, last, error = collect_repo(name, path)
        if error:
            print(f"WARNING: skipping {name} — {error}", file=sys.stderr)
        union |= hours
        repos.append({
            "name": name,
            "repo": entry.get("repo"),
            "hours": len(hours),
            "lastCommit": last,
            "skipped": bool(error),
        })
    return {
        "comment": "GENERATED by scripts/dev-hours/collect.py — do not hand-edit. "
                   "Union of the clock hours in which any Dungeon Train repo saw a commit; "
                   "overlapping hours count once. `hours` is delta-encoded epoch hours.",
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "totalHours": len(union),
        "repos": repos,
        "hours": delta_encode(union),
    }


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--clone-dir", type=Path,
                    help="read each repo's history from GitHub, cloned (blobless) under this dir")
    ap.add_argument("--dry-run", action="store_true", help="report the totals, write nothing")
    ap.add_argument("--allow-shrink", action="store_true",
                    help="write even when the total is lower than the committed one")
    args = ap.parse_args(argv)

    config = json.loads(args.config.read_text(encoding="utf-8"))
    # GH_TOKEN reaches the private dp-relay; the public repos clone without it.
    index = build_index(config, args.clone_dir, os.environ.get("GH_TOKEN") or None)

    naive_sum = sum(r["hours"] for r in index["repos"])
    for r in index["repos"]:
        mark = " (skipped)" if r["skipped"] else ""
        print(f"  {r['name']:<28} {r['hours']:>5} hours   last {r['lastCommit'] or '—'}{mark}")
    print(f"  {'UNION (overlaps count once)':<28} {index['totalHours']:>5} hours"
          f"   [{naive_sum - index['totalHours']} double-counted hours removed]")

    if args.dry_run:
        return 0
    if not args.allow_shrink:
        previous = None
        if args.out.exists():
            previous = json.loads(args.out.read_text(encoding="utf-8")).get("totalHours")
        problem = check_not_shrinking(previous, index["totalHours"])
        if problem:
            print(f"ERROR: {problem}", file=sys.stderr)
            return 1
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(index, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {args.out.relative_to(REPO) if args.out.is_relative_to(REPO) else args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
