import 'package:calendar_alarm/src/screens/home.dart';
import 'package:calendar_alarm/src/widgets/modal.dart'; // AlarmSettingsModalのインポートが必要です
import 'package:firebase_analytics/firebase_analytics.dart';
import 'package:flutter/material.dart';
import 'package:popup_menu/popup_menu.dart';

class HomeWithFAB extends StatelessWidget {
  final FirebaseAnalytics analytics;
  final GlobalKey _menuKey = GlobalKey();

  HomeWithFAB({Key? key, required this.analytics}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: const Home(),
      floatingActionButton: FloatingActionButton(
        key: _menuKey,
        onPressed: () => _showAddOptions(context),
        child: const Icon(Icons.add), // プラスアイコン
        tooltip: '追加', // ツールチップに「追加」と表示
      ),
    );
  }

  void _showAddOptions(BuildContext context) {
    PopupMenu menu = PopupMenu(
      context: context,
      items: [
        MenuItem(
          title: '目覚ましを設定',
          image: Icon(Icons.alarm),
        ),
        MenuItem(
          title: 'イベントを登録',
          image: Icon(Icons.event),
        ),
      ],
      onClickMenu: (MenuItemProvider item) {
        if (item.menuTitle == '目覚ましを設定') {
          _showAlarmSettingsModal(context);
        } else if (item.menuTitle == 'イベントを登録') {
          // ここにイベント登録ロジックを追加
        }
      },
    );
    menu.show(widgetKey: _menuKey);
  }

  void _showAlarmSettingsModal(BuildContext context) {
    showDialog(
      context: context,
      builder: (BuildContext context) {
        // AlarmSettingsModalのインスタンスを作成し、表示する
        return AlarmSettingsModal(
          // AlarmSettingsModalのコンストラクタに必要なパラメータを渡す
          // 例:
          title: 'アラーム設定',
          onConfirmed: () {
            // 確認ボタンが押されたときの処理
          },
        );
      },
    );
  }
}
