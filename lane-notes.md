STATE RUNNING 2026-11-14T12:50Z | counts pending
mission: それぞれのプロジェクトに書いてあるプランに書いてある作業をやること。どのプロジェクトもまず既存のPRを本質的に対処することを作業を優先してください。そうしないと溜まってばっかりなので
model claude-opus-5-5 (lead, medium) | advisor present | github via gh | push via git | png ok (to be confirmed at first evidence)
goal: Work the plan calendar-alarm as its LEAD (Mac lane) until DONE or DONE-WAITING | DONE-WAITING satisfies it: yes
repo rules read: AGENTS.md, doc/test-spec.md (screen/ring PRs need real-device evidence), .github/scrum-board.yml (project 10), unresolved review threads 0 before merge (copied from the cloud lane notes on PR #358)
DEPLOY | merge to master -> nothing (debug APK artifact only) | evidence .github/workflows/all_check.yml:50-55; fad.yml:1-3 workflow_dispatch only; ios-device-sync.yml daily cron may dispatch fad.yml (iOS) = staging, independent of merges (copied from PR #358 notes)
---
calendar-alarm [Mac lane]. The cloud lane (PR #358 notes) owns #365 #363 #191 #188 #373 #374 #376 #378-#385 and #358. This lane owns #370 #189, the device evidence, and the ledger status.tsv (Mac-only, ~/.claude/plans/takeover-20261111/project/calendar-alarm/status.tsv).
START 2026-11-14T12:38Z exec-prompt v2. handoff: ~/.claude/state/session-handoff/calendar-alarm-20261114-restart.md; next step = independent review of #370, then #189 on the iPhone 12 mini, then row 6 on the Pixel.
worktrees 2026-11-14T12:45Z:
/Users/kouiso/ghq/kouiso/calendar-alarm master a844486 clean (untracked .omc/ only, never committed) | no local commits
/Users/kouiso/ghq/_lanes/ca-36 ci/36-testflight-lane clean | master merged in, pushed 1823870
/Users/kouiso/ghq/_lanes/ca-pr189 pr189-work clean | pushed to feat/147-caf-presets 0e6e72a
/Users/kouiso/ghq/_lanes/ca-emu-app detached 1a5eceb clean | nothing to push
/Users/kouiso/ghq/_lanes/ca-main detached 5583ae3 clean | nothing to push
/Users/kouiso/ghq/_lanes/ca-notes notes/calendar-alarm (this file)
DECIDED | adopt the cloud lane's PASS LINE, POLICY and DECIDED lines (PR #358 note/cloud-calendar-alarm-20261113.md, 2026-11-14T12:2xZ) as prior decisions | reason one product, one set of rulings | revert the manager overrides a line
DECIDED | disk / = 19 GiB free at 12:38Z, under the 20 GiB start line: no Xcode/Gradle build until space is freed | reason exec-prompt 5.4 | revert none
BOARD 2026-11-14T13:00Z | Project 10 open items in Iteration 7 (this week) and 8 (next): #68 [PBI] UI/UX rebuild, #200 [SBI] Android lock-screen stop drag (device rows 6/7; no claude-cloud label). rule 2 puts 2 at the front. rule (-ux): 0 seen.
DISK 2026-11-14T13:00Z | / 19 GiB free. Big: ~/ghq 90G, /private/tmp 9.3G, CoreSimulator/Devices 6.6G (3 sims, none booted; sims banned here), ~/.gradle/caches 4.8G. /tmp/mac-heavy-build.lock holds pid 11684 = not running (stale, other session's). No deletion done. Mac load 21 at 13:00Z (PC-LOAD hook blocks local runs).
BRAIN ALERT-11 | Sonnet member: the 3 brain-sync stash commits' line is already in HEAD (6bd140a8c); nothing to apply; clears after 24h.
IN WORK 13:00Z | #370 master merged (1823870), independent Opus review running | manager (Fable high, name ca-manager) scene a running | Pixel member (ca-pixel): stills for #363 #365 #373 #188 #191 from CI APKs, muted, no ringing, files to evidence/
needs owner:
- Free disk for iOS builds (adhoc_local for #189): delete the 3 unused simulators (6.6 GiB; sims banned on this Mac)? recommend yes.
- One sound window for: #191 ring-through-DND case, #189 two bundled sounds, rows 7/12/35/V3.
for main session:
- fad.yml x2 (row 19, #309): the owner's 「してよい」 is quoted only in the cloud notes (relayed by its manager); no source on this machine. This lane will not dispatch it without a source line.
