import 'package:calendar_alarm/src/screens/home.dart';
// screens
import 'package:calendar_alarm/src/screens/onboarding.dart';
import 'package:calendar_alarm/src/screens/profile.dart';
import 'package:calendar_alarm/src/screens/register.dart';
import 'package:calendar_alarm/src/screens/settings.dart';
import 'package:firebase_analytics/firebase_analytics.dart';
import 'package:flutter/material.dart';

class MyApp extends StatelessWidget {
  final FirebaseAnalytics analytics;

  const MyApp({super.key, required this.analytics});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Argon PRO Flutter',
      theme: ThemeData(fontFamily: 'OpenSans'),
      initialRoute: '/onboarding',
      debugShowCheckedModeBanner: false,
      routes: <String, WidgetBuilder>{
        '/onboarding': (BuildContext context) => const Onboarding(),
        '/home': (BuildContext context) => const Home(),
        '/profile': (BuildContext context) => const Profile(),
        '/settings': (BuildContext context) => const Settings(),
        '/account': (BuildContext context) => const Register(),
      },
    );
  }
}
