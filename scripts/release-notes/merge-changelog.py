#!/usr/bin/env python3
"""Three-way merge of changelog.json by entry id: every entry from both sides survives.

changelog.json conflicts are routine (every Gate 3 merge appends at the same end of the file),
and resolving one by taking a side silently drops the other side's entries. This merges by `id`:

  * an id on either side survives, unless one side deleted it since the merge base and the other
    side left it untouched (a deliberate removal);
  * an entry both sides changed: the `released: true` copy wins (mark-released on main stamps
    the shipped version), otherwise ours;
  * order: ours, then entries only theirs has, in their order.

As a git merge driver (`.gitattributes` names it; enable once per clone):
    git config merge.dt-changelog.driver "python3 scripts/release-notes/merge-changelog.py %O %A %B"
By hand, on a file git left conflicted mid-merge:
    python3 scripts/release-notes/merge-changelog.py --resolve
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import changelog_io  # noqa: E402

CHANGELOG_PATH = ".github/release-notes/changelog.json"


def _by_id(entries: list[dict]) -> dict[str, dict]:
    return {e["id"]: e for e in entries if isinstance(e, dict) and "id" in e}


def _pick(base: dict | None, ours: dict, theirs: dict) -> dict:
    if ours == theirs:
        return ours
    if base is not None and ours == base:
        return theirs
    if base is not None and theirs == base:
        return ours
    if theirs.get("released") and not ours.get("released"):
        return theirs
    return ours


def merge_entries(base: list[dict], ours: list[dict], theirs: list[dict]) -> list[dict]:
    b, o, t = _by_id(base), _by_id(ours), _by_id(theirs)
    out: list[dict] = []
    for entry in ours:
        eid = entry.get("id")
        if eid is None:
            out.append(entry)
        elif eid in t:
            out.append(_pick(b.get(eid), entry, t[eid]))
        elif eid in b and entry == b[eid]:
            continue  # theirs deleted it and ours never touched it
        else:
            out.append(entry)
    for entry in theirs:
        eid = entry.get("id")
        if eid is None or eid in o:
            continue
        if eid in b and entry == b[eid]:
            continue  # ours deleted it and theirs never touched it
        out.append(entry)
    return out


def merge_docs(base: dict, ours: dict, theirs: dict) -> dict:
    return {**ours, "entries": merge_entries(base.get("entries", []), ours.get("entries", []),
                                             theirs.get("entries", []))}


def _stage(n: int) -> dict:
    raw = subprocess.run(["git", "show", f":{n}:{CHANGELOG_PATH}"], check=False, text=True,
                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout
    return json.loads(raw) if raw.strip() else {"entries": []}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("files", nargs="*", help="base ours theirs (merge-driver form); "
                                                 "the result is written over ours")
    parser.add_argument("--resolve", action="store_true",
                        help="resolve the conflicted changelog.json from git's index stages")
    args = parser.parse_args(argv)
    if args.resolve:
        merged = merge_docs(_stage(1), _stage(2), _stage(3))
        changelog_io.save_changelog(merged, CHANGELOG_PATH)
        print(f"resolved {CHANGELOG_PATH}: {len(merged['entries'])} entries; "
              f"`git add {CHANGELOG_PATH}` to continue")
        return 0
    if len(args.files) != 3:
        parser.error("pass base ours theirs, or --resolve")
    base, ours, theirs = (changelog_io.load_changelog(p) for p in args.files)
    changelog_io.save_changelog(merge_docs(base, ours, theirs), args.files[1])
    return 0  # never leaves conflict markers: a JSON file with them can't be loaded


if __name__ == "__main__":
    sys.exit(main())
