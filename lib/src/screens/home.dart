import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:calendar_alarm/src/screens/calendar.dart';
import 'package:calendar_alarm/src/widgets/drawer.dart';
//widgets
import 'package:calendar_alarm/src/widgets/navbar.dart';
import 'package:flutter/material.dart';

class Home extends StatelessWidget {
  const Home({super.key});

  // final GlobalKey _scaffoldKey = new GlobalKey();
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: const Navbar(
        title: 'Home',
        searchBar: true,
        categoryOne: 'Beauty',
        categoryTwo: 'Fashion',
      ),
      backgroundColor: ArgonColors.bgColorScreen,
      drawer: const ArgonDrawer(currentPage: 'Home'),
      body: Container(
        padding: const EdgeInsets.only(left: 24, right: 24),
        child: CalendarScreen(),
      ),
    );
  }
}
