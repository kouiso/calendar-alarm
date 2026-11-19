# Issue #421 行19 検証記録（Pixel 7 Pro 実機・Android 17）

検証日時: 2026-11-19 15:42–16:10 JST / アプリ `com.calendaralarm`（debug ビルド、PR #191 系）

対象行: 「おやすみモード中も鳴らす」: サイレント（アラームも止める設定）と、優先モードで「アラーム」を外した時に、ON＋許可ありでも OS が音を止めること、検査画面の「おやすみモード」の行が要対応になること（#149）

## 前提状態

- 「おやすみモードの例外」（通知ポリシーアクセス）許可済み
- アプリの「おやすみモード中も鳴らす」スイッチ ON
- アラーム音量を一時的に 1 へ（音量0だとストリーム mute の帰属が分からないため。検証後に 0 へ戻した）

## 結果（合格）

| 条件 | 検査画面の行 | OS の音の扱い |
|---|---|---|
| zen OFF（対照・音量1） | — | `STREAM_ALARM: Muted: false` |
| サイレント（`set_dnd on` → INTERRUPTION_FILTER_NONE） | 「おやすみモードでアラームが鳴りません」= 要対応 ✓ | `STREAM_ALARM: Muted: true`（再生は始まるが出力は止まる）✓ |
| 優先モード +「アラーム」除外（`set_dnd priority` + 設定画面でアラーム OFF、consolidated policy から PRIORITY_CATEGORY_ALARMS 消滅を確認） | 同上 = 要対応 ✓ | `STREAM_ALARM: Muted: true` ✓ |

- サイレント中に「テストで鳴らす（10秒後）」を実行: RingingActivity は起動し `USAGE_ALARM` の再生構成は `state:started mutedState:opPlayAudio` で立つが、`STREAM_ALARM Muted: true` のため OS 側で無音化される（`muted source:opPlayAudio portVolume` のみ。`muted player piids` には入らない）
- 監査ログは `FIRE|tm:...` — アプリ側は鳴動を試み、止めるのは OS 側という設計（`silencedByDnd` は OFF の時だけ自分で止める、ON は OS 任せ）と一致
- 補足観測: 端末の自動ルール「休日」（カレンダー連動）が優先モードを常時発動しており、`set_dnd off` 後も優先モードへ戻る。本端末の常態として記録

## 復元

- おやすみモード「アラーム」割り込みを元（許可）へ戻した
- アラーム音量 0 へ戻した
- アプリのスイッチ OFF へ戻した
- DND は自動ルール発動の優先モード（検証前と同じ常態）

## 画像

- `issue421-19-trouble-silent.annotated.png` — サイレント中の検査画面（要対応行）
- `issue421-19-trouble-priority.annotated.png` — 優先モード・アラーム除外時の検査画面（要対応行）
- `issue421-19-os-alarms-off.annotated.png` — OS 設定で「アラーム」を外した画面

## 行20/86 追加分（同日 16:14-16:16 実施）
- おやすみモード中も鳴らす=ON + オーナー自動ルール（休日/仕事, alarms=allow）で FIRE 確認
- audit: SCHEDULE→FIRE→WARN(オーバーレイ未許可,FSI=可)→STOP_TAP(画面)→DISMISS を 2 連続で記録
- 行86: POWER キーで画面消去・ロック後に鳴動 → 起きると RingingActivity 前面 → 左端ハンドルのスライドで停止 → RingingActivity 終了・鳴動通知消失
- 鳴動画面構成: 大きい時刻・「スヌーズ 10分」大ボタン・「スライドして停止」スライダー（行20 外観分。TalkBack/360x740/オーバーレイ許可時の停止画面は未検証）

## 行90 再起動テスト（audit_log 抜粋）
- `RESYNC reason=boot:android.intent.action.BOOT_COMPLETED` — 再起動直後にアプリが再同期
- `FIRE ev:6:1907:1795075567016:b0` — 「検証-再起動テスト」予定（dtstart 17:06:07）に定刻発火
- `WARN ... オーバーレイ未許可 (FSI=可): ロック解除中は通知シェードからのみ停止可能` — ロック解除後画面ONのため鳴動画面は自動全面化せず、通知の「停止」で停止を確認

## 行97 後半（手動アラーム・オフライン・権限なし）
- 前提: READ/WRITE_CALENDAR revoke + Wi-Fi/モバイルデータ OFF
- `RESYNC reason=alarm:1 updated` — 手動アラーム(sa:1 17:20)登録
- `FIRE sa:1:2026-11-19` 17:20:00 — 権限なし・オフラインで定刻発火
- 通知シェード「停止」で消去 → 通知0件。検証後に権限・ネットワーク復旧、手動アラーム削除
