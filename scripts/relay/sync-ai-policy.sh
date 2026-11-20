#!/usr/bin/env bash
# Open (or refresh) the dp-relay PR that brings the website's AI Policy in line with the game's.
#
# The in-game AI Policy (en_us.json gui.dungeontrain.ai_policy.* + client/policy/AiPolicyContent.java)
# is the source of truth. dungeon.training/ai-policy/ is generated from it: export-ai-policy.py writes
# public/dungeontrain/ai-policy/policy.json in the relay, and the relay's scripts/build-ai-policy.js
# renders the page. The relay's ai-policy-sync.test.js fails if the page is ever edited by hand.
#
# Called by .github/workflows/sync-ai-policy.yml on every push to main that touches those files.
# Idempotent: no difference → nothing pushed, nothing opened; a difference → the bot branch is
# rebuilt from the relay's main and ONE pull request is opened or brought up to date.
#
# Usage:
#   RELAY_REPO_TOKEN=<pat> scripts/relay/sync-ai-policy.sh [--dry-run]
#
# Env:
#   RELAY_REPO_TOKEN  fine-grained PAT on bh679/dp-relay: Contents + Pull requests read/write (the
#                     same secret sync-approved-mods-catalog.sh uses). Not needed with RELAY_CHECKOUT.
#   SOURCE_SHA        the dungeon-train-mc commit the text comes from (PR body). Default: HEAD.
#   RELAY_REPO        override the relay repo (default bh679/dp-relay).
#   RELAY_CHECKOUT    use an existing relay checkout instead of cloning (local runs, tests).
#
# --dry-run: do everything up to the commit, print the diff, push nothing, open nothing.
set -euo pipefail

DRY_RUN=false
[ "${1:-}" = "--dry-run" ] && DRY_RUN=true
RELAY_REPO="${RELAY_REPO:-bh679/dp-relay}"
BOT_BRANCH="auto/ai-policy"
POLICY_REL="public/dungeontrain/ai-policy/policy.json"
PAGE_REL="public/dungeontrain/ai-policy/index.html"

DT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE_SHA="${SOURCE_SHA:-$(git -C "$DT_ROOT" rev-parse --short HEAD)}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 1. The export, straight from this checkout.
python3 "$DT_ROOT/scripts/relay/export-ai-policy.py" --out "$WORK/policy.json"

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
for f in "$PAGE_REL" scripts/build-ai-policy.js package.json; do
  [ -f "$f" ] || { echo "::error::relay checkout has no $f"; exit 1; }
done

# 3. Refresh the copy and re-render the page.
cp "$WORK/policy.json" "$POLICY_REL"
node scripts/build-ai-policy.js

if git diff --quiet -- "$POLICY_REL" "$PAGE_REL" && git ls-files --error-unmatch "$POLICY_REL" >/dev/null 2>&1; then
  echo "Website AI Policy already matches the game at $SOURCE_SHA — nothing to open."
  exit 0
fi
echo "::group::Website AI Policy diff for $SOURCE_SHA"
git --no-pager diff --stat -- "$POLICY_REL" "$PAGE_REL"
git --no-pager diff --word-diff -- "$PAGE_REL" | grep -E '\[-|\{\+' || true
echo "::endgroup::"

if $DRY_RUN; then
  echo "[dry-run] would commit, force-push $BOT_BRANCH to $RELAY_REPO and open/update its PR"
  exit 0
fi

# 4. One patch bump per relay commit (relay convention; never a git tag).
NEW_VERSION="$(node -e '
  const fs = require("fs"); const p = JSON.parse(fs.readFileSync("package.json", "utf8"));
  const [a, b, c] = p.version.split(".").map(Number); p.version = `${a}.${b}.${c + 1}`;
  fs.writeFileSync("package.json", JSON.stringify(p, null, 2) + "\n"); process.stdout.write(p.version);')"

TITLE="fix: AI Policy page follows the in-game policy (dungeon-train-mc $SOURCE_SHA)"
BODY="Automated by dungeon-train-mc sync-ai-policy.yml (scripts/relay/sync-ai-policy.sh).

- \`$POLICY_REL\`: \`export-ai-policy.py\` at dungeon-train-mc $SOURCE_SHA.
- \`$PAGE_REL\`: re-rendered by \`node scripts/build-ai-policy.js\`.
- \`package.json\`: $NEW_VERSION.

Merge, then \`scripts/deploy-public-pages.sh\` to publish https://dungeon.training/ai-policy/."

git checkout --quiet -B "$BOT_BRANCH"
git add -- "$POLICY_REL" "$PAGE_REL" package.json
git -c user.name="github-actions[bot]" -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
  commit --quiet -m "$TITLE" -m "$BODY"

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
