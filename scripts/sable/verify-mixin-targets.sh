#!/usr/bin/env bash
# Diff every Sable class DT mixes into, between two Sable versions.
#
# Run this at every `sable_version` bump — it is checklist item #5 in gradle.properties,
# mechanised. DT binds to Sable internals by name (`remap = false`), so a bump can silently
# change what a mixin sits on top of.
#
#   ./gradlew build                                  # once per version, to populate the cache
#   scripts/sable/verify-mixin-targets.sh 2.0.2 2.0.5
#
# Reading the output:
#   IDENTICAL  the class is byte-for-byte unchanged; every claim in the owning mixin's javadoc
#              (call-site counts, enumerated method sets) still holds verbatim.
#   CHANGED    the bodies differ; the diff is printed. Read it and decide whether the freeze
#              gate set still covers the per-body work, then update the mixin's javadoc stamp.
#
# Exit 0 = all identical. Exit 1 = something changed (a prompt to think, not a failure).
# Exit 2 = the check could not run (missing jar, no JDK 21).
#
# What this does NOT prove: that some *other* Sable class did not start doing per-body work.
# See README.md for the manual supplements.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
CACHE="$HOME/.gradle/caches/modules-2/files-2.1/maven.modrinth/sable"

# Each row: <jar>|<fully-qualified class>|<the DT mixin that depends on it>
# Adding a Sable mixin means adding a row here.
TARGETS=(
  "outer |dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem        |SubLevelPhysicsSystemFreezeMixin"
  "outer |dev.ryanhcode.sable.sublevel.ServerSubLevel                      |ServerSubLevelFreezeMixin"
  "rapier|dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline    |RapierPipelineFreezeMixin"
  "outer |dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager    |SubLevelHeatMapSplitMixin"
  "outer |dev.ryanhcode.sable.util.LevelAccelerator                        |LevelAcceleratorNoSyncLoadMixin"
  "outer |dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision|SubLevelEntityCollisionNoLoadMixin"
  "outer |dev.ryanhcode.sable.SableCommonEvents                            |SableBlockChangeGuardMixin"
)

die() { echo "error: $*" >&2; exit 2; }

usage() {
  echo "usage: $(basename "$0") <old-version> <new-version>" >&2
  echo "       versions are bare semver (2.0.2) or full coordinates (2.0.2+mc1.21.1)" >&2
  exit 2
}

[ $# -eq 2 ] || usage

# The +mc… suffix tracks the repo's own pin, so this keeps working across MC bumps.
SABLE_PIN="$(sed -n 's/^sable_version=//p' "$REPO/gradle.properties" | head -1)"
[ -n "$SABLE_PIN" ] || die "no sable_version in $REPO/gradle.properties"
MC_SUFFIX="${SABLE_PIN#*+}"

# javap must be at least 21 — Sable's classes are class-file version 65.
find_javap() {
  local candidates=() c major
  [ -n "${JAVA_HOME:-}" ] && candidates+=("$JAVA_HOME/bin/javap")
  c="$(command -v javap 2>/dev/null)" && [ -n "$c" ] && candidates+=("$c")
  # Gradle provisions its own toolchain when the system JDK is too old.
  for c in "$HOME"/.gradle/jdks/eclipse_adoptium-2[1-9]-*/*/Contents/Home/bin/javap \
           "$HOME"/.gradle/jdks/eclipse_adoptium-2[1-9]-*/*/bin/javap; do
    [ -x "$c" ] && candidates+=("$c")
  done
  for c in "${candidates[@]}"; do
    [ -x "$c" ] || continue
    major="$("$c" -version 2>&1 | sed -E 's/^([0-9]+).*/\1/')"
    case "$major" in ''|*[!0-9]*) continue;; esac
    [ "$major" -ge 21 ] && { echo "$c"; return 0; }
  done
  return 1
}

JAVAP="$(find_javap)" || die "no JDK 21+ javap found (checked \$JAVA_HOME, \$PATH, ~/.gradle/jdks)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Unpack <version> into $WORK/<version>/{outer,rapier}. RapierPhysicsPipeline ships only in the
# bundled sable_rapier jar-in-jar, so `javap -cp <the sable jar>` alone can never find it.
unpack() {
  local ver="$1" dest="$WORK/$1" dir jar inner
  ver="${ver%%+*}"
  dir="$CACHE/$ver+$MC_SUFFIX"
  [ -d "$dir" ] || die "Sable $ver+$MC_SUFFIX is not in the Gradle cache.
       Set sable_version=$ver+$MC_SUFFIX in gradle.properties and run ./gradlew build to fetch it,
       then restore the pin and re-run this script."
  jar="$(find "$dir" -name '*.jar' -print -quit)"
  [ -n "$jar" ] || die "no jar under $dir"
  mkdir -p "$dest/outer" "$dest/rapier"
  unzip -oq "$jar" -d "$dest/outer" || die "could not unpack $jar"
  inner="$(find "$dest/outer/META-INF/jarjar" -name '*sable_rapier*.jar' -print -quit 2>/dev/null)"
  [ -n "$inner" ] || die "no bundled sable_rapier jar inside $jar"
  unzip -oq "$inner" -d "$dest/rapier" || die "could not unpack $inner"
}

# Bytecode offsets and constant-pool indices shift on any recompile, so normalise them away —
# otherwise every class reads as CHANGED and the signal is lost.
dump() {
  "$JAVAP" -p -c -cp "$WORK/$2/$1" "$3" 2>&1 | sed -E 's/^ +[0-9]+: //; s/#[0-9]+/#/g'
}

OLD="${1%%+*}"; NEW="${2%%+*}"
unpack "$OLD"
unpack "$NEW"

echo "Sable mixin-target diff: $OLD+$MC_SUFFIX -> $NEW+$MC_SUFFIX"
echo "javap: $JAVAP"
echo

changed=0
for row in "${TARGETS[@]}"; do
  IFS='|' read -r jar cls owner <<< "$row"
  jar="$(echo "$jar" | tr -d ' ')"; cls="$(echo "$cls" | tr -d ' ')"; owner="$(echo "$owner" | tr -d ' ')"
  short="${cls##*.}"
  dump "$jar" "$OLD" "$cls" > "$WORK/a.txt"
  dump "$jar" "$NEW" "$cls" > "$WORK/b.txt"
  if grep -q '^Error: class not found' "$WORK/b.txt"; then
    printf '===== %-28s MISSING in %s  (%s)\n' "$short" "$NEW" "$owner"
    echo "      The mixin target moved or was renamed. This is a hard load crash, not a perf loss:"
    echo "      dungeontrain.mixins.json sets injectors.defaultRequire=1."
    changed=1
    continue
  fi
  if diff -q "$WORK/a.txt" "$WORK/b.txt" >/dev/null; then
    printf '===== %-28s IDENTICAL  (%s)\n' "$short" "$owner"
  else
    printf '===== %-28s CHANGED    (%s)\n' "$short" "$owner"
    diff "$WORK/a.txt" "$WORK/b.txt" | sed 's/^/    /'
    changed=1
  fi
done

echo
if [ "$changed" -eq 0 ]; then
  echo "PASS: every mixin target is byte-identical. The javadoc claims in the owning mixins"
  echo "      still hold verbatim — move their version stamps to $NEW+$MC_SUFFIX."
  exit 0
fi
echo "REVIEW NEEDED: at least one target changed. Read each diff above and confirm the"
echo "      soft-freeze gate set still covers the per-body work (gradle.properties item #5)."
echo "      Then stamp the outcome on item #5 and on the affected mixins' javadoc."
exit 1
