import 'package:firebase_auth/firebase_auth.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:sign_in_with_apple/sign_in_with_apple.dart';

class AuthenticationService {
  final FirebaseAuth _firebaseAuth;

  AuthenticationService(this._firebaseAuth);

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
    return OAuthProvider(kAppleProviderId).credential(
      idToken: appleCredential.identityToken,
      accessToken: appleCredential.authorizationCode,
    );
  }

  Future<void> signIn(SignInProvider provider) async {
    final credential = await _getCredentialFromProvider(provider);
    await _firebaseAuth.signInWithCredential(credential);
  }

  Future<void> linkAccount(SignInProvider provider) async {
    await _ensureLoggedIn();
    final credential = await _getCredentialFromProvider(provider);
    await _firebaseAuth.currentUser!.linkWithCredential(credential);
  }

  Future<void> unlinkAccount(String providerId) async {
    await _ensureLoggedIn();
    await _firebaseAuth.currentUser!.unlink(providerId);
  }

  Future<void> _ensureLoggedIn() async {
    if (_firebaseAuth.currentUser == null) {
      throw AuthenticationException('Not logged in');
    }
  }
}

class AuthenticationException implements Exception {
  final String message;
  AuthenticationException(this.message);
}

const String kAppleProviderId = 'apple.com';

enum SignInProvider {
  google,
  apple,
  // Add other providers if necessary
}
