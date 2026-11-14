# Android の宣言の表

対象は `androidApp/src/main/AndroidManifest.xml` の権限 15 とサービス 3 と受信 4 である。
この表は棚卸しだけを行う。残すか消すかは決めない。
「持ち主の判断」の欄は空けてある。答えが出たら埋める。

- 行番号は 2026-11-13 時点の `master` のもの。
  - 例外: 権限 #15 は #191 で足した行。#191 を取り込むと manifest の 16 行目に入り、#7 以降の行番号は 1 つずつ後ろへずれる（表の行番号は直していない）。
- 「審査で要る説明」の出典は Google Play ヘルプと Android の公式資料（2026-11-13 に確認）。
  - 正確なアラームは https://support.google.com/googleplay/android-developer/answer/13986130
  - 全画面の通知は https://support.google.com/googleplay/android-developer/answer/13161072
  - 常駐サービスの型は https://support.google.com/googleplay/android-developer/answer/13392821
  - Android 10 の全画面の通知の変更は https://developer.android.com/about/versions/10/behavior-changes-10
- 申告の項目に当たらない宣言は「申告の項目なし」と書いた。審査で見られる可能性までは確かめていない。
- Play Console の申告画面は操作していない。
- Play が見るのはライブラリの manifest を合成した後の manifest である。ライブラリが足す権限は末尾にまとめた。

## 権限（15）

