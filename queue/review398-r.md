REVIEW | reviewer: general-purpose, opus | authored no commit on this PR | head d70ad1da8ef41fa2ddf6c83080d51211db6229bf | axes: pass line, design, scope, tests, body vs diff, evidence | verdict PASS | must 0 want 5 nit 5

# PR #398 レビュー（issue #301、2026-11-14 時点）

対象: `kouiso/calendar-alarm` PR #398 `test/301-shared-table`、head `d70ad1da8ef41fa2ddf6c83080d51211db6229bf`（`/home/user/wt301` の HEAD と一致を確認）。
差分は3ファイルだけ: `testdata/shared-decisions.json`（新規 122 行）、`shared/src/jvmTest/kotlin/com/calendaralarm/shared/SharedDecisionTableTest.kt`（新規 160 行）、`shared/build.gradle.kts`（+7 行）。製品コードの変更はなし。

## 1. 合格線（pass line）

| 項目 | 結果 | 根拠 |
|---|---|---|
| 根本原因 | 該当（テストだけの変更）。#301 の「表を1つ置き、Kotlin のテストで読んで shared の判定と照合する」をそのまま満たしている | 差分3ファイル |
| テスト・CI だけの変更: 変異を入れると落ちる | 満たす。shared の本体に変異を 11 個入れ、8 個で落ちた（§4） | §4 の表 |
| i18n / hardcoded-ja | 対象外。製品の文字列は増えていない。`node scripts/hardcoded-ja/check.mjs` → `OK: 日本語の文字列リテラルは増えていません (合計 309 個)`。CI の `i18n-check` は success | 手元で実行、check-runs |
| shared のシグネチャ変更 / iOS の入口 | なし。`commonMain` は変えていない。`build.gradle.kts` の変更は `tasks.withType<Test>` だけ（JVM と Android の unit test のタスク。`KotlinNativeTest` は `Test` を継承しないので iOS のテストのタスクにもかからない） | `shared/build.gradle.kts:41` |
| DB スキーマ・価格の条件・本番・FAD の配布 | どれも触っていない | 差分 |
| author 以外のレビュー | このレビューまでは 0 件（`pulls/398/reviews` が空） | gh api |
| インラインコメント | 0 件（`pulls/398/comments` length=0）。Codex は利用上限、CodeRabbit は「Review rate limited」で、指摘は出ていない | gh api |
| issue を閉じてよいか | #301 の確かめ方は「`:shared:jvmTest` が通る（ロケールが C の環境では `LC_ALL=C.UTF-8` を付ける）」だけ。手元で 133 件・失敗 0 → 満たすので `Closes #301` でよい | §4 |

## 2. 設計（design）

- 表を `commonTest/resources` ではなくリポジトリ直下の `testdata/` に置いたのは、iOS の `iosApp/unit-test` からも読めるようにするため。理由は筋が通っている（`SharedDecisionTableTest.kt:29-30`、json の `_readme`）。
- 読み込み元を `jvmTest` にしたのは `java.io.File` を使うため。issue の「場所: shared の commonTest」とは違うが、ファイルを読む API が JVM にしか無い以上、妥当な判断。ただし本文にその理由が書かれていない（nit N1）。
- `build.gradle.kts:40-45` で表をテストの入力として登録している。登録しないと表だけ直したときに jvmTest が UP-TO-DATE で飛ばされる、という説明を手元で確かめた:
  1. 変更なし → `> Task :shared:jvmTest FROM-CACHE`
  2. 表の `00:50:00Z` を `00:51:00Z` に書き換え → `> Task :shared:jvmTest FAILED`（`AssertionError at SharedDecisionTableTest.kt:94`）
  3. 元に戻す → `FROM-CACHE`

  つまり表を書き換えるとキャッシュが外れる。登録は効いている。
- パーサが緩い。行に無いキーは既定値になり、知らないキーは黙って無視される（`SharedDecisionTableTest.kt:67-71`, `73-80`, `124`）。たとえば `"minuteBefore"` と書き間違えても、その行は既定値で照合されて通ってしまう（want W4）。

## 3. 範囲（scope）

- 範囲は issue の中に収まっている。タイトルでの種類判定（`classifyEventType`）は #306 で変更中なので表から外し、種類は必ず `googleEventType` で渡している（`SharedDecisionTableTest.kt:131`、json の `_readme`）。並行している PR と衝突しないための正しい切り分け。
- 表に入っていないもの: 招待の絞り込み、`disabledCalendarIds`、`rule.enabled`、`muteAll`、`importEventReminders`、`commonExtraOffsets`、複数日にまたがる終日予定。#301 は網羅を求めていないので must ではない（nit N4）。

