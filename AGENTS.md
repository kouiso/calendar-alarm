# AGENTS.md

## Overview
Kotlin Multiplatform (KMP) によるカレンダー連動アラームアプリ。
Android (Compose Material3) と iOS (SwiftUI + AlarmKit, iOS 26+) の両実装が `shared` のドメイン層を共有する。

## Structure
- `shared/` — KMP 共有モジュール (ドメイン層: 鳴動展開・オフセット計算・天気クライアント等)
  - `bridge/SharedBridge.kt` — Swift から JSON 経由で共有ドメインを呼ぶブリッジ
- `androidApp/` — Android アプリ (エンジン + Compose UI)
- `iosApp/` — iOS アプリ (SwiftUI + AlarmKit + EventKit + WidgetKit)
  - `project.yml` — xcodegen のプロジェクト定義。変更後は `xcodegen generate` で `CalendarAlarm.xcodeproj` を再生成する
  - `iosApp/SharedKit.framework` は Gradle の `embedAndSignAppleFrameworkForXcode` タスクで自動生成される (ビルドフェーズで実行)

## Setup
```bash
# Android
./gradlew :androidApp:assembleDebug

# iOS (Xcode + xcodegen が必要)
cd iosApp && xcodegen generate
xcodebuild -project CalendarAlarm.xcodeproj -target iosApp -sdk iphonesimulator -configuration Debug build
# 実機配布には App Group `group.com.calendaralarm.ios` をプロビジョニングに登録すること
```

## Code Style
- 日本語でコメント・ドキュメントを記述
- 既存コードスタイルに従う
- コミット件名は英語 Conventional Commits (`type(scope): english subject`)

### Testing
```bash
./gradlew :shared:jvmTest :shared:testDebugUnitTest :androidApp:testDebugUnitTest
```

## AI ごとの入口
- 共通ルールの正本はこの `AGENTS.md`。共通 Skill の正本は `.agents/skills/<skill>/SKILL.md`
- `GEMINI.md`、`.github/copilot-instructions.md`、`.cursor/rules/calendar-alarm.mdc`、
  `.claude/skills/` の中身は `scripts/sync-ai-rule.mjs` が生成する。直接は編集しない
- 正本を変えたら `node scripts/sync-ai-rule.mjs` で生成し直す。`--check` で古い生成物を検出する（CI でも検査）
- Codex・Devin・OpenCode はこの `AGENTS.md` を直接読む
- `.claude/settings.json` と `.github/mcp-config.json` は AI 専用の設定で、生成の対象外

## Skills
- `local-ci` — 変更の種類ごとに手元で回す検査（`.agents/skills/local-ci/SKILL.md`）
- FAD のテスター追加・削除: `fad-invite`（`.agents/skills/fad-invite/SKILL.md`）

## GitHub Project での作業管理

- この repo の作業は GitHub Project（`.github/scrum-board.yml` の番号）で管理する
- 次にやる物を決める・課題を起票する・Status を動かす・繰り越す時は必ず先に `.agents/skills/scrum-board/SKILL.md`（`.claude/skills/scrum-board/`）を読んでから手を付ける
- 読めない・見つからない場合の要点: 次にやる物=盤の「今週の Iteration」で Wait 空の先頭（無ければ「無し」と答える・issue一覧や open PR から選ばない）・課題は題の頭に `[PBI]`/`[SBI]` を付け sub-issue と盤登録と項目（Iteration・Priority・Estimate・Size）までやる・繰り越しは `kouiso/.github` の `scripts/scrum_keeper/scrum_keeper.py --job carryover|okawari` を使う
- Status は Backlog / In progress / In review / Done の 4 つ。Todo は作らん
