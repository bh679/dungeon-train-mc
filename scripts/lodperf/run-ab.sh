#!/usr/bin/env bash
# A/B the far-carriage render LOD: same seed, same train, same route, LOD on vs off.
#
# Both arms are run from scratch — fresh server world, fresh client, fresh JVMs — because a
# second arm reusing a warm world would be measuring a different thing (populated chunk cache,
# JIT already warm, meshes already built).
#
# Protocol per arm:
#   1. wipe and recreate the server + client game directories
#   2. start a headless dedicated server on a FIXED level-seed
#   3. start a client that auto-connects, with the LOD forced on or off at launch
#   4. /dtp <X> — teleports the player and respawns the train seeded at that world-X
#   5. settle, then /tp the player AHEAD_BLOCKS further along the train
#   6. hold still for MEASURE_S while the client samples every frame
#   7. shut both down and collect the logs
#
# The LOD is a CLIENT-side feature, so the result is the client's frametime distribution. The
# server's MSPT is collected too, as a control: it should NOT move, and if it does, something
# other than the LOD is differing between the arms.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

# ---- Knobs (env-overridable) ------------------------------------------------------------
SEED="${SEED:-8675309}"              # level-seed; the DT train seed is derived from it
DTP_X="${DTP_X:-1000}"               # /dtp target world-X
AHEAD_BLOCKS="${AHEAD_BLOCKS:-36}"   # 4 carriages x 9 blocks (CarriageDims.DEFAULT_LENGTH)
MEASURE_S="${MEASURE_S:-120}"        # measurement hold, seconds
SETTLE_S="${SETTLE_S:-45}"           # post-/dtp settle before the tp
JOIN_TIMEOUT_S="${JOIN_TIMEOUT_S:-300}"
RCON_PORT="${RCON_PORT:-25575}"
RCON_PW="${RCON_PW:-lodperf}"
SERVER_PORT="${SERVER_PORT:-25566}"
VIEW_DISTANCE="${VIEW_DISTANCE:-12}"
PLAYER="${PLAYER:-Dev}"

JAVA_HOME="${JAVA_HOME:-$HOME/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home}"
export JAVA_HOME

SERVER_DIR="$REPO_ROOT/run-lodperf-server"
CLIENT_DIR="$REPO_ROOT/run-lodperf-client"
OUT_DIR="$REPO_ROOT/build/lodperf"
mkdir -p "$OUT_DIR"

rcon() { python3 "$REPO_ROOT/scripts/lodperf/rcon.py" 127.0.0.1 "$RCON_PORT" "$RCON_PW" "$1"; }

log() { echo "[lodperf-harness] $*"; }

wait_for() { # wait_for <description> <timeout_s> <shell-condition>
  local desc="$1" timeout="$2" cond="$3" waited=0
  while ! eval "$cond"; do
    sleep 2; waited=$((waited + 2))
    if [ "$waited" -ge "$timeout" ]; then
      log "TIMEOUT after ${timeout}s waiting for: $desc"
      return 1
    fi
  done
  log "ok: $desc (${waited}s)"
}

run_arm() { # run_arm <on|off>
  local arm="$1"
  local slog="$OUT_DIR/server-$arm.log"
  local clog="$OUT_DIR/client-$arm.log"
  log "=== ARM: lod=$arm ==="

  # Fresh everything. Both arms must start from identical state or the comparison is worthless.
  rm -rf "$SERVER_DIR" "$CLIENT_DIR"
  mkdir -p "$SERVER_DIR" "$CLIENT_DIR"

  echo "eula=true" > "$SERVER_DIR/eula.txt"
  cat > "$SERVER_DIR/server.properties" <<EOF
level-seed=$SEED
online-mode=false
server-port=$SERVER_PORT
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PW
view-distance=$VIEW_DISTANCE
simulation-distance=10
max-tick-time=-1
sync-chunk-writes=false
spawn-protection=0
EOF

  log "starting server (seed=$SEED)"
  ./gradlew runServer -PgameDir="$SERVER_DIR" --console=plain > "$slog" 2>&1 &
  local server_pid=$!

  wait_for "server ready" 600 "/usr/bin/grep -q 'Done (' '$slog'" || { kill $server_pid 2>/dev/null; return 1; }
  wait_for "rcon up" 120 "rcon 'list' >/dev/null 2>&1" || { kill $server_pid 2>/dev/null; return 1; }

  log "starting client (lod=$arm), auto-joining 127.0.0.1:$SERVER_PORT"
  ./gradlew runClient \
    -PgameDir="$CLIENT_DIR" \
    -PlodEnabled="$([ "$arm" = on ] && echo true || echo false)" \
    -PlodPerfSample \
    -PlodPerfWindowMs=$((MEASURE_S * 1000)) \
    -PjoinServer="127.0.0.1:$SERVER_PORT" \
    --console=plain > "$clog" 2>&1 &
  local client_pid=$!

  wait_for "player joined" "$JOIN_TIMEOUT_S" "rcon 'list' 2>/dev/null | /usr/bin/grep -q '$PLAYER'" \
    || { kill $client_pid $server_pid 2>/dev/null; return 1; }

  rcon "op $PLAYER" >/dev/null || true
  sleep 5

  log "/dtp $DTP_X"
  rcon "execute as $PLAYER run dtp $DTP_X" || true
  log "settling ${SETTLE_S}s for train assembly + physics"
  sleep "$SETTLE_S"

  log "/tp +$AHEAD_BLOCKS blocks (4 carriages ahead)"
  rcon "execute as $PLAYER at $PLAYER run tp $PLAYER ~$AHEAD_BLOCKS ~ ~" || true

  # The sampler's window is MEASURE_S, so exactly one [lodperf] line lands after this point.
  # Start the window cleanly: the tp itself causes a chunk-load spike that belongs to neither arm.
  sleep 3
  echo "LODPERF_WINDOW_START $(date -u +%s)" >> "$clog"
  log "measuring for ${MEASURE_S}s"
  sleep "$((MEASURE_S + 15))"
  echo "LODPERF_WINDOW_END $(date -u +%s)" >> "$clog"

  log "collecting + shutting down"
  rcon "save-all" >/dev/null 2>&1 || true
  # Grab the server's own tick report as the control.
  rcon "debug start" >/dev/null 2>&1 || true
  sleep 5
  rcon "debug stop" >/dev/null 2>&1 || true

  kill "$client_pid" 2>/dev/null || true
  rcon "stop" >/dev/null 2>&1 || true
  wait "$server_pid" 2>/dev/null || true
  wait "$client_pid" 2>/dev/null || true
  sleep 5
  log "arm $arm done"
}

main() {
  log "building mod first"
  ./gradlew build --console=plain > "$OUT_DIR/build.log" 2>&1 \
    || { log "BUILD FAILED — see $OUT_DIR/build.log"; exit 1; }

  # `off` first: if anything about the harness is broken, it shows up on the arm that exercises
  # the least new code, which makes the failure easier to attribute.
  run_arm off
  run_arm on

  log "=== RESULTS ==="
  python3 "$REPO_ROOT/scripts/lodperf/report.py" "$OUT_DIR" | tee "$OUT_DIR/report.txt"
}

main "$@"
