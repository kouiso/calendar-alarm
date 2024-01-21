import 'package:flutter/material.dart';

// 時間設定ウィジェット
class TimePickerWidget extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return ElevatedButton(
      onPressed: () => _selectTime(context),
      child: Text('時間を設定'),
    );
  }

  Future<void> _selectTime(BuildContext context) async {
    final TimeOfDay? pickedTime = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.now(),
    );

    if (pickedTime != null) {
      // 選択された時間に基づいて何かの処理を行う
      // 例: setStateを使って状態を更新するなど
    }
  }
}
