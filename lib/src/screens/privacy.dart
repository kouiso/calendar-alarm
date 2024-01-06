import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:calendar_alarm/src/widgets/navbar.dart';
import 'package:flutter/material.dart';

String privacyText1 =
    'In recent months, Facebook, Google, IBM, Microsoft and others have aggressively lobbied officials in the Trump administration and elsewhere to start outlining a federal privacy law...';
String privacyText2 =
    'We are committed to being part of the process and a constructive part of the process...';
String privacyText3 =
    'The efforts could set up a big fight with consumer and privacy groups...';
String privacyText4 =
    'At a board meeting for the Information Technology Industry Council in May, Joel Kaplan...';

class Privacy extends StatelessWidget {
  const Privacy({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: const Navbar(
        title: 'Privacy',
        rightOptions: false,
        backButton: true,
      ),
      backgroundColor: ArgonColors.bgColorScreen,
      body: Stack(
        children: [
          SingleChildScrollView(
            child: Padding(
              padding: const EdgeInsets.only(
                left: 32,
                right: 32,
                top: 24,
                bottom: 100,
              ),
              child: Column(
                children: [
                  Text(
                    privacyText1,
                    style:
                        const TextStyle(fontSize: 16, color: ArgonColors.text),
                  ),
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      privacyText2,
                      style: const TextStyle(
                        fontSize: 16,
                        color: ArgonColors.text,
                      ),
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      privacyText3,
                      style: const TextStyle(
                        fontSize: 16,
                        color: ArgonColors.text,
                      ),
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      privacyText4,
                      style: const TextStyle(
                        fontSize: 16,
                        color: ArgonColors.text,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
          Align(
            alignment: FractionalOffset.bottomCenter,
            child: Container(
              width: double.maxFinite,
              height: MediaQuery.of(context).size.height * 0.15,
              decoration: const BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  stops: [
                    0.1,
                    0.8,
                  ],
                  colors: [
                    Color.fromRGBO(255, 255, 255, 0),
                    Color.fromRGBO(255, 255, 255, 1),
                  ],
                ),
              ),
              child: SafeArea(
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceAround,
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    SizedBox(
                      width: 150,
                      child: ElevatedButton(
                        style: ElevatedButton.styleFrom(
                          foregroundColor: ArgonColors.white,
                          backgroundColor: ArgonColors
                              .primary, // Text Color (Foreground color)
                        ),
                        onPressed: () {
                          // Respond to button press
                        },
                        child: const Text(
                          'ACCEPT',
                          style: TextStyle(
                            fontWeight: FontWeight.w600,
                            fontSize: 16,
                          ),
                        ),
                      ),
                    ),
                    SizedBox(
                      width: 150,
                      child: ElevatedButton(
                        style: ElevatedButton.styleFrom(
                          foregroundColor: ArgonColors.muted,
                          backgroundColor: ArgonColors
                              .secondary, // Text Color (Foreground color)
                        ),
                        onPressed: () {
                          // Respond to button press
                        },
                        child: const Text(
                          'DECLINE',
                          style: TextStyle(
                            fontWeight: FontWeight.w600,
                            fontSize: 16,
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
