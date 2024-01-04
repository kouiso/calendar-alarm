import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:flutter/material.dart';

class Input extends StatelessWidget {
  const Input({
    super.key,
    required this.placeholder, // Marked as required
    this.suffixIcon,
    this.prefixIcon,
    this.onTap,
    this.onChanged,
    this.autofocus = false,
    this.borderColor = ArgonColors.border,
    this.controller,
  });
  final String placeholder; // Non-nullable String
  final Widget? suffixIcon;
  final Widget? prefixIcon;
  final void Function()? onTap;
  final void Function(String)? onChanged;
  final TextEditingController? controller;
  final bool autofocus;
  final Color borderColor;

  @override
  Widget build(BuildContext context) {
    return TextField(
      cursorColor: ArgonColors.muted,
      onTap: onTap,
      onChanged: onChanged,
      controller: controller,
      autofocus: autofocus,
      style: const TextStyle(
        height: 0.85,
        fontSize: 14,
        color: ArgonColors.initial,
      ),
      textAlignVertical: const TextAlignVertical(y: 0.6),
      decoration: InputDecoration(
        filled: true,
        fillColor: ArgonColors.white,
        hintStyle: const TextStyle(
          color: ArgonColors.muted,
        ),
        suffixIcon: suffixIcon,
        prefixIcon: prefixIcon,
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(4),
          borderSide: BorderSide(
            color: borderColor,
          ),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(4),
          borderSide: BorderSide(
            color: borderColor,
          ),
        ),
        hintText: placeholder,
      ),
    );
  }
}
