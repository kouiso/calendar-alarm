import 'package:calendar_alarm/src/widgets/drawer.dart';
import 'package:calendar_alarm/src/widgets/navbar.dart';
import 'package:flutter/material.dart';

class PageLayout extends StatelessWidget {
  final String title;
  final Widget bodyContent;
  final bool notShowNavbar; // ナビゲーションバーを表示するかどうかのプロパティ

  const PageLayout({
    Key? key,
    required this.title,
    required this.bodyContent,
    this.notShowNavbar = false, // デフォルトはtrueに設定
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: notShowNavbar
          ? null
          : Navbar(title: title), // showNavbarに基づいてナビゲーションバーを表示または非表示
      drawer: ArgonDrawer(currentPage: title),
      body: bodyContent,
    );
  }
}
