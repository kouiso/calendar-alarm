import 'dart:ui' as ui;

import 'package:calendar_alarm/src/app.dart';
import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:calendar_alarm/src/widgets/drawer.dart'; //widgets
import 'package:calendar_alarm/src/widgets/navbar.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';
import 'package:font_awesome_flutter/font_awesome_flutter.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:sign_in_with_apple/sign_in_with_apple.dart';

class Register extends StatefulWidget {
  const Register({super.key});

  @override
  _RegisterState createState() => _RegisterState();
}

class _RegisterState extends State<Register> {
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

        // サインイン成功後の処理...
        // 例: ユーザー情報を取得したり、ホーム画面に遷移したりする
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
    return Scaffold(
      appBar: const Navbar(transparent: true, title: ''),
      extendBodyBehindAppBar: true,
      drawer: const ArgonDrawer(currentPage: 'Account'),
      body: Stack(
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
              children: [
                Padding(
                  padding: const EdgeInsets.only(
                    top: 16,
                    left: 24,
                    right: 24,
                    bottom: 32,
                  ),
                  child: Card(
                    elevation: 5,
                    clipBehavior: Clip.antiAlias,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(4),
                    ),
                    child: Column(
                      children: [
                        Container(
                          height: MediaQuery.of(context).size.height * 0.15,
                          decoration: const BoxDecoration(
                            color: ArgonColors.white,
                            border: Border(
                              bottom: BorderSide(
                                width: 0.5,
                                color: ArgonColors.muted,
                              ),
                            ),
                          ),
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.spaceAround,
                            children: [
                              const Center(
                                child: Padding(
                                  padding: EdgeInsets.only(top: 8),
                                  child: Text(
                                    'Sign up with',
                                    style: TextStyle(
                                      color: ArgonColors.text,
                                      fontSize: 16,
                                    ),
                                  ),
                                ),
                              ),
                              Padding(
                                padding: const EdgeInsets.only(bottom: 8),
                                child: Row(
                                  mainAxisAlignment:
                                      MainAxisAlignment.spaceAround,
                                  children: [
                                    SizedBox(
                                      height: 36,
                                      child: ElevatedButton(
                                        style: ElevatedButton.styleFrom(
                                          foregroundColor: ArgonColors.primary,
                                          backgroundColor:
                                              ArgonColors.secondary,
                                          shape: RoundedRectangleBorder(
                                            borderRadius:
                                                BorderRadius.circular(4),
                                          ),
                                        ),
                                        onPressed: _signInWithGoogle,
                                        child: const Padding(
                                          padding: EdgeInsets.only(
                                            bottom: 10,
                                            top: 10,
                                            left: 8,
                                            right: 8,
                                          ),
                                          child: Row(
                                            mainAxisAlignment:
                                                MainAxisAlignment.spaceAround,
                                            children: [
                                              Icon(
                                                FontAwesomeIcons.google,
                                                size: 13,
                                              ),
                                              SizedBox(width: 5),
                                              Text(
                                                'GOOGLE',
                                                style: TextStyle(
                                                  fontWeight: FontWeight.w600,
                                                  fontSize: 13,
                                                ),
                                              ),
                                            ],
                                          ),
                                        ),
                                      ),
                                    ),
                                    SizedBox(
                                      height: 36,
                                      child: ElevatedButton(
                                        style: ElevatedButton.styleFrom(
                                          foregroundColor: ArgonColors.primary,
                                          backgroundColor:
                                              ArgonColors.secondary,
                                          shape: RoundedRectangleBorder(
                                            borderRadius:
                                                BorderRadius.circular(4),
                                          ),
                                        ),
                                        onPressed: _signInWithApple,
                                        child: const Padding(
                                          padding: EdgeInsets.only(
                                            bottom: 10,
                                            top: 10,
                                            left: 8,
                                            right: 8,
                                          ),
                                          child: Row(
                                            mainAxisAlignment:
                                                MainAxisAlignment.spaceAround,
                                            children: [
                                              Icon(
                                                FontAwesomeIcons.apple,
                                                size: 13,
                                              ),
                                              SizedBox(width: 5),
                                              Text(
                                                'APPLE',
                                                style: TextStyle(
                                                  fontWeight: FontWeight.w600,
                                                  fontSize: 13,
                                                ),
                                              ),
                                            ],
                                          ),
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ),
                        ),
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
