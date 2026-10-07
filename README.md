# calendar-alarm

カレンダーの予定に連動して「絶対に鳴る」ことを目指すアラームアプリ。
Kotlin Multiplatform 製。Android は Jetpack Compose (Material3)、iOS は SwiftUI + AlarmKit (iOS 26+)。

## 機能

- カレンダー自動取込 (端末の全カレンダーを読込。Google/Microsoft 等のアカウントは端末に登録するだけで連携)
- カレンダー単位の鳴動 ON/OFF・既定オフセット・終日予定の鳴動時刻
- 予定ごとの鳴動 ON/OFF・鳴らすタイミング上書き・複数リマインダー (最大5件)
- 単発・曜日繰り返しアラーム + 例外日スキップ
- スヌーズ / タイマー / ストップウォッチ
- イベント場所の天気予報
- ホームウィジェット (次のアラーム)
- 鳴動監査ログ (鳴った/止めた/スヌーズ/鳴らせなかった を記録)

## セットアップ

### Android

Android Studio (Ladybug 以降) または JDK 17 があれば動く。

```bash
./gradlew :androidApp:assembleDebug   # androidApp/build/outputs/apk/debug/androidApp-debug.apk
./gradlew :shared:jvmTest :shared:testDebugUnitTest :androidApp:testDebugUnitTest  # テスト
```

### iOS (iOS 26+ / AlarmKit)

Xcode 26+ と xcodegen が必要。

```bash
cd iosApp && xcodegen generate
open CalendarAlarm.xcodeproj   # Xcode から実行。SharedKit.framework はビルドフェーズが Gradle で自動生成
```

実機配布では App Group `group.com.calendaralarm.ios` を Developer Portal / プロビジョニングに登録する。

## モジュール構成

- `shared/` — KMP 共有モジュール (鳴動スケジュール展開・ドメインロジック・天気クライアント・Swift向け JSON ブリッジ)
- `androidApp/` — Android アプリ (鳴動エンジン・カレンダー同期・Compose UI・ウィジェット)
- `iosApp/` — iOS アプリ (SwiftUI・AlarmKit 鳴動エンジン・EventKit 同期・WidgetKit ウィジェット)
