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
"$HERE/run-case.sh" "A - full set (positive control)"                  dt sable ain ais pmob ecp
"$HERE/run-case.sh" "B - missing AIN only"                             dt sable ais pmob ecp
"$HERE/run-case.sh" "C - missing all four siblings (upgrade path)"     dt sable
"$HERE/run-case.sh" "D - PlayerMob above floor (cascade tolerance)"    dt sable ain ais pmob-new ecp
"$HERE/run-case.sh" "E - PlayerMob below floor"                        dt sable ain ais pmob-old ecp
"$HERE/run-case.sh" "F - missing Sable (exact-pin control)"            dt ain ais pmob ecp