| # | 宣言（manifest の行） | 使っている場所 | 無いと何が起きるか | 審査で要る説明 | 持ち主の判断 |
|---|---|---|---|---|---|
| 1 | READ_CALENDAR（6） | `data/calendar/CalendarContractReader.kt:32`（カレンダー一覧）、`:53`（予定の展開）<br>`CalendarAlarmApp.kt:75`、`:123`（許可が有る時だけ変更を監視）<br>`ui/Permissions.kt:110`、`:167`（許可の確認と依頼） | 予定を読めないので予定からアラームを作れない。アプリの中心機能が止まる | 申告の項目なし。実行時の許可ダイアログで頼む。使い道の説明は権限の画面の文言（`ui/Permissions.kt` の `perm_*` 文字列）にある | |
| 2 | WRITE_CALENDAR（7） | `ui/ExtractedEventDialog.kt:122`（許可の確認と依頼）、`:178`（`CalendarContract.Events` へ insert） | 「メールから予定」の確定時に予定を書き込めない。それ以外の機能には影響しない | 申告の項目なし。メールから取り出した予定を端末のカレンダーへ書く説明が要る見込み | |
| 3 | POST_NOTIFICATIONS（9） | `ui/Permissions.kt:118`、`:168`（API 33 以上で許可を確認と依頼）、`:339` | API 33 以上で通知を出せない。鳴動サービスの通知と全画面の通知が出ない | 申告の項目なし | |
| 4 | USE_EXACT_ALARM（11） | `engine/AlarmScheduler.kt:34`（`AlarmManager.setAlarmClock`）。`:21` のコメントに取り下げ時の `SecurityException` を想定する旨がある | 条件を満たす OS ではこれで正確なアラームが使える。無い場合は SCHEDULE_EXACT_ALARM の許可を利用者に頼む流れ（`ui/Permissions.kt:152` の EXACT）になる | **要る。** Play Console で正確なアラームを申告する。USE_EXACT_ALARM は「アラームまたはタイマーのアプリ」と「予定の通知を出すカレンダーアプリ」だけに許される。満たさないと公開できない | |
| 5 | SCHEDULE_EXACT_ALARM（12） | `ui/Permissions.kt:124`（`canScheduleExactAlarms`）、`:152`、`ui/SettingsScreen.kt:581`、`ui/TroubleshootingScreen.kt:119`、`:199`（許可画面を開く）。manifest に上限の OS 版は付いていない | USE_EXACT_ALARM が効かない OS がある。その OS では利用者が許可しないと正確なアラームを予約できない | 申告は USE_EXACT_ALARM の側で行う。出典は SCHEDULE_EXACT_ALARM だけの申告に触れていない。出典はこれを USE_EXACT_ALARM の代わりの手段として挙げている。SCHEDULE_EXACT_ALARM は利用者の許可が要る | |
| 6 | VIBRATE（14） | `engine/AlarmService.kt:658` の `startVibration`、`:668` の `vibrate`。設定は `ui/SettingsScreen.kt:1335`（鳴動中のバイブ） | 鳴動中のバイブが動かない。音と画面は影響を受けない | 申告の項目なし | |
| 7 | WAKE_LOCK（15） | `engine/AlarmService.kt:685` の `acquireWakeLock`、`:688` の `PARTIAL_WAKE_LOCK`（`calendaralarm:ringing`）、`:689` の `acquire`。呼び出しは `:335` | 宣言が無いと `acquire()` が `SecurityException` を投げる。`:335` の呼び出しは `runCatching` で囲まれていない。呼び出しは `:189` の `scope.launch` の中にある。`scope`（`:50`）に例外の受け手が無いのでプロセスが落ちる。`:335` は `startAudio`（`:311`）の後なので鳴り始めた音も止まる。ただし work-runtime 2.10.0 が同じ権限を足す。この行だけを消しても合成後の manifest には残る | 申告の項目なし | |
| 8 | USE_FULL_SCREEN_INTENT（16） | `engine/AlarmService.kt:538`（`canUseFullScreenIntent`）、`:571`（`setFullScreenIntent`）<br>`ui/Permissions.kt:128`、`:154`、`ui/SettingsScreen.kt:584`、`ui/TroubleshootingScreen.kt:125`、`:205` | API 29 以上の端末で全画面の通知が無視される（対象 API 29 以上のアプリへの Android 10 の決まり）。このアプリは minSdk 26 で targetSdk 35 なので API 29 以上の端末すべてに当たる。ロック中やスリープ中も鳴動画面は全面表示されない。通知の小さい表示だけになる | **要る。** API 34 以上を対象にするアプリは Play Console で申告する。アラームか通話が中心機能のアプリだけが自動で許可される。審査がある | |
| 9 | SYSTEM_ALERT_WINDOW（19） | `engine/RingingOverlay.kt:54`（`Settings.canDrawOverlays`）、`:87`（`TYPE_APPLICATION_OVERLAY`）<br>`engine/AlarmService.kt:346`（停止画面を出す分岐） | 全画面の通知が効かない端末で停止画面の保険が出ない。許可はアプリから頼まない。利用者が自分で許可した端末だけで使う | 申告の項目なし。ただし重ねて表示する権限なので審査で使い道を聞かれる可能性がある。manifest のコメント（17-18 行）にある「停止画面の保険」が説明の材料になる | |
| 10 | FOREGROUND_SERVICE（21） | `engine/AlarmService.kt:477`、`:482`（`startForeground`）<br>`engine/AlarmReceiver.kt:52`（`startForegroundService`） | API 28 以上で `startForeground` が `SecurityException` を投げる。`engine/AlarmService.kt:488` の `catch` が受けて `:200-211` で見逃しに記録する。アラームは鳴らない。ただし work-runtime 2.10.0 が同じ権限を足す。この行だけを消しても合成後の manifest には残る | 常駐サービスの型の申告（#11 を参照） | |
| 11 | FOREGROUND_SERVICE_MEDIA_PLAYBACK（22） | `engine/AlarmService.kt:477-479`（API 34 以上で `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`） | API 34 以上で `mediaPlayback` の型で `startForeground` を呼ぶと例外になる。呼び出し側は例外を受けて見逃しに記録する（`:489` 付近のコメント） | **要る。** API 34 以上を対象にするアプリは型ごとに申告する。説明と影響と手順の動画が要る。`mediaPlayback` は利用者が始めた再生でなければならない（英語版の原文は「Playback must be user-initiated.」）。動画には利用者が再生を始める様子を示す。鳴動は予約の時刻に自動で始まる。この条件に当たるかの確認が要る。申告が通らない恐れがある。動画に何を示すかは未決 | |
| 12 | RECEIVE_BOOT_COMPLETED（24） | `engine/BootReceiver.kt:17-25`（再起動後と自分の更新後に `SyncWorker.enqueueNow`） | work-runtime 2.10.0 が同じ権限を足す。この行だけを消しても合成後の manifest には残る。権限が合成後にも無い場合は `BootReceiver` が再起動後の放送を受けられない | 申告の項目なし | |
| 13 | REQUEST_IGNORE_BATTERY_OPTIMIZATIONS（26） | `ui/SettingsScreen.kt:597`、`ui/TroubleshootingScreen.kt:187`（省電力の解除を頼む画面を直接開く）。確認は `ui/SettingsScreen.kt:513`、`ui/TroubleshootingScreen.kt:112` | 解除を頼む画面を直接開けない。鳴動そのものは影響を受けない（最適化の対象でも鳴ると `ui/TroubleshootingScreen.kt:181` のコメントが書いている。`res/values/strings.xml:173` の文言も同じ） | 申告の項目なし。ただし Android の公式資料は次のように書いている。Doze と App Standby の除外を直接要求することを Play の規約が禁じている。アプリの中核機能に悪影響が出る場合だけが例外である。出典は https://developer.android.com/training/monitoring-device-state/doze-standby 。この表の作成者は規約の文面そのものを読んでいない。このアプリが当たるかは未判断 | |
| 14 | INTERNET（28） | `shared/.../weather/WeatherApi.kt:33`、`:45`、`:77`（天気の取得）<br>`shared/.../ai/OpenRouterApi.kt:35`（AI による予定の抽出）<br>`data/calendar/GoogleCalendarTypes.kt:33`（Google カレンダー API） | 天気と AI による予定の抽出と Google カレンダーの取り込みが使えない。端末のカレンダーとアラーム本体は動く | 申告の項目なし。通信先と送るデータ（予定の文章と地名）がデータの安全性の申告の材料になる見込み | |
| 15 | ACCESS_NOTIFICATION_POLICY（16。#191 で追加） | `ui/TroubleshootingScreen.kt:226`、`:230`（`isNotificationPolicyAccessGranted` で許可を確認）、`:236`（`ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` を開く）。設定「おやすみモードを無視」が ON の時だけ検査する | 設定の「通知ポリシーへのアクセス」の一覧にこのアプリが出ない。利用者が許可できないので、おやすみモード中に鳴動チャンネルの bypassDnd が効かない（#191 のレビュー指摘） | 申告の項目なし（未確認） | |

