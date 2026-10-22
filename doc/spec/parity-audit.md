# 旧版機能の引き継ぎ照合表

SBI GitHub Issue #76（決定4）。対象は `doc/spec/kmp-replacement.md` の F-1〜F-13・画面一覧（§4）・旧版にあった機能。
照合日 2026-10-21、基準は origin/master `69cf31a`。コードを読んで確認した行だけを根拠にしている。実機では動かしていない。

凡例: あり = 該当コードを確認 / なし = grep で見つからない / 未確認 = 読み切れていない / 対象外 = 仕様で対象外

パスは `A` = `androidApp/src/main/java/com/calendaralarm/`、`I` = `iosApp/iosApp/`、`S` = `shared/src/commonMain/kotlin/com/calendaralarm/shared/`。

## 1. 機能要件（F-1〜F-13）

| 機能 | Android | iOS | 根拠 |
|---|---|---|---|
| F-1 カレンダー読み込み | あり | あり | A `data/calendar/CalendarContractReader.kt` / I `EventKitReader.swift:28` `calendars()` |
| F-1 カレンダー単位の有効/無効 | あり | あり | A `data/AlarmRepository.kt:422` `disabledCalendarIds` / I `SettingsView.swift:378` `calendarRow`（Toggle）, `Models.swift:283` |
| F-2 アジェンダ一覧（場所・天気・アラーム状態） | あり | あり | A `ui/AgendaScreen.kt:343` `AgendaList` / I `AgendaView.swift:4`, `:284` `EventRow` |
| F-3 カレンダー既定オフセット | あり | あり | A `ui/SettingsScreen.kt:253` `defaultMinutes`, `:607` `onChange(minutesBefore…)` / I `SettingsView.swift:387`（`rule.minutesBefore`） |
| F-3 イベント単位の上書き | あり | あり | A `data/db/Entities.kt:112` `EventOverrideEntity`, `ui/AgendaScreen.kt:277` / I `Models.swift:144` `EventOverrideDTO`, `EventDetailSheet.swift:17` |
| F-4 フルスクリーン鳴動 | あり | あり（OS描画） | A `engine/AlarmService.kt:500` `setFullScreenIntent`, `RingingActivity.kt:246` / I `AlarmKitScheduler.swift:121` `AlarmPresentation.Alert` |
| F-4 バイブ | あり | 対象外（端末の設定に従う。#101） | A `engine/AlarmService.kt:52,86` / I AlarmKit の `AlarmConfiguration` にバイブの指定が無い（iOS 26.1 SDK）。設定画面にそう書いた |
| F-4 音量固定・徐々に音量UP | あり | 対象外（端末の設定に従う。#101） | A `engine/AlarmService.kt:329` / I AlarmKit に音量の指定が無い（iOS 26.1 SDK）。設定画面にそう書いた |
| F-4 カスタム音（既定音） | あり | あり（#101） | A `data/SettingsRepository.kt` `defaultSoundUri` / I `AlarmSoundLibrary.swift`（Library/Sounds へ caf で取り込み、`AlertSound.named` で予約）、`SettingsView.swift` アラーム音の行 |
| F-4 一括ミュート | あり | あり | A `data/SettingsRepository.kt` `muteAll` / I `SettingsView.swift:54-55` 「アラームをすべてミュート」 |
| F-5 スヌーズ（鳴動画面から） | あり | あり | A `engine/RingingActivity.kt:138-139` `onSnooze`,`onSnoozeAt` / I `RingingView.swift:80`, `AlarmKitScheduler.swift:158` |
| F-5 スヌーズ間隔の設定 | あり（5/10/15/30/45/60） | あり（5/10/15のみ） | A `ui/SettingsScreen.kt:1056`, `data/SettingsRepository.kt` `snoozePresets` / I `SettingsView.swift:21` `snoozeOptions = [5, 10, 15]` |
| F-6 単発・曜日繰り返しアラーム | あり | あり | A `ui/AlarmEditScreen.kt:121,382` / I `AlarmsView.swift:123-126` |
| F-7 例外日 | あり | あり | A `ui/AlarmEditScreen.kt:100,284` / I `AlarmsView.swift:90,231` |
| F-8 天気（場所→5日予報） | あり | あり | A `ui/AgendaScreen.kt:290`, `S weather/WeatherApi.kt:44` / I `AgendaView.swift:8-9`, `WeatherService.swift` |
| F-9 タイマー | あり | あり | A `ui/TimerScreen.kt:129` / I `TimerView.swift:31` `TimerFace` |
| F-9 ストップウォッチ | あり | あり | A `ui/TimerScreen.kt:269` / I `TimerView.swift:175` `StopwatchFace` |
| F-9 タイマープリセット（追加・削除） | あり | あり | A `ui/TimerScreen.kt:199-221` / I `TimerView.swift:74,89,111`, `Models.swift:220` 7件 |
| F-10 ホームウィジェット | あり | あり | A `widget/NextAlarmWidget.kt:27` / I `Widget/CalendarAlarmWidget.swift:62`, `Store.swift:260` |
| F-11 課金/広告 | 未確認 | 未確認 | Phase 3。Android の宣言権限（`AndroidManifest.xml`）に課金・広告関連なし。コード側 grep は未実施 |
| F-12 アカウント直同期 | 一部（Google アカウントを1つ連携、eventType 取得用） | なし | A `ui/SettingsScreen.kt:833,842` `googleAccountEmail` / I `iosApp/iosApp` 配下は `Models.swift:56-58` の写像関数のみ。複数アカウントは #74 の範囲 |
| F-13 iOS 版（EventKit + AlarmKit） | 対象外 | あり | I `EventKitReader.swift:7`, `AlarmKitScheduler.swift:36` |

