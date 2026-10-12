#!/usr/bin/env python3
"""エミュレータ E2E の採取物 (uiautomator dump / dumpsys) を判定する小道具。

サブコマンド:
  stop-node <ui.xml> <pkg> <text>   指定パッケージの text ノード中心座標を "x y" で出す (無ければ exit 1)
  overlay <dumpsys_window.txt> <pkg> 画面上にある pkg の APPLICATION_OVERLAY 窓の数を出す
  alarm-players <dumpsys_audio.txt> <uid>  uid の USAGE_ALARM で state:started のプレイヤー数を出す
  notif <dumpsys_notification.txt> <pkg> <id>  pkg/id の通知レコード数を出す
  audit <db> [since_millis]          audit_log を時刻順に TSV で出す
"""
import re
import sqlite3
import sys
import xml.etree.ElementTree as ET


def stop_node(path, pkg, text):
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, FileNotFoundError):
        return 1
    for node in root.iter("node"):
        if node.get("package") != pkg:
            continue
        if node.get("text") != text and node.get("content-desc") != text:
            continue
        m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        if x2 <= x1 or y2 <= y1:
            continue
        print(f"{(x1 + x2) // 2} {(y1 + y2) // 2}")
        return 0
    return 1


def _window_blocks(text):
    """dumpsys window windows を "Window #n Window{...}:" 単位に分割する。"""
    blocks, cur = [], None
    for line in text.splitlines():
        if re.match(r"\s*Window #\d+ Window\{", line):
            if cur:
                blocks.append(cur)
            cur = [line]
        elif cur is not None:
            cur.append(line)
    if cur:
        blocks.append(cur)
    return ["\n".join(b) for b in blocks]


def overlay(path, pkg):
    text = open(path, encoding="utf-8", errors="replace").read()
    n = 0
    for block in _window_blocks(text):
        head = block.splitlines()[0]
        if pkg not in head:
            continue
        if "ty=APPLICATION_OVERLAY" not in block:
            continue
        # 窓が存在しても画面に出ていない (キーガードに隠れる等) なら数えない
        if "isOnScreen=false" in block or "isVisible=false" in block:
            continue
        if "mHasSurface=false" in block:
            continue
        n += 1
    print(n)
    return 0


def alarm_players(path, uid):
    n = 0
    for line in open(path, encoding="utf-8", errors="replace"):
        if "AudioPlaybackConfiguration" not in line:
            continue
        if f"u/pid:{uid}/" not in line:
            continue
        if "state:started" in line and "USAGE_ALARM" in line:
            n += 1
    print(n)
    return 0


def notif(path, pkg, nid):
    n = 0
    pat = re.compile(r"NotificationRecord\(.*pkg=" + re.escape(pkg) + r"\b.*\bid=" + re.escape(nid) + r"\b")
    for line in open(path, encoding="utf-8", errors="replace"):
        if pat.search(line):
            n += 1
    print(n)
    return 0


def audit(db, since=0):
    con = sqlite3.connect(db)
    for at, action, detail in con.execute(
        "SELECT atMillis, action, detail FROM audit_log WHERE atMillis >= ? ORDER BY atMillis, id",
        (int(since),),
    ):
        print(f"{at}\t{action}\t{detail}")
    return 0


def main(argv):
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    cmd, args = argv[1], argv[2:]
    if cmd == "stop-node":
        return stop_node(*args)
    if cmd == "overlay":
        return overlay(*args)
    if cmd == "alarm-players":
        return alarm_players(*args)
    if cmd == "notif":
        return notif(*args)
    if cmd == "audit":
        return audit(*args)
    print(f"unknown command: {cmd}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
