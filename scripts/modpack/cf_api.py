#!/usr/bin/env python3
"""Shared CurseForge read helpers for the modpack scripts.

Two scripts need to read CurseForge: reconcile.py (did the pack version publish?) and
wait-for-approval.py (did the mod file get approved?). Both face the same two facts, so
the helpers live here rather than being duplicated:

  * CurseForge has no keyless official read API. api.curseforge.com needs
    CURSEFORGE_API_KEY. The upload credential in CURSEFORGE_TOKEN does NOT work for reads.
  * Without that key we fall back to api.cfwidget.com, a public mirror that CACHES. It
    observably lags by many minutes, so it can confirm a thing exists but can never prove
    a thing is absent. Callers must decide what a negative result from it means.

Stdlib only, matching the other scripts here.
"""
import http.client
import json
import os
import time
import urllib.error
import urllib.request

USER_AGENT = "dungeon-train-modpack-reconcile"

CF_API = "https://api.curseforge.com/v1"
CFWIDGET_API = "https://api.cfwidget.com"

# api.curseforge.com's documented limits: at most 50 results per page, index + pageSize <= 10000.
PAGE_SIZE = 50
MAX_LISTED_RESULTS = 10000

# CurseForge's FileStatus enum, from the REST API docs. The author portal's status numbers agree
# (4 approved, 5 rejected, 8 archived, 18 under manual review).
STATUS_APPROVED = 4
STATUS_NAMES = {
    1: "Processing", 2: "ChangesRequired", 3: "UnderReview", 4: "Approved",
    5: "Rejected", 6: "MalwareDetected", 7: "Deleted", 8: "Archived", 9: "Testing",
    10: "Released", 11: "ReadyForReview", 12: "Deprecated", 13: "Baking",
    14: "AwaitingPublishing", 15: "FailedPublishing", 16: "Cooking", 17: "Cooked",
    18: "UnderManualReview", 19: "ScanningForMalware", 20: "ProcessingFile",
    21: "PendingRelease", 22: "ReadyForCooking", 23: "PostProcessing",
}
# Statuses a file does not leave by waiting: someone has to act, usually by re-uploading.
STATUSES_REJECTED = frozenset({2, 5, 6, 7, 15})

APPROVED = "approved"
PENDING = "pending"
REJECTED = "rejected"


class AuthError(RuntimeError):
    """api.curseforge.com refused CURSEFORGE_API_KEY. Waiting will not fix this one."""


def get_json(url, headers=None, retries=3, backoff=2.0):
    """GET + parse JSON with simple retry/backoff."""
    last = None
    for attempt in range(1, retries + 1):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, **(headers or {})})
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            if exc.code in (401, 403) and "x-api-key" in (headers or {}):
                raise AuthError(f"GET {url} refused the API key: HTTP {exc.code}") from exc
            last = exc
            if attempt < retries:
                time.sleep(backoff * attempt)
        # OSError covers URLError, TimeoutError and connection resets; HTTPException covers
        # a body truncated mid-transfer (IncompleteRead), which GitHub's releases listing
        # does under load. All of them are transient and belong inside the retry.
        except (OSError, http.client.HTTPException, json.JSONDecodeError) as exc:
            last = exc
            if attempt < retries:
                time.sleep(backoff * attempt)
    raise RuntimeError(f"GET {url} failed after {retries} attempts: {last}")


def is_authoritative():
    """True when we can read CurseForge's own API rather than the cfwidget mirror.

    Only an authoritative source can prove something is ABSENT. cfwidget caches, and
    observably lags — during the 2026-08-22 live test it still reported 260 files /
    v0.592.0 while curseforge.com already listed 261 / v0.613.0.
    """
    return bool(os.environ.get("CURSEFORGE_API_KEY"))


def api_headers():
    """Headers for an authoritative api.curseforge.com read. Empty when no key is set."""
    key = os.environ.get("CURSEFORGE_API_KEY")
    return {"x-api-key": key, "Accept": "application/json"} if key else {}


def is_approved(info):
    """True when an api.curseforge.com file record is public: Approved and not withdrawn."""
    return info.get("fileStatus") == STATUS_APPROVED and info.get("isAvailable") is not False


def list_approved_files(project_id):
    """Every approved file of a project, read from api.curseforge.com across all its pages.

    The sort order is undocumented, so the first page cannot stand in for the newest files. A
    failed page raises rather than returning a partial list: callers decide what to publish
    from this. The docs do not say whether unapproved files are returned, so they are filtered
    here, and a file under review can never be mistaken for a published one.
    """
    files = []
    index = 0
    while index + PAGE_SIZE <= MAX_LISTED_RESULTS:
        data = get_json(
            f"{CF_API}/mods/{project_id}/files?index={index}&pageSize={PAGE_SIZE}",
            headers=api_headers(),
        )
        page = data.get("data") or []
        files.extend(page)
        index += len(page)
        total = (data.get("pagination") or {}).get("totalCount")
        finished = len(page) < PAGE_SIZE if total is None else index >= total
        if not page or finished:
            break
    return [f for f in files if is_approved(f)]


def file_status(project_id, file_id):
    """Authoritative status of one file. Returns (APPROVED|PENDING|REJECTED, status name)."""
    data = get_json(f"{CF_API}/mods/{project_id}/files/{file_id}", headers=api_headers())
    info = data.get("data") or {}
    status = info.get("fileStatus")
    name = STATUS_NAMES.get(status, f"status {status!r}")
    if status in STATUSES_REJECTED:
        return REJECTED, name
    if status != STATUS_APPROVED:
        return PENDING, name
    if not is_approved(info):
        return PENDING, f"{name} but not available"
    return APPROVED, name
