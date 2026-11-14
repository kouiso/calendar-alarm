STATE RUNNING 2026-11-14T13:54Z | merged 15 since 12:20Z (#374 #376 #377 #381 #379 #380 #387 #378 #389 #390 #391 #392 #394 #393 + #369/#372 before); open PR 13; open issues 165
mission: それぞれのプロジェクトに書いてあるプランに書いてある作業をやること。どのプロジェクトもまず既存のPRを本質的に対処することを作業を優先してください。そうしないと溜まってばっかりなので
model claude-opus-5-5 (lead, medium) | advisor present (fable) | github via gh api (REST; GraphQL blocked, so the Project board's Iteration cannot be read) | push via git | png ok (Playwright/Chromium)
goal: owner RESUME 2026-11-14 18:45 JST (AI rows landed, passed reviews merged, own PRs landed or closed, rest OWNER/DEVICE) | DONE-WAITING satisfies it: yes
repo rules read: AGENTS.md, doc/test-spec.md 検証の証跡「鳴動・UI系の修正は実際に鳴らして止めた動画/スクショを PR に添付する」 (stricter than G4 for screen PRs: real-device evidence), .github/scrum-board.yml (project 10), unresolved review threads 0 before merge
DEPLOY | merge to master -> nothing (CI builds a debug APK as a 14-day artifact only) | evidence .github/workflows/all_check.yml:50-55 (upload-artifact); fad.yml:1-3 is workflow_dispatch only (tester distribution, never on merge); ios-device-sync.yml:8-30,248 is a daily cron that may dispatch fad.yml (iOS) to testers from master = staging, independent of merges
---
HOUR 2026-11-14T13:54Z | merged 9 since 12:40Z (#380 #387 #378 #389 #390 #391 #392 #394 #393) | closed 3 (#298 via #392; #253 via #385 proof; #124 superseded) | output 6 PRs (#396-#400 + base merges) and 1 issue (#401) | open PR 12->13 | open issues 166->165 | ledger open: plan rows not readable here | owner 11 (Q1-Q11; Q11 = #191 default ON without DND permission) | device 5 PRs (#363 #365 #373 #188 #191) | stuck at device (screen PRs) and owner | design: current design = VqUeceTJYaL9Z3U2JyLnNm (notes DECIDED + design/README.md); REkqj = old (owner); 8U3ce not in any source of this lane | rule 1(a) moved 0 (Iteration unreadable), 1(b) claude-cloud issues kept behind | skipped (-ux) 0 | members running 8 (reviews #396 #397 #398 #399 #400 opus; fixers #191 #188 sonnet; design-gap issue drafter sonnet) | in flight: #396-#400 review, #191/#188 r7 fixes, 11 design-gap issues, #398 fresh CI on 5336dcb (base merge; rerun API 403)
HOUR 2026-11-14T12:40Z | merged 5 (#374 #376 #377 #381 #379) | closed 4 (#144 done; #299 #252 via #381; #375 stays open) | output 9 | open PR 10->12 | open issues 167->166 | ledger open: plan rows not in this session | owner 3 | device 4 PRs (#363 #365 #373 + #188 #191 after board fixes) | stuck at device (screen PRs) | rule 1(a) moved 0 (Iteration unreadable), 1(b) 11 claude-cloud issues put behind; skipped (-ux) 0 | members running 8 (#191/#188 board fix, #378 neg control, spec #143/#145/#148, test #153, test #151, #298 setup script, #386 lint, #375 retry test) + reviewers (#380 delta, #387) | in flight: PR #378 #380 #385(probe) #387 + 6 items
calendar-alarm [cloud] — this session is the cloud lane. Mac lane (another session) holds #370, #189 and the device evidence.
START 2026-11-14T12:20Z exec-prompt v2 adopted. handoff: this file's 引き継ぎ section; next step = existing open PRs (#373 #374 #376 in review; #365 #188 #191 #363 waiting on device evidence).
worktrees 2026-11-14T12:20Z: all 19 clean, nothing unpushed. /home/user/{calendar-alarm master, calendar-alarm-notes notes, wt188, wt189 (Mac's PR, untouched), wt191, wt204, wt231, wt232, wt243, wt271, wt297, wt309, wt366, wt371, wt-advisor (no commits)} match their upstream; wt367 e41db5c, wt375 3ce3f77, wtpl 47bfae9 match the remote branch; wt289 0991bb6 = merged into master as b831a9c (remote branch gone).
DECIDED | owner additions 1-4 adopted 2026-11-14T12:25Z | reason manager message | revert none needed
rule 2 (-ux): open PRs+issues 177 read by REST; 0 assigned to or opened by a login ending in -ux. skipped: 0.
CYCLE 1 T=2026-11-14T12:14Z | PRs 10 | issues 167 | ledger rows: plan rows not in this session (status.tsv is on the Mac; packet says AI rows startable now = row 36, Mac lane) | list cycle-1.tsv
  PR queues: REVIEW 3 (#373 #374 #376) | DEVICE 4 (#365 #363 #191 #188, reviews passed) | OTHER-LANE 2 (#370 #189, Mac) | NOTES 1 (#358). issues: sorting in batches of 50.
DECIDED | design source of truth = the owner's design canvas https://claude.ai/artifact/VqUeceTJYaL9Z3U2JyLnNm (「calendar-alarm デザイン」: ds = Night UI 第2版, p140-p155 per issue, d68 = #68 決定1〜9 screens, logo = #209) | reason owner 2026-11-14 「さっきのはデザインです」 | revert owner names another source
  use: every screen PR's reviewer compares the change (and the device screenshots) with the matching board: #188→p154, #191→p149, #363→p155/d68 settings, #365→d68 home, #373→d68 tabs/home. The canvas is read only from this lane.
merged since start-up: #374 (7777367, gate https://github.com/kouiso/calendar-alarm/pull/374#issuecomment-6877163983), #376 (346513c, gate https://github.com/kouiso/calendar-alarm/pull/376#issuecomment-6877164166; body changed to Refs #375, the retry-loop test stays open on #375).
issue sort (4 Sonnet members, read-only): DEVICE 67 | WORK 49 | JUDGE 33 | DONE-ALREADY 11 | OWNER 7 = 167. files sorted-0[0-3].tsv in the lead's scratchpad; DONE-ALREADY and JUDGE go to the manager in bundles of 10.
in work: #300 (lint in CI + baseline, child of #253), #201 (0:00 ringing from work-location/birthday/OOO).
OWNER ANSWER 2026-11-14 (relayed by the manager) | ledger row 19 (#309: build number goes up on every distribution) | question: may the AI run the tester distribution twice? | answer 「してよい」 | row 19 and the rows it blocks (row 20, then 18) are unblocked. No store submission, no production, no sound.
  this session cannot dispatch it: POST actions/workflows/fad.yml/dispatches -> 403 "Resource not accessible by integration" (2026-11-14T12:32Z). -> for main session: please run fad.yml twice on master with platform=android (or have the Mac lane run it) and send the two run numbers; then #309 closes with the two APK versionCodes.
PASS LINE (manager, scene a, 2026-11-14): root cause fixed; behaviour change = a test failing on base and passing on head; test/CI-only = a shown mutation that fails. i18n: ja equals the old literal (test), every key has en (plurals too), i18n-check green, baseline only falls, a Robolectric render test (en, 360dp) where English is longer. shared signature change: iOS entry points unchanged (or the PR iOS build green once #378 lands). No DB schema, price gate, production, FAD dispatch (FAD now allowed only for row 19). Non-author REVIEW naming the merged SHA, must 0; all check-runs green incl. both verify lanes; every inline comment answered. Screen PR: body names its design board; the reviewer compares labels, order and states; a difference is fixed or goes to OWNER; no board -> say so and follow ds. Real-device shots at that head, device muted; English shots for #365 and #373. Close an issue only when its 確かめ方 is met, else Refs.
POLICY (manager): #373 is a screen PR -> DEVICE (ja+en shots: tabs, agenda empty/all-day/untitled, themes, fresh-install timer presets). Device queue: #363, #365, #373; #188 and #191 after their board fixes. No new screen PR while five wait. i18n is blocked (#288 needs #325 and #255; #290 needs #325 and #272; #313 needs #288; #292 overlaps #188/#191 and iOS #312). Non-screen next: #356 (targetSdk 36), #306 (#201-B), #298 (script and docs only). Then claude-cloud: #300 (=#377), #299 (=#381), #301; #304 after #191.
DECIDED (manager) | #191 follows board p149: label 「おやすみモード中も鳴らす」, default on, switch disabled without the permission with 「許可を開く」 below; OFF behaviour stays "follow the OS, ring when the OS allows alarms" (board says OFF = notification only; kept because a silent miss is the worst failure) -> needs owner: one line | revert one commit
DECIDED (manager) | #188 label differs from board p154 (「鳴動画面を大きいボタンにする」) -> align it | pricing free vs Pro is live for the owner (#140 closed) and does not block landing (no price code; Refs #154)
DECIDED (manager) | #201: A keep legacy masks and document; B all-day keeps substring matching, timed events classified only on whole-title match, add オフィス/自宅/在宅 as whole-title only, under #306 with spec 7.1 in the same PR; C not now, open an issue; D fix the all-day imported-reminder base in its own issue (importEventReminders defaults to true, SettingsRepository.kt:95,175: a 540-min reminder rings at 0:00), gated on one device read, iOS coordination (#274-#276), and a fail-before test | revert one commit each
DECIDED (manager) | 10-minute repost window kept; open an issue for "a new message under the same key within 10 minutes is silent" | revert window 0
withdrawn: judge packet 3 baseline 5->6 (superseded by 23ec50d); #191 "switch stays pressable".
needs owner: (1) #191 OFF behaviour: keep "follow the OS" or board's "notification only"? recommend keep. (2) #188 free vs Pro. (3) test-spec says シミュレータ/実機: may CI-emulator screenshots count for text-only PRs (#373, #365)? recommend yes for text-only.
for main session: (a) fad.yml x2 (above). (b) Iteration of Project 10 unreadable here.
merged since HOUR 12:40Z: none new; closed: #385 (throwaway lint probe, CI lint failed on NewApi as intended: https://github.com/kouiso/calendar-alarm/actions/runs/43595692748/job/132449565285), #300 and #253 (all done conditions met: #377 + #385). New issue #386 (lint follow-ups).
near miss 2026-11-14T12:4xZ: the #378 member's first revert command included `--amend`; the auto-mode classifier blocked the whole command, nothing ran; redone as `git revert -n` + a normal commit. Prevention: every member brief already says "no amend"; the classifier is the second net. No history was changed.
#378: negative control done (12b36d5 red: Models.swift:638 compile error; 93b6ba6 revert green); net diff vs 01b00db empty. Under review.
#191 9a2551c / #188 90ef21b aligned to boards p149 / p154; reviews running in parallel. Board differences for the owner: #191 OFF behaviour; #191 switch locked without permission; #188 row placement (board: top of a group with 文字の大きさ・鳴動画面の背景; PR: end of the appearance card).
merged: #380 (a88254a), #387 (89af2d1, target SDK 36; CI emulator API 36 follow-up = #395). #201 decisions A-D recorded on GitHub: https://github.com/kouiso/calendar-alarm/issues/201#issuecomment-6877487819
GitHub secondary rate limit hit 2026-11-14T12:5xZ (PR creation 403). Prevention: one GitHub write at a time per agent, no retry loops; the lead opens queued PRs.
JUDGE 3.1/3.2/3.3/3.4 (manager, 2026-11-14): CLOUD NOW = #306 (in work), #211 audit table (with #203 comparison), #269 (method changed: on restore clear scheduled rows and per-event overrides, then resync; backup rules cannot exclude single tables in one db file), #220 SBI-1 and SBI-4 audits (public repo: only a non-exploitable summary on GitHub) and R8 as its own PR gated on the stop E2E on the minified build, #301 without classifyEventType. QUEUED (screen, device queue): #217 after #363, #218 after #205, #317-#324 (#323 first). CLOSE: #124 (superseded). DEVICE: #139. HOLD: #215, #332, #333. #208 decided for the owner: no app-sent email in v1. #213 option A decided (display + link), timing = owner Q9. #237/#268 key decided (account + Google stable calendar id, name fallback), migration = owner Q8 (DB, excluded from lane merges). #382 does not wait for #207.
OWNER INSTRUCTION 2026-11-14 (relayed) 「あと全セッションにdesignartifactを配ったけど（Horsemanager以外）、デザイン通りになるように全部作ること」 | design link: https://claude.ai/artifact/VqUeceTJYaL9Z3U2JyLnNm (repo copy design/project/, canvas version 1794658749-78c1) | inventory member running.
DESIGN SOURCE 2026-11-14T13:52Z | owner (via manager) 「カレンダーアラームの古いデザイン https://claude.ai/artifact/REkqjYhaUXMoj42bHWE6cQ」 = OLD, do not build to it | current = https://claude.ai/artifact/VqUeceTJYaL9Z3U2JyLnNm (evidence: DECIDED line above from owner 「さっきのはデザインです」; design/README.md 正本; repo copy design/project/ and inventory built from it) | https://claude.ai/artifact/8U3ceHFcRKKfk5LZ3JJ22m appears in no source of this lane (plan/notes/repo) -> not used | stale: doc/spec/user-stories.md:7 names REkqj as 正本 -> small doc PR
DESIGN SOURCE CONFIRMED 2026-11-14T14:00Z | owner (via manager) 「カレンダーアラームの最新のデザイン」 https://claude.ai/artifact/VqUeceTJYaL9Z3U2JyLnNm = CURRENT | opened from this container OK (Artifact read, version 1794658749-78c1) | all 27 screen/index boards byte-identical to repo design/project/ (copy 1794404197-426d); only new board logo.dc.html (#209) + canvas.json differ -> design/ refresh PR | inventory and 13 design-gap issues stay valid
DECIDED 2026-11-14T13:53Z | #191 「許可を開く」 = board p149 filled .btn (accent, h52 r26, full width), replacing the earlier "outlined" note | reason design decides what is on screen; design/asset css .btn{background:var(--ac)}; review r7 M1 | revert manager names outlined again
DEVICE REQUEST sent 2026-11-14T13:52Z: (1) design rows 1-4,30 iOS agenda/detail/alarms screenshots vs d68 (distributed build, muted) (2) #396 timed 「在宅」 rings / all-day = absence (branch fix/306-title-whole-match)
DECIDED (manager) | design-fix screen PRs may start now: never two open PRs on the same screen file; at most 4 design-fix PRs open; device time to older PRs first; order #323 (stack on #373) -> #318(#342 shared pieces) / #317(#340) / #321(#348) in parallel -> #322 after #373, #316(#338) after #365, #320 after #188+#229, #324 after #191+#228, #319 after settings PRs+#325 | revert: the manager's earlier rule
DECIDED (manager) | cloud side-by-side counts as design-match evidence if: committed screenshot test at the PR head with the real screen/theme/fonts and board sample values; assertions on the board's CSS numbers in light and dark; names the canvas version; one board verified once against the canonical canvas; device-only for status bar/insets, keyboard, notification, lock screen, overlay, all iOS. Real-device shot stays the landing gate.
DECIDED (manager) | design wins outright over open PRs for what is on screen; limits: 未実装の案/案A／B need a pick unless the spec picked; items/order/plans/billing = written spec (§7.2 of PR #188 is not owner spec); no board (#365 mute line, all English) = follow ds, list as design to-do; on-screen sentences must be true in code.
DECIDED (manager, supersedes the earlier "OFF stays") | #191 OFF = per board p149 「おやすみモード中は鳴らさず、通知だけ残す」: switch OFF + any OS DND -> no ring, notification + MISSED. ON unchanged. Tell the owner (not a question). Revert one commit. The notes' old merge condition "OFF・アラームのみで鳴って止められる" is void; ops requests (a)(b) become "does not ring, notification stays".
DECIDED (manager) | #188 follows p154: row placement col 4, ring screen col 2 (snooze big too, no snooze chips in large mode, small dial, 22px title). Pricing = owner Q10.
OWNER RULING 2026-11-14 (relayed by the manager) 「CIはローカルで通れば通ったものとみなしていいよ」 | gate G3: a local run of the repo's CI checks for the touched files (lint, tests, build, gate scripts such as scripts/hardcoded-ja/check.mjs) passing on the exact head to merge counts as checks passed; post LOCAL-CHECK (command, head, pass/fail, time); never claim hosted CI green unless it is; a later hosted failure on that head = defect, fixed at once in a follow-up PR. Review naming the head, evidence image and excluded kinds unchanged.
GitHub secondary rate limit (content creation) active since ~12:56Z; retries 12:57Z, 13:08Z refused. Queue: scratchpad drain.sh (PRs #306 #269 #301 #220 #211, #124 reason comment); next try 13:29Z, 30 s apart, stop at first 403.
needs owner (manager's picks in brackets):
  Q1 #68 accept a muted Android run as the device record? [yes]
  Q2 #203 delete the タイトルコード setting? [delete]
  Q3 #207 new users: event with no calendar reminder -> (a) silent or (b) ring at 開始時 by default as a visible switch; existing users unchanged? [b, yes]
  Q4 #241 repeating alarm rang 7:00, moved to 22:00 the same day: ring tonight? [yes]
  Q5 #244 add この回だけ／全部の回 for mute and minutes-before? per-occurrence wins [yes, after launch items]
  Q6 #245/#286 start share-the-ring-log with titles always removed? [yes]
  Q7 #315 approve the reason table (account / calendar / ring-mode / all-day / invite / type / title word / this event; all-muted only on the band) [yes]
  Q8 #237/#268 allow one database migration that re-keys per-calendar settings? [yes]
  Q9 #208/#213/#215 leave popup/email display and bulk apply until after the first release? [yes]
  Q10 #154 appearance all free in the first release? [yes]
  Q11 #191: default of 「おやすみモード中も鳴らす」 is ON, and ON without the DND-policy permission now acts as OFF (board p149 rule set by manager). Most users have not granted it, so during DND they get no ring, only the 鳴らせなかった notification. Keep (board) or ring anyway until granted? [keep board / ring until granted]
  plus: #191 OFF behaviour (keep follow-the-OS vs board's notification-only) [keep]; #194 CI macOS cost approval (free while public) ; text-only screen PRs: may CI-emulator shots count? [yes]; #306 decided for the owner (B) — tell him.
rule 1(b): 11 open items carry claude-cloud; the others go first.
rule 1(a): Project 10's Iteration cannot be read from this session (GraphQL and user-level project REST both 403). Moved to front by 1(a): 0 (unknown). -> for main session.
manager (Fable high) spawned for scene (a): pass line and policy pending.
for main session: (1) please send this week's and next week's Iteration items of Project 10 for calendar-alarm (this session cannot read the board). (2) the cloud lane owns #365 #363 #191 #188 #373 #374 #376 #358; Mac lane owns #370 #189 and the device evidence for #365 #363 #191 #188 #373.

## 2026-11-14 12:20 UTC の進み

- 取り込み: #372（10247cb、#371 を閉じた）。独立レビュー must 0 を a0a68d6 で確かめ、CI 全部成功で入れた。
- 画面の 4 つの PR の独立レビューはすべて must 0（その head で CI 全部成功）。残りは ops の実機の証跡だけ（DEVICE）。
  - #365 23ec50d: https://github.com/kouiso/calendar-alarm/pull/365#issuecomment-6876886952
  - #188 6398aca: https://github.com/kouiso/calendar-alarm/pull/188#issuecomment-6876945779
  - #191 523163f: https://github.com/kouiso/calendar-alarm/pull/191#issuecomment-6876945995
  - #363 1ef6a65: https://github.com/kouiso/calendar-alarm/pull/363#issuecomment-6876946144
  - master が #372 で進んだので、取り込む直前にそれぞれへ master を取り込み直す（コードが変われば別のレビュー）。
- 新しい PR #373（#367 の残りの画面の文言。タブ・テーマ・タイマーの既定・天気など。基準 309 → 275）。Refs #367（天気の表示を切り替える所は AgendaScreen.kt と RingingActivity.kt にあり、画面の PR と重なるので後で）。独立レビュー中。実機の証跡（ja・en）が要る。
- 新しい PR #374（テストだけ: plurals も日英の食い違いを見る。#365 のレビューの should）。独立レビュー中。画面は変えないので証跡は要らない。

## 2026-11-14 12:05 UTC の進み

- 取り込み: #369（b831a9c）。#289 を閉じた。
- #365: 判断役の「基準を 5 から 6 に上げる」は、入れた検査が base より上げるのを止めるので通らない。代わりに #291 を #365 に入れた（23ec50d）。shared は種類と数だけを返し、Android は 6 つの 1 行を strings.xml（ja・en、数のある 2 つは plurals）から引く。基準は 309 から 304。iOS は自分の Swift の文言を持つので変わらない。戻し方: 23ec50d の revert。shared 116 件・androidApp 210 件 失敗 0。
- #188（6398aca）・#191（523163f）: 新しい文言を strings.xml へ移し、master を取り込んだ。#191 は AlarmRepository.kt の衝突を解いた（PR の markDndSuppressed と master の条件付き markMissed の両方を残した）。#191 は 237 件・#188 は 211 件 失敗 0。
- #372: 7c72dfa で must 0。`verify (control)` が 2 回とも emulator の `adb: device offline` で落ちた（そのレーンは固定の対照 3d106817 をビルドする）。master を取り込んで（a0a68d6）CI をもう一度走らせた。
- #365・#188・#191 の独立レビュー中。

### ops への依頼の head（2026-11-14 12:05 UTC 時点。撮る前に PR のページで最新の head を確かめる）
- #363: 585e6ab（変わらず）
- #188: 6398aca
- #191: 523163f
- #365: 23ec50d（日本語と英語の両方で、予定の画面と設定の画面の状態の 1 行）

## 2026-11-14 11:40 UTC の進み

- #369: 独立レビュー 4 回目で must 0・should 0（head 0991bb6、https://github.com/kouiso/calendar-alarm/pull/369#issuecomment-6876669337 ）。CodeRabbit の 3 スレッドは直して解決。CI の `verify (fix)` が終われば取り込む。
- #371 → PR #372（止めて 10 分以内の出し直しは鳴らさない）: 独立レビュー 2 回で must 0（head 7c72dfa、https://github.com/kouiso/calendar-alarm/pull/372#issuecomment-6876702532 ）。時計が戻った時に黙り続ける should を直した。DB の移行は無い。5d1e06c の `verify (control)` は emulator の `adb: device offline` で落ちた（この PR のコードはそのレーンでビルドされない。PR にコメント済み）。7c72dfa の CI が通れば取り込む。
- #188・#191: 新しい日本語の文言を strings.xml（ja・en）へ移している（判断役 packet 3）。#369 が入った後、各 PR に master を取り込む。
- 台帳: Mac の `status.tsv` はこのクラウドの機械からは読めない。このセッションの台帳はこの記録（PR #358）。

## 2026-11-14 11:26 UTC の進み

- #368（#366 ロックの外の書き込み）: 取り込み済み（37e52ae）。
- #369: 1 回目のレビューの must・should を直した（b3719e3）。2 回目の独立レビュー（https://github.com/kouiso/calendar-alarm/pull/369#issuecomment-6876559801 ）で must 1（PR が基準の数を実際より下げても通り、その後の master が落ちる）。should 2（検査しない置き場、名前の変更・分割の通り道）。直している。直したら別のレビューをかける。

### 判断役の決定（2026-11-14 11:25 UTC、packet 3）
1. 通知を元にしたアラーム（`nf:` の id）が、止めた後に同じ通知の出し直しで鳴り直す件: 止めてから 10 分の固定の窓。
   - 同じ鎖（`nf:X` とスヌーズの子）に PENDING か FIRED があれば何もしない。
   - DISMISSED（利用者が止めた）なら、最後に止めてから 10 分未満は鳴らさず、鳴動ログに 1 行書く。10 分以上なら新しいアラームとして鳴らす。
   - それ以外（行が無い、MISSED・CANCELLED・SKIPPED だけ）はすぐ鳴らす。
   - 窓は出し直しで延びない。止めた時刻はプロセスが落ちても読めること（DISMISS の記録の行を使う。読めなければ `triggerAtMillis` から数え、鳴る側へ倒す）。確かめと書き込みは `ringTransitionLock` の中。
   - 受け入れる限界: 同じキーの本当に新しい通知が 10 分以内に来ても鳴らない。
   - 戻し方: 窓の長さを 0 にすれば今の動きに戻る。issue にして直す（DB の列を足さずに済む形を先に試す。列を足すなら DB の移行なので取り込まず OWNER に回す）。
2. #369 と画面の PR の順: #369 を先に入れる。その後、各画面の PR に master を取り込み、新しい文言を移す。
   - #363: そのまま通る。
   - #188: 「停止」2 つは既存の `common_stop` を使う。「停止ボタンを大きくする」は `strings.xml`（ja・en）へ。テストは `ja` を固定するか、文言をリソースから読む。
   - #191: 設定の文言 5 つと通知チャネル名 2 つを `strings.xml`（ja・en）へ。`markDndSuppressed(` は #369 で既に診断の呼び出しとして除外した。
   - #365: 例外として、`shared/.../logic/RingStatus.kt` の基準を 5 から 6 に上げる（shared には strings.xml が無い。#291 で種類と数に置き換える）。ほかの基準の引き上げは認めない。

## 2026-11-14 09:54 UTC の進み（再開後）

### 2026-11-14 10:16 UTC までに終えたこと
- 取り込み: #361（1a5eceb、先に入れた）、#360（2e975f8、#361 と合わせた後の独立レビュー must 0、CI 全部成功）。
- 閉じた issue: #282（#361 で完了）、#257（#360 で完了）。#258 は判断役に回した。
- #191: CodeRabbit の 4 件を直した（760b544）。独立レビューの must（設定を読めない時に全画面が出なくなる）を直した（b8168e4、仮の通知も鳴らす側の既定で出す）。4 スレッドは返信して解決。再レビュー中。
- #188: CodeRabbit の読み上げの指摘を直した（e759485）。独立レビューの must（行の高さが 56dp から 44dp に縮む）を直した（61c1be6）。再レビュー中。
- 新しい PR #365（#271「すべてミュート中」を状態の 1 行に）。多言語の作業（Q77）の前段（#291 の前提）。独立レビュー中。
- 判断役に 4 件（#258 を閉じるか、外に残る 3 か所の書き込みを直すか、Q77 の「済み」の基準、#289 が依頼文の決まりに当たらないか）。

### 判断役の決定（2026-11-14 10:27 UTC、packet 2）
1. #258: #360 で済んだとして閉じた（理由と、REPLACE が安全な条件をコメントに書いた）。#232 は開いたまま（「書き戻しと状態の変更が入り混じらない」と読み替え）。
2. 再同期の外に残る、読んでから無条件に書く所: #366 を作り（#232 の子）、今直している。重さは #232 より軽い（主に間違った見逃しの通知）。
3. Q77（多言語）の「済み」: Android と shared で日本語と英語がそろい、shared は種類を返し、#289 で新しい直書きを止める。iOS は Mac の列で別に数える（#295・#296・#312 が閉じるまで Q77 全体は済みにしない）。足りない画面の文言は #367 を作った。鳴動の記録の説明文はこの段階では対象外（持ち主が違う考えなら言ってほしい）。shared の既定のタイトル（`AlarmExpander.kt`）を変える時は iOS の #312 と合わせる。
4. #289（日本語の直書きを CI で止める）: 依頼文の決まりに当たらない。`androidApp/src/main/java` 全体を見て、コメントは除き、基準は減るだけにする。

### 2026-11-14 11:14 UTC の状態（新しい manager への答え）

- 開いている PR: #368（#366 ロックの外の書き込み）、#369（#289 日本語の直書きの検査）、#365（#271）、#363（#204）、#191（#149）、#188（#154）、#189（Mac の列）、#358（この記録）。
- push していない作業: 0。ローカルにだけある `fix/232-resync-race` は、確かめのための一時のコミットだけで、中身は取り込み済みの #360 にある。`chore/advisor-fable` はコミットが無い。どちらも push しない。
- 止まった時のこと: アカウントの週の上限で #368・#369 のレビューが途中で止まった（11:25Z に解けた）。同じレビューを続きから再開した。
- 次の 5 つ:
  1. #368 のレビュー → must 0 なら取り込む（画面は変えない。証跡はテストで足りる）。
  2. #369 のレビュー → must 0 なら、画面の PR との順を決めて取り込む（画面の PR は日本語の文言を足すので、基準の扱いを決める）。
  3. #366 を閉じ、#232 の残りを確かめる。
  4. #291（RingStatus を文ではなく種類と数で返す）を #365 の後に作る。
  5. #313・#290 など、画面の PR と重ならない文言の移し替えを順に。
- 新しい manager の決まり: 取り込む前に、実装した者以外のレビューが head を名指しして PR に載っていること。料金・DB の移行・公開範囲・本番の変更は取り込まない。5 件ごとに記録を更新して push する。待つだけの時は、何を待つかを書いて止まる。

### 画面が変わる PR の止まり方（2026-11-14 10:24 UTC に整理し直した）

- 止めているのは持ち主の許可ではなく、リポジトリの決まり（`doc/test-spec.md`「鳴動・UI系の修正は実際に鳴らして止めた動画/スクショを PR に添付する」）。持ち主の言葉「レビューが通った PR は聞かずに取り込む」により、証跡がそろえば聞かずに取り込む。取り込みは持ち主に代わって決めたこととして記録し、元に戻す方法は squash コミットの revert。
- そのため、画面の確認は OWNER ではなく DEVICE（ops への依頼）に移す。
- 取り込む順: #363 → #188 → #191 → #365。3 つとも `SettingsScreen.kt` を変えるので、1 つ入れるたびに次の PR へ master を取り込み直し、その head で証跡を撮る。ops には 1 つずつ頼む。

### ops への依頼（実機。先に端末を消音し、そのことを記録する。Mac mini では emulator・シミュレータを使わない）

1. #363（head 585e6ab。master を取り込み直した。androidApp 199 件 失敗 0）: 360dp 相当の幅・文字 1.3 倍で、設定の招待予定・通知タイミング・スヌーズの選択肢の行の、master と PR の前後のスクリーンショット。全部のチップが見えて押せること。
2. #188（head 61c1be6）: 「停止ボタンを大きくする」ON の鳴動画面と、他アプリの上のオーバーレイの大ボタンのスクリーンショット、押して止まる動画。TalkBack でその行が見出しとスイッチの状態を 1 つとして読むこと。
3. #191（head ae7e338。#360 などとの取り込みの後の独立レビュー must 0: https://github.com/kouiso/calendar-alarm/pull/191#issuecomment-6875880858 ）: 「おやすみモードを無視」OFF で、OS のおやすみモードを (a) アラームのみ (b) アラームを許可した重要な通知のみ (c) サイレント にした時の動き（a・b は鳴って止められる、c は鳴らずに「鳴らせなかった」の通知）。ON で通知ポリシーの許可が無い時のスイッチの下の案内の行のスクリーンショット。判断役の取り込みの条件（OFF・アラームのみで鳴って止められる）はこの (a)。
4. #365（head 4a966fc）: 一括ミュートを ON にした時の予定の画面と設定の画面の状態の 1 行のスクリーンショット（日本語）。

### OWNER に残るもの
- #188・#154: 外観を無料にするか Pro と連動させるか（コードに料金の判定は無い。#154 の行として残す）。
- `.claude/settings.json` の advisor の PR（AI の設定ファイルの書き換えを安全の確認が止めた）。

### 持ち分（manager の指示）
- このクラウドのセッション: Android の PR #360・#361・#188・#191・#363（push・レビュー・取り込み）。
- Mac のセッション「calendar-alarm [macmini]」: #189 の iOS ビルド・テストと実機のスクリーンショット、端末の行、Android の PR に要る実機の証跡（emulator・シミュレータは使わない）。#189 には触らない。

### Mac のセッションへの証跡の依頼（実機で。Mac mini では emulator・シミュレータを使わない（持ち主の決まり）。先に端末を消音し、そのことを記録する）
- #191: 「おやすみモードを無視」OFF で、OS のおやすみモードを (a) アラームのみ (b) アラームを許可した重要な通知のみ (c) サイレント にした時、それぞれアラームが鳴るか・鳴らないか（a と b は鳴って止められる、c は鳴らずに「鳴らせなかった」の通知）。設定画面で ON かつ通知ポリシーの許可が無い時の案内の行のスクリーンショット。
- #188: 「停止ボタンを大きくする」ON の鳴動画面と、他アプリの上のオーバーレイの大ボタンのスクリーンショット、押して止まること。TalkBack でその行が「停止ボタンを大きくする、スイッチ、オフ」のように読まれること。
- #363: 360dp・文字 1.3 倍で、設定の招待予定・通知タイミング・スヌーズの選択肢の行の前後のスクリーンショット。

### 今の動き
- #360: 前の head 609a682 の陰性対照のレーンが一度落ちた（emulator の adb の接続の問題。この PR のコードは対照のレーンでビルドされない）。誰かがそのレーンを再実行し、その再実行が同じ concurrency の群の新しい head 89dd58e の実行を取り消した。ジョブの再実行と手動の dispatch は、このセッションの権限では 403。順を入れ替え、先に #361 を入れ、その後 #360 に master を取り込む（本当の変更なので CI が走る。resync が重なるのでレビューもかける）。
- #361: master を取り込んだ（0c2b1fe、この PR の変更は 8c28816 と同じ、shared 115 件・androidApp 192 件 失敗 0）。CI 待ち。通ったら取り込む。
- #191: CodeRabbit の指摘 4 件を直した（760b544、androidApp 215 件 失敗 0）。独立レビュー中。
- #188: CodeRabbit の指摘（大ボタンの切り替えに読み上げの名前が無い）を直した（e759485）。スレッドは返信して解決。独立レビュー中。
- `.claude/settings.json` の advisor の PR はまだ止められたまま（OWNER）。

## 引き継ぎ（新しいセッションはここだけ読めば続けられる）

### 取り込みの権限
- 持ち主の常の言葉（manager 経由、このチャットで受けた。2026-11-14）:「レビューが通った PR は聞かずに取り込む」。
- ただし依頼文の決まりで、画面が変わる PR は持ち主の画面確認の後にだけ取り込む。
- 取り込み方: squash。取り込む前に、独立レビュー must 0・CI 成功（今の head）・未解決の指摘 0 を確かめる。
- 元に戻す方法: 取り込んだ squash コミットを `git revert` する PR を作る。閉じた PR・issue は開き直せる。

### 取り込んだ PR（2026-11-14）
- #362（7561d42）versionCode を配布ごとに上げる（#309）
- #359（f780ef6）既定の「何分前」「スヌーズ」の変更で再同期（#231 / #254）
- #364（e129dd5）ci.sh で UTF-8 のロケールを設定（#297）

### 閉じたもの（2026-11-14、理由はそれぞれにコメント済み）
- PR #141（広告。持ち主 Q72「入れない」。ブランチ `devin/1792919311-pbi140-ads` は残した）
- issue #140・#196（広告。not planned）、#254（#359 で完了）、#297（#364 で完了）

### 開いている PR と止まっている理由
| PR | head | 状態 | 次の一手 / 待ち |
|---|---|---|---|
| #360（#232） | 609a682（master を取り込み済み、衝突なし、手元 193 件 失敗 0） | CI の emulator の 2 レーンが実行中だった。元の head aa78f2b は独立レビュー must 0。取り込みの後の差分は行番号だけ違う（同じ変更）。#359 の見張りとロックの関係の確認レビューは投稿済み（must 0: https://github.com/kouiso/calendar-alarm/pull/360#issuecomment-6873870939） | 1) CI を確かめる 2) 上の確認レビューの判定が must 0 なら 3) squash で取り込む 4) #257 だけ閉じる（#232 の「同時に走らない」は満たしていない。外に残る 3 か所: `cancelStaleWaitingEvents`・`markMissed()`・`rearmWaitingRings`） |
| #361（#243） | 8c28816 | 独立レビュー must 0（https://github.com/kouiso/calendar-alarm/pull/361#issuecomment-6873615277）、CI 成功 | #360 の後に master を取り込む。`resync` が 3 つの PR で重なるので、手で直した衝突があれば本当のレビューをかける。その後取り込む。#282 を閉じる |
| #191（#149） | ebd28fe | 独立レビュー must 0（https://github.com/kouiso/calendar-alarm/pull/191#issuecomment-6873815540）、CI 全部成功（emulator の鳴動・停止レーンの証跡: actions run 43570843636） | 画面が変わるので持ち主の画面確認待ち（OWNER）。#359〜#361 の後に master を取り込み、取り込みをレビューしてから。DEVICE: おやすみモード中の ON/OFF の端末での比較 |
| #188（#154） | aecf600 | 独立レビュー must 0（https://github.com/kouiso/calendar-alarm/pull/188#issuecomment-6873609241）、CI 成功 | OWNER: 画面確認、外観の料金（無料か Pro か）。DEVICE: 大ボタン ON/OFF の見た目と停止、TalkBack |
| #363（#204） | 0e7eb9e | 独立レビュー must 0（https://github.com/kouiso/calendar-alarm/pull/363#issuecomment-6873503995）、CI 成功 | OWNER: 画面確認。DEVICE: 前後のスクリーンショット |
| #189（#147） | fc92a5f | 独立レビュー must 0（読んだだけ）、CI（Android のみ）成功 | DEVICE: Mac での iOS ビルドとテスト 2 件、実機で 2 つ以上のプリセットが鳴る。判断役の決定で「選ぶ」の近くに使える形式と先頭約 30 秒の説明を足す（Swift、Mac で） |
| #358 | - | この記録（draft） | - |

### 次の 10 行（この順）
1. #360: CI を確かめ、上の 2)〜4) をして取り込む。
2. #361: master を取り込み、テスト、必要ならレビュー、取り込む。#282 を閉じる。
3. #191: master を取り込み、テストと取り込みのレビュー。画面確認を OWNER に残す。
4. 判断役（Fable high）に 1 件: #360 の「書き戻しだけをロックに入れる」形で #258 を満たすか（#258 の文は「全体を同じ Mutex で」）。
5. 新しい不具合の候補を issue にするか判断: 再同期の外で読んでから無条件で書く 3 か所（上の #360 の 4)）。
6. 多言語（持ち主 Q77「多言語の仕組みは最初に作る、公開は日本向け」）: まず #290（AgendaScreen の文言を文字列の資源へ）と #271→#291（RingStatus の種類）が、開いている PR とファイルが重ならないか確かめる。重ならない物から。
7. #313・#291 は shared の関数の形を変える。iOS（`iosApp` と `SharedBridge.kt`）が使っていないか先に grep する。使っていれば古い入口を残すか DEVICE にする。CI に iOS のビルドは無い。
8. #289（画面のコードに日本語を直書きしたら CI で止める）を「仕組みを先に」として検討。依頼文の「自由文の意味を正規表現で判定しない」に当たらないか先に確かめる。
9. #288（SettingsScreen）と #292（鳴動画面・通知）は、#188・#191・#363 が入るまで待つ（同じファイル）。
10. 判断役に 1 件: Q77 の「済み」の基準（2 つ目の言語は英語か、iOS も同時か）。

### 持ち主待ち（OWNER）
- 画面確認: #191・#188・#363・#189。
- #188: 外観を無料にするか Pro と連動させるか。
- `.claude/settings.json` に `"advisorModel": "fable"` を足す PR: AI の設定ファイルの書き換えをこのセッションの安全の確認が止めた。持ち主が自分で足すか、この操作を許す設定が要る。
- issue の OWNER と、新しい機能で着手の許可が要る物の一覧は、下の「残りの issue 145 件の仕分け」にある（多言語の物は Q77 で許可が出たとみなす）。

### 端末待ち（DEVICE）
- 下の「残りの issue 145 件の仕分け」と「issue の仕分け（PR が紐づく 29 件）」の DEVICE の節にすべて書いてある。
- #236: 先に #265（`emulator_verify.yml` で再起動後・解除前の再現を記録できる見込み、未確認）。その後 #266。
- 音は出さない（持ち主 Q71 の時間は過ぎた）。emulator の CI レーンは今までどおり。

### 環境（新しいクラウドのセッション）
- Android SDK を入れる: commandlinetools を `/opt/android-sdk/cmdline-tools/latest` に置き、`sdkmanager "platforms;android-35" "platform-tools" "build-tools;35.0.0"`。`local.properties` に `sdk.dir=/opt/android-sdk`（git の管理外）。
- `scripts/ci.sh` はロケールを自分で直す（#364）。`./gradlew` を直接動かす時は `LC_ALL=C.UTF-8`。
- GraphQL は使えない。REST の `gh api repos/kouiso/calendar-alarm/...` を使う。コメントを書く時は `-F body=@file`（`-f` だとファイルの中身が送られない）。
- 主の model: Opus 5.5 medium、advisor: Fable（`/advisor fable`）。Sonnet・Haiku の作業者は、助言が要る時は lead に上げる。

# クラウドでの引き継ぎ作業の記録（calendar-alarm）

新しい記録が上。数字は PR か issue の番号。

## 2026-11-14 04:54 UTC 時点

区分ごとの数: a（マージできる）3 / b（ここで直せる・レビュー中）2 / c（端末・画面・持ち主の判断が要る）4 / d 0
対象外: #141（広告。触っていない）

| 番号 | 区分 | やったこと | head | checks | レビュー | マージ済みか / 足りないもの |
|---|---|---|---|---|---|---|
| #363（#204） | b | 設定のチップの行（招待予定・通知タイミング・スヌーズの選択肢）を折り返すようにした。360dp・文字 1.3 倍で右端のチップが幅 0 に潰れるのを、テストで再現してから直した | 0e7eb9e | 手元 unit 189 件 失敗 0、lint 成功 | [must 0（3a7f1f5）](https://github.com/kouiso/calendar-alarm/pull/363#issuecomment-6871929498)。should 1 は直した。差分の再レビュー中 | 未マージ。画面が変わるので、持ち主の画面確認と前後のスクリーンショットが要る（区分 c になる見込み） |
| #362（#309） | a | versionCode を `-PversionCode` から読み、配布の workflow が run number を渡す。手元で合成後の manifest を見て確かめた。配布の workflow は動かしていない | 68166d0 | CI 全部成功 | [must 0](https://github.com/kouiso/calendar-alarm/pull/362#issuecomment-6871833158) | 未マージ。持ち主の「merge #362」で入れられる。番号が上がることは次の配布で確かめる |
| #361（#243） | b | 予定の時刻が動いても予定ごとの指定を引き継ぎ、終わった回の指定を消す（shared の OverrideCarry と再同期）。レビューの must 2 回（別の回へ付け間違える 2 つの形）を直した | cb05f1f | 手元 shared 114+114 件・androidApp 189 件 失敗 0、lint 成功 | [1 回目 must 1](https://github.com/kouiso/calendar-alarm/pull/361#issuecomment-6871836927)、[2 回目 must 1](https://github.com/kouiso/calendar-alarm/pull/361#issuecomment-6871963689)。3 回目のレビュー中 | 未マージ。レビューの結果待ち。iOS（#283）は Mac が要る |
| #360（#232） | a | 前の表のとおり | aa78f2b | CI 全部成功 | [差分の再レビュー must 0](https://github.com/kouiso/calendar-alarm/pull/360#issuecomment-6871728088) | 未マージ。持ち主の「merge #360」で入れられる |
| #359（#231） | a | 前の表のとおり | 804760a | CI 全部成功 | [差分の再レビュー must 0](https://github.com/kouiso/calendar-alarm/pull/359#issuecomment-6871690563) | 未マージ。持ち主の「merge #359」で入れられる |
| #236 | c | 手を付けていない | - | - | - | ロック解除の前に鳴らすには、受信・鳴動のサービス・音を、DB を読めない状態（Direct Boot）で動かす必要がある。エミュレータで確かめずに入れると、起動時に落ちる危険がある。子の #265（今は鳴らないことをエミュレータで記録する）が先。推し: #265 をエミュレータのある端末で行い、その後で #266 を小さく作る |

### マージの順の提案

1. #362（ビルドの設定だけ。他と重ならない）
2. #359 → #360 → #361（どれも `AlarmRepository.kt` を変える。1 つ入れるたびに、次の PR へ master を取り込み直し、テストと差分のレビューをやり直す）
3. 画面の PR（#191・#188・#363・#189）は、持ち主の画面確認の後。#188・#191・#363 は `SettingsScreen.kt` を変えるので、同じく 1 つずつ。

## 2026-11-14 04:27 UTC 時点（再開後）

区分ごとの数: a 0 / b 2（#359・#360。差分の再レビュー待ち）/ c 3（#191・#188・#189）/ d 0
次: #243 → #236・#204・#309

| 番号 | 区分 | やったこと | head | checks | レビュー | マージ済みか / 足りないもの |
|---|---|---|---|---|---|---|
| #360（#232） | b | 再同期の書き戻しを鳴動・停止・スヌーズと同じロックに入れ、途中で鳴った・止めた・スヌーズした行を PENDING に戻さない。テスト 4 件（直す前は落ちる） | aa78f2b | 手元 unit 190 件 失敗 0、lint 成功 | [must 0（15a73c6）](https://github.com/kouiso/calendar-alarm/pull/360#issuecomment-6871643208)。should 1 は直した。差分の再レビュー中 | 未マージ。画面は変えない。持ち主の「merge #360」で入れられる |
| #359（#231） | b | 「何分前」「スヌーズ」の既定を変えたら再同期する見張りを足した。テスト 3 件（直す前は 2 件が落ちる） | 804760a | 手元 unit 189 件 失敗 0、lint 成功、3 回の再実行で成功 | [must 0（fe3ae60）](https://github.com/kouiso/calendar-alarm/pull/359#issuecomment-6871554433)。should 2 件は直した。差分の再レビュー中 | 未マージ。画面は変えない。持ち主の「merge #359」で入れられる |

### 新しい不具合の候補（issue にはしていない）

- 再同期の外にも、行を読んでから状態を無条件で書く所が残っている。`cancelStaleWaitingEvents`（CANCELLED）、`markMissed()`（MISSED）、`rearmWaitingRings`（`setPendingIfFired` の前後）。読んだ後に停止が入ると上書きされる。提案: どれも `ringTransitionLock` の中で読み直すか、条件付きの UPDATE にする。（#360 のレビューの should 2）
- #359 と #360 は同じ `AlarmRepository.kt` を変える。後でマージする方は、master を取り込み直して、もう一度テストとレビューを通す。

## 2026-11-14 04:05 UTC 時点

区分ごとの数: a（マージできる）0 / b（ここで直せる）0 / c（端末・画面の証拠・本番・持ち主の判断が要る）3 / d（不要・置き換え済み）0
対象外: #141（広告。触っていない）
次: #231 → #232 → #243 → #236・#204・#309

| 番号 | 区分 | やったこと | head | checks | レビュー | マージ済みか / 足りないもの |
|---|---|---|---|---|---|---|
| #191 | c | master を取り込んだ。チャンネルの選び方を `ringChannelId(vibrate, ignoreDnd)` の 1 つにし、停止手段の判定・MainActivity・検査画面に通した。テスト 2 件を追加（直す前は 2 件とも落ち、直した後は通る）。manifest の表に権限 #15 を追加。本文を差分に合わせた | 15bb42f | CI 全部成功。手元 androidApp unit 198 件 失敗 0、lint 成功 | [must 0](https://github.com/kouiso/calendar-alarm/pull/191#issuecomment-6871389425) | 未マージ。足りないもの: OFF の意味の判断（局長確認 9）、おやすみモード中の ON/OFF の端末での比較（確認 8）、持ち主の画面確認 |
| #188 | c | 持ち主のスレッドの指摘どおり、フォントと iOS の範囲を「オーナー判断待ち」にした。master を取り込んだ。レビューの should に合わせ `largeButtons` の既定値を外した。本文を差分に合わせた。スレッドは返信して解決済み | 5f172b2 | CI 実行中（前の head bca0e14 は成功）。手元 unit 188 件 失敗 0、lint 成功 | [must 0](https://github.com/kouiso/calendar-alarm/pull/188#issuecomment-6871353363)、[差分の再レビュー must 0](https://github.com/kouiso/calendar-alarm/pull/188#issuecomment-6871414155) | 未マージ。足りないもの: 無料か Pro と連動するか（局長確認 7）、フォント・iOS の範囲の判断、端末での ON/OFF の比較（確認 8）、持ち主の画面確認 |
| #189 | c | master を取り込み、project.pbxproj の 1 か所の衝突を git で直した（両方の行を残した）。本文の食い違い（iOS のビルド成功と書きつつ未確認）を直した。足りないテスト 2 件は Linux に Swift が無いので書いていない（not run） | fc92a5f | CI（Android のビルドのみ）成功 | [must 0（読んだだけ）](https://github.com/kouiso/calendar-alarm/pull/189#issuecomment-6871360657) | 未マージ。足りないもの: Mac での iOS ビルドとテスト 2 件、実機で 2 つ以上のプリセットが鳴る確認（確認 8）、持ち主の画面確認 |

### 直していない指摘（should 以下）

- #191: 鳴動の設定（バイブ・おやすみモード・全画面）がサービス全体の値で、同時の要求で取り違える余地がある（master も同じ形）。設定の読み取りが時間切れの時、短い間 bypass 側の通知が出る。検査画面の `collect(ignoreDnd = true)` に既定値がある。
- #188: 鳴動画面の最初の 1 コマはスライダー。オーバーレイの大ボタンに押した時の波紋が無い。
- #189: 新しい文字列 4 つが Localizable.xcstrings に無い（英語でも日本語で出る）。チップの「標準」と上の行の「iPhone の標準」で呼び名がずれる。#147 の「任意の MP3 は使えないと説明文に書く」が入っていない。同名ファイルがあるとコピーし直さない（音を差し替えても古いまま）。どれも Swift のビルドが要るので、ここでは直していない。

### マージの順の注意

- #188 と #191 は SettingsScreen.kt・SettingsRepository.kt・AlarmService.kt の同じあたりを変える。後でマージする方は、master を取り込み直して、もう一度独立レビューが要る。

## 待っているもの（持ち主）

マージは持ち主がこのチャットで「merge #N」と打った時だけ行う。3 つとも画面が変わるので、持ち主の画面確認も要る。

- #191 の「確認 7」: 依頼文には #191 の確認 7 の答えが無いとあるが、#191・#149 に「確認 7」は無い。#191 で番号が付いているのは 8（端末での比較）・9（OFF の意味）・11（取り込みの時のチャンネル。今回直した）。局長確認 7 は #188 の「外観は全部無料で、Pro と連動しない」のこと（下に別に書いた）。#191 の未決は確認 9 とみなし、提案の文を残す。
  - 確認 9 の提案: 「OFF の時、OS が『アラームのみ』や『重要な通知のみ（アラームは許可）』なら鳴らす」。選択肢: (1) 今のまま、おやすみモード中は全部黙る (2) OS がアラームを許す設定の時は鳴らす。推し: (2)。寝過ごしの危険が小さいため。
- #191: #149 の「権限が無い時はトグルを無効表示」は入っていない。今はトグルはいつでも押せて、案内は検査画面に出る。選択肢: (1) このままでよい (2) 無効表示を足す。推し: (1)。OFF は許可が無くても意味があるため。
- #188 の確認 7: 外観（大ボタン・テーマ・背景）を全部無料にし Pro と連動させないか。選択肢: (1) 全部無料 (2) 一部を Pro に。推し: 判断材料が無いので持ち主に任せる。仕様書は「判断待ち」と書いてある。
- #188: フォント選択を持つか、iOS をどこまで合わせるか。仕様書の提案はフォント無し・iOS はテーマ追随のみ。

## 環境のこと（2026-11-14 時点）

- Android SDK（cmdline-tools・platform 35・build-tools 35.0.0）はこのコンテナに入れられた。止められた host は無い。
- Gradle は `LANG=C.UTF-8` が要る。無いと日本語のテスト名のクラスファイルが書けずにビルドが落ちる。
- Swift・Xcode は無い。iOS のビルドとテストは回せない。
- エミュレータ・実機・音は使っていない。
- GitHub の GraphQL はこのセッションでは使えない（REST のみ）。

## 新しい不具合の候補（issue にはしていない）

- 設定画面の各行が、保存の後に自分で再同期を呼んでいる（#231 の根）。保存する層から再同期を呼ぶ形に寄せた後、画面側の呼び出しを外す片付けは、#188 と #191 が入った後の別の PR にする提案。
