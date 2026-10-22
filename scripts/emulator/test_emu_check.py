#!/usr/bin/env python3
"""emu_check.py anr-wait の試験。CI の実エミュレータで採れた uiautomator dump と同じ形のノードを使う。
実行: python3 scripts/emulator/test_emu_check.py
"""
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


def dump(title=None):
    nodes = ""
    if title:
        nodes = (
            f'<node text="{title}" resource-id="android:id/alertTitle" package="android" bounds="[40,246][280,292]" />'
            '<node text="Wait" resource-id="android:id/aerr_wait" package="android" bounds="[16,355][304,403]" />'
        )
    return f'<?xml version="1.0"?><hierarchy rotation="0">{nodes}<node text="停止" package="com.calendaralarm" bounds="[0,0][10,10]" /></hierarchy>'


def anr_wait(xml, label="Calendar Alarm"):
    with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False) as f:
        f.write(xml)
    try:
        r = subprocess.run([sys.executable, os.path.join(HERE, "emu_check.py"), "anr-wait", f.name, label], capture_output=True, text=True)
        return r.returncode, r.stdout.strip()
    finally:
        os.unlink(f.name)


class AnrWait(unittest.TestCase):
    def test_other_app_dialog_returns_wait_center(self):
        self.assertEqual(anr_wait(dump("Pixel Launcher isn't responding")), (0, "160 379"))

    def test_own_app_dialog_is_not_dismissed(self):
        self.assertEqual(anr_wait(dump("Calendar Alarm isn't responding"))[0], 1)

    def test_no_dialog(self):
        self.assertEqual(anr_wait(dump())[0], 1)

    def test_broken_xml(self):
        self.assertEqual(anr_wait("<hierarchy")[0], 1)


if __name__ == "__main__":
    unittest.main()
