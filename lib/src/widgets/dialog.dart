// error_dialog.dart
import 'package:flutter/material.dart';

class Dialog extends StatelessWidget {
  final String title;
  final String message;
  final VoidCallback? onPressedOk;

  const Dialog({
    Key? key,
    required this.title,
    required this.message,
    this.onPressedOk,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(title),
      content: Text(message),
      actions: <Widget>[
        TextButton(
          child: const Text('OK'),
          onPressed: () {
            Navigator.of(context).pop(); // ここでダイアログを閉じる
            onPressedOk?.call(); // onPressedOkが存在する場合のみ呼び出す
          },
        ),
      ],
    );
  }
}
