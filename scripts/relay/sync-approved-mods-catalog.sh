#!/usr/bin/env bash
# Open (or refresh) the dp-relay PR that carries this release's baked approved-mods whitelist.
#
# The relay keeps a verbatim copy of the jar's assets/dungeontrain/cheat/approved_mods.json at
# web/js/approved-mods-catalog.json plus a projection, web/js/approved-mod-projects.json, written by
# its scripts/sync-mod-projects.js from THIS repo's src/main/whitelist/approved_hashes.json and
# modpack/modpack.config.json. With the relay's enforceHashes switch on, that copy is the id list
# Free Play is judged from, so a stale copy files honest players (2026-11-04: a month-old copy was
# 21 ids short and would have filed 1292 of 1714 reporting players). Until this script the copy was
# refreshed by hand.
#
# Called by release.yml after the GitHub Release exists, for every release (real and cascade) except
# test builds. Idempotent: no difference → nothing pushed, nothing opened; a difference → the bot
# branch is rebuilt from the relay's main and ONE pull request is opened or brought up to date.
#
# Usage:
#   RELAY_REPO_TOKEN=<pat> RELEASE_TAG=v0.1645.0 \
#     scripts/relay/sync-approved-mods-catalog.sh build/libs/dungeontrain-0.1645.0.jar [--dry-run]
#
# Env:
#   RELAY_REPO_TOKEN  fine-grained PAT on bh679/dp-relay: Contents read/write + Pull requests
#                     read/write. Also used as GH_TOKEN for `gh pr`. Not needed with --dry-run.
#   RELEASE_TAG       the DT tag the copy comes from (PR title + commit message).
#   RELAY_REPO        override the relay repo (default bh679/dp-relay).
#   RELAY_CHECKOUT    use an existing relay checkout instead of cloning (tests).
#
# --dry-run: do everything up to the commit, print the diff summary, push nothing, open nothing.
set -euo pipefail

JAR="${1:?usage: sync-approved-mods-catalog.sh <dungeontrain.jar> [--dry-run]}"
DRY_RUN=false
[ "${2:-}" = "--dry-run" ] && DRY_RUN=true
: "${RELEASE_TAG:?must be set}"
RELAY_REPO="${RELAY_REPO:-bh679/dp-relay}"
BOT_BRANCH="auto/approved-mods-catalog"
JAR_ENTRY="assets/dungeontrain/cheat/approved_mods.json"
CATALOG_REL="web/js/approved-mods-catalog.json"
PROJECTS_REL="web/js/approved-mod-projects.json"

DT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HASHES="$DT_ROOT/src/main/whitelist/approved_hashes.json"
MODPACK="$DT_ROOT/modpack/modpack.config.json"
for f in "$JAR" "$HASHES" "$MODPACK"; do
  [ -f "$f" ] || { echo "::error::missing input $f"; exit 1; }
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 1. The baked file, straight out of the jar that shipped.
unzip -p "$JAR" "$JAR_ENTRY" > "$WORK/approved_mods.json"
python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); assert isinstance(d.get("groups"), dict) and d["groups"], "no groups"' "$WORK/approved_mods.json" \
  || { echo "::error::$JAR_ENTRY in $JAR is not a baked whitelist"; exit 1; }

# 2. The relay checkout.
if [ -n "${RELAY_CHECKOUT:-}" ]; then
  RELAY="$RELAY_CHECKOUT"
else
  : "${RELAY_REPO_TOKEN:?must be set (or pass RELAY_CHECKOUT)}"
  RELAY="$WORK/relay"
  git clone --quiet --depth 1 --branch main \
    "https://x-access-token:${RELAY_REPO_TOKEN}@github.com/${RELAY_REPO}.git" "$RELAY"
fi
cd "$RELAY"
for f in "$CATALOG_REL" "$PROJECTS_REL" scripts/sync-mod-projects.js package.json; do
  [ -f "$f" ] || { echo "::error::relay checkout has no $f"; exit 1; }
