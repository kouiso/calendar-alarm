import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:calendar_alarm/src/screens/about.dart';
import 'package:calendar_alarm/src/screens/agreement.dart';
import 'package:calendar_alarm/src/screens/notifications-settings.dart';
import 'package:calendar_alarm/src/screens/privacy.dart';
import 'package:calendar_alarm/src/widgets/drawer.dart';
//widgets
import 'package:calendar_alarm/src/widgets/navbar.dart';
import 'package:calendar_alarm/src/widgets/table-cell.dart';
import 'package:flutter/material.dart';

class Settings extends StatefulWidget {
  const Settings({super.key});

  @override
  _SettingsState createState() => _SettingsState();
}

class _SettingsState extends State<Settings> {
  late bool switchValueOne;
  late bool switchValueTwo;

  @override
  void initState() {
    setState(() {
      switchValueOne = false;
      switchValueTwo = false;
    });
    super.initState();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: const Navbar(
        title: 'Settings',
      ),
      drawer: const ArgonDrawer(currentPage: 'Settings'),
      body: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            children: [
              const Center(
                child: Padding(
                  padding: EdgeInsets.only(top: 8),
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
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text(
                    'Use FaceID to signin',
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
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text(
                    'Auto-Lock security',
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
              TableCellSettings(
                title: 'Notifications',
                onTap: () {
                  Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (context) => const NotificationsSettings(),
                    ),
                  );
                },
              ),
              const SizedBox(height: 36),
              const Center(
                child: Padding(
                  padding: EdgeInsets.only(top: 16),
                  child: Text(
                    'Payment Settings',
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
                    'These are also important settings',
                    style: TextStyle(color: ArgonColors.text),
                  ),
                ),
              ),
              const TableCellSettings(title: 'Manage Payment Options'),
              const TableCellSettings(title: 'Manage Gift Cards'),
              const SizedBox(
                height: 36,
              ),
              const Center(
                child: Padding(
                  padding: EdgeInsets.only(top: 16),
                  child: Text(
                    'Privacy Settings',
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
                    'Third most important settings',
                    style: TextStyle(color: ArgonColors.text),
                  ),
                ),
              ),
              TableCellSettings(
                title: 'User Agreement',
                onTap: () {
                  Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (context) => const UserAgreement(),
                    ),
                  );
                },
              ),
              TableCellSettings(
                title: 'Privacy',
                onTap: () {
                  Navigator.push(
                    context,
                    MaterialPageRoute(builder: (context) => const Privacy()),
                  );
                },
              ),
              TableCellSettings(
                title: 'About',
                onTap: () {
                  Navigator.push(
                    context,
                    MaterialPageRoute(builder: (context) => const About()),
                  );
                },
              ),
            ],
          ),
        ),
      ),
    );
  }
}
