# 引き継ぎ 2026-11-14（クラウドの担当。Mac の担当の分は同じフォルダの handoff-20261114.md）

書いた時刻: 2026-11-14T14:25Z（`date` の値）。この会話はここで閉じる。続きはこのファイルから始める。

## 目的（1行）

既存の PR を関門（レビュー・証跡・検査・GATE）を通して片付け、全画面をデザインの正本どおりに作る。終わりの形は「持ち主待ちと実機待ちだけが残り、全部が一覧になっている」状態。

## 今日 merge したもの

#369 #372 #374 #376 #377 #378 #379 #380 #381 #387 #389 #390 #391 #392 #393 #394
（どれも、作者でない人の REVIEW（PASS・must 0）、証跡の画像、LOCAL-CHECK、GATE のコメントを付けて、head の sha 指定の squash で merge した）

今日閉じた issue: #124 #144 #252 #253 #289 #298 #299 #300 ほか。新しく作った issue: #401（依存の古さの知らせ）、#402〜#410（デザイン差分）。

## 開いている PR と次の一手

| PR | head | 状態 | 次の一手 |
|---|---|---|---|
| #398 | 5336dcb | REVIEW PASS（must 0）。CI 全部 success（verify (fix) も） | REVIEW のコメントがまだ GitHub に無い。`queue/review398-r.md` を投稿 → LOCAL-CHECK（証跡 `evidence/398-5336dcb.png`、notes の commit 16118b5）→ GATE → squash merge。Closes #301 は確かめ方を満たす |
| #396 | a3fef8c | 前のレビュー FAIL（must 1: NBSP）。直した（`Char.isWhitespace` で畳む、ooo は決定 B に無いので外した） | 新しい本文（`queue/body396-fix.md`）を PATCH、CodeRabbit のスレッド 4573679730 に返事（`queue/reply396-nbsp.md`）、#307 にコメント（`queue/reply396-ios307.md`）。そのあと新しいレビュー |
| #397 | e5818f8 | 前のレビュー FAIL（must 2）。直した（Refs #269 に変更、manifest の配線のテスト、再同期の待ち合わせ） | 本文（`queue/body397-fix.md`）を PATCH して新しいレビュー |
| #399 | fe8be93 | 前のレビュー FAIL（must 1: minify の検査が難読化されていなかった）。直した（release と同じ設定の `e2eMinified` を作り、停止 E2E をそれで回す） | 本文（`queue/body399-fix.md`）を PATCH、返事（`queue/reply399-must1.md`）、hosted の verify (fix-minified) の結果を見て新しいレビュー |
| #400 | 05458bf | 前のレビュー FAIL（must 1: 記録の無い決定）。直した（（未確認）にした、want 1〜5 も反映） | 本文（`queue/body400-fix.md`）を PATCH して新しいレビュー |
| #191 | d68d92e | 前のレビュー FAIL（must 4）。直した（「許可を開く」は板の塗りつぶしボタン、三日月のアイコン、`.prev` の箱、見逃しの通知のテスト） | 新しいレビュー。着地には実機の写真が要る（DEVICE）。持ち主への質問 Q11 あり（下） |
| #188 | 506fc3a | 前のレビュー FAIL（must 3）。直した（本文のテストの説明、オーバーレイの間 12、E2E の失敗の調べ） | 新しいレビュー。head の verify (fix) の結果を確かめる（前回 UNLOCKED_BY_STOP。差分に keyguard を触るコードは無い、一度きりの可能性（未確認））。着地は DEVICE |
| #363 #365 #373 | — | レビュー済みの画面の PR | 実機の写真待ち（DEVICE）。着地の前に master を取り込む |
| #189 | — | Mac 側の担当 | 触らない |
| #358 | — | クラウド側のメモ（draft） | 下書きのまま。中身は下の「途中のもの」 |
| #388 | — | Mac 側のメモ（draft） | 触らない |

## 途中のもの（どこにあるか）

- **まだ GitHub に書いていない書き込み 20 件**: ブランチ `chore/cloud-notes-calendar-alarm` の `queue/` に全部の本文がある。順番は `queue/queue2.sh`（13 件: デザイン差分の issue 4 件 = `queue/dgap-10・11a・11b・11c.body.md`、題の頭に `[PBI][UX] `、ラベル feature／#396〜#400 の REVIEW 5 件／デザインの写しの PR 1 件／#398 の LOCAL-CHECK・GATE・merge）、次に `queue/queue3.sh`（7 件: #396 #397 #399 #400 の本文、#396 のスレッドの返事、#399 の返事、#307 のコメント）
- **デザインの写しの更新**: ブランチ `docs/design-copy-78c1`（push 済み、PR はまだ）。本文は `queue/pr-design.md`
- **review の want を直す PR（ブランチは push 済み、PR はまだ）**:
  - `docs/spec-manual-snooze-count`（#389 の want: 手動スヌーズは回数に数えるが、押せる回数に上限は無い）。本文 `queue/fu-389.md`、Refs #143
  - `test/375-infra-error-order`（#394 の want: 呼び出し側の順を固定）。本文 `queue/fu-394.md`、Closes #375
