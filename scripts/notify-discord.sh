#!/usr/bin/env bash
# Send a rich Discord webhook embed announcing a Dungeon Train release.
#
# Required env (release.yml provides these):
#   DISCORD_WEBHOOK_URL  Discord channel webhook URL (secret).
#   RELEASE_TAG          e.g. v0.80.0
#   REPO                 e.g. bh679/dungeon-train-mc
#   PRERELEASE           "true" or "false" — controls embed color + label.
#   MODRINTH_VERSION     mc-publish step output; non-empty if Modrinth upload succeeded.
#   CURSEFORGE_VERSION   mc-publish step output; non-empty if CurseForge upload succeeded.
#   GH_TOKEN             gh CLI auth (already set by GitHub Actions for `${{ github.token }}`).
#
# Idempotence: Discord webhooks always create a new message. Re-firing
# workflow_dispatch against the same tag will produce a duplicate
# announcement. Acceptable for now.

set -euo pipefail

: "${DISCORD_WEBHOOK_URL:?required}"
: "${RELEASE_TAG:?required}"
: "${REPO:?required}"
: "${PRERELEASE:?required}"

# Per-platform success markers based on whether mc-publish recorded an
# uploaded version ID. Empty output → upload didn't happen → ⚠️.
MR_MARK=$([ -n "${MODRINTH_VERSION:-}" ] && echo "✅" || echo "⚠️")
CF_MARK=$([ -n "${CURSEFORGE_VERSION:-}" ] && echo "✅" || echo "⚠️")

# Discord embed color: orange for beta builds, Discord-green for stable.
if [ "$PRERELEASE" = "true" ]; then
  COLOR=16753920    # 0xFF8C00 — orange
  TYPE_LABEL="Beta release"
else
  COLOR=5763719     # 0x57F287 — Discord-native green
  TYPE_LABEL="Release"
fi

# First ~500 chars of the GitHub release notes, used as the embed body.
NOTES=$(gh release view "$RELEASE_TAG" --repo "$REPO" --json body --jq '.body[:500]' 2>/dev/null || echo "")

LOGO_URL="https://raw.githubusercontent.com/$REPO/main/src/main/resources/logo.png"
LANDING_URL="https://github.com/$REPO/wiki/Downloads"
GH_RELEASE_URL="https://github.com/$REPO/releases/tag/$RELEASE_TAG"
MODRINTH_URL="https://modrinth.com/mod/dungeon-train/version/$RELEASE_TAG"
CURSEFORGE_URL="https://www.curseforge.com/minecraft/mc-mods/dungeon-train/files"

PAYLOAD=$(jq -n \
  --arg title "Dungeon Train $RELEASE_TAG" \
  --arg landing "$LANDING_URL" \
  --arg type "$TYPE_LABEL" \
  --arg notes "$NOTES" \
  --argjson color "$COLOR" \
  --arg cf_status "$CF_MARK" \
  --arg mr_status "$MR_MARK" \
  --arg cf_url "$CURSEFORGE_URL" \
  --arg mr_url "$MODRINTH_URL" \
  --arg gh_url "$GH_RELEASE_URL" \
  --arg logo "$LOGO_URL" \
  '{
    username: "Dungeon Train",
    avatar_url: $logo,
    embeds: [{
      title: $title,
      url: $landing,
      description: ("**" + $type + "** — a new build is available.\n\n" + $notes),
      color: $color,
      thumbnail: { url: $logo },
      fields: [
        { name: "CurseForge", value: ($cf_status + " [Download](" + $cf_url + ")"), inline: true },
        { name: "Modrinth",   value: ($mr_status + " [Download](" + $mr_url + ")"), inline: true },
        { name: "GitHub",     value: ("✅ [Download](" + $gh_url + ")"),             inline: true }
      ],
      footer: { text: "Powered by Valkyrien Skies 2" }
    }]
  }')

# Allow caller to dry-run the script (build payload, skip POST) by setting DRY_RUN=1.
if [ "${DRY_RUN:-}" = "1" ]; then
  echo "$PAYLOAD"
  exit 0
fi

curl -fsS -X POST -H "Content-Type: application/json" -d "$PAYLOAD" "$DISCORD_WEBHOOK_URL" >/dev/null
echo "✓ Notified Discord for $RELEASE_TAG"
