// lib/src/services/event_service.dart
import 'package:cloud_firestore/cloud_firestore.dart';

class EventServiceException implements Exception {
  final String message;
  EventServiceException(this.message);
}

class EventService {
  final FirebaseFirestore firestore;
  final int itemsPerPage = 10;

  EventService({required this.firestore});

  Query buildQuery() {
    return firestore
        .collection('calendarEvents')
        .orderBy('start.dateTime')
        .limit(itemsPerPage);
  }

  Future<QuerySnapshot> fetchEvents() async {
    try {
      return await buildQuery().get();
    } catch (e) {
      throw EventServiceException('Failed to fetch events: $e');
    }
  }
}
