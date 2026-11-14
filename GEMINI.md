<!-- AUTO-GENERATED from AGENTS.md / .agents/skills by scripts/sync-ai-rule.mjs — DO NOT EDIT DIRECTLY -->

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
クラウド（Claude・Codex・Devin）で回せる物と回せない物（2026-11-14 時点）:
- 回せる: Android の unit テスト（`bash scripts/ci.sh fast`）と `node scripts/hardcoded-ja/check.mjs`。先に `bash scripts/setup-android-sdk.sh` で SDK を入れ、UTF-8 ロケールは `scripts/ci.sh` が設定する
- 回せない: iOS のビルドとテスト（Mac・Xcode が要る）、emulator・実機・音の確認
- 持ち主の承認が要る: SDK の取得先への通信、クラウドの環境へスクリプトを常に登録するか

```bash
# Android SDK（クラウドなど SDK が無い所で。入っていれば何もしない）
bash scripts/setup-android-sdk.sh

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
- UTF-8 のロケールが要る（テスト名が日本語のため）。`scripts/ci.sh` は自分で設定する。`./gradlew` を直接動かす時は `LC_ALL=C.UTF-8` を付ける
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

- この repo のボードの設定は `.github/scrum-board.yml` にある
- 手順は global skill `scrum-board` に従う
