import 'package:firebase_auth/firebase_auth.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:sign_in_with_apple/sign_in_with_apple.dart';

/// AuthenticationServiceは、FirebaseAuthを使用して認証機能を提供するクラスです。
class AuthenticationService {
  final FirebaseAuth _firebaseAuth;

  /// AuthenticationServiceのコンストラクタ。
  ///
  /// [_firebaseAuth]はFirebaseAuthのインスタンスを受け取ります。
  AuthenticationService(this._firebaseAuth);

  /// プロバイダーから認証情報を取得します。
  ///
  /// [provider]はサインインプロバイダーを指定します。
  /// GoogleまたはAppleの認証情報を取得し、それを返します。
  /// サポートされていないプロバイダーが指定された場合、AuthenticationExceptionをスローします。
  Future<AuthCredential> _getCredentialFromProvider(
      SignInProvider provider) async {
    try {
      switch (provider) {
        case SignInProvider.google:
          return await _getGoogleCredential();
        case SignInProvider.apple:
          return await _getAppleCredential();
        default:
          throw AuthenticationException('Provider not supported');
      }
    } on FirebaseAuthException catch (e) {
      throw AuthenticationException(e.message ?? 'An unknown error occurred');
    }
  }

  /// Googleから認証情報を取得します。
  ///
  /// GoogleSignInを使用してユーザー認証を行い、認証情報を返します。
  /// ユーザーが認証を中断した場合、FirebaseAuthExceptionをスローします。
  Future<AuthCredential> _getGoogleCredential() async {
    final GoogleSignInAccount? googleUser = await GoogleSignIn().signIn();
    if (googleUser == null) {
      throw FirebaseAuthException(
        code: 'ERROR_ABORTED_BY_USER',
        message: 'Google sign in aborted by user',
      );
    }
    final GoogleSignInAuthentication googleAuth =
        await googleUser.authentication;
    return GoogleAuthProvider.credential(
      accessToken: googleAuth.accessToken,
      idToken: googleAuth.idToken,
    );
  }

  /// Appleから認証情報を取得します。
  ///
  /// SignInWithAppleを使用してAppleID認証を行い、認証情報を返します。
  /// 必要なスコープはemailとfullNameです。
  Future<AuthCredential> _getAppleCredential() async {
    final AuthorizationCredentialAppleID appleCredential =
        await SignInWithApple.getAppleIDCredential(
      scopes: [
        AppleIDAuthorizationScopes.email,
        AppleIDAuthorizationScopes.fullName,
      ],
    );
    return OAuthProvider(kAppleProviderId).credential(
      idToken: appleCredential.identityToken,
      accessToken: appleCredential.authorizationCode,
    );
  }

  /// 指定されたプロバイダーでサインインします。
  ///
  /// [provider]はサインインプロバイダーを指定します。
  /// 指定されたプロバイダーでユーザー認証を行い、FirebaseAuthにサインインします。
  Future<void> signIn(SignInProvider provider) async {
    final credential = await _getCredentialFromProvider(provider);
    await _firebaseAuth.signInWithCredential(credential);
  }

  /// 指定されたプロバイダーでアカウントをリンクします。
  ///
  /// [provider]はサインインプロバイダーを指定します。
  /// 既にログインしているユーザーに対して、指定されたプロバイダーで認証情報をリンクします。
  Future<void> linkAccount(SignInProvider provider) async {
    await _ensureLoggedIn();
    final credential = await _getCredentialFromProvider(provider);
    await _firebaseAuth.currentUser!.linkWithCredential(credential);
  }

  /// 指定されたプロバイダーIDでアカウントのリンクを解除します。
  ///
  /// [providerId]はリンクを解除するプロバイダーのIDを指定します。
  /// 既にログインしているユーザーから、指定されたプロバイダーの認証情報を解除します。
  Future<void> unlinkAccount(String providerId) async {
    await _ensureLoggedIn();
    await _firebaseAuth.currentUser!.unlink(providerId);
  }

  /// ユーザーがログインしているか確認します。
  ///
  /// ユーザーがログインしていない場合、AuthenticationExceptionをスローします。
  Future<void> _ensureLoggedIn() async {
    if (_firebaseAuth.currentUser == null) {
      throw AuthenticationException('Not logged in');
    }
  }
}

/// 認証時に発生する例外を表すクラスです。
class AuthenticationException implements Exception {
  final String message;
  AuthenticationException(this.message);
}

const String kAppleProviderId = 'apple.com';

/// サインインプロバイダーを表す列挙型です。
enum SignInProvider {
  google,
  apple,
  // 必要に応じて他のプロバイダーを追加
}
