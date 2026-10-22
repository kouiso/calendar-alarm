"""PR #48 overlay geometry on the real Pixel, silent. Verifies the overlay (FSI denied, calculator in front)
now has an 80dp slide track, 64dp thumb and 80dp snooze (same as RingingActivity and iOS), and that the
slide still behaves (half = returns, off-thumb swipe = nothing, full = stops).
Every volume 0, DND total silence, vibration 0 before anything rings; restored at the end.
Test build applicationId com.calendaralarm.pr48 (installed app and its data untouched)."""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime

from PIL import Image

SER = "2A091FDH300C0J"
OUT, APK = sys.argv[1], sys.argv[2]
P = "com.calendaralarm.pr48"
HARN = f"{P}/com.calendaralarm.emu.EmuHarnessReceiver"
CALC = "com.google.android.calculator"
VIB_KEYS = ("alarm_vibration_intensity", "ring_vibration_intensity", "notification_vibration_intensity",
            "media_vibration_intensity", "haptic_feedback_intensity")
os.makedirs(OUT, exist_ok=True)
log = open(os.path.join(OUT, "run.log"), "w")
results = []


def say(*a):
    s = f"[{datetime.now().strftime('%H:%M:%S.%f')[:-3]}] " + " ".join(str(x) for x in a)
    print(s, flush=True)
    log.write(s + "\n")
    log.flush()


def adb(*a, raw=False):
    r = subprocess.run(["adb", "-s", SER, *a], capture_output=True)
    return r.stdout if raw else r.stdout.decode("utf-8", "replace")


def sh(*a):
    return adb("shell", *a)


def vol(st):
    m = re.search(r"volume is (\d+)", sh("cmd", "media_session", "volume", "--stream", str(st), "--get"))
    return int(m.group(1)) if m else None


def ringing():
    return "engine.AlarmService" in sh("dumpsys", "activity", "services", P)


def overlay_windows():
    out = sh("dumpsys", "window", "windows")
    hits = []
    for block in re.split(r"\n  Window #\d+ ", out):
        if P in block and re.search(r"ty=APPLICATION_OVERLAY|type=APPLICATION_OVERLAY|TYPE_APPLICATION_OVERLAY", block):
            hits.append(block.splitlines()[0].strip())
    return hits


def app_activities():
    return sorted(set(re.findall(re.escape(P) + r"/[\w.]+", sh("dumpsys", "activity", "activities"))))


def nodes():
    sh("uiautomator", "dump", "/sdcard/u48.xml")
    xml = adb("exec-out", "cat", "/sdcard/u48.xml")
    out = []
    for n in ET.fromstring(xml).iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            out.append({"text": n.get("text", ""), "desc": n.get("content-desc", ""),
                        "bounds": tuple(int(x) for x in m.groups())})
    return out


def find(pred):
    return next((n for n in nodes() if pred(n)), None)


def shot(path):
    open(path, "wb").write(adb("exec-out", "screencap", "-p", raw=True))


def record(row, ok, note):
    results.append((row, "PASS" if ok else "FAIL", note))
    say(f"ROW {row}: {'PASS' if ok else 'FAIL'} {note}")


def slider():
    return find(lambda n: n["desc"] == "スライドして停止")


def swipe(x0, y, x1, ms=900):
    sh("input", "swipe", str(x0), str(y), str(x1), str(y), str(ms))
    say(f"swipe {x0},{y} -> {x1},{y}")


def wait_quiet(timeout=10):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if not ringing():
            return True
        time.sleep(1)
    return False


