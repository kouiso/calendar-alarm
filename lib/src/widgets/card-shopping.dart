import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:flutter/material.dart';

class CardShopping extends StatelessWidget {
  const CardShopping({
    super.key,
    this.body = 'Placeholder Title',
    this.stock = true,
    this.price = '332',
    this.img = 'https://via.placeholder.com/200',
    this.deleteOnPress,
  });

  final String body;
  final bool stock;
  final String price;
  final String img;
  final void Function()? deleteOnPress;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 135,
      width: double.infinity,
      decoration: BoxDecoration(
        color: ArgonColors.white,
        borderRadius: const BorderRadius.all(Radius.circular(5)),
        boxShadow: [
          BoxShadow(
            color: Colors.grey.withOpacity(0.2),
            spreadRadius: 2,
            blurRadius: 5,
          ),
        ],
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceAround,
          children: [
            // Other widgets...
            Expanded(
              flex: 2,
              child: Padding(
                padding: const EdgeInsets.only(right: 8),
                child: Column(
                  // Other widgets...
                  children: [
                    // Other widgets...
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        ElevatedButton(
                          style: ElevatedButton.styleFrom(
                            backgroundColor: ArgonColors.initial,
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(4),
                            ),
                          ),
                          onPressed: () {
                            deleteOnPress?.call();
                          },
                          child: const Padding(
                            padding: EdgeInsets.only(
                              left: 5,
                              right: 5,
                              top: 12,
                              bottom: 12,
                            ),
                            child: Text(
                              'DELETE',
                              style: TextStyle(
                                fontWeight: FontWeight.w600,
                                fontSize: 11,
                              ),
                            ),
                          ),
                        ),
                        ElevatedButton(
                          style: ElevatedButton.styleFrom(
                            backgroundColor: ArgonColors.initial,
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(4),
                            ),
                          ),
                          onPressed: () {},
                          child: const Padding(
                            padding: EdgeInsets.only(
                              left: 5,
                              right: 5,
                              top: 12,
                              bottom: 12,
                            ),
                            child: Text(
                              'SAVE FOR LATER',
                              style: TextStyle(
                                fontWeight: FontWeight.w700,
                                fontSize: 11,
                              ),
                            ),
                          ),
                        ),
                      ],
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

class Dropdown extends StatefulWidget {
  const Dropdown({super.key});

  @override
  _DropdownState createState() => _DropdownState();
}

class _DropdownState extends State<Dropdown> {
  String dropdownValue = '1';

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 14,
      child: DropdownButtonHideUnderline(
        child: Theme(
          data: Theme.of(context).copyWith(
            canvasColor: ArgonColors.initial,
          ),
          child: DropdownButton<String>(
            value: dropdownValue,
            icon: const Padding(
              padding: EdgeInsets.only(left: 25),
              child: Icon(
                Icons.keyboard_arrow_down,
                color: ArgonColors.white,
              ),
            ),
            iconSize: 20,
            elevation: 1,
            style: const TextStyle(color: ArgonColors.white),
            onChanged: (String? newValue) {
              setState(() {
                dropdownValue = newValue!;
              });
            },
            items: <String>['1', '2', '3', '4']
                .map<DropdownMenuItem<String>>((String value) {
              return DropdownMenuItem<String>(
                value: value,
                child: Text(
                  value,
                  style: const TextStyle(
                    color: ArgonColors.white,
                    fontWeight: FontWeight.w600,
                    fontSize: 12,
                  ),
                ),
              );
            }).toList(),
          ),
        ),
      ),
    );
  }
}
