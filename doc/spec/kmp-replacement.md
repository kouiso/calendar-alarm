# Calendar Alarm — KMP リプレイス仕様書

最終更新: 2026-10-04 / 版: v0.1 (ドラフト)

## 1. 目的・背景

Google Play の「Calendar Alarm Clock Reminder」(4LIMIT Sports GmbH 製、
`com.app_by_LZ.calendar_alarm_clock`) と同等の体験を持つカレンダー連動アラームアプリを、
Kotlin Multiplatform (KMP) で自前実装する。

中核となる価値は「**登録したカレンダーの予定に対し、通知ではなく"止めるまで鳴り続ける
アラーム"を確実に鳴らす**」こと。競合アプリのレビューで最大の不満は機能不足ではなく
「鳴るはずが鳴らない / 止められない」という鳴動信頼性の揺れであり、本アプリは
**鳴動信頼性を設計の第一目標**とする。

## 2. スコープ

### 対象

- Android 版 (Kotlin + Jetpack Compose) を第一リリースとする
- KMP 構成で作り、ロジック層を iOS と共有できる状態を維持する
- iOS 版は Android 安定後に SwiftUI + AlarmKit (iOS 26+) で追加する

### 対象外 (初期版)

- iOS 26 未満のサポート (通知 30 秒制限で中核体験を再現できないため)
- Web / Desktop
- 他者アプリとのデータ共有・書き込み (読み取り専用とする)
- AI 補助機能

## 3. 機能要件

| ID | 機能 | 概要 | Phase |
|----|------|------|-------|
| F-1 | カレンダー読み込み | 端末のカレンダープロバイダ (Google / Outlook / ローカル等、OS に登録済みの全カレンダー) からイベントを読み込み、カレンダー単位で有効/無効を切替 | 1 |
| F-2 | イベント一覧 | 今日以降のイベントをアジェンダ形式で表示。アラーム設定状態・場所・天気を1行に集約 | 1 |
| F-3 | イベント連動アラーム | イベント毎またはカレンダー毎に「N 分前にアラーム」を自動設定。カレンダー単位の既定オフセット + イベント単位の上書き | 1 |
| F-4 | アラーム鳴動 | 指定時刻にフルスクリーン表示 + 鳴り続ける音 + バイブ。マナーモード/サイレントでも鳴る (アラーム音量系)。カスタム MP3/着メロ選択可 | 1 |
| F-5 | スヌーズ | 鳴動画面から「N 分後に再鳴動」。間隔は設定で変更可 | 1 |
| F-6 | 単発・繰り返しアラーム | イベント非連動の通常目覚まし。曜日繰り返し + 例外日 (休日・振替) 指定 | 2 |
| F-7 | 例外・休日スキップ | 繰り返しアラームに特定日を鳴らさない/時刻を変える例外を登録 | 2 |
| F-8 | 天気 | イベントに場所があれば予報 (当日〜5日先) を一覧に表示 | 2 |
| F-9 | タイマー/ストップウォッチ | 通常の時計アプリ相当 | 2 |
| F-10 | ホームウィジェット | 直近イベント+アラーム状態の表示 | 2 |
| F-11 | 課金/広告 | 無料版は広告、Pro 課金で広告除去+拡張音源等 | 3 |
| F-12 | アカウント直同期 | CalDAV / Google Calendar API / MS Graph で端末プロバイダ経由しない取り込み | 3 |
| F-13 | iOS 版 | EventKit + AlarmKit (iOS 26+) で同等機能 | 3 |

## 4. 画面一覧

| 画面 | 内容 | Phase |
|------|------|-------|
| オンボーディング | 権限 (カレンダー・通知・アラーム) の段階的取得と用途説明 | 1 |
| イベント一覧 (ホーム) | 日付ヘッダ + アジェンダ。イベント行にアラーム状態・天気・場所 | 1 |
| イベント詳細/アラーム編集 | オフセット・ミュート・個別音・スヌーズ設定 | 1 |
| 鳴動画面 | フルスクリーン。イベント名・開始時刻・場所・残り時間。「止める」「スヌーズ」 | 1 |
| カレンダー設定 | カレンダー毎の有効化・既定オフセット・ミュート | 1 |
| 設定 | 既定音・音量・バイブ・スヌーズ間隔・電池最適化ガイド・トラブルシュート | 1 |
| アラーム一覧/編集 | 単発・繰り返しアラームと例外管理 | 2 |
| 月/週ビュー | カレンダー形式の俯瞰 | 2 |

## 5. アーキテクチャ

### 5.1 モジュール構成

```
calendar-alarm/
├── shared/                    # KMP 共通層
│   └── src/
│       ├── commonMain/        # 全OS共通ロジック
│       ├── androidMain/       # Android 固有 (CalendarContract, AlarmManager 接続)
│       └── iosMain/           # iOS 固有 (EventKit / AlarmKit ブリッジ)  ※Phase 3
├── androidApp/                # Android アプリ (Compose UI + 鳴動エンジン)
└── iosApp/                    # iOS アプリ (SwiftUI)  ※Phase 3
```

