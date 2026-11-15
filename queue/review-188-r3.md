REVIEW | reviewer: general-purpose subagent, Opus 5.5 | authored no commit on this PR | head 033d15f35a3526a8f1b0a1271e8c0e879cd86c02 | delta d67dbb4..033d15f | axes: pass line, scope, tests, body vs diff, evidence | verdict PASS | must 0 want 2 nit 4

## 指摘
- [want] doc/spec/kmp-replacement.md:282,310,313-317,337-346 — 033d15f が直したのは 322-324 だけ。PR が SettingsScreen.kt の 258 に 9 行、1692 に 49 行、AlarmService.kt の 355 に 1 行を足したので、master では合っていた参照がずれる。例: `AlarmService.kt:374`（ringTimeoutMs）は head で 375、`:919`（CRESCENDO_MS）は 920、「音量を徐々に上げる」は SettingsScreen.kt 1330→1345。ずれ幅は AlarmService の 355 以降が +1、SettingsScreen が +15（1692 以降は +64）
- [want] RingingActivity.kt:402 — `maxHeight - 170.dp` は OFF の値のまま（高さ 740 で文字盤 170dp、盤は 176）。本文の「残り」に書いてある。実機で確かめるまで保留のまま
- [nit] RingingActivity.kt:146 — `initial = false`。ON でも最初の 1 フレームがスライダーになり得る（前から・未確認）
- [nit] RingingOverlay.kt:316-322 — 大きい停止ボタンに押した時の見た目の変化が無い（前から）
- [nit] values/strings.xml:180 — キー名 `settings_large_stop_button` が中身（スヌーズも含めた大きいボタン）と合わない（前から）
- [nit] PR 本文「変えたこと」の master 取り込み — bca0e14 までしか書いていない。d67dbb4・f4f63de が抜けている

## 前の指摘
- must 1（build 赤、TroubleshootingFlagTest.kt:54）: このテストは差分に無い（delta も PR 全体も 0 行）。033d15f の check runs（REST）では build が success。揺れの見立てと合う。コードの直しは無い
- must 2（フォントと iOS の「決定」）: 直っている。§7.2 の 2 か所は「案・持ち主の判断待ち」になり、事実の文は変わっていない。PR の差分と本文に「決定」は残っていない（本文 §7.2 は「範囲の案」）
- want 行番号: 322-324 は直っている（後で確かめた）。ほかは上の want のとおり
- want 本文: 直っている（head 033d15f、基準 cea7184、hardcoded-ja 270）
- want 170dp: 残っている（保留）。nit 3 件: 残っている

## 033d15f の行番号（head で grep）
AlarmService.kt:392-393（autoSnoozeMax の判定）、SettingsRepository.kt:115/190/300（定義・読み・setAutoSnoozeMax）、SettingsScreen.kt:1428-1429、RingingOverlay.kt:248、AlarmRepository.kt:536。どれも書かれたとおりの所を指す

## マージ f4f63de
remerge-diff は 0 行（競合なし）。両方が触ったのは SettingsRepository.kt と strings.xml（ja・en）の 3 つ。どれも master の変更行（31/44/44）と PR の変更行（7/1/1）が、マージ後の差分に 1 行も違わずに残る。競合の印なし。重複キーなし（ja 254・en 253、ja だけにあるのは app_name）。PR のファイルが参照する R.string はすべてある。API: PR は DEFAULT_TIMER_PRESETS を使わない。`palette.label`（SettingsScreen.kt:1524、master と同じ）は @Composable getter（Palettes.kt:230-231）を composable の中で呼ぶ。`WeatherApi.describe` は残る（RingingActivity.kt:469）。AlarmRepository は両方とも触っていない

## ガード（テスト → 消すと落ちる行）
- LargeButtonRingingLayoutTest → RingingActivity.kt:184 `largeButtons = largeButtons`、:489 高さ 124、:508 `if (!largeButtons)`、:401 ringBase 176、:453 予定名 22
- RingingOverlayLargeLayoutTest → RingingOverlay.kt:241 高さ 124、:248 `&& !largeButtons`、:342 間 12、:161 文字 24、:201 予定名 22
- SettingsLargeButtonsOrderTest → SettingsScreen.kt:264 `LargeButtonsCard(...)`（:271 AppearanceCard より前）
- LargeButtonsRowTest → SettingsScreen.kt:1722 `.toggleable(...)`、:1737 `minimumInteractiveComponentSize()`（56dp）
- LargeButtonStopTest → RingingActivity.kt:547 `onClick = onDismiss`・:548 tag
- RingingOverlaySlideStopTest（足した 1 件）→ RingingOverlay.kt:311 分岐・:323 `dismiss(context, instanceId)`・:331 高さ
- ガードはコンパイルだけ: AlarmService.kt:355（:71 に既定値が無い）。`= false` に置き換えても落ちるテストは無い

## CI の対象パス
all_check.yml（build = `ci.sh all`+lint、i18n-check）は paths-ignore が `**.md`・`docs/**` だけなので、androidApp/** と res を含むこの PR で動く。emulator_verify.yml（verify ×3）は `androidApp/src/main/**` で動く。ios_build は iosApp/shared だけが対象で、この PR は触らない

## 手元の検査（localcheck-188-r3.txt）
hardcoded-ja exit 0（270 個）、sync-ai-rule --check exit 0、diff --check exit 0、キー検査 exit 0。Gradle は動かしていない。hosted CI の 7 件 success は参考として見ただけ

## 残り
実機の画像はまだ無い（わかっている残りで、採点しない）
