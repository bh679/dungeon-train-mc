#!/usr/bin/env python3
"""Find the newest GitHub release the CurseForge MODPACK is missing — and can publish now.

Why this exists
---------------
release-modpack.yml gates the pack upload on the DT mod file being approved on CurseForge,
because a pack manifest that references an unapproved file is rejected outright
("References file with invalid status"). That gate is right. What was wrong is what happened
when approval took longer than the gate's timeout: the run failed and NOTHING ever retried,
so the release was dropped from the pack for good. Approval routinely takes longer than an
hour, so this was the normal case, not the exception — by September 2026 the pack was 41 of
the last 100 releases behind while every one of those DT files was, by then, approved.

This script is the retry. It runs from the 6-hourly modpack-reconcile.yml and inverts the
question: rather than "wait for approval", it asks "which missing release is ALREADY
approved?" — so it never polls and never waits. A DT file being publicly listed on the mod
project IS the approval (the same signal wait-for-approval.py accepts on its mirror path).

Scope: the NEWEST release only. The pack is brought current; historical gaps stay as gaps.
Players install the latest, and 41 obsolete versions landing at once would bury the pack's
file list and multiply the odds of tripping CurseForge's flaky validation.

Output: when there is something to publish, writes ``tag=v<version>`` and ``dt_file_id=<id>``
to $GITHUB_OUTPUT (and prints them); when there is nothing, writes nothing so the workflow's
dispatch step can gate on ``steps.<id>.outputs.tag``. ``--dry-run`` prints only.

Exit codes: 0 always for a decision (including "nothing to do"); 1 only when a listing could
not be read at all — a decision made on half the data could publish the wrong thing.

Local check against real data (read-only, no key needed):
  python3 scripts/modpack/catch-up.py --dry-run
"""
import argparse
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cf_api  # noqa: E402

# reconcile.py is a script with a hyphen-free name, so a plain import works; load it via
# importlib anyway so this file mirrors how the tests in this directory load siblings.
_spec = importlib.util.spec_from_file_location("reconcile", os.path.join(HERE, "reconcile.py"))
reconcile = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(reconcile)

DT_PROJECT = int(os.environ.get("CURSEFORGE_PROJECT_ID") or 1527512)


def approved_mod_files(project_id=DT_PROJECT):
    """{normalised version: file id} for every DT MOD file CurseForge lists publicly.

    Public listing is the approval proof: CurseForge does not list files that are still
    Processing / UnderReview / Rejected, on either the official API or the cfwidget mirror.
    """
    if cf_api.is_authoritative():
        data = cf_api.get_json(
            f"{cf_api.CF_API}/mods/{project_id}/files?pageSize=10000",
            headers=cf_api.api_headers(),
        )
        entries = [(f.get("displayName", ""), f.get("id")) for f in data.get("data", [])]
    else:
        data = cf_api.get_json(f"{cf_api.CFWIDGET_API}/{project_id}")
        entries = [(f.get("display", ""), f.get("id")) for f in data.get("files", [])]
    files = {}
    for raw, file_id in entries:
        version = reconcile.normalize(raw)
        if version and isinstance(file_id, int):
            files[version] = file_id
    return files


def choose(releases, published, approved):
    """Bring the pack current: publish the NEWEST release, if it is missing and approved.

    `releases` newest-first as (version, date); `published` a set of pack versions;
    `approved` {version: file_id}. Returns (version, file_id) or None.

    Only the newest release is ever a candidate — never an older missing one:

      * If the newest release is already in the pack, the pack is current. Older gaps are
        left alone by design (no backfill: players install the latest, and dozens of
        obsolete versions landing at once would bury the pack's file list).
      * If it is missing but its DT file is not approved yet, wait — publishing an older
        approved release instead would mean two uploads for one catch-up and a pack that
        briefly advertises a stale latest.
    """
    if not releases:
        return None
    newest = releases[0][0]
    if newest in published:
        return None
    file_id = approved.get(newest)
    if file_id is None:
        return None
    return newest, file_id


def write_github_output(version, file_id):
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as fh:
        fh.write(f"tag=v{version}\ndt_file_id={file_id}\n")


def run(dry_run):
    try:
        releases = reconcile.github_releases()
        published = {v for v, _ in reconcile.curseforge_versions()}
        approved = approved_mod_files()
    except RuntimeError as exc:
        print(f"::error::catch-up: could not read a listing ({exc}); not deciding on partial data.")
        return 1

    if not cf_api.is_authoritative():
        print("Note: reading the cfwidget mirror (CURSEFORGE_API_KEY unset). It lags, so a "
              "release may look missing for a while after it publishes — harmless here, because "
              "release-modpack.yml's per-tag concurrency group dedupes a repeat dispatch.")

    missing = [v for v, _ in releases if v not in published]
    newest_release = releases[0][0] if releases else "?"
    newest_pack = max(published, key=reconcile.version_key) if published else "none"
    print(f"Newest release {newest_release}; newest CurseForge pack version {newest_pack}; "
          f"{len(missing)} of the last {len(releases)} releases missing from the pack.")

    pick = choose(releases, published, approved)
    if pick is None:
        if newest_release in published:
            print(f"CurseForge pack is current at {newest_release} — nothing to publish"
                  + (f" ({len(missing)} older gaps left as-is by design)." if missing else "."))
        else:
            print(f"Newest release {newest_release} has no approved DT file on CurseForge yet "
                  "— nothing to publish this pass; will re-check next run.")
        return 0

    version, file_id = pick
    verb = "Would publish" if dry_run else "Publishing"
    print(f"{verb} pack version v{version} from DT file {file_id} "
          "(newest release; its DT mod file is approved).")
    if not dry_run:
        write_github_output(version, file_id)
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dry-run", action="store_true",
                    help="Print the decision without writing $GITHUB_OUTPUT.")
    args = ap.parse_args(argv)
    return run(args.dry_run)


if __name__ == "__main__":
    sys.exit(main())
