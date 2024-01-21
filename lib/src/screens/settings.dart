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
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _initializeUserAndAuthService();
    });
  }

  void _initializeUserAndAuthService() async {
    _authService = AuthenticationService(FirebaseAuth.instance);
    await _updateCurrentUser();
  }

  Future<void> _updateCurrentUser() async {
    var currentUser = FirebaseAuth.instance.currentUser;
    setState(() {
      user = currentUser;
    });
  }

  Future<void> _linkAccount(Future<void> Function() linkMethod) async {
    try {
      await linkMethod();
      await _updateCurrentUser();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(Words.accountLinked)),
      );
    } catch (e) {
      _showErrorDialog(Words.accountLinkFailed);
    }
  }

  Future<void> _unlinkAccount(String providerId) async {
    if (user!.providerData.length <= 1) {
      _showErrorDialog(Words.cannotDeleteMainAccount);
      return;
    }
    try {
      await _authService.unlinkAccount(providerId);
      await _updateCurrentUser();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(Words.accountUnlinked)),
      );
    } catch (e) {
      _showErrorDialog(Words.accountUnlinkFailed);
    }
  }

  void _showErrorDialog(String message) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(Words.error),
        content: Text(message),
        actions: [
          TextButton(
            child: Text(Words.close),
            onPressed: () => Navigator.of(context).pop(),
          ),
        ],
      ),
    );
  }

  void _navigateTo(Widget page) {
    Navigator.push(
      context,
      MaterialPageRoute(builder: (context) => page),
    );
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
              TableCellSettings(
                title: Words.linkGoogleAccount,
                onTap: () => _linkAccount(
                    () => _authService.linkAccount(SignInProvider.google)),
              ),
              TableCellSettings(
                title: Words.linkAppleAccount,
                onTap: () => _linkAccount(
                    () => _authService.linkAccount(SignInProvider.apple)),
              ),
              const Divider(),
              if (user != null)
                ...user!.providerData.map((provider) {
                  bool isOnlyAccount = user!.providerData.length == 1;
                  return ListTile(
                    title: Text("${Words.linkedAccount}${provider.providerId}"),
                    trailing: IconButton(
                      icon: const Icon(Icons.delete),
                      onPressed: isOnlyAccount
                          ? null
                          : () => _unlinkAccount(provider.providerId),
                      tooltip: isOnlyAccount ? 'メインアカウントは削除できません。' : null,
                    ),
                  );
                }),
              const Divider(),
              // その他の設定項目
              TableCellSettings(
                title: Words.userAgreement,
                onTap: () => _navigateTo(const UserAgreement()),
              ),
              TableCellSettings(
                title: Words.privacy,
                onTap: () => _navigateTo(const Privacy()),
              ),
              TableCellSettings(
                title: Words.about,
                onTap: () => _navigateTo(const About()),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
