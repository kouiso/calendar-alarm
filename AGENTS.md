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
