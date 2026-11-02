#!/usr/bin/env bash
# Supplemental tests; these are not the course end-to-end acceptance suite.
# Usage: bash scripts/run-frontend-regression-tests.sh [ui|integration] [options]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export FRONTEND_DIR="${FRONTEND_DIR:-$ROOT/frontend}"
suite="${1:-ui}"
if (($#)); then shift; fi
case "$suite" in ui|integration) ;; *) echo 'Expected ui or integration.' >&2; exit 1 ;; esac
source "$ROOT/scripts/lib/frontend-instrumented-test-common.sh"
run_instrumented_tests "$suite" "$@"
