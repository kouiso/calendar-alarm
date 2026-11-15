REVIEW | reviewer: general-purpose subagent, Opus 5.5 medium | authored no commit on this PR | head c3e7a5ad0ff0795e801f4ed5bf4f2eb781ed050d | axes: pass line, design, scope, tests, body vs diff, evidence | verdict FAIL | must 1 want 0 nit 1
- [must] PR #413 本文末尾・コミット c3e7a5a 本文 — `Refs #143` が違う issue を指している。変えたのは `doc/spec/kmp-replacement.md` §7.5「放置したら自動でスヌーズ (#145)」の回数の数え方で、#143 は「全イベント共通の追加リマインダー」（スヌーズの話が無い）。回数上限と「Alarm missed 相当の仕様化」を受け入れ条件に持つのは #145。本文の「残り: #143 のうち、編集操作と iOS の確認は…残る」も #143 の受け入れ条件（共通リマインダーの編集・削除）の話で、この変更とつながらない。このまま squash すると master のコミットが #143 に誤って紐づく。`Refs #145`（必要なら #389 も）に直し、「残り」を #145 の残り（鳴動画面の −／＋、実機確認）に書き直す
- [nit] doc/spec/kmp-replacement.md:286 — 「いつでも何回でも押せる」は回数に関しては正しい（onSnoozed は autoSnoozeMax を見ない）が、ボタンが出るかどうかは別の条件（`RingingOverlay.kt:237` の `snoozePresets.isNotEmpty()` など）がある。「回数の上限では止まらない」くらいに絞ると誤読が無い
- 文言の裏付け（head c3e7a5a、origin/master 910d669 でも行番号は同じ）: `AlarmRepository.kt:530-543` の onSnoozed は TERMINAL_STATES だけ見て snoozeRow を呼び、autoSnoozeMax を参照しない。非テストコードで `autoSnoozeMax` を読むのは `AlarmService.kt:392`（判定）、`SettingsRepository.kt:114,186,295`（定義・丸め・保存）、`SettingsScreen.kt:1399-1400`（画面）だけで、上限判定は 392 のみ。`Models.kt:294-296` の snoozed() は手動・自動とも snoozeSeq+1。§7.5 は「Android のみ」の節なので iOS 側（Engine.swift:597-607 に上限無し）とも矛盾しない
- scope: 1 ファイル +2/-1、ドキュメントのみ。日本語・Conventional Commit 英語件名 OK
- tests: 挙動を変えない文書変更のためテスト対象なし
LOCAL-CHECK | head c3e7a5ad0ff0795e801f4ed5bf4f2eb781ed050d | node scripts/hardcoded-ja/check.mjs && node scripts/sync-ai-rule.mjs --check | pass 2 fail 0 | 2026-11-15T05:08:01Z
```
$ node scripts/hardcoded-ja/check.mjs && node scripts/sync-ai-rule.mjs --check
OK: 日本語の文字列リテラルは増えていません (合計 309 個)
AI rule files already in sync
exit=0
```
