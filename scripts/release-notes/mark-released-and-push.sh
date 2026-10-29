#!/usr/bin/env bash
# Mark a release's changelog entries released and push the ledger to main
# (release.yml "Mark changelog entries released").
#
# Usage: mark-released-and-push.sh <release-tag> <release-sha>
#   <release-sha>  the commit the release was built from. The entries unreleased in
#                  ITS changelog.json are the ones this release shipped.
#
# main moves during a release (parallel merges + their version-bump commits), so the
# push can be rejected. A rejected attempt is never rebased: the old commit's
# changelog.json hunk conflicts with any merge that also touched the ledger. Each
# attempt instead starts from a fresh origin/main and re-runs the marking there,
# limited to the release's entries, so later merges stay unreleased.
#
# Leaves the checkout detached on the pushed commit (later steps read the stamps).
# Only tracked files are reset; untracked files (rendered notes) survive.
#
# Env: MARK_ATTEMPTS (default 5), MARK_BACKOFF_BASE seconds (default 5; doubles
#      per attempt), REMOTE (default origin), BRANCH (default main).
set -euo pipefail

# Everything runs inside main(): bash reads a script lazily, and the checkouts below can
# rewrite this very file if main changed it. A function is parsed whole before it runs.
main() {
  RELEASE_TAG="${1:?usage: mark-released-and-push.sh <release-tag> <release-sha>}"
  RELEASE_SHA="${2:?usage: mark-released-and-push.sh <release-tag> <release-sha>}"
  ATTEMPTS="${MARK_ATTEMPTS:-5}"
  BACKOFF="${MARK_BACKOFF_BASE:-5}"
  REMOTE="${REMOTE:-origin}"
  BRANCH="${BRANCH:-main}"
  LEDGER=".github/release-notes/changelog.json"

  SNAPSHOT="$(mktemp "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/release-changelog.XXXXXX")"
  trap 'rm -f "$SNAPSHOT"' EXIT
  git show "$RELEASE_SHA:$LEDGER" > "$SNAPSHOT"

  for attempt in $(seq 1 "$ATTEMPTS"); do
    if [ "$attempt" -gt 1 ]; then
      wait_s=$(( BACKOFF * (1 << (attempt - 2)) ))
      echo "Retrying from a fresh $REMOTE/$BRANCH in ${wait_s}s (attempt $attempt/$ATTEMPTS)."
      sleep "$wait_s"
    fi
    if ! git fetch --quiet "$REMOTE" "$BRANCH"; then
      echo "Fetch of $REMOTE/$BRANCH failed."
      continue
    fi
    git checkout --quiet -f --detach FETCH_HEAD
    python3 scripts/release-notes/mark-released.py --released-in "$RELEASE_TAG" --release-ledger "$SNAPSHOT"
    if git diff --quiet -- "$LEDGER"; then
      echo "No changelog entries to mark on $REMOTE/$BRANCH — nothing to commit."
      return 0
    fi
    git add "$LEDGER"
    git commit --quiet -m "chore(release-notes): mark $RELEASE_TAG released [skip ci]"
    if git push "$REMOTE" "HEAD:$BRANCH"; then
      echo "Pushed changelog update on attempt $attempt."
      return 0
    fi
    echo "Push rejected ($BRANCH moved during the release)."
  done

  echo "::error::Failed to push changelog update after $ATTEMPTS attempts. Mark by hand: git show $RELEASE_SHA:$LEDGER > /tmp/r.json, then python3 scripts/release-notes/mark-released.py --released-in $RELEASE_TAG --release-ledger /tmp/r.json"
  return 1
}

main "$@"
exit $?
