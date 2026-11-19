# PR #191 Android 実機検証 — OS 記録証跡

- 端末: Pixel 7 Pro (serial `2A091FDH300C0J`), Android 17
- ビルド: `androidApp-debug.apk` (PR head `d5a1726`)
- 検証日時: 2026-11-18 21:44–21:54 (ホスト JST)。端末時計は 14:44–14:54 表示
- 検証条件: `zen_mode=1` (マナーモード/DND 有効)、音量ゼロ。可聴確認は行わず、audit_log・dumpsys・通知記録で判定

## audit_log (Room `calendar-alarm.db`)

```
SCHEDULE|adhoc tm:1795067394626 @1795067404626        2026-11-18 21:49:54
WARN|tm:1795067394626: オーバーレイ未許可 (FSI=可)     21:50:04
FIRE|tm:1795067394626                                 21:50:04  ← ON+DND: 鳴動
STOP_TAP / DISMISS                                    21:50:07
SCHEDULE|adhoc tm:1795067627920 @1795067637920        21:53:47
FIRE|tm:1795067627920                                 21:53:58
MISS|tm:1795067627920: おやすみモード中は鳴らさない
      (おやすみモード中も鳴らす=OFF, filter=2)          21:53:58  ← OFF+DND: 鳴動抑止
```

## dumpsys window (ON ケース 21:50 頃)

```
mCurrentFocus=Window{... u0 com.calendaralarm/com.calendaralarm.engine.RingingActivity}
```

## dumpsys notification (ON ケース)

- 鳴動通知: `pkg=com.calendaralarm`, `channel=alarm_vib`, importance=4, fullScreenIntent → RingingActivity, flags=`ONGOING_EVENT|ONLY_ALERT_ONCE|HIGH_PRIORITY`

## 通知チャンネル (dumpsys notification)

| channel | mBypassDnd |
|---|---|
| alarm_vib | true |
| alarm_novib | true |
| alarm_vib_dnd | false |
| alarm_novib_dnd | false |
| alarm_alerts_nv2 | false |
| missed_v1 (OFF ケースの見逃し通知) | - |

## OFF ケース (21:54)

- `mCurrentFocus` = `MainActivity` (RingingActivity は開かず)
- `channel=missed_v1` の通知がポストされ、シェードに「『テスト鳴動』を鳴らせませんでした」と表示

## 設定 UI 状態 (uiautomator)

- 許可前: `content-desc="おやすみモード中も鳴らす（許可が必要）"`, `checked=false`, `enabled=false` + 案内「使うには『おやすみモードの例外』の許可が要ります。」+「許可を開く」
- 「許可を開く」→ `com.android.settings/.Settings$ZenAccessSettingsActivity` が開き、付与ダイアログで「許可」
- 許可後: `content-desc="おやすみモード中も鳴らす"`, `checked=true`, `enabled=true` + 説明カード「オフにすると / おやすみモード中は鳴らさず、通知だけ残す」
