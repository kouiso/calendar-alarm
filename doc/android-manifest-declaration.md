# Android の宣言の表

対象は `androidApp/src/main/AndroidManifest.xml` の権限 14、サービス 3、受信 4 である。
この表は棚卸しだけを行う。残すか消すかは決めない。
「持ち主の判断」の欄は空けてある。答えが出たら埋める。

- 行番号は 2026-11-13 時点の `master` のもの。
- 「審査で要る説明」の出典は Google Play ヘルプ（2026-11-13 に確認）。
  - 正確なアラーム: https://support.google.com/googleplay/android-developer/answer/13986130
  - 全画面の通知: https://support.google.com/googleplay/android-developer/answer/13161072
  - 常駐サービスの型: https://support.google.com/googleplay/android-developer/answer/13392821
- 申告の項目に当たらない宣言は「申告の項目なし」と書いた。審査で見られる可能性までは確かめていない。
- Play Console の申告画面は操作していない。

## 権限（14）

| # | 宣言（manifest の行） | 使っている場所 | 無いと何が起きるか | 審査で要る説明 | 持ち主の判断 |
|---|---|---|---|---|---|
| 1 | READ_CALENDAR（6） | `data/calendar/CalendarContractReader.kt:32`（カレンダー一覧）、`:53`（予定の展開）<br>`CalendarAlarmApp.kt:75`、`:123`（許可が有る時だけ変更を監視）<br>`ui/Permissions.kt:110`、`:167`（許可の確認と依頼） | 予定を読めないので、予定からアラームを作れない。アプリの中心機能が止まる | 申告の項目なし。実行時の許可ダイアログで頼む。使い道の説明は権限の画面の文言（`ui/Permissions.kt` の `perm_*` 文字列）にある | |
| 2 | WRITE_CALENDAR（7） | `ui/ExtractedEventDialog.kt:122`（許可の確認と依頼）、`:178`（`CalendarContract.Events` へ insert） | 「メールから予定」の確定時に予定を書き込めない。それ以外の機能には影響しない | 申告の項目なし。メールから取り出した予定を端末のカレンダーへ書く、という説明が要る見込み | |
| 3 | POST_NOTIFICATIONS（9） | `ui/Permissions.kt:118`、`:168`（API 33 以上で許可を確認と依頼）、`ui/Permissions.kt:339` | API 33 以上で通知を出せない。鳴動サービスの通知と全画面の通知が出ない | 申告の項目なし | |
| 4 | USE_EXACT_ALARM（11） | `engine/AlarmScheduler.kt:34`（`AlarmManager.setAlarmClock`）。`:21` のコメントに、取り下げ時の `SecurityException` を想定する旨がある | 条件を満たす OS では、これで正確なアラームが使える。無い場合は SCHEDULE_EXACT_ALARM の許可を利用者に頼む流れ（`ui/Permissions.kt:152` の EXACT）になる | **要る。** Play Console で正確なアラームを申告する。USE_EXACT_ALARM は「アラームまたはタイマーのアプリ」と「予定の通知を出すカレンダーアプリ」だけに許される。満たさないと公開できない | |
| 5 | SCHEDULE_EXACT_ALARM（12） | `ui/Permissions.kt:124`（`canScheduleExactAlarms`）、`:152`、`ui/SettingsScreen.kt:581`、`ui/TroubleshootingScreen.kt:119`、`:199`（許可画面を開く）。manifest に上限の OS 版は付いていない | USE_EXACT_ALARM が効かない OS では、利用者が許可しないと正確なアラームを予約できない | **要る。** 上の USE_EXACT_ALARM と同じ申告。SCHEDULE_EXACT_ALARM は利用者の許可が要る | |
| 6 | VIBRATE（14） | `engine/AlarmService.kt:658` の `startVibration`、`:668` の `vibrate`。設定は `ui/SettingsScreen.kt:1335`（鳴動中のバイブ） | 鳴動中のバイブが動かない。音と画面は影響を受けない | 申告の項目なし | |
| 7 | WAKE_LOCK（15） | `engine/AlarmService.kt:685` の `acquireWakeLock`、`:688` の `PARTIAL_WAKE_LOCK`（`calendaralarm:ringing`）、`:689` の `acquire`。呼び出しは `:335` | 宣言が無いと `acquire()` が `SecurityException` を投げる。`:335` の呼び出しは `runCatching` で囲まれていないので、鳴動の準備が `beginRinging` の途中で止まる見込み。鳴動中の CPU の眠りを防ぐ、という本来の役目も果たせない | 申告の項目なし | |
| 8 | USE_FULL_SCREEN_INTENT（16） | `engine/AlarmService.kt:538`（`canUseFullScreenIntent`）、`:571`（`setFullScreenIntent`）<br>`ui/Permissions.kt:128`、`:154`、`ui/SettingsScreen.kt:584`、`ui/TroubleshootingScreen.kt:125`、`:205` | API 34 以上でロック中やスリープ中に鳴動画面が全面表示されない。通知の小さい表示だけになる | **要る。** API 34 以上を対象にするアプリは Play Console で申告する。自動で許可されるのは、アラームか通話が中心機能のアプリだけ。審査がある | |
| 9 | SYSTEM_ALERT_WINDOW（19） | `engine/RingingOverlay.kt:54`（`Settings.canDrawOverlays`）、`:87`（`TYPE_APPLICATION_OVERLAY`）<br>`engine/AlarmService.kt:346`（停止画面を出す分岐） | 全画面の通知が効かない端末で、停止画面の保険が出ない。許可はアプリから頼まない。利用者が自分で許可した端末だけで使う | 申告の項目なし。ただし重ねて表示する権限なので、審査で使い道を聞かれる可能性がある。manifest のコメント（17-18 行）にある「停止画面の保険」が説明の材料になる | |
| 10 | FOREGROUND_SERVICE（21） | `engine/AlarmService.kt:477`、`:482`（`startForeground`）<br>`engine/AlarmReceiver.kt:52`（`startForegroundService`） | 鳴動サービスを前面で動かせず、バックグラウンドで止められる | 常駐サービスの型の申告（#11 を参照） | |
| 11 | FOREGROUND_SERVICE_MEDIA_PLAYBACK（22） | `engine/AlarmService.kt:477-479`（API 34 以上で `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`） | API 34 以上で、`mediaPlayback` の型で `startForeground` を呼ぶと例外になる。呼び出し側は例外を受けて見逃しに記録する（`:489` 付近のコメント） | **要る。** API 34 以上を対象にするアプリは型ごとに申告する。説明、影響、手順の動画が要る。`mediaPlayback` の例は、利用者が始めた再生で、動画には利用者が再生を始める様子を示す。鳴動は予約の時刻に自動で始まるので、この条件に当たるかの確認が要る。動画に何を示すかは未決 | |
| 12 | RECEIVE_BOOT_COMPLETED（24） | `engine/BootReceiver.kt:17-25`（再起動後と自分の更新後に `SyncWorker.enqueueNow`） | 再起動後にアラームが復元されない。次にアプリを開くまで鳴らない | 申告の項目なし | |
| 13 | REQUEST_IGNORE_BATTERY_OPTIMIZATIONS（26） | `ui/SettingsScreen.kt:597`、`ui/TroubleshootingScreen.kt:187`（省電力の解除を頼む画面を直接開く）。確認は `ui/SettingsScreen.kt:513`、`ui/TroubleshootingScreen.kt:112` | 解除を頼む画面を直接開けない。鳴動そのものは影響を受けない（`ui/TroubleshootingScreen.kt:181` のコメントと `res/values/strings.xml:173` の文言は、最適化の対象でも鳴るとしている） | 申告の項目なし。ただし Android の公式資料は、Doze と App Standby の除外の直接の要求を、アプリの中核機能に悪影響が無い限り Play の規約が禁じている、と書いている（出典 https://developer.android.com/training/monitoring-device-state/doze-standby 、この表の作成者は規約の文面そのものを読んでいない）。このアプリが当たるかは未判断。コードと文言は、鳴動は最適化の対象でも鳴るとしている（`ui/TroubleshootingScreen.kt:181`、`res/values/strings.xml:173`） | |
| 14 | INTERNET（28） | `shared/.../weather/WeatherApi.kt:33`、`:45`、`:77`（天気の取得）<br>`shared/.../ai/OpenRouterApi.kt:35`（AI による予定の抽出）<br>`data/calendar/GoogleCalendarTypes.kt:33`（Google カレンダー API） | 天気、AI による予定の抽出、Google カレンダーの取り込みが使えない。端末のカレンダーとアラーム本体は動く | 申告の項目なし。通信先と送るデータ（予定の文章、地名）は、データの安全性の申告の材料になる見込み | |

