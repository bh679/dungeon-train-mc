#!/usr/bin/env bash
# Builds the FFmpeg mirror zip DT falls back to when Vista's own download fails
# (client/live/FfmpegInstaller, client/live/FfmpegMirror).
#
# Source: Gyan's Windows "essentials" build, the same one Vista installs by default. It is a GPL
# build; the Live Eye needs its libx264 encoder, which LGPL builds leave out.
# The output keeps only ffmpeg.exe, ffprobe.exe and the licence/readme, with fixed timestamps,
# so the same input always gives the same zip and the same SHA-256.
#
# Usage: scripts/live/ffmpeg-mirror/build.sh [out-dir]   (default: build/ffmpeg-mirror)
# Then put the printed sha256/size into FfmpegMirror.java and deploy the zip (README.md).
set -euo pipefail

VERSION="9.0.2"
UPSTREAM_URL="https://github.com/GyanD/codexffmpeg/releases/download/${VERSION}/ffmpeg-${VERSION}-essentials_build.zip"
UPSTREAM_SHA256="60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba"
OUT_NAME="windows-x64.zip"

out_dir="${1:-build/ffmpeg-mirror}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$out_dir"
out_dir="$(cd "$out_dir" && pwd)"

echo "Downloading $UPSTREAM_URL"
curl -fsSL --retry 3 -o "$work/upstream.zip" "$UPSTREAM_URL"
actual="$(shasum -a 256 "$work/upstream.zip" | cut -d' ' -f1)"
if [[ -n "$UPSTREAM_SHA256" && "$actual" != "$UPSTREAM_SHA256" ]]; then
  echo "Upstream SHA-256 mismatch: got $actual, expected $UPSTREAM_SHA256" >&2
  exit 1
fi
echo "Upstream sha256: $actual"

root="ffmpeg-${VERSION}-essentials_build"
unzip -q "$work/upstream.zip" "$root/bin/ffmpeg.exe" "$root/bin/ffprobe.exe" "$root/LICENSE" "$root/README.txt" -d "$work/x"
stage="$work/stage"
mkdir -p "$stage"
cp "$work/x/$root/bin/ffmpeg.exe" "$work/x/$root/bin/ffprobe.exe" "$stage/"
cp "$work/x/$root/LICENSE" "$stage/LICENSE.txt"
cp "$work/x/$root/README.txt" "$stage/README-gyan.txt"
cat > "$stage/SOURCE.txt" <<SRC
FFmpeg ${VERSION}, Windows x64 "essentials" build by Gyan Doshi (https://www.gyan.dev/ffmpeg/builds/).
Unmodified binaries, repackaged by Dungeon Train with only ffmpeg.exe and ffprobe.exe.
Licence: GPL v3 (LICENSE.txt). Corresponding source:
  FFmpeg ${VERSION}: https://ffmpeg.org/releases/ffmpeg-${VERSION}.tar.xz
  Build and library versions: README-gyan.txt and https://github.com/GyanD/codexffmpeg/releases/tag/${VERSION}
SRC
find "$stage" -exec touch -t 202601010000 {} +
rm -f "$out_dir/$OUT_NAME"
(cd "$stage" && zip -X -q -9 "$out_dir/$OUT_NAME" ffmpeg.exe ffprobe.exe LICENSE.txt README-gyan.txt SOURCE.txt)

echo "Wrote $out_dir/$OUT_NAME"
echo "sha256: $(shasum -a 256 "$out_dir/$OUT_NAME" | cut -d' ' -f1)"
echo "size:   $(wc -c < "$out_dir/$OUT_NAME" | tr -d ' ')"
