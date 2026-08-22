#!/usr/bin/env bash
# Shared curl-upload helper with retry/backoff on TRANSIENT failures.
#
# Sourced by scripts/modpack/publish-curseforge.sh and publish-modrinth.sh, which
# previously each did a single unguarded curl: one blip from the platform aborted the
# workflow, and because nothing re-dispatches release-modpack.yml, that release's pack
# version was lost permanently. CurseForge in particular returns sporadic
# `HTTP 500 {"errorCode":500,"errorMessage":"An unhandled exception occurred…"}` on
# /upload-file — that alone cost us several published pack versions.
#
# Usage:
#   source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/upload-retry.sh"
#   upload_with_retry "<label>" <curl-args…>
#   # then read: $UPLOAD_HTTP_CODE, $UPLOAD_BODY
#
# `curl -sS -w '\n%{http_code}'` is supplied by the helper; pass only the headers, form
# parts and URL. The caller still decides what a non-2xx code means — the helper only
# decides what is worth RETRYING.
#
# Retry policy:
#   * 5xx            → transient server fault, retry.
#   * 000            → curl transport failure (DNS/TLS/connection reset), retry.
#   * everything else → return immediately, including 4xx.
#
# 4xx is deliberately NOT retried: a client error means our payload is wrong, so retrying
# just re-sends the same bad request and burns attempts. The historic
# `HTTP 400 errorCode 1002 "Invalid JSON"` outage (a curl -F quirk, fixed in #757) is
# exactly that case — it needed a code fix, not a retry.
#
# Tunables (env): UPLOAD_RETRY_ATTEMPTS (default 3), UPLOAD_RETRY_DELAYS (default "15 45").
# Tests set these to 1 / "0" to keep runs fast.

UPLOAD_RETRY_ATTEMPTS="${UPLOAD_RETRY_ATTEMPTS:-3}"
UPLOAD_RETRY_DELAYS="${UPLOAD_RETRY_DELAYS:-15 45}"

upload_with_retry() {
  local label="$1"; shift
  local attempt=1
  local delays response delay
  # Intentional word splitting: UPLOAD_RETRY_DELAYS is a space-separated list.
  # shellcheck disable=SC2206
  delays=($UPLOAD_RETRY_DELAYS)

  while :; do
    # `|| response=…` keeps `set -e` from aborting on curl's non-zero transport exit;
    # the sentinel 000 then routes into the retry branch below.
    response=$(curl -sS -w $'\n%{http_code}' "$@") || response=$'\n000'
    UPLOAD_HTTP_CODE=$(printf '%s' "$response" | tail -n1)
    UPLOAD_BODY=$(printf '%s' "$response" | sed '$d')

    case "$UPLOAD_HTTP_CODE" in
      000|5??) ;;            # transient — fall through to the retry logic
      *) return 0 ;;         # definitive (2xx/3xx/4xx) — hand back to the caller
    esac

    if [ "$attempt" -ge "$UPLOAD_RETRY_ATTEMPTS" ]; then
      echo "::warning::$label: transient failure (HTTP $UPLOAD_HTTP_CODE) after ${UPLOAD_RETRY_ATTEMPTS} attempt(s) — giving up."
      return 0
    fi

    delay="${delays[$((attempt - 1))]:-${delays[${#delays[@]} - 1]:-45}}"
    echo "::warning::$label: transient failure (HTTP $UPLOAD_HTTP_CODE) on attempt ${attempt}/${UPLOAD_RETRY_ATTEMPTS} — retrying in ${delay}s…"
    sleep "$delay"
    attempt=$((attempt + 1))
  done
}
