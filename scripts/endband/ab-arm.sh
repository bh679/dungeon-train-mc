#!/bin/bash
# One arm of the End-band decoration A/B (issue #1785 / #1746): a headless dedicated server on a pinned
# seed generates the same BetterEnd-band strip with endBandFeatureSpill on or off; the region files are
# then censused with count_decorations.py.
#
# Usage: ab-arm.sh <armName> <on|off> <worktreePath> <port> [seed] [outDir]
#
# Environment knobs (all optional):
#   REMAP=on|off     write endBandBetterEndOnly (the #1785 vanilla->BetterEnd remap); unset = NeoForge default
#   STRIP_IN=1500    blocks past the slot's start the strip begins (ignored when STRIP_X0 is set)
#   STRIP_X0=36272   absolute strip start X (chunk-aligned down); must lie inside the slot
#   STRIP_LEN=1024   strip length in blocks
#   Z0=32 Z1=95      strip Z range (inclusive); the default is 4 chunk rows off the corridor
#   SETTLE_SECS=90   wait after the FULL count stops moving before `stop`
#
#   bash scripts/endband/ab-arm.sh A_off off ../dt-ab-A 25571 424242
#   bash scripts/endband/ab-arm.sh B_on  on  ../dt-ab-B 25572 424242
#   python3 scripts/endband/count_decorations.py --compare \
#       scripts/endband/out/A_off.world/region scripts/endband/out/B_on.world/region --x <X0> <X1> --z 32 95
#
# The strip: STRIP_LEN blocks starting STRIP_IN blocks into the first `end better` slot of run 0 (read
# from `/dungeontrain debug cycle-layout`), Z 32..95 — off the track corridor, 4 chunk rows. Both arms
# must use the same seed, order and worktree revision; one worktree per arm if run concurrently (two
# servers cannot share run/). See README.md.
set -uo pipefail

ARM="${1:?arm name}"; SPILL="${2:?on|off}"; WT="${3:?worktree path}"; PORT="${4:?port}"
SEED="${5:-424242}"
S="$(cd "$(dirname "$0")" && pwd)"
OUT="${6:-$S/out}"
WT_SLUG="$(basename "$WT")"
STRIP_IN="${STRIP_IN:-1500}"          # blocks past the slot's start (clear of the fade-in)
STRIP_LEN="${STRIP_LEN:-1024}"        # 64 chunk columns
Z0="${Z0:-32}"; Z1="${Z1:-95}"          # 4 chunk rows off the corridor unless overridden
STRIP_X0="${STRIP_X0:-}"               # absolute strip start; empty = slot start + STRIP_IN
REMAP="${REMAP:-}"                     # endBandBetterEndOnly: on|off, empty = mod default
SETTLE_SECS="${SETTLE_SECS:-90}"       # after the FULL count stops moving: spill delivery, saves
POLL_SECS=30
mkdir -p "$OUT"

case "$SPILL" in on) SPILL_BOOL=true ;; off) SPILL_BOOL=false ;; *) echo "spill must be on|off"; exit 1 ;; esac
REMAP_LINE=""
case "$REMAP" in
  on)  REMAP_LINE="	endBandBetterEndOnly = true" ;;
  off) REMAP_LINE="	endBandBetterEndOnly = false" ;;
  "")  ;;
  *)   echo "REMAP must be on|off"; exit 1 ;;
esac

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(ls -d "$HOME"/.gradle/jdks/*adoptium*21*/*/Contents/Home 2>/dev/null | head -1)"
  [ -n "$JAVA_HOME" ] && export JAVA_HOME
fi

SLOG="$OUT/$ARM.server.log"; : > "$SLOG"
say() { echo "[$(date +%H:%M:%S)] [$ARM] $*"; }

cleanup() {
  say "cleanup"
  pkill -f "$WT_SLUG.*devlaunch" 2>/dev/null; sleep 3
  pkill -9 -f "$WT_SLUG.*devlaunch" 2>/dev/null
  exec 3>&- 2>/dev/null; rm -f "$FIFO" 2>/dev/null
}
trap cleanup EXIT

cd "$WT" || exit 1

if [ -d run/world ]; then mv run/world "run/world.old.$(date +%s)"; fi
mkdir -p run/logs run/config
rm -f run/logs/latest.log

cat > run/server.properties <<EOF
level-seed=$SEED
level-type=dungeontrain\:dungeon_train
server-port=$PORT
online-mode=false
enforce-secure-profile=false
max-tick-time=-1
view-distance=10
simulation-distance=8
sync-chunk-writes=false
level-name=world
motd=DT End-band A/B $ARM
EOF
echo "eula=true" > run/eula.txt

# A partial COMMON config is enough: NeoForge fills every other key with its default.
{
  echo "[worldgen]"
  echo "	endBandFeatureSpill = $SPILL_BOOL"
  echo "	endBandTerrain = \"WORLDGEN\""
  [ -n "$REMAP_LINE" ] && echo "$REMAP_LINE"
} > run/config/dungeontrain-common.toml

