REVIEW | reviewer: general-purpose, opus | authored no commit on this PR | head c241800aadf93483a83774115196b0cf2a298a70 | axes: pass line, design, scope, tests, body vs diff, evidence | verdict FAIL | must 1 want 2 nit 2

# PR #396 レビュー (issue #306)

対象: `/home/user/wt306` の HEAD = `c241800aadf93483a83774115196b0cf2a298a70` (確認済み)。base = origin/master `a37e822`。
コミット: `15f897d` (時刻のある予定を題全体の一致で判定)、`c241800` (ooo の追加と5種類の固定)。

## 1. 合格線 (pass line)

| 項目 | 結果 | 根拠 |
|---|---|---|
| 根本原因が直っている | OK | `Models.kt:105-120`。時刻のある予定は `hit()` が `whole == it` (題全体の一致) になり、「出社して面談」など部分一致による誤分類がなくなった。`resolveEventType` が `event.allDay` を渡す (`Models.kt:139-140`) |
| 挙動の変更に base で落ち head で通るテストがある | OK | 下の「テスト」節。shared 6件・androidApp 1件が base で落ち、head で通る |
| i18n: hardcoded-ja baseline は下がる方向だけ | OK | `scripts/hardcoded-ja-baseline.json` は変更なし。head でも base でも合計 309 個。`GITHUB_BASE_REF=master` 付きでも OK |
| i18n-check (CI) | OK | check-run `i18n-check` success |
| shared の引数変更で iOS の入口が変わらない | OK | `classifyEventType(title, allDay)` を呼ぶ Kotlin は `Models.kt:140` だけ。iOS の Swift は自前の写し `iosApp/iosApp/Models.swift:52` を使い、`SharedKitBridge.swift` / `RingingView.swift` に classify/resolve の参照なし。`git diff origin/master...HEAD -- iosApp` は空。`normalizeTitleForMatch` は `internal` で framework に出ない。check-run `build` success |
| DB スキーマ・価格の関門・本番・FAD 配布 | 触っていない | 差分は 7 ファイル (Models.kt、テスト 4、仕様 1、check.mjs 1) |
| 非作成者の REVIEW で must 0 | 未達 | head 名指しの非作成者レビューは coderabbit の COMMENTED (id 5904547102) のみ。本レビューは must 1 |
| インラインコメントすべてに返答 | **未達** | CodeRabbit のインライン 4573679730 (`Models.kt:97`) に返信 0 件。内容は下の must-1 で妥当と確認した |
| issue を閉じてよいか | OK (Closes 可) | #306 の確かめ方「:shared:jvmTest が通り、『出社して面談』などの時刻のある予定が展開される」は満たす (jvmTest 137件 失敗0、`EventTypeDefaultsExpansionTest.kt:157` が展開数=題の数を確かめる) |

## 2. 設計

- 終日=部分一致、時刻付き=正規化後の完全一致、の2本立ては #201 の判断B (issuecomment-6877487819) の文面どおり。Google の eventType が先 (`Models.kt:140`) も変わらない。
- 正規化 `normalizeTitleForMatch` (`Models.kt:85-98`) は全角英数記号 U+FF01-FF5E と U+3000 を半角に寄せ、前後を `trim()`、中を `Regex("\\s+")` で1つにする。**`trim()` は Kotlin の `isWhitespace` (U+00A0 を含む) を使うが、`\\s` は JVM では ASCII の空白だけ**で、端と中で空白の定義が違う。下の must-1。
- 終日の経路 (`t = title.lowercase()`) は正規化しない。base と同じ挙動で、仕様にも「終日は題に語を含めば」とあるので一貫している。
- `wholeTitle("オフィス","自宅","在宅")` は終日でも部分一致しない設計で、判断Bの「題全体の一致だけで足す」に合う。

## 3. スコープ

- 判断B にない追加が1つある: `wholeTitle("ooo")` (`Models.kt:114`、コミット c241800)。base では時刻付きの「OOO」は EVENT で鳴っていた。head では ABSENCE になり、既定では**鳴らなくなる**。鳴らなくする向きの変更が記録された判断の外にある (want-1)。
- KDoc の終日 startMillis の説明 (`Models.kt:200-205` 付近) の書き換えは #379 のレビュー指摘への対応と本文にある。挙動は変わらないので許容。
- `check.mjs` の許可リストに `wholeTitle` を足した (`scripts/hardcoded-ja/check.mjs:50`)。中身は照合キーワードで画面の文言ではないので、`hit` と同じ扱いは妥当。

## 4. テスト (自分で回した結果)

base 用の作業ツリー: `/tmp/claude-0/-home-user/c98a3d37-2e8b-5f9f-bf50-a9de72bb8786/scratchpad/rv396` (`git worktree add --detach` で origin/master)。stash は使っていない。

### base に head のテストを写して実行
- `LANG=C.UTF-8 LC_ALL=C.UTF-8 ./gradlew --max-workers=2 :shared:jvmTest --tests com.calendaralarm.shared.EventTypeDefaultsExpansionTest` → **18 件中 6 件が失敗**
  - FAIL `オフィスと自宅と在宅は題全体の一致だけで…` (オフィス allDay=false expected WORKPLACE but was EVENT)
  - FAIL `時刻のある予定は題全体の一致だけで判定する_語を含む普通の予定は分類されず鳴る` (was [WORKPLACE, ABSENCE, …])
  - FAIL `oooは題全体の一致だけで不在になり…` (OOO expected ABSENCE but was EVENT)
  - FAIL `…_題が語そのものなら分類されて予約されない` (在宅 expected WORKPLACE but was EVENT)
  - FAIL `…_大文字小文字と空白と全角の違いは同じ語として扱う` (expected ABSENCE but was WORKPLACE)
  - FAIL `時刻のある予定は5種類とも…` (expected EVENT but was FOCUS_TIME)
  - pass (変えない挙動の確認): `GoogleのeventTypeが…優先する`、`終日の予定は今までどおり…`、`題が語そのものの3種類も既定では予約されない`
