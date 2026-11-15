#!/usr/bin/env bash
# エミュレータ E2E 用の操作口 (EmuHarnessReceiver) を、指定チェックアウトの
# androidApp/src/<variant> へ差し込む (variant は第2引数。省略は debug、R8 後の E2E は e2eMinified)。CI のビルド直前にだけ実行し、コミットはしない。
# 修正前のコミット (陰性対照) にも同じ操作口を入れて同一シナリオを流すための仕組み。
set -euo pipefail

APP_DIR=${1:?usage: inject_harness.sh <app checkout dir>}
HERE=$(cd "$(dirname "$0")" && pwd)
VARIANT=${2:-debug}
case "$VARIANT" in debug|e2eMinified) ;; *) echo "invalid variant: $VARIANT" >&2; exit 2 ;; esac
DEBUG_DIR="$APP_DIR/androidApp/src/$VARIANT"

if [ -e "$DEBUG_DIR/AndroidManifest.xml" ]; then
  echo "debug manifest already exists in $DEBUG_DIR; refusing to overwrite" >&2
  exit 1
fi
mkdir -p "$DEBUG_DIR/java/com/calendaralarm/emu"
cp "$HERE/harness/AndroidManifest.xml" "$DEBUG_DIR/AndroidManifest.xml"
cp "$HERE/harness/EmuHarnessReceiver.kt" "$DEBUG_DIR/java/com/calendaralarm/emu/EmuHarnessReceiver.kt"
echo "harness injected into $DEBUG_DIR"
