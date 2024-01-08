import 'package:calendar_alarm/src/app.dart';
import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:calendar_alarm/src/layouts/page-layout.dart';
import 'package:calendar_alarm/src/screens/about.dart';
import 'package:calendar_alarm/src/screens/agreement.dart';
import 'package:calendar_alarm/src/screens/notifications-settings.dart';
import 'package:calendar_alarm/src/screens/privacy.dart';
import 'package:calendar_alarm/src/widgets/table-cell.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:sign_in_with_apple/sign_in_with_apple.dart';

class Settings extends StatefulWidget {
  const Settings({super.key});

  @override
  _SettingsState createState() => _SettingsState();
}

class _SettingsState extends State<Settings> {
  late bool switchValueOne;
  late bool switchValueTwo;
  User? user;

  @override
  void initState() {
    super.initState();
    switchValueOne = false;
    switchValueTwo = false;
    user = FirebaseAuth.instance.currentUser;
    _checkAuthentication();
  }

  void _checkAuthentication() async {
    final user = FirebaseAuth.instance.currentUser;
    if (user == null) {
      // ログイン画面にリダイレクト
      Future.microtask(
          () => Navigator.of(context).pushReplacementNamed('/account'));
    }
  }

  Future<void> linkGoogleAccount() async {
    try {
      final GoogleSignInAccount? googleUser = await GoogleSignIn().signIn();
      final GoogleSignInAuthentication googleAuth =
          await googleUser!.authentication;

      final AuthCredential credential = GoogleAuthProvider.credential(
        accessToken: googleAuth.accessToken,
        idToken: googleAuth.idToken,
      );

      await FirebaseAuth.instance.currentUser!.linkWithCredential(credential);
      // 成功時の処理（例えば状態の更新やメッセージの表示）
    } on FirebaseAuthException catch (e) {
      // エラー処理（例えばエラーダイアログの表示）
      MyApp.showErrorDialog(context, 'Googleサインインエラー', 'サインインに失敗しました: $e');
    }
  }

  Future<void> linkAppleAccount() async {
    try {
      final AuthorizationCredentialAppleID appleCredential =
          await SignInWithApple.getAppleIDCredential(
        scopes: [
          AppleIDAuthorizationScopes.email,
          AppleIDAuthorizationScopes.fullName,
        ],
      );

      final OAuthCredential oauthCredential =
          OAuthProvider("apple.com").credential(
        idToken: appleCredential.identityToken,
        accessToken: appleCredential.authorizationCode,
      );

      await FirebaseAuth.instance.currentUser!
          .linkWithCredential(oauthCredential);
      // 成功時の処理（例えば状態の更新やメッセージの表示）
    } on FirebaseAuthException catch (e) {
      // エラー処理（例えばエラーダイアログの表示）
      MyApp.showErrorDialog(context, 'Googleサインインエラー', 'サインインに失敗しました: $e');
    }
  }

  Future<void> unlinkAccount(String providerId) async {
    try {
      await user!.unlink(providerId);
      setState(() {
        user = FirebaseAuth.instance.currentUser;
      });
      // 成功時の処理（例えば状態の更新やメッセージの表示）
    } catch (e) {
      // エラー処理（例えばエラーダイアログの表示）
      MyApp.showErrorDialog(context, 'Googleサインインエラー', 'サインインに失敗しました: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return PageLayout(
      title: "Settings",
      bodyContent: SingleChildScrollView(
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
              const SizedBox(height: 36),
              const Center(
                child: Padding(
                  padding: EdgeInsets.only(top: 16),
                  child: Text(
                    'Connected Accounts',
                    style: TextStyle(
                      color: ArgonColors.text,
                      fontWeight: FontWeight.w600,
                      fontSize: 18,
                    ),
                  ),
                ),
              ),
              ...user!.providerData.map((provider) {
                return ListTile(
                  title: Text(provider.providerId),
                  trailing: IconButton(
                    icon: const Icon(Icons.delete),
                    onPressed: () => unlinkAccount(provider.providerId),
                  ),
                );
              }),
              const SizedBox(height: 36),
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