- **途中で止めたもの**:
  - `handoff/tap-does-nothing-20261114`: #390 の want（押しても何も起きないことを本当に押して確かめる）の書きかけ。確かめていない。作り直してよい。`TroubleshootingFlagTest` で一度だけ 5000ms の待ち時間切れが出た件もここで調べる予定だった
  - #391 の want（次のアラームの時刻の assert）: 手を付けていない
- デザインの差分の一覧（コードを読んだだけの棚卸し）: `queue/design-inventory.md`

## 決まったこと・受けた指示

- デザインの正本は https://claude.ai/artifact/VqUeceTJYaL9Z3U2JyLnNm （持ち主「カレンダーアラームの最新のデザイン」）。版 1794658749-78c1。画面の板は repo の `design/project/` と同じ中身（2026-11-14 に sha256 で確かめた）。増えたのは logo の板だけ。https://claude.ai/artifact/REkqjYhaUXMoj42bHWE6cQ は古いデザインで、作る基準にしない
- 画面に何を出すかはデザインが決める。どの項目があるか・並び・プランと課金は文字の仕様が決める
- 持ち主「CIはローカルで通れば通ったものとみなしていいよ」: その head で repo の検査を手元で回し、LOCAL-CHECK に書けば通った扱い。hosted が後で落ちたらすぐ直す
- 持ち主「あと全セッションにdesignartifactを配ったけど、デザイン通りになるように全部作ること」
- テスター配布（fad）は 2 回まで持ち主が許した。この担当からは dispatch が 403 で動かせない
- #191: OFF ＋ OS のおやすみモード中は鳴らさず、見逃しの通知を残す（板 p149）。「許可を開く」は板どおり塗りつぶし（前の「枠付き」の指示を置き換えた）
- #188: 板 p154 の 2 列目と 4 列目に従う
- GitHub の書き込み: 同時に書くのは 2 人まで、間を 2 秒以上空ける。読むのはまとめて1回。60 秒より短い間隔で何度も読みに行かない。403 の二次制限が出たら Retry-After まで待つ（待つ間は手元の作業）

## 持ち主待ち・実機待ち

- 持ち主: Q1〜Q10（メモの PR #358 の一覧）、Q11 = #191 で「おやすみモード中も鳴らす」の既定が ON で、通知ポリシーの許可が無いと OFF と同じになる。多くの人は許可していないので、おやすみモード中は鳴らない。板のままにするか、許可されるまで鳴らすか。#194 の macOS の費用の承認。#401
- 実機: #363 #365 #373 #188 #191 の写真。iPhone での確かめの依頼を 13:52Z に出した（iOS の画面とデザインの比較、#396 の「在宅」）

## ぶつかった壁と越え方

- GitHub の二次制限（content creation の 403）に 3 回当たった。書き込みを1本の列にまとめ、間を 40 秒にし、待つ間はレビューや修正をファイルに書いた
- workflow の dispatch と job の re-run は 403。CI をもう一度回したい時は、master を取り込む commit（base merge）で新しい run を起こした
- merge の API が 502 を返したが実際は merge されていた。merge の後は merged の状態を読み直す
- GraphQL が使えないので、レビューのスレッドは `pulls/N/ccr/review_threads` の REST で数えた
- 古い後始末の待ちのスクリプトが、後で古いレビューを投稿しかけていた。止めて消した。待ちのスクリプトを残す時は、何を書くかを確かめてから

## 次の会話が最初にやる3つ

1. `queue/queue2.sh` と `queue/queue3.sh` の中身を、40 秒おきに1件ずつ GitHub に書く（最初の 4xx で止める）。#398 はそこで merge まで行く
2. #396 #397 #399 #400 #191 #188 を、作者でない新しいレビューにかける（前のレビューは `queue/review*-r*.md`）。PASS したら証跡・LOCAL-CHECK・GATE を付けて1件ずつ merge（#191 #188 は実機の写真が揃ってから）
3. push 済みのブランチ `docs/design-copy-78c1`・`docs/spec-manual-snooze-count`・`test/375-infra-error-order` の PR を開いてレビューにかける。そのあとデザインの差分の修正を、#323（#373 の上に積む）→ #318・#317・#321 の順で、同時に 4 件まで、同じ画面のファイルを 2 件で触らない