## 4. テスト（tests）と、表が仕様と合っているか

### 実行したこと

- head（`/home/user/wt301`）: `LANG=C.UTF-8 LC_ALL=C.UTF-8 ./gradlew --max-workers=2 -q :shared:jvmTest --rerun-tasks` → exit 0。結果の XML を集計すると全体 133 件・失敗 0。そのうち `SharedDecisionTableTest` が 4 件・失敗 0。本文の「133 件 0 失敗、新規 4 件」と一致する。
- base 側の作業用 worktree（`/tmp/claude-0/-home-user/c98a3d37-2e8b-5f9f-bf50-a9de72bb8786/scratchpad/rv398`、`origin/master` = `584f2c9`）に PR の3ファイルをコピー → `SharedDecisionTableTest` が通る。テストだけの変更なので base でも通るのが正しい。PR の merge-base `89af2d1` から `origin/master` までの間に `shared/` と `testdata/` の変更は無いことも確認した。
- 変異（同じ作業用 worktree の shared 本体に入れ、1つずつ戻した。最後の `git status` には PR の3ファイルしか残っていない。`/home/user/wt301` の管理下のファイルは触っていない）:

| # | 変異（`commonMain`） | 結果 |
|---|---|---|
| M1 | `inWindow` の猶予 `triggerAt > now - FIRE_GRACE` → `>=`（`AlarmExpander.kt:386`） | **落ちた** `expandEvents: 猶予ちょうど(60分前に過ぎた鳴動)は捨てる expected:<[]> but was:<[(1791162000000, 0, ALARM)]>` |
| M2 | `RingStatus.summarize` で許可と音量0の順番を入れ替え | **落ちた** |
| M3 | `passesEventFilters` から個別 ALARM の早期 return を削除 | **落ちた** |
| M4 | `EventTypeFilter.allows` の `\|\| free` → `&& free` | **落ちた** |
| M5 | `SettingsPreview.clock` の `padStart(2,'0')` を削除 | **落ちた** `snoozeClock: 分は2桁で埋める expected:<6:[0]5> but was:<6:[]5>` |
| M6 | 終日の `toInstant(zone)` → `toInstant(TimeZone.UTC)` | **落ちた** |
| M7 | 同じ時刻の重複除外（`seenTriggers`）を無効化（`AlarmExpander.kt:106`） | **落ちた** |
| M8 | 予定ごとの `minutesBefore` の上書きを無視 | **落ちた** |
| M9 | 同じ時刻のとき後ろの鳴動（リマインダー側）が勝つように変更 | **通ってしまった** |
| M10 | 窓の上限 `triggerAt > horizon` → `>=`（`AlarmExpander.kt:384`） | **通ってしまった** |
| M11 | 追加リマインダーを `reminderAction` ではなく `startAction` で出す（`AlarmExpander.kt:119`） | **通ってしまった** |

M1 から M8 は落ちたので、合格線（変異を入れると落ちる）は満たしている。M9 から M11 が通ってしまう理由は want W2・W3 に書いた。

### 表の値が仕様と合っているか（`doc/spec/kmp-replacement.md`）

- §7.1 の種類ごとの既定（予定・タスクは鳴らす。不在・サイレントモード・勤務場所・誕生日は鳴らさない。予定なしは鳴らす）、判定の順番（個別指定 → 種類 → 予定あり／なし）、「仮と不明は予定ありとして扱う」: すべて json の `eventTypeFilter` の 16 行と一致する。
- §7.3 の終日（既定 540、負数なら鳴らさない、UTC の日付をローカルの allDayMinutes に解釈し直す）: 「8:00 JST = 前日 23:00Z」の行と、負数の行で押さえている。
- §6 の鳴動の猶予 60 分: 59 分なら残す、60 分なら捨てる、の境界を両側から押さえている（M1 で落ちたことで確かめた）。
- `RingStatus` の優先順（許可 → 音量0 → 見逃し → 予約なし → 準備OK）と、負数を 0 に丸める処理: `RingStatus.kt:40-48` と一致する。
- 重複の規則（master に入った #389 の `doc/spec/kmp-replacement.md:259`「開始と同じ時刻なら開始の動作で1回」）: json の 13 行目の行名は「同じ時刻は開始側を優先して1回」だが、開始とリマインダーがどちらも `ALARM` なので、どちらが勝ったか区別できない（M9・M11 が通ってしまう）。行名が主張していることを、行の中身は確かめていない（want W2）。

## 5. 本文と差分（body vs diff）

