// lib/src/models/sign_in_method.dart
import 'package:calendar_alarm/src/services/authentication-service.dart';

class SignInMethod {
  final String name;
  final Future Function(AuthenticationService) action;

  SignInMethod(this.name, this.action);
}
