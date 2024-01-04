import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:flutter/material.dart';

class CardCategory extends StatelessWidget {
  const CardCategory({
    super.key,
    this.title = 'Placeholder Title',
    this.img = 'https://via.placeholder.com/250',
    this.tap, // Functionの代わりにvoid Function()?を使用
  });

  final String img;
  final void Function()? tap; // 型をvoid Function()?に変更
  final String title;

  static void defaultFunc() {
    print('the function works!');
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 252,
      child: GestureDetector(
        onTap: tap, // ここでtapを使用
        child: Card(
          elevation: 0.4,
          shape: const RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(6)),
          ),
          child: Stack(
            children: [
              Container(
                decoration: BoxDecoration(
                  borderRadius: const BorderRadius.all(Radius.circular(6)),
                  image: DecorationImage(
                    image: NetworkImage(img),
                    fit: BoxFit.cover,
                  ),
                ),
              ),
              Container(
                decoration: const BoxDecoration(
                  color: Colors.black45,
                  borderRadius: BorderRadius.all(Radius.circular(6)),
                ),
              ),
              Center(
                child: Text(
                  title,
                  style: const TextStyle(
                    color: ArgonColors.white,
                    fontWeight: FontWeight.w600,
                    fontSize: 18,
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
