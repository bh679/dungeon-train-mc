#!/usr/bin/env bash
# Single source of truth for which channel a Dungeon Train build ships on.
#
#   scripts/release/release-type.sh <tag> [--test]
#
# Prints GITHUB_OUTPUT-style lines:
#   type=<alpha|beta|release>   -> mc-publish `version-type:` / CurseForge `releaseType` /
#                                  Modrinth `version_type`
#   prerelease=<true|false>     -> GitHub Release `prerelease:` flag
#
# Rule:
#   --test         -> alpha  (test build: publicly visible but below CurseForge's default Release
#                             filter — the quietest either platform allows)
#   MAJOR == 0     -> beta   (pre-1.0)
#   MAJOR  > 0     -> release
#
# Every publisher MUST call this instead of hardcoding a channel. v0.692.0 was re-uploaded to
# CurseForge as a full `release` because reupload-curseforge.yml carried its own hardcoded
# `version-type: release` — the one non-beta file on the listing. Callers:
#   .github/workflows/release.yml              (mod → GitHub + Modrinth + CurseForge)
#   .github/workflows/reupload-curseforge.yml  (mod → CurseForge recovery path)
#   scripts/modpack/publish-curseforge.sh      (CurseForge modpack)
#   scripts/modpack/publish-modrinth.sh        (Modrinth modpack)
set -euo pipefail

TAG=""
IS_TEST=false
for arg in "$@"; do
  case "$arg" in
    --test) IS_TEST=true ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    -*) echo "release-type.sh: unknown option '$arg'" >&2; exit 2 ;;
    *) TAG="$arg" ;;
  esac
done

if [ -z "$TAG" ]; then
  echo "release-type.sh: usage: release-type.sh <tag> [--test]" >&2
  exit 2
fi

TAG_NUMERIC="${TAG#v}"
if ! [[ "$TAG_NUMERIC" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "release-type.sh: tag '$TAG' is not vMAJOR.MINOR.PATCH" >&2
  exit 2
fi
MAJOR="${TAG_NUMERIC%%.*}"

if [ "$IS_TEST" = true ]; then
  TYPE=alpha; PRERELEASE=true
  echo "Release type: alpha (test build, tag $TAG)" >&2
elif [ "$MAJOR" -gt 0 ]; then
  TYPE=release; PRERELEASE=false
  echo "Release type: release (tag $TAG, MAJOR=$MAJOR > 0)" >&2
else
  TYPE=beta; PRERELEASE=true
  echo "Release type: beta (tag $TAG, MAJOR=$MAJOR — pre-1.0)" >&2
fi

echo "type=$TYPE"
echo "prerelease=$PRERELEASE"
