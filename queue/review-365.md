REVIEW | reviewer: general-purpose subagent, Fable 5.2 | authored no commit on this PR | head 26ffc3363d7bf54e4f8bb78344f981f156acd6ae | axes: pass line, design, scope, tests, body vs diff, evidence | verdict PASS | must 0 want 1 nit 1

## 指摘
- [want] PR 本文 — 本文が今の差分より古い。(1) #291 の作業（`Summary.line` の削除、`RingStatusText.kt`、`ring_status_*` の文字リソースと plurals、英語の文言、`RingStatusTextTest.kt`、baseline 309→304）が「変えたこと」に無く、`Refs #271` だけで #291 への参照も無い。(2)「SettingsScreen は引数の受け渡し 3 行だけ」は今は 4 行で、`status.line` → `ringStatusLine(status)` も入っている。(3)「テスト」の件数（116/116/192）は #291 を足す前のもの。(4)「残り: 実機での画面確認は未実施」は、実機の写真が付いた今は事実と違う。master に入るコードは正しいので must にはしないが、squash の本文に使うなら先に直すこと。
- [nit] shared/src/commonMain/kotlin/com/calendaralarm/shared/logic/RingStatus.kt:32 — KDoc が「通知だけが届く」のまま。画面の文言は「通知とタイマーは届きます」に直してあるので、コメントもそろえる。

## デザインとの比較
基準: design/project/d68/home.dc.html の 2 列目 `.hero .live`（canvas 1794658749-78c1、light で描画）。実機: agenda-ja.png（Pixel 7 Pro、head 23ec50d、light）。並べた画像: /tmp/claude-0/-home-user-calendar-alarm/4883ef84-55b1-5e04-8735-0a29b98f7ecb/scratchpad/side-365.png
- 文言（範囲内）: ミュート中のボードは無い（home の 4 列は ふだん／許可不足／予定なし／詳細）。照らす相手が無いので矛盾ではない。#238 のボードは持ち主の宿題と本文にある。ほかの 5 種類の日本語は master の文と 1 字ずつ同じで、ボードの 3 種類（準備OK・許可不足・予約なし）とも同じ。
- 置き場所（範囲内）: 「次のアラーム」の札の中、予定名の下の 1 行。ボードと同じ。
- 色と字（範囲内）: ボードは 12px・`--mu`（rgb(91,99,114)）、札の地は `--acs`。実機は bodySmall・onSurfaceVariant の灰色、札は薄い橙。見た目は合っている。この PR は `NextAlarmBanner` の見た目を変えていない。
- 行頭の丸（範囲外）: ボードは行頭に 8px の丸（OK は緑、注意 `.live.w` は赤）がある。実機には丸が無く、字が左端から始まる。master でも同じ（AgendaScreen.kt:396-402 はこの PR で触っていない）。ミュート中の丸の色はボードに決まりが無い。別 Issue 向き。
- 英語の端末で「次のアラーム」「なし」が日本語のまま（範囲外）: AgendaScreen.kt:370,377 の直書き。親 #247 の残り。状態の 1 行そのものは英語で出ている（agenda-en.png）。
- 設定の「鳴動の状態」カード: 実機で赤字（既存の `else -> warn`）で 2 行に折り返して出ている。設定のボードとは照らしていない（依頼は home の 1 行）。

## 軸ごと
- pass line: head 26ffc33 の check run 8 件。build・i18n-check・ios-build・plan・verify (fix)・verify (control-3d106817)・verify (fix-minified) は success、CodeRabbit は neutral（回数の上限）。手元: hardcoded-ja `OK (合計 304 個)` exit=0、sync-ai-rule `--check` exit=0（scratchpad/localcheck-365.txt）。
- design: 上のとおり。この PR が触った所でボードと食い違う点は 0。
- scope: 9 ファイル。#271（ALL_MUTED、許可不足の次、Android の文言）と #291（種類と数だけ返し、文言は strings.xml）に収まる。iOS は Swift 側の `RingStatusSummary` が別物で shared の `.line` を使っていない（grep で確認）ので、`line` を消しても壊れない。ios-build も success。
- tests: shared `RingStatusTest` に ALL_MUTED の優先順 6 行、Android `RingStatusTextTest` が ja 6 文・en 8 文（単数・複数）を文字で照合。`allMuted -> Kind.ALL_MUTED` を消すと落ちることは前のレビュー（e54d65b）が実行して確認済み。私は gradle を回していない（CI の build が head で success）。
- body vs diff: want 1 件（上）。優先順と文言の説明は差分と合っている。
- evidence: 実機の写真 4 枚（ja/en × 予定/設定）を取得して目で確認。文言は ja・en とも strings.xml と一致。設定の写真はアラーム音量も 0 だが 1 行は ALL_MUTED で、優先順のとおり。

## 前の指摘の状態
- e54d65b should「通知だけが届きます」→ 4a966fc で「通知とタイマーは届きます」に直した。画面とテストは直っている。KDoc だけ残り（上の nit）。
- e54d65b nit 予定の行「アラームをすべてミュート中です」との語順の違い → #272 で扱うと返答済み。未着手のまま。
- 4a966fc should 本文の古い文言 → 文言は直っている。ただし #291 を足した後の本文は更新されていない（上の want）。
- 23ec50d should `StringParityTest` が `<plurals>` を見ない → 未対応。今回の 2 件は `RingStatusTextTest` が見ているのでこの PR は止めない。
- 23ec50d nit iOS のコメント「正本は shared の RingStatus」（AgendaView.swift:372）と iOS に ALL_MUTED が無い → 差分の外。本文の「残り」に iOS は別作業とある。
- レビュースレッドは 0 件。Codex は失敗、CodeRabbit は上限で、どちらも指摘なし。

## 証拠のメモ
- 写真は head 23ec50d。今の head 26ffc33 は 23ec50d + master の merge。`git diff --stat 23ec50d 26ffc33 -- androidApp/src/main shared/src/commonMain` で画面側に当たるのは AgendaScreen.kt の 1 行（`private fun AgendaList` → `internal`）だけ。`NextAlarmBanner`・`RingStatusText.kt`・SettingsScreen.kt・strings.xml は変わっていないので、写真はそのまま使える。
- ボードは support.js 抜き・CSS を file:// に差し替えて Playwright で描いた。Web フォントの取得は確かめていないので、字の形の細かい比較はしていない。
