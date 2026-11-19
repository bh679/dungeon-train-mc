#!/usr/bin/env python3
"""Check that modpack versions we uploaded actually became PUBLIC.

Why this exists
---------------
A CurseForge modpack upload returning HTTP 200 with a file id does NOT mean the file
was published. CurseForge validates the pack asynchronously and can reject it after the
fact — e.g.

    Invalid manifest.json file: 500 - "{"errorCode":500,"errorMessage":"An unhandled
    exception occurred while processing the request."}"

When that happens the workflow is still green, nothing is logged anywhere we look, and
the release is silently missing from the pack. That is how the CurseForge pack came to
sit 13 releases behind (86 versions behind Modrinth) without anyone noticing.

Two modes:

  --verify <version>   Poll until <version> is publicly listed on the given platform.
                       Exit 1 if it never appears. Run straight after an upload so a
                       rejection fails the release loudly instead of silently.

  (default)            Drift report: newest published pack version per platform vs the
                       newest GitHub release. With --fail-if-stale-hours, exit 1 when
                       CurseForge has published nothing recent. Used by the scheduled
                       modpack-reconcile workflow as a backstop.

Data sources (stdlib only, matching the other scripts here):
  * Modrinth   — api.modrinth.com (public, no key).
  * CurseForge — api.curseforge.com when CURSEFORGE_API_KEY is set (official, preferred);
                 otherwise api.cfwidget.com, a keyless public mirror. The upload token in
                 CURSEFORGE_TOKEN is an upload credential and does NOT work for reads.
  * Releases   — api.github.com (GITHUB_TOKEN honoured if present).
"""
import argparse
import os
import sys
import time

# Shared CurseForge read helpers (HTTP + the key-vs-mirror distinction). Same directory,
# so a plain import works when this file is run as a script.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cf_api  # noqa: E402

CF_MODPACK_PROJECT = int(os.environ.get("CURSEFORGE_MODPACK_PROJECT_ID") or 1556213)
MR_MODPACK_PROJECT = os.environ.get("MODRINTH_MODPACK_PROJECT_ID") or "bEFyz3ji"

# Which projects the listing readers below look at. Defaults are the two MODPACKS, which is what
# this script was written for. ``--curseforge-project`` / ``--modrinth-project`` retarget it at any
# project — notably the MOD, so ``reupload-curseforge.yml`` can verify a mod upload actually landed
# with the same "an upload is not a publish" logic the pack already relies on.
CF_PROJECT = CF_MODPACK_PROJECT
MR_PROJECT = MR_MODPACK_PROJECT
GITHUB_REPO = os.environ.get("GITHUB_REPOSITORY") or "bh679/dungeon-train-mc"

USER_AGENT = cf_api.USER_AGENT

# Thin aliases onto cf_api — kept so this module's long-standing names still resolve.
_get_json = cf_api.get_json


def normalize(raw):
    """'Dungeon Train v0.613.0' / 'v0.613.0' / '0.613.0' -> '0.613.0'."""
    text = str(raw).strip()
    if text.lower().startswith("dungeon train"):
        text = text[len("dungeon train"):].strip()
    return text.lstrip("vV").strip()


def version_key(version):
    try:
        return tuple(int(part) for part in version.split("."))
    except ValueError:
        return (0,)


curseforge_is_authoritative = cf_api.is_authoritative


def curseforge_versions():
    """Publicly listed pack versions on CurseForge, newest-first."""
    if cf_api.is_authoritative():
        files = cf_api.list_approved_files(CF_PROJECT)
        pairs = [(normalize(f.get("displayName", "")), f.get("fileDate", "")) for f in files]
    else:
        data = _get_json(f"{cf_api.CFWIDGET_API}/{CF_PROJECT}")
        files = data.get("files", [])
        pairs = [(normalize(f.get("display", "")), f.get("uploaded_at", "")) for f in files]
    pairs = [(v, d) for v, d in pairs if v]
    return sorted(pairs, key=lambda p: version_key(p[0]), reverse=True)


def modrinth_versions():
    data = _get_json(f"https://api.modrinth.com/v2/project/{MR_PROJECT}/version")
    pairs = [(normalize(v.get("version_number", "")), v.get("date_published", "")) for v in data]
    pairs = [(v, d) for v, d in pairs if v]
    return sorted(pairs, key=lambda p: version_key(p[0]), reverse=True)


def github_releases():
    headers = {"Accept": "application/vnd.github+json"}
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = _get_json(f"https://api.github.com/repos/{GITHUB_REPO}/releases?per_page=100",
                     headers=headers)
    pairs = [(normalize(r.get("tag_name", "")), r.get("created_at", "")) for r in data]
    pairs = [(v, d) for v, d in pairs if v]
    return sorted(pairs, key=lambda p: version_key(p[0]), reverse=True)


