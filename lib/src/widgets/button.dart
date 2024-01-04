import 'package:flutter/material.dart';

const double defaultBorderRadius = 3;

class StretchableButton extends StatelessWidget {

  const StretchableButton({
    super.key,
    required this.buttonColor,
    required this.borderRadius,
    required this.children,
    this.splashColor = Colors.transparent,
    this.buttonBorderColor,
    this.onPressed,
    this.buttonPadding = 0.0,
  });
  final VoidCallback? onPressed;
  final double borderRadius;
  final double? buttonPadding;
  final Color buttonColor;
  final Color splashColor;
  final Color? buttonBorderColor;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final contents = List<Widget>.from(children);

        if (constraints.minWidth == 0) {
          contents.add(const SizedBox.shrink());
        } else {
          contents.add(const Spacer());
        }

        BorderSide bs;
        if (buttonBorderColor != null) {
          bs = BorderSide(
            color: buttonBorderColor!,
          );
        } else {
          bs = BorderSide.none;
        }

        return ElevatedButton(
          onPressed: onPressed,
          style: ElevatedButton.styleFrom(
            foregroundColor: splashColor,
            backgroundColor: buttonColor,
            padding: EdgeInsets.all(buttonPadding!),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(borderRadius),
              side: bs,
            ),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: contents,
          ),
        );
      },
    );
  }
}
