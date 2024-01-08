import 'package:firebase_auth/firebase_auth.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:sign_in_with_apple/sign_in_with_apple.dart';

class AuthenticationService {
  final FirebaseAuth _firebaseAuth;

  AuthenticationService(this._firebaseAuth);

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

  Future<AuthCredential> _getAppleCredential() async {
    final AuthorizationCredentialAppleID appleCredential =
        await SignInWithApple.getAppleIDCredential(
      scopes: [
        AppleIDAuthorizationScopes.email,
        AppleIDAuthorizationScopes.fullName,
      ],
    );

    return OAuthProvider("apple.com").credential(
      idToken: appleCredential.identityToken,
      accessToken: appleCredential.authorizationCode,
    );
  }

  Future<void> signInWithGoogle() async {
    try {
      final credential = await _getGoogleCredential();
      await _firebaseAuth.signInWithCredential(credential);
    } on FirebaseAuthException catch (e) {
      throw e;
    }
  }

  Future<void> signInWithApple() async {
    try {
      final credential = await _getAppleCredential();
      await _firebaseAuth.signInWithCredential(credential);
    } on FirebaseAuthException catch (e) {
      throw e;
    }
  }

  Future<void> linkGoogleAccount() async {
    try {
      final credential = await _getGoogleCredential();
      await _firebaseAuth.currentUser!.linkWithCredential(credential);
    } on FirebaseAuthException catch (e) {
      throw e;
    }
  }

  Future<void> linkAppleAccount() async {
    try {
      final credential = await _getAppleCredential();
      await _firebaseAuth.currentUser!.linkWithCredential(credential);
    } on FirebaseAuthException catch (e) {
      throw e;
    }
  }

  Future<void> unlinkAccount(String providerId) async {
    try {
      await _firebaseAuth.currentUser!.unlink(providerId);
    } catch (e) {
      throw e;
    }
  }
}
