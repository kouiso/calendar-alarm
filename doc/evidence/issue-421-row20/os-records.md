# 行20 鳴動画面（大ボタン/小画面/TalkBack/オーバーレイ）検証記録

- 端末: Pixel 7 Pro / Android（実機）
- 検証日時: 2026-11-19 20:12〜20:18 JST
- 実施内容:
  - 「鳴動画面を大きいボタンにする」ON → 10秒テスト鳴動 → 大時刻・「スヌーズ 5分」「停止」の大ボタン表示を確認（issue421-20-bigbtn-ring）
  - 大ボタン「停止」タップ → audit `STOP_TAP(画面)` → `DISMISS`（20:12:44）
  - オーバーレイ権限付与後の鳴動 → audit `OVERLAY_SHOWN: 停止UIをオーバーレイで表示`（20:14:15）→ 画面停止成功
  - 表示密度 480dpi（360dp 幅）でホーム＋鳴動画面を確認 → 文字は大きく可読（issue421-20-360-home / 360-ring）
  - TalkBack 有効時の鳴動 → 全要素（時刻・件名・スヌーズ・停止）がテキスト付きで露出、`STOP_TAP(画面)`→`DISMISS` で停止可能（issue421-20-talkback は TalkBack ON 通知の緑枠フォーカスを撮影）
- 監査ログ抜粋:
  - `265|FIRE|tm:...` → `266|STOP_TAP|画面` → `268|DISMISS`（20:12:44, 大ボタン）
  - `271|FIRE` → `272|OVERLAY_SHOWN|停止UIをオーバーレイで表示` → `273|STOP_TAP(画面)` → `274|DISMISS`（20:14:15, オーバーレイ許可時）
  - `284|FIRE` → `285|WARN|オーバーレイ未許可(FSI=可)` → `286|STOP_TAP(画面)` → `287|DISMISS`（20:17:08-39, TalkBack ON・overlay default）
- 復旧: TalkBack OFF・density 420 に戻し・SYSTEM_ALERT_WINDOW=default・大ボタン OFF
- 備考: TalkBack の「新しくなりました」チュートリアル・通知許可ダイアログを通過して検証。スヌーズ再鳴は行89で検証済み
