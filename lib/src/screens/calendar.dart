import 'package:flutter/foundation.dart'; // kDebugModeのためにインポート
import 'package:flutter/material.dart';
import 'package:googleapis/calendar/v3.dart' as google_calendar;
import 'package:googleapis_auth/auth_io.dart';
import 'package:table_calendar/table_calendar.dart';
import 'package:url_launcher/url_launcher.dart' as url_launcher;

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

  @override
  void initState() {
    super.initState();
    _calendarFormat = CalendarFormat.month;
    _focusedDay = DateTime.now();
    _selectedDay = DateTime.now();
    _selectedEvents = [];
    _events = {};
    _eventsFuture = _fetchEvents();
  }

  Future<List<google_calendar.Event>> _fetchEvents() async {
    try {
      final client = await _getAuthenticatedClient();
      final api = google_calendar.CalendarApi(client);
      final calendarEvents = await api.events.list('primary');
      return calendarEvents.items ?? [];
    } catch (e) {
      logMessage('Error fetching events: $e');
      return [];
    }
  }

  Future<AutoRefreshingAuthClient> _getAuthenticatedClient() async {
    final clientId = ClientId("", "");
    return clientViaUserConsent(
      clientId,
      [google_calendar.CalendarApi.calendarScope],
      (url) async {
        if (await url_launcher.canLaunchUrl(Uri.parse(url))) {
          await url_launcher.launchUrl(Uri.parse(url));
        } else {
          logMessage('Could not launch $url');
        }
      },
    );
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
          child: FutureBuilder<List<google_calendar.Event>>(
            future: _eventsFuture,
            builder: (context, snapshot) {
              if (snapshot.connectionState == ConnectionState.waiting) {
                return const CircularProgressIndicator();
              } else if (snapshot.hasError) {
                return Center(child: Text('Error: ${snapshot.error}'));
              } else if (snapshot.hasData) {
                for (final event in snapshot.data!) {
                  final eventDate = event.start!.date ?? event.start!.dateTime!;
                  if (_events[eventDate] == null) {
                    _events[eventDate] = [];
                  }
                  _events[eventDate]!.add(event);
                }
                return ListView.builder(
                  itemCount: _selectedEvents.length,
                  itemBuilder: (context, index) {
                    return Card(
                      child: ListTile(
                        title:
                            Text(_selectedEvents[index].summary ?? 'No Title'),
                        subtitle: Text(
                          _selectedEvents[index].start?.dateTime?.toString() ??
                              'No Start Time',
                        ),
                      ),
                    );
                  },
                );
              } else {
                return const Center(child: Text('No Events Found'));
              }
            },
          ),
        ),
      ],
    );
  }
}

void logMessage(String message) {
  if (kDebugMode) {
    print(message);
  }
}
