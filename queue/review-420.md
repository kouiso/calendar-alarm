REVIEW | reviewer: general-purpose subagent, Fable 5.2 | authored no commit on this PR | head dde13dc4748a6e89315a865bcd757ea81ecf8987 | axes: pass line, scope, tests, body vs diff, evidence | verdict PASS | must 0 want 1 nit 3

## 指摘
- [want] PR 全体 — head の check run は 6 件とも skipped で、Kotlin の 2 ファイルはどこでもコンパイルされていない。本文は「CI の unit テストで確かめる」と書いている。マージの前に unit テストの success を 1 回見る。読んだ限りコンパイルは通る（下）
- [nit] scripts/emulator/test_run_scenarios_retry.sh:84 — `ends_with_return` は正しいコードでも落ちる形がある: `; return 1`、`; return  # 理由`、`fi` の前の空行。今の 2 か所は素の `return` なので通る。落ちる時は最後の行を出すので原因は読める
- [nit] scripts/emulator/test_run_scenarios_retry.sh:75 — 入れ子の `if` は、内側の枝が `return` で終わると通ってしまう（本文の「落ちる」とは限らない）。行の途中の `# ; return` を通す件は本文だけにあり、テストのコメントに無い
- [nit] PR 本文 — escape 表記を書いたはずの 2 か所が、API の出力では空白 1 字の code span に見える（描画は未確認）

## 変異（写し rv420/emulator で実施。worktree は無変更、写しは diff -r で元と一致）
- テストは自分の場所から `run_scenarios.sh` を引く（`HERE/run_scenarios.sh`）ので、写しの中で完結する
- (a) 1 か所目 L386 の `; return` を消す → exit 1「呼び出し側 1 の INFRA_ERROR の枝が return で抜けない」
- (b) 1 か所目の record の行をコメントにする → exit 1「呼び出し側の数が揃わない … infra=1」
- 本文の 3 つ目（2 か所目の `; return`）は再現していない
- 抜け（`# ; return`、入れ子）は、エミュレータ無しでは動かせない script を文字で留めるテストとして許せる。わざと書かないと起きない形で、本文に書いてある

## Kotlin（読んで確認）
- TitleMatchRuleTest.kt: master は生の c2 a0 が 1 個、head は 0 個で、escape 表記（6 バイト）が 1 個。escape を実の字に戻した行は master と同じ。足したのはコメント 2 行だけで、期待値（ABSENCE、WORKPLACE）は同じ。コメントの説明は `Models.kt:97-102,143-144` と合う
- SettingRowLayoutTest.kt: import は `androidx.compose.foundation.layout.FlowRowScope` と `androidx.compose.runtime.Composable`。`SettingsScreen.kt:22,77` と同じで、`IconChipRow`（:503）の型とも同じ。2 つとも :85 で使う。同名の宣言は無い。ktlint・detekt の設定は無い

## 軸
- pass line: テストは exit 0。変異 2 つで落ちる
- scope: テスト 3 ファイルだけ（+30 −4）。本体は無変更
- tests: 上のとおり。Kotlin は未実行（Gradle はここで回せない）
- body vs diff: 一致。ずれは nit の 2 点
- evidence: /tmp/claude-0/-home-user-calendar-alarm/4883ef84-55b1-5e04-8735-0a29b98f7ecb/scratchpad/localcheck-420.txt（hardcoded-ja、sync-ai-rule --check、git diff --check は exit 0）

## check runs（6 件、全部 completed・skipped）
- plan、i18n-check、verify、build、ios-build、contract: skipped。success 0、failure 0、in_progress 0。PR は draft
