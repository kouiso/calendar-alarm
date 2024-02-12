import 'package:calendar_alarm/src/constants/Words.dart';
import 'package:calendar_alarm/src/layouts/page-layout.dart';
import 'package:calendar_alarm/src/screens/about.dart';
import 'package:calendar_alarm/src/screens/agreement.dart';
import 'package:calendar_alarm/src/screens/privacy.dart';
import 'package:calendar_alarm/src/services/platform_service.dart';
import 'package:calendar_alarm/src/widgets/table-cell.dart';
import 'package:flutter/material.dart';

/// `Settings`はアプリケーションの設定画面を管理するクラスです。
class Settings extends StatefulWidget {
  /// コンストラクタ
  const Settings({super.key});

  @override
  _SettingsState createState() => _SettingsState();
}

class _SettingsState extends State<Settings> {
  bool _isGoogleSyncEnabled = false; // Googleアカウントとの同期ステータス
  String? _googleAccountEmail; // GoogleアカウントのEmailを保持する変数

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      final List<String> accounts = await PlatformService.getGoogleAccounts();
      _showAccountPicker(context, accounts);
    });
  }

  void _fetchGoogleAccountEmail() async {
    final List<String> accounts = await PlatformService.getGoogleAccounts();
    if (accounts.isNotEmpty) {
      setState(() {
        _googleAccountEmail = accounts.first;
      });
    }
  }

  /// Googleアカウントとの同期を切り替えます。
  void _toggleGoogleSync(bool value) {
    showDialog(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          title: Text("Googleアカウントとの同期"),
          content: Text("端末でログインしているGoogleアカウントと全て連携されますがよろしいですか？"),
          actions: <Widget>[
            TextButton(
              child: Text("キャンセル"),
              onPressed: () {
                Navigator.of(context).pop();
                setState(() {
                  _isGoogleSyncEnabled = false; // スイッチをOFFに戻す
                });
              },
            ),
            TextButton(
              child: Text("OK"),
              onPressed: () {
                Navigator.of(context).pop();
                setState(() {
                  _isGoogleSyncEnabled = true; // スイッチをONに設定
                });
              },
            ),
          ],
        );
      },
    );
  }

  /// エラーダイアログを表示します。
  void _showErrorDialog(String message) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(Words.error),
        content: Text(message),
        actions: [
          TextButton(
            child: Text(Words.close),
            onPressed: () => Navigator.of(context).pop(),
          ),
        ],
      ),
    );
  }

  void _showAccountPicker(BuildContext context, List<String> accounts) async {
    final String? selectedAccount = await showDialog<String>(
      context: context,
      builder: (BuildContext context) {
        return SimpleDialog(
          title: const Text('Googleアカウントを選択'),
          children: accounts.map((String account) {
            return SimpleDialogOption(
              onPressed: () {
                Navigator.pop(context, account);
              },
              child: Text(account),
            );
          }).toList(),
        );
      },
    );

    if (selectedAccount != null) {
      // 選択されたアカウントのEmailを状態に保存
      setState(() {
        _googleAccountEmail = selectedAccount;
      });
      // ここで、Googleカレンダーの同期を開始する処理を追加することもできます
      // 例: _syncGoogleCalendar(selectedAccount);
    }
  }

  /// 指定されたページにナビゲートします。
  void _navigateTo(Widget page) {
    Navigator.push(
      context,
      MaterialPageRoute(builder: (context) => page),
    );
  }

  @override
  Widget build(BuildContext context) {
    return PageLayout(
      title: Words.settings,
      bodyContent: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            children: [
              if (_googleAccountEmail != null) // GoogleアカウントのEmailがある場合のみ表示
                ListTile(
                  leading: Icon(Icons.email),
                  title: Text("Googleアカウント"),
                  subtitle: Text(_googleAccountEmail!),
                ),
              const Divider(),
              TableCellSettings(
                title: Words.userAgreement,
                onTap: () => _navigateTo(const UserAgreement()),
              ),
              TableCellSettings(
                title: Words.privacy,
                onTap: () => _navigateTo(const Privacy()),
              ),
              TableCellSettings(
                title: Words.about,
                onTap: () => _navigateTo(const About()),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
