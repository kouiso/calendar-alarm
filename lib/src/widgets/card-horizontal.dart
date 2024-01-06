import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:flutter/material.dart';

class CardHorizontal extends StatelessWidget {
  const CardHorizontal({
    super.key,
    this.title = 'Placeholder Title',
    this.cta = '',
    this.img = 'https://via.placeholder.com/200',
    this.tap = defaultFunc,
  });

  final String cta;
  final String img;
  final void Function()? tap;
  final String title;

  static void defaultFunc() {
    print('the function works!');
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 130,
      child: GestureDetector(
        onTap: tap,
        child: Card(
          elevation: 0.6,
          shape: const RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(6)),
          ),
          child: Row(
            children: [
              Flexible(
                child: Container(
                  decoration: BoxDecoration(
                    borderRadius: const BorderRadius.only(
                      topLeft: Radius.circular(6),
                      bottomLeft: Radius.circular(6),
                    ),
                    image: DecorationImage(
                      image: NetworkImage(img),
                      fit: BoxFit.cover,
                    ),
                  ),
                ),
              ),
              Flexible(
                child: Padding(
                  padding: const EdgeInsets.all(8),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: const TextStyle(
                          color: ArgonColors.header,
                          fontSize: 13,
                        ),
                      ),
                      Text(
                        cta,
                        style: const TextStyle(
                          color: ArgonColors.primary,
                          fontSize: 11,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
