#!/usr/bin/env python3
"""Hold a batch of changelog entries back from releases, then launch them all at once.

A soft launch merges a feature to main switched off. Its changelog entries must not go out with
the releases in between, and main should not take its version bumps yet. So the entries wait in
``.github/release-notes/held/<batch>/``, a folder nothing else reads: ``changelog_io`` only loads
``changelog.json`` and ``unreleased/``, so the release notes, the release marking step and the
website never see a held entry.

``held/<batch>/batch.json`` records how many MINOR bumps the batch is owed (one per PR it
carried). Launch day moves the entries into ``unreleased/`` (restamped to the version main will
reach) and deletes ``batch.json``; ``version-bump.yml`` sees that deletion and bumps main that
many times instead of once. The next real release then ships every held entry together.

Usage:
  held.py hold <batch> --bumps N --pr 2299 --pr 2300 ... --id <entry-id> ...
  held.py launch <batch>
  held.py status
"""
from __future__ import annotations

import argparse
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import changelog_io  # noqa: E402

HELD_DIRNAME = "held"
BATCH_FILE = "batch.json"


def held_root(ledger: str | None = None) -> str:
    """``held/`` beside the ledger file."""
    target = ledger or changelog_io.CHANGELOG_FILE
    return os.path.join(os.path.dirname(os.path.abspath(target)), HELD_DIRNAME)


def batch_dir(batch: str, ledger: str | None = None) -> str:
    if not changelog_io.ENTRY_ID_RE.match(batch):
        raise ValueError(f"batch name {batch!r} is not a valid slug")
    return os.path.join(held_root(ledger), batch)


def launch_version(current: str, bumps: int) -> str:
    """The version main reaches once version-bump.yml has bumped ``bumps`` times from ``current``."""
    parsed = changelog_io.parse_semver(current)
    if parsed is None:
        raise ValueError(f"mod_version '{current}' is not a strict X.Y.Z version")
    if bumps < 1:
        raise ValueError("a batch is owed at least one bump")
    major, minor, _patch = parsed
    return f"{major}.{minor + bumps}.0"


def hold(batch: str, ids: list[str], bumps: int, prs: list[int], ledger: str | None = None) -> list[str]:
    """Move the unreleased entries ``ids`` out of the ledger and ``unreleased/`` into the batch."""
    if not ids:
        raise ValueError("name at least one entry to hold")
    data = changelog_io.load_changelog(ledger)
    by_id = {e["id"]: e for e in data["entries"]}
    missing = [i for i in ids if i not in by_id]
    if missing:
        raise ValueError(f"no such entry: {', '.join(missing)}")
    released = [i for i in ids if by_id[i].get("released")]
    if released:
        raise ValueError(f"already released, cannot hold: {', '.join(released)}")
    out_dir = batch_dir(batch, ledger)
    os.makedirs(out_dir, exist_ok=True)
    for entry_id in ids:
        changelog_io.write_json(os.path.join(out_dir, f"{entry_id}.json"), by_id[entry_id])
    keep = [e for e in data["entries"] if e["id"] not in set(ids)]
    changelog_io.save_changelog({**data, "entries": keep}, ledger)
    meta_path = os.path.join(out_dir, BATCH_FILE)
    meta = changelog_io.read_json(meta_path) if os.path.exists(meta_path) else {}
    held_ids = sorted(set(meta.get("entries", [])) | set(ids))
    changelog_io.write_json(meta_path, {
        "batch": batch,
        "bumps": bumps,
        "prs": sorted(set(meta.get("prs", [])) | set(prs)),
        "entries": held_ids,
    })
    return held_ids


def load_batch(batch: str, ledger: str | None = None) -> tuple[dict, list[dict]]:
    """The batch's ``batch.json`` and its entries."""
    path = batch_dir(batch, ledger)
    meta = changelog_io.read_json(os.path.join(path, BATCH_FILE))
    entries = [changelog_io.read_json(os.path.join(path, f"{i}.json")) for i in meta["entries"]]
    return meta, entries


def launch(batch: str, current_version: str, ledger: str | None = None) -> tuple[str, list[str]]:
    """Move the batch into ``unreleased/``, restamped to its launch version, and delete the batch."""
    meta, entries = load_batch(batch, ledger)
    version = launch_version(current_version, int(meta["bumps"]))
    data = changelog_io.load_changelog(ledger)
    clash = sorted({e["id"] for e in data["entries"]} & {e["id"] for e in entries})
    if clash:
        raise ValueError(f"already in the ledger: {', '.join(clash)}")
    for entry in entries:
        changelog_io.write_unreleased_entry({**entry, "version": version}, ledger)
    shutil.rmtree(batch_dir(batch, ledger))
    root = held_root(ledger)
    if os.path.isdir(root) and not os.listdir(root):
        os.rmdir(root)
    return version, [e["id"] for e in entries]


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    h = sub.add_parser("hold", help="move unreleased entries into a held batch")
    h.add_argument("batch")
    h.add_argument("--id", dest="ids", action="append", default=[], help="entry id (repeat)")
    h.add_argument("--bumps", type=int, required=True, help="MINOR bumps the batch is owed")
    h.add_argument("--pr", dest="prs", action="append", type=int, default=[], help="carried PR (repeat)")
    la = sub.add_parser("launch", help="release a held batch into unreleased/")
    la.add_argument("batch")
    sub.add_parser("status", help="list held batches")
    args = p.parse_args(argv)
    if args.cmd == "hold":
        held_ids = hold(args.batch, args.ids, args.bumps, args.prs)
        print(f"held/{args.batch}: {len(held_ids)} entr(y/ies), owed {args.bumps} bump(s)")
    elif args.cmd == "launch":
        current = changelog_io.read_mod_version()
        if current is None:
            print("::error::could not read mod_version from gradle.properties", file=sys.stderr)
            return 1
        version, ids = launch(args.batch, current)
        print(f"Launched held/{args.batch}: {len(ids)} entr(y/ies) into unreleased/ as v{version}.")
        print("Also flip PerksGate.LAUNCHED (or the batch's own switch) in the same PR.")
    else:
        root = held_root()
        names = sorted(os.listdir(root)) if os.path.isdir(root) else []
        if not names:
            print("No held batches.")
        for name in names:
            meta, _ = load_batch(name)
            print(f"{name}: {len(meta['entries'])} entries, owed {meta['bumps']} bump(s), PRs {meta['prs']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
