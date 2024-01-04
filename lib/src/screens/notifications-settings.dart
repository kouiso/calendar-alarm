import 'package:calendar_alarm/src/constants/Theme.dart';
//widgets
import 'package:calendar_alarm/src/widgets/navbar.dart';
import 'package:flutter/material.dart';

class NotificationsSettings extends StatefulWidget {
  const NotificationsSettings({super.key});

  @override
  _NotificationsSettingsState createState() => _NotificationsSettingsState();
}

class _NotificationsSettingsState extends State<NotificationsSettings> {
  late bool switchValueOne;
  late bool switchValueTwo;
  late bool switchValueThree;
  late bool switchValueFour;

  @override
  void initState() {
    setState(() {
      switchValueOne = false;
      switchValueTwo = false;
      switchValueThree = false;
      switchValueFour = false;
    });
    super.initState();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: const Navbar(
        title: 'Notifications Settings',
        backButton: true,
        rightOptions: false,
      ),
      backgroundColor: ArgonColors.bgColorScreen,
      body: Padding(
        padding: const EdgeInsets.only(left: 36, right: 36),
        child: Column(
          children: [
            const Center(
              child: Padding(
                padding: EdgeInsets.only(top: 24),
                child: Text(
                  'Recommended Settings',
                  style: TextStyle(
                    color: ArgonColors.text,
                    fontWeight: FontWeight.w600,
                    fontSize: 18,
                  ),
                ),
              ),
            ),
            const Center(
              child: Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text(
                  'These are the most important settings',
                  style: TextStyle(color: ArgonColors.text, fontSize: 14),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.only(top: 24),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text(
                    'Someone mentions me',
                    style: TextStyle(color: ArgonColors.text),
                  ),
                  Switch.adaptive(
                    value: switchValueOne,
                    onChanged: (bool newValue) =>
                        setState(() => switchValueOne = newValue),
                    activeColor: ArgonColors.primary,
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text(
                    'Anyone follows me',
                    style: TextStyle(color: ArgonColors.text),
                  ),
                  Switch.adaptive(
                    value: switchValueTwo,
                    onChanged: (bool newValue) =>
                        setState(() => switchValueTwo = newValue),
                    activeColor: ArgonColors.primary,
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text(
                    'Someone comments me',
                    style: TextStyle(color: ArgonColors.text),
                  ),
                  Switch.adaptive(
                    value: switchValueThree,
                    onChanged: (bool newValue) =>
                        setState(() => switchValueThree = newValue),
                    activeColor: ArgonColors.primary,
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text(
                    'A seller follows me',
                    style: TextStyle(color: ArgonColors.text),
                  ),
                  Switch.adaptive(
                    value: switchValueFour,
                    onChanged: (bool newValue) =>
                        setState(() => switchValueFour = newValue),
                    activeColor: ArgonColors.primary,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
