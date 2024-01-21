import 'package:flutter/material.dart';

// バイブレーション設定ウィジェット
class VibrationSwitchWidget extends StatefulWidget {
  @override
  _VibrationSwitchWidgetState createState() => _VibrationSwitchWidgetState();
}

class _VibrationSwitchWidgetState extends State<VibrationSwitchWidget> {
  bool isVibrationOn = false;

  @override
  Widget build(BuildContext context) {
    return SwitchListTile(
      title: Text('バイブレーション'),
      value: isVibrationOn,
      onChanged: _toggleVibration,
    );
  }

  void _toggleVibration(bool value) {
    setState(() {
      isVibrationOn = value;
    });
    // ここにバイブレーション設定ロジックを追加する
  }
}
