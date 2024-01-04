import 'dart:ui' as ui;

import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:calendar_alarm/src/widgets/drawer.dart';
//widgets
import 'package:calendar_alarm/src/widgets/navbar.dart';
import 'package:flutter/material.dart';
import 'package:font_awesome_flutter/font_awesome_flutter.dart';

class Register extends StatefulWidget {
  const Register({super.key});

  @override
  _RegisterState createState() => _RegisterState();
}

class _RegisterState extends State<Register> {
  final double height = ui.window.physicalSize.height;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: const Navbar(transparent: true, title: ''),
      extendBodyBehindAppBar: true,
      drawer: const ArgonDrawer(currentPage: 'Account'),
      body: Stack(
        children: [
          Container(
            decoration: const BoxDecoration(
              image: DecorationImage(
                image: AssetImage('assets/img/register-bg.png'),
                fit: BoxFit.cover,
              ),
            ),
          ),
          SafeArea(
            child: ListView(
              children: [
                Padding(
                  padding: const EdgeInsets.only(
                    top: 16,
                    left: 24,
                    right: 24,
                    bottom: 32,
                  ),
                  child: Card(
                    elevation: 5,
                    clipBehavior: Clip.antiAlias,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(4),
                    ),
                    child: Column(
                      children: [
                        Container(
                          height: MediaQuery.of(context).size.height * 0.15,
                          decoration: const BoxDecoration(
                            color: ArgonColors.white,
                            border: Border(
                              bottom: BorderSide(
                                width: 0.5,
                                color: ArgonColors.muted,
                              ),
                            ),
                          ),
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.spaceAround,
                            children: [
                              const Center(
                                child: Padding(
                                  padding: EdgeInsets.only(top: 8),
                                  child: Text(
                                    'Sign up with',
                                    style: TextStyle(
                                      color: ArgonColors.text,
                                      fontSize: 16,
                                    ),
                                  ),
                                ),
                              ),
                              Padding(
                                padding: const EdgeInsets.only(bottom: 8),
                                child: Row(
                                  mainAxisAlignment:
                                      MainAxisAlignment.spaceAround,
                                  children: [
                                    SizedBox(
                                      height: 36,
                                      child: ElevatedButton(
                                        style: ElevatedButton.styleFrom(
                                          foregroundColor: ArgonColors.primary,
                                          backgroundColor:
                                              ArgonColors.secondary,
                                          shape: RoundedRectangleBorder(
                                            borderRadius:
                                                BorderRadius.circular(4),
                                          ),
                                        ),
                                        onPressed: () {},
                                        child: const Padding(
                                          padding: EdgeInsets.only(
                                            bottom: 10,
                                            top: 10,
                                            left: 8,
                                            right: 8,
                                          ),
                                          child: Row(
                                            mainAxisAlignment:
                                                MainAxisAlignment.spaceAround,
                                            children: [
                                              Icon(
                                                FontAwesomeIcons.facebook,
                                                size: 13,
                                              ),
                                              SizedBox(width: 5),
                                              Text(
                                                'GOOGLE',
                                                style: TextStyle(
                                                  fontWeight: FontWeight.w600,
                                                  fontSize: 13,
                                                ),
                                              ),
                                            ],
                                          ),
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                              // Divider()
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
