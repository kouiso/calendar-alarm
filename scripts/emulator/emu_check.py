#!/usr/bin/env python3
"""エミュレータ E2E の採取物 (uiautomator dump / dumpsys) を判定する小道具。

サブコマンド:
  stop-node <ui.xml> <pkg> <text>   指定パッケージの text ノード中心座標を "x y" で出す (無ければ exit 1)
  overlay <dumpsys_window.txt> <pkg> 画面上にある pkg の APPLICATION_OVERLAY 窓の数を出す
  hiding-windows <dumpsys_window.txt> 画面上で他アプリのオーバーレイを隠す窓 (HIDE_NON_SYSTEM_OVERLAY_WINDOWS) を出す
  alarm-players <dumpsys_audio.txt> <uid>  uid の USAGE_ALARM で state:started のプレイヤー数を出す
  notif <dumpsys_notification.txt> <pkg> <id>  pkg/id の通知レコード数を出す
  anr-wait <ui.xml> <app_label>...   他アプリの「応答なし」ダイアログの「待機」中心座標を "x y" で出す (無い・自アプリのものなら exit 1)
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


def anr_wait(path, *app_labels):
    """遅い共有ランナーでは Pixel Launcher 等が応答なしになり、全画面のダイアログが停止UIを隠す。
    それを「停止UIが無い」と読まないために、他アプリのものだけ「待機」で閉じる。
    自アプリの応答なしは本物の不具合なので閉じず、そのまま判定に回す。
    ダイアログが複数あっても、タイトルと「待機」は同じダイアログの中のものだけを組にする。
    英語以外の表示でも動くよう、ダイアログの印は文言でなく resource-id (aerr_wait) で見る"""
    # 自アプリの表示名が分からなければ、自アプリのものと見分けられないので何も閉じない
    labels = [label for label in app_labels if label.strip()]
    if not labels:
        return 1
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, FileNotFoundError):
        return 1
    parent = {child: node for node in root.iter() for child in node}
    found = []
    for node in root.iter("node"):
        if node.get("resource-id") != "android:id/aerr_wait":
            continue
        # この「待機」を含む最小の祖先のうち、タイトルを持つもの
        anc, title = parent.get(node), None
        while anc is not None and title is None:
            for t in anc.iter("node"):
                if t.get("resource-id") == "android:id/alertTitle":
                    title = t.get("text") or ""
                    break
            anc = parent.get(anc)
        found.append((title, node.get("bounds", "")))
    if not found:
        return 1
    for title, bounds in found:
        # タイトルが読めない (無い・空)・自アプリの名前を含むダイアログは触らない (本物の不具合を隠さない)
        if title is None or not title.strip() or any(label in title for label in labels):
            continue
        m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", bounds)
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


def hiding_windows(path):
    """dumpsys window の "Hiding System Alert Windows:" 節に載っている窓 (= 今オーバーレイを隠している窓)。"""
    inside = False
    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.rstrip("\n")
        if line.strip() == "Hiding System Alert Windows:":
            inside = True
            continue
        if not inside:
            continue
        m = re.match(r"\s*#\d+ (Window\{.*\}):?$", line)
        if m:
            print(m.group(1))
        elif line.strip() and not line.startswith("    "):
            inside = False
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
    if cmd == "hiding-windows":
        return hiding_windows(*args)
    if cmd == "alarm-players":
        return alarm_players(*args)
    if cmd == "notif":
        return notif(*args)
    if cmd == "anr-wait":
        return anr_wait(*args)
    if cmd == "audit":
        return audit(*args)
    print(f"unknown command: {cmd}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
