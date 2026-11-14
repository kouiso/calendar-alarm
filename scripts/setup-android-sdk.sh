#!/usr/bin/env bash
# なぜ: クラウドの作業場には Android SDK が無く、`scripts/ci.sh fast` が SDK location not found で止まる (#298, #251)。
# 入れる物: commandline-tools (版と sha256 を固定)、compileSdk の platform、AGP が使う build-tools。
# 何度流しても安全: 入っている物は飛ばす。対話は要らない。秘密は使わない。
# 使い方: bash scripts/setup-android-sdk.sh   (入れ先は ANDROID_HOME、無ければ ANDROID_SDK_ROOT、無ければ ~/android-sdk)
set -euo pipefail

CMDLINE_TOOLS_BUILD=11076708
CMDLINE_TOOLS_SHA256=2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258
# なぜ: AGP 8.10.1 (gradle/libs.versions.toml) の既定の build-tools が 35.0.0。AGP を上げたらここも見直す
BUILD_TOOLS_VERSION=35.0.0

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"

# なぜ: platform の版を二重管理しない。libs.versions.toml の compileSdk を正本にする
compile_sdk="$(sed -n 's/^compileSdk *= *"\([0-9][0-9]*\)".*/\1/p' "$root/gradle/libs.versions.toml")"
[ -n "$compile_sdk" ] || { echo 'compileSdk not found in gradle/libs.versions.toml' >&2; exit 1; }

for tool in java curl unzip sha256sum; do
  command -v "$tool" >/dev/null || { echo "Required tool missing: $tool" >&2; exit 1; }
done

sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$sdkmanager" ]; then
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' EXIT
  curl -fsSL -o "$work/cmdline-tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"
  # なぜ: 取得物をそのまま実行しない。固定した sha256 と合わなければ止める
  echo "${CMDLINE_TOOLS_SHA256}  $work/cmdline-tools.zip" | sha256sum -c - >/dev/null \
    || { echo 'commandline-tools sha256 mismatch' >&2; exit 1; }
  unzip -q "$work/cmdline-tools.zip" -d "$work/unzipped"
  mkdir -p "$sdk/cmdline-tools"
  rm -rf "$sdk/cmdline-tools/latest.partial"
  mv "$work/unzipped/cmdline-tools" "$sdk/cmdline-tools/latest.partial"
  mv "$sdk/cmdline-tools/latest.partial" "$sdk/cmdline-tools/latest"
fi

if [ -d "$sdk/platforms/android-$compile_sdk" ] && [ -d "$sdk/build-tools/$BUILD_TOOLS_VERSION" ]; then
  echo "Android SDK already has platform $compile_sdk and build-tools $BUILD_TOOLS_VERSION: $sdk"
else
  # なぜ: yes が SIGPIPE で 141 を返しても pipefail で落ちないようにする
  { yes || true; } | "$sdkmanager" --sdk_root="$sdk" --licenses >/dev/null
  "$sdkmanager" --sdk_root="$sdk" "platforms;android-$compile_sdk" "build-tools;$BUILD_TOOLS_VERSION"
fi

# なぜ: 既にある local.properties は人が書いた物かもしれないので上書きしない (git 管理外)
if [ ! -f "$root/local.properties" ]; then
  printf 'sdk.dir=%s\n' "$sdk" > "$root/local.properties"
  echo "Wrote $root/local.properties"
fi

echo "Done. Next: export ANDROID_HOME=$sdk && bash scripts/ci.sh fast"
