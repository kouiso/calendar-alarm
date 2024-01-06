import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:carousel_slider/carousel_slider.dart';
import 'package:flutter/material.dart';

class ProductCarousel extends StatefulWidget {
  const ProductCarousel({
    super.key, // Made Key nullable and optional
    required this.imgArray, // Marked as required for null safety
  });
  final List<Map<String, String>> imgArray;

  @override
  _ProductCarouselState createState() => _ProductCarouselState();
}

class _ProductCarouselState extends State<ProductCarousel> {
  int _current = 0;

  @override
  Widget build(BuildContext context) {
    return CarouselSlider(
      items: widget.imgArray.map((item) {
        return Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(8),
              child: DecoratedBox(
                decoration: const BoxDecoration(
                  boxShadow: [
                    BoxShadow(
                      color: Color.fromRGBO(0, 0, 0, 0.4),
                      blurRadius: 8,
                      spreadRadius: 0.3,
                      offset: Offset(0, 3),
                    ),
                  ],
                ),
                child: AspectRatio(
                  aspectRatio: 2 / 2,
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(4),
                    child: Image.network(
                      item['img'] ?? '', // Handle potential null value
                      fit: BoxFit.cover,
                      alignment: Alignment.topCenter,
                    ),
                  ),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.only(top: 16),
              child: Column(
                children: [
                  Text(
                    item['price'] ?? '', // Handle potential null value
                    style: const TextStyle(
                      fontSize: 16,
                      color: ArgonColors.header,
                    ),
                  ),
                  Text(
                    item['title'] ?? '', // Handle potential null value
                    style: const TextStyle(
                      fontSize: 32,
                      color: ArgonColors.text,
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.only(left: 16, right: 16, top: 8),
                    child: Text(
                      item['description'] ?? '', // Handle potential null value
                      style: const TextStyle(
                        fontSize: 16,
                        color: ArgonColors.muted,
                      ),
                      textAlign: TextAlign.center,
                    ),
                  ),
                ],
              ),
            ),
          ],
        );
      }).toList(),
      options: CarouselOptions(
        height: 530,
        aspectRatio: 4 / 4,
        enableInfiniteScroll: false,
        onPageChanged: (index, reason) {
          setState(() {
            _current = index;
          });
        },
      ),
    );
  }
}
