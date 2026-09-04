#!/usr/bin/env bash
#
# Run the shader-compatibility sweep across every pack in run/shaderpacks/.
#
#   scripts/shaders/sweep-all.sh [--preview] "<world folder name>" [pack.zip ...]
#
# --preview captures the Shaders menu's preview frame instead of the diagnostic sites: one shot per
# pack, the train stopped, no panel, into run/screenshots/preview-<pack>.png. The control is skipped
# — the menu's "Shaders off" row is a placeholder, not a photograph — and every other launch is
# identical to a sweep launch, which is what makes the nine frames comparable.
#
# One client launch per pack. Each launch points Iris at that pack, opens the world, and lets
# client/ShaderSweep drive itself round the atmosphere sites, writing run/screenshots/sweep-<pack>-<site>.png
# with the F3+5 diagnostics panel up. See docs/shaders/compat-matrix.md.
#
# The mod quits the game when it is done, so the ordinary path is simply "wait for gradle to exit".
# The deadline below is the backstop for a run that wedges; it kills only the process tree this
# script started, never a broad pattern match — other work on this machine is none of its business.
set -uo pipefail

PREVIEW=0
if [ "${1:-}" = "--preview" ]; then
    PREVIEW=1
    shift
fi

WORLD="${1:-}"
if [ -z "$WORLD" ]; then
    echo "usage: $0 [--preview] \"<world folder name>\" [pack.zip ...]" >&2
    echo "  world folders: $(ls -1 run/saves 2>/dev/null | tr '\n' ' ')" >&2
    exit 2
fi
shift

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 1

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME

LOG="run/logs/latest.log"
MARKER="SHADER SWEEP COMPLETE"
# Generous: a cold world load under shaders is minutes, and a slow run is better than a lost one.
DEADLINE_SECONDS="${SWEEP_DEADLINE_SECONDS:-1500}"

# "vanilla" is not a pack — it is the control. Without a no-shader shot of the same site in the
# same world, "the pack ate the sky" and "there was nothing to see there" are the same picture, and
# every cell in the matrix is a guess. It runs first so the baseline exists before anything is
# compared against it.
if [ "$#" -gt 0 ]; then
    PACKS=("$@")