## サービス（3）

| # | 宣言（manifest の行） | 使っている場所 | 無いと何が起きるか | 審査で要る説明 | 持ち主の判断 |
|---|---|---|---|---|---|
| 1 | `.engine.AlarmService`（66-71）。`foregroundServiceType="mediaPlayback"` | `engine/AlarmReceiver.kt:52`、`:137-138`（`ACTION_START` で起動）<br>`engine/AlarmService.kt:471-482`（前面化） | アラームが鳴らない。アプリの中心機能が止まる | **要る。** 常駐サービスの型の申告（権限 #10 と #11）。`exported=false` なので他アプリからは呼ばれない | |
| 2 | `.engine.NotificationAlarmService`（73-81）。`BIND_NOTIFICATION_LISTENER_SERVICE` を要求 | `engine/NotificationAlarmService.kt:32`（`onListenerConnected`）、`:49`（`onNotificationPosted`）。他アプリの通知の題と本文を読み、設定した規則に合えばアラームにする。自分の通知、進行中の通知、グループの要約は拾わない（`:50-55`） | 「通知アラーム」の機能が動かない。他の機能には影響しない。利用者がシステム設定で「通知へのアクセス」を許可した時だけ動く | 申告の項目なし。他アプリの通知を読むので、審査で使い道の説明が要る可能性が高い。読む内容、端末の外へ出さないこと、利用者が許可した時だけ動くことが説明の材料になる（端末の外へ出さないことは、この表では確かめていない） | |
| 3 | `.ai.MailPrintService`（83-93）。`BIND_PRINT_SERVICE` を要求。`printservice.xml` の設定画面は `MainActivity` | `ai/MailPrintService.kt:53`（`onPrintJobQueued`）。メールの印刷で届いた PDF の文字を OpenRouter で予定に変換する。実際には印刷しない。AI の鍵が無い版は `:61` の分岐で「AI は使えません」と通知して終わる | 「印刷から予定」の機能が動かない。他の機能には影響しない。なお `onCreatePrinterDiscoverySession` は `null` を返す（`ai/MailPrintService.kt:46`）ので、印刷先を 1 つも登録しない。印刷先の一覧に出るか、印刷ジョブが届いて `:53` に着くかは、この表では確かめていない（#212 は出ると書いている） | 申告の項目なし。印刷サービスを、プリンタではなくメールの取り込み口として使っている点の説明が要る見込み。PDF の文字を外部の AI サービスへ送る点は、データの安全性の申告の材料になる | |

