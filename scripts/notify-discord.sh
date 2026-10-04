#!/usr/bin/env bash
# Send a rich Discord webhook embed announcing a Dungeon Train release.
#
# Required env (release.yml provides these):
#   DISCORD_WEBHOOK_URL  Discord channel webhook URL (secret).
#   RELEASE_TAG          e.g. v0.80.0
#   REPO                 e.g. bh679/dungeon-train-mc
#   PRERELEASE           "true" or "false" — controls embed color + label.
#   GH_TOKEN             gh CLI auth (already set by GitHub Actions for `${{ github.token }}`).
#
# Optional:
#   RELEASE_NOTES        Notes text to embed instead of fetching the GitHub release body —
#                        for previewing an announcement (e.g. into the dev channel via the
#                        relay's dev cap) before a tag exists. Truncated to the same 500 chars.
#
# Idempotence: Discord webhooks always create a new message. Re-firing
# workflow_dispatch against the same tag will produce a duplicate
# announcement. Acceptable for now.

set -euo pipefail

: "${DISCORD_WEBHOOK_URL:?required}"
: "${RELEASE_TAG:?required}"
: "${REPO:?required}"
: "${PRERELEASE:?required}"

# Discord embed color: orange for beta builds, Discord-green for stable.
if [ "$PRERELEASE" = "true" ]; then
  COLOR=16753920    # 0xFF8C00 — orange
  TYPE_LABEL="Beta release"
else
  COLOR=5763719     # 0x57F287 — Discord-native green
  TYPE_LABEL="Release"
fi

# First ~500 chars of the GitHub release notes, used as the embed body. Published notes link their
# title heading to the update page and end with "Read more" (scripts/release-notes/link-changelog.py);
# both are stripped first so the ping keeps one destination — its title and Download button.
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if [ -n "${RELEASE_NOTES:-}" ]; then
  FULL_NOTES=$RELEASE_NOTES
else
  FULL_NOTES=$(gh release view "$RELEASE_TAG" --repo "$REPO" --json body --jq '.body' 2>/dev/null || echo "")
fi
NOTES=$(printf '%s\n' "$FULL_NOTES" | python3 "$SCRIPT_DIR/release-notes/link-changelog.py" --strip | head -c 500 || true)

LOGO_URL="https://raw.githubusercontent.com/$REPO/main/src/main/resources/logo.png"
# Every link in the message goes to ONE place, the update page (dp-relay public/dungeontrain/update/):
# the embed title opens this release on it (#vX.Y.Z), the Download button the page itself. The page shows the newest modpack on CurseForge
# and Modrinth, the standalone mod, and what changed. Same URL as the in-game notifier
# (client/version/compare/UpdatePage#BASE_URL). Deliberately no per-platform download fields — one
# place to go, and it is never stale while a pack awaits CurseForge approval.
UPDATE_PAGE_URL="https://brennan.games/dungeontrain/update/"

PAYLOAD=$(jq -n \
  --arg title "Dungeon Train $RELEASE_TAG" \
  --arg type "$TYPE_LABEL" \
  --arg notes "$NOTES" \
  --argjson color "$COLOR" \
  --arg update_url "$UPDATE_PAGE_URL" \
  --arg tag "$RELEASE_TAG" \
  --arg download_label "Download $RELEASE_TAG" \
  --arg logo "$LOGO_URL" \
  '{
    username: "Dungeon Train",
    avatar_url: $logo,
    embeds: [{
      title: $title,
      url: ($update_url + "#" + $tag),
      description: ("**" + $type + "** — a new build is available.\n\n" + $notes),
      color: $color,
      thumbnail: { url: $logo },
      footer: { text: "Powered by Sable" }
    }],
    components: [{
      type: 1,
      components: [{ type: 2, style: 5, label: $download_label, url: $update_url }]
    }]
  }')

# Allow caller to dry-run the script (build payload, skip POST) by setting DRY_RUN=1.
if [ "${DRY_RUN:-}" = "1" ]; then
  echo "$PAYLOAD"
  exit 0
fi

# A plain (non-application) webhook drops message components unless the request opts in with
# with_components=true — without it the Download button silently vanishes. Link buttons need no
# interaction handler, so this is all a webhook needs.
case "$DISCORD_WEBHOOK_URL" in
  *\?*) POST_URL="$DISCORD_WEBHOOK_URL&with_components=true" ;;
  *)    POST_URL="$DISCORD_WEBHOOK_URL?with_components=true" ;;
esac
curl -fsS -X POST -H "Content-Type: application/json" -d "$PAYLOAD" "$POST_URL" >/dev/null
echo "✓ Notified Discord for $RELEASE_TAG"
