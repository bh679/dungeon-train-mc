#!/usr/bin/env python3
"""Apply one auto-release cascade tick.

If queue.json.pending[] is non-empty: pop pending[0] and apply it based on type:
  - new_file:    write item.content as a brand-new file at item.target.
  - add_variant: append item.variant to letters[<letter.index>].variants[] in
                 item.target. Creates the file/letter if missing. Used to grow
                 a multi-letter story file incrementally across cascade fires.

Otherwise: rotate to one entry in auto_balancing.json and nudge its weight by
+/-1 (clamped to [1, 20], flips direction at the boundary so it never sticks).

Writes step outputs to stdout AND $GITHUB_OUTPUT:
  commit_message, label, change_kind, applied_id

Env overrides (for testing):
  STATE_FILE    default: .github/auto-release/state.json
  QUEUE_FILE    default: .github/auto-release/queue.json
  LOOT_FILE     default: src/main/resources/data/dungeontrain/loot_table/chests/auto_balancing.json
  NOW_EPOCH     default: current UTC epoch
"""
import json
import os
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


def main():
    now_epoch = int(os.environ.get("NOW_EPOCH", time.time()))
    now_iso = iso_now(now_epoch)

    with open(STATE_FILE) as f:
        state = json.load(f)
    with open(QUEUE_FILE) as f:
        queue = json.load(f)

    pending = queue.get("pending", [])
    applied = queue.get("applied", [])

    if pending:
        item = pending[0]
        apply_queue_item(item)
        queue["pending"] = pending[1:]
        queue["applied"] = applied + [{
            "id": item["id"],
            "applied_at": now_iso,
            "target": item["target"],
        }]
        write_json(QUEUE_FILE, queue)
        # Force "chore(auto):" prefix so version-bump.yml's guard recognises this
        # as an auto-release commit and doesn't try to MINOR-bump on top of it.
        commit_message = f"chore(auto): {item['commit_message']}"
        label = item["label"]
        change_kind = "queue"
        applied_id = item["id"]
    else:
        commit_message, label = nudge_auto_balance(state, now_epoch)
        change_kind = "auto_balance"
        applied_id = ""

    state["last_auto_release_at"] = now_iso
    write_json(STATE_FILE, state)

    write_output(
        commit_message=commit_message,
        label=label,
        change_kind=change_kind,
        applied_id=applied_id,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
