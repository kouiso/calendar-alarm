// lib/src/models/sign_in_method.dart
import 'package:calendar_alarm/src/services/authentication-service.dart';

/// `SignInMethod`クラスは、サインイン方法を表します。
///
/// このクラスは、サインイン方法の名前と、そのアクションを保持します。
/// アクションは、`AuthenticationService`を引数に取る非同期関数です。
class SignInMethod {
  /// サインイン方法の名前を表します。
  final String name;

  /// サインインを実行するアクションを表します。
  ///
  /// この関数は、`AuthenticationService`を引数に取り、`Future`を返します。
  final Future Function(AuthenticationService) action;

  /// `SignInMethod`のコンストラクタです。
  ///
  /// [name]はサインイン方法の名前、[action]はサインインを実行するアクションを指定します。
  SignInMethod(this.name, this.action);
}