density = int(re.search(r"(\d+)", sh("wm", "density")).group(1))
DP = density / 160.0
saved = {st: vol(st) for st in (2, 3, 4, 5)}
saved_zen = sh("settings", "get", "global", "zen_mode").strip()
saved_vib = {k: sh("settings", "get", "system", k).strip() for k in VIB_KEYS}
night = sh("cmd", "uimode", "night").strip()
say("density", density, "dp=", DP, "| saved volumes", saved, "| zen", saved_zen, "| vib", saved_vib, "|", night)
try:
    for st in (2, 3, 4, 5):
        sh("cmd", "media_session", "volume", "--stream", str(st), "--set", "0")
    sh("cmd", "notification", "set_dnd", "none")
    for k in VIB_KEYS:
        sh("settings", "put", "system", k, "0")
    now_vols = {st: vol(st) for st in (2, 3, 4, 5)}
    zen = sh("settings", "get", "global", "zen_mode").strip()
    vib_now = {k: sh("settings", "get", "system", k).strip() for k in VIB_KEYS}
    say("volumes now", now_vols, "| zen_mode", zen, "| vibration now", vib_now)
    if zen != "2" or any(v not in (0, 1) for v in now_vols.values()) or any(v != "0" for v in vib_now.values()):
        raise SystemExit("not silent; abort before anything rings")
    sh("input", "keyevent", "KEYCODE_WAKEUP")
    sh("svc", "power", "stayon", "true")
    sh("wm", "dismiss-keyguard")
    sh("cmd", "statusbar", "collapse")

    sh("pm", "uninstall", P)
    say(adb("install", "-r", "-d", APK).strip().splitlines()[-1])
    sh("pm", "grant", P, "android.permission.POST_NOTIFICATIONS")
    sh("appops", "set", P, "SCHEDULE_EXACT_ALARM", "allow")
    sh("appops", "set", P, "USE_FULL_SCREEN_INTENT", "deny")
    sh("appops", "set", P, "SYSTEM_ALERT_WINDOW", "allow")
    say("appops:", sh("appops", "get", P, "USE_FULL_SCREEN_INTENT").strip(), "|",
        sh("appops", "get", P, "SYSTEM_ALERT_WINDOW").strip())
    sh("am", "force-stop", P)
    sh("am", "start", "-W", "-n", f"{CALC}/com.android.calculator2.Calculator")
    time.sleep(2)

    out = sh("am", "broadcast", "--include-stopped-packages", "-n", HARN, "-a", "com.calendaralarm.emu.SCHEDULE",
             "--ei", "seconds", "8", "--es", "label", "P48-OV80")
    say("schedule:", out.strip().splitlines()[-1] if out.strip() else out)
    t0 = time.time()
    while time.time() - t0 < 60 and not ringing():
        time.sleep(1)
    time.sleep(3)
    wins = overlay_windows()
    acts = app_activities()
    shot(f"{OUT}/o1-overlay.png")
    record("overlay-path", ringing() and bool(wins) and not any("RingingActivity" in a for a in acts),
           f"ringing={ringing()}, APPLICATION_OVERLAY windows={len(wins)}, app activities={acts}")

    s = slider()
    snz = find(lambda n: n["text"].startswith("スヌーズ ") and n["text"].endswith("分") and "選ぶ" not in n["text"])
    say("slider", s, "| snooze", snz)
    if not s or not snz:
        record("geometry", False, "slider or snooze node not found")
    else:
        l, t, r, b = s["bounds"]
        h_dp = (b - t) / DP
        sh_dp = (snz["bounds"][3] - snz["bounds"][1]) / DP
        record("track-80dp", abs(h_dp - 80) <= 1.0, f"slider height = {b - t}px = {h_dp:.1f}dp (want 80)")
        record("snooze-80dp", abs(sh_dp - 80) <= 1.0, f"snooze height = {snz['bounds'][3] - snz['bounds'][1]}px = {sh_dp:.1f}dp (want 80)")
        img = Image.open(f"{OUT}/o1-overlay.png").convert("RGB")
        yc = (t + b) // 2
        acc = [x for x in range(l, r) if all(abs(c - d) < 12 for c, d in zip(img.getpixel((x, yc - int((b - t) * 0.3))), (0xBD, 0xBD, 0xFF)))
               or all(abs(c - d) < 12 for c, d in zip(img.getpixel((x, yc)), (0xBD, 0xBD, 0xFF)))]
        if acc:
            # 丸の左端〜右端 (■ の部分は地の色になるが、最左と最右の画素で直径を測る)
            w_px = acc[-1] - acc[0] + 1
            pad_px = acc[0] - l
            record("thumb-64dp", abs(w_px / DP - 64) <= 2.0 and abs(pad_px / DP - 8) <= 2.0,
                   f"thumb width = {w_px}px = {w_px / DP:.1f}dp (want 64), left pad = {pad_px}px = {pad_px / DP:.1f}dp (want 8), grab limit=(64+8*3)dp={88 * DP:.0f}px")
        else:
            record("thumb-64dp", False, "no accent pixels found in the track")

        # 掴める幅: 88dp の内側ぎりぎり (84dp) から引き始めても止まる / 外側 (96dp) からは止まらない
        # 先に外側から払う (止まったら以降が続かないため)
        y = (t + b) // 2
        x_out = l + int(96 * DP)
        swipe(x_out, y, r - int(8 * DP))
        time.sleep(3)
        record("outside-grab-96dp", ringing() and bool(overlay_windows()),
               f"swipe from {96}dp (outside 88dp) to the right end: still ringing={ringing()}")
        # 丸の中央から半分
        x_c = l + int((8 + 32) * DP)
        travel = (r - l) - int((64 + 16) * DP)
        swipe(x_c, y, x_c + int(travel * 0.5))
        time.sleep(3)
        shot(f"{OUT}/o2-after-half.png")
        img2 = Image.open(f"{OUT}/o2-after-half.png").convert("RGB")
        px = img2.getpixel((l + int(14 * DP), y))
        record("half-returns", ringing() and bool(overlay_windows()) and all(abs(c - d) < 12 for c, d in zip(px, (0xBD, 0xBD, 0xFF))),
               f"still ringing={ringing()}, thumb back at left pixel={px}")
        # 掴める幅の内側 84dp から全引き → 止まる
        s2 = slider()
        l, t, r, b = s2["bounds"]
        swipe(l + int(84 * DP), (t + b) // 2, r - int(4 * DP))
        stopped = wait_quiet(10)
        time.sleep(2)
        shot(f"{OUT}/o3-after-full.png")
        record("inside-grab-84dp-full-stops", stopped and not overlay_windows(),
               f"swipe from 84dp (inside 88dp) to the right end: stopped={stopped}, overlay windows={len(overlay_windows())}")
finally:
    if ringing():
        sh("am", "broadcast", "-n", HARN, "-a", "com.calendaralarm.emu.DISMISS")
    sh("am", "force-stop", P)
    sh("pm", "uninstall", P)
    for st, v in saved.items():
        if v is not None:
            sh("cmd", "media_session", "volume", "--stream", str(st), "--set", str(v))
    sh("cmd", "notification", "set_dnd", {"0": "off", "1": "priority", "2": "none", "3": "alarms"}.get(saved_zen, "off"))
    for k, v in saved_vib.items():
        if v == "null":
            sh("settings", "delete", "system", k)
        else:
            sh("settings", "put", "system", k, v)
    if night.endswith("yes"):
        sh("cmd", "uimode", "night", "yes")
    sh("svc", "power", "stayon", "false")
    sh("input", "keyevent", "KEYCODE_HOME")
    sh("rm", "/sdcard/u48.xml")
    say("restored volumes", {st: vol(st) for st in (2, 3, 4, 5)}, "| zen:", sh("settings", "get", "global", "zen_mode").strip(),
        "| vibration:", {k: sh("settings", "get", "system", k).strip() for k in VIB_KEYS})
    say("RESULTS")
    for row in results:
        say("  ", *row)
