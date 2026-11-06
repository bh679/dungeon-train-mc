#!/usr/bin/env bash
# Local pre-flight for .github/workflows/build.yml.
#
# Runs the same four jobs CI runs on a pull request, on this machine, before you push:
#   build                 ./gradlew build (in the background, with the daemon)
#   modpack-checks        stdlib Python guards for the modpack configs + generated data
#   net-checks            PROTOCOL_VERSION guard, diffed against origin/main
#   localization-checks   lang parity / duplicates / provenance / placeholders + tests
#
#   scripts/ci/run-local.sh                 # everything (Gradle + Python)
#   scripts/ci/run-local.sh --skip-build    # Python jobs only (~1 min)
#   scripts/ci/run-local.sh --only net-checks
#   scripts/ci/run-local.sh --no-fetch      # don't refresh origin/main first
#
# Each step logs to build/local-ci/<job>/<nn>-<slug>.log. The terminal shows one PASS/FAIL
# line per step and a summary of failures. Exit 0 only if every step passed.
#
# THE STEP LIST IS HAND-COPIED FROM build.yml. The drift guard at the end compares the
# `python3 scripts/...` invocations here against the workflow and warns when they differ;
# when you add a step to build.yml, add it here too. A green local run is strong evidence,
# not proof: CI runs Gradle cold with --no-daemon on Linux.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 2
WORKFLOW="${LOCAL_CI_WORKFLOW:-.github/workflows/build.yml}"
LOG_ROOT="build/local-ci"
BASE_REF="origin/main"

SKIP_BUILD=0
DO_FETCH=1
ONLY=""
usage() {
  sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1 ;;
    --no-fetch)   DO_FETCH=0 ;;
    --only)
      shift
      case "${1:-}" in
        build|modpack-checks|net-checks|localization-checks) ONLY="$1" ;;
        *) echo "run-local.sh: --only needs one of build|modpack-checks|net-checks|localization-checks" >&2; usage ;;
      esac ;;
    -h|--help) usage ;;
    *) echo "run-local.sh: unknown flag '$1'" >&2; usage ;;
  esac
  shift
done

FAILED=()
PASSED=0
STEP_NO=0
CURRENT_JOB=""

want_job() { [ -z "$ONLY" ] || [ "$ONLY" = "$1" ]; }

job() {
  CURRENT_JOB="$1"
  STEP_NO=0
  mkdir -p "$LOG_ROOT/$CURRENT_JOB"
  printf '\n== %s ==\n' "$CURRENT_JOB"
}

# step <label> <command...>: run, log, report one line.
step() {
  local label="$1"; shift
  STEP_NO=$((STEP_NO + 1))
  local slug
  slug="$(printf '%s' "$label" | tr -c 'A-Za-z0-9' '-' | sed 's/-\{2,\}/-/g; s/^-//; s/-$//')"
  local log
  log="$(printf '%s/%s/%02d-%s.log' "$LOG_ROOT" "$CURRENT_JOB" "$STEP_NO" "$slug")"
  local start=$SECONDS
  if "$@" >"$log" 2>&1; then
    printf '  PASS  %-58s %4ds\n' "$label" "$((SECONDS - start))"
    PASSED=$((PASSED + 1))
  else
    printf '  FAIL  %-58s %4ds  -> %s\n' "$label" "$((SECONDS - start))" "$log"
    FAILED+=("$CURRENT_JOB: $label -> $log")
  fi
}

# Several CI steps are a block of test files. Run each; the step fails if any did.
run_all() {
  local rc=0 f
  for f in "$@"; do
    echo "--- $f"
    python3 "$f" || rc=1
  done
  return $rc
}

# --- build (background) ---------------------------------------------------------------------
BUILD_PID=""
BUILD_LOG="$LOG_ROOT/build/01-gradlew-build.log"
if [ "$SKIP_BUILD" -eq 0 ] && want_job build; then
  mkdir -p "$LOG_ROOT/build"
  echo "== build == (./gradlew build running in the background -> $BUILD_LOG)"
  BUILD_START=$SECONDS
  ./gradlew build >"$BUILD_LOG" 2>&1 &
  BUILD_PID=$!
fi

if [ "$DO_FETCH" -eq 1 ] && want_job net-checks; then
  git fetch -q origin main || echo "warning: git fetch origin main failed; net-checks diffs against the stale $BASE_REF" >&2
fi

