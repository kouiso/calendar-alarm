// lib/src/services/event_service.dart
import 'package:cloud_firestore/cloud_firestore.dart';

/// イベントサービスの例外を表すクラスです。
class EventServiceException implements Exception {
  /// 例外メッセージを保持します。
  final String message;

  /// コンストラクタで例外メッセージを受け取ります。
  EventServiceException(this.message);
}

/// イベント情報を取得するためのサービスクラスです。
class EventService {
  /// Firestoreのインスタンスを保持します。
  final FirebaseFirestore firestore;

  /// 1ページあたりのアイテム数を定義します。
  final int itemsPerPage = 10;

  /// コンストラクタでFirestoreのインスタンスを受け取ります。
  EventService({required this.firestore});

  /// イベント情報を取得するためのクエリを構築します。
  ///
  /// イベントは開始日時で並び替えられ、ページあたりのアイテム数で制限されます。
  Query buildQuery() {
    return firestore
        .collection('calendarEvents')
        .orderBy('start.dateTime')
        .limit(itemsPerPage);
  }

  /// イベント情報を非同期で取得します。
  ///
  /// 成功した場合は[QuerySnapshot]を返し、失敗した場合は[EventServiceException]をスローします。
  Future<QuerySnapshot> fetchEvents() async {
    try {
      return await buildQuery().get();
    } catch (e) {
      throw EventServiceException('イベントの取得に失敗しました: $e');
    }
  }
}
