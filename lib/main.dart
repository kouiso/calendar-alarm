import 'package:firebase_analytics/firebase_analytics.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_crashlytics/firebase_crashlytics.dart';
import 'package:flutter/material.dart';

import 'firebase_options.dart';
import 'src/app.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  await Firebase.initializeApp(
    options: DefaultFirebaseOptions.currentPlatform,
  );

  // Crashlyticsの初期設定
  FlutterError.onError = FirebaseCrashlytics.instance.recordFlutterError;

  // Firebase Analyticsのインスタンス
  final FirebaseAnalytics analytics = FirebaseAnalytics.instance;

  runApp(MyApp(analytics: analytics));
}
