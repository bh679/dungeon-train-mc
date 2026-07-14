#!/usr/bin/env bash
# Package + upload the Dungeon Train CurseForge modpack.
#
# Builds the pack zip (manifest.json + overrides/ at the archive root) from a
# pre-rendered manifest, then uploads it to the modpack project via the legacy
# CurseForge upload API using the SAME upload token the mod uses
# (X-Api-Token: $CURSEFORGE_TOKEN). We deliberately use the raw API rather than
# mc-publish here: mc-publish's modpack support is rough, whereas this endpoint
# is deterministic and self-contained — the only resolution it needs is the
# numeric Minecraft game-version id (one jq call).
#
# Args:
#   --manifest <path>    Rendered CurseForge manifest.json (required).
#   --tag <vX.Y.Z>       Release tag — sets displayName, pack version + releaseType (required).
#   --overrides <dir>    Folder copied into the pack as overrides/ (default: modpack/overrides).
#   --changelog <text>   Markdown changelog for the upload (default: a templated one-liner).
#   --config <path>      modpack.config.json — source of curseforge_relations declared on the
#                        upload (default: modpack/modpack.config.json).
#
# Required env:
#   CURSEFORGE_MODPACK_PROJECT_ID  CurseForge modpack project id (e.g. 1556213).
#   CURSEFORGE_TOKEN               Upload token (X-Api-Token). Not needed when DRY_RUN=1.
# Optional env:
#   ZIP_OUTPUT_DIR  Where to write the zip (default: $PWD).
#   DRY_RUN=1       Build + validate the zip and print intended metadata; skip the upload.
#
# Local test (no upload):
#   python3 scripts/modpack/build-manifest.py --dt-file-id 9999999 --version 0.0.0 \
#     --output /tmp/manifest.json
#   DRY_RUN=1 CURSEFORGE_MODPACK_PROJECT_ID=1556213 \
#     scripts/modpack/publish-curseforge.sh --manifest /tmp/manifest.json --tag v0.0.0

set -euo pipefail

API="https://minecraft.curseforge.com/api"
OVERRIDES_DIR="modpack/overrides"
CONFIG="modpack/modpack.config.json"
CHANGELOG=""
MANIFEST=""
TAG=""

while [ $# -gt 0 ]; do
  case "$1" in
    --manifest)  MANIFEST="$2";      shift 2 ;;
    --tag)       TAG="$2";           shift 2 ;;
    --overrides) OVERRIDES_DIR="$2"; shift 2 ;;
    --changelog) CHANGELOG="$2";     shift 2 ;;
    --config)    CONFIG="$2";        shift 2 ;;
    *) echo "::error::unknown argument: $1"; exit 2 ;;
  esac
done

: "${MANIFEST:?--manifest required}"
: "${TAG:?--tag required}"
: "${CURSEFORGE_MODPACK_PROJECT_ID:?must be set}"

[ -f "$MANIFEST" ] || { echo "::error::manifest not found: $MANIFEST"; exit 1; }

VERSION="${TAG#v}"
MAJOR="${VERSION%%.*}"
# Match release.yml: pre-1.0 (MAJOR==0) ships as a beta, 1.0+ as a full release.
if [ "$MAJOR" -gt 0 ] 2>/dev/null; then
  RELEASE_TYPE="release"
else
  RELEASE_TYPE="beta"
fi

MC_VERSION=$(jq -r '.minecraft.version // empty' "$MANIFEST")
[ -n "$MC_VERSION" ] || { echo "::error::manifest is missing .minecraft.version"; exit 1; }

if [ -z "$CHANGELOG" ]; then
  CHANGELOG="Dungeon Train modpack $TAG — bundles the Dungeon Train mod and its required Sable dependency for NeoForge $MC_VERSION."
fi

# CurseForge "Relations" to declare on the uploaded modpack file — mirrors the mod's
# own curseforge-dependencies (sable required; AIN/AIS/PMOB embedded). Sourced from
# modpack.config.json so the list lives in one editable place. Missing/empty → none.
RELATIONS_PROJECTS="[]"
if [ -f "$CONFIG" ]; then
  RELATIONS_PROJECTS=$(jq -c '.curseforge_relations // []' "$CONFIG")
else
  echo "::warning::modpack config not found at $CONFIG — uploading without CurseForge relations."
fi
REL_COUNT=$(printf '%s' "$RELATIONS_PROJECTS" | jq 'length')

# --- Build the pack zip: manifest.json + overrides/ at the archive root ---
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT
cp "$MANIFEST" "$WORKDIR/manifest.json"
if [ -d "$OVERRIDES_DIR" ]; then
  cp -R "$OVERRIDES_DIR" "$WORKDIR/overrides"
else
  mkdir -p "$WORKDIR/overrides"
fi
# The .gitkeep is a repo placeholder; it must not ship inside the pack.
rm -f "$WORKDIR/overrides/.gitkeep"

