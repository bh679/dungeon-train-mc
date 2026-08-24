#!/bin/bash
# One arm of a Dungeon Train server-tick A/B benchmark.
#
# Usage: run-arm.sh <armName> <on|off> <worktreePath> <port> [outDir]
#
# Normally driven by run-ab.sh rather than called directly. See README.md for the full protocol
# and the gotchas — several of them cost a whole invalid run to discover.
#
# Protocol (identical in both arms; only the flag differs):
#   fresh world at the pinned perf seed, superflat preset
#     -> headless server (-PperfTest: quiet game rules)
#     -> dev client auto-joins (-PquickJoin) so a rider is aboard
#     -> /dtp 1000                       (train where the mobs are)
#     -> POPULATE: walk the train in 4-carriage hops, gate OFF in BOTH arms
#     -> set the arm's flag
#     -> tp 4 carriages ahead, then tp sideways OFF the train
#     -> rendezvous with the other arm
#     -> measure 120s
set -uo pipefail

ARM="${1:?arm name}"; ARM_FLAG="${2:?on|off}"; WT="${3:?worktree path}"; PORT="${4:?port}"
S="$(cd "$(dirname "$0")" && pwd)"
OUT="${5:-$S/out}"
WT_SLUG="$(basename "$WT")"
mkdir -p "$OUT"

# JAVA_HOME must be a JDK 21 — a stray Java 8 on PATH breaks the Gradle build outright.
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(ls -d "$HOME"/.gradle/jdks/*adoptium*21*/*/Contents/Home 2>/dev/null | head -1)"
  export JAVA_HOME
fi
[ -n "${JAVA_HOME:-}" ] || { echo "JAVA_HOME unset and no adoptium-21 found under ~/.gradle/jdks"; exit 1; }

# Must match PerfTestMode.DEFAULT_SEED. The world seed alone pins the TRAIN seed too, because
# DungeonTrainWorldData derives generationSeed from it — there is no second seed to set.
SEED="${PERF_SEED:-8675309031337}"
PRESET='dungeontrain\:dungeon_train_flat'
DTP_X=1000
AHEAD=36               # 4 carriages x CarriageDims.DEFAULT_LENGTH (9)
OFF_TRAIN_Z=10         # off the deck but hard beside the track (see README gotcha 5)
POPULATE_HOPS=4        # 4x36=144 blocks, under the train's own length (see README gotcha 4)
POPULATE_DWELL=8
MEASURE_SECS="${MEASURE_SECS:-120}"
# Sizes the resident carriage window: TrainCarriageAppender targets (vd*16*2)/carriageLength.
# 12 lands ~18-20 resident; raise to 20 (~31) to push past PhysicsSubstepTuner's HIGH_WATER=24.
VIEW_DISTANCE="${VIEW_DISTANCE:-12}"
SETTLE_AFTER_DTP=45
SETTLE_AFTER_JOIN=45

SLOG="$OUT/$ARM.server.log"; CLOG="$OUT/$ARM.client.log"
: > "$SLOG"; : > "$CLOG"
say() { echo "[$(date +%H:%M:%S)] [$ARM] $*"; }

cleanup() {
  say "cleanup"
  # Scoped to THIS worktree so a sibling worktree's dev session is never touched.
  pkill -f "$WT_SLUG.*devlaunch" 2>/dev/null; sleep 3
  pkill -9 -f "$WT_SLUG.*devlaunch" 2>/dev/null
  exec 3>&- 2>/dev/null; rm -f "$FIFO" 2>/dev/null
}
trap cleanup EXIT

cd "$WT" || exit 1

# Fresh world per run. Moved aside rather than deleted — rm -rf is deny-listed in this repo, and
# keeping the old worlds makes a bad run re-inspectable. Prune run/world.old.* yourself when they
# add up; they reach hundreds of MB quickly.
if [ -d run/world ]; then mv run/world "run/world.old.$(date +%s)"; fi
mkdir -p run/logs
rm -f run/logs/latest.log     # a stale "Done (" satisfies the readiness wait instantly (gotcha 6)

cat > run/server.properties <<EOF
level-seed=$SEED
level-type=$PRESET
server-port=$PORT
online-mode=false
enforce-secure-profile=false
max-tick-time=-1
view-distance=$VIEW_DISTANCE
simulation-distance=10
sync-chunk-writes=false
level-name=world
motd=DT perf A/B $ARM
EOF
echo "eula=true" > run/eula.txt

