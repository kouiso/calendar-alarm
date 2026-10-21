"""PR #48 round 4 on the real Pixel, silent: the overlay path (FSI denied) and rows 14/15 with timestamps.
Before anything rings: every volume 0, DND total silence, vibration intensities 0. Restored at the end.
Test build applicationId com.calendaralarm.pr48 (the installed app and its data are untouched)."""
import os
import re
import sqlite3
import subprocess
import sys
import time
from datetime import datetime

sys.path.insert(0, "/Users/kouiso/.claude/jobs/9b132361/tmp/work/cal-fin-meta/r3")
import emu  # noqa: E402
from PIL import Image  # noqa: E402

emu.SERIAL = "2A091FDH300C0J"
OUT, APK = sys.argv[1], sys.argv[2]
ONLY = set(os.environ.get("ONLY", "O,C,E,F").split(","))
P = "com.calendaralarm.pr48"
HARN = f"{P}/com.calendaralarm.emu.EmuHarnessReceiver"
CALC = "com.google.android.calculator"
VIB_KEYS = ("alarm_vibration_intensity", "ring_vibration_intensity", "notification_vibration_intensity",
            "media_vibration_intensity", "haptic_feedback_intensity")
os.makedirs(OUT, exist_ok=True)
log = open(os.path.join(OUT, "run.log"), "w")
results = []


def ts():
    return datetime.now().strftime("%H:%M:%S.%f")[:-3]


def say(*a):
    s = f"[{ts()}] " + " ".join(str(x) for x in a)
    print(s, flush=True)
    log.write(s + "\n")
    log.flush()


def sh(*a):
    return emu.adb("shell", *a)


def vol(st):
    m = re.search(r"volume is (\d+)", sh("cmd", "media_session", "volume", "--stream", str(st), "--get"))
    return int(m.group(1)) if m else None


def ringing():
    return "engine.AlarmService" in sh("dumpsys", "activity", "services", P)


def top():
    m = re.search(r"topResumedActivity=\S+ \S+ (\S+)", sh("dumpsys", "activity", "activities"))
    return m.group(1) if m else "?"


def overlay_windows():
    """dumpsys window windows のうち、テストアプリが出した APPLICATION_OVERLAY の窓"""
    out = sh("dumpsys", "window", "windows")
    hits = []
    for block in re.split(r"\n  Window #\d+ ", out):
        if P in block and re.search(r"ty=APPLICATION_OVERLAY|type=APPLICATION_OVERLAY|TYPE_APPLICATION_OVERLAY", block):
            hits.append(block.splitlines()[0].strip())
    return hits


def app_activities():
    """テストアプリの Activity が履歴に残っているか (RingingActivity が裏で出ていないことの確認)"""
    return sorted(set(re.findall(re.escape(P) + r"/[\w.]+", sh("dumpsys", "activity", "activities"))))


def pull_db():
    d = os.path.join(OUT, "db")
    os.makedirs(d, exist_ok=True)
    for f in ("calendar-alarm.db", "calendar-alarm.db-wal", "calendar-alarm.db-shm"):
        data = subprocess.run(["adb", "-s", emu.SERIAL, "exec-out", "run-as", P, "cat", f"databases/{f}"],
                              capture_output=True).stdout
        path = os.path.join(d, f)
        if data and not data.startswith(b"run-as"):
            open(path, "wb").write(data)
        elif os.path.exists(path):
            os.remove(path)
    return sqlite3.connect(os.path.join(d, "calendar-alarm.db"))


def states(ids):
    c = pull_db()
    return {i: (c.execute("select state from scheduled_instances where id=?", (i,)).fetchone() or ["-"])[0] for i in ids}


def snooze_rows(parent):
    c = pull_db()
    return list(c.execute("select id, state, triggerAtMillis from scheduled_instances where id like ? order by triggerAtMillis",
                          (parent + ":snz%",)))


