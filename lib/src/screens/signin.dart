import 'dart:ui' as ui;

import 'package:calendar_alarm/src/app.dart';
import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:calendar_alarm/src/layouts/page-layout.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:sign_in_button/sign_in_button.dart';
import 'package:sign_in_with_apple/sign_in_with_apple.dart';

class Signin extends StatefulWidget {
  const Signin({super.key});

  @override
  _SigninState createState() => _SigninState();
}

class _SigninState extends State<Signin> {
  final double height = ui.window.physicalSize.height;

  Future<void> _signInWithGoogle() async {
    try {
      final GoogleSignIn googleSignIn = GoogleSignIn();
      final GoogleSignInAccount? googleUser = await googleSignIn.signIn();
      if (googleUser != null) {
        final GoogleSignInAuthentication googleAuth =
            await googleUser.authentication;
        final AuthCredential credential = GoogleAuthProvider.credential(
          accessToken: googleAuth.accessToken,
          idToken: googleAuth.idToken,
        );
        await FirebaseAuth.instance.signInWithCredential(credential);
      } else {
        // ユーザーがGoogleのサインインをキャンセルした場合の処理
      }
    } catch (error) {
      MyApp.showErrorDialog(context, 'サインインエラー', 'サインインに失敗しました: $error');
    }
  }

  Future<void> _signInWithApple() async {
    try {
      final appleCredential = await SignInWithApple.getAppleIDCredential(
        scopes: [
          AppleIDAuthorizationScopes.email,
          AppleIDAuthorizationScopes.fullName,
        ],
      );
      final oauthCredential = OAuthProvider("apple.com").credential(
        idToken: appleCredential.identityToken,
        accessToken: appleCredential.authorizationCode,
      );
      await FirebaseAuth.instance.signInWithCredential(oauthCredential);
    } catch (error) {
      MyApp.showErrorDialog(context, 'サインインエラー', 'サインインに失敗しました: $error');
    }
  }

  Widget _signInButton(
      {required String text,
      required Buttons buttonType,
      required VoidCallback onPressed}) {
    return SizedBox(
      height: 60,
      child: SignInButton(
        text: text,
        buttonType,
        onPressed: onPressed,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    double screenHeight = MediaQuery.of(context).size.height;

    return PageLayout(
      title: "サインイン",
      bodyContent: Stack(
        children: [
          Container(
            decoration: const BoxDecoration(
              image: DecorationImage(
                image: AssetImage('assets/img/register-bg.png'),
                fit: BoxFit.cover,
              ),
            ),
          ),
          SafeArea(
            child: ListView(
              padding: EdgeInsets.only(
                top: (screenHeight - 320) / 2,
                bottom: (screenHeight - 320) / 2,
              ),
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 24.0),
                  child: Card(
                    elevation: 5,
                    clipBehavior: Clip.antiAlias,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(4),
                    ),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const Padding(
                          padding: EdgeInsets.only(top: 20, bottom: 10),
                          child: Text(
                            'サインイン',
                            style: TextStyle(
                              color: ArgonColors.text,
                              fontSize: 16,
                            ),
                          ),
                        ),
                        _signInButton(
                          text: "Googleにサインイン",
                          buttonType: Buttons.google,
                          onPressed: _signInWithGoogle,
                        ),
                        const SizedBox(height: 20),
                        _signInButton(
                          text: "Appleにサインイン",
                          buttonType: Buttons.apple,
                          onPressed: _signInWithApple,
                        ),
                        const SizedBox(height: 20),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
