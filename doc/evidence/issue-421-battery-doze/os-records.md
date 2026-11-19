# Issue #421 行96 — OS/DB 記録（バッテリーセーバー+画面消灯 30分）

端末: Pixel 7 Pro・音量0

- `settings put global low_power 1` → `mSettingBatterySaverEnabled=true`
- `input keyevent 26` で画面消灯 → `mWakefulness=Dozing`
- 予定 `検証-절전테스트`(event 1923) 20:13 開始・トリガー 20:08:46・`PENDING` 確認後 Doze で約28分放置
- 監査ログ: `FIRE|ev:6:1923:1795086826499:b5|2026-11-19 20:08:46`（定刻ぴったり・ズレなし）
- 画面を起こすと鳴動画面（20:08/検証-절전테스트）が前面、`alarm_vib` 系通知も残存
- 後始末: 通知「停止」で DISMISS・low_power 0 復旧（`mSettingBatterySaverEnabled=false`）・予定削除