def audit_since(ms):
    c = pull_db()
    return list(c.execute("select atMillis, action, detail from audit_log where atMillis >= ? order by id", (ms,)))


def dump_audit(since, label):
    say(f"--- audit_log ({label})")
    for at, action, detail in audit_since(since):
        say("   ", datetime.fromtimestamp(at / 1000).strftime("%H:%M:%S.%f")[:-3], action, detail)


def ensure_quiet():
    if ringing():
        say("leftover ring -> harness DISMISS")
        sh("am", "broadcast", "-n", HARN, "-a", "com.calendaralarm.emu.DISMISS")
        for _ in range(10):
            if not ringing():
                break
            time.sleep(1)


def schedule(secs, label, quiet_first=True):
    if quiet_first:
        ensure_quiet()
    out = sh("am", "broadcast", "--include-stopped-packages", "-n", HARN, "-a", "com.calendaralarm.emu.SCHEDULE",
             "--ei", "seconds", str(secs), "--es", "label", label)
    m = re.search(r'result=1, data="([^"]*)"', out)
    say("schedule", label, f"in {secs}s ->", m.group(1) if m else out.strip())
    return m.group(1) if m else None


def wait_ring(timeout=60):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if ringing():
            time.sleep(2)
            say(f"ringing after {time.time() - t0:.1f}s")
            return True
        time.sleep(1)
    say(f"no ring within {timeout}s")
    return False


def wait_quiet(timeout=10):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if not ringing():
            return True
        time.sleep(1)
    return False


def wait_title(label, timeout=60):
    """画面に label のアラーム名が出るまで待つ (どの鳴動を表示しているかを名前で確かめる)"""
    t0 = time.time()
    while time.time() - t0 < timeout:
        if label in emu.texts():
            say(f"screen shows {label} after {time.time() - t0:.1f}s")
            return True
        time.sleep(1)
    say(f"screen did not show {label} within {timeout}s")
    return False


def rec_start(name):
    return subprocess.Popen(["adb", "-s", emu.SERIAL, "shell", "screenrecord", "--time-limit", "170",
                             f"/sdcard/p48-{name}.mp4"])


def rec_stop(p, name):
    sh("pkill", "-INT", "screenrecord")
    time.sleep(2)
    p.wait(timeout=10)
    emu.adb("pull", f"/sdcard/p48-{name}.mp4", f"{OUT}/{name}.mp4")
    sh("rm", f"/sdcard/p48-{name}.mp4")


def slider_node():
    return next((n for n in emu.nodes(emu.dump()) if n["desc"] == "スライドして停止"), None)


