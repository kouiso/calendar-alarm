import 'package:calendar_alarm/src/constants/Theme.dart';
import 'package:flutter/material.dart';

class TableCellSettings extends StatelessWidget {
  const TableCellSettings({super.key, required this.title, this.onTap});
  final String title;
  final void Function()? onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.only(top: 16),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(title, style: const TextStyle(color: ArgonColors.text)),
            const Padding(
              padding: EdgeInsets.only(right: 8),
              child: Icon(
                Icons.arrow_forward_ios,
                color: ArgonColors.text,
                size: 14,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
