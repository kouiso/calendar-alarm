import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:flutter/material.dart';
import 'package:table_calendar/table_calendar.dart';

class CalendarScreen extends StatefulWidget {
  const CalendarScreen({super.key});

  @override
  CalendarScreenState createState() => CalendarScreenState();
}

class CalendarScreenState extends State<CalendarScreen> {
  late Map<DateTime, List<dynamic>> _events;
  late List<dynamic> _selectedEvents;
  late CalendarFormat _calendarFormat;
  late DateTime _focusedDay;
  late DateTime _selectedDay;

  @override
  void initState() {
    super.initState();
    _calendarFormat = CalendarFormat.month;
    _focusedDay = DateTime.now();
    _selectedDay = DateTime.now();
    _selectedEvents = [];
    _events = {};
    _fetchEventsFromFirestore();
  }

  void _fetchEventsFromFirestore() async {
    final eventsCollection =
        FirebaseFirestore.instance.collection('calendarEvents');
    final snapshot = await eventsCollection
        .where('start.dateTime',
            isGreaterThanOrEqualTo:
                DateTime.now().subtract(const Duration(days: 30)))
        .where('start.dateTime',
            isLessThanOrEqualTo: DateTime.now().add(const Duration(days: 30)))
        .get();

    Map<DateTime, List<dynamic>> newEvents = {};
    for (var doc in snapshot.docs) {
      var event = doc.data();
      DateTime eventDate = (event['start']['dateTime'] as Timestamp).toDate();
      if (!newEvents.containsKey(eventDate)) {
        newEvents[eventDate] = [];
      }
      newEvents[eventDate]?.add(event);
    }

    setState(() {
      _events = newEvents;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Flutter Calendar App'),
      ),
      body: Column(
        children: [
          TableCalendar(
            firstDay: DateTime.utc(2000),
            lastDay: DateTime.utc(2050, 12, 31),
            focusedDay: _focusedDay,
            calendarFormat: _calendarFormat,
            onDaySelected: (selectedDay, focusedDay) {
              setState(() {
                _selectedDay = selectedDay;
                _focusedDay = focusedDay;
                _selectedEvents = _events[_selectedDay] ?? [];
              });
            },
            selectedDayPredicate: (day) {
              return isSameDay(_selectedDay, day);
            },
          ),
          Expanded(
            child: ListView.builder(
              itemCount: _selectedEvents.length,
              itemBuilder: (context, index) {
                final event = _selectedEvents[index];
                return Card(
                  child: ListTile(
                    title: Text(event['summary'] ?? 'No Title'),
                    subtitle: Text(
                      (event['start']['dateTime'] as Timestamp)
                              .toDate()
                              .toString() ??
                          'No Start Time',
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
