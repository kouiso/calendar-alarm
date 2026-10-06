# AGENTS.md

## Overview
Kotlin Multiplatform (KMP) によるカレンダー連動アラームアプリ。
Android 先行実装 (Compose Material3)。iOS は Phase 3 で `shared` の iOS ターゲット上に構築予定。

## Structure
- `shared/` — KMP 共有モジュール (ドメイン層: 鳴動展開・オフセット計算・天気クライアント等)
- `androidApp/` — Android アプリ (エンジン + Compose UI)

## Setup
```bash
./gradlew :androidApp:assembleDebug
```

## Code Style
- 日本語でコメント・ドキュメントを記述
- 既存コードスタイルに従う
- コミット件名は英語 Conventional Commits (`type(scope): english subject`)

### Testing
```bash
./gradlew :shared:jvmTest :shared:testDebugUnitTest :androidApp:testDebugUnitTest
```
