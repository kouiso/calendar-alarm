import 'package:firebase_analytics/firebase_analytics.dart';
import 'package:firebase_auth/firebase_auth.dart'; // FirebaseAuthをインポート
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_crashlytics/firebase_crashlytics.dart';
import 'package:flutter/material.dart';

import 'firebase_options.dart';
import 'src/app.dart';

/// アプリケーションのメインエントリポイントです。
///
/// Firebaseの初期化、CrashlyticsとAnalyticsの設定、匿名ログインを行い、
/// アプリケーションを起動します。
void main() async {
  // Flutterのウィジェットバインディングを初期化します。
  WidgetsFlutterBinding.ensureInitialized();

  // Firebaseを初期化します。
  await Firebase.initializeApp(
    options: DefaultFirebaseOptions.currentPlatform,
  );

  // FlutterエラーをCrashlyticsに記録するためのハンドラを設定します。
  FlutterError.onError = FirebaseCrashlytics.instance.recordFlutterError;

  // Firebase Analyticsのインスタンスを作成します。
  final FirebaseAnalytics analytics = FirebaseAnalytics.instance;

  // 匿名ユーザーとしてFirebase Authにログインします。
  await FirebaseAuth.instance.signInAnonymously();

  // MyAppウィジェットを起動し、Firebase Analyticsのインスタンスを渡します。
  runApp(MyApp(analytics: analytics));
}
