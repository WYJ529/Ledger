#!/usr/bin/env bash
#
# Run backend interface test suites with coverage, in order:
#   1. tests/mock/
#   2. tests/no-mock/
#   3. tests/mock/ + tests/no-mock/
#
# Assumes a Node.js + TypeScript project using Jest, with this layout:
#   backend/
#   ├── src/              source code
#   └── tests/
#       ├── mock/         interface tests with mocks
#       ├── no-mock/      interface tests without mocks
#       └── ...           other test directories
# Usage:
#   ./scripts/run-backend-interface-tests.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$ROOT/backend"
MOCK_DIR="$BACKEND_DIR/tests/mock"
NO_MOCK_DIR="$BACKEND_DIR/tests/no-mock"

info()  { printf '\033[34m==>\033[0m %s\n' "$*"; }
warn()  { printf '\033[33mWARN:\033[0m %s\n' "$*"; }
die()   { printf '\033[31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

require_dir() {
  [[ -d "$1" ]] || die "Missing directory: $1"
}

has_test_files() {
  find "$1" -name "$2" -print -quit 2>/dev/null | grep -q .
}

run_interface_tests() {
  local label="$1"
  local coverage_dir="$2"
  shift 2
  local -a test_paths=("$@")

  info "Running $label interface tests with coverage (${test_paths[*]})..."
  mkdir -p "$coverage_dir"
  npm test -- "${test_paths[@]}" --runInBand --coverage --coverageDirectory="$coverage_dir" --json --outputFile="$coverage_dir/results.json"
  echo
}

# ---------------------------------------------------------------------------
# Prerequisites
# ---------------------------------------------------------------------------
command -v node >/dev/null 2>&1 || die "Node.js not found."
command -v npm  >/dev/null 2>&1 || die "npm not found."

require_dir "$BACKEND_DIR"
[[ -f "$BACKEND_DIR/package.json" ]] || die "Missing $BACKEND_DIR/package.json"
require_dir "$BACKEND_DIR/src"
require_dir "$BACKEND_DIR/tests"

if ! node -e "const p=require('$BACKEND_DIR/package.json'); process.exit(p.scripts?.test ? 0 : 1)"; then
  die "No \"test\" script found in $BACKEND_DIR/package.json (expected Jest)."
fi

has_mock_tests=false
has_no_mock_tests=false

if [[ -d "$MOCK_DIR" ]] && has_test_files "$MOCK_DIR" '*.test.ts'; then
  has_mock_tests=true
elif [[ -d "$MOCK_DIR" ]]; then
  warn "Skipping mocked tests: no files in tests/mock/ (add *.test.ts when ready)."
else
  warn "Skipping mocked tests: tests/mock/ not found yet."
fi

if [[ -d "$NO_MOCK_DIR" ]] && has_test_files "$NO_MOCK_DIR" '*.test.ts'; then
  has_no_mock_tests=true
elif [[ -d "$NO_MOCK_DIR" ]]; then
  die "No test files found in $NO_MOCK_DIR (expected *.test.ts)."
else
  die "Missing directory: $NO_MOCK_DIR"
fi

if [[ "$has_mock_tests" == false && "$has_no_mock_tests" == false ]]; then
  die "No interface tests found under backend/tests/."
fi

if [[ ! -d "$BACKEND_DIR/node_modules" ]]; then
  info "Installing dependencies..."
  ( cd "$BACKEND_DIR" && npm ci --include=dev )
fi

# ---------------------------------------------------------------------------
# Run interface test phases
# ---------------------------------------------------------------------------
(
  cd "$BACKEND_DIR"

  if [[ "$has_mock_tests" == true ]]; then
    run_interface_tests "mocked" "coverage/mock" "tests/mock/"
  fi

  if [[ "$has_no_mock_tests" == true ]]; then
    run_interface_tests "no-mock" "coverage/no-mock" "tests/no-mock/"
  fi

  if [[ "$has_mock_tests" == true && "$has_no_mock_tests" == true ]]; then
    run_interface_tests "all interface" "coverage/interface" "tests/mock/" "tests/no-mock/"
  fi
)

info "All backend interface test suites finished."
