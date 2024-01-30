import 'package:flutter/material.dart';
import 'package:flutter_ringtone_player/flutter_ringtone_player.dart';

// アラーム音選択ウィジェット
class RingtonePickerWidget extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return ElevatedButton(
      onPressed: () => _playRingtone(),
      child: Text('アラーム音を選択'),
    );
  }

  void _playRingtone() {
    // アラーム音選択ロジックをメソッドに分離
    FlutterRingtonePlayer().play(
      android: AndroidSounds.notification,
      ios: IosSounds.glass,
      looping: true, // Android only - API >= 28
      volume: 0.1, // Android only - API >= 28
      asAlarm: true, // Android only
    );
  }
}
