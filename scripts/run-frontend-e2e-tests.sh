#!/usr/bin/env bash
#
# Run frontend E2E tests (app/src/androidTest/.../e2e/*.kt).
#
# Manual sign-in is optional: use -RequireSignIn (PowerShell) or --require-sign-in (Bash).
# Usage: ./scripts/run-frontend-e2e-tests.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export FRONTEND_DIR="${FRONTEND_DIR:-$ROOT/frontend}"

source "$ROOT/scripts/lib/frontend-instrumented-test-common.sh"
run_instrumented_tests e2e "$@"
