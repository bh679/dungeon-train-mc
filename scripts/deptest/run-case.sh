#!/usr/bin/env bash
# Stage an exact mods/ set into a real NeoForge server and report how mod loading ended.
#
# Usage: scripts/deptest/run-case.sh "<case name>" <mod-key> [<mod-key> ...]
#
# Mod keys:
#   dt         the freshly built Dungeon Train jar (build/libs/)
#   sable      the pinned Sable build
#   ain ais pmob ecp    the four un-bundled sibling mods, at their pinned versions
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
else
  echo "  RESULT: INCONCLUSIVE — inspect the log"
  tail -5 "$LOG" | cut -c1-160 | sed 's/^/    /'
fi
echo "  log: ${LOG#$REPO/}"
echo
