#!/usr/bin/env bash
# iOS の純粋な判定 (OS・画面に依存しないファイル) を Mac 上で実行して確かめる。
# シミュレータの試験ターゲットが無いので、swiftc で必要なファイルだけを束ねて走らせる。
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 同じ checkout で同時に走っても実行ファイルを奪い合わないよう、実行ごとの一時ディレクトリを使う。
out="$(mktemp -d "${TMPDIR:-/tmp}/ios-logic-test.XXXXXX")"
trap 'rm -rf "$out"' EXIT
swiftc -O -o "$out/run" \
  "$root/iosApp/iosApp/OffAppStopPlan.swift" \
  "$root/iosApp/logic-test/off-app-stop/main.swift"
"$out/run"