- `./gradlew --max-workers=2 :androidApp:testDebugUnitTest --tests com.calendaralarm.EventTypeToggleResyncTest` → **5 件中 1 件失敗**: `時刻のある予定は題に語を含むだけでは3種類にならず…` (出社して面談 expected:<1> but was:<0>)
- `EventTypeTest` / `EventFilterTest` は新しい引数を使うので base ではコンパイルできず、写していない。同じ挙動は上の resolveEventType 経由のテストで base 失敗を確認済み。

### head で実行 (キャッシュを避けるため `--rerun`)
- `:shared:jvmTest --rerun` → 137 件 失敗 0
- `:androidApp:testDebugUnitTest --rerun` → 231 件 失敗 0 (1m16s)
- `node scripts/hardcoded-ja/check.mjs` → OK (合計 309 個)。`GITHUB_BASE_REF=master` 付きでも OK。base でも 309 個。
- 変異: check.mjs の `callees` から `wholeTitle` を外すと `Models.kt: 日本語の文字列リテラルが 0 個から 3 個に増えました (+3)` で失敗。元に戻し、`git status --short` が空なのを確認。

### 追加の確かめ (scratch に head の Models.kt と小さなテストを置いた)
- `classifyEventType("Out  of office", allDay=false)` → **EVENT** (期待 ABSENCE で失敗)
- `classifyEventType(" 不在 ", allDay=false)` → ABSENCE (端の NBSP は trim で落ちる)
- `classifyEventType("ｵﾌｨｽ", allDay=false)` → EVENT (半角カナは変換しない、仕様どおり)

### CI (head の check-runs)
CodeRabbit / verify (control-3d106817) / verify (fix) / i18n-check / build / plan すべて success。chatgpt-codex-connector は利用上限で中身のあるレビューなし (状態は failed と書いているが指摘本文は無い)。

## 5. 本文と差分

- 規則・iOS は #307 まで別・Google 優先・check.mjs の変更は差分と一致する。
- 「変更前のコードで shared の新規・反転 6 件のうち 4 件が落ちる」は c241800 の前の数のまま。今は新規・反転 8 件 (追加 7 + 反転 1) のうち 6 件が落ちる (nit-1)。
- 経緯で引く判断B に ooo が無いのに、本文の規則には ooo がある (want-1)。

## 6. 証拠

- 判断の記録: issue #201 comment 6877487819 (2026-11-14T12:48:24Z、本人の言葉ではないと明記)。
- インライン未返信: PR comment 4573679730 (`in_reply_to` が 4573679730 の返信なし)。
- 上の実行結果は、2026-11-14 にこの環境で回したもの。

## must

1. **CodeRabbit のインライン 4573679730 に返答し、直す** (`shared/src/commonMain/kotlin/com/calendaralarm/shared/model/Models.kt:97`)。指摘は妥当: JVM では `"Out  of office"` が時刻付きで EVENT のまま (上で実際に確認)。`trim()` と中の空白で定義が違う。
   直し方の例: `Regex("\\s+")` をやめて文字単位で畳む。
   ```kotlin
   val sb = StringBuilder(); var sp = false
   for (c in half.trim()) { if (c.isWhitespace()) { if (!sp) sb.append(' '); sp = true } else { sb.append(c); sp = false } }
   return sb.toString().lowercase()
   ```
   `EventTypeDefaultsExpansionTest` の「大文字小文字と空白と全角」に `typeOf("Out  of office", allDay = false)` == ABSENCE を足し、base(=今の head) で落ちて直した後で通るのを示す。直さないと決めるなら、その理由をスレッドに返す。

## want

1. **ooo の出所を判断の記録に揃える** (`Models.kt:114`、`doc/spec/kmp-replacement.md` の ooo の行)。判断B (issuecomment-6877487819) はオフィス・自宅・在宅だけを足すと書いており、ooo は無い。時刻付きの「OOO」を既定で鳴らさなくする変更なので、#201 に判断役の追記をもらうか、この PR からは外して別の issue にする。
2. iOS との違いの期限を #307 に書く。Android と iOS で時刻付きの予定の分類が違う状態は #307 (open) まで続く。#307 の本文に「#396 の規則 (終日=部分一致、時刻付き=正規化後の完全一致、ooo/オフィス/自宅/在宅は全体の一致だけ、NBSP の扱い)」を写し、Swift 側の正規化で同じ入力の表を使うと書いておく (#301 の照合表が入れば、そこへ)。

## nit

1. PR 本文の「6 件のうち 4 件が落ちる」を「8 件のうち 6 件 (残り 2 件は変えない挙動の確認)」に直し、androidApp の新規 1 件も base で落ちたと書く。
2. `Models.kt:82` の KDoc「空白の連なりを1つに」は、must-1 を直したら「Unicode の空白 (NBSP を含む)」と書き足す。

---
_Generated by [Claude Code](https://claude.ai/code)_
