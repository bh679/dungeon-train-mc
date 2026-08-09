#!/bin/bash
# Dungeon Train contents-despawn A/B — both arms concurrently, on the pinned-seed superflat world.
#
#   bash scripts/perf/run-ab.sh <armB-worktree-path>
#
# ~11 minutes. Opens two Minecraft client windows (a rider must be aboard or the train despawns).
# Leave them alone while it runs; it stops both servers and kills both clients itself.
#
# RUN THIS FROM YOUR OWN TERMINAL, not from inside Claude Code — the agent harness reaps long-lived
# background process trees (exit 144), which kills both servers mid-protocol. See README gotcha 1.
#
# Arm A = despawn OFF (baseline). Arm B = despawn ON. Each needs its OWN worktree, because two
# servers cannot share run/ (world, server.properties, logs, Gradle project lock). Create one with:
#   git worktree add ../dt-perf-armB --detach <branch>
set -uo pipefail

S="$(cd "$(dirname "$0")" && pwd)"
WA="$(cd "$S/../.." && pwd)"          # arm A = the worktree this script lives in
WB="${1:?usage: run-ab.sh <armB-worktree-path>}"
[ -d "$WB" ] || { echo "arm B worktree not found: $WB"; exit 1; }

OUT="$S/out"
mkdir -p "$OUT"
rm -f "$OUT/A_off.ready" "$OUT/B_on.ready"

# Hard watchdog so a wedged run never leaves stray Minecraft JVMs behind. Scoped to these two
# worktrees only.
( sleep 1800
  pkill -9 -f "$(basename "$WA").*devlaunch"
  pkill -9 -f "$(basename "$WB").*devlaunch" ) >/dev/null 2>&1 &
WATCHDOG=$!

bash "$S/run-arm.sh" A_off off "$WA" 25571 "$OUT" > "$OUT/A.console" 2>&1 &
PA=$!
sleep 5
bash "$S/run-arm.sh" B_on  on  "$WB" 25573 "$OUT" > "$OUT/B.console" 2>&1 &
PB=$!

echo "both arms running (~11 min).  watch:  tail -f $OUT/A.console"
wait $PA $PB
kill $WATCHDOG 2>/dev/null

echo
echo "================ RESULT ================"
python3 "$S/analyze.py" "$OUT"
echo
echo "Both arms must read 'valid'. If either does not, the run is unusable —"
echo "do not read the delta. See README.md."
