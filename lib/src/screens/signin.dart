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

  // Googleサインアップメソッド
  Future<void> _signInWithGoogle() async {
    try {
      // Googleサインインのインスタンスを作成
      final GoogleSignIn googleSignIn = GoogleSignIn();
      final GoogleSignInAccount? googleUser = await googleSignIn.signIn();

      // Googleユーザーがnullでないことを確認
      if (googleUser != null) {
        final GoogleSignInAuthentication googleAuth =
            await googleUser.authentication;
        final AuthCredential credential = GoogleAuthProvider.credential(
          accessToken: googleAuth.accessToken,
          idToken: googleAuth.idToken,
        );

        // FirebaseAuthでサインイン
        await FirebaseAuth.instance.signInWithCredential(credential);
      } else {
        // ユーザーがGoogleのサインインをキャンセルした場合の処理
        // 例: ユーザーに通知する、ログを記録するなど
      }
    } catch (error) {
      MyApp.showErrorDialog(context, 'サインインエラー', 'サインインに失敗しました: $error');
    }
  }

  // Appleサインアップメソッド
  Future<void> _signInWithApple() async {
    try {
      final appleCredential = await SignInWithApple.getAppleIDCredential(
        scopes: [
          AppleIDAuthorizationScopes.email,
          AppleIDAuthorizationScopes.fullName
        ],
      );
      final oauthCredential = OAuthProvider("apple.com").credential(
        idToken: appleCredential.identityToken,
        accessToken: appleCredential.authorizationCode,
      );
      await FirebaseAuth.instance.signInWithCredential(oauthCredential);
      // サインイン成功後の処理...
    } catch (error) {
      MyApp.showErrorDialog(context, 'サインインエラー', 'サインインに失敗しました: $error');
    }
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
                image: AssetImage('assets/img/Signin-bg.png'),
                fit: BoxFit.cover,
              ),
            ),
          ),
          SafeArea(
            child: ListView(
              padding: EdgeInsets.only(
                top: (screenHeight - 320) / 2, // カードの高さを考慮して中央に配置
                bottom: (screenHeight - 320) / 2,
              ),
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(
                      horizontal: 24.0), // 左右のマージンを設定
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
                        SizedBox(
                          height: 60, // Googleボタンの高さ
                          child: SignInButton(
                            text: "Googleにサインイン",
                            Buttons.google,
                            onPressed: _signInWithGoogle,
                          ),
                        ),
                        const SizedBox(height: 20), // ボタン間のスペース
                        SizedBox(
                          height: 60, // Appleボタンの高さ
                          child: SignInButton(
                            text: "Appleにサインイン",
                            Buttons.apple,
                            onPressed: _signInWithApple,
                          ),
                        ),
                        const SizedBox(height: 20), // 下の余白
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