## 2. 画面一覧（§4）

| 画面 | Android | iOS | 根拠 |
|---|---|---|---|
| オンボーディング | あり | あり | A `ui/OnboardingScreen.kt:54`, `MainActivity.kt:121` / I `OnboardingView.swift:4`, `CalendarAlarmApp.swift:66` |
| イベント一覧 | あり | あり | 上の F-2 |
| イベント詳細/アラーム編集 | あり | あり | A `ui/AgendaScreen.kt:272,503` / I `AgendaView.swift:78`, `EventDetailSheet.swift:5` |
| 鳴動画面 | あり（アプリ描画） | あり（OS描画 + アプリ内 `RingingView`） | A `engine/RingingActivity.kt` / I `AlarmKitScheduler.swift:121`, `CalendarAlarmApp.swift:72` |
| カレンダー設定 | あり | あり | A `ui/SettingsScreen.kt:238-260` / I `SettingsView.swift:234-241` |
| 設定 | あり | あり（項目は少ない） | A `ui/SettingsScreen.kt:134-384` / I `SettingsView.swift:30-270` |
| 電池最適化ガイド・トラブルシュート | あり | なし | A `ui/TroubleshootingScreen.kt:217`, `:181`, `MainActivity.kt:304` / I 画面なし（`iosApp/iosApp` 配下に troubleshoot・診断の一致なし）。iOS は設定に「10秒後に鳴動テスト」あり（`SettingsView.swift:352`） |
| アラーム一覧/編集 | あり | あり | A `ui/AlarmsScreen.kt:57`, `AlarmEditScreen.kt:77` / I `AlarmsView.swift:4,140` |
| 月/週ビュー | あり（月・3日・日タイムライン） | あり（同3種） | A `ui/AgendaScreen.kt:248-261` / I `AgendaView.swift:57-61` |
| 監査ログ表示 | あり | あり | A `data/db/Entities.kt:122`, `ui/SettingsScreen.kt:384` / I `Store.swift:45,242`, `SettingsView.swift:272` |

## 3. 旧版にあった機能