PLATFORMS = {"curseforge": curseforge_versions, "modrinth": modrinth_versions}


PUBLISHED = "published"
MISSING = "missing"
INCONCLUSIVE = "inconclusive"
REJECTED = "rejected"
IN_REVIEW = "in-review"


def _report_rejected(file_id, detail):
    print(f"::error::CurseForge file {file_id} (project {CF_PROJECT}) has status {detail}, so "
          "it will not publish without action.")
    print(f"::error::See the reason at https://legacy.curseforge.com/project/{CF_PROJECT}/files "
          "then fix it or recover with reupload-curseforge.yml.")


def _report_still_waiting(file_id, timeout_minutes, detail, seen):
    if seen:
        print(f"::warning::CurseForge file {file_id} is still {detail} after {timeout_minutes} "
              "minutes. This is NOT a rejection, so do not re-upload. This run has stopped "
              "watching: re-dispatch this workflow to check it again.")
    else:
        print(f"::warning::Could not read CurseForge file {file_id} for {timeout_minutes} "
              f"minutes (last: {detail}). Its status is UNKNOWN, neither approved nor rejected.")


def verify_file(file_id, timeout_minutes, poll_seconds):
    """Poll one file's own status on the official API until it settles or the timeout passes.

    Returns PUBLISHED, REJECTED, IN_REVIEW (still waiting on CurseForge at the timeout; manual
    review holds last days) or INCONCLUSIVE (the lookup failing or 404ing right up to the
    timeout, which proves nothing: the docs don't say whether a held file is visible).
    """
    deadline = time.monotonic() + timeout_minutes * 60
    attempt = 0
    while True:
        attempt += 1
        try:
            state, detail = cf_api.file_status(CF_PROJECT, file_id)
            seen = True
        except cf_api.AuthError:
            raise
        except RuntimeError as exc:
            state, detail, seen = None, f"lookup failed ({exc})", False
            print(f"::warning::CurseForge file {file_id}: {detail}; will retry")
        if state == cf_api.APPROVED:
            print(f"✓ CurseForge file {file_id} is {detail} (after {attempt} check(s))")
            return PUBLISHED
        if state == cf_api.REJECTED:
            _report_rejected(file_id, detail)
            return REJECTED
        if time.monotonic() >= deadline:
            _report_still_waiting(file_id, timeout_minutes, detail, seen)
            return IN_REVIEW if seen else INCONCLUSIVE
        print(f"  CurseForge file {file_id}: {detail}; re-checking in {poll_seconds}s…")
        time.sleep(poll_seconds)


def verify(platform, version, timeout_minutes, poll_seconds):
    """Poll until `version` is publicly listed (an authoritative source), or look once (a cache).

    Returns PUBLISHED, MISSING, or INCONCLUSIVE. INCONCLUSIVE means we could not see the
    version but had no source able to prove absence — a cache, or a listing that never loaded
    — so the caller must not treat that as a failure.
    """
    want = normalize(version)
    fetch = PLATFORMS[platform]
    deadline = time.monotonic() + timeout_minutes * 60
    authoritative = platform != "curseforge" or curseforge_is_authoritative()
    if not authoritative:
        print("Note: reading the cfwidget mirror (CURSEFORGE_API_KEY unset). It lags behind "
              "curseforge.com, so a negative result here is inconclusive rather than a failure.")
    attempt = 0
    while True:
        attempt += 1
        read_failed = False
        try:
            published = {v for v, _ in fetch()}
        except cf_api.AuthError:
            raise
        except RuntimeError as exc:
            print(f"::warning::{platform}: listing fetch failed ({exc}); will retry")
            published = set()
            read_failed = True
        if want in published:
            print(f"✓ {platform}: {want} is publicly listed (after {attempt} check(s))")
            return PUBLISHED
        # A cache can never turn this into a failure, so polling it only burns the timeout (it
        # cost every release 30 min): answer from the first look.
        if not authoritative:
            print(f"::warning::{platform}: {want} is not visible via the cfwidget mirror yet, "
                  "but the mirror is cached and cannot prove absence — NOT failing on it, and "
                  "not waiting on it either.")
            print("::warning::Confirm by hand at "
                  "https://www.curseforge.com/minecraft/modpacks/dungeon-train/files , and "
                  "set the CURSEFORGE_API_KEY secret to make this check authoritative.")
            return INCONCLUSIVE
        if time.monotonic() >= deadline and read_failed:
            print(f"::warning::{platform}: could not read the listing for {timeout_minutes} "
                  f"minutes, so {want} is UNKNOWN, neither published nor missing.")
            return INCONCLUSIVE
        if time.monotonic() >= deadline:
            print(f"::error::{platform}: {want} was uploaded but is STILL not publicly listed "
                  f"after {timeout_minutes} minutes.")
            print("::error::The upload was accepted but the platform did not publish it — "
                  "most likely rejected during validation. Check the project's file list in "
                  "the author dashboard for a rejection reason.")
            return MISSING
        print(f"  {platform}: {want} not listed yet; re-checking in {poll_seconds}s…")
        time.sleep(poll_seconds)


