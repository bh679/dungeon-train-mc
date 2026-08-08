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

# Kill the forked game JVMs for THIS worktree only.
#
# `kill <gradle-pid>` is not enough: Gradle forks the game as a child JVM that outlives the task
# process. A surviving client from the previous arm holds the window and audio device, and the
# next arm's client then stalls during init and never reaches the server — which is exactly how
# the first attempt at this harness failed.
#
# Matched on this worktree's own moddev VM-args file so a sibling worktree's dev client (other
# sessions do run them on this machine) is never touched.
kill_game_jvms() { # kill_game_jvms <client|server>
  local which="$1"
  local pattern="$REPO_ROOT/build/moddev/${which}RunVmArgs.txt"
  local pids
  pids=$(pgrep -f "$pattern" || true)
  [ -z "$pids" ] && return 0
  log "killing leftover $which JVM(s): $pids"
  kill $pids 2>/dev/null || true
  for _ in $(seq 1 15); do
    sleep 1
    pgrep -f "$pattern" >/dev/null || return 0
  done
  log "forcing $which JVM(s)"
  pkill -9 -f "$pattern" 2>/dev/null || true
  sleep 2
}

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

  # Client settings that decide whether the measurement means anything.
  #
  # DT's idle framerate throttle caps the client to 30fps whenever the window is unfocused, and an
  # unattended harness window is ALWAYS unfocused. Left on, it dominates the result: an early run
  # reported 47.7 vs 29.3 fps, which was two arms throttled by different amounts with both tails
  # pinned to the cap, not anything about the LOD. Off for the harness; the report also rejects any
  # run whose measured frames were throttled.
  #
  # maxFps unlimited (260) and vsync off for the same reason at the other end — a refresh-rate cap
  # would flatten both arms onto the same number and hide a real difference.
  mkdir -p "$CLIENT_DIR/config"
  cat > "$CLIENT_DIR/config/dungeontrain-client.toml" <<'EOF'
[framerateThrottle]
	enabled = false
EOF
  cat > "$CLIENT_DIR/options.txt" <<EOF
maxFps:260
enableVsync:false
pauseOnLostFocus:false
renderDistance:$VIEW_DISTANCE
simulationDistance:10
graphicsMode:1
fullscreen:false
EOF

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

  # Auto-join is occasionally flaky at client init, so allow one restart. A retry cannot bias the
  # measurement — the measured window only opens after the join, and the client is fresh either
  # way — but silently tolerating more than one would hide a real regression in startup.
  local client_pid="" joined=0
  for attempt in 1 2; do
    kill_game_jvms client
    log "starting client (lod=$arm), auto-joining 127.0.0.1:$SERVER_PORT (attempt $attempt)"
    ./gradlew runClient \
      -PgameDir="$CLIENT_DIR" \
      -PlodEnabled="$([ "$arm" = on ] && echo true || echo false)" \
      -PlodPerfSample \
      -PlodPerfWindowMs=10000 \
      -PlodPerfCsv="$OUT_DIR/frames-$arm.csv" \
      -PjoinServer="127.0.0.1:$SERVER_PORT" \
      --console=plain > "$clog" 2>&1 &
    client_pid=$!

    if wait_for "player joined" "$JOIN_TIMEOUT_S" \
        "rcon 'list' 2>/dev/null | /usr/bin/grep -q '$PLAYER'"; then
      joined=1
      break
    fi
    log "client did not join on attempt $attempt"
    kill $client_pid 2>/dev/null || true
  done

  if [ "$joined" -ne 1 ]; then
    log "ABORT arm $arm: client never joined"
    kill_game_jvms client
    rcon "stop" >/dev/null 2>&1 || true
    kill $server_pid 2>/dev/null || true
    kill_game_jvms server
    return 1
  fi

  rcon "op $PLAYER" >/dev/null || true
  sleep 5

  log "/dtp $DTP_X"
  rcon "execute as $PLAYER run dtp $DTP_X" || true
  log "settling ${SETTLE_S}s for train assembly + physics"
  sleep "$SETTLE_S"

  log "/tp +$AHEAD_BLOCKS blocks (4 carriages ahead)"
  rcon "execute as $PLAYER at $PLAYER run tp $PLAYER ~$AHEAD_BLOCKS ~ ~" || true

  # Settle briefly: the tp itself causes a chunk-load and mesh-rebuild spike that belongs to
  # neither arm, and including it would just add the same noise to both.
  sleep 10
  # Window bounds in epoch millis. The report slices the raw frame dump to exactly this range —
  # the sampler's own aggregate window is anchored to the client's first rendered frame, so its
  # boundaries do not line up with the measurement period and must not be used as the result.
  local start_ms
  start_ms=$(python3 -c 'import time; print(int(time.time()*1000))')
  log "measuring for ${MEASURE_S}s"
  sleep "$MEASURE_S"
  local end_ms
  end_ms=$(python3 -c 'import time; print(int(time.time()*1000))')
  echo "$start_ms $end_ms" > "$OUT_DIR/window-$arm.txt"

  log "collecting + shutting down"
  rcon "save-all" >/dev/null 2>&1 || true
  # Grab the server's own tick report as the control.
  rcon "debug start" >/dev/null 2>&1 || true
  sleep 5
  rcon "debug stop" >/dev/null 2>&1 || true

  kill "$client_pid" 2>/dev/null || true
  kill_game_jvms client
  rcon "stop" >/dev/null 2>&1 || true
  wait "$server_pid" 2>/dev/null || true
  kill_game_jvms server
  sleep 5
  log "arm $arm done"
}

main() {
  log "building mod first"
  ./gradlew build --console=plain > "$OUT_DIR/build.log" 2>&1 \
    || { log "BUILD FAILED — see $OUT_DIR/build.log"; exit 1; }

  # `off` first: if anything about the harness is broken, it shows up on the arm that exercises
  # the least new code, which makes the failure easier to attribute.
  run_arm off || { log "arm off failed — no comparison possible"; exit 1; }
  run_arm on  || { log "arm on failed — no comparison possible"; exit 1; }

  log "=== RESULTS ==="
  python3 "$REPO_ROOT/scripts/lodperf/report.py" "$OUT_DIR" | tee "$OUT_DIR/report.txt"
}

main "$@"
