# 聞き取りへの答え 2026-11-15（calendar-alarm のクラウドの会話、ritmo アカウント）

書いた時刻: 2026-11-15（UTC）。この会話はここで止まる。続きは個人アカウント側の会話が持つ。

## (a) 触ったリポジトリ

`kouiso/calendar-alarm` だけ。

## (b) 作った・push した・持っている PR と枝

| PR / 枝 | この会話がしたこと | 今の状態 |
|---|---|---|
| #398 `test/301-shared-table` | REVIEW・証跡・GATE を載せて squash merge（3bafcd4） | merge 済み。#301 は閉じた |
| #400 `docs/211-settings-audit` | 本文を直し、新しいレビュー（PASS）・証跡・GATE のあと squash merge（c26f670） | merge 済み |
| #399 `build/220-r8` | 本文を直し、新しいレビュー（PASS）・証跡・GATE のあと squash merge（910d669） | merge 済み |
| #412 `test/375-infra-error-order` | 前の担当が push した枝から、下書きの PR を開いた | 別の会話が 05:29Z ごろ merge した |
| #413 `docs/spec-manual-snooze-count` | 同上 | 同上（merge 済み） |
| #414 `docs/design-copy-78c1` | 同上 | 同上（merge 済み） |
| #396 `fix/306-title-whole-match` | 文書だけの commit を 1 つ push（c7a9895）。本文を書き直した | 別の会話が先へ進めた（PR の head は b6364b6）。リモートに枝はもう無い |
| #397 `fix/269-restore-clear` | 本文を直しただけ（commit は無い） | 最後のレビューは FAIL（must 1）。そのあとは見ていない |
| #358 `chore/cloud-notes-calendar-alarm` | メモと証跡の画像を push（最後は 84b4566） | 開いたまま（下書き、取り込まない） |
| `claude/calendar-alarm-cloud-messaging-llwve4` | この会話の枝。commit は無い | 古い master と同じ |
| `handoff/ritmo-hearing-20261115` | このファイルを置くために新しく作った | このファイルだけ |

Issue へのコメント: #307 に 2 件（1 件は係に載せさせた文、1 件は訂正 6887255912）。**訂正のほうにも誤りがある**（「iOS の一覧では勤務場所と表示される」と書いたが、iOS の一覧に種類の表示は無い）。

`wip/*-ritmo-20261115` の枝と `handoff/notes-20261115` の枝は**作っていない**（push していない物が無かったため）。

## (c) 途中の物・push していない物

- push していない物: 無い。作業場所 3 つ（master の写し、`fix/306-title-whole-match`、メモの枝）は、どれも変更なし・未 push の commit なし・stash なし（2 回確かめた）。
- 動いている job・係: 無い。#396 の実装の係は、止めの指示で、何も書き換える前に止めた。
- PR の知らせの購読: #412 #413 #414 は外した。
- 訂正の指示が届く前に、最後の引き継ぎをメモの枝へ push している（84b4566）。そのあとは、既にある枝へは push していない。

## (d) 次にやるつもりだったこと

1. #396: 決定 B の文字どおりに直す。題の規則を選べるようにし、Android は「時刻のある予定は題全体の一致」、iOS の入口（`SharedBridge.expand`）は #307 まで今までの部分一致のままにする。仕様 7.1 と本文の iOS の節を事実に合わせ、#307 に 2 回目の訂正を載せる。そのあと新しいレビュー。根拠: https://github.com/kouiso/calendar-alarm/pull/396#issuecomment-6887353719
2. #397: 再同期の待ち合わせ（`AlarmRepository.kt:711`）と起動時の配線（`CalendarAlarmApp.kt:106-122`）が無いと落ちるテストを足す。根拠: https://github.com/kouiso/calendar-alarm/pull/397#issuecomment-6887275357
3. #365・#373・#363 の順に、レビュー・証跡・GATE。#373 は `AgendaScreen.kt:275` の直書きを直してから実機で撮り直す。
4. #191・#188 は実機の写真待ち。
5. あとの直し（まだ手を付けていない）: #398 の want（生き残る変異 3 件・知らないキー）、#400 の want（`settings-rows-211.md:142`）、#399 の want（`scripts/ci.sh:54` の見張り、`run-as`）、#412 への CodeRabbit の指摘（`scripts/emulator/test_run_scenarios_retry.sh:73` はコメントにした行にも一致する）。

## 分かったこと（次の会話のために）

- このクラウドの環境は `dl.google.com` への通信を止めていて、Gradle を回せなかった。コードの確かめは hosted の CI に頼った。
- デザインのページ（VqUeceTJYaL9Z3U2JyLnNm）は、この会話からは開けなかった（別の組織の物と表示された）。repo の写し `design/project/` を使った。
- 細かい記録: メモの枝 `chore/cloud-notes-calendar-alarm` の `note/cloud-calendar-alarm-20261113.md`（最後の HANDOFF の節。#412〜#414 を「下書き・未レビュー」と書いたままで、そこは古い）。