FIFO="$OUT/$ARM.fifo"; rm -f "$FIFO"; mkfifo "$FIFO"
say "server up (seed=$SEED preset=$PRESET port=$PORT wt=$WT_SLUG)"
./gradlew runServer -PperfTest -I "$S/stdin.gradle" --console=plain < "$FIFO" > "$SLOG" 2>&1 &
exec 3>"$FIFO"     # hold the write end open so the server's stdin never sees EOF

waitfor() { # waitfor <file> <regex> <timeout_s> <label>
  local f="$1" re="$2" to="$3" lbl="$4" i=0
  while [ "$i" -lt "$to" ]; do
    if /usr/bin/grep -qE "$re" "$f" 2>/dev/null; then say "ok: $lbl (${i}s)"; return 0; fi
    sleep 2; i=$((i+2))
  done
  say "TIMEOUT: $lbl"; return 1
}

waitfor "$SLOG" 'Done \(' 600 "server ready" || exit 1
say "client connecting"
./gradlew runClient -PquickJoin="127.0.0.1:$PORT" --console=plain > "$CLOG" 2>&1 &
waitfor "$SLOG" 'joined the game' 600 "player joined" || exit 1
PLAYER=$(/usr/bin/grep -oE '[A-Za-z0-9_]+ joined the game' "$SLOG" | head -1 | awk '{print $1}')
say "player=$PLAYER; settling ${SETTLE_AFTER_JOIN}s"
sleep "$SETTLE_AFTER_JOIN"

# Populate with the gate OFF in BOTH arms so both enter the measured window holding the same
# content. If the gate were live during the walk, arm B would sweep as it went and the arms would
# start from different states — the one thing that would invalidate the comparison outright.
echo "dungeontrain debug contentsdespawn off" >&3; sleep 3

# /dtp resolves the player from the command source, so it cannot run from the bare console.
say "dtp $DTP_X"
echo "execute as $PLAYER run dtp $DTP_X" >&3
sleep "$SETTLE_AFTER_DTP"

say "populate: $POPULATE_HOPS x +$AHEAD"
for _ in $(seq 1 "$POPULATE_HOPS"); do
  echo "execute as $PLAYER at @s run tp @s ~$AHEAD ~ ~" >&3
  sleep "$POPULATE_DWELL"
done
say "populate done — $(/usr/bin/grep -c 'Distance gate:' "$SLOG") spawn-gate fires"

say "contentsdespawn -> $ARM_FLAG"
echo "dungeontrain debug contentsdespawn $ARM_FLAG" >&3; sleep 3

say "tp +$AHEAD (4 carriages ahead)"
echo "execute as $PLAYER at @s run tp @s ~$AHEAD ~ ~" >&3; sleep 5

say "tp OFF the train (+${OFF_TRAIN_Z}z)"
echo "execute as $PLAYER at @s run tp @s ~ ~ ~$OFF_TRAIN_Z" >&3
sleep 5

# Rendezvous: both arms wait here so the measured windows cover the SAME wall-clock span. Without
# it one arm's window overlaps the other's populate phase and the CPU contention between the two
# servers is asymmetric — which defeats the point of running them concurrently.
touch "$OUT/$ARM.ready"
say "at rendezvous"
rz=0
while [ "$rz" -lt 300 ]; do
  [ -f "$OUT/A_off.ready" ] && [ -f "$OUT/B_on.ready" ] && break
  sleep 2; rz=$((rz+2))
done
[ "$rz" -ge 300 ] && say "WARNING: rendezvous timed out — the other arm never arrived; this run is not comparable"
say "rendezvous released (${rz}s)"

# A no-op relative tp echoes "Teleported <player> to X" — the cheapest way to read the player's
# position out of a headless server without RCON. Bracketing the window gives the drift check.
echo "execute as $PLAYER at @s run tp @s ~ ~ ~" >&3
sleep 2
MARK=$(wc -l < "$SLOG" | tr -d ' ')
say "MEASURE START (line $MARK, ${MEASURE_SECS}s)"
echo "$MARK" > "$OUT/$ARM.mark"
sleep "$MEASURE_SECS"
say "MEASURE END"
echo "execute as $PLAYER at @s run tp @s ~ ~ ~" >&3
sleep 2
echo "dungeontrain debug contentsdespawn status" >&3; sleep 2
echo "stop" >&3; sleep 15
say "done"
