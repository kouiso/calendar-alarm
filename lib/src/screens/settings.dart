import 'package:calendar_alarm/src/constants/Words.dart';
import 'package:calendar_alarm/src/layouts/page-layout.dart';
import 'package:calendar_alarm/src/screens/about.dart';
import 'package:calendar_alarm/src/screens/agreement.dart';
import 'package:calendar_alarm/src/screens/privacy.dart';
import 'package:calendar_alarm/src/services/authentication-service.dart';
import 'package:calendar_alarm/src/widgets/table-cell.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';

class Settings extends StatefulWidget {
  const Settings({super.key});

  @override
  _SettingsState createState() => _SettingsState();
}

class _SettingsState extends State<Settings> {
  User? user;
  late AuthenticationService _authService;

  @override
  void initState() {
    super.initState();
    user = FirebaseAuth.instance.currentUser;
    _authService = AuthenticationService(FirebaseAuth.instance);
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
              // その他の設定項目
              TableCellSettings(
                title: Words.linkGoogleAccount,
                onTap: () async {
                  await _authService.linkGoogleAccount();
                  setState(() {
                    user = FirebaseAuth.instance.currentUser;
                  });
                },
              ),
              TableCellSettings(
                title: Words.linkAppleAccount,
                onTap: () async {
                  await _authService.linkAppleAccount();
                  setState(() {
                    user = FirebaseAuth.instance.currentUser;
                  });
                },
              ),
              const Divider(),
              ...user!.providerData.map((provider) {
                return ListTile(
                  title: Text("${Words.linkedAccount}${provider.providerId}"),
                  trailing: IconButton(
                    icon: const Icon(Icons.delete),
                    onPressed: () async {
                      await _authService.unlinkAccount(provider.providerId);
                      setState(() {
                        user = FirebaseAuth.instance.currentUser;
                      });
                    },
                  ),
                );
              }),
              const Divider(),
              // その他の設定項目
              TableCellSettings(
                title: Words.userAgreement,
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
                title: Words.privacy,
                onTap: () {
                  Navigator.push(
                    context,
                    MaterialPageRoute(builder: (context) => const Privacy()),
                  );
                },
              ),
              TableCellSettings(
                title: Words.about,
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
