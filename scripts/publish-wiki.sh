#!/usr/bin/env bash
# Render Downloads.md + Downloads-Archive.md from GitHub Releases data and
# push them to the project's wiki repo. Idempotent: no commit if content is
# unchanged.
#
# Required env:
#   WIKI_TOKEN  Personal access token with Contents: write on the parent repo
#               (covers the .wiki.git repo).
#   GH_TOKEN    Token for `gh api` calls (workflow's github.token is fine).
#   REPO        owner/name (e.g. bh679/dungeon-train-mc).
#   RELEASE_TAG The tag we just released (used in the commit message).
#
# Local test:
#   WIKI_TOKEN=$(gh auth token) GH_TOKEN=$(gh auth token) \
#     REPO=bh679/dungeon-train-mc RELEASE_TAG=v0.70.0 \
#     bash scripts/publish-wiki.sh

set -euo pipefail

: "${WIKI_TOKEN:?must be set}"
: "${GH_TOKEN:?must be set}"
: "${REPO:?must be set}"
: "${RELEASE_TAG:?must be set}"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

# Fetch all releases (newest first by default). Filter out drafts and prereleases.
RELEASES_JSON="$WORKDIR/releases.json"
gh api "repos/$REPO/releases?per_page=100" \
  --jq '[.[] | select(.draft==false and .prerelease==false)]' \
  > "$RELEASES_JSON"

NUM_RELEASES=$(jq 'length' "$RELEASES_JSON")
if [ "$NUM_RELEASES" -lt 1 ]; then
  echo "::warning::No published releases found for $REPO; skipping wiki update."
  exit 0
fi

LATEST_TAG=$(jq -r '.[0].tag_name' "$RELEASES_JSON")
LATEST_NAME=$(jq -r '.[0].name // .[0].tag_name' "$RELEASES_JSON")
LATEST_DATE=$(jq -r '.[0].published_at[0:10]' "$RELEASES_JSON")
LATEST_URL=$(jq -r '.[0].html_url' "$RELEASES_JSON")
LATEST_ASSET_NAME=$(jq -r '.[0].assets[0].name // empty' "$RELEASES_JSON")
LATEST_ASSET_URL=$(jq -r '.[0].assets[0].browser_download_url // empty' "$RELEASES_JSON")
LATEST_ASSET_SIZE=$(jq -r '.[0].assets[0].size // 0' "$RELEASES_JSON")

if [ -z "$LATEST_ASSET_URL" ]; then
  echo "::warning::Latest release $LATEST_TAG has no assets; skipping wiki update."
  exit 0
fi

# Format jar size as KB/MB.
human_size() {
  local bytes="$1"
  if [ "$bytes" -ge 1048576 ]; then
    awk "BEGIN { printf \"%.1f MB\", $bytes / 1048576 }"
  else
    awk "BEGIN { printf \"%d KB\", ($bytes + 512) / 1024 }"
  fi
}
LATEST_SIZE_HUMAN=$(human_size "$LATEST_ASSET_SIZE")

# --- Render Downloads.md ---
DL_FILE="$WORKDIR/Downloads.md"
cat > "$DL_FILE" <<EOF
# Downloads

> **First time installing?** Read [Installation](Installation) first — Dungeon Train needs Forge 1.20.1, Valkyrien Skies, and Kotlin for Forge to work, and they all have to match versions.

## Latest release: $LATEST_TAG

**Released:** $LATEST_DATE

[**Download \`$LATEST_ASSET_NAME\` ($LATEST_SIZE_HUMAN)**]($LATEST_ASSET_URL)

[Release notes on GitHub →]($LATEST_URL)

---

Looking for an older build? See [Downloads-Archive](Downloads-Archive).

## Setup

1. **[Installation](Installation)** — full step-by-step (Minecraft 1.20.1 + Forge 47.4.2 + Valkyrien Skies + Kotlin for Forge + this jar)
2. [Compatibility](Compatibility) — known Valkyrien Skies quirks
3. [Features](Features) — what the mod adds

> This page is auto-updated on every release by \`scripts/publish-wiki.sh\` (run from the \`release.yml\` workflow on every \`v*.*.*\` tag push).
EOF

# --- Render Downloads-Archive.md ---
ARCH_FILE="$WORKDIR/Downloads-Archive.md"
cat > "$ARCH_FILE" <<EOF
# Downloads — All Releases

> Looking for the most recent build? See [Downloads](Downloads).
> First time installing? Read [Installation](Installation).

| Version | Released | Download | Release notes |
|---|---|---|---|
EOF

jq -r '.[] | [.tag_name, (.published_at[0:10]), (.assets[0].name // ""), (.assets[0].browser_download_url // ""), (.assets[0].size // 0), .html_url] | @tsv' "$RELEASES_JSON" \
  | while IFS=$'\t' read -r tag date asset_name asset_url asset_size html_url; do
      if [ -n "$asset_url" ]; then
        size_human=$(human_size "$asset_size")
        echo "| $tag | $date | [\`$asset_name\`]($asset_url) ($size_human) | [Notes]($html_url) |" >> "$ARCH_FILE"
      else
        echo "| $tag | $date | _no jar attached_ | [Notes]($html_url) |" >> "$ARCH_FILE"
      fi
    done

cat >> "$ARCH_FILE" <<EOF

> Pre-v0.70.0 versions exist as git tags but predate the release pipeline — no downloadable jars are attached. v0.70.0 is the first downloadable public release.
>
> This page is auto-updated on every release by \`scripts/publish-wiki.sh\` (run from the \`release.yml\` workflow on every \`v*.*.*\` tag push).
EOF

# --- Push to wiki ---
WIKI_DIR="$WORKDIR/wiki"
git clone --quiet --depth 1 \
  "https://x-access-token:${WIKI_TOKEN}@github.com/${REPO}.wiki.git" \
  "$WIKI_DIR"

cp "$DL_FILE" "$WIKI_DIR/Downloads.md"
cp "$ARCH_FILE" "$WIKI_DIR/Downloads-Archive.md"

cd "$WIKI_DIR"

if git diff --quiet -- Downloads.md Downloads-Archive.md; then
  echo "Wiki Downloads pages already match rendered output for $RELEASE_TAG; nothing to push."
  exit 0
fi

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add Downloads.md Downloads-Archive.md
git commit -m "docs: auto-publish Downloads pages for $RELEASE_TAG"
git push origin HEAD

echo "Wiki Downloads pages updated for $RELEASE_TAG."
