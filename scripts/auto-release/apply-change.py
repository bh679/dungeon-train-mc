#!/usr/bin/env python3
"""Apply one auto-release cascade tick.

Priority order each tick (modulated by AUTO_RELEASE_MODE):
  1. AIN catch-up bump: if `adventureitemnames_version` in gradle.properties is
     behind the latest GitHub release, bump it by ONE version step. The workflow
     verifies the build before committing; on build failure the workflow reverts
     and re-runs this script with SKIP_AIN=1.
  2. Queue item: pop pending[0] from queue.json and apply by type (new_file or
     add_variant).
  3. Mode fallback:
       - always:       nudge auto_balancing.json weight (existing behaviour).
       - with-content: mark cascade stopped (no commit, no release).
       - ain:          mark cascade stopped (no commit, no release).

"Stopped" sets state.cascade_stopped=true. should-fire.py refuses to fire while
the flag is set; auto-release-reset.yml clears it on the next real release.

Writes step outputs to stdout AND $GITHUB_OUTPUT:
  commit_message, label, change_kind, applied_id
AIN bumps additionally emit: ain_from, ain_to.

Env overrides (for testing):
  STATE_FILE              default: .github/auto-release/state.json
  QUEUE_FILE              default: .github/auto-release/queue.json
  LOOT_FILE               default: src/main/resources/data/dungeontrain/loot_table/chests/auto_balancing.json
  GRADLE_PROPERTIES_FILE  default: gradle.properties
  NOW_EPOCH               default: current UTC epoch
  AUTO_RELEASE_MODE       default: always (case-insensitive: always|with-content|ain)
  SKIP_AIN                default: unset; "1" disables AIN check (used by the
                          workflow fallback after a failed gradle build)
  AIN_RELEASES_OVERRIDE   default: unset; JSON array of {tagName: "v..."}
                          bypasses the `gh release list` subprocess (tests use
                          this; CI relies on the real subprocess)
"""
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

STATE_FILE = os.environ.get("STATE_FILE", ".github/auto-release/state.json")
QUEUE_FILE = os.environ.get("QUEUE_FILE", ".github/auto-release/queue.json")
LOOT_FILE = os.environ.get(
    "LOOT_FILE",
    "src/main/resources/data/dungeontrain/loot_table/chests/auto_balancing.json",
)
GRADLE_PROPERTIES_FILE = os.environ.get("GRADLE_PROPERTIES_FILE", "gradle.properties")

ALLOWED_TARGET_PREFIX = "src/main/resources/data/dungeontrain/"
WEIGHT_MIN, WEIGHT_MAX = 1, 20

AIN_REPO = "bh679/adventureitemnames-mc"
AIN_VERSION_KEY = "adventureitemnames_version"
MODE_ALWAYS = "always"
MODE_WITH_CONTENT = "with-content"
MODE_AIN = "ain"
VALID_MODES = {MODE_ALWAYS, MODE_WITH_CONTENT, MODE_AIN}


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


def apply_queue_item(item):
    item_type = item.get("type", "new_file")
    if item_type == "new_file":
        apply_new_file(item)
    elif item_type == "add_variant":
        apply_add_variant(item)
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


def parse_mode():
    raw = os.environ.get("AUTO_RELEASE_MODE", "")
    normalized = raw.strip().lower()
    if normalized in VALID_MODES:
        return normalized
    return MODE_ALWAYS


def parse_semver(s):
    """Strict X.Y.Z parse. Returns (int, int, int) or None."""
    if not isinstance(s, str):
        return None
    parts = s.split(".")
    if len(parts) != 3:
        return None
    try:
        return tuple(int(p) for p in parts)
    except ValueError:
        return None


def read_ain_current_version():
    """Read adventureitemnames_version=X.Y.Z from gradle.properties.

    Returns the version string (e.g. "0.25.0") or None if not found / invalid.
    """
    try:
        with open(GRADLE_PROPERTIES_FILE) as f:
            for line in f:
                if line.startswith(AIN_VERSION_KEY + "="):
                    return line[len(AIN_VERSION_KEY) + 1:].strip()
    except FileNotFoundError:
        return None
    return None


