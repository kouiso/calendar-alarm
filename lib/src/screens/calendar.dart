import 'package:flutter/material.dart';
import 'package:googleapis/calendar/v3.dart' as google_calendar;
import 'package:table_calendar/table_calendar.dart';

class CalendarScreen extends StatefulWidget {
  const CalendarScreen({super.key});

  @override
  CalendarScreenState createState() => CalendarScreenState();
}

class CalendarScreenState extends State<CalendarScreen> {
  late Map<DateTime, List<google_calendar.Event>> _events;
  late List<google_calendar.Event> _selectedEvents;
  late CalendarFormat _calendarFormat;
  late DateTime _focusedDay;
  late DateTime _selectedDay;
  late Future<List<google_calendar.Event>> _eventsFuture;
  final ScrollController _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    _calendarFormat = CalendarFormat.month;
    _focusedDay = DateTime.now();
    _selectedDay = DateTime.now();
    _selectedEvents = [];
    _events = _createTestEvents();
    _eventsFuture =
        Future<List<google_calendar.Event>>.value(_events[_focusedDay] ?? []);
    _scrollController.addListener(_scrollListener);
  }

  Map<DateTime, List<google_calendar.Event>> _createTestEvents() {
    final testEvents = <DateTime, List<google_calendar.Event>>{};
    // 今日の日付にテストイベントを設定
    final today = DateTime.now();
    final startOfToday = DateTime(today.year, today.month, today.day);
    final testEvent = google_calendar.Event(
      summary: 'Test Event',
      start: google_calendar.EventDateTime(dateTime: startOfToday),
      end: google_calendar.EventDateTime(
        dateTime: startOfToday.add(const Duration(hours: 1)),
      ),
    );
    testEvents[startOfToday] = [testEvent];
    return testEvents;
  }

  void _scrollListener() {
    if (_scrollController.position.pixels ==
        _scrollController.position.maxScrollExtent) {
      // 追加のイベントを読み込む処理（実装されていません）
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        TableCalendar(
          firstDay: DateTime.utc(2000),
          lastDay: DateTime.utc(2050, 12, 31),
          focusedDay: _focusedDay,
          calendarFormat: _calendarFormat,
          eventLoader: (day) {
            return _events[day] ?? [];
          },
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
            controller: _scrollController,
            itemCount: _selectedEvents.length,
            itemBuilder: (context, index) {
              return Card(
                child: ListTile(
                  title: Text(_selectedEvents[index].summary ?? 'No Title'),
                  subtitle: Text(
                    _selectedEvents[index].start?.dateTime?.toString() ??
                        'No Start Time',
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }
}
