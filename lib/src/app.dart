import 'package:calendar_alarm/src/constants/Words.dart';
import 'package:calendar_alarm/src/screens/onboarding.dart';
import 'package:calendar_alarm/src/screens/profile.dart';
import 'package:calendar_alarm/src/screens/settings.dart';
import 'package:calendar_alarm/src/screens/signin.dart';
import 'package:calendar_alarm/src/widgets/home_with_fab.dart'; // Import the new file
import 'package:firebase_analytics/firebase_analytics.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

class MyApp extends StatelessWidget {
  final FirebaseAnalytics analytics;

  MyApp({Key? key, required this.analytics})
      : super(key: key); // Remove 'const' keyword

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: Words.appTitle,
      theme: ThemeData(fontFamily: 'OpenSans'),
      debugShowCheckedModeBanner: false,
      localizationsDelegates: [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      supportedLocales: [
        const Locale('ja', 'JP'),
        // 他のサポートされるロケールを追加
      ],
      initialRoute: '/',
      routes: {
        '/': (BuildContext context) => StreamBuilder<User?>(
              stream: FirebaseAuth.instance.authStateChanges(),
              builder: (context, snapshot) {
                if (snapshot.hasData) {
                  return HomeWithFAB(analytics: analytics);
                } else {
                  return const Signin();
                }
              },
            ),
        '/onboarding': (BuildContext context) => const Onboarding(),
        '/home': (BuildContext context) => HomeWithFAB(analytics: analytics),
        '/profile': (BuildContext context) => const Profile(),
        '/settings': (BuildContext context) => const Settings(),
        '/signin': (BuildContext context) => const Signin(),
      },
    );
  }
}
