#!/usr/bin/env bash
# 通知で届く予定がこの先にあるかの判定 (Models.swift の notificationDeliveryAhead) を macOS で走らせる。
# 端末のタイムゾーンと暦で結果が変わる関数なので、西暦の UTC-7 / UTC+9 と和暦の3通りで流す
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
# なぜ: 複数ファイルで最上位の文を書けるのは main.swift だけ
cp "$root/iosApp/logic-test/notify-ahead-main.swift" "$tmp/main.swift"
swiftc -o "$tmp/notify-ahead-test" "$root/iosApp/iosApp/Models.swift" "$tmp/main.swift"
for zone in America/Los_Angeles Asia/Tokyo; do
  echo "== TZ=$zone (gregorian)"
  TZ="$zone" "$tmp/notify-ahead-test"
  echo "== TZ=$zone (japanese calendar)"
  TZ="$zone" "$tmp/notify-ahead-test" -AppleLocale ja_JP@calendar=japanese -AppleCalendar japanese
done
