#!/usr/bin/env python3
"""Catch release mod files CurseForge rejected after their verify run stopped watching.

Why this exists
---------------
verify-curseforge-upload.yml watches a release's file for 300 minutes, but manual review holds
last days, so a rejection after that window would go unnoticed. Running that watch for longer
would keep a runner busy for days per release and could queue real releases behind it. This
script runs from the hourly modpack-reconcile.yml instead: one status read per file, no waiting.

Where the file ids come from
----------------------------
CurseForge's file listing only returns approved files, and nothing stores the ids. Each verify
run's title carries its tag and file id, so ``gh run list`` reads them back.

Alert once
----------
New rejections from one pass go in one issue, labelled ``curseforge-rejection``, with a
``cf-file:<id>`` marker per file. A marked file is never alerted or read again, even once the
issue is closed. The run goes red only when it opens an issue or fails to, so never hour after
hour for the same file.

A hold or an unreadable file is never a rejection. Without CURSEFORGE_API_KEY it does nothing.

Exit codes: 1 when an issue was opened or could not be opened; otherwise 0.

Local check against real files (read-only, needs CURSEFORGE_API_KEY):
  python3 scripts/modpack/check-late-rejections.py --dry-run --check v0.1176.0=9078014
"""
import argparse
import datetime
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cf_api  # noqa: E402

DT_PROJECT = int(os.environ.get("CURSEFORGE_PROJECT_ID") or 1527512)

VERIFY_WORKFLOW = "verify-curseforge-upload.yml"
# Must match run-name in verify-curseforge-upload.yml.
RUN_TITLE = re.compile(r"^Verify (v\d+(?:\.\d+)*) file (\d+)$")
LABEL = "curseforge-rejection"
MARKER = re.compile(r"cf-file:(\d+)")
# Longer than the longest recorded block of rejections (21 days).
WINDOW_DAYS = 30
# gh's --limit caps at what we ask for; a month of releases sits well below this.
LIST_LIMIT = 1000
# Each failed read retries for several seconds, so stop rather than sit through an outage.
MAX_UNREADABLE_IN_A_ROW = 3


def gh(*args):
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def parse_run_titles(titles):
    """{file id: tag}, from run titles. Titles of runs with no file id are skipped."""
    files = {}
    for title in titles:
        match = RUN_TITLE.match(title)
        if match:
            files[int(match.group(2))] = match.group(1)
    return files


def recent_files():
    since = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(days=WINDOW_DAYS)
    output = gh("run", "list", "--workflow", VERIFY_WORKFLOW, "--limit", str(LIST_LIMIT),
                "--created", f">={since:%Y-%m-%d}", "--json", "displayTitle",
                "--jq", ".[].displayTitle")
    return parse_run_titles(output.splitlines())


def alerted_files():
    """File ids an issue already covers, open or closed."""
    output = gh("issue", "list", "--label", LABEL, "--state", "all", "--limit", str(LIST_LIMIT),
                "--json", "body", "--jq", ".[].body")
    return {int(file_id) for file_id in MARKER.findall(output)}


def find_rejected(files):
    """Read each file once. Returns [(tag, file id, status)] for those rejected.

    Stops early on a refused key or repeated failures, keeping what it already found.
    """
    rejected = []
    unreadable = 0
    for file_id, tag in files.items():
        try:
            state, detail = cf_api.file_status(DT_PROJECT, file_id)
        except cf_api.AuthError as exc:
            print(f"::warning::CURSEFORGE_API_KEY was refused, so this pass stopped: {exc}")
            break
        except RuntimeError as exc:
            print(f"::warning::CurseForge file {file_id} ({tag}) is unreadable, which is not a "
                  f"rejection: {exc}")
            unreadable += 1
            if unreadable >= MAX_UNREADABLE_IN_A_ROW:
                print(f"::warning::{unreadable} unreadable files in a row, so this pass stopped.")
                break
            continue
        unreadable = 0
        print(f"  {tag} file {file_id}: {detail}")
        if state == cf_api.REJECTED:
            rejected.append((tag, file_id, detail))
    return rejected


def issue_title(rejected):
    if len(rejected) == 1:
        tag, file_id, _ = rejected[0]
        return f"CurseForge rejected {tag} (file {file_id})"
    return f"CurseForge rejected {len(rejected)} files, newest {rejected[0][0]}"


def issue_body(rejected):
    rows = "\n".join(f"| {tag} | {file_id} | {status} | `cf-file:{file_id}` |"
                     for tag, file_id, status in rejected)
    return (
        "CurseForge rejected these release files after their verify run stopped watching.\n\n"
        "| Release | File | Status | Marker |\n|---|---|---|---|\n"
        f"{rows}\n\n"
        f"See the reason at https://legacy.curseforge.com/project/{DT_PROJECT}/files then fix it, "
        "or if it was a CurseForge fault recover with:\n\n"
        "```bash\n"
        f"gh workflow run reupload-curseforge.yml -f tag={rejected[0][0]}\n"
        "```\n\n"
        "The markers stop these files being alerted again, including after this issue is closed."
    )


def open_issue(rejected):
    gh("label", "create", LABEL, "--force", "--color", "D93F0B",
       "--description", "A release file CurseForge rejected")
    return gh("issue", "create", "--title", issue_title(rejected), "--label", LABEL,
              "--body", issue_body(rejected)).strip()


def parse_check(text):
    match = re.fullmatch(r"(v\d+(?:\.\d+)*)=(\d+)", text)
    if not match:
        raise argparse.ArgumentTypeError(f"expected <tag>=<file id>, got {text!r}")
    return int(match.group(2)), match.group(1)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dry-run", action="store_true",
                    help="Report what would be alerted without opening an issue.")
    ap.add_argument("--check", type=parse_check, action="append", metavar="TAG=FILE_ID",
                    help="With --dry-run: read these files instead of the recent verify runs.")
    args = ap.parse_args(argv)
    if args.check and not args.dry_run:
        ap.error("--check needs --dry-run")

    if not cf_api.is_authoritative():
        print("::notice::CURSEFORGE_API_KEY is unset, so late rejections were not checked.")
        return 0

    try:
        files = dict(args.check) if args.check else recent_files()
        alerted = alerted_files()
    except (subprocess.CalledProcessError, OSError) as exc:
        detail = getattr(exc, "stderr", None) or exc
        print(f"::warning::Could not read verify runs or issues, so nothing was judged: {detail}")
        return 0

    unalerted = {file_id: tag for file_id, tag in files.items() if file_id not in alerted}
    print(f"Checking {len(unalerted)} of {len(files)} recent file(s); "
          f"{len(files) - len(unalerted)} already alerted.")
    rejected = find_rejected(unalerted)
    if not rejected:
        print("No new rejections.")
        return 0

    if args.dry_run:
        print(f"Would open an issue: {issue_title(rejected)}\n\n{issue_body(rejected)}")
        return 0

    try:
        url = open_issue(rejected)
    except (subprocess.CalledProcessError, OSError) as exc:
        detail = getattr(exc, "stderr", None) or exc
        print(f"::error::CurseForge rejected {rejected}, but the issue could not be opened: "
              f"{detail}")
        return 1
    print(f"::error::{issue_title(rejected)}: {url}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
