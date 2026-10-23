#!/usr/bin/env python3
"""Mark the changelog entries a release shipped as released (CI step in release.yml).

Run by release.yml after a successful real release (auto == false). It flips
the shipped `released: false` entries to released, stamping the release tag and
a UTC timestamp, so the next release's unreleased set starts clean. The workflow
then commits the changed ledger to main with a [skip ci] message.

Which entries a release shipped: `--release-ledger` names the changelog.json of
the commit the release was built from; the entries unreleased THERE are the
release's entries, and only those are marked in the current ledger. That lets
the workflow re-run this against a newer main (after a PR merged mid-release
and the push was rejected) without also marking entries merged after the cut.
Versions can't draw that line: every unreleased entry carries the same "next
release" version, so one merged a minute after the cut looks like it shipped.
Without `--release-ledger` every unreleased entry is marked (manual use).

Re-running for the same release is a no-op: already-released entries keep their
stamps. A shipped id no longer in the current ledger is warned about, not fatal.

Writes the file only — git commit/push lives in mark-released-and-push.sh.
Emits `changed` and `count` to $GITHUB_OUTPUT so the caller knows whether to commit.

Usage:
  python3 scripts/release-notes/mark-released.py --released-in v0.292.0
  python3 scripts/release-notes/mark-released.py --released-in v0.292.0 \\
      --release-ledger /tmp/release-changelog.json

Path honours the CHANGELOG_FILE env override.
"""
import argparse
import os
import sys

import changelog_io


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Mark a release's changelog entries released.")
    p.add_argument(
        "--released-in",
        required=True,
        dest="released_in",
        help="The release tag, e.g. v0.292.0",
    )
    p.add_argument(
        "--release-ledger",
        dest="release_ledger",
        default=None,
        help="changelog.json of the commit the release was built from; only the "
        "entries unreleased there are marked. Omit to mark every unreleased entry.",
    )
    return p.parse_args(argv)


def shipped_ids(release_ledger: str) -> set[str]:
    """Ids of the entries unreleased in the release commit's ledger.

    Raises FileNotFoundError / ValueError on a missing or malformed file — a
    release ledger that can't be read must stop the step, not mark everything.
    """
    if not os.path.isfile(release_ledger):
        # load_changelog treats a missing file as empty; here that would mark nothing silently.
        raise FileNotFoundError("no such file")
    data = changelog_io.load_changelog(release_ledger)
    return {e["id"] for e in changelog_io.unreleased_entries(data["entries"]) if "id" in e}


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    ids: set[str] | None = None
    if args.release_ledger is not None:
        try:
            ids = shipped_ids(args.release_ledger)
        except (OSError, ValueError) as e:
            print(f"::error::cannot read release ledger {args.release_ledger}: {e}", file=sys.stderr)
            return 1

    data = changelog_io.load_changelog()
    if ids is not None:
        present = {e.get("id") for e in data["entries"]}
        for missing in sorted(ids - present):
            print(f"::warning::shipped changelog entry '{missing}' is no longer in the ledger", file=sys.stderr)

    new_entries, count = changelog_io.mark_released(
        data["entries"], ids, args.released_in, changelog_io.utc_now_iso()
    )
    if count == 0:
        print("No unreleased changelog entries to mark.")
        changelog_io.write_github_output(changed="false", count=0)
        return 0

    changelog_io.save_changelog({**data, "entries": new_entries})
    print(f"Marked {count} changelog entr{'y' if count == 1 else 'ies'} released in {args.released_in}.")
    changelog_io.write_github_output(changed="true", count=count)
    return 0


if __name__ == "__main__":
    sys.exit(main())
