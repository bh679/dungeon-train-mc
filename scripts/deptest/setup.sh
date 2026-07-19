#!/usr/bin/env bash
# One-time setup for the dependency-contract harness: install a NeoForge server and fetch the
# one jar that can't come from the Gradle cache (a PlayerMob build BELOW the declared floor).
#
# Downloads roughly 200 MB. Everything it writes is gitignored.
#
# Usage: scripts/deptest/setup.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"

prop() { grep "^$1=" "$REPO/gradle.properties" | head -1 | cut -d= -f2-; }
NEO_VERSION="$(prop neo_version)"

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
[ -x "$JAVA_HOME/bin/java" ] || { echo "ERROR: no JDK 21 found; set JAVA_HOME." >&2; exit 2; }
export JAVA_HOME

echo "==> NeoForge $NEO_VERSION server -> $HERE/server"
curl -fsSL -o "$HERE/neoforge-installer.jar" \
  "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NEO_VERSION/neoforge-$NEO_VERSION-installer.jar"
mkdir -p "$HERE/server"
"$JAVA_HOME/bin/java" -jar "$HERE/neoforge-installer.jar" --installServer "$HERE/server" | tail -2

# Case E needs a PlayerMob older than playermob_min_version. Any release below the floor works;
# 0.50.0 is comfortably below it and is a plain GitHub release asset.
OLD_PMOB=0.50.0
echo "==> PlayerMob $OLD_PMOB (below-floor fixture)"
curl -fsSL -o "$HERE/playermob-old.jar" \
  "https://github.com/bh679/playermob-mc/releases/download/v$OLD_PMOB/playermob-neoforge-$OLD_PMOB+$(prop minecraft_version).jar"

echo
echo "Setup complete. Run: scripts/deptest/run-all.sh"
