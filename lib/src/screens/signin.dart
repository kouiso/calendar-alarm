import 'dart:ui' as ui;

import 'package:calendar_alarm/src/app.dart';
import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:calendar_alarm/src/constants/Words.dart';
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

  final signInMethods = [
    SignInMethod('Google', (authService) => authService.signInWithGoogle()),
    SignInMethod('Apple', (authService) => authService.signInWithApple()),
  ];

  void _handleSignIn(SignInMethod method) async {
    try {
      await method.action(authService);
      // サインイン成功時の処理
    } catch (e) {
      // エラーダイアログを表示
      MyApp.showErrorDialog(context, '${method.name}${Words.signInError}',
          '${Words.signInFailed}$e');
    }
  }

  Widget _signInButton(
      {required String text,
      required Buttons buttonType,
      required Function onPressed}) {
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
      title: Words.signIn,
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
                            Words.signIn,
                            style: TextStyle(
                              color: ArgonColors.text,
                              fontSize: 16,
                            ),
                          ),
                        ),
                        for (var method in signInMethods)
                          _signInButton(
                            text: "${method.name}${Words.signInWith}",
                            buttonType: method.name == 'Google'
                                ? Buttons.google
                                : Buttons.apple,
                            onPressed: () => _handleSignIn(method),
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

class SignInMethod {
  final String name;
  final Future Function(AuthenticationService) action;

  SignInMethod(this.name, this.action);
}
