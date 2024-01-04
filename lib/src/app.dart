import 'package:calendar_alarm/src/screens/home.dart';
// screens
import 'package:calendar_alarm/src/screens/onboarding.dart';
import 'package:calendar_alarm/src/screens/profile.dart';
import 'package:calendar_alarm/src/screens/register.dart';
import 'package:calendar_alarm/src/screens/settings.dart';
import 'package:flutter/material.dart';

void main() => runApp(const MyApp());

class MyApp extends StatelessWidget {
  const MyApp({super.key});

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