**共有層 (commonMain) に置くもの** — バグの巣窟を一本化する目的:

- イベント→アラーム展開ロジック (オフセット適用・ミュート・例外日・繰り返し計算)
- 日時・タイムゾーン処理 (`kotlinx-datetime`)
- カレンダーモデル正規化 (各ソースのイベントを共通 `CalendarEvent` に落とす)
- 設定・ルール (カレンダー毎の既定値、音量、スヌーズ間隔)
- 天気クライアント + 場所→予報マッピング (Ktor)
- DB (Room KMP)・リポジトリ定義・ViewModel/UseCase

**OS 側に残すもの** — 薄いアダプタ層:

- Android: CalendarContract 読み取り、AlarmManager スケジュール、鳴動 FGS、
  オーディオ再生、通知、ウィジェット (Glance)、権限フロー
- iOS: EventKit 読み取り、AlarmKit 呼び出し (Swift shim)、WidgetKit

### 5.2 expect/actual 境界

```kotlin
// commonMain
interface AlarmScheduler {
    suspend fun schedule(instances: List<AlarmInstance>)
    suspend fun cancel(ids: List<AlarmId>)
}
interface CalendarProvider {
    suspend fun calendars(): List<CalendarSource>
    suspend fun events(range: DateRange): List<CalendarEvent>
}
interface AlarmAudio { fun play(sound: AlarmSound); fun stop() }
```

| 境界 | Android actual | iOS actual |
|------|---------------|------------|
| AlarmScheduler | AlarmManager (`setAlarmClock`/`setExactAndAllowWhileIdle`) | AlarmKit (Swift shim 経由、ワンショット展開) |
| CalendarProvider | CalendarContract | EventKit |
| AlarmAudio | MediaPlayer (STREAM_ALARM) + FGS | AlarmKit の AlertSound (バンドル MP3) |
| 鳴動画面 | Full-screen Intent Activity | AlarmKit システム描画 (アプリ側 UI なし) |
| ウィジェット | Glance | WidgetKit |

### 5.3 アラーム展開アルゴリズム (共通層の核心)

1. 有効カレンダーのイベント + アラームルール → 鳴動すべき `AlarmInstance` を
   **展開ウィンドウ (既定 14 日先まで)** 分だけ計算する
2. 例外日・ミュート・オフセット・タイムゾーンを適用して確定時刻リストを生成
3. `AlarmScheduler` に差分適用 (追加/更新/削除のみ)
4. 再展開トリガ: カレンダー内容の変更検知・設定変更・端末再起動・
   タイムゾーン/ロケール変更・日付ロールオーバー

iOS は AlarmKit の予約上限 (約 64 件) と繰り返しが週次のみという制約があるため、
上記と同じ展開結果を「ワンショットの列」としてウィンドウ内に絞って予約する設計に揃える。

## 6. プラットフォーム設計 (Android)

### 鳴動経路

- `AlarmManager.setAlarmClock()` (最優先・設定アイコン表示) または
  `setExactAndAllowWhileIdle()` で時刻登録
- 発火時に `mediaPlayback` フォアグラウンドサービス起動 → STREAM_ALARM で
  カスタム音をループ再生 + フルスクリーン Intent で鳴動画面表示 + バイブ
- 停止/スヌーズは鳴動画面のボタン → サービス停止。スヌーズは次回発火を再登録
- `BOOT_COMPLETED` / `TIMEZONE_CHANGED` / `DATE_CHANGED` レシーバで再展開・再登録
- WorkManager でカレンダー差分同期と「鳴動予定の健全性チェック」を定期実行

### 権限・ポリシー

| 権限 | 用途 | 備考 |
|------|------|------|
| READ_CALENDAR | カレンダー読み込み | 実行時 |
| POST_NOTIFICATIONS | 鳴動通知/事前通知 | Android 13+ |
| USE_EXACT_ALARM | 正確なアラーム | アラーム中核アプリのため審査上許容 |
| USE_FULL_SCREEN_INTENT | 鳴動画面 | Android 14+ は設定画面誘導あり |
| FOREGROUND_SERVICE(_MEDIA_PLAYBACK/_DATA_SYNC) | 鳴動/同期 | |
| RECEIVE_BOOT_COMPLETED | 再起動後の再登録 | |
| VIBRATE / INTERNET | バイブ / 天気 | |
| ACCESS_*_LOCATION (任意) | 現在地ベースの天気 | 未許可でも場所文字列ジオコーディングで代替 |

### OEM 電池最適化対策

