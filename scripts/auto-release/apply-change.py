#!/usr/bin/env python3
"""Apply one auto-release cascade tick.

Priority order each tick (modulated by AUTO_RELEASE_MODE):
  1. Sibling-mod catch-up bumps, in declared order (AIN, then AIS): for each
     sibling, if its version in gradle.properties is behind the latest GitHub
     release, bump it by ONE version step. First hit wins; the rest of the
     tick is skipped. The workflow verifies the build before committing; on
     build failure the workflow reverts and re-runs this script with the
     failing sibling's SKIP_<NAME>=1 so the tick falls through to a different
     sibling or the queue.
  2. Queue item: pop pending[0] from queue.json and apply by type (new_file,
     add_variant, or add_random_book_variant).
  3. Mode fallback:
       - always:       nudge auto_balancing.json weight (existing behaviour).
       - with-content: mark cascade stopped (no commit, no release).
       - ain:          mark cascade stopped (no commit, no release).
       - ais:          mark cascade stopped (no commit, no release).

Modes `ain` and `ais` are sibling-gated: they ONLY consider their own sibling
mod's bump and ignore the other. Modes `always` and `with-content` consider
all siblings.

"Stopped" sets state.cascade_stopped=true. should-fire.py refuses to fire while
the flag is set; auto-release-reset.yml clears it on the next real release.

Writes step outputs to stdout AND $GITHUB_OUTPUT:
  commit_message, label, change_kind, applied_id
Sibling bumps additionally emit <name_lower>_from / <name_lower>_to
(e.g. ain_from / ain_to, ais_from / ais_to).

Env overrides (for testing):
  STATE_FILE              default: .github/auto-release/state.json
  QUEUE_FILE              default: .github/auto-release/queue.json
  LOOT_FILE               default: src/main/resources/data/dungeontrain/loot_table/chests/auto_balancing.json
  GRADLE_PROPERTIES_FILE  default: gradle.properties
  NOW_EPOCH               default: current UTC epoch
  AUTO_RELEASE_MODE       default: always (case-insensitive: always|with-content|ain|ais)
  SKIP_AIN                default: unset; "1" disables AIN check
  SKIP_AIS                default: unset; "1" disables AIS check
                          (used by the workflow fallback after a failed gradle build)
  AIN_RELEASES_OVERRIDE   default: unset; JSON array of {tagName: "v..."}
  AIS_RELEASES_OVERRIDE   default: unset; same shape as AIN_RELEASES_OVERRIDE
                          bypasses the `gh release list` subprocess (tests use
                          this; CI relies on the real subprocess)
"""
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from siblings import (
    MODE_AIN,
    MODE_AIS,
    MODE_ALWAYS,
    MODE_WITH_CONTENT,
    SIBLING_MODS,
    fetch_sibling_releases,
    parse_mode,
    parse_semver,
    read_sibling_version,
    write_sibling_version,
)

STATE_FILE = os.environ.get("STATE_FILE", ".github/auto-release/state.json")
QUEUE_FILE = os.environ.get("QUEUE_FILE", ".github/auto-release/queue.json")
LOOT_FILE = os.environ.get(
    "LOOT_FILE",
    "src/main/resources/data/dungeontrain/loot_table/chests/auto_balancing.json",
)

ALLOWED_TARGET_PREFIX = "src/main/resources/data/dungeontrain/"
WEIGHT_MIN, WEIGHT_MAX = 1, 20


def parse_iso(s):
    if s is None:
        return None
    if isinstance(s, str) and s.endswith("Z"):
        s = s[:-1] + "+00:00"
    return int(datetime.fromisoformat(s).timestamp())