else
    PACKS=()
    # The control is a sweep concept. A preview run has nothing to compare against a no-shader
    # frame — the menu's "Shaders off" row says what it is in words.
    if [ "$PREVIEW" -eq 0 ]; then PACKS+=("vanilla"); fi
    while IFS= read -r line; do PACKS+=("$line"); done < <(cd run/shaderpacks && ls -1 ./*.zip 2>/dev/null | sed 's|^\./||')
fi

if [ "${#PACKS[@]}" -eq 0 ]; then
    echo "no shader packs in run/shaderpacks/" >&2
    exit 1
fi

if [ "$PREVIEW" -eq 1 ]; then
    SWEEP_PROP="-PshaderPreview=$WORLD"
    echo "capturing menu previews for ${#PACKS[@]} pack(s) over world '$WORLD'"
else
    SWEEP_PROP="-PshaderSweep=$WORLD"
    echo "sweeping ${#PACKS[@]} pack(s) over world '$WORLD'"
fi
FAILED=()

for pack in "${PACKS[@]}"; do
    echo
    echo "=== $pack ==="
    if [ "$pack" = "vanilla" ]; then
        printf 'enableShaders=false\n' > run/config/iris.properties
    else
        printf 'enableShaders=true\nshaderPack=%s\nmaxShadowRenderDistance=32\n' "$pack" > run/config/iris.properties
    fi
    # A preview run does NOT restore the save, deliberately.
    #
    # The first attempt did, on the reasoning that nine frames must start from the same state. It
    # cost a cold worldgen join that blew past the harness's ten-minute join deadline and produced
    # no shot at all. The determinism is bought instead in the site itself: it rides to a band-free
    # stretch scanned from the origin, so every pack photographs the same place whatever the save
    # was left holding. SWEEP_FRESH_SAVE=1 still works if a run needs isolating.

    # SWEEP_FRESH_SAVE=1 restores a pristine save before every pack.
    #
    # Off by default, and the reason is cost. It does remove "something accumulated in the reused
    # world" as an explanation for the carriage room engaging for the first few packs and never
    # again — but it also deletes every region file the earlier runs generated, so each pack pays
    # for cold worldgen twice over. A validation run stalled past a three-minute join on exactly
    # that. The room-box diagnostic answers the same question for free, so this is kept for when a
    # result needs isolating rather than paid on every sweep.
    if [ "${SWEEP_FRESH_SAVE:-0}" = "1" ] && [ -d "run/saves-template/$WORLD" ]; then
        echo "  restoring pristine save (SWEEP_FRESH_SAVE=1 — expect a slow first join)"
        rsync -a --delete "run/saves-template/$WORLD/" "run/saves/$WORLD/"
    fi
    rm -f "$LOG"

    ./gradlew runClient --no-daemon "$SWEEP_PROP" >"run/logs/sweep-launch.out" 2>&1 &
    pid=$!

    # Bring the game window to the front and leave it there.
    #
    # Not cosmetic: macOS stops rendering an occluded window while its ticks carry on, so the
    # framebuffer goes stale and every capture is a copy of the last frame that drew. One run
    # produced eight screenshots of which seven were byte-identical, with a log that looked
    # perfectly healthy. ShaderSweep now shouts when a capture lands on too few frames; this is
    # what stops it happening in the first place.
    ( sleep 45; osascript -e 'tell application "System Events" to tell process "java" to set frontmost to true' >/dev/null 2>&1 ) &

    started=$SECONDS
    done_ok=0
    while true; do
        if ! kill -0 "$pid" 2>/dev/null; then
            # Gradle exited. The marker decides whether that was the sweep finishing or a crash.
            if [ -f "$LOG" ] && grep -q "$MARKER" "$LOG"; then done_ok=1; fi
            break
        fi
        if [ -f "$LOG" ] && grep -q "$MARKER" "$LOG"; then
            done_ok=1
            # The mod quits itself; give it a moment, then stop waiting either way.
            for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 2; done
            break
        fi
        if [ $(( SECONDS - started )) -gt "$DEADLINE_SECONDS" ]; then
            echo "  TIMEOUT after ${DEADLINE_SECONDS}s"
            break
        fi
        sleep 5
    done

    # Scoped teardown: this script's own gradle process and its children, by pid. Never pkill -f.
    if kill -0 "$pid" 2>/dev/null; then
        pkill -TERM -P "$pid" 2>/dev/null
        kill -TERM "$pid" 2>/dev/null
        sleep 5
        pkill -KILL -P "$pid" 2>/dev/null
        kill -KILL "$pid" 2>/dev/null
    fi
    wait "$pid" 2>/dev/null

    # Keep the log before the next pack deletes it. The screenshots carry the panel visually, but
    # the log is the greppable half — one line per site with every value the panel drew, which is
    # what makes a result comparable across twelve runs instead of only readable one image at a time.
    if [ -f "$LOG" ]; then
        mkdir -p run/logs/packs
        cp "$LOG" "run/logs/packs/${pack%.zip}.log" 2>/dev/null
    fi

    # A pack that failed to compile leaves Iris with shaders off, and a sweep that ran with no
    # pack at all is not a measurement of that pack.
    if [ -f "$LOG" ] && /usr/bin/grep -q "pack failed to load" "$LOG"; then
        done_ok=0
    fi

    if [ "$done_ok" -eq 1 ]; then
        shots=$(ls -1 run/screenshots/sweep-*.png 2>/dev/null | wc -l | tr -d ' ')
        echo "  done — $shots shot(s) in run/screenshots so far"
    else
        echo "  FAILED — no '$MARKER' in $LOG"
        FAILED+=("$pack")
        cp -f "$LOG" "run/logs/sweep-failed-${pack%.zip}.log" 2>/dev/null
    fi
done

echo
echo "=== sweep finished ==="
ls -1 run/screenshots/sweep-*.png 2>/dev/null | sed 's|^|  |'
if [ "${#FAILED[@]}" -gt 0 ]; then
    echo "packs with no completion marker (logs kept as run/logs/sweep-failed-*.log):"
    printf '  %s\n' "${FAILED[@]}"
    exit 1
fi
