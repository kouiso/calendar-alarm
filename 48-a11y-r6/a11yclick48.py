"""PR #48 row 13 on the real Pixel 7 Pro, silent: send the accessibility framework's ACTION_CLICK (the call TalkBack's
double-tap ends in) to the real 'スライドして停止' node, on both the Compose ringing screen and the View overlay.
Silence: all volumes 0, DND total silence, vibration intensities 0, TalkBack OFF, nothing speaks.
Test build applicationId com.calendaralarm.pr48 + instrumentation com.calendaralarm.pr48.test (installed app untouched).
Usage: a11yclick48.py <outdir> <app.apk> <test.apk>"""
import os
import re
import sqlite3
import subprocess
import sys
import threading
import time
from datetime import datetime

SER = "2A091FDH300C0J"
OUT, APK, TAPK = sys.argv[1], sys.argv[2], sys.argv[3]
P = "com.calendaralarm.pr48"
CALC = "com.google.android.calculator"
VIB = ("alarm_vibration_intensity", "ring_vibration_intensity", "notification_vibration_intensity",
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


def ringing():
    return "engine.AlarmService" in sh("dumpsys", "activity", "services", P)


def top():
    m = re.search(r"topResumedActivity=\S+ \S+ (\S+)", sh("dumpsys", "activity", "activities"))
    return m.group(1) if m else "?"


def overlay_windows():
    out = sh("dumpsys", "window", "windows")
    return [b.splitlines()[0].strip() for b in re.split(r"\n  Window #\d+ ", out)
            if P in b and re.search(r"APPLICATION_OVERLAY", b)]


def db():
    d = os.path.join(OUT, "db")
    os.makedirs(d, exist_ok=True)
    for f in ("calendar-alarm.db", "calendar-alarm.db-wal", "calendar-alarm.db-shm"):
        data = subprocess.run(["adb", "-s", SER, "exec-out", "run-as", P, "cat", f"databases/{f}"], capture_output=True).stdout
        path = os.path.join(d, f)
        if data and not data.startswith(b"run-as"):
            open(path, "wb").write(data)
        elif os.path.exists(path):
            os.remove(path)
    return sqlite3.connect(os.path.join(d, "calendar-alarm.db"))


def shot(name):
    open(f"{OUT}/{name}.png", "wb").write(adb("exec-out", "screencap", "-p", raw=True))


def one_path(name, overlay):
    say(f"=== path {name} ===")
    sh("am", "force-stop", P)
    label = f"A11Y-{name}"
    if overlay:
        sh("appops", "set", P, "USE_FULL_SCREEN_INTENT", "deny")
        sh("appops", "set", P, "SYSTEM_ALERT_WINDOW", "allow")
        sh("am", "start", "-W", "-n", f"{CALC}/com.android.calculator2.Calculator")
        time.sleep(2)
    else:
        sh("appops", "set", P, "USE_FULL_SCREEN_INTENT", "allow")
        sh("appops", "set", P, "SYSTEM_ALERT_WINDOW", "deny")
    rec = subprocess.Popen(["adb", "-s", SER, "shell", "screenrecord", "--time-limit", "100", f"/sdcard/a11y-{name}.mp4"])
    t_begin = int(time.time() * 1000)
    status_lines = []

    def run_instrument():
        p = subprocess.run(["adb", "-s", SER, "shell", "am", "instrument", "-w", "-r", "-e", "class",
                            "com.calendaralarm.emu.A11yClickTest", "-e", "label", label,
                            f"{P}.test/androidx.test.runner.AndroidJUnitRunner"], capture_output=True)
        status_lines.extend(p.stdout.decode("utf-8", "replace").splitlines())

    th = threading.Thread(target=run_instrument)
    th.start()
    t0 = time.time()
    if not overlay:
        time.sleep(3)
        sh("am", "start", "-n", f"{P}/com.calendaralarm.MainActivity")  # 鳴動を検知して鳴動画面を開く側を前面に
    timeline = []
    shot_before = shot_after = False
    prev = None
    while th.is_alive() and time.time() - t0 < 100:
        r, t, w = ringing(), top(), bool(overlay_windows())
        cur = (r, t, w)
        if cur != prev:
            say(f"t+{time.time() - t0:5.1f}s ringing={r} top={t} overlay_window={w}")
            timeline.append((round(time.time() - t0, 1), r, t, w))
            prev = cur
        time.sleep(0.4)
    th.join(timeout=5)
    sh("pkill", "-INT", "screenrecord")
    time.sleep(2)
    rec.wait(timeout=10)
    adb("pull", f"/sdcard/a11y-{name}.mp4", f"{OUT}/{name}.mp4")
    sh("rm", f"/sdcard/a11y-{name}.mp4")
    for l in status_lines:
        if "INSTRUMENTATION_STATUS: " in l and "numtests" not in l and "stream=" not in l and "id=" not in l and "test=" not in l and "class=" not in l and "current=" not in l:
            say("instrument:", l.replace("INSTRUMENTATION_STATUS: ", ""))
        elif l.startswith("INSTRUMENTATION_RESULT") or l.startswith("OK (") or "FAILURES" in l or "Error" in l:
            say("instrument:", l)
    open(f"{OUT}/{name}-instrument.txt", "w").write("\n".join(status_lines))
    c = db()
    audit = list(c.execute("select atMillis, action, detail from audit_log where atMillis >= ? order by id", (t_begin,)))
    say("--- audit_log")
    for at, action, detail in audit:
        say("   ", datetime.fromtimestamp(at / 1000).strftime("%H:%M:%S.%f")[:-3], action, detail)
    states = list(c.execute("select id, state from scheduled_instances where id like 'tm:%' order by triggerAtMillis desc limit 3"))
    say("instance states (latest 3):", states)
    stop_actions = [a for a in audit if "DISMISS" in a[1].upper() or "STOP" in a[1].upper()]
    said_ok = any("performAction returned true" in l for l in status_lines)
    found = [l for l in status_lines if "FOUND" in l]
    ring_before = any(r for (_, r, _, _) in timeline)
    ring_end = timeline[-1][1] if timeline else None
    stopped_by_click = said_ok and bool(stop_actions)
    record(f"13-{name}", ring_before and stopped_by_click,
           f"ACTION_CLICK performAction={said_ok}; audit stop entries={len(stop_actions)}; found={found[:1]}")


def record(row, ok, note):
    results.append((row, "PASS" if ok else "FAIL", note))
    say(f"ROW {row}: {'PASS' if ok else 'FAIL'} {note}")


saved_vols = {}
saved_zen = None
try:
    saved_zen = sh("settings", "get", "global", "zen_mode").strip()
    saved_vols = {st: re.search(r"volume is (\d+)", sh("cmd", "media_session", "volume", "--stream", str(st), "--get")).group(1)
                  for st in (2, 3, 4, 5)}
    say("before: zen", saved_zen, "| volumes", saved_vols, "| a11y services:", sh("settings", "get", "secure", "enabled_accessibility_services").strip())
    for st in (2, 3, 4, 5):
        sh("cmd", "media_session", "volume", "--stream", str(st), "--set", "0")
    sh("cmd", "notification", "set_dnd", "none")
    for k in VIB:
        sh("settings", "put", "system", k, "0")
    if sh("settings", "get", "global", "zen_mode").strip() != "2":
        raise SystemExit("not silent")
    if "TalkBackService" in sh("settings", "get", "secure", "enabled_accessibility_services"):
        raise SystemExit("TalkBack still on")
    sh("input", "keyevent", "KEYCODE_WAKEUP")
    sh("svc", "power", "stayon", "true")
    sh("pm", "uninstall", P)
    say(adb("install", "-r", "-d", APK).strip().splitlines()[-1])
    say(adb("install", "-r", "-t", TAPK).strip().splitlines()[-1])
    sh("pm", "grant", P, "android.permission.POST_NOTIFICATIONS")
    sh("appops", "set", P, "SCHEDULE_EXACT_ALARM", "allow")
    only = os.environ.get("ONLY", "activity,overlay").split(",")
    if "activity" in only:
        one_path("activity", overlay=False)
    if "overlay" in only:
        one_path("overlay", overlay=True)
finally:
    sh("am", "force-stop", P)
    sh("pm", "uninstall", f"{P}.test")
    sh("pm", "uninstall", P)
    sh("svc", "power", "stayon", "false")
    sh("input", "keyevent", "KEYCODE_HOME")
    for k in VIB:
        sh("settings", "delete", "system", k)
    say("vibration after restore:", {k: sh("settings", "get", "system", k).strip() for k in VIB})
    say("RESULTS")
    for row in results:
        say("  ", *row)