FIFO="$OUT/$ARM.fifo"; rm -f "$FIFO"; mkfifo "$FIFO"
say "server up (seed=$SEED spill=$SPILL remap=${REMAP:-default} port=$PORT wt=$WT_SLUG)"
./gradlew runServer -I "$S/../perf/stdin.gradle" --console=plain < "$FIFO" > "$SLOG" 2>&1 &
exec 3>"$FIFO"

waitfor() { # waitfor <file> <regex> <timeout_s> <label>
  local f="$1" re="$2" to="$3" lbl="$4" i=0
  while [ "$i" -lt "$to" ]; do
    if /usr/bin/grep -qE "$re" "$f" 2>/dev/null; then say "ok: $lbl (${i}s)"; return 0; fi
    sleep 2; i=$((i+2))
  done
  say "TIMEOUT: $lbl"; return 1
}

waitfor "$SLOG" 'Done \(' 900 "server ready" || exit 1
sleep 5

# The band layout: first "end better" slot of run 0 (forward), as INFO-logged by the debug command.
# Poll rather than sleep: the layout is logged a few seconds after the command and a one-shot grep
# raced it (both arms of the first run lost by under a second).
SLOT_RE='[0-9]+ +end +better +core=[0-9]+ +X -?[0-9]+\.\.-?[0-9]+'
echo "dungeontrain debug cycle-layout 1" >&3
waitfor "$SLOG" "$SLOT_RE" 60 "layout logged" || { say "no 'end better' slot in the layout log; see $SLOG"; echo "stop" >&3; sleep 10; exit 1; }
LINE=$(/usr/bin/grep -oE "$SLOT_RE" "$SLOG" | head -1)
SLOT_FROM=$(echo "$LINE" | sed -E 's/.*X (-?[0-9]+)\.\.(-?[0-9]+).*/\1/')
SLOT_TO=$(echo "$LINE" | sed -E 's/.*X (-?[0-9]+)\.\.(-?[0-9]+).*/\2/')
if [ -n "$STRIP_X0" ]; then X0=$(( STRIP_X0 / 16 * 16 )); else X0=$(( (SLOT_FROM + STRIP_IN) / 16 * 16 )); fi
X1=$(( X0 + STRIP_LEN - 1 ))
if [ "$X0" -lt "$SLOT_FROM" ] || [ "$X1" -ge "$SLOT_TO" ]; then say "strip $X0..$X1 leaves the slot ($SLOT_FROM..$SLOT_TO); move STRIP_X0 / shorten STRIP_LEN"; echo "stop" >&3; sleep 10; exit 1; fi
say "slot 'end better' X $SLOT_FROM..$SLOT_TO; strip X $X0..$X1 Z $Z0..$Z1"
echo "$X0 $X1 $Z0 $Z1" > "$OUT/$ARM.strip"

# forceload in batches: vanilla caps one command at 256 chunks, so columns per batch = 256 / rows.
ROWS=$(( (Z1 - Z0 + 1) / 16 )); [ "$ROWS" -lt 1 ] && ROWS=1
COLS=$(( 256 / ROWS )); [ "$COLS" -lt 1 ] && COLS=1
x=$X0
while [ "$x" -le "$X1" ]; do
  xe=$(( x + COLS*16 - 1 )); [ "$xe" -gt "$X1" ] && xe=$X1
  echo "forceload add $x $Z0 $xe $Z1" >&3
  x=$(( xe + 1 ))
  sleep 1
done
EXPECTED=$(( (STRIP_LEN / 16) * ROWS ))

# Poll until every strip chunk is FULL on disk and the count has stopped moving.
REGION="run/world/region"
last=-1; stable=0; t=0
while [ "$t" -lt 3600 ]; do
  sleep "$POLL_SECS"; t=$((t+POLL_SECS))
  echo "save-all flush" >&3; sleep 5
  full=$(python3 "$S/count_decorations.py" "$REGION" --x "$X0" "$X1" --z "$Z0" "$Z1" --count-full 2>/dev/null || echo 0)
  say "FULL chunks: $full / $EXPECTED"
  if [ "$full" = "$last" ]; then stable=$((stable+1)); else stable=0; fi
  last=$full
  if [ "$full" -ge "$EXPECTED" ] || [ "$stable" -ge 3 ]; then break; fi
done

say "settling ${SETTLE_SECS}s for spill delivery"
sleep "$SETTLE_SECS"
echo "save-all flush" >&3; sleep 10
echo "stop" >&3; sleep 20
waitfor "$SLOG" 'ThreadedAnvilChunkStorage.*All dimensions are saved|All dimensions are saved|Stopping server' 120 "server stopped" || true
sleep 5

if [ -d run/world ]; then
  if [ -d "$OUT/$ARM.world" ]; then mv "$OUT/$ARM.world" "$OUT/$ARM.world.old.$(date +%s)"; fi
  cp -r run/world "$OUT/$ARM.world"
fi
say "census:"
python3 "$S/count_decorations.py" "$OUT/$ARM.world/region" --x "$X0" "$X1" --z "$Z0" "$Z1" --no-table
say "done — world copied to $OUT/$ARM.world; strip in $OUT/$ARM.strip"
