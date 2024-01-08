import 'package:calendar_alarm/src/screens/home.dart';
import 'package:calendar_alarm/src/screens/onboarding.dart';
import 'package:calendar_alarm/src/screens/profile.dart';
import 'package:calendar_alarm/src/screens/settings.dart';
import 'package:calendar_alarm/src/screens/signin.dart';
import 'package:calendar_alarm/src/widgets/dialog.dart' as custom_dialog;
import 'package:firebase_analytics/firebase_analytics.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';

class MyApp extends StatelessWidget {
  final FirebaseAnalytics analytics;

  const MyApp({super.key, required this.analytics});

  static void showErrorDialog(
      BuildContext context, String title, String message,
      {VoidCallback? onPressedOk} // ここをオプショナルに変更
      ) {
    showDialog(
      context: context,
      builder: (BuildContext context) => custom_dialog.Dialog(
        title: title,
        message: message,
        onPressedOk: onPressedOk, // コールバック関数をDialogに渡す
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Calendar Alarm',
      theme: ThemeData(fontFamily: 'OpenSans'),
      debugShowCheckedModeBanner: false,
      home: StreamBuilder<User?>(
        stream: FirebaseAuth.instance.authStateChanges(),
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.active) {
            // ユーザーがログインしているかどうかをチェック
            User? user = snapshot.data;
            if (user == null) {
              // ユーザーがログインしていない場合、Registerページにリダイレクト
              return const Signin();
            }
            // ユーザーがログインしている場合、Homeページにリダイレクト
            return const Home();
          }
          // 接続中のローディングインジケーターを表示
          return const Scaffold(
            body: Center(
              child: CircularProgressIndicator(),
            ),
          );
        },
      ),
      routes: <String, WidgetBuilder>{
        '/onboarding': (BuildContext context) => const Onboarding(),
        '/home': (BuildContext context) => const Home(),
        '/profile': (BuildContext context) => const Profile(),
        '/settings': (BuildContext context) => const Settings(),
        '/account': (BuildContext context) => const Signin(),
      },
    );
  }
}
