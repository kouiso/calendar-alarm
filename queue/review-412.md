REVIEW | reviewer: general-purpose subagent, Opus 5.5 medium | authored no commit on this PR | head f22594ead9fff81dccc251c0eb435cd2eac478e7 | axes: pass line, design, scope, tests, body vs diff, evidence | verdict PASS | must 0 want 1 nit 0
- [want] scripts/emulator/test_run_scenarios_retry.sh:67-90 — 並び順だけを見ていて、INFRA_ERROR の分岐が `return` で抜けることは固定していない。手元で `run_scenarios.sh:386` の `; return` を消す変異を入れると、テストは rc=0 のまま通る（その時は INFRA_ERROR を記録したあと probes_ringing に落ちて PROBE_PRECONDITION も記録され、#375 が防ぎたい「PROBE_PRECONDITION に化ける」が起きる）。L_INFRA の grep を「同じ行か次の行に return がある」まで含める等で塞げる。#375 の受け入れ条件そのものは満たしているので want
- pass line: #375 の完了条件「err の時に採り直す」「採り直しても採れなければ INFRA_ERROR」は #376（本体）・#394（採り直しの単体テスト・CI 配線）で入っており、残っていた「呼び出し側の順序の固定」（#394 本文で次 PR に回すと書かれた分）をこの PR が足す。Closes #375 は妥当
- tests（fail-before/pass-after を手元で確認）: head で 8 件 ok・rc=0。変異 M1 側1の capfail ブロックを probes の後ろへ → `FAIL 呼び出し側 1 の順序` rc=1。M2 側2で同じ → rc=1。M4 `:386` の INFRA_ERROR を PROBE_PRECONDITION に → `呼び出し側の数が揃わない ... infra=1` rc=1。master 版のテストに M1 を当てると rc=0（＝この PR 以前は順序の入れ替えを検出できなかった）
- body vs diff: 本文の行番号（側1 L365<L384<L386<L388、側2 L510<L540<L542<L545）・「8 件 ok」・変異 1〜3 の結果は再現した。CI 配線（`ci-contract.yml:39`、`emulator_verify.yml:115`）は既存のまま同じスクリプトを呼ぶ
- design/scope: テストスクリプト 1 ファイル +25 のみ。コメントは日本語、件名は英語 Conventional Commit
LOCAL-CHECK | head f22594ead9fff81dccc251c0eb435cd2eac478e7 | node scripts/hardcoded-ja/check.mjs && node scripts/sync-ai-rule.mjs --check (+ bash -n と bash scripts/emulator/test_run_scenarios_retry.sh、変異 M1-M4) | pass 4 fail 0 | 2026-11-15T05:08:01Z
```
$ node scripts/hardcoded-ja/check.mjs && node scripts/sync-ai-rule.mjs --check && bash -n scripts/emulator/test_run_scenarios_retry.sh && bash scripts/emulator/test_run_scenarios_retry.sh
OK: 日本語の文字列リテラルは増えていません (合計 309 個)
AI rule files already in sync
ok   err,err,ok → 3 回目で採れる (result='1 1 1' captures=3)
ok   ずっと err → 初回+採り直し3回で諦める (result='err err err' captures=4)
ok   0 1 1 は採り直さない (鳴っていないものを通さない) (result='0 1 1' captures=1)
ok   初回から採れる値はそのまま (result='1 1 1' captures=1)
ok   state_capture_failed err
ok   state_capture_failed 0 1 1
ok   呼び出し側 1 の順序 (L365 retry < L384 state_capture_failed < L386 INFRA_ERROR < L388 probes_ringing)
ok   呼び出し側 2 の順序 (L510 retry < L540 state_capture_failed < L542 INFRA_ERROR < L545 probes_ringing)
exit=0
```