| 機能 | Android | iOS | 根拠 |
|---|---|---|---|
| 印刷→予定化（メール本文から予定を抽出） | あり | 対象外（OS に印刷サービスの仕組みがない。共有で代替） | A `AndroidManifest.xml` PrintService, `ai/MailPrintService.kt:34,52,73`, `ui/ExtractedEventDialog.kt:43,123,169` |
| 共有→予定化 | なし | あり | A `androidApp/src/main` 配下に `ACTION_SEND` の一致なし（intent-filter は MAIN・PrintService・各 Receiver のみ）/ I `MailShareExt/ShareViewController.swift:8,10-11`, `CalendarAlarmApp.swift:85`, `MailImportView.swift:5,54` |
| 予定の書き込み（抽出結果をカレンダーへ） | あり | あり | A `ui/ExtractedEventDialog.kt:169` / I `EventKitReader.swift:70-74` |
| 通知アラーム（他アプリの通知で鳴らす） | あり | 対象外（他アプリの通知を読む公開 API が iOS に無い。#103） | A `engine/NotificationAlarmService.kt:23`, `data/SettingsRepository.kt` `notificationRules` / I 出典は末尾 |
| カレンダーの通知時刻でも鳴らす | あり | あり | A `data/SettingsRepository.kt` `importEventReminders` / I `SettingsView.swift:214-218` |
| 天気（鳴動画面・一覧ヘッダ） | あり | あり | A `ui/SettingsScreen.kt` / `RingingActivity.kt`（WeatherApi 参照）/ I `AgendaView.swift:28` `WeatherHeaderRow`, `WeatherBackdropView.swift:5` |
| テーマ・背景画像 | あり | あり | A `ui/theme/Palettes.kt`, `SettingsScreen.kt:224` / I `Theme.swift:94`, `SettingsView.swift:70-99` |
| 再起動後の再登録 | あり | 未確認 | A `engine/BootReceiver.kt:20` / I AlarmKit 予約が再起動後も残るかは未確認。`CalendarAlarmApp.swift:37` の BGTask 再同期はある |
| TZ・日付変更での再展開 | あり | 未確認 | A `engine/TimeChangeReceiver.kt:14`, `AndroidManifest.xml`（TIME_SET / TIMEZONE_CHANGED / DATE_CHANGED）/ I `iosApp/iosApp` 配下に significantTimeChange・タイムゾーン通知の一致なし |
| 定期の整合性チェック | あり | あり（BGTask） | A `CalendarAlarmApp.kt:142` `enqueueUniquePeriodicWork` / I `CalendarAlarmApp.swift:37,52` |
| 同時刻イベントの衝突処理 | あり | 未確認 | A `engine/AlarmService.kt:218-224` 座席確保 / I 同時刻の記述なし。AlarmKit 側の挙動は未確認 |
| 取りこぼし通知（missed） | あり | あり | A `engine/MissedNotifier.kt` / I `Engine.swift:38` |
| 日本語・英語のローカライズ | なし | なし | A `res/values/strings.xml` のみで `values-en` なし / I `.strings`・`.xcstrings`・`.lproj` が見つからない |
| 週番号・週の開始曜日 | あり（#106） | あり（#106） | 旧版（`com.app_by_LZ.calendar_alarm_clock`）の文言に「Show Calendar Week」「First day of week」がある。A `ui/SettingsScreen.kt` `WeekDisplaySettings`、計算は S `logic/WeekCalendar.kt` / I `SettingsView.swift`、`AgendaViewsIOS.swift` `WeekDisplay` |

## 4. 欠け（片方のOSにない）

| # | 欠け | 欠けているOS | 補足 |
|---|---|---|---|
| G1 | 鳴動のバイブ・音量固定・徐々に音量UP・カスタム音 | 一部解消（#101） | カスタム音は iOS でも選べる。音量・徐々に大きく・バイブは AlarmKit に指定の口が無い（`AlarmManager.AlarmConfiguration` の引数は sound だけ）ため、設定画面に「iPhone の設定に従う」と書いた |
| G2 | 共有→予定化 | Android | 印刷サービスのみ。`ACTION_SEND` の受け口がない |
| G3 | 通知アラーム | iOS | 対象外に決定（#103）。`getDeliveredNotifications` が返すのは自アプリの通知だけ。通知サービス拡張も自アプリ宛てのリモート通知だけ。ANCS は BLE アクセサリ向けで端末内アプリは使えない |
| G4 | 自己診断・トラブルシュート画面 | iOS | 電池最適化は iOS に該当しない。権限・次回予定・最後の鳴動の一覧はない（仕様 §6） |
| G5 | 日本語・英語のローカライズ | 両方 | 仕様 §9 は日英。現状は日本語固定 |
| G6 | 週番号・週開始曜日が画面に出ない | 解消（#106） | 旧版にあった機能。両 OS で予定一覧の週番号（ISO 8601）と月表示の週の始まりを設定できる |
| G7 | スヌーズ間隔の選択肢が 5/10/15 だけ | iOS | Android は 5〜60 の6択 |
| G8 | 再起動・TZ変更後の再登録、同時刻衝突の処理 | iOS（未確認） | コード上の根拠が見つからないため要実機確認 |

課金・広告（F-11）は Phase 3 で、コード側の確認は今回していない。Google アカウント複数対応は #74、通知時刻の重複は #75 の範囲で、ここでは扱わない。

## 出典（G3、2026-10-21 閲覧）

- https://developer.apple.com/documentation/usernotifications/unusernotificationcenter/getdeliverednotifications(completionhandler:) — "Fetches all of your app’s delivered notifications that are still present in Notification Center."
- https://developer.apple.com/documentation/usernotifications/unnotificationserviceextension — "When your app receives a remote notification for your app, the system loads your extension"
- https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Introduction/Introduction.html — Bluetooth アクセサリが iOS の通知を受け取るための仕組み（端末内のアプリ向けではない）