def write_ain_version(new_version):
    """Replace the adventureitemnames_version line in-place. Preserves other
    lines byte-for-byte."""
    with open(GRADLE_PROPERTIES_FILE) as f:
        lines = f.readlines()
    found = False
    for i, line in enumerate(lines):
        if line.startswith(AIN_VERSION_KEY + "="):
            # Preserve trailing newline if present.
            suffix = "\n" if line.endswith("\n") else ""
            lines[i] = f"{AIN_VERSION_KEY}={new_version}{suffix}"
            found = True
            break
    if not found:
        fail(f"{GRADLE_PROPERTIES_FILE}: {AIN_VERSION_KEY} line not found")
    with open(GRADLE_PROPERTIES_FILE, "w") as f:
        f.writelines(lines)


def fetch_ain_releases():
    """Return a list of version-string tags (e.g. ["0.25.0", "0.24.0", ...]).

    Order is whatever `gh release list` returns; the caller sorts. Failures
    return an empty list with a warning — the cascade falls through gracefully.
    """
    override = os.environ.get("AIN_RELEASES_OVERRIDE")
    if override is not None:
        try:
            data = json.loads(override)
        except json.JSONDecodeError as e:
            print(f"::warning::AIN_RELEASES_OVERRIDE not valid JSON: {e}", file=sys.stderr)
            return []
    else:
        try:
            result = subprocess.run(
                ["gh", "release", "list", "--repo", AIN_REPO,
                 "--json", "tagName", "-L", "200"],
                capture_output=True, text=True, timeout=30, check=True,
            )
        except (subprocess.SubprocessError, FileNotFoundError) as e:
            print(f"::warning::gh release list failed: {e}", file=sys.stderr)
            return []
        try:
            data = json.loads(result.stdout)
        except json.JSONDecodeError as e:
            print(f"::warning::gh release list returned invalid JSON: {e}", file=sys.stderr)
            return []

    versions = []
    for entry in data:
        tag = entry.get("tagName", "")
        if tag.startswith("v"):
            tag = tag[1:]
        if parse_semver(tag) is not None:
            versions.append(tag)
    return versions


def try_ain_bump():
    """Bump adventureitemnames_version by ONE version step.

    Returns a dict of outputs (commit_message, label, change_kind, applied_id,
    ain_from, ain_to) on success, or None if no newer version is available.
    """
    current = read_ain_current_version()
    if current is None:
        print(f"::warning::could not read {AIN_VERSION_KEY} from {GRADLE_PROPERTIES_FILE}", file=sys.stderr)
        return None
    current_tuple = parse_semver(current)
    if current_tuple is None:
        print(f"::warning::current AIN version {current!r} not strict X.Y.Z", file=sys.stderr)
        return None

    available = fetch_ain_releases()
    if not available:
        return None

    greater = sorted(
        (v for v in available if parse_semver(v) > current_tuple),
        key=parse_semver,
    )
    if not greater:
        return None

    new_version = greater[0]
    write_ain_version(new_version)
    return {
        "commit_message": f"chore(auto): bump AIN {current} -> {new_version}",
        "label": f"AIN bump: {current} -> {new_version}",
        "change_kind": "ain_bump",
        "applied_id": f"ain-{new_version}",
        "ain_from": current,
        "ain_to": new_version,
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
    skip_ain = os.environ.get("SKIP_AIN") == "1"

    with open(STATE_FILE) as f:
        state = json.load(f)
    with open(QUEUE_FILE) as f:
        queue = json.load(f)

    # 1. AIN catch-up (unless explicitly skipped after a failed build).
    if not skip_ain:
        ain_result = try_ain_bump()
        if ain_result is not None:
            state["last_auto_release_at"] = now_iso
            write_json(STATE_FILE, state)
            write_output(**ain_result)
            return 0

    # 2. Mode `ain` stops here when no bump is available.
    if mode == MODE_AIN:
        stopped = mark_cascade_stopped(state, "no AIN update available (mode=ain)")
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
        stopped = mark_cascade_stopped(state, "queue empty and AIN up to date (mode=with-content)")
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