## サービス（3）

| # | 宣言（manifest の行） | 使っている場所 | 無いと何が起きるか | 審査で要る説明 | 持ち主の判断 |
|---|---|---|---|---|---|
| 1 | `.engine.AlarmService`（66-69）。`foregroundServiceType="mediaPlayback"` | `engine/AlarmReceiver.kt:52`、`:137-138`（`ACTION_START` で起動）<br>`engine/AlarmService.kt:471-482`（前面化） | アラームが鳴らない。アプリの中心機能が止まる | **要る。** 常駐サービスの型の申告（権限 #10 と #11）。`exported=false` なので他アプリからは呼ばれない | |
| 2 | `.engine.NotificationAlarmService`（73-80）。`BIND_NOTIFICATION_LISTENER_SERVICE` を要求 | `engine/NotificationAlarmService.kt:32`（`onListenerConnected`）、`:49`（`onNotificationPosted`）。他アプリの通知の題と本文を読む。設定した規則に合えばアラームにする。自分の通知と進行中の通知とグループの要約は拾わない（`:50-55`） | 「通知アラーム」の機能が動かない。他の機能には影響しない。利用者がシステム設定で「通知へのアクセス」を許可した時だけ動く | 申告の項目なし。他アプリの通知を読むので審査で使い道の説明が要る可能性が高い。読む内容が説明の材料になる。利用者が許可した時だけ動くことも材料になる。この表は端末の外へ出さないかどうかを確かめていない | |
| 3 | `.ai.MailPrintService`（83-93）。`BIND_PRINT_SERVICE` を要求。`printservice.xml` の設定画面は `MainActivity` | `ai/MailPrintService.kt:53`（`onPrintJobQueued`）。メールの印刷で届いた PDF の文字を OpenRouter で予定に変換する。実際には印刷しない。AI の鍵が無い版は `:61` の分岐で「AI は使えません」と通知して終わる | 「印刷から予定」の機能が動かない。他の機能には影響しない。なお `onCreatePrinterDiscoverySession` は `null` を返す（`ai/MailPrintService.kt:46`）ので印刷先を 1 つも登録しない。この表は 2 点を確かめていない。印刷先の一覧に出るか。印刷ジョブが届いて `:53` に着くか。（#212 は出ると書いている） | 申告の項目なし。印刷サービスをプリンタではなくメールの取り込み口として使う点の説明が要る見込み。PDF の文字を外部の AI サービスへ送る点がデータの安全性の申告の材料になる | |

## 受信（4）

