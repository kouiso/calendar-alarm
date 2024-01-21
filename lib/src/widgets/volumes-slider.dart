import 'package:flutter/material.dart';

// 音量設定ウィジェット
class VolumeSliderWidget extends StatefulWidget {
  @override
  _VolumeSliderWidgetState createState() => _VolumeSliderWidgetState();
}

class _VolumeSliderWidgetState extends State<VolumeSliderWidget> {
  double volume = 0.5;

  @override
  Widget build(BuildContext context) {
    return Slider(
      value: volume,
      min: 0,
      max: 1,
      divisions: 10,
      label: volume.round().toString(),
      onChanged: _setVolume,
    );
  }

  void _setVolume(double newVolume) {
    setState(() {
      volume = newVolume;
    });
    // ここに音量設定ロジックを追加する
  }
}