def drift_report(fail_if_stale_hours):
    releases = github_releases()
    newest_release, newest_release_date = releases[0] if releases else ("?", "")
    print(f"Newest GitHub release: {newest_release} ({newest_release_date})")

    stale = False
    summary = {}
    for name, fetch in PLATFORMS.items():
        try:
            versions = fetch()
        except RuntimeError as exc:
            print(f"::warning::{name}: could not fetch listing: {exc}")
            continue
        if name == "curseforge" and not curseforge_is_authoritative():
            print("\n(CurseForge figures come from the cached cfwidget mirror — set "
                  "CURSEFORGE_API_KEY for live data.)")
        newest, newest_date = versions[0] if versions else ("none", "")
        published = {v for v, _ in versions}
        missing = [v for v, _ in releases if v not in published]
        summary[name] = {"newest": newest, "published": len(versions), "missing": len(missing)}
        print(f"\n{name}: newest={newest} ({newest_date}), {len(versions)} published")
        if missing:
            shown = ", ".join(missing[:15])
            more = f" (+{len(missing) - 15} more)" if len(missing) > 15 else ""
            print(f"  {len(missing)} of the last {len(releases)} releases are NOT published: "
                  f"{shown}{more}")

        if name == "curseforge" and fail_if_stale_hours and newest_date:
            age_h = _age_hours(newest_date)
            if age_h is not None:
                print(f"  newest CurseForge pack file is {age_h:.1f}h old "
                      f"(threshold {fail_if_stale_hours}h)")
                if age_h > fail_if_stale_hours and newest != newest_release:
                    stale = True

    if stale:
        print(f"\n::error::CurseForge modpack has published nothing for over "
              f"{fail_if_stale_hours}h and is behind the newest release "
              f"({summary.get('curseforge', {}).get('newest')} vs {newest_release}). "
              "Uploads may be being rejected — check the CurseForge author dashboard.")
        return 1
    print("\nNo action needed.")
    return 0


def _age_hours(iso_date):
    """Hours since an ISO-8601 timestamp, or None if unparseable."""
    import datetime
    text = str(iso_date).replace("Z", "+00:00")
    try:
        when = datetime.datetime.fromisoformat(text)
    except ValueError:
        return None
    if when.tzinfo is None:
        when = when.replace(tzinfo=datetime.timezone.utc)
    return (datetime.datetime.now(datetime.timezone.utc) - when).total_seconds() / 3600


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--verify", metavar="VERSION",
                    help="Poll until VERSION is publicly listed; exit 1 if it never appears.")
    ap.add_argument("--file-id", type=int,
                    help="With --verify and CURSEFORGE_API_KEY: read this CurseForge file's own "
                         "status instead of searching the listing, which tells a rejection from "
                         "a file still under review. Ignored without the key.")
    ap.add_argument("--platform", choices=sorted(PLATFORMS), default="curseforge",
                    help="Platform to verify (default: curseforge).")
    ap.add_argument("--timeout-minutes", type=int, default=30,
                    help="How long --verify waits for the version to appear (default: 30).")
    ap.add_argument("--poll-seconds", type=int, default=60,
                    help="Delay between --verify polls (default: 60).")
    ap.add_argument("--curseforge-project",
                    help="CurseForge project to read (default: the modpack). Pass the MOD's "
                         "project id to verify a mod upload rather than a pack upload.")
    ap.add_argument("--modrinth-project",
                    help="Modrinth project slug/id to read (default: the modpack).")
    ap.add_argument("--fail-if-stale-hours", type=float, default=0,
                    help="Drift mode: exit 1 if CurseForge has published nothing newer than "
                         "this many hours AND is behind the newest release. 0 = report only.")
    args = ap.parse_args(argv)

    # Retarget the listing readers before anything calls them.
    global CF_PROJECT, MR_PROJECT
    if args.curseforge_project:
        CF_PROJECT = int(args.curseforge_project)
    if args.modrinth_project:
        MR_PROJECT = args.modrinth_project

    if args.verify:
        try:
            if args.file_id and args.platform == "curseforge" and curseforge_is_authoritative():
                outcome = verify_file(args.file_id, args.timeout_minutes, args.poll_seconds)
            else:
                outcome = verify(args.platform, args.verify, args.timeout_minutes,
                                 args.poll_seconds)
        except cf_api.AuthError as exc:
            print(f"::error::CURSEFORGE_API_KEY was refused, so nothing was verified: {exc}")
            return 1
        return 1 if outcome in (MISSING, REJECTED) else 0
    return drift_report(args.fail_if_stale_hours)


if __name__ == "__main__":
    sys.exit(main())
