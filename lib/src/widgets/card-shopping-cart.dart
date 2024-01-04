import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:flutter/material.dart';

class CardShopingCart extends StatelessWidget {
  const CardShopingCart({
    super.key,
    this.title = 'Placeholder Title',
    this.img = 'https://via.placeholder.com/200',
  });

  final String img;
  final String title;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 230,
      width: 180,
      child: GestureDetector(
        child: Card(
          elevation: 0,
          shape: const RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(8)),
          ),
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: ArgonColors.white,
              borderRadius: const BorderRadius.all(Radius.circular(5)),
              boxShadow: [
                BoxShadow(
                  color: Colors.grey.withOpacity(0.1),
                  spreadRadius: 2,
                  blurRadius: 2,
                  offset: const Offset(0, 2), // changes position of shadow
                ),
              ],
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Flexible(
                  flex: 2,
                  child: Container(
                    decoration: BoxDecoration(
                      borderRadius: const BorderRadius.only(
                        topLeft: Radius.circular(6),
                        topRight: Radius.circular(6),
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
                    padding: const EdgeInsets.only(
                      top: 10,
                      bottom: 8,
                      left: 8,
                      right: 8,
                    ),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          title,
                          style: const TextStyle(
                            color: ArgonColors.black,
                            fontSize: 14,
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
      ),
    );
  }
}
