#!/usr/bin/env bash
set -euo pipefail
caller_dir="$PWD"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
mode="${1:-all}"
from_caller() {
  case "$1" in /*) printf '%s\n' "$1" ;; *) printf '%s/%s\n' "$caller_dir" "$1" ;; esac
}
validate_scenarios() {
  SCENARIOS="${SCENARIOS:-unlocked race locked locked_screen_on}"
  local pattern='^(unlocked|race|locked|locked_screen_on)( (unlocked|race|locked|locked_screen_on))*$'
  if ! [[ "$SCENARIOS" =~ $pattern ]]; then echo 'Invalid emulator scenarios' >&2; exit 2; fi
}
case "$mode" in
  fast) ./gradlew :shared:jvmTest :shared:testDebugUnitTest :androidApp:testDebugUnitTest --stacktrace --no-parallel ;;
  all) ./gradlew :shared:jvmTest :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --stacktrace --no-parallel ;;
  android-build) ./gradlew :androidApp:assembleDebug --stacktrace ;;
  ios)
    (cd iosApp; xcodegen generate; xcodebuild -project CalendarAlarm.xcodeproj -target iosApp -sdk iphonesimulator -configuration Debug build)
    ;;
  emulator-build)
    app_dir="$(from_caller "${2:?An isolated app checkout is required}")"
    app_dir="$(cd "$app_dir" && pwd)"
    bash "$root/scripts/emulator/inject_harness.sh" "$app_dir"
    (cd "$app_dir"; ./gradlew :androidApp:assembleDebug --stacktrace)
    ;;
  emulator-run)
    validate_scenarios
    apk="$(from_caller "${2:?APK path required}")"
    output="$(from_caller "${3:?Evidence directory required}")"
    # なぜ: APK 不在などの早期失敗を前回の陰性対照で成功と誤判定させない。
    mkdir -p "$output"
    : > "$output/results.tsv"
    test -f "$apk" || { echo 'APK does not exist' >&2; exit 1; }
    read -r -a scenarios <<< "$SCENARIOS"
    bash "$root/scripts/emulator/run_scenarios.sh" "$apk" "$output" "${scenarios[@]}"
    ;;
  emulator-evaluate)
    validate_scenarios
    output="$(from_caller "${2:?Evidence directory required}")"
    expect="${3:?Lane expectation required}"
    lane="${4:?Lane name required}"
    if [ "$expect" = stop-ui-missing ] && [ "$SCENARIOS" != unlocked ]; then echo 'Negative control requires unlocked only' >&2; exit 2; fi
    bash "$root/scripts/emulator/evaluate.sh" "$output" "$expect" "$lane" "$SCENARIOS"
    ;;
  emulator)
    : "${CI_EMULATOR_APK:?Provide the APK built from the isolated app-under-test checkout}"
    : "${CI_EMULATOR_OUT:?Provide an evidence directory}"
    CI_EMULATOR_APK="$(from_caller "$CI_EMULATOR_APK")"
    CI_EMULATOR_OUT="$(from_caller "$CI_EMULATOR_OUT")"
    outcome=success
    bash scripts/ci.sh emulator-run "$CI_EMULATOR_APK" "$CI_EMULATOR_OUT" || outcome=failure
    # なぜ: 陰性対照の期待した失敗と、途中で壊れた実行を既存 evaluator で区別する。
    SCENARIO_OUTCOME="$outcome" bash scripts/ci.sh emulator-evaluate "$CI_EMULATOR_OUT" "${EXPECT:-pass}" "${LANE:-local}"
    ;;
  *) echo "Unknown CI mode: $mode" >&2; exit 2 ;;
esac
