import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:calendar_alarm/src/widgets/drawer-tile.dart';
import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

class ArgonDrawer extends StatelessWidget {
  const ArgonDrawer({super.key, required this.currentPage});
  final String currentPage;

  _launchURL() async {
    const url = 'https://creative-tim.com';
    if (await canLaunch(url)) {
      await launch(url);
    } else {
      throw 'Could not launch $url';
    }
  }

  @override
  Widget build(BuildContext context) {
    return Drawer(
      child: Container(
        color: ArgonColors.white,
        child: Column(
          children: [
            SizedBox(
              height: MediaQuery.of(context).size.height * 0.1,
              width: MediaQuery.of(context).size.width * 0.85,
              child: SafeArea(
                bottom: false,
                child: Align(
                  alignment: Alignment.bottomLeft,
                  child: Padding(
                    padding: const EdgeInsets.only(left: 32),
                    child: Image.asset('assets/img/argon-logo.png'),
                  ),
                ),
              ),
            ),
            Expanded(
              flex: 2,
              child: ListView(
                padding: const EdgeInsets.only(top: 24, left: 16, right: 16),
                children: [
                  DrawerTile(
                    icon: Icons.home,
                    onTap: () {
                      if (currentPage != 'Home') {
                        Navigator.pushReplacementNamed(context, '/home');
                      }
                    },
                    iconColor: ArgonColors.primary,
                    title: 'Home',
                    isSelected: currentPage == 'Home' ? true : false,
                  ),
                  DrawerTile(
                    icon: Icons.account_circle,
                    onTap: () {
                      if (currentPage != 'Account') {
                        Navigator.pushReplacementNamed(context, '/account');
                      }
                    },
                    iconColor: ArgonColors.info,
                    title: 'Account',
                    isSelected: currentPage == 'Account' ? true : false,
                  ),
                  DrawerTile(
                    icon: Icons.settings,
                    onTap: () {
                      if (currentPage != 'Settings') {
                        Navigator.pushReplacementNamed(context, '/settings');
                      }
                    },
                    iconColor: ArgonColors.success,
                    title: 'Settings',
                    isSelected: currentPage == 'Settings' ? true : false,
                  ),
                ],
              ),
            ),
            Expanded(
              child: Container(
                padding: const EdgeInsets.only(left: 8, right: 16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Divider(
                      height: 4,
                      thickness: 0,
                      color: ArgonColors.muted,
                    ),
                    const Padding(
                      padding: EdgeInsets.only(top: 16, left: 16, bottom: 8),
                      child: Text(
                        'DOCUMENTATION',
                        style: TextStyle(
                          color: Color.fromRGBO(0, 0, 0, 0.5),
                          fontSize: 15,
                        ),
                      ),
                    ),
                    DrawerTile(
                      icon: Icons.airplanemode_active,
                      onTap: _launchURL,
                      iconColor: ArgonColors.muted,
                      title: '使い方について',
                      isSelected:
                          currentPage == 'Getting started' ? true : false,
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
