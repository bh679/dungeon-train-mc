#!/usr/bin/env bash
# Make the website's light copy of an animated release image: a silent, looping-friendly .mp4 beside the GIF
# (images/foo.gif -> images/foo.loop.mp4), many times smaller. Attach it with set-major.py --loop; the homepage
# Updates card plays it in place of the GIF.
#
# Usage: scripts/release-notes/gif-to-loop.sh .github/release-notes/images/foo.gif [more.gif ...]
set -euo pipefail

CRF="${LOOP_CRF:-26}"

command -v ffmpeg >/dev/null || { echo "::error::ffmpeg is not installed" >&2; exit 1; }
[ "$#" -gt 0 ] || { echo "usage: $0 <image.gif> [...]" >&2; exit 1; }

for gif in "$@"; do
  case "$gif" in *.gif) ;; *) echo "::error::not a .gif: $gif" >&2; exit 1 ;; esac
  [ -f "$gif" ] || { echo "::error::no such file: $gif" >&2; exit 1; }
  out="${gif%.gif}.loop.mp4"
  # Even dimensions for yuv420p; faststart so playback begins before the whole file arrives.
  ffmpeg -loglevel error -y -i "$gif" -an -c:v libx264 -crf "$CRF" -pix_fmt yuv420p \
    -vf "scale=trunc(iw/2)*2:trunc(ih/2)*2" -movflags +faststart "$out"
  echo "$out  $(wc -c <"$gif" | tr -d ' ') -> $(wc -c <"$out" | tr -d ' ') bytes"
done
