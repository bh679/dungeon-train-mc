#!/usr/bin/env bash
# Package + upload the Dungeon Train MODRINTH modpack (.mrpack).
#
# The Modrinth sibling of publish-curseforge.sh. A Modrinth modpack is a zip of
# `modrinth.index.json` + `overrides/` at the archive root, with the `.mrpack`
# extension. It is uploaded as a new project version via the Modrinth v2 API using
# the SAME token the mod uses (Authorization: $MODRINTH_TOKEN). We use the raw API
# rather than mc-publish (matching publish-curseforge.sh) so the modpack pipeline is
# deterministic and self-contained.
#
# Args:
#   --index <path>       Rendered modrinth.index.json (required).
#   --tag <vX.Y.Z>       Release tag — sets name, version_number + version_type (required).
#   --overrides <dir>    Folder copied into the pack as overrides/ (default: modpack/overrides).
#   --changelog <text>   Markdown changelog for the version (default: a templated one-liner).
#
# Required env:
#   MODRINTH_MODPACK_PROJECT_ID  Modrinth modpack project id or slug (e.g. bEFyz3ji).
#   MODRINTH_TOKEN               Upload token (Authorization). Not needed when DRY_RUN=1.
# Optional env:
#   ZIP_OUTPUT_DIR  Where to write the .mrpack (default: $PWD).
#   DRY_RUN=1       Build + validate the .mrpack and print intended metadata; skip the upload.
#
# Local test (no upload):
#   python3 scripts/modpack/build-mrpack.py --dt-version <id> --version 0.0.0 \
#     --output /tmp/modrinth.index.json
#   DRY_RUN=1 MODRINTH_MODPACK_PROJECT_ID=bEFyz3ji \
#     scripts/modpack/publish-modrinth.sh --index /tmp/modrinth.index.json --tag v0.0.0

set -euo pipefail

API="https://api.modrinth.com/v2"
OVERRIDES_DIR="modpack/overrides"
CHANGELOG=""
INDEX=""
TAG=""

while [ $# -gt 0 ]; do
  case "$1" in
    --index)     INDEX="$2";         shift 2 ;;
    --tag)       TAG="$2";           shift 2 ;;
    --overrides) OVERRIDES_DIR="$2"; shift 2 ;;
    --changelog) CHANGELOG="$2";     shift 2 ;;
    *) echo "::error::unknown argument: $1"; exit 2 ;;
  esac
done

: "${INDEX:?--index required}"
: "${TAG:?--tag required}"
: "${MODRINTH_MODPACK_PROJECT_ID:?must be set}"

[ -f "$INDEX" ] || { echo "::error::index not found: $INDEX"; exit 1; }

VERSION="${TAG#v}"
MAJOR="${VERSION%%.*}"
# Match release.yml: pre-1.0 (MAJOR==0) ships as a beta, 1.0+ as a full release.
if [ "$MAJOR" -gt 0 ] 2>/dev/null; then
  VERSION_TYPE="release"
else
  VERSION_TYPE="beta"
fi

MC_VERSION=$(jq -r '.dependencies.minecraft // empty' "$INDEX")
[ -n "$MC_VERSION" ] || { echo "::error::index is missing .dependencies.minecraft"; exit 1; }

if [ -z "$CHANGELOG" ]; then
  CHANGELOG="Dungeon Train modpack $TAG — bundles the Dungeon Train mod and its required Sable dependency for NeoForge $MC_VERSION."
fi

# --- Build the .mrpack: modrinth.index.json + overrides/ at the archive root ---
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT
cp "$INDEX" "$WORKDIR/modrinth.index.json"
if [ -d "$OVERRIDES_DIR" ]; then
  cp -R "$OVERRIDES_DIR" "$WORKDIR/overrides"
else
  mkdir -p "$WORKDIR/overrides"
fi
# The .gitkeep is a repo placeholder; it must not ship inside the pack.
rm -f "$WORKDIR/overrides/.gitkeep"

MRPACK_NAME="dungeon-train-${VERSION}.mrpack"
MRPACK_PATH="${ZIP_OUTPUT_DIR:-$PWD}/$MRPACK_NAME"
rm -f "$MRPACK_PATH"
( cd "$WORKDIR" && zip -r -X -q "$MRPACK_PATH" modrinth.index.json overrides )
echo "✓ Built $MRPACK_PATH"
unzip -l "$MRPACK_PATH"

# Modrinth version metadata. The pack files are embedded by URL in the index, so the
# version itself declares no project dependencies. file_parts names the multipart field
# carrying the .mrpack ("file" below); primary_file marks it as the version's main file.
METADATA=$(jq -nc \
  --arg name "Dungeon Train $TAG" \
  --arg version_number "$VERSION" \
  --arg version_type "$VERSION_TYPE" \
  --arg changelog "$CHANGELOG" \
  --arg mc "$MC_VERSION" \
  --arg project_id "$MODRINTH_MODPACK_PROJECT_ID" \
  '{
     name: $name,
     version_number: $version_number,
     version_type: $version_type,
     changelog: $changelog,
     dependencies: [],
     game_versions: [$mc],
     loaders: ["neoforge"],
     featured: false,
     project_id: $project_id,
     primary_file: "file",
     file_parts: ["file"]
   }')

if [ "${DRY_RUN:-}" = "1" ]; then
  echo "DRY_RUN=1 — skipping Modrinth upload."
  echo "Would upload as: name='Dungeon Train $TAG', version_number=$VERSION, version_type=$VERSION_TYPE, Minecraft=$MC_VERSION, loader=neoforge, project=$MODRINTH_MODPACK_PROJECT_ID"
  echo "::group::Version metadata"
  printf '%s\n' "$METADATA" | jq .
  echo "::endgroup::"
  exit 0
fi

: "${MODRINTH_TOKEN:?must be set for upload}"

# Write metadata to a file and feed it as the `data` field value (curl `<` form) with an
# explicit JSON content type — avoids shell-escaping the JSON (which can contain ';').
printf '%s' "$METADATA" > "$WORKDIR/data.json"

# --- Upload ---
RESPONSE=$(curl -sS -w $'\n%{http_code}' \
  -H "Authorization: $MODRINTH_TOKEN" \
  -F "data=<$WORKDIR/data.json;type=application/json" \
  -F "file=@$MRPACK_PATH;type=application/x-modrinth-modpack+zip;filename=$MRPACK_NAME" \
  "$API/version")
HTTP_CODE=$(printf '%s' "$RESPONSE" | tail -n1)
BODY=$(printf '%s' "$RESPONSE" | sed '$d')

if [ "$HTTP_CODE" != "200" ] && [ "$HTTP_CODE" != "201" ]; then
  echo "::error::Modrinth modpack upload failed (HTTP $HTTP_CODE): $BODY"
  exit 1
fi

NEW_VERSION_ID=$(printf '%s' "$BODY" | jq -r '.id // empty')
echo "✓ Uploaded modpack $MRPACK_NAME to project $MODRINTH_MODPACK_PROJECT_ID (version id ${NEW_VERSION_ID:-unknown}, $VERSION_TYPE)"
