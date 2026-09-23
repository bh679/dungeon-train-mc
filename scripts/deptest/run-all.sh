#!/usr/bin/env bash
# The dependency-contract suite. Run before a release that touches dependency declarations.
#
#   scripts/deptest/setup.sh      # once
#   ./gradlew build               # the jar under test
#   scripts/deptest/run-all.sh
#
# Expected results are tabulated in README.md — a deviation is a regression, not a puzzle.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

# Positive control first: if A fails, every later "failed" result is meaningless.
"$HERE/run-case.sh" "A - full set (positive control)"                  dt sable ain ais pmob ecp te kt db sff fp moon bn bclib wover wunder
"$HERE/run-case.sh" "B - missing AIN only"                             dt sable ais pmob ecp te kt db sff fp moon bn bclib wover wunder
"$HERE/run-case.sh" "C - missing all five siblings (upgrade path)"      dt sable fp moon bn bclib wover wunder
"$HERE/run-case.sh" "D - PlayerMob above floor (cascade tolerance)"    dt sable ain ais pmob-new ecp te kt db sff fp moon bn bclib wover wunder
"$HERE/run-case.sh" "E - PlayerMob below floor"                        dt sable ain ais pmob-old ecp te kt db sff fp moon bn bclib wover wunder
"$HERE/run-case.sh" "F - missing Sable (exact-pin control)"            dt ain ais pmob ecp te kt db sff fp moon bn bclib wover wunder
# Hybrid siblings (Keep Trim, Dungeon Backup, Sable Fence & Trapdoor Fix) are jarJar'd AND CurseForge
# Includes. A above = CurseForge-app layout (top-level copies present, nested ones skipped);
# G = Modrinth / manual layout (nested copies only).
"$HERE/run-case.sh" "G - hybrid siblings nested only (Modrinth path)"  dt sable ain ais pmob ecp te fp moon bn bclib wover wunder
"$HERE/run-case.sh" "H - missing Fast Paintings + Moonlight"           dt sable ain ais pmob ecp te kt db sff bn bclib wover wunder
"$HERE/run-case.sh" "I - missing BetterNether (libraries present)"      dt sable ain ais pmob ecp te kt db sff fp moon bclib wover wunder
