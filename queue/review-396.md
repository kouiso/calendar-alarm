REVIEW | reviewer: general-purpose subagent, Opus 5.5 medium | authored no commit on this PR | head b6364b6140a8a352d00190ed07c4f4df9e74ce72 | axes: pass line, design, scope, tests, body vs diff, evidence | verdict PASS | must 0 want 0 nit 1

判断B をそのまま実装しています。Android は題全体の一致、iOS が通る `SharedBridge.expand` だけが master と同じ部分一致です。本文と仕様 7.1 の文はコードと合っています。前回 (c7a9895) の FAIL の指摘はすべて直っています。マージの前に、まだ実行中の `verify (fix-minified)` が終わるのを待ってください (下の「hosted のチェック」)。

## 指摘

- [nit] shared/src/commonTest/kotlin/com/calendaralarm/shared/TitleMatchRuleTest.kt:46 — `Row("Out of office", false, ABSENCE, WORKPLACE)` の題には、Out と of の間に目に見えない U+00A0 (NBSP) が入っている (`od -c` で `302 240` を確認)。見た目は普通の「Out of office」なのに、今までの規則の期待値が WORKPLACE になっているので、読んだ人には期待値の誤りに見える。直前のコメント (44 行) にも NBSP のことは書いていない。`EventTypeDefaultsExpansionTest` と同じく `"Out of office"` と書けば意図が読める。挙動の誤りではない。

## 軸ごとに見たもの

