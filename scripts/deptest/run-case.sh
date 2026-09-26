#!/usr/bin/env bash
# Stage an exact mods/ set into a real NeoForge server and report how mod loading ended.
#
# Usage: scripts/deptest/run-case.sh "<case name>" <mod-key> [<mod-key> ...]
#
# Mod keys:
#   dt         the freshly built Dungeon Train jar (build/libs/)
#   sable      the pinned Sable build
#   ain ais pmob ecp te  the five un-bundled sibling mods, at their pinned versions
#   kt db sff  the three hybrid siblings (also jarJar'd in the DT jar), at their pinned versions
#   fp moon    Fast Paintings + its Moonlight library (third-party required deps)
#   bn bclib wover wunder  BetterNether: New Dawn + its three libraries (third-party required deps)
#   be         BetterEnd: New Dawn (shares BetterNether's three libraries)
#   wwoo cristel bop tb glitch  WWOO + Cristel Lib, Biomes O' Plenty + TerraBlender + GlitchCore
#              (second-lap overworld mods, third-party required deps)
#   sp         Sable Pathfinder (Modrinth-required, `optional` in mods.toml — absent on CurseForge)
#   pmob-new   PlayerMob ABOVE the declared floor (uses playermob_version)
#   pmob-old   PlayerMob BELOW the declared floor (downloaded, see README)
#
# Everything is resolved from the Gradle cache, so the versions tested are exactly the ones
# gradle.properties declares — there is no second list to keep in sync.
#
# See README.md for why ./gradlew runServer cannot substitute for this.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SERVER="$HERE/server"
GC="$HOME/.gradle/caches/modules-2/files-2.1"

prop() { grep "^$1=" "$REPO/gradle.properties" | head -1 | cut -d= -f2-; }

NEO_VERSION="$(prop neo_version)"
ARGS_FILE="libraries/net/neoforged/neoforge/$NEO_VERSION/unix_args.txt"

# Gradle provisions a JDK 21 for the build; reuse it so this doesn't depend on the shell's
# default java (which on this project's machines is often 8 or 17 and cannot run MC 1.21.1).
find_jdk21() {
  # Layout differs by OS: macOS nests <dist>/<jdk>/Contents/Home, Linux is <dist>/<jdk>.
  # Match on an actual java binary rather than guessing the shape, and confirm the version.
  local candidate
  while IFS= read -r candidate; do
    if "$candidate" -version 2>&1 | head -1 | grep -q '"21\.'; then
      # strip /bin/java to get JAVA_HOME
      echo "${candidate%/bin/java}"
      return 0
    fi
  done < <(find "$HOME/.gradle/jdks" -maxdepth 6 -type f -name java -path "*/bin/java" 2>/dev/null)
  return 1
}

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-}/bin/java" ]; then
  JAVA_HOME="$(find_jdk21 || true)"
fi
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "ERROR: no JDK 21 found. Set JAVA_HOME to a Java 21 install." >&2
  exit 2
fi
export JAVA_HOME

# Newest non-sources jar under a Gradle cache module directory.
cached() { find "$GC/$1/$2" -name "*.jar" ! -name "*sources*" 2>/dev/null | head -1; }

