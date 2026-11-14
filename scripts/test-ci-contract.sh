#!/usr/bin/env bash
set -euo pipefail
if ! command -v task >/dev/null 2>&1; then
  {
    echo "test-ci-contract: task (go-task) が PATH にありません。契約テストは fixture の中で task ci / ci:fast / ci:ios を呼ぶので、実物が要ります。"
    echo "入れ方: macOS は brew install go-task、どの OS でも go install github.com/go-task/task/v3/cmd/task@latest、"
    echo "または https://taskfile.dev/installation/ の手順。詳しくは doc/ci-local.md。"
  } >&2
  exit 1
fi
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture="$(mktemp -d)"
# 失敗した時に、どの行の期待が外れたかと、その直前に fixture へ書かれた出力を出す。
# 出力は fixture の中のファイルなので、EXIT で消す前に出さないと CI には "exit code 1" しか残らない (#381 のレビュー)
on_exit() {
  local rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "test-ci-contract: FAILED (exit $rc) at line ${fail_line:-$LINENO}: ${fail_cmd:-unknown}" >&2
    echo "--- last task/ci.sh output ---" >&2
    tail -n 60 "$fixture/output" >&2 2>/dev/null || true
    echo "--- recorded commands ---" >&2
    cat "$fixture/commands" >&2 2>/dev/null || true
  fi
  rm -rf "$fixture"
}
# 「失敗するはずが成功した」側の確認 (if ...; then expected_failure; fi) は ERR を通らないので、呼び出し行をここで残す
expected_failure() { fail_line=${BASH_LINENO[0]}; fail_cmd='succeeded but was expected to fail'; exit 1; }
trap 'fail_line=$LINENO; fail_cmd=$BASH_COMMAND' ERR
trap on_exit EXIT
mkdir -p "$fixture/scripts/emulator" "$fixture/app under test" "$fixture/iosApp" "$fixture/bin"
cp "$root/scripts/ci.sh" "$fixture/scripts/ci.sh"
cp "$root/Taskfile.yml" "$fixture/Taskfile.yml"
cp "$root/scripts/emulator/evaluate.sh" "$fixture/scripts/emulator/evaluate.sh"
cp "$root/scripts/emulator/inject_harness.sh" "$fixture/scripts/emulator/inject_harness.sh"
cp -R "$root/scripts/emulator/harness" "$fixture/scripts/emulator/harness"
cat > "$fixture/gradlew" <<'GRADLE'
#!/usr/bin/env bash
printf '%s\n' "${0##*/} $*" >> "$COMMAND_LOG"
if [ "${GRADLE_FAIL:-false}" = true ]; then exit 42; fi
GRADLE
chmod +x "$fixture/gradlew"
cp "$fixture/gradlew" "$fixture/app under test/gradlew"
cat > "$fixture/scripts/emulator/run_scenarios.sh" <<'DEVICE'
#!/usr/bin/env bash
printf '%s\n' "run-scenarios $*" >> "$COMMAND_LOG"
if [ -n "${DEVICE_REPORT:-}" ]; then mkdir -p "$2"; cp "$DEVICE_REPORT" "$2/results.tsv"; fi
exit "${DEVICE_RC:-0}"
DEVICE
for tool in xcodegen xcodebuild; do cp "$fixture/gradlew" "$fixture/bin/$tool"; done
export PATH="$fixture/bin:$PATH" COMMAND_LOG="$fixture/commands"
(cd "$fixture"; task ci) > "$fixture/output" 2>&1
expected='gradlew :shared:jvmTest :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --stacktrace --no-parallel'
test "$(cat "$COMMAND_LOG")" = "$expected"
: > "$COMMAND_LOG"
(cd "$fixture"; task ci:fast) > "$fixture/output" 2>&1
test "$(cat "$COMMAND_LOG")" = 'gradlew :shared:jvmTest :shared:testDebugUnitTest :androidApp:testDebugUnitTest --stacktrace --no-parallel'
 : > "$COMMAND_LOG"