done

# 3. Refresh both files.
cp "$WORK/approved_mods.json" "$CATALOG_REL"
node scripts/sync-mod-projects.js "$HASHES" --modpack "$MODPACK"

if git diff --quiet -- "$CATALOG_REL" "$PROJECTS_REL"; then
  echo "Relay catalog already matches $RELEASE_TAG — nothing to open."
  exit 0
fi
echo "::group::Relay catalog diff for $RELEASE_TAG"
git --no-pager diff --stat -- "$CATALOG_REL" "$PROJECTS_REL"
python3 -I - "$WORK/approved_mods.json" <<'PY'
import json, subprocess, sys
new = json.load(open(sys.argv[1]))
old = json.loads(subprocess.run(['git', 'show', 'HEAD:web/js/approved-mods-catalog.json'], capture_output=True, text=True, check=True).stdout or '{}')
ids = lambda d: {i for g in (d.get('groups') or {}).values() for i in (g.get('ids') or [])}
added, removed = sorted(ids(new) - ids(old)), sorted(ids(old) - ids(new))
print(f"ids added: {', '.join(added) or 'none'}")
print(f"ids removed: {', '.join(removed) or 'none'}")
PY
echo "::endgroup::"

# 4. One patch bump per relay commit (relay convention; never a git tag). package-lock.json is
#    left alone — the relay does not keep its version field in step with package.json.
NEW_VERSION="$(node -e '
  const fs = require("fs"); const p = JSON.parse(fs.readFileSync("package.json", "utf8"));
  const [a, b, c] = p.version.split(".").map(Number); p.version = `${a}.${b}.${c + 1}`;
  fs.writeFileSync("package.json", JSON.stringify(p, null, 2) + "\n"); process.stdout.write(p.version);')"

TITLE="fix: refresh the baked approved-mods catalog from DT $RELEASE_TAG"
BODY="Automated by dungeon-train-mc release.yml (scripts/relay/sync-approved-mods-catalog.sh).

- \`$CATALOG_REL\`: verbatim \`$JAR_ENTRY\` from the $RELEASE_TAG jar.
- \`$PROJECTS_REL\`: \`node scripts/sync-mod-projects.js\` over that tag's \`approved_hashes.json\` + \`modpack.config.json\`.
- \`package.json\`: $NEW_VERSION.

Merge, then deploy (\`scripts/deploy-relay.sh\`) so the relay judges Free Play from the same list the jar ships. \`catalog-freshness.yml\` stays red until this lands."

git checkout --quiet -B "$BOT_BRANCH"
git add -- "$CATALOG_REL" "$PROJECTS_REL" package.json
git -c user.name="github-actions[bot]" -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
  commit --quiet -m "$TITLE" -m "$BODY"

if $DRY_RUN; then
  echo "[dry-run] would force-push $BOT_BRANCH to $RELAY_REPO and open/update PR: $TITLE"
  git --no-pager show --stat HEAD | head -20
  exit 0
fi

# 5. Push the bot branch (rebuilt from main each time, so a force push is the only correct push)
#    and open the PR if none is open for it; otherwise refresh its title/body.
git push --quiet --force origin "$BOT_BRANCH"
export GH_TOKEN="$RELAY_REPO_TOKEN"
EXISTING="$(gh pr list -R "$RELAY_REPO" --head "$BOT_BRANCH" --state open --json number --jq '.[0].number // empty')"
if [ -n "$EXISTING" ]; then
  gh pr edit "$EXISTING" -R "$RELAY_REPO" --title "$TITLE" --body "$BODY" >/dev/null
  echo "Updated $RELAY_REPO#$EXISTING: $TITLE"
else
  URL="$(gh pr create -R "$RELAY_REPO" --base main --head "$BOT_BRANCH" --title "$TITLE" --body "$BODY")"
  echo "Opened $URL"
fi