def geometry():
    n = slider_node()
    if not n:
        return None
    l, t, r, b = n["bounds"]
    h = b - t
    return {"bounds": n["bounds"], "y": (t + b) // 2, "x_start": l + h // 2, "x_end": r - h // 2, "l": l, "r": r, "h": h}


def slide(fraction, start_on_thumb=True):
    g = geometry()
    if not g:
        say("slider NOT FOUND")
        return False
    if start_on_thumb:
        x0, x1 = g["x_start"], int(g["x_start"] + (g["x_end"] - g["x_start"]) * fraction)
    else:
        x0, x1 = g["x_end"], int(g["r"] - (g["r"] - g["l"]) * 0.4)
    sh("input", "swipe", str(x0), str(g["y"]), str(x1), str(g["y"]), "900")
    say(f"swipe {x0},{g['y']} -> {x1},{g['y']} (slider {g['bounds']}, fraction={fraction}, on_thumb={start_on_thumb})")
    return True


def thumb_at_left(png):
    """丸がトラック左端にあり右端には無いかを画素で確かめる。丸の色は経路で違う
    (オーバーレイは #BDBDFF、鳴動画面はテーマ色) ので、トラック中央の地の色との差で見る"""
    g = geometry()
    img = Image.open(png).convert("RGB")
    y = g["y"] - int(g["h"] * 0.25)
    left = img.getpixel((g["x_start"], y))
    mid = img.getpixel(((g["l"] + g["r"]) // 2, y))
    right = img.getpixel((g["x_end"], y))
    dist = lambda a, b: sum(abs(x - z) for x, z in zip(a, b))
    ok = dist(left, mid) > 120 and dist(right, mid) < 40
    return ok, (g["x_start"], y), {"left": left, "mid": mid, "right": right}


def record(row, ok, note):
    results.append((row, "PASS" if ok else "FAIL", note))
    say(f"ROW {row}: {'PASS' if ok else 'FAIL'} {note}")


def foreground_calculator():
    sh("am", "start", "-W", "-n", f"{CALC}/com.android.calculator2.Calculator")
    time.sleep(2)
    say("foreground:", top())


saved = {st: vol(st) for st in (2, 3, 4, 5)}
saved_zen = sh("settings", "get", "global", "zen_mode").strip()
saved_vib = {k: sh("settings", "get", "system", k).strip() for k in VIB_KEYS}
night = sh("cmd", "uimode", "night").strip()
say("saved volumes", saved, "| zen", saved_zen, "| vibration", saved_vib, "|", night)
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
    sh("cmd", "uimode", "night", "no")
    sh("input", "keyevent", "KEYCODE_WAKEUP")
    sh("svc", "power", "stayon", "true")
    sh("wm", "dismiss-keyguard")
    sh("cmd", "statusbar", "collapse")

    sh("pm", "uninstall", P)
    say(emu.adb("install", "-r", "-d", APK).strip().splitlines()[-1])
    sh("pm", "grant", P, "android.permission.POST_NOTIFICATIONS")
    sh("appops", "set", P, "SCHEDULE_EXACT_ALARM", "allow")
    sh("am", "start", "-W", "-n", f"{P}/com.calendaralarm.MainActivity")
    time.sleep(3)
    for _ in range(4):
        if not emu.tap("^あとで$", wait=1):
            break
    t_begin = int(time.time() * 1000)

    if "O" in ONLY:
        # O: オーバーレイ経路 (7)。FSI を拒否して鳴動画面 (Activity) が出ない状態にし、
        # 他アプリ (電卓) が前面のまま鳴らす。4・5・6 とチップ (+5分) をオーバーレイ上で確かめる
        sh("appops", "set", P, "USE_FULL_SCREEN_INTENT", "deny")
        sh("appops", "set", P, "SYSTEM_ALERT_WINDOW", "allow")
        say("appops:", sh("appops", "get", P, "USE_FULL_SCREEN_INTENT").strip(), "|",
            sh("appops", "get", P, "SYSTEM_ALERT_WINDOW").strip())
        # MainActivity が裏に生きていると、鳴動を検知して RingingActivity を自分で開く
        # (SYSTEM_ALERT_WINDOW 持ちは裏からの起動が通る) ので、オーバーレイ経路を見るため止めておく
        sh("am", "force-stop", P)
        foreground_calculator()
        say("app activities before ring:", app_activities())
        t_o = int(time.time() * 1000)
        r = rec_start("o-overlay-slide")
        schedule(8, "P48-O1")
        ok = wait_ring()
        time.sleep(2)
        wins = overlay_windows()
        t = top()
        texts = emu.texts()
        emu.shot(f"{OUT}/o1-overlay.png")
        say("overlay windows:", wins, "| top:", t, "| texts:", texts)
        acts = app_activities()
        say("app activities while ringing:", acts)
        record("7a", ok and bool(wins) and not any("RingingActivity" in a for a in acts) and "P48-O1" in texts,
               f"APPLICATION_OVERLAY windows={len(wins)}, top={t}")
        record("7-3", not any(x.startswith("+") and x.endswith("分") for x in texts), "chips closed before tap (overlay)")
        emu.tap("スヌーズ時間を選ぶ", wait=1.5)
        texts2 = emu.texts()
        emu.shot(f"{OUT}/o2-overlay-chips-open.png")
        record("7-3b", any(x.startswith("+") and x.endswith("分") for x in texts2),
               f"after tap: {[x for x in texts2 if x.startswith('+')]}")
        emu.tap("スヌーズ時間を選ぶ", wait=1.5)
        slide(0.5)
        time.sleep(3)
        emu.shot(f"{OUT}/o3-overlay-after-half-slide.png")
        left, probe, px = thumb_at_left(f"{OUT}/o3-overlay-after-half-slide.png")
        record("7-4", ringing() and bool(overlay_windows()) and left,
               f"still ringing={ringing()}, thumb back at left={left} (pixel {probe}={px})")
        slide(0, start_on_thumb=False)
        time.sleep(3)
        emu.shot(f"{OUT}/o4-overlay-after-off-thumb-swipe.png")
        record("7-6", ringing() and bool(overlay_windows()), f"swipe not starting on the thumb: still ringing={ringing()}")
        slide(0.97)
        stopped = wait_quiet()
        time.sleep(2)
        emu.shot(f"{OUT}/o5-overlay-after-full-slide.png")
        record("7-5", stopped and not overlay_windows(),
               f"stopped={stopped}, overlay windows after={len(overlay_windows())}, top={top()}")
        rec_stop(r, "o-overlay-slide")
        dump_audit(t_o, "overlay slide")

    if "C" in ONLY:
        # C: オーバーレイのチップ (+5分) で止まり、5分後にオーバーレイでもう一度鳴る
        sh("am", "force-stop", P)
        foreground_calculator()
        say("app activities before ring:", app_activities())
        t_c = int(time.time() * 1000)
        r = rec_start("c-overlay-chip")
        parent = schedule(8, "P48-O2")
        wait_ring()
        time.sleep(1)
        pre = overlay_windows()
        say("overlay windows:", pre, "| top:", top(), "| app activities:", app_activities())
        emu.tap("スヌーズ時間を選ぶ", wait=1.5)
        emu.shot(f"{OUT}/c0-overlay-chips-open.png")
        tapped = time.time()
        emu.tap(r"^\+5分$", wait=1)
        quiet = wait_quiet()
        rows = snooze_rows(parent)
        mins = [round((x[2] / 1000 - tapped) / 60, 1) for x in rows if x[1] == "PENDING"]
        say("after +5 chip quiet=", quiet, "rows", rows, "minutes ahead", mins, "overlay windows", overlay_windows())
        record("7-chip", bool(pre) and quiet and any(4.5 <= m <= 5.5 for m in mins) and not overlay_windows(),
               f"stopped={quiet}, snooze child in {mins} min")
        rec_stop(r, "c-overlay-chip")
        rerang = quiet and wait_ring(timeout=5 * 60 + 60)
        r = rec_start("c-overlay-chip-rering")
        time.sleep(1)
        wins = overlay_windows()
        emu.shot(f"{OUT}/c1-overlay-rering.png")
        record("7-chip-rering", rerang and bool(wins), f"re-rang {int(time.time() - tapped)}s after the chip, overlay windows={len(wins)}")
        slide(0.97)
        record("7-chip-stop", wait_quiet(), "stopped by a slide on the overlay")
        rec_stop(r, "c-overlay-chip-rering")
        dump_audit(t_c, "overlay chip")

    # ここからは鳴動画面 (Activity) の経路に戻す: FSI 許可、オーバーレイ拒否
    sh("appops", "set", P, "USE_FULL_SCREEN_INTENT", "allow")
    sh("appops", "set", P, "SYSTEM_ALERT_WINDOW", "deny")
    sh("am", "start", "-W", "-n", f"{P}/com.calendaralarm.MainActivity")
    time.sleep(2)

    if "E" in ONLY:
        # E: 2件同時 (14)。同じ秒に2件を予約し、1件目を止めた後に2件目が鳴って普通にスライドで止まる
        t_e = int(time.time() * 1000)
        r = rec_start("e-two-at-once")
        e1 = schedule(10, "P48-E1")
        e2 = schedule(10, "P48-E2", quiet_first=False)
        wait_ring()
        texts = emu.texts()
        first = "P48-E2" if "P48-E2" in texts else "P48-E1" if "P48-E1" in texts else "?"
        other = "P48-E1" if first == "P48-E2" else "P48-E2"
        emu.shot(f"{OUT}/e1-first.png")
        say("first on screen:", first, "| states", states([e1, e2]), "| top", top())
        slide(0.97)
        time.sleep(1)
        say("after first slide: states", states([e1, e2]), "| ringing", ringing())
        second = wait_title(other, timeout=90)
        # 画面の切り替え直後は木の取得が空振りすることがあるので、スライダーが取れるまで待つ
        g = None
        for _ in range(6):
            time.sleep(1)
            g = geometry()
            if g:
                break
        emu.shot(f"{OUT}/e2-second.png")
        left, probe, px = (thumb_at_left(f"{OUT}/e2-second.png") if g else (False, None, None))
        say("second screen thumb at left:", left, probe, px, "| states", states([e1, e2]))
        slide(0.97)
        all_quiet = wait_quiet(15)
        st = states([e1, e2])
        record(14, second and left and all_quiet and all(v == "DISMISSED" for v in st.values()),
               f"second ({other}) shown={second}, its thumb at left={left}, all stopped={all_quiet}, states={st}")
        rec_stop(r, "e-two-at-once")
        dump_audit(t_e, "row 14")

    if "F" in ONLY:
        # F: 引いたまま別の鳴動が始まる (15)。丸を 90% まで引いて指を離さず、その間に B が鳴り始め、
        # 画面が B に替わってから離す。見ていなかった B は止まらない
        sh("am", "start", "-W", "-n", f"{P}/com.calendaralarm.MainActivity")
        time.sleep(2)
        t_f = int(time.time() * 1000)
        r = rec_start("f-hold-while-switch")
        a = schedule(8, "P48-FA")
        wait_ring()
        wait_title("P48-FA", timeout=10)
        b = schedule(15, "P48-FB", quiet_first=False)
        g = geometry()
        x0, y = g["x_start"], g["y"]
        x1 = int(g["x_start"] + (g["x_end"] - g["x_start"]) * 0.9)
        sh("input", "motionevent", "DOWN", str(x0), str(y))
        for i in range(1, 11):
            sh("input", "motionevent", "MOVE", str(x0 + (x1 - x0) * i // 10), str(y))
            time.sleep(0.05)
        say(f"holding at {x1},{y} (90%) | states", states([a, b]))
        emu.shot(f"{OUT}/f1-holding-on-a.png")
        switched = wait_title("P48-FB", timeout=40)
        time.sleep(2)
        emu.shot(f"{OUT}/f2-switched-to-b-still-holding.png")
        sh("input", "motionevent", "UP", str(x1), str(y))
        say("released | states", states([a, b]))
        time.sleep(4)
        st = states([a, b])
        still = ringing() and "P48-FB" in emu.texts()
        emu.shot(f"{OUT}/f3-after-release.png")
        record(15, switched and still and st[b] != "DISMISSED",
               f"switched to B while holding={switched}, B still ringing on screen={still}, states after release={st}")
        # 後片付け: B をスライドで止め、押し出されていた A も鳴り直したら止める
        slide(0.97)
        back_a = wait_title("P48-FA", timeout=60)
        if back_a:
            slide(0.97)
        wait_quiet(15)
        say("cleanup: A rang again after B=", back_a, "| final states", states([a, b]))
        rec_stop(r, "f-hold-while-switch")
        dump_audit(t_f, "row 15")
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
    say("restored volumes", {st: vol(st) for st in (2, 3, 4, 5)}, "| zen:", sh("settings", "get", "global", "zen_mode").strip(),
        "| vibration:", {k: sh("settings", "get", "system", k).strip() for k in VIB_KEYS})
    say("RESULTS")
    for row in results:
        say("  ", *row)
