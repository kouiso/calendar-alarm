import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:calendar_alarm/src/widgets/drawer.dart';
//widgets
import 'package:calendar_alarm/src/widgets/navbar.dart';
import 'package:flutter/material.dart';

class Profile extends StatelessWidget {
  const Profile({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      extendBodyBehindAppBar: true,
      appBar: const Navbar(
        title: 'Profile',
        transparent: true,
      ),
      backgroundColor: ArgonColors.bgColorScreen,
      drawer: const ArgonDrawer(currentPage: 'Profile'),
      body: Stack(
        children: <Widget>[
          Container(
            decoration: const BoxDecoration(
              image: DecorationImage(
                alignment: Alignment.topCenter,
                image: AssetImage('assets/img/profile-screen-bg.png'),
                fit: BoxFit.fitWidth,
              ),
            ),
          ),
          SafeArea(
            child: ListView(
              children: [
                Padding(
                  padding: const EdgeInsets.only(left: 16, right: 16, top: 74),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Stack(
                        children: <Widget>[
                          DecoratedBox(
                            decoration: BoxDecoration(
                              boxShadow: [
                                BoxShadow(
                                  color: Colors.grey.withOpacity(0.1),
                                  spreadRadius: 1,
                                  blurRadius: 7,
                                  offset: const Offset(
                                    0,
                                    3,
                                  ), // changes position of shadow
                                ),
                              ],
                            ),
                            child: Card(
                              clipBehavior: Clip.antiAliasWithSaveLayer,
                              elevation: 0,
                              shape: const RoundedRectangleBorder(
                                borderRadius:
                                    BorderRadius.all(Radius.circular(5)),
                              ),
                              child: Padding(
                                padding: const EdgeInsets.only(
                                  top: 85,
                                  bottom: 20,
                                ),
                                child: Row(
                                  children: [
                                    Expanded(
                                      child: Column(
                                        children: [
                                          Row(
                                            mainAxisAlignment:
                                                MainAxisAlignment.center,
                                            children: [
                                              Container(
                                                decoration: BoxDecoration(
                                                  color: ArgonColors.info,
                                                  borderRadius:
                                                      BorderRadius.circular(3),
                                                  boxShadow: [
                                                    BoxShadow(
                                                      color: Colors.grey
                                                          .withOpacity(0.3),
                                                      spreadRadius: 1,
                                                      blurRadius: 7,
                                                      offset: const Offset(
                                                        0,
                                                        3,
                                                      ), // changes position of shadow
                                                    ),
                                                  ],
                                                ),
                                                padding:
                                                    const EdgeInsets.symmetric(
                                                  horizontal: 8,
                                                  vertical: 8,
                                                ),
                                                child: const Text(
                                                  'CONNECT',
                                                  style: TextStyle(
                                                    color: ArgonColors.white,
                                                    fontSize: 12,
                                                    fontWeight: FontWeight.bold,
                                                  ),
                                                ),
                                              ),
                                              const SizedBox(
                                                width: 30,
                                              ),
                                              Container(
                                                decoration: BoxDecoration(
                                                  color: ArgonColors.initial,
                                                  borderRadius:
                                                      BorderRadius.circular(3),
                                                  boxShadow: [
                                                    BoxShadow(
                                                      color: Colors.grey
                                                          .withOpacity(0.3),
                                                      spreadRadius: 1,
                                                      blurRadius: 7,
                                                      offset: const Offset(
                                                        0,
                                                        3,
                                                      ), // changes position of shadow
                                                    ),
                                                  ],
                                                ),
                                                padding:
                                                    const EdgeInsets.symmetric(
                                                  horizontal: 8,
                                                  vertical: 8,
                                                ),
                                                child: const Text(
                                                  'MESSAGE',
                                                  style: TextStyle(
                                                    color: ArgonColors.white,
                                                    fontSize: 12,
                                                    fontWeight: FontWeight.bold,
                                                  ),
                                                ),
                                              ),
                                            ],
                                          ),
                                          const SizedBox(height: 40),
                                          const Row(
                                            mainAxisAlignment:
                                                MainAxisAlignment.spaceAround,
                                            children: [
                                              Column(
                                                children: [
                                                  Text(
                                                    '2K',
                                                    style: TextStyle(
                                                      color: Color.fromRGBO(
                                                        82,
                                                        95,
                                                        127,
                                                        1,
                                                      ),
                                                      fontSize: 20,
                                                      fontWeight:
                                                          FontWeight.bold,
                                                    ),
                                                  ),
                                                  Text(
                                                    'Orders',
                                                    style: TextStyle(
                                                      color: Color.fromRGBO(
                                                        50,
                                                        50,
                                                        93,
                                                        1,
                                                      ),
                                                      fontSize: 12,
                                                    ),
                                                  ),
                                                ],
                                              ),
                                              Column(
                                                children: [
                                                  Text(
                                                    '10',
                                                    style: TextStyle(
                                                      color: Color.fromRGBO(
                                                        82,
                                                        95,
                                                        127,
                                                        1,
                                                      ),
                                                      fontSize: 20,
                                                      fontWeight:
                                                          FontWeight.bold,
                                                    ),
                                                  ),
                                                  Text(
                                                    'Photos',
                                                    style: TextStyle(
                                                      color: Color.fromRGBO(
                                                        50,
                                                        50,
                                                        93,
                                                        1,
                                                      ),
                                                      fontSize: 12,
                                                    ),
                                                  ),
                                                ],
                                              ),
                                              Column(
                                                children: [
                                                  Text(
                                                    '89',
                                                    style: TextStyle(
                                                      color: Color.fromRGBO(
                                                        82,
                                                        95,
                                                        127,
                                                        1,
                                                      ),
                                                      fontSize: 20,
                                                      fontWeight:
                                                          FontWeight.bold,
                                                    ),
                                                  ),
                                                  Text(
                                                    'Comments',
                                                    style: TextStyle(
                                                      color: Color.fromRGBO(
                                                        50,
                                                        50,
                                                        93,
                                                        1,
                                                      ),
                                                      fontSize: 12,
                                                    ),
                                                  ),
                                                ],
                                              ),
                                            ],
                                          ),
                                          const SizedBox(height: 40),
                                          const Align(
                                            child: Text(
                                              'Jessica Jones, 27',
                                              style: TextStyle(
                                                color: Color.fromRGBO(
                                                  50,
                                                  50,
                                                  93,
                                                  1,
                                                ),
                                                fontSize: 28,
                                              ),
                                            ),
                                          ),
                                          const SizedBox(height: 10),
                                          const Align(
                                            child: Text(
                                              'San Francisco, USA',
                                              style: TextStyle(
                                                color: Color.fromRGBO(
                                                  50,
                                                  50,
                                                  93,
                                                  1,
                                                ),
                                                fontSize: 18,
                                                fontWeight: FontWeight.w200,
                                              ),
                                            ),
                                          ),
                                          const Divider(
                                            height: 40,
                                            thickness: 1.5,
                                            indent: 32,
                                            endIndent: 32,
                                          ),
                                          const Padding(
                                            padding: EdgeInsets.only(
                                              left: 32,
                                              right: 32,
                                            ),
                                            child: Align(
                                              child: Text(
                                                'An artist of considerable range, Jessica name taken by Melbourne...',
                                                textAlign: TextAlign.center,
                                                style: TextStyle(
                                                  color: Color.fromRGBO(
                                                    82,
                                                    95,
                                                    127,
                                                    1,
                                                  ),
                                                  fontSize: 17,
                                                  fontWeight: FontWeight.w200,
                                                ),
                                              ),
                                            ),
                                          ),
                                          const SizedBox(height: 15),
                                          const Align(
                                            child: Text(
                                              'Show more',
                                              style: TextStyle(
                                                color: ArgonColors.primary,
                                                fontWeight: FontWeight.w400,
                                                fontSize: 16,
                                              ),
                                            ),
                                          ),
                                          const SizedBox(height: 25),
                                          const Padding(
                                            padding: EdgeInsets.only(
                                              right: 25,
                                              left: 25,
                                            ),
                                            child: Row(
                                              mainAxisAlignment:
                                                  MainAxisAlignment
                                                      .spaceBetween,
                                              children: [
                                                Text(
                                                  'Album',
                                                  style: TextStyle(
                                                    fontWeight: FontWeight.bold,
                                                    fontSize: 16,
                                                    color: ArgonColors.text,
                                                  ),
                                                ),
                                                Text(
                                                  'View All',
                                                  style: TextStyle(
                                                    color: ArgonColors.primary,
                                                    fontSize: 13,
                                                    fontWeight: FontWeight.w600,
                                                  ),
                                                ),
                                              ],
                                            ),
                                          ),
                                          SizedBox(
                                            height: 250,
                                            child: GridView.count(
                                              primary: false,
                                              padding:
                                                  const EdgeInsets.symmetric(
                                                horizontal: 24,
                                                vertical: 15,
                                              ),
                                              crossAxisSpacing: 10,
                                              mainAxisSpacing: 10,
                                              crossAxisCount: 3,
                                              children: <Widget>[
                                                Container(
                                                  height: 100,
                                                  decoration:
                                                      const BoxDecoration(
                                                    borderRadius:
                                                        BorderRadius.all(
                                                      Radius.circular(
                                                        6,
                                                      ),
                                                    ),
                                                    image: DecorationImage(
                                                      image: NetworkImage(
                                                        'https://images.unsplash.com/photo-1501601983405-7c7cabaa1581?fit=crop&w=240&q=80',
                                                      ),
                                                      fit: BoxFit.cover,
                                                    ),
                                                  ),
                                                ),
                                                Container(
                                                  decoration:
                                                      const BoxDecoration(
                                                    borderRadius:
                                                        BorderRadius.all(
                                                      Radius.circular(6),
                                                    ),
                                                    image: DecorationImage(
                                                      image: NetworkImage(
                                                        'https://images.unsplash.com/photo-1543747579-795b9c2c3ada?fit=crop&w=240&q=80hoto-1501601983405-7c7cabaa1581?fit=crop&w=240&q=80',
                                                      ),
                                                      fit: BoxFit.cover,
                                                    ),
                                                  ),
                                                ),
                                                Container(
                                                  decoration:
                                                      const BoxDecoration(
                                                    borderRadius:
                                                        BorderRadius.all(
                                                      Radius.circular(6),
                                                    ),
                                                    image: DecorationImage(
                                                      image: NetworkImage(
                                                        'https://images.unsplash.com/photo-1551798507-629020c81463?fit=crop&w=240&q=80',
                                                      ),
                                                      fit: BoxFit.cover,
                                                    ),
                                                  ),
                                                ),
                                                Container(
                                                  decoration:
                                                      const BoxDecoration(
                                                    borderRadius:
                                                        BorderRadius.all(
                                                      Radius.circular(6),
                                                    ),
                                                    image: DecorationImage(
                                                      image: NetworkImage(
                                                        'https://images.unsplash.com/photo-1470225620780-dba8ba36b745?fit=crop&w=240&q=80',
                                                      ),
                                                      fit: BoxFit.cover,
                                                    ),
                                                  ),
                                                ),
                                                Container(
                                                  decoration:
                                                      const BoxDecoration(
                                                    borderRadius:
                                                        BorderRadius.all(
                                                      Radius.circular(6),
                                                    ),
                                                    image: DecorationImage(
                                                      image: NetworkImage(
                                                        'https://images.unsplash.com/photo-1503642551022-c011aafb3c88?fit=crop&w=240&q=80',
                                                      ),
                                                      fit: BoxFit.cover,
                                                    ),
                                                  ),
                                                ),
                                                Container(
                                                  decoration:
                                                      const BoxDecoration(
                                                    borderRadius:
                                                        BorderRadius.all(
                                                      Radius.circular(6),
                                                    ),
                                                    image: DecorationImage(
                                                      image: NetworkImage(
                                                        'https://images.unsplash.com/photo-1482686115713-0fbcaced6e28?fit=crop&w=240&q=80',
                                                      ),
                                                      fit: BoxFit.cover,
                                                    ),
                                                  ),
                                                ),
                                              ],
                                            ),
                                          ),
                                        ],
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ),
                          const FractionalTranslation(
                            translation: Offset(0, -0.5),
                            child: Align(
                              alignment: FractionalOffset(0.5, 0),
                              child: CircleAvatar(
                                backgroundImage: AssetImage(
                                  'assets/img/profile-screen-avatar.jpg',
                                ),
                                radius: 65,
                                // maxRadius: 200.0,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ],
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
