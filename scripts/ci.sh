#!/usr/bin/env bash
set -euo pipefail
# なぜ: テスト名が日本語なので、UTF-8 でないロケール (クラウドの C など) では Kotlin の
# クラスファイル名が書けず、ビルドが InvalidPathException で落ちる (#297)
case "${LC_ALL:-${LANG:-}}" in
  *[Uu][Tt][Ff]-8*|*[Uu][Tt][Ff]8*) ;;
  *) export LC_ALL=C.UTF-8 LANG=C.UTF-8 ;;
esac
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
  shared) ./gradlew :shared:jvmTest --stacktrace --no-parallel ;;
  fast) ./gradlew :shared:jvmTest :shared:testDebugUnitTest :androidApp:testDebugUnitTest --stacktrace --no-parallel ;;
  all) ./gradlew :shared:jvmTest :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --stacktrace --no-parallel ;;
  android-build) ./gradlew :androidApp:assembleDebug --stacktrace ;;
  # なぜ: 翻訳漏れ・API 版・権限まわりの新しい指摘を止める。今ある指摘は androidApp/lint-baseline.xml に固定済み (#300)
  lint) ./gradlew :androidApp:lintDebug --stacktrace ;;
  i18n-check)
    # なぜ: 画面の日本語を Kotlin に直接書く増加を止める (#289)。Gradle 不要で数秒。先に検査器自身のテストを回す
    node --test scripts/hardcoded-ja/check.test.mjs
    node scripts/hardcoded-ja/check.mjs
    ;;
  ios)
    (cd iosApp; xcodegen generate; xcodebuild -project CalendarAlarm.xcodeproj -target iosApp -sdk iphonesimulator -configuration Debug build)
    ;;
  ios-logic)
    # なぜ: iOS の XCTest はシミュレータが要る。画面と SharedKit に依存しない計算だけを macOS の swiftc で検査する
    out="$(mktemp -d)"
    swiftc iosApp/iosApp/ring-history.swift iosApp/logic-test/main.swift -o "$out/ring-history-test"
    "$out/ring-history-test"
    ;;
  emulator-build)
    app_dir="$(from_caller "${2:?An isolated app checkout is required}")"
    app_dir="$(cd "$app_dir" && pwd)"
    # なぜ: E2E_MINIFIED=true の時だけ、release と同じ R8 (難読化あり) の e2eMinified variant で停止 E2E を回す (#220)。
    # debug variant は R8 が debug モードで動き名前が変わらないため使わない。未設定は従来どおり debug
    variant=debug
    gradle_task=assembleDebug
    if [ "${E2E_MINIFIED:-}" = true ]; then variant=e2eMinified; gradle_task=assembleE2eMinified; fi
    bash "$root/scripts/emulator/inject_harness.sh" "$app_dir" "$variant"
    (cd "$app_dir"; ./gradlew ":androidApp:$gradle_task" --stacktrace)
    # なぜ: 受け渡しの切れで minify なしのまま緑になり門が素通りになるのを防ぐ。mapping が無ければ R8 は動いていない
    if [ "$variant" = e2eMinified ]; then
      test -s "$app_dir/androidApp/build/outputs/mapping/e2eMinified/mapping.txt" || { echo 'R8 did not run: mapping.txt is missing for e2eMinified' >&2; exit 1; }
    fi
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
