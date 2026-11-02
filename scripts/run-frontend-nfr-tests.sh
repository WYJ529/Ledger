#!/usr/bin/env bash
#
# Run frontend NFR tests (app/src/androidTest/.../nfr/*.kt).
#
# Manual sign-in is optional: use -RequireSignIn (PowerShell) or --require-sign-in (Bash).
# Usage: ./scripts/run-frontend-nfr-tests.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export FRONTEND_DIR="${FRONTEND_DIR:-$ROOT/frontend}"

source "$ROOT/scripts/lib/frontend-instrumented-test-common.sh"
run_instrumented_tests nfr "$@"