- **pass line**
  - LEGACY_SUBSTRING が master と同じか: `git show origin/master:shared/.../model/Models.kt` の `classifyEventType` (79-91 行) と、head の `Models.kt:129-150` を並べて読んだ。`legacy` が true の時、`hit` は `t = title.lowercase()` への `contains` だけになり、master と同じ語・同じ順で判定する。`wholeTitle` は `!legacy &&` で必ず false になる。WORKPLACE の行は `hit(...) || wholeTitle(...)` なので master の `hit(...)` と同じになる。`normalizeTitleForMatch` は計算されるが、副作用は無く結果にも使われない。同じ規則だと判断した。
  - LEGACY を渡しているのは 1 か所だけ: main で `TitleMatchRule.LEGACY_SUBSTRING` を使うのは `SharedBridge.kt:111` だけ (shared・androidApp・iosApp を grep した)。`SharedBridge.plan` は種類を判定しない。
  - Android: `AlarmRepository.kt:305` (`passesEventFilters`) と `:838` (`expandEvents`)、`:328,334` (`silencedWithoutOverride`。中で `AlarmExpander.kt:355` の既定の `passesEventFilters` を呼ぶ) は、どれも既定の WHOLE_TITLE_FOR_TIMED になる。予約と一覧の規則は揃っている。
  - iOS: Swift が shared を呼ぶのは `SharedKitBridge.swift:9,15` (expand と plan) だけ。一覧は Swift の写し (`Models.swift:52,78,138,144,622` → `AgendaView.swift:543,564,568`) を使う。写しは master と同じ部分一致なので、iOS の予約と一覧はどちらも #306 より前のままになる。判断B の「iOS は #307 まで別のまま」と合う。`import SharedKit` があるのは `SharedKitBridge.swift` と `RingingView.swift` だけで、Widget は shared を呼ばない。
  - #306 の完了条件: 時刻のある「出社して面談」などが Android で展開されることを、`EventTypeDefaultsExpansionTest` の反転したテストと `EventTypeToggleResyncTest` の新しいテストが固定している。`Closes #306` は正しい。
  - master の取り込み (b6364b6): `git show --cc` で解決した hunk は無かった (衝突なし)。取り込んだのは 910d669 まで。その後の master (e224f93 まで。#412・#414・#413) とも `git merge-tree` で衝突しない。`SharedDecisionTableTest` (#398) の表は、行ごとに題を「会議」に固定し、種類は Google の eventType で渡している。`testdata/shared-decisions.json` の `googleEventType` は birthday 2・default 8・focusTime 1・outOfOffice 3・task 1・workingLocation 1 で、どれも `googleEventTypeToEventType` が知っている値。題の規則には依存しないので、新しい規則と食い違わない。`expandEvents` の行も「会議」で、どちらの規則でも EVENT になる。hosted の `build` success もこれと合う。
  - リポジトリの決まり: 34922fd の件名は英語の Conventional Commits。コメントは日本語。`node scripts/sync-ai-rule.mjs --check` は同期済み。i18n の確認も OK (下の「evidence」)。
- **design**
  - 規則を enum の引数にして、既定値を Android の規則にした。そのため Android の呼び出し側は変わらず、#307 では SharedBridge の 1 行と Swift の写しを直せば揃う。`silencedWithoutOverride` (shared) は規則の引数を取らないが、使っているのは Android だけなので今は問題ない。
  - 手で追った入力 (LEGACY): 「出社して面談」→ WORKPLACE、「Takeaway 発注締切」→ ABSENCE ("away")、「ＡＢＳＥＮＴ」→ EVENT、NBSP 入りの「Out of office」→ WORKPLACE ("office")、「在宅」(終日) → EVENT。どれも master と同じ。
  - 参考 (指摘ではない): Swift の `lowercased()`/`contains` (書記素単位・正準等価) と Kotlin の `lowercase()`/`contains` (UTF-16 単位) は、合成文字などで結果が違いうる。これは master でも同じで、この PR で増えたものではない。
- **scope**: 差分は 10 ファイル。規則 (Models.kt)、渡す経路 (AlarmExpander.kt・SharedBridge.kt)、テスト 5 ファイル、仕様 7.1、check.mjs の `wholeTitle` 1 語、CalendarEvent の KDoc (#379 の指摘で、本文に書いてある。挙動は変わらない)。判断B の外の挙動の変更は無い。
- **tests**
  - `TitleMatchRuleTest` の表 (16 行): 各行を手で追い、期待値どおりになることを確かめた。LEGACY の列は、master の `classifyEventType` とずれると落ちる。
  - `iOSが通るSharedBridgeのexpandは今までの部分一致で予約する`: 既定の絞り込みで、期待値は {オフィス, 在宅(終日), 定例}。c7a9895 の bridge (規則を渡さず題全体の一致) では {出社して面談, Takeaway, 定例} になるので落ちる。master では `TitleMatchRule` が無いのでコンパイルで落ちる。`eventId = event.instanceKey` (`AlarmExpander.kt:115`) なので、`keysOf` での比較は正しい。
  - `Androidが通るexpandEventsの既定は時刻付きを題全体の一致で予約する`: master の規則では {オフィス, 在宅, 定例} になるので落ちる。
  - `EventTypeDefaultsExpansionTest` (反転 1 件・新規 7 件)、`EventTypeTest` (新規 1 件)、`EventTypeToggleResyncTest` (新規 1 件) は、前回のレビュー (a3fef8c) で確かめた状態から変わっていない (`git diff c7a9895 34922fd --stat` にこれらのファイルは無い)。
  - 前回の nit 2 件 (`OOO`、空の題) は表の 48-50 行で固定された。
- **body vs diff**: 本文の文をコードと 1 つずつ照らした。
  - 合う: 規則の節、引数を渡す 5 つの関数、既定値、`SharedBridge.kt:111`、`Models.swift:52`、`AgendaView.swift:543`、`Models.swift:622` (`silencedWithoutOverride` の中で `passesEventFilters` を呼ぶ行)、テストの件数 (新規 7・EventTypeTest 1・androidApp 1・TitleMatchRuleTest 3・表 16 行)、`ooo` を足していないこと、check.mjs の OK (309 個)、sync の同期済み。
  - 仕様 7.1 (`doc/spec/kmp-replacement.md:222-244`) の Android の節・iOS の節・「iOS でも #307 まで予約と一覧は食い違わない」も、上の経路と合う。「`dropped` → `muted`・`notifies`」も `AgendaView.swift:553,563` と合う。
  - 照らしていないもの: 本文の「LEGACY と master が 44 通りで差 0」は実行の記録なので、ここでは再実行していない (読んだ結果、同じ規則であることは上で確かめた)。
- **evidence**: hosted の結果は下のとおり。手元の結果は `/tmp/claude-0/-home-user-calendar-alarm/4883ef84-55b1-5e04-8735-0a29b98f7ecb/scratchpad/localcheck-396.txt` に保存した。
  - `node scripts/hardcoded-ja/check.mjs`: OK (合計 309 個)、exit=0。
  - `node scripts/sync-ai-rule.mjs --check`: 同期済み、exit=0。
  - Gradle は回していない (dl.google.com に届かない)。Swift・iOS は実機でも simulator でも見ていない。

## 前回の FAIL (head c7a9895、issuecomment-6887353719) の指摘の状態

- must 1 (「判断B は端末を分けていない」は誤り。判断役の追記が要る): 解決。方針を判断B の文面どおり (iOS を変えない) に戻したので、追記は要らなくなった。本文からこの文は消えている。
- must 2 (「一覧では勤務場所と表示される」は誤り): 解決。仕様 7.1 (240 行) と本文は「iOS の一覧に種類の名前は出ない。写しが効くのは鳴る・鳴らないとスイッチの判定だけ」になった。コードとも合う。#307 にも訂正のコメント (issuecomment-6889756771、05:28Z) が付いた。
- must 3 (逆向きの食い違い。iOS で一覧は鳴ると見せて鳴らない): 解決。iOS の予約が LEGACY に戻ったので、勤務場所の 3 語でも、正規化して初めて一致する題でも、予約と一覧は食い違わない。仕様 222 行も直った。
- want 1 (終日は正規化しない、の言い過ぎ): 解決。仕様 235-236 行が「小文字にするだけ」「オフィス・自宅・在宅の一致だけは終日でも揃える」になった (`Models.kt:136-141` と合う)。
- nit 1 (`ooo` のテスト)・nit 2 (空の題のテスト): 解決 (`TitleMatchRuleTest.kt:48-50`)。
- インラインのスレッド: 1 本 (`PRRT_kwDOKMSxv863eQGT`、NBSP)。返信済み・resolved・outdated。未解決は 0 本。

## hosted のチェック (head b6364b6。05:38Z と 05:40Z 頃に 2 回取得)

- `build`: success (05:29:11Z → 05:33:40Z)
- `i18n-check`: success (05:29:12Z → 05:29:19Z)
- `ios-build`: success (05:29:19Z → 05:32:33Z。ビルドが通ることしか見ない)
- `plan`: success (05:29:14Z → 05:29:16Z)
- `verify (fix)`: success (05:29:19Z → 05:36:45Z)
- `verify (control-3d106817)`: success (05:29:19Z → 05:35:42Z)
- `verify (fix-minified)`: **in_progress** (05:29:19Z に開始。2 回目の取得でもまだ終わっていない)。マージはこれの完了を待つこと
- `CodeRabbit`: neutral (05:28:56Z。レビューの枠切れ)
- 失敗 0、取り消し 0。

後始末: 一時 worktree `/tmp/rv396` は、このレビューの終わりに消した。
