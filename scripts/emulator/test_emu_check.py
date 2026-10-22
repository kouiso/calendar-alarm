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


def dialog(title, bounds="[16,355][304,403]", wrap=True):
    inner = (
        f'<node text="{title}" resource-id="android:id/alertTitle" package="android" bounds="[40,246][280,292]" />'
        f'<node text="Wait" resource-id="android:id/aerr_wait" package="android" bounds="{bounds}" />'
    )
    return f'<node class="android.widget.FrameLayout" package="android">{inner}</node>' if wrap else inner


def wrap_xml(*nodes):
    return '<?xml version="1.0"?><hierarchy rotation="0">' + "".join(nodes) + "</hierarchy>"


def anr_wait(xml, *labels):
    labels = labels or ("Calendar Alarm",)
    with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False) as f:
        f.write(xml)
    try:
        r = subprocess.run([sys.executable, os.path.join(HERE, "emu_check.py"), "anr-wait", f.name, *labels], capture_output=True, text=True)
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

    def test_two_dialogs_are_paired_per_dialog(self):
        # 他アプリのタイトルと自アプリの「待機」を取り違えて、自アプリの方を押さない
        xml = wrap_xml(dialog("Pixel Launcher isn't responding", "[0,0][10,10]"), dialog("Calendar Alarm isn't responding", "[100,100][200,200]"))
        self.assertEqual(anr_wait(xml), (0, "5 5"))
        only_own = wrap_xml(dialog("Calendar Alarm isn't responding"), dialog("Calendar Alarm isn't responding", "[1,1][3,3]"))
        self.assertEqual(anr_wait(only_own)[0], 1)

    def test_localized_title_of_other_app_is_dismissed(self):
        self.assertEqual(anr_wait(wrap_xml(dialog("Pixel Launcher は応答していません")))[0], 0)

    def test_localized_title_of_own_app_is_not_dismissed(self):
        self.assertEqual(anr_wait(wrap_xml(dialog("Calendar Alarm は応答していません")))[0], 1)

    def test_empty_bounds_is_ignored(self):
        self.assertEqual(anr_wait(wrap_xml(dialog("Pixel Launcher isn't responding", "")))[0], 1)

    def test_dialog_without_readable_title_is_not_touched(self):
        xml = wrap_xml('<node class="x" package="android"><node text="Wait" resource-id="android:id/aerr_wait" package="android" bounds="[0,0][10,10]" /></node>')
        self.assertEqual(anr_wait(xml)[0], 1)

    def test_empty_title_is_not_touched(self):
        self.assertEqual(anr_wait(wrap_xml(dialog("  ")))[0], 1)

    def test_any_known_own_label_blocks_dismissal(self):
        xml = wrap_xml(dialog("カレンダーアラーム は応答していません"))
        self.assertEqual(anr_wait(xml, "Calendar Alarm", "カレンダーアラーム")[0], 1)
        self.assertEqual(anr_wait(xml, "Calendar Alarm")[0], 0)

    def test_no_known_label_dismisses_nothing(self):
        self.assertEqual(anr_wait(wrap_xml(dialog("Pixel Launcher isn't responding")), "")[0], 1)

    def test_broken_xml(self):
        self.assertEqual(anr_wait("<hierarchy")[0], 1)


if __name__ == "__main__":
    unittest.main()