- 初回起動と設定画面から電池最適化除外のリクエスト導線を用意
  (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` は審査注意。基本は
  `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` へ誘導)
- Samsung / Xiaomi / OPPO 等のメーカー別設定手順をアプリ内ガイド化
- 「鳴らなかった」自己診断画面 (最後の鳴動・次回予定・権限状態を一覧)

### iOS (Phase 3) の要点

- **AlarmKit (iOS 26+)**: マナー/集中モード突破・停止まで鳴動・カスタム MP3
  (バンドル or `Library/Sounds` の AlertSound)。アラート UI はシステム描画
- 制約: 繰り返しは週次のみ・予約上限約 64 件・要 `NSAlarmKitUsageDescription` +
  `requestAuthorization`・countdown 表示に widget extension 必須
- 上限制約は「展開ウィンドウ内のワンショット列」を予約し、発火・編集の都度
  ローテーションする方式で回避
- Swift 専用 API のため KMP からは薄い Swift shim を介して呼ぶ

## 7. 信頼性設計 (差別化の中核)

競合の弱点 = 鳴動の不確実性。以下を仕様として担保する。

- **鳴動の二重経路**: AlarmManager 登録に加え、WorkManager が「次回鳴動が
  正しく登録されているか」を定期検査し、欠落を修復
- **監査ログ**: 「登録/発火/停止/スヌーズ/スキップ理由」を永続化し、
  設定画面の自己診断から参照可能にする (サポートのための説明責任)
- **同時刻衝突ポリシー**: 複数イベントが同時刻に鳴動予定の場合、最も早い
  イベントを優先し、残りはその停止直後に連続鳴動 (取りこぼさない)
- **鳴動中の状態永続化**: 鳴動画面を開かず再起動しても、未解決アラームは
  再鳴動する (レビューで「開くだけで消えた」系の苦情があった競合の轍を踏まない)
- **サイレント/マナー時の挙動**: STREAM_ALARM で鳴る仕様とし、
  音量 0 の場合はバイブ + 画面のみ (仕様として明文化)

## 8. データモデル (案)

```kotlin
CalendarSource   // provider アカウント・カレンダーID・有効フラグ・既定オフセット
CalendarEvent    // id, calendarId, title, start/end, allDay, location, rrule, exdates
AlarmRule        // 対象 (calendar|event|standalone), offsetMinutes, sound, volume,
                 // vibrate, snoozeMinutes, enabled
StandaloneAlarm  // 時刻, 曜日繰り返し, 例外日, 音/バイブ設定
AlarmInstance    // 確定鳴動 (ruleRef, eventRef?, triggerAt, sound, status:
                 // scheduled|fired|snoozed|dismissed|missed)
WeatherCache     // locationKey, date, forecast
AuditLog         // 鳴動系イベントの記録
```

## 9. 非機能要件

- **オフライン**: カレンダー・アラーム・設定は完全オフライン動作 (天気のみ通信)
- **起動・軽量性**: 鳴動発火時の起き上がりが速いこと (Flutter エンジン等を
  積まない KMP/Android ネイティブであることの強みを活かす)
- **省電力**: 常駐ポーリングなし。WorkManager の最小頻度運用
- **ローカライズ**: 日本語・英語

## 10. 段階リリース

| Phase | 内容 | 出口基準 |
|-------|------|---------|
| 0 | KMP ひな形 + CI | Android 起動・共通層テスト |
| 1 | Android MVP: F-1〜F-5, 一覧/設定/鳴動画面 | §11 の鳴動マトリクス全パス |
| 2 | Android フル: F-6〜F-10 (例外・天気・タイマー・ウィジェット) | 競合機能同等 |
| 3 | iOS (AlarmKit) + 課金/広告 + 直同期 | iOS 26+ で鳴動確認 |

## 11. 受け入れ条件 — 鳴動テストマトリクス

以下の全ケースで「指定時刻にフルスクリーン+音が鳴り、停止/スヌーズできる」こと。

| # | 状態 | 期待 |
|---|------|------|
| 1 | アプリ起動中 | 鳴動 |
| 2 | アプリ kill 後 | 鳴動 |
| 3 | 画面ロック中 | フルスクリーンで鳴動 |
| 4 | 端末再起動直後 (再ログイン前) | 鳴動 |
| 5 | マナー/サイレントモード | 鳴動 (STREAM_ALARM) |
| 6 | おやすみモード (DND) | 鳴動 |
| 7 | タイムゾーン変更後 | 正しい時刻に鳴動 |
| 8 | DST 境界 | 正しい時刻に鳴動 |
| 9 | 同時刻イベント複数 | 全件取りこぼしなく順次鳴動 |
| 10 | 例外日 | 鳴らない |
| 11 | ミュートカレンダー/イベント | 鳴らない |
| 12 | 電池最適化 ON のまま (未除外) | 鳴動 (setAlarmClock 経路) |

## 12. 未決事項

- アプリ名・アイコン・パッケージ名
- 天気プロバイダ選定 (WeatherAPI / OpenWeatherMap 等)
- 広告 SDK・課金価格設計 (Phase 3)
- CalDAV/Google/MS 直同期の要否 (端末プロバイダで不足するケースの調査)
- Firebase/Crashlytics 等のテレメトリ導入可否

## 付録: 参照

- 競合: <https://play.google.com/store/apps/details?id=com.app_by_LZ.calendar_alarm_clock>
- AlarmKit: <https://developer.apple.com/documentation/alarmkit> (iOS 26+)
- Android アラーム権限: `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`/`USE_FULL_SCREEN_INTENT`
