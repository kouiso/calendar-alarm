# calendar-alarm

カレンダーの予定に連動して「絶対に鳴る」ことを目指すアラームアプリ。
Kotlin Multiplatform + Jetpack Compose (Material3) 製。Android 先行、iOS は Phase 3。

## 機能

- カレンダー自動取込 (端末の全カレンダーを CalendarContract 経由で読込。Google/Microsoft 等のアカウントは端末に登録するだけで連携)
- カレンダー単位の鳴動 ON/OFF・既定オフセット・終日予定の鳴動時刻
- 予定ごとの鳴動 ON/OFF・鳴らすタイミング上書き・複数リマインダー (最大5件)
- 単発・曜日繰り返しアラーム + 例外日スキップ
- スヌーズ / タイマー / ストップウォッチ
- イベント場所の天気予報
- ホームウィジェット (次のアラーム)
- 鳴動監査ログ (鳴った/止めた/スヌーズ/鳴らせなかった を記録)

## セットアップ

Android Studio (Ladybug 以降) または JDK 17 があれば動く。

```bash
./gradlew :androidApp:assembleDebug   # androidApp/build/outputs/apk/debug/androidApp-debug.apk
./gradlew :shared:jvmTest :shared:testDebugUnitTest :androidApp:testDebugUnitTest  # テスト
```

## モジュール構成

- `shared/` — KMP 共有モジュール (鳴動スケジュール展開・ドメインロジック・天気クライアント)
- `androidApp/` — Android アプリ (鳴動エンジン・カレンダー同期・Compose UI・ウィジェット)