ZIP_NAME="dungeon-train-${VERSION}.zip"
ZIP_PATH="${ZIP_OUTPUT_DIR:-$PWD}/$ZIP_NAME"
rm -f "$ZIP_PATH"
( cd "$WORKDIR" && zip -r -X -q "$ZIP_PATH" manifest.json overrides )
echo "✓ Built $ZIP_PATH"
unzip -l "$ZIP_PATH"

if [ "${DRY_RUN:-}" = "1" ]; then
  echo "DRY_RUN=1 — skipping CurseForge upload."
  echo "Would upload as: displayName='Dungeon Train $TAG', releaseType=$RELEASE_TYPE, Minecraft=$MC_VERSION, project=$CURSEFORGE_MODPACK_PROJECT_ID"
  echo "Would declare $REL_COUNT CurseForge relation(s): $RELATIONS_PROJECTS"
  exit 0
fi

: "${CURSEFORGE_TOKEN:?must be set for upload}"

# --- Resolve numeric game-version ids (CurseForge wants ids, not strings) ---
VERSIONS_JSON=$(curl -sS -H "X-Api-Token: $CURSEFORGE_TOKEN" "$API/game/versions") \
  || { echo "::error::failed to fetch CurseForge game versions"; exit 1; }
if ! printf '%s' "$VERSIONS_JSON" | jq -e 'type == "array"' >/dev/null 2>&1; then
  echo "::error::unexpected /game/versions response (bad token?): $(printf '%s' "$VERSIONS_JSON" | head -c 300)"
  exit 1
fi

MC_ID=$(printf '%s' "$VERSIONS_JSON" | jq -r --arg v "$MC_VERSION" 'map(select(.name == $v)) | (.[0].id // empty)')
[ -n "$MC_ID" ] || { echo "::error::no CurseForge game-version id for Minecraft $MC_VERSION"; exit 1; }
NEO_ID=$(printf '%s' "$VERSIONS_JSON" | jq -r 'map(select((.name // "" | ascii_downcase) == "neoforge")) | (.[0].id // empty)')

if [ -n "$NEO_ID" ]; then
  GAME_VERSIONS=$(jq -nc --argjson mc "$MC_ID" --argjson neo "$NEO_ID" '[$mc, $neo]')
else
  echo "::warning::NeoForge modloader game-version id not found; tagging Minecraft version only."
  GAME_VERSIONS=$(jq -nc --argjson mc "$MC_ID" '[$mc]')
fi

METADATA=$(jq -nc \
  --arg changelog "$CHANGELOG" \
  --arg displayName "Dungeon Train $TAG" \
  --argjson gameVersions "$GAME_VERSIONS" \
  --arg releaseType "$RELEASE_TYPE" \
  --argjson relProjects "$RELATIONS_PROJECTS" \
  '{changelog: $changelog, changelogType: "markdown", displayName: $displayName, gameVersions: $gameVersions, releaseType: $releaseType}
   + (if ($relProjects | length) > 0 then {relations: {projects: $relProjects}} else {} end)')
echo "Declaring $REL_COUNT CurseForge relation(s): $(printf '%s' "$RELATIONS_PROJECTS" | jq -c 'map(.slug + "(" + .type + ")")')"

# --- Upload ---
# The metadata part MUST be typed application/json (with a UTF-8-safe charset) — curl's -F
# leaves plain (non-@file) fields untyped by default, and CurseForge's upload-file endpoint then
# appears to mis-decode multi-byte UTF-8 (e.g. CJK changelog text) instead of treating it as
# UTF-8 JSON, corrupting the payload into "Invalid JSON" (errorCode 1002) — even though the JSON
# we send is valid (verified with `jq -e .`). ASCII-only changelogs never hit this, since ASCII
# bytes are identical under UTF-8 and a Latin-1-style fallback. mc-publish (used for the mod's own
# CurseForge upload) sets this correctly and has never hit the bug with the same content.
RESPONSE=$(curl -sS -w $'\n%{http_code}' \
  -H "X-Api-Token: $CURSEFORGE_TOKEN" \
  -F "metadata=$METADATA;type=application/json;charset=utf-8" \
  -F "file=@$ZIP_PATH" \
  "$API/projects/$CURSEFORGE_MODPACK_PROJECT_ID/upload-file")
HTTP_CODE=$(printf '%s' "$RESPONSE" | tail -n1)
BODY=$(printf '%s' "$RESPONSE" | sed '$d')

if [ "$HTTP_CODE" != "200" ]; then
  echo "::error::CurseForge modpack upload failed (HTTP $HTTP_CODE): $BODY"
  exit 1
fi

NEW_FILE_ID=$(printf '%s' "$BODY" | jq -r '.id // empty')
echo "✓ Uploaded modpack $ZIP_NAME to project $CURSEFORGE_MODPACK_PROJECT_ID (file id ${NEW_FILE_ID:-unknown}, $RELEASE_TYPE)"
