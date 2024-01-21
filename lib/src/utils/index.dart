// lib/src/utils/index.dart

import 'package:calendar_alarm/src/widgets/dialog.dart' as custom_dialog;
import 'package:calendar_alarm/src/widgets/modal.dart'; // 必要ならインポート
import 'package:flutter/material.dart';

void showErrorDialog(BuildContext context, String title, String message,
    {VoidCallback? onPressedOk}) {
  showDialog(
    context: context,
    builder: (BuildContext context) => custom_dialog.Dialog(
      title: title,
      message: message,
      onPressedOk: onPressedOk,
    ),
  );
}

void showSettingsModal(BuildContext context) {
  showDialog(
    context: context,
    builder: (BuildContext context) {
      return AlarmSettingsModal(
        title: '設定',
        onConfirmed: () {
          // 設定を保存するなどのアクションを実行
        },
      );
    },
  );
}
