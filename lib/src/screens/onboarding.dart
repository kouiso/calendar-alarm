import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:flutter/material.dart';

class Onboarding extends StatelessWidget {
  const Onboarding({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: <Widget>[
          Container(
            decoration: const BoxDecoration(
              image: DecorationImage(
                image: AssetImage('assets/img/onboard-background.png'),
                fit: BoxFit.cover,
              ),
            ),
          ),
          Padding(
            padding:
                const EdgeInsets.only(top: 71, left: 32, right: 32, bottom: 16),
            child: SafeArea(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.spaceAround,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisAlignment: MainAxisAlignment.end,
                    children: [
                      Image.asset('assets/img/logo-argon.png', scale: 1),
                      const SizedBox(height: 16),
                      Padding(
                        padding: const EdgeInsets.only(right: 48),
                        child: Text.rich(
                          TextSpan(
                            text: 'Argon Design System',
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 58,
                            ),
                            children: <InlineSpan>[
                              WidgetSpan(
                                child: Container(
                                  padding: const EdgeInsets.all(2),
                                  margin: const EdgeInsets.fromLTRB(
                                    0,
                                    0,
                                    50,
                                    50,
                                  ),
                                  decoration: BoxDecoration(
                                    borderRadius: BorderRadius.circular(4),
                                    color: const Color.fromRGBO(
                                      17,
                                      205,
                                      239,
                                      1,
                                    ),
                                  ),
                                  child: const Padding(
                                    padding: EdgeInsets.only(
                                      left: 4,
                                      right: 4,
                                    ),
                                    child: Text(
                                      'PRO',
                                      style: TextStyle(
                                        color: Colors.white,
                                        fontWeight: FontWeight.w600,
                                        fontSize: 16,
                                      ),
                                    ),
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                  const Text(
                    'Take advantage of all the features and screens made by Creative-Tim, coded on Flutter for both',
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 18,
                      fontWeight: FontWeight.w200,
                    ),
                  ),
                  Row(
                    children: <Widget>[
                      Image.asset('assets/img/logo-ios.png', scale: 2.6),
                      const SizedBox(width: 30),
                      Image.asset('assets/img/logo-android.png', scale: 2.6),
                    ],
                  ),
                  Padding(
                    padding: const EdgeInsets.only(top: 16),
                    child: SizedBox(
                      width: double.infinity,
                      child: ElevatedButton(
                        onPressed: () {
                          // Respond to button press
                          Navigator.pushReplacementNamed(context, '/home');
                        },
                        style: ElevatedButton.styleFrom(
                          foregroundColor: ArgonColors.white,
                          backgroundColor: ArgonColors.info, // Text color
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(4),
                          ),
                        ),
                        child: const Padding(
                          padding: EdgeInsets.only(
                            left: 16,
                            right: 16,
                            top: 12,
                            bottom: 12,
                          ),
                          child: Text(
                            'GET STARTED',
                            style: TextStyle(
                              fontWeight: FontWeight.w600,
                              fontSize: 16,
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
