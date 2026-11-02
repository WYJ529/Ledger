#!/usr/bin/env bash
#
# Shared helpers for frontend instrumented test runners (E2E, NFR and regression).
# Source after setting ROOT and FRONTEND_DIR.
#
# Expected layout:
#   frontend/app/src/androidTest/.../{e2e,nfr,ui,integration}/
#   frontend/app/build.gradle[.kts]

: "${ROOT:?ROOT must be set}"
: "${FRONTEND_DIR:?FRONTEND_DIR must be set}"

info() { printf '\033[34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33mWARN:\033[0m %s\n' "$*"; }
die()  { printf '\033[31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

configure_android_home() {
  if [[ -z "${ANDROID_HOME:-}" || ! -d "$ANDROID_HOME" ]]; then
    sdk_dir="$(grep -E '^sdk\.dir=' "$FRONTEND_DIR/local.properties" 2>/dev/null \
      | head -1 | cut -d= -f2- | tr -d ' "' || true)"
    [[ -n "$sdk_dir" ]] && export ANDROID_HOME="$sdk_dir"
  fi
  export PATH="${ANDROID_HOME:-}/platform-tools:$PATH"
}

pick_gradle_build_file() {
  local dir="$1"
  if [[ -f "$dir/build.gradle.kts" ]]; then
    printf '%s\n' "$dir/build.gradle.kts"
  elif [[ -f "$dir/build.gradle" ]]; then
    printf '%s\n' "$dir/build.gradle"
  else
    die "Cannot find build.gradle.kts or build.gradle in $dir."
  fi
}

resolve_test_root() {
  TEST_ROOT="$FRONTEND_DIR/app/src/androidTest"
  [[ -d "$TEST_ROOT" ]] || die "Cannot find $TEST_ROOT."
  GRADLE_TASK=":app:connectedDebugAndroidTest"
  APP_BUILD="$(pick_gradle_build_file "$FRONTEND_DIR/app")"
}

application_id() {
  grep -E '[[:space:]]applicationId[[:space:]]*=' "$APP_BUILD" \
    | head -1 | sed -E 's/.*=[[:space:]]*["'\'']([^"'\'']+)["'\''].*/\1/'
}

kotlin_test_class() {
  local file="$1" package class_name
  package="$(grep -E '^[[:space:]]*package[[:space:]]+' "$file" | head -1 \
    | sed -E 's/^[[:space:]]*package[[:space:]]+//;s/[[:space:]]*$//')"
  class_name="$(grep -E '^[[:space:]]*(class|object)[[:space:]]+' "$file" | head -1 \
    | sed -E 's/^[[:space:]]*(class|object)[[:space:]]+([A-Za-z0-9_]+).*/\2/')"
  [[ -n "$package" && -n "$class_name" ]] || die "Could not resolve test class from $file"
  printf '%s.%s' "$package" "$class_name"
}

discover_test_files() {
  local subdir="$1"
  TEST_FILES=()
  while IFS= read -r file; do
    [[ -n "$file" ]] && TEST_FILES+=("$file")
  done < <(find "$TEST_ROOT" -path "*/${subdir}/*" -name '*Test.kt' 2>/dev/null | sort)
}

ensure_backend() {
  local start_script="$ROOT/scripts/run-backend.sh"
  if [[ ! -f "$start_script" ]]; then
    info "No scripts/run-backend.sh; skipping backend startup."
    return 0
  fi
  info "Starting backend (scripts/run-backend.sh) ..."
  if [[ -x "$start_script" ]]; then
    "$start_script"
  else
    bash "$start_script"
  fi
}

wait_for_app_sign_in() {
  warn "Sign in inside the app on the emulator (not just emulator Settings > Accounts)."
  warn "  Use a disposable Ledger test account required by the selected scenario."
  warn "  When the main app screen is visible, press Enter here to run tests ..."
  read -r
}

run_instrumented_tests() {
  local suite="$1"
  shift
  local list_only=false skip_setup=false require_sign_in=false
  local -a gradle_args=()
  while (($#)); do
    case "$1" in
      --list-only) list_only=true ;;
      --skip-setup) skip_setup=true ;;
      --require-sign-in) require_sign_in=true ;;
      --) shift; gradle_args=("$@"); break ;;
      *) die "Unknown option '$1'; put Gradle arguments after --." ;;
    esac
    shift
  done
  local subdir label
  case "$suite" in
    e2e) subdir=e2e; label=E2E ;;
    nfr) subdir=nfr; label=NFR ;;
    ui) subdir=ui; label='UI regression' ;;
    integration) subdir=integration; label=integration ;;
    *) die "Unknown suite '$suite' (expected e2e, nfr, ui or integration)." ;;
  esac

  resolve_test_root
  discover_test_files "$subdir"

  if ((${#TEST_FILES[@]} == 0)); then
    if [[ "$list_only" == true ]]; then
      warn "No frontend ${label} tests exist yet; this is not a passing suite."
      return 0
    fi
    die "No frontend ${label} tests in app/src/androidTest/.../${subdir}/. See documentation/Testing.md."
  fi

  local app_id classes=() class_arg
  for file in "${TEST_FILES[@]}"; do
    classes+=("$(kotlin_test_class "$file")")
  done
  if [[ "$list_only" == true ]]; then printf '%s\n' "${classes[@]}"; return 0; fi
  class_arg="$(IFS=,; echo "${classes[*]}")"
  app_id="$(application_id)"
  [[ -n "$app_id" ]] || die "Could not resolve applicationId from $APP_BUILD."

  if [[ "$skip_setup" == false ]]; then
    ensure_backend
    bash "$ROOT/scripts/run-frontend.sh"
  fi
  configure_android_home
  command -v adb >/dev/null 2>&1 || die "adb not found."

  if [[ "$require_sign_in" == true ]]; then wait_for_app_sign_in; fi
  adb shell am force-stop "$app_id" >/dev/null 2>&1 || true

  info "Running frontend ${label} tests (${class_arg}) ..."
  (
    cd "$FRONTEND_DIR"
    ./gradlew "$GRADLE_TASK" \
      -Pandroid.testInstrumentationRunnerArguments.class="$class_arg" "${gradle_args[@]}"
  )
  info "Frontend ${label} tests finished."
}