(cd "$fixture"; task ci:ios) > "$fixture/output" 2>&1
printf '%s\n' 'xcodegen generate' 'xcodebuild -project CalendarAlarm.xcodeproj -target iosApp -sdk iphonesimulator -configuration Debug build' > "$fixture/ios-expected"
diff -u "$fixture/ios-expected" "$COMMAND_LOG"
if (cd "$fixture"; GRADLE_FAIL=true bash scripts/ci.sh all) > "$fixture/output" 2>&1; then expected_failure; fi
: > "$COMMAND_LOG"
(cd "$fixture"; bash scripts/ci.sh emulator-build "$fixture/app under test") > "$fixture/output" 2>&1
test -f "$fixture/app under test/androidApp/src/debug/AndroidManifest.xml"
test "$(cat "$COMMAND_LOG")" = 'gradlew :androidApp:assembleDebug --stacktrace'
: > "$COMMAND_LOG"
if (cd "$fixture"; bash scripts/ci.sh emulator-build "$fixture/app under test") > "$fixture/output" 2>&1; then expected_failure; fi
test ! -s "$COMMAND_LOG"
mkdir -p "$fixture/results"
printf 'fixture-only\n' > "$fixture/app.apk"
export SCENARIOS='unlocked race locked locked_screen_on'
(cd "$fixture"; bash scripts/ci.sh emulator-run app.apk results)
test "$(tail -1 "$COMMAND_LOG")" = "run-scenarios $fixture/app.apk $fixture/results unlocked race locked locked_screen_on"
: > "$COMMAND_LOG"
if (cd "$fixture"; SCENARIOS=$'unlocked\ninvalid' bash scripts/ci.sh emulator-run app.apk results) > "$fixture/output" 2>&1; then expected_failure; fi
test ! -s "$COMMAND_LOG"
for scenario in unlocked race locked locked_screen_on; do printf '%s\tPASS\tOK\n' "$scenario"; done > "$fixture/results/results.tsv"
(cd "$fixture"; SCENARIO_OUTCOME=success bash scripts/ci.sh emulator-evaluate results pass fix) > "$fixture/output" 2>&1
if (cd "$fixture"; SCENARIO_OUTCOME=failure bash scripts/ci.sh emulator-evaluate results pass fix) > "$fixture/output" 2>&1; then expected_failure; fi
printf 'unlocked\tPASS\tOK\n' > "$fixture/results/results.tsv"
if (cd "$fixture"; SCENARIO_OUTCOME=success bash scripts/ci.sh emulator-evaluate results pass fix) > "$fixture/output" 2>&1; then expected_failure; fi
printf 'unlocked\tFAIL\tSTOP_UI_MISSING\n' > "$fixture/results/results.tsv"
(cd "$fixture"; SCENARIOS=unlocked SCENARIO_OUTCOME=failure bash scripts/ci.sh emulator-evaluate results stop-ui-missing control) > "$fixture/output" 2>&1
if (cd "$fixture"; SCENARIOS=unlocked bash scripts/ci.sh emulator-run missing.apk results) > "$fixture/output" 2>&1; then expected_failure; fi
if (cd "$fixture"; SCENARIOS=unlocked SCENARIO_OUTCOME=failure bash scripts/ci.sh emulator-evaluate results stop-ui-missing control) > "$fixture/output" 2>&1; then expected_failure; fi
printf 'unlocked\tFAIL\tOTHER_ERROR\n' > "$fixture/results/results.tsv"
if (cd "$fixture"; SCENARIOS=unlocked SCENARIO_OUTCOME=failure bash scripts/ci.sh emulator-evaluate results stop-ui-missing control) > "$fixture/output" 2>&1; then expected_failure; fi
if (cd "$fixture"; bash scripts/ci.sh unknown) > "$fixture/output" 2>&1; then expected_failure; fi
for scenario in unlocked race locked locked_screen_on; do printf '%s\tPASS\tOK\n' "$scenario"; done > "$fixture/device-report.tsv"
export CI_EMULATOR_APK="$fixture/app.apk" CI_EMULATOR_OUT="$fixture/results" DEVICE_REPORT="$fixture/device-report.tsv"
(cd "$fixture"; task ci:emulator) > "$fixture/output" 2>&1
if (cd "$fixture"; DEVICE_RC=42 task ci:emulator) > "$fixture/output" 2>&1; then expected_failure; fi
printf 'unlocked\tFAIL\tSTOP_UI_MISSING\n' > "$fixture/device-report.tsv"
(cd "$fixture"; SCENARIOS=unlocked EXPECT=stop-ui-missing DEVICE_RC=42 task ci:emulator) > "$fixture/output" 2>&1
unset DEVICE_REPORT
mkdir -p "$fixture/verifier/scripts" "$fixture/cloud app"
cp "$fixture/scripts/ci.sh" "$fixture/verifier/scripts/ci.sh"
cp -R "$fixture/scripts/emulator" "$fixture/verifier/scripts/emulator"
cp "$fixture/gradlew" "$fixture/cloud app/gradlew"
(cd "$fixture"; bash verifier/scripts/ci.sh emulator-build 'cloud app') > "$fixture/output" 2>&1
test -f "$fixture/cloud app/androidApp/src/debug/AndroidManifest.xml"
echo 'OK actual Task profiles, one Gradle invocation, harness overwrite rejection, scenario arguments and real evaluator safety'
