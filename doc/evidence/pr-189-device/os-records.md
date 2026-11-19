# PR #189 実機検証 OS 記録（iPhone 実機）

- 端末: iPhone 実機（12 系・iOS 26 系）
- 検証日時: 2026-11-18（端末時計 14:00–15:10 頃）
- 判定材料: 音は出さず syslog（SpringBoard/ToneLibrary・mobiletimerd）の記録で判定

## プリセット「ベル」（preset_bell.caf）

`bell-evidence.txt`（syslog 抜粋・32 件一致）:

- `TLAlertConfiguration: type = alarm; externalToneFileURL = .../Library/Sounds/preset_bell.caf; maximumDuration = 900; shouldRepeat = YES; shouldIgnoreRingerSwitch = YES`
- `Successfully set audio category to AVAudioSessionCategoryAlarm` / `Successfully bypassed ringer switch policy`
- `inserting 3 player items into AVQueuePlayer for tone asset: .../preset_bell.caf`（ループ再生用のキュー投入）
- `mobiletimerd didPostToneAlertWithIdentifier: externalTone:.../preset_bell.caf-...`
- `didBeginPlayingWithEvent`（再生開始イベント発火）

## プリセット「ゆっくり」（preset_soft.caf）

`soft-evidence.txt`（syslog 抜粋・32 件一致）:

- `type = alarm; externalToneFileURL = .../Library/Sounds/preset_soft.caf; maximumDuration = 900; shouldRepeat = YES; shouldIgnoreRingerSwitch = YES`
- 同じく `AVAudioSessionCategoryAlarm` 設定・`didPostToneAlertWithIdentifier`・再生開始を確認

## 補足

- 2 つのプリセットが別々の .caf を `Library/Sounds/` 配下のコピーとして AlarmKit の externalTone に渡し、実際に再生キューへ乗ったことを OS 記録で確認（#147 終了条件「2 つ以上のプリセットが鳴る事」を充足）
- アラーム音量フォールバック値 0.0625（ミュート運用下での検証）
- 画面側は各鳴動で AlarmKit のアラーム UI（スヌーズ/スライドして停止）が出ることをスクリーンショットで確認
