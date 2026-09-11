#!/usr/bin/env python3
"""Block until a Dungeon Train MOD file is approved on CurseForge — or fail loudly.

Why this exists
---------------
The modpack manifest references the DT jar by file id. CurseForge validates the pack
against that reference, and rejects the whole pack when the referenced file is not yet
approved:

    Invalid manifest.json file: References file with invalid status: 8715518
    (projectID 1527512).

release-modpack.yml used to guard that with a fixed `sleep 15m` — a guess, not a check.
When approval took longer (or never came, as for every DT file after v0.625.0 in August
2026), the pack was built around an unapproved file, uploaded, and rejected afterwards,
leaving the workflow green and the release silently missing from the pack.

This script replaces the guess with the actual question: is file <id> public yet?

Sources (see cf_api.py):
  * CURSEFORGE_API_KEY set — api.curseforge.com, authoritative. Approved means
    fileStatus == 4 and isAvailable is not false.
  * No key — api.cfwidget.com, a caching public mirror. The file appearing there proves
    approval; it not appearing proves nothing, because the mirror lags.

Exit codes: 0 approved, 1 not approved within the timeout (``--on-timeout fail``, the
default). With ``--on-timeout defer`` a timeout still means "do NOT publish" — it exits 0
and writes ``approved=false`` to $GITHUB_OUTPUT so the workflow can skip the upload and end
green, because the 6-hourly catch-up in modpack-reconcile.yml will publish this release once
the file is approved (scripts/modpack/catch-up.py). A red run on every slow approval taught
everyone to ignore the workflow, which is how the pack fell 41 releases behind.

Either way, we never publish against an unapproved file, including on the inconclusive
mirror path. The asymmetry is deliberate: a deferred publish costs a few hours, while a
false pass costs a silently missing pack version — the exact bug this guards.

Local check against real data (no upload, no key needed):
  python3 scripts/modpack/wait-for-approval.py --file-id 8707838 --timeout-minutes 0
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cf_api  # noqa: E402

DT_PROJECT = int(os.environ.get("CURSEFORGE_PROJECT_ID") or 1527512)

# CurseForge fileStatus enum. Only Approved (4) is a public, referenceable file.
STATUS_APPROVED = 4
STATUS_NAMES = {
    1: "Processing", 2: "ChangesRequired", 3: "UnderReview", 4: "Approved",
    5: "Rejected", 6: "MalwareDetected", 7: "Deleted", 8: "Archived", 9: "Testing",
    10: "Released", 11: "ReadyForReview", 12: "Deprecated", 13: "Baking",
    14: "AwaitingPublishing", 15: "FailedPublishing",
}

APPROVED = "approved"
PENDING = "pending"
UNKNOWN = "unknown"

ON_TIMEOUT_FAIL = "fail"
ON_TIMEOUT_DEFER = "defer"


def status_via_api(project_id, file_id):
    """Authoritative status of one file. Returns (state, human-readable detail)."""
    data = cf_api.get_json(
        f"{cf_api.CF_API}/mods/{project_id}/files/{file_id}",
        headers=cf_api.api_headers(),
    )
    info = data.get("data") or {}
    status = info.get("fileStatus")
    name = STATUS_NAMES.get(status, f"status {status!r}")
    if status != STATUS_APPROVED:
        return PENDING, name
    if info.get("isAvailable") is False:
        return PENDING, f"{name} but not available"
    return APPROVED, name


def status_via_mirror(project_id, file_id):
    """Mirror-based status. Presence proves approval; absence proves nothing."""
    data = cf_api.get_json(f"{cf_api.CFWIDGET_API}/{project_id}")
    for entry in data.get("files", []):
        if entry.get("id") == file_id:
            return APPROVED, f"listed as {entry.get('display') or entry.get('name') or 'a file'}"
    return UNKNOWN, "not in the mirror's file list"


def wait_for_approval(project_id, file_id, timeout_minutes, poll_seconds,
                      on_timeout=ON_TIMEOUT_FAIL):
    """Poll until `file_id` is approved. Returns True when approved, False on timeout."""
    authoritative = cf_api.is_authoritative()
    check = status_via_api if authoritative else status_via_mirror
    if not authoritative:
        print("Note: reading the cfwidget mirror (CURSEFORGE_API_KEY unset). It lags behind "
              "curseforge.com, so it can confirm approval but cannot prove the file was "
              "rejected — a timeout here may just be cache lag.")

    deadline = time.monotonic() + timeout_minutes * 60
    attempt = 0
    detail = "no reading yet"
    while True:
        attempt += 1
        try:
            state, detail = check(project_id, file_id)
        except RuntimeError as exc:
            state, detail = UNKNOWN, f"lookup failed ({exc})"
            print(f"::warning::CurseForge file {file_id}: {detail}; will retry")
        if state == APPROVED:
            print(f"✓ CurseForge file {file_id} is approved — {detail} "
                  f"(after {attempt} check(s))")
            return True
        if time.monotonic() >= deadline:
            _report_timeout(project_id, file_id, timeout_minutes, detail, authoritative,
                            on_timeout)
            return False
        print(f"  file {file_id} not approved yet ({detail}); re-checking in {poll_seconds}s…")
        time.sleep(poll_seconds)


def _report_timeout(project_id, file_id, timeout_minutes, detail, authoritative,
                    on_timeout=ON_TIMEOUT_FAIL):
    # A deferred timeout is expected and recoverable, so it is a warning; a failing one is
    # the end of the line for this run, so it is an error.
    level = "warning" if on_timeout == ON_TIMEOUT_DEFER else "error"
    print(f"::{level}::CurseForge file {file_id} (project {project_id}) is still not approved "
          f"after {timeout_minutes} minutes — last reading: {detail}.")
    print(f"::{level}::Publishing the modpack now would reference an unapproved file and the "
          "pack would be REJECTED (\"References file with invalid status\"). Not publishing.")
    if on_timeout == ON_TIMEOUT_DEFER:
        print(f"::{level}::Deferred: modpack-reconcile.yml runs every 6 hours and will publish "
              "this release's pack version once the file is approved (scripts/modpack/"
              "catch-up.py). Nothing to do by hand.")
    else:
        print(f"::{level}::Check the file's status in the CurseForge author dashboard: "
              f"https://legacy.curseforge.com/project/{project_id}/files — once it is "
              "approved, re-dispatch release-modpack.yml for this tag.")
    if not authoritative:
        print(f"::{level}::This reading came from the cached cfwidget mirror. Set the "
              "CURSEFORGE_API_KEY secret to make the check authoritative and rule out cache lag.")


def write_github_output(approved):
    """Tell the workflow the verdict so later steps can gate on it. No-op outside Actions."""
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as fh:
        fh.write(f"approved={'true' if approved else 'false'}\n")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--file-id", type=int, required=True,
                    help="CurseForge file id of the Dungeon Train jar to wait for.")
    ap.add_argument("--project-id", type=int, default=DT_PROJECT,
                    help=f"CurseForge project id of the mod (default: {DT_PROJECT}).")
    ap.add_argument("--timeout-minutes", type=int, default=60,
                    help="How long to wait for approval before failing (default: 60).")
    ap.add_argument("--poll-seconds", type=int, default=60,
                    help="Delay between polls (default: 60).")
    ap.add_argument("--on-timeout", choices=[ON_TIMEOUT_FAIL, ON_TIMEOUT_DEFER],
                    default=ON_TIMEOUT_FAIL,
                    help="What a timeout means: 'fail' exits 1 (default); 'defer' exits 0 "
                         "and writes approved=false to $GITHUB_OUTPUT, leaving the publish "
                         "to the scheduled catch-up.")
    args = ap.parse_args(argv)

    approved = wait_for_approval(args.project_id, args.file_id,
                                 args.timeout_minutes, args.poll_seconds, args.on_timeout)
    write_github_output(approved)
    if approved or args.on_timeout == ON_TIMEOUT_DEFER:
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
