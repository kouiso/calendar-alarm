import 'package:carousel_slider/carousel_slider.dart';
import 'package:flutter/material.dart';

class ImageSlider extends StatefulWidget {

  const ImageSlider({
    super.key, // Made Key nullable and optional
    required this.imgArray, // Marked as required for null safety
  });
  final List<String> imgArray;

  @override
  _ImageSliderState createState() => _ImageSliderState();
}

class _ImageSliderState extends State<ImageSlider> {
  int _current = 0;

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        CarouselSlider(
          items: widget.imgArray
              .map((item) => Container(
                  decoration: BoxDecoration(
                      image: DecorationImage(
                          image: NetworkImage(item),
                          fit: BoxFit.cover,
                          alignment: Alignment.topCenter,),),),)
              .toList(),
          options: CarouselOptions(
              height: 700,
              // aspectRatio: 2.0,
              viewportFraction: 1,
              onPageChanged: (index, reason) {
                setState(() {
                  _current = index;
                });
              },),
        ),
        Container(
          alignment: Alignment.bottomCenter,
          padding: const EdgeInsets.only(bottom: 20),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: widget.imgArray.map((url) {
              final index = widget.imgArray.indexOf(url);
              return Container(
                width: 8,
                height: 8,
                margin:
                    const EdgeInsets.symmetric(vertical: 10, horizontal: 2),
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: _current == index
                      ? const Color.fromRGBO(255, 255, 255, 0.9)
                      : const Color.fromRGBO(255, 255, 255, 0.4),
                ),
              );
            }).toList(),
          ),
        ),
      ],
    );
  }
}