- 3ファイルの説明、表に入れたもの・外したもの、置き場の理由、UP-TO-DATE の件、133 件: すべて差分と手元での実行結果に一致する。
- 「iosApp/unit-test から表を読む側は未着手（issue の本文どおり後で）」: #301 の本文には「後で」とは書かれていない。書いてあるのは「場所: shared の commonTest、iosApp/unit-test と共有できる置き場」だけ。言い過ぎなので、「#301 の範囲外。iOS 側は別の issue で」などに直す（nit N1）。
- 「新規テストは master に対象が無いため『修正前に落ちる』ものではない」: 正しい。代わりに表を書き換えると落ちることを示していて、このレビューでも本体への変異 M1 から M8 で落ちることを確かめた。

## 6. 根拠（evidence）と CI

- head の check-runs: `build` success、`i18n-check` success、`plan` success、`verify (control-3d106817)` success、CodeRabbit neutral（rate limited）、**`verify (fix)` failure**（run 43600072934、`(verify) alarm stop E2E (emulator)`）。
- 失敗した行: `RESULT unlocked: FAIL PROBE_PRECONDITION before stop (players service notif)=0 1 1; every probe must see the ring before stop`。この PR は製品コードも Android アプリも変えていないので、原因は PR ではなく emulator の不安定さと見ている（**（未確認）**）。同じ時間帯の他の PR（3f401c37、c241800a、4211b71c）では同じ workflow が success だった。とはいえ、head に赤い check が残ったままマージするべきではない（want W1）。
- PR の branch は master より遅れている（merge-base `89af2d1`、master `584f2c9`）。master に入った `a37e822`（#378）で `ios_build.yml` が `shared/**` の変更で動くようになったが、この PR には iOS build の check-run が付いていない（want W5）。

## must

なし。

## want

- **W1** `verify (fix)`（emulator の停止 E2E）が head で failure になっている。job を再実行して緑にしてからマージする。もう一度落ちたら、`PROBE_PRECONDITION` の原因を別の issue に切り出し、その旨を PR に書く。
- **W2** 「開始側を優先」をきちんと確かめる。`SharedDecisionTableTest.kt:67-72` の `AlarmRule` に `reminderAction` の読み込みを足し、json に次の2行を加える。
  - (a) `{"minutesBefore": 10, "startAction": "NOTIFY", "reminderAction": "ALARM", "extraOffsets": [10]}` → `00:50Z` に `NOTIFY` が1件だけ出る（開始の動作で1回）。
  - (b) `{"minutesBefore": 10, "startAction": "MUTE", "extraOffsets": [10]}` → `00:50Z` に `ALARM` が1件出る（master の仕様にある「開始が鳴らさないでも、同時刻のリマインダーは鳴る」）。

  こうすると M9・M11 で落ちるようになる。
- **W3** 窓の上限の境界の行を足す: `horizon` を `2026-10-05T01:00:00Z`（鳴動時刻とちょうど同じ）にして、expected に鳴動が1件出る行。こうすると M10 で落ちるようになる。いまある「窓の外」の行は 1 分外側しか見ていない。
- **W4** パーサが知らないキーを黙って無視しないようにする。行ごと、`rule` ごと、`override` ごと、`filter` ごとに使ってよいキーの集合を決め、`assertEquals(emptySet(), keys - allowed, name)` で確かめる。こうすれば、キーを書き間違えた行が既定値のまま通ることがなくなる。
- **W5** master に rebase して、`ios_build.yml` を走らせる（この PR は `shared/build.gradle.kts` に触れている）。iOS の入口が変わっていないことを CI でも確かめる。

## nit

- **N1** 本文の「issue の本文どおり後で」を事実に合わせて直す。あわせて、`commonTest` ではなく `jvmTest` にした理由（`java.io.File` が JVM にしか無い）を一言書く。
- **N2** json の `"version": 1` をテストで読んでいない。`assertEquals(1, root["version"]...)` を入れると、Swift 側と書式を変えたときに気づける。
- **N3** `locateTable()`（`SharedDecisionTableTest.kt:38-46`）はファイルシステムのルートまで遡って探す。`settings.gradle.kts` があるディレクトリで止めると、リポジトリの外にある同じ名前のファイルを拾わない。
- **N4** 招待の絞り込み、`disabledCalendarIds`、`rule.enabled`、`muteAll`、`importEventReminders`、`commonExtraOffsets`、複数日にまたがる終日予定は、表への追加候補として #223 の子の issue に残す。
- **N5** iOS から表を読むようになったら、`iosSimulatorArm64Test` などの `KotlinNativeTest` にも同じ入力の登録が要る（`build.gradle.kts:41` は `Test` 型だけに効く）。

---
_Generated by [Claude Code](https://claude.ai/code)_
