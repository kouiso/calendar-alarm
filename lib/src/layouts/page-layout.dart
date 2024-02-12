import 'package:calendar_alarm/src/widgets/drawer.dart';
import 'package:calendar_alarm/src/widgets/navbar.dart';
import 'package:flutter/material.dart';

/// `PageLayout`はアプリケーションの基本的なページレイアウトを提供するステートレスウィジェットです。
///
/// このウィジェットは、ナビゲーションバー、ドロワー、および本文のコンテンツを含む`Scaffold`を構築します。
class PageLayout extends StatelessWidget {
  /// ページのタイトルを保持します。
  final String title;

  /// ページの本文コンテンツを保持します。
  final Widget bodyContent;

  /// ナビゲーションバーを表示するかどうかを制御するフラグです。
  ///
  /// デフォルトは`true`で、ナビゲーションバーが表示されます。
  final bool? showNavbar;

  /// `PageLayout`のコンストラクタ。
  ///
  /// [title]はページのタイトル、[bodyContent]はページの本文コンテンツを指定します。
  /// [showNavbar]を`false`に設定すると、ナビゲーションバーが非表示になります。
  const PageLayout({
    Key? key,
    required this.title,
    required this.bodyContent,
    this.showNavbar = true,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: showNavbar ?? true ? Navbar(title: title) : null,
      drawer: ArgonDrawer(currentPage: title),
      body: bodyContent,
    );
  }
}