## 受信（4）

| # | 宣言（manifest の行） | 使っている場所 | 無いと何が起きるか | 審査で要る説明 | 持ち主の判断 |
|---|---|---|---|---|---|
| 1 | `.engine.AlarmReceiver`（96-99）。`exported=false` | `engine/AlarmReceiver.kt:16`、`:20`（`ACTION_FIRE`）、`:52`（鳴動サービスの起動）。予約は `engine/AlarmScheduler.kt:34` の `setAlarmClock` | 予約の時刻になっても鳴動が始まらない。停止の放送も受けられない | 申告の項目なし。他アプリからは呼ばれない | |
| 2 | `.engine.BootReceiver`（101-111）。`exported=true`。BOOT_COMPLETED、MY_PACKAGE_REPLACED、QUICKBOOT_POWERON | `engine/BootReceiver.kt:17-25`（`SyncWorker.enqueueNow`） | 再起動後と更新後にアラームが復元されない | 申告の項目なし。権限 #12 と対 | |
| 3 | `.engine.TimeChangeReceiver`（113-122）。`exported=true`。TIME_SET、TIMEZONE_CHANGED、DATE_CHANGED | `engine/TimeChangeReceiver.kt:16-18`（`SyncWorker.enqueueNow`） | 時刻、タイムゾーン、日付を変えても予約が組み直されない。時刻のずれたアラームが残る | 申告の項目なし | |
| 4 | `.widget.NextAlarmWidgetReceiver`（124-134）。`exported=false`。APPWIDGET_UPDATE | `widget/NextAlarmWidget.kt:87` | 「次のアラーム」のウィジェットが更新されない、または置けない。他の機能には影響しない | 申告の項目なし | |

## 数の確認

| 種類 | 計画の数 | 実際の数 | 方法 |
|---|---|---|---|
| 権限（`uses-permission`） | 14 | 14 | manifest の 6-28 行を数えた |
| サービス（`service`） | 3 | 3 | AlarmService、NotificationAlarmService、MailPrintService |
| 受信（`receiver`） | 4 | 4 | AlarmReceiver、BootReceiver、TimeChangeReceiver、NextAlarmWidgetReceiver |

アクティビティ（MainActivity と RingingActivity）は対象の外とした。
