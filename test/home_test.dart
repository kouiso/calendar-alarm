import 'package:calendar_alarm/src/services/event_service.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  // EventServiceのbuildQuery関数のテスト
  test('buildQuery should return a Query object', () {
    final firestore = FirebaseFirestore.instance;
    final eventService = EventService(firestore: firestore);
    final query = eventService.buildQuery();
    expect(query, isInstanceOf<Query>());
  });
}
