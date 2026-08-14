#!/usr/bin/env bash
#
# Build the Video Tools tile assets from screen-capture footage.
#
# Each source clip produces two artifacts from one set of frames:
#
#   1. <name>.gif          — a real, sped-up, compressed GIF for Discord / store pages / social.
#                            NOT shipped in the jar.
#   2. <name>.png          — a grid sprite sheet, which is what the in-game tile actually
#                            animates (Minecraft cannot render a GIF; GUI animation is
#                            blit-a-frame-at-a-time). Shipped under
#                            assets/dungeontrain/textures/gui/videotools/.
#
# The grid is deliberately wide-and-short rather than one tall strip: a 41-frame vertical strip
# would be 4100px tall, and older GPUs cap texture size at 2048.
#
# The source .mov files live OUTSIDE the repo (they're screen captures, tens of MB). Point
# SRC_DIR at wherever they are. Re-run this only when the footage changes.
#
# Usage:  scripts/videotools/build-tiles.sh [SRC_DIR]
#
# IMPORTANT: the frame count printed for each clip must match VideoTool.java's `frames` field,
# and the grid must match its `cols`/`rows`. The script prints both — if you change SPEED or FPS,
# update VideoTool.java to match or the tile will animate onto blank cells.

set -euo pipefail

SRC_DIR="${1:-$HOME/Pictures/Screenshots}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_TEX="$REPO_ROOT/src/main/resources/assets/dungeontrain/textures/gui/videotools"
OUT_GIF="${OUT_GIF:-$REPO_ROOT/build/videotools-gifs}"

# Playback speed multiplier and output frame rate. Both feed the frame count.
SPEED="${SPEED:-5}"
FPS="${FPS:-12}"

# Letterbox crop of the source captures: 640x412 → 640x400 at y=12 (a clean 8:5).
CROP="${CROP:-640:400:0:12}"
# Per-frame size in the sheet. 8:5, matching the crop.
TILE_W="${TILE_W:-160}"
TILE_H="${TILE_H:-100}"

mkdir -p "$OUT_TEX" "$OUT_GIF"

build() {
  local src_name="$1" out_name="$2" cols="$3" rows="$4"
  local src="$SRC_DIR/$src_name.mov"

  if [[ ! -f "$src" ]]; then
    echo "!! missing source: $src" >&2
    return 1
  fi

  # setpts speeds up playback; fps then resamples to the target rate.
  local vf="crop=$CROP,setpts=PTS/$SPEED,fps=$FPS"

  # 1. GIF — two-pass palette so the compression doesn't smear the dark carriage interiors.
  local palette="$OUT_GIF/$out_name.palette.png"
  ffmpeg -v error -y -i "$src" -vf "$vf,scale=320:-1:flags=lanczos,palettegen=max_colors=128" "$palette"
  ffmpeg -v error -y -i "$src" -i "$palette" \
    -lavfi "$vf,scale=320:-1:flags=lanczos [x]; [x][1:v] paletteuse=dither=bayer:bayer_scale=3" \
    -loop 0 "$OUT_GIF/$out_name.gif"
  rm -f "$palette"

  # 2. Sprite sheet — same frames, tiled into a cols×rows grid, left-to-right then top-to-bottom.
  ffmpeg -v error -y -i "$src" \
    -vf "$vf,scale=$TILE_W:$TILE_H,tile=${cols}x${rows}" \
    -frames:v 1 "$OUT_TEX/$out_name.png"

  local frames
  frames="$(ffprobe -v error -count_frames -select_streams v:0 \
    -show_entries stream=nb_read_frames -of default=nw=1:nk=1 "$OUT_GIF/$out_name.gif")"
  printf '%-18s %2s frames  grid %sx%s  sheet %s  gif %s\n' \
    "$out_name" "$frames" "$cols" "$rows" \
    "$(du -h "$OUT_TEX/$out_name.png" | cut -f1)" \
    "$(du -h "$OUT_GIF/$out_name.gif" | cut -f1)"
}

# travelthrough — flying the length of the carriages, doors opening ahead (cinematographer mode).
build travelthrough cinematographer 7 6
# cinematic — the sweeping exterior of the train on the viaduct (the intro cinematic).
build cinematic cinematic 4 3

echo
echo "Sheets → $OUT_TEX"
echo "GIFs   → $OUT_GIF (not shipped in the jar)"