| # | 宣言（manifest の行） | 使っている場所 | 無いと何が起きるか | 審査で要る説明 | 持ち主の判断 |
|---|---|---|---|---|---|
| 1 | `.engine.AlarmReceiver`（96-98）。`exported=false` | `engine/AlarmReceiver.kt:16`、`:20`（`ACTION_FIRE`）、`:52`（鳴動サービスの起動）。予約は `engine/AlarmScheduler.kt:34` の `setAlarmClock` | 予約の時刻になっても鳴動が始まらない。停止の放送も受けられない | 申告の項目なし。他アプリからは呼ばれない | |
| 2 | `.engine.BootReceiver`（101-110）。`exported=true`。BOOT_COMPLETED と MY_PACKAGE_REPLACED と QUICKBOOT_POWERON | `engine/BootReceiver.kt:17-25`（`SyncWorker.enqueueNow`） | 再起動後と更新後にアラームが復元されない | 申告の項目なし。権限 #12 と対 | |
| 3 | `.engine.TimeChangeReceiver`（113-121）。`exported=true`。TIME_SET と TIMEZONE_CHANGED と DATE_CHANGED | `engine/TimeChangeReceiver.kt:16-18`（`SyncWorker.enqueueNow`） | 時刻とタイムゾーンと日付を変えても予約が組み直されない。時刻のずれたアラームが残る | 申告の項目なし | |
| 4 | `.widget.NextAlarmWidgetReceiver`（124-134）。`exported=false`。APPWIDGET_UPDATE | `widget/NextAlarmWidget.kt:87` | 「次のアラーム」のウィジェットが更新されない。または置けない。他の機能には影響しない | 申告の項目なし | |

## ライブラリが足す権限

アプリの manifest に行が無いまま合成後の manifest に入る権限がある。work-runtime 2.10.0（`gradle/libs.versions.toml:14`、`androidApp/build.gradle.kts:81`）の分を確かめた。

| 権限 | 使っている場所 | この行を消した時 | 審査で要る説明 | 持ち主の判断 |
|---|---|---|---|---|
| ACCESS_NETWORK_STATE | アプリのコードは直接使っていない（`ACCESS_NETWORK_STATE` と `ConnectivityManager` と `NetworkCapabilities` を `androidApp` と `shared` で検索して 0 件）。work-runtime がネットワーク条件つきの仕事の判定に使う | アプリの manifest に行が無いので消せない。合成後に入る | 申告の項目なし | |

work-runtime 2.10.0 の manifest にある権限は WAKE_LOCK と ACCESS_NETWORK_STATE と RECEIVE_BOOT_COMPLETED と FOREGROUND_SERVICE の 4 つ。
このうち 3 つ（WAKE_LOCK と RECEIVE_BOOT_COMPLETED と FOREGROUND_SERVICE）はアプリの manifest も宣言している（権限 #7 と #12 と #10）。
確かめ方は次のとおり。Gradle のビルドは走らせていない。

- 手元の Gradle キャッシュにある `~/.gradle/caches/modules-2/files-2.1/androidx.work/work-runtime/2.10.0/` の `work-runtime-2.10.0.aar` を `unzip -p` で開いた。`AndroidManifest.xml` の `uses-permission` を読んだ。
- 結果は 23 行の WAKE_LOCK と 24 行の ACCESS_NETWORK_STATE と 25 行の RECEIVE_BOOT_COMPLETED と 26 行の FOREGROUND_SERVICE。
- サービス 3 つと受信 8 つの宣言も同じ manifest にある。この表では扱っていない。
- 他のライブラリが足す権限は調べていない。ビルドしていないので合成後の manifest そのものは見ていない。

## 数の確認

| 種類 | 計画の数 | 実際の数 | 方法 |
|---|---|---|---|
| 権限（`uses-permission`） | 14 | 15 | manifest の 6-30 行を数えた。#15 は #191 で追加 |
| サービス（`service`） | 3 | 3 | AlarmService と NotificationAlarmService と MailPrintService |
| 受信（`receiver`） | 4 | 4 | AlarmReceiver と BootReceiver と TimeChangeReceiver と NextAlarmWidgetReceiver |
| ライブラリが足す権限 | なし | 1 | ACCESS_NETWORK_STATE（上の節） |

アクティビティ（MainActivity と RingingActivity）は対象の外とした。#308 の範囲が権限とサービスの宣言のため。RingingActivity の `showWhenLocked` と `turnScreenOn`（60-61 行）は全画面の通知の申告で説明の材料になる。
