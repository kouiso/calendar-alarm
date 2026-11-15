REVIEW | reviewer: general-purpose subagent, Fable 5.2 | authored no commit on this PR | head d67dbb492e99c7faa2bf60bb74c5a467e4617f1e | axes: pass line, design, scope, tests, body vs diff, evidence | verdict FAIL | must 1 want 4 nit 3

## 指摘
- [must] CI `build`（job 132757479044）— この head で failure。`TroubleshootingFlagTest.notificationsOffIsFlaggedAndTapOpensAppNotificationSettings` が `TroubleshootingFlagTest.kt:54` の待ち（5 秒）で `ComposeTimeoutException`（266 件中 1 件）。PR が触っていないテストで、同じ待ちを使う残り 2 件は通ったので時間切れの揺れの見込みだが、未確認。再実行して緑を確かめる。また落ちるなら master 2dce06b と比べる。
- [want] doc/spec/kmp-replacement.md:258,261 — フォント「対象外（決定）」と iOS「(決定)」。#154 にも PR にも持ち主の決定の記録が無い。解決済みスレッド r4570747205 で「判断待ち」に直した（afa573b）あと、aecf600 で「決定」に戻った。未決なら「判断待ち（案）」と書く。**管理役の判断が要る**（未決なら must 相当）。
- [want] doc/spec/kmp-replacement.md:322-324 — #419 が書いた行番号がこの PR の追加でずれる。`AlarmService.kt:391-392`→392-393、`SettingsRepository.kt:186`→189、`SettingsScreen.kt:1411`→1426、`RingingOverlay.kt:237`→248。
- [want] PR 本文 — 「今の head（506fc3a）251 件、hardcoded-ja 309」が古い。今は d67dbb4（master のマージ 2 回）、CI は 266 件、hardcoded-ja は 304。
- [want] RingingActivity.kt:402 — `maxHeight - 170.dp` が OFF の値のまま。高さ 740 で文字盤が 170dp（盤は 176）。本文の「残り」に有る。
- [nit] RingingActivity.kt:146 — `initial = false` で、ON でも最初の 1 フレームがスライダーになり得る（未確認）。
- [nit] RingingOverlay.kt:312 — 大きい停止は押した時の見た目の変化が無い。:164 は OFF の太字の付け方が変わった。
- [nit] values/strings.xml:163 — キー名 `settings_large_stop_button` が中身と合わない。

## デザインとの比較（盤 p154）
- 2 列目・鳴動画面: 高さ 124/124、角丸 32、24・600、間 12/12、スヌーズ rgba(255,255,255,.08)・#ECEEF3、停止はテーマの色、スヌーズが上・停止が下、「スヌーズ時間を選ぶ」とスライド無し、文字盤 176・内 140・時刻 66、予定名 22・600。盤を描いて測った値とコードの値が合う。実機では未確認。
- 違い（範囲内）: 高さ 740 で文字盤 170dp（want）。
- 違い（範囲外・前から）: 文字盤の線の濃さ（盤 .30、コード .25/.15）。
- オーバーレイ: ボタンの値は合う。円・時刻の書体・予定名の太さ・日付の pill・「10分後に開始」は前からの違いで本文に有る。停止の色が #BDBDFF/#26247A の固定でテーマに従わない点は本文に無い（前から・範囲外）。
- 4 列目・設定: 実機（506fc3a）の画像で、独立したカード、見出し、スイッチ 1 つ、説明文なし、テーマの上、高さ約 64dp が盤と合う。
- 持ち主の判断待ち（採点しない）: 「文字の大きさ」「鳴動画面の背景」「案B の鍵」の行は無い。無料か Pro か（今は全部無料）。フォント。iOS（大きいボタンは Android だけ）。

## 観点
- pass line: 未達。head の `build` が赤、verify 3 本は実行中。
- design: この PR が触った所で盤と違うのは文字盤 170dp だけ。
- scope: 15 ファイルとも #154 の中。DataStore のキー `large_buttons`（既定 false）を足しただけ。
- tests: 新しい 6 ファイルは master ではコンパイルで落ちる。設定→鳴動画面は `LargeButtonRingingLayoutTest`、オーバーレイは `show` の引数に既定値が無く、渡し忘れがコンパイルで分かる。OFF の値のテストも有る。ここでは Gradle を動かしていない。
- body vs diff: 数値と文言は合う。head・件数が古い（want）。
- evidence: 実機は 506fc3a の設定 2 枚だけ。この head の画像と、大きいボタンの鳴動画面・オーバーレイの画像は無い。

## マージの解決（3dc26d8）
`SettingsScreen.kt` は master に 64 行を足しただけ（numstat 64/0）。競合の印なし。master の `IconChipRow`、`RingStatusCard(muteAll)`、`ringStatusLine`、`InviteFilterRow` は残っている。CI の build は compile を通り unit test まで進んだ。

## 前の指摘
- r7（94d675b）must 1・2、want 1・2、nit 1: 直っている。must 3（verify (fix) の赤）: この head では実行中。差分に keyguard・finish の変更は無い。want 3、nit 2・3: 残っている。
- レビュースレッド 2 件は解決済み、未解決 0。コメント 25 件を読んだ（bot の本文は飛ばした）。

## 実機で確かめること
1. ON の鳴動画面（ロック中と解除中）: スヌーズと停止が同じ大きさ、停止が下、選ぶ無し、文字が切れない、文字盤の大きさ
2. ON のオーバーレイ: 同じ見た目、停止 1 回で止まる
3. 停止のあとロック画面に戻る（UNLOCKED_BY_STOP が出ない）
4. OFF の鳴動画面とオーバーレイが今までと同じ
5. この head の設定の行 OFF/ON、TalkBack の読み上げ
6. ON で開いた直後にスライダーが一瞬出ないか

## 証拠
- check runs（1 回見直した）: build failure、i18n-check success、plan success、CodeRabbit neutral、verify (fix)・(fix-minified)・(control-3d106817) in_progress
- 手元: `hardcoded-ja/check.mjs` exit 0（304 個）、`sync-ai-rule.mjs --check` exit 0 → `localcheck-188.txt`
- `side-188.png`: 盤 4 列目｜実機 506fc3a OFF・ON｜盤 2 列目｜コードの値の表。盤は代わりの書体
