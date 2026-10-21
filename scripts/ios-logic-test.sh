#!/usr/bin/env bash
# iOS の純粋な判定 (OS・画面に依存しないファイル) を Mac 上で実行して確かめる。
# シミュレータの試験ターゲットが無いので、swiftc で必要なファイルだけを束ねて走らせる。
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$root/build/ios-logic-test"
mkdir -p "$out"
swiftc -O -o "$out/run" \
  "$root/iosApp/iosApp/OffAppStopPlan.swift" \
  "$root/iosApp/logic-test/main.swift"
"$out/run"