def iso_now(epoch):
    return datetime.fromtimestamp(epoch, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def write_output(**kv):
    gh_out = os.environ.get("GITHUB_OUTPUT")
    fh = open(gh_out, "a") if gh_out else None
    for k, v in kv.items():
        line = f"{k}={v}"
        print(line)
        if fh:
            fh.write(line + "\n")
    if fh:
        fh.close()


def write_json(path, obj):
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def fail(msg):
    print(f"::error::{msg}", file=sys.stderr)
    sys.exit(1)


def _validate_target(target):
    if not target.startswith(ALLOWED_TARGET_PREFIX):
        fail(f"target {target!r} not under {ALLOWED_TARGET_PREFIX}")
    if not target.endswith(".json"):
        fail(f"target {target!r} must end with .json")


def apply_new_file(item):
    target = item.get("target", "")
    _validate_target(target)
    if Path(target).exists():
        fail(f"target {target!r} already exists - refusing to overwrite")
    Path(target).parent.mkdir(parents=True, exist_ok=True)
    write_json(target, item["content"])


def apply_add_variant(item):
    """Append one variant to a story file. Creates the file/letter if missing.

    Idempotency / safety: when the target file exists, the story_meta and the
    letter label must match the existing data. Mismatch -> hard fail.
    """
    target = item.get("target", "")
    _validate_target(target)

    story_meta = item.get("story_meta") or {}
    letter_meta = item.get("letter") or {}
    variant = item.get("variant")

    for field in ("id", "character", "story"):
        if not story_meta.get(field):
            fail(f"add_variant: story_meta.{field} required")
    if letter_meta.get("index") is None or not letter_meta.get("label"):
        fail("add_variant: letter.index and letter.label required")
    if not isinstance(variant, str) or not variant:
        fail("add_variant: variant must be a non-empty string")

    letter_index = letter_meta["index"]
    letter_label = letter_meta["label"]

    if Path(target).exists():
        with open(target) as f:
            story = json.load(f)
        for key in ("id", "character", "story"):
            if story.get(key) != story_meta[key]:
                fail(f"{target}: existing {key}={story.get(key)!r} != queue {story_meta[key]!r}")
        letters = story.setdefault("letters", [])
    else:
        story = {
            "id": story_meta["id"],
            "character": story_meta["character"],
            "story": story_meta["story"],
            "letters": [],
        }
        letters = story["letters"]
        Path(target).parent.mkdir(parents=True, exist_ok=True)

    letter = next((l for l in letters if l.get("index") == letter_index), None)
    if letter is None:
        letter = {"index": letter_index, "label": letter_label, "variants": []}
        letters.append(letter)
        letters.sort(key=lambda l: l.get("index", 0))
    else:
        if letter.get("label") != letter_label:
            fail(f"letter {letter_index} label mismatch: existing {letter.get('label')!r} != queue {letter_label!r}")

    letter.setdefault("variants", []).append(variant)
    write_json(target, story)


def apply_add_random_book_variant(item):
    """Append one variant to a random_book file. Creates the file if missing.

    Random books use a flat top-level variants[] array (no letters), unlike
    multi-letter story files. When the target file exists, random_book_meta
    fields (id, title, author) must match existing data — mismatch -> hard fail.
    """
    target = item.get("target", "")
    _validate_target(target)

    book_meta = item.get("random_book_meta") or {}
    variant = item.get("variant")

    for field in ("id", "title", "author"):
        if not book_meta.get(field):
            fail(f"add_random_book_variant: random_book_meta.{field} required")
    if not isinstance(variant, str) or not variant:
        fail("add_random_book_variant: variant must be a non-empty string")

    if Path(target).exists():
        with open(target) as f:
            book = json.load(f)
        for key in ("id", "title", "author"):
            if book.get(key) != book_meta[key]:
                fail(f"{target}: existing {key}={book.get(key)!r} != queue {book_meta[key]!r}")
    else:
        book = {
            "id": book_meta["id"],
            "title": book_meta["title"],
            "author": book_meta["author"],
            "generation": book_meta.get("generation", 0),
            "weight": book_meta.get("weight", 1),
            "variants": [],
        }
        Path(target).parent.mkdir(parents=True, exist_ok=True)

    book.setdefault("variants", []).append(variant)
    write_json(target, book)


def apply_queue_item(item):
    item_type = item.get("type", "new_file")
    if item_type == "new_file":
        apply_new_file(item)
    elif item_type == "add_variant":
        apply_add_variant(item)
    elif item_type == "add_random_book_variant":
        apply_add_random_book_variant(item)
    else:
        fail(f"unsupported queue item type: {item_type!r}")


def nudge_auto_balance(state, now_epoch):
    with open(LOOT_FILE) as f:
        loot = json.load(f)
    entries = loot["pools"][0]["entries"]
    anchor = parse_iso(state.get("schedule_anchor")) or now_epoch
    h_since_anchor = max(0, (now_epoch - anchor) // 3600)
    idx = h_since_anchor % len(entries)
    direction = 1 if (h_since_anchor // len(entries)) % 2 == 0 else -1
    entry = entries[idx]
    old_w = entry["weight"]
    new_w = old_w + direction
    if new_w < WEIGHT_MIN or new_w > WEIGHT_MAX:
        new_w = old_w - direction  # flip at the boundary
    new_w = max(WEIGHT_MIN, min(WEIGHT_MAX, new_w))
    entry["weight"] = new_w
    write_json(LOOT_FILE, loot)
    name = entry["name"].split(":")[-1]
    return (
        f"chore(auto): auto-balance {name} weight {old_w}->{new_w}",
        f"Auto-balancing: {name} weight {old_w}->{new_w}",
    )


def try_sibling_bump(name, version_key, repo, override_env):
    """Bump <version_key> by ONE version step toward <repo>'s latest release.

    Returns a dict of outputs (commit_message, label, change_kind, applied_id,
    <name_lower>_from, <name_lower>_to) on success, or None if no newer
    version is available.
    """
    current = read_sibling_version(version_key)
    if current is None:
        print(f"::warning::could not read {version_key} from {GRADLE_PROPERTIES_FILE}", file=sys.stderr)
        return None
    current_tuple = parse_semver(current)
    if current_tuple is None:
        print(f"::warning::current {name} version {current!r} not strict X.Y.Z", file=sys.stderr)
        return None

    available = fetch_sibling_releases(repo, override_env)
    if not available:
        return None

    greater = sorted(
        (v for v in available if parse_semver(v) > current_tuple),
        key=parse_semver,
    )
    if not greater:
        return None

    new_version = greater[0]
    write_sibling_version(version_key, new_version)
    lower = name.lower()
    return {
        "commit_message": f"chore(auto): bump {name} {current} -> {new_version}",
        "label": f"{name} bump: {current} -> {new_version}",
        "change_kind": f"{lower}_bump",
        "applied_id": f"{lower}-{new_version}",
        f"{lower}_from": current,
        f"{lower}_to": new_version,
    }


def mark_cascade_stopped(state, reason):
    state["cascade_stopped"] = True
    write_json(STATE_FILE, state)
    return {
        "commit_message": f"chore(auto): cascade stopped — {reason}",
        "label": f"Cascade stopped: {reason}",
        "change_kind": "stopped",
        "applied_id": "",
    }


def apply_queue(queue, now_iso):
    pending = queue.get("pending", [])
    applied = queue.get("applied", [])
    item = pending[0]
    apply_queue_item(item)
    queue["pending"] = pending[1:]
    queue["applied"] = applied + [{
        "id": item["id"],
        "applied_at": now_iso,
        "target": item["target"],
    }]
    write_json(QUEUE_FILE, queue)
    return {
        # Force "chore(auto):" prefix so version-bump.yml's guard recognises
        # this as an auto-release commit and doesn't try to MINOR-bump on top.
        "commit_message": f"chore(auto): {item['commit_message']}",
        "label": item["label"],
        "change_kind": "queue",
        "applied_id": item["id"],
    }


def main():
    now_epoch = int(os.environ.get("NOW_EPOCH", time.time()))
    now_iso = iso_now(now_epoch)
    mode = parse_mode()

    with open(STATE_FILE) as f:
        state = json.load(f)
    with open(QUEUE_FILE) as f:
        queue = json.load(f)

    # 1. Sibling-mod catch-up — try each sibling in declared order. First hit
    #    wins and the tick returns. Each sibling can be skipped via SKIP_<NAME>
    #    (used by the workflow's revert-after-build-failure path) or by mode
    #    gating: mode=ain limits to AIN only, mode=ais limits to AIS only.
    for name, version_key, repo, override_env, skip_env in SIBLING_MODS:
        if os.environ.get(skip_env) == "1":
            continue
        if mode == MODE_AIN and name != "AIN":
            continue
        if mode == MODE_AIS and name != "AIS":
            continue
        result = try_sibling_bump(name, version_key, repo, override_env)
        if result is not None:
            state["last_auto_release_at"] = now_iso
            write_json(STATE_FILE, state)
            write_output(**result)
            return 0

    # 2. Sibling-gated modes stop here when their sibling has no update.
    if mode == MODE_AIN:
        stopped = mark_cascade_stopped(state, "no AIN update available (mode=ain)")
        write_output(**stopped)
        return 0
    if mode == MODE_AIS:
        stopped = mark_cascade_stopped(state, "no AIS update available (mode=ais)")
        write_output(**stopped)
        return 0

    # 3. Queue item.
    if queue.get("pending"):
        queue_result = apply_queue(queue, now_iso)
        state["last_auto_release_at"] = now_iso
        write_json(STATE_FILE, state)
        write_output(**queue_result)
        return 0

    # 4. Mode `with-content` stops when nothing is queued.
    if mode == MODE_WITH_CONTENT:
        stopped = mark_cascade_stopped(state, "queue empty and all siblings up to date (mode=with-content)")
        write_output(**stopped)
        return 0

    # 5. Mode `always`: existing auto-balance fallback.
    commit_message, label = nudge_auto_balance(state, now_epoch)
    state["last_auto_release_at"] = now_iso
    write_json(STATE_FILE, state)
    write_output(
        commit_message=commit_message,
        label=label,
        change_kind="auto_balance",
        applied_id="",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
