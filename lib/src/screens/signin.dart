import 'dart:ui' as ui;

import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:calendar_alarm/src/constants/Words.dart';
import 'package:calendar_alarm/src/layouts/page-layout.dart';
import 'package:calendar_alarm/src/services/authentication-service.dart';
import 'package:calendar_alarm/src/utils/index.dart'
    as utils; // Updated import path
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
    SignInMethod('Google', SignInProvider.google),
    SignInMethod('Apple', SignInProvider.apple),
    // Add Microsoft support if necessary
  ];

  bool _isLoading = false;

  Future<void> _handleSignIn(SignInProvider provider) async {
    try {
      await authService.signIn(provider);
      // サインイン成功時の処理、例えばホーム画面へのリダイレクト
      Navigator.of(context).pushReplacementNamed('/home');
    } catch (e) {
      // ユーザーフレンドリーなエラーメッセージを表示
      final errorMessage = e is AuthenticationException
          ? e.message
          : Words.unexpectedErrorOccurred;
      utils.showErrorDialog(context, '${provider.name}${Words.signInError}',
          errorMessage); // Updated function call
    }
  }

  Widget _signInButton(
      {required String text, required SignInProvider provider}) {
    return SignInButton(
      _getButton(provider),
      text: text,
      onPressed: () async {
        setState(() => _isLoading = true); // ローディング状態を開始
        await _handleSignIn(provider);
        setState(() => _isLoading = false); // ローディング状態を終了
      },
    );
  }

  Buttons _getButton(SignInProvider provider) {
    switch (provider) {
      case SignInProvider.google:
        return Buttons.google;
      case SignInProvider.apple:
        return Buttons.apple;
      // Microsoftサポートを追加する場合はここにcaseを追加
      default:
        return Buttons.google; // デフォルトのフォールバック
    }
  }

  @override
  Widget build(BuildContext context) {
    double screenHeight = MediaQuery.of(context).size.height;

    return PageLayout(
      title: Words.signIn,
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
                            provider: method.provider,
                          ),
                        const SizedBox(height: 20),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
          if (_isLoading)
            const Center(
                child: CircularProgressIndicator()), // ローディングインジケーターを表示
        ],
      ),
    );
  }
}

class SignInMethod {
  final String name;
  final SignInProvider provider;

  SignInMethod(this.name, this.provider);
}