resolve() {
  case "$1" in
    dt)       ls -t "$REPO"/build/libs/dungeontrain-*.jar 2>/dev/null | grep -v -- '-sources' | head -1 ;;
    sable)    cached "maven.modrinth/sable"          "$(prop sable_version)" ;;
    ain)      cached "bh679/adventureitemnames"      "$(prop adventureitemnames_version)" ;;
    ais)      cached "bh679/adventureitemstats"      "$(prop adventureitemstats_version)" ;;
    ecp)      cached "bh679/enderchestpersistence"   "$(prop enderchestpersistence_version)" ;;
    te)       cached "bh679/tradeeverything"         "$(prop tradeeverything_version)" ;;
    # Third-party required deps, keyed by Modrinth VERSION ID (see gradle.properties).
    fp)       cached "maven.modrinth/fast-paintings" "$(prop fastpaintings_version)" ;;
    moon)     cached "maven.modrinth/moonlight"      "$(prop moonlight_version)" ;;
    sp)       cached "maven.modrinth/sable-pathfinder" "$(prop sablepathfinder_version)" ;;
    bn)       cached "maven.modrinth/betternether-neoforge" "$(prop betternether_version)" ;;
    bclib)    cached "maven.modrinth/bclib-neoforge"        "$(prop bclib_version)" ;;
    wover)    cached "maven.modrinth/worldweaver-neoforge"  "$(prop worldweaver_version)" ;;
    wunder)   cached "maven.modrinth/wunderlib-neoforge"    "$(prop wunderlib_version)" ;;
    be)       cached "maven.modrinth/betterend-neoforge"    "$(prop betterend_version)" ;;
    wwoo)     cached "maven.modrinth/wwoo"            "$(prop wwoo_version)" ;;
    cristel)  cached "maven.modrinth/cristel-lib"     "$(prop cristellib_version)" ;;
    bop)      cached "maven.modrinth/biomes-o-plenty" "$(prop biomesoplenty_version)" ;;
    tb)       cached "maven.modrinth/terrablender"    "$(prop terrablender_version)" ;;
    glitch)   cached "maven.modrinth/glitchcore"      "$(prop glitchcore_version)" ;;
    # Hybrid siblings — ALSO jarJar'd inside the DT jar. Present as top-level jars they model the
    # CurseForge-app install (nested copy must be skipped); absent they model Modrinth/manual.
    kt)       cached "bh679/keeptrim"                "$(prop keeptrim_version)" ;;
    db)       cached "bh679/dungeonbackup"           "$(prop dungeonbackup_version)" ;;
    sff)      cached "bh679/sable_fence_trapdoor_fix" "$(prop sablefencetrapdoorfix_version)" ;;
    # Stream Detect / DPI Bypass Detect — hybrid like kt/db/sff: ALSO jarJar'd inside the DT jar.
    # Present (Case A) they model the CurseForge-app install (nested copy skipped); absent they
    # model Modrinth/manual, loading from the nested copy.
    sd)       cached "bh679/streamdetect"            "$(prop streamdetect_version)" ;;
    dbd)      cached "bh679/dpibypassdetect"         "$(prop dpibypassdetect_version)" ;;
    # Pigman Villagers — jarJar'd inside the DT jar; present (Case A) proves the nested copy is
    # skipped in favour of a top-level one.
    pv)       cached "bh679/pigmanvillagers"         "$(prop pigmanvillagers_version)" ;;
    # At the declared floor — the oldest build DT claims to support.
    pmob)     cached "bh679/playermob"               "$(prop playermob_min_version)" ;;
    # Above the floor: whatever the cascade has moved playermob_version to. When those two
    # values differ, this case is the live proof that cascade bumps don't break players.
    pmob-new) cached "bh679/playermob"               "$(prop playermob_version)" ;;
    pmob-old) echo "$HERE/playermob-old.jar" ;;
    *) echo "unknown mod key: $1" >&2; return 1 ;;
  esac
}

if [ ! -f "$SERVER/$ARGS_FILE" ]; then
  echo "ERROR: no NeoForge $NEO_VERSION server at $SERVER — run the setup in README.md first." >&2
  exit 2
fi

CASE_NAME="$1"; shift
rm -rf "$SERVER/mods"; mkdir -p "$SERVER/mods"
echo "eula=true" > "$SERVER/eula.txt"

echo "=== CASE: $CASE_NAME ==="
for key in "$@"; do
  src="$(resolve "$key")"
  if [ -z "$src" ] || [ ! -f "$src" ]; then
    echo "  !! no jar for '$key' — build the mod first, or see README.md for the downloaded ones"
    exit 2
  fi
  cp "$src" "$SERVER/mods/"
  echo "  + $(basename "$src")"
done

mkdir -p "$HERE/logs"
LOG="$HERE/logs/$(echo "$CASE_NAME" | tr ' /' '__').log"

# On success the server would run forever, so feed it `stop`: the console handler consumes it
# once boot completes. On a mod-loading failure the JVM exits before ever reading stdin.
# macOS ships no coreutils `timeout`, hence the explicit watchdog.
cd "$SERVER"
( echo "stop" ) | "$JAVA_HOME/bin/java" -Xmx2G @"$ARGS_FILE" --nogui > "$LOG" 2>&1 &
JVM_PID=$!
( sleep 300; kill -9 "$JVM_PID" 2>/dev/null ) 2>/dev/null &
WATCHDOG=$!
wait "$JVM_PID"
kill "$WATCHDOG" 2>/dev/null

# Logs carry ANSI colour codes, so grep with -a and match on the stable text.
if grep -qaE "Missing or unsupported mandatory dependencies|Mod Loading has failed|LoadingFailedException" "$LOG"; then
  echo "  RESULT: MOD LOADING FAILED (dependency error)"
  grep -aoE "Mod ID: '[^']+', Requested by: '[^']+', Expected range: '[^']+', Actual version: '[^']+'" "$LOG" \
    | sort -u | sed 's/^/    /'
elif grep -qaE 'Done \([0-9.]+s\)! For help' "$LOG"; then
  echo "  RESULT: SERVER STARTED CLEANLY"
  # Hybrid siblings (jarJar'd for Modrinth, separate Includes on CurseForge): when a top-level
  # copy is in mods/ NeoForge's JarSelector must drop the nested one. Surface which ids it did.
  grep -aoE "Attempted to select a dependency jar for JarJar which was passed in as source: [a-z_]+" "$LOG" \
    | sed -E 's/.*source: /    JarJar: nested copy skipped, mods\/ copy wins: /' | sort -u
else
  echo "  RESULT: INCONCLUSIVE — inspect the log"
  tail -5 "$LOG" | cut -c1-160 | sed 's/^/    /'
fi
echo "  log: ${LOG#$REPO/}"
echo
