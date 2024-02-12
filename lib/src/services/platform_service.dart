import 'package:flutter/services.dart';

/// `PlatformService`クラスは、ネイティブプラットフォームとの通信を担当します。
class PlatformService {
  /// `_channel`は、Flutterとネイティブプラットフォーム間の通信チャネルを表します。
  static const MethodChannel _channel =
      MethodChannel('com.example.app/channel');

  /// `getGoogleAccounts`メソッドは、デバイスに登録されているGoogleアカウントのリストを非同期で取得します。
  ///
  /// 戻り値:
  ///   - `Future<List<String>>`: Googleアカウントのメールアドレスのリストを非同期で返します。
  static Future<List<String>> getGoogleAccounts() async {
    final List<dynamic> accounts =
        await _channel.invokeMethod('getGoogleAccounts');
    return accounts.cast<String>();
  }

  // PlatformServiceクラス内にメソッドを追加
  static Future<List<Map<String, String>>> fetchCalendarEvents() async {
    final List<dynamic> events =
        await _channel.invokeMethod('fetchCalendarEvents');
    return events.cast<Map<String, String>>();
  }
}
