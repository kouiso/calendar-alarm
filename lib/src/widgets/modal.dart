import 'package:flutter/material.dart';

class AlarmSettingsModal extends StatefulWidget {
  final String title;
  final VoidCallback? onConfirmed;

  const AlarmSettingsModal({
    Key? key,
    required this.title,
    this.onConfirmed,
  }) : super(key: key);

  @override
  _AlarmSettingsModalState createState() => _AlarmSettingsModalState();
}

class _AlarmSettingsModalState extends State<AlarmSettingsModal> {
  TimeOfDay? selectedTime;
  String? selectedRingtone;
  bool isVibrationEnabled = false;
  double alarmVolume = 0.5;

  void _onTimeChanged(TimeOfDay? newTime) {
    if (newTime != null && newTime != selectedTime) {
      setState(() => selectedTime = newTime);
    }
  }

  void _onRingtoneChanged(String? newRingtone) {
    if (newRingtone != null && newRingtone != selectedRingtone) {
      setState(() => selectedRingtone = newRingtone);
    }
  }

  void _onVibrationChanged(bool newValue) {
    setState(() => isVibrationEnabled = newValue);
  }

  void _onVolumeChanged(double newValue) {
    setState(() => alarmVolume = newValue);
  }

  Future<void> _selectTime(BuildContext context) async {
    final TimeOfDay? picked = await showTimePicker(
      context: context,
      initialTime: selectedTime ?? TimeOfDay.now(),
    );
    _onTimeChanged(picked);
  }

  Future<void> _selectRingtone(BuildContext context) async {
    // 仮のリングトーンリスト
    final List<String> ringtones = ['Ringtone 1', 'Ringtone 2', 'Ringtone 3'];

    // ダイアログを表示してリングトーンを選択
    final String? picked = await showDialog<String>(
      context: context,
      builder: (BuildContext context) {
        return SimpleDialog(
          title: const Text('目覚まし音を選択'),
          children: ringtones.map((String ringtone) {
            return SimpleDialogOption(
              onPressed: () {
                Navigator.pop(context, ringtone); // 選択したリングトーンを返す
              },
              child: Text(ringtone),
            );
          }).toList(),
        );
      },
    );
    _onRingtoneChanged(picked);
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.title),
      content: SingleChildScrollView(
        child: ListBody(
          children: <Widget>[
            _buildTimePicker(context),
            _buildRingtonePicker(context),
            _buildVibrationSwitch(),
            _buildVolumeSlider(),
          ],
        ),
      ),
      actions: _buildDialogActions(context),
      contentPadding:
          const EdgeInsets.symmetric(horizontal: 24.0, vertical: 20.0),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.all(Radius.circular(20.0)),
      ),
    );
  }

  Widget _buildTimePicker(BuildContext context) {
    return ListTile(
      title: const Text('時間を設定'),
      trailing: Text(
        selectedTime?.format(context) ?? '時間を選択',
        style: TextStyle(color: Theme.of(context).colorScheme.secondary),
      ),
      onTap: () => _selectTime(context),
    );
  }

  Widget _buildRingtonePicker(BuildContext context) {
    return ListTile(
      title: const Text('目覚まし音を選択'),
      trailing: Text(
        selectedRingtone ?? '音を選択',
        style: TextStyle(color: Theme.of(context).colorScheme.secondary),
      ),
      onTap: () => _selectRingtone(context),
    );
  }

  Widget _buildVibrationSwitch() {
    return SwitchListTile(
      title: const Text('バイブレーション'),
      value: isVibrationEnabled,
      onChanged: _onVibrationChanged,
    );
  }

  Widget _buildVolumeSlider() {
    return ListTile(
      title: const Text('アラームの音量'),
      subtitle: Slider(
        value: alarmVolume,
        min: 0,
        max: 1,
        divisions: 10,
        onChanged: _onVolumeChanged,
      ),
    );
  }

  List<Widget> _buildDialogActions(BuildContext context) {
    return [
      TextButton(
        child: const Text('キャンセル'),
        onPressed: () => Navigator.of(context).pop(),
      ),
      TextButton(
        child: const Text('OK'),
        onPressed: () {
          widget.onConfirmed?.call();
          Navigator.of(context).pop();
        },
      ),
    ];
  }
}
