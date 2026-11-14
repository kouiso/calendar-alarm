Device evidence for #365 on a real Pixel: the muted status line shows on both screens, in Japanese and English.

```
DEVICE EVIDENCE | PR #365 | head 23ec50d7a60727732808f160de832df40e8e8ea4
build   Android CI run 43591342661 debug APK | versionCode 1 read back
device  Pixel 7 Pro, Android 17, ja-JP and en-US (per-app locale), default font and density
setup   calendar + notification permission granted, "mute all alarms" on in the app
mute    zen_mode=2 read back, streams at minimum, nothing rang
board   d68 home (cloud lane map); comparison is the reviewer's
not covered  agenda header, 次のアラーム and なし stay Japanese in en-US (hard-coded, outside this PR)
```

| | agenda | settings |
|---|---|---|
| ja | ![agenda ja](https://github.com/kouiso/calendar-alarm/blob/6f95614/evidence/365-23ec50d-agenda-ja.png?raw=true) | ![settings ja](https://github.com/kouiso/calendar-alarm/blob/6f95614/evidence/365-23ec50d-settings-ja.png?raw=true) |
| en | ![agenda en](https://github.com/kouiso/calendar-alarm/blob/6f95614/evidence/365-23ec50d-agenda-en.png?raw=true) | ![settings en](https://github.com/kouiso/calendar-alarm/blob/6f95614/evidence/365-23ec50d-settings-en.png?raw=true) |
