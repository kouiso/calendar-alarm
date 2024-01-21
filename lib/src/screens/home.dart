import 'package:calendar_alarm/src/constants/Words.dart';
import 'package:calendar_alarm/src/layouts/page-layout.dart';
import 'package:calendar_alarm/src/services/event_service.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:flutter/material.dart';
import 'package:table_calendar/table_calendar.dart';

class Home extends StatefulWidget {
  const Home({Key? key}) : super(key: key);

  @override
  _HomeState createState() => _HomeState();
}

class _HomeState extends State<Home> {
  Map<DateTime, List<dynamic>> _events = {};
  List<dynamic> _selectedEvents = [];
  final CalendarFormat _calendarFormat = CalendarFormat.month;
  DateTime _focusedDay = DateTime.now();
  DateTime _selectedDay = DateTime.now();
  bool _hasMoreData = true;
  final EventService _eventService =
      EventService(firestore: FirebaseFirestore.instance);

  late Future<void> _initialFetchEvents;

  @override
  void initState() {
    super.initState();
    _initialFetchEvents = _fetchEventsWithPagination();
  }

  Future<void> _fetchEventsWithPagination() async {
    String? errorMessage;

    if (!_hasMoreData) return;

    try {
      QuerySnapshot snapshot = await _eventService.fetchEvents();

      if (snapshot.docs.isNotEmpty) {
        _updateCalendarEvents(snapshot);
      }

      if (snapshot.docs.length < _eventService.itemsPerPage) {
        setState(() {
          _hasMoreData = false;
        });
      }
    } catch (e) {
      print('Failed to fetch events with pagination: $e');
      errorMessage = 'Failed to fetch events.';
    }

    if (errorMessage != null) {
      WidgetsBinding.instance!.addPostFrameCallback((_) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(errorMessage ?? 'An error occurred'),
          ),
        );
      });
    }
  }

  void _updateCalendarEvents(QuerySnapshot<dynamic> snapshot) {
    for (var doc in snapshot.docs) {
      var event = doc.data() as Map<String, dynamic>;
      DateTime eventDate = (event['start']['dateTime'] as Timestamp).toDate();
      if (!_events.containsKey(eventDate)) {
        _events[eventDate] = [];
      }
      _events[eventDate]?.add(event);
    }
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    return RefreshIndicator(
      onRefresh: _fetchEventsWithPagination,
      child: PageLayout(
        title: Words.appTitle,
        notShowNavbar: false,
        bodyContent: FutureBuilder(
          future: _initialFetchEvents,
          builder: (BuildContext context, AsyncSnapshot snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            } else if (snapshot.hasError) {
              return Center(
                  child: Text(snapshot.error.toString() ?? 'Error occurred.'));
            } else {
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
                    child: ListView.builder(
                      itemCount: _selectedEvents.length + 1,
                      itemBuilder: (context, index) {
                        if (index == _selectedEvents.length) {
                          if (!_hasMoreData) {
                            return Center(child: Text('No more events'));
                          } else {
                            return Center(child: CircularProgressIndicator());
                          }
                        }

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
                            onTap: () {
                              showDialog(
                                context: context,
                                builder: (context) {
                                  return AlertDialog(
                                    title: Text(event['summary'] ?? 'No Title'),
                                    content: Text(
                                      (event['start']['dateTime'] as Timestamp)
                                              .toDate()
                                              .toString() ??
                                          'No Start Time',
                                    ),
                                  );
                                },
                              );
                            },
                          ),
                        );
                      },
                    ),
                  ),
                ],
              );
            }
          },
        ),
        floatingActionButton: FloatingActionButton(
          onPressed: () {
            // アラーム設定画面を表示する
            showAlarmSettingDialog(context);
          },
          child: Icon(Icons.alarm_add),
        ),
      ),
    );
  }

  void showAlarmSettingDialog(BuildContext context) {
    showDialog(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          title: Text('Set Alarm'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              // 時間を設定するウィジェット
              // 音を選択するウィジェット
              // バイブレーションの有無を選択するウィジェット
              // 音量を設定するスライダー
            ],
          ),
          actions: <Widget>[
            // キャンセルボタン
            // 設定ボタン
          ],
        );
      },
    );
  }
}
