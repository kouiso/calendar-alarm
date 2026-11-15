REVIEW | reviewer: general-purpose subagent, Fable 5.2 | authored no commit on this PR | head 0bbab80aeebf63ac13f22bc2a476f74523584cfb | axes: pass line, scope, tests, body vs diff, evidence | verdict PASS | must 0 want 1 nit 1

## 指摘
- [want] doc/spec/kmp-replacement.md:308 — 「設定の読み書きと画面は `SettingsRepository.kt:114,186`」の 114 は定義、186 は読み込みで、書き込みの行が無い。書くのは `setAutoSnoozeMax`（`SettingsRepository.kt:295`。呼ぶのは `SettingsScreen.kt:1412`）。「定義と読み込み」に直すか `:295` を足す。識別子 `autoSnoozeMax` の出現箇所としては 4 か所とも合っていて、挙動の説明に誤りは無いので must にしていない
- [nit] doc/spec/kmp-replacement.md:312 — 触っていない隣の行の `snoozeRow`、`AlarmRepository.kt:589-` は今 `:595`。PR 本文の「残り」に入る範囲

## 事実の照合（head のコード）
- `RingingOverlay.kt:237` は `if (snoozePresets.isNotEmpty()) {`。合う（ファイルは engine/ の下）。スヌーズのボタン自体は常に足されるので「分数の選択は」という限定も合う
- `AlarmService.kt:391-392` は `ringSettings.autoSnooze && instance.snoozeSeq < ringSettings.autoSnoozeMax`。合う。androidApp の main と shared で比較に使うのはここだけ（grep 7 行。iosApp は 0 行。テストは `AutoSnoozeServiceTest.kt` のみ）
- `AlarmRepository.kt:536` は `suspend fun onSnoozed`。合う。`AlarmRepository.kt` に `autoSnoozeMax` は 0 行
- `SettingsScreen.kt:1411` は `selected = p.autoSnoozeMax == n`。合う
- logo: `design/project/logo.dc.html` あり。`canvas.json` の題は「#209 ロゴの3案」で `page` 無し（1ページ目のほかの板と同じ）。A・B・C の名前は html に各 1 か所
- user-stories.md: 「not found」は 5fea7b2 の前の版の 7 行目にあった文言で、当時の正本は REkqj… の方。合う。版 `1794658749-78c1` は design/README.md:7 と同じ。`d68/` は 5fea7b2 で差分 0

## 軸
- pass line: 文書 3 ファイルに書いた事実は、want 1 件を除いて head のコードと合う
- scope: +10 −5。題と本文のとおりで、コードは無変更
- tests: 文書のみで対象のテストは無い。hardcoded-ja、sync-ai-rule --check、git diff --check は手元で exit 0
- body vs diff: 本文の項目は diff と一致。「残り」の古い行番号も実際に残っている
- evidence: /tmp/claude-0/-home-user-calendar-alarm/4883ef84-55b1-5e04-8735-0a29b98f7ecb/scratchpad/localcheck-419.txt

## check runs
- 0 件（success 0、failure 0、in_progress 0）。PR は draft
