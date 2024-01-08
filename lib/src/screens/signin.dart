import 'dart:ui' as ui;

import 'package:calendar_alarm/src/app.dart';
import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:calendar_alarm/src/layouts/page-layout.dart';
import 'package:calendar_alarm/src/services/authentication-service.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';
import 'package:sign_in_button/sign_in_button.dart';

class Signin extends StatefulWidget {
  const Signin({super.key});

  @override
  _SigninState createState() => _SigninState();
}

class _SigninState extends State<Signin> {
  final double height = ui.window.physicalSize.height;

  final authService = AuthenticationService(FirebaseAuth.instance);

  void _handleSignInGoogle() async {
    try {
      await authService.signInWithGoogle();
      // サインイン成功時の処理
    } on FirebaseAuthException catch (e) {
      // エラーダイアログを表示
      MyApp.showErrorDialog(context, 'Googleサインインエラー', 'サインインに失敗しました: $e');
    }
  }

  void _handleSignInApple() async {
    try {
      await authService.signInWithApple();
      // サインイン成功時の処理
    } on FirebaseAuthException catch (e) {
      // エラーダイアログを表示
      MyApp.showErrorDialog(context, 'Appleサインインエラー', 'サインインに失敗しました: $e');
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
      notShowNavbar: false,
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
                            onPressed: () => _handleSignInGoogle()),
                        const SizedBox(height: 20),
                        _signInButton(
                            text: "Appleにサインイン",
                            buttonType: Buttons.apple,
                            onPressed: () => _handleSignInApple()),
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