# --- modpack-checks -------------------------------------------------------------------------
if want_job modpack-checks; then
  job modpack-checks
  step "Modpack Include <-> mod dependency consistency"   python3 scripts/modpack/check-relations.py
  step "Modpack Modrinth pins present on every mod"       python3 scripts/modpack/build-mrpack.py --check-config
  step "Modpack override allowlist"                       python3 scripts/modpack/check-overrides.py
  step "Dependency version pins (Sable chain + floors)"   python3 scripts/modpack/check-pins.py
  step "Modpack mod_ids present"                          python3 scripts/modpack/check-mod-ids.py
  step "Modpack mod_ids match the pinned jars (network)"  python3 scripts/modpack/check-mod-ids.py --verify
  step "Modpack manifest + relation tests" run_all \
    scripts/modpack/test_check_mod_ids.py \
    scripts/modpack/test_build_manifest.py \
    scripts/modpack/test_check_relations.py \
    scripts/modpack/test_check_overrides.py \
    scripts/modpack/test_build_mrpack.py \
    scripts/modpack/test_check_pins.py \
    scripts/modpack/test_upload_retry.py \
    scripts/modpack/test_reconcile.py \
    scripts/modpack/test_wait_for_approval.py \
    scripts/modpack/test_catch_up.py \
    scripts/modpack/test_check_late_rejections.py
  step "Lost City tests"                                  python3 scripts/lost-city/test_lostcity.py
  step "Lost City templates match their recipes"          python3 scripts/lost-city/build-templates.py --check
  step "Lost City variants match their recipes"           python3 scripts/lost-city/generate-variants.py --check
  step "Mob photo collections hold every train mob"       python3 scripts/advancements/generate-mob-photo-collections.py --check
  step "Mob buff offset tests"                            python3 scripts/difficulty/test_derive_mob_offsets.py
  step "Mob buff offsets match the rooms"                 python3 scripts/difficulty/derive-mob-offsets.py --check
  step "Credits house rows match bundled builds + books"  python3 scripts/credits/house-credits.py --check
  step "Green vault blockstate and models are generated"  python3 scripts/assets/build-green-vault.py --check
fi

# --- net-checks -----------------------------------------------------------------------------
if want_job net-checks; then
  job net-checks
  step "Packet layout change bumps PROTOCOL_VERSION"      python3 scripts/net/check-protocol-version.py --base "$BASE_REF"
  step "Protocol-version check tests"                     python3 scripts/net/test_check_protocol_version.py
fi

# --- localization-checks --------------------------------------------------------------------
locale_tree_check() {
  local locales
  locales=$(ls src/main/resources/assets/dungeontrain/lang | sed 's/\.json$//' | grep -v '^en_us$' | tr '\n' ' ')
  # shellcheck disable=SC2086
  python3 scripts/localization/validate-locale.py $locales
}
if want_job localization-checks; then
  job localization-checks
  step "Every locale carries every English key"           python3 scripts/localization/check-lang-parity.py
  step "No lang file or provenance sidecar repeats a key" python3 scripts/localization/check-lang-duplicates.py
  step "Locale trees are structurally intact"             locale_tree_check
  step "Per-line translation provenance aligned"          python3 scripts/localization/check-provenance.py
  step "Per-book narrative provenance aligned"            python3 scripts/localization/check-narrative-provenance.py
  step "Translated books keep every {placeholder}"        python3 scripts/localization/check-book-placeholders.py
  step "Provenance script tests" run_all \
    scripts/localization/test_check_provenance.py \
    scripts/localization/test_stamp_provenance.py \
    scripts/localization/test_check_narrative_provenance.py \
    scripts/localization/test_stamp_narrative_provenance.py \
    scripts/localization/test_provenance_io.py \
    scripts/localization/test_build_review_package.py \
    scripts/localization/test_apply_translator_renames.py \
    scripts/localization/test_import_approved_translations.py \
    scripts/localization/test_import_english_edits.py \
    scripts/advancements/test_import_requirement_edits.py \
    scripts/advancements/editor/test_apply.py \
    scripts/localization/test_lang_format.py \
    scripts/localization/test_check_lang_duplicates.py \
    scripts/localization/test_book_format.py \
    scripts/localization/test_check_book_placeholders.py \
    scripts/localization/test_plural_forms.py \
    scripts/localization/test_merge_locale_keys.py
fi

# --- wait for the build ---------------------------------------------------------------------
if [ -n "$BUILD_PID" ]; then
  printf '\n== build == waiting for ./gradlew build ...\n'
  if wait "$BUILD_PID"; then
    printf '  PASS  %-58s %4ds\n' "./gradlew build" "$((SECONDS - BUILD_START))"
    PASSED=$((PASSED + 1))
  else
    printf '  FAIL  %-58s %4ds  -> %s\n' "./gradlew build" "$((SECONDS - BUILD_START))" "$BUILD_LOG"
    FAILED+=("build: ./gradlew build -> $BUILD_LOG")
  fi
fi

# --- drift guard: does this script still name every script build.yml runs? ------------------
# Compares the set of `scripts/<...>.py` paths in the workflow with the set in this file.
scripts_in() { grep -oE 'scripts/[A-Za-z0-9_./-]+\.py' "$1" | sort -u; }
if [ -f "$WORKFLOW" ]; then
  DRIFT="$(comm -3 <(scripts_in "$WORKFLOW") <(scripts_in "$0"))"
  if [ -n "$DRIFT" ]; then
    printf '\nWARNING: run-local.sh and %s name different scripts (< only in workflow, > only here):\n' "$WORKFLOW"
    printf '%s\n' "$DRIFT" | sed 's/^\t/> /; s/^\([^>]\)/< \1/'
    FAILED+=("drift: run-local.sh is out of sync with $WORKFLOW")
  fi
else
  echo "warning: $WORKFLOW not found; drift guard skipped" >&2
fi

# --- summary --------------------------------------------------------------------------------
printf '\n%d passed, %d failed\n' "$PASSED" "${#FAILED[@]}"
if [ "${#FAILED[@]}" -gt 0 ]; then
  printf '  %s\n' "${FAILED[@]}"
  exit 1
fi
echo "Local CI green. The GitHub run on the PR is still required before merging."
