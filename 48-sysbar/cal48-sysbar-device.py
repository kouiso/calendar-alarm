"""PR #48: status bar icon colour on the ringing screen, real Pixel 7 Pro, silent.
Usage: cal48-sysbar-device.py <out-dir> <apk>
Everything audible is set to 0 first and restored at the end. Test build applicationId com.calendaralarm.pr48
(the installed app and its data are untouched). Never touches the iPhone."""
import os
import re
import subprocess
import sys
import time

from PIL import Image

SERIAL = "2A091FDH300C0J"
OUT, APK = sys.argv[1], sys.argv[2]
P = "com.calendaralarm.pr48"
HARN = f"{P}/com.calendaralarm.emu.EmuHarnessReceiver"
os.makedirs(OUT, exist_ok=True)
log = open(os.path.join(OUT, "run.log"), "w")


def say(*a):
    s = " ".join(str(x) for x in a)
    print(s, flush=True)
    log.write(s + "\n")
    log.flush()


def adb(*a, check=False):
    r = subprocess.run(["adb", "-s", SERIAL, *a], capture_output=True, text=True)
    if check and r.returncode:
        raise RuntimeError(r.stderr)
    return (r.stdout + r.stderr).strip()


def sh(*a):
    return adb("shell", *a)


def vol(st):
    m = re.search(r"volume is (\d+)", sh("cmd", "media_session", "volume", "--stream", str(st), "--get"))
    return int(m.group(1)) if m else None


def top():
    m = re.search(r"topResumedActivity=\S+ \S+ (\S+)", sh("dumpsys", "activity", "activities"))
    return m.group(1) if m else "?"


def ringing():
    return "engine.AlarmService" in sh("dumpsys", "activity", "services", P)


def shot(path):
    data = subprocess.run(["adb", "-s", SERIAL, "exec-out", "screencap", "-p"], capture_output=True).stdout
    open(path, "wb").write(data)
    return Image.open(path).convert("RGB")


def strip_stats(img, h, w=None):
    """status bar band: how many near-black glyph pixels vs light glyph pixels vs background"""
    w = w or img.width
    black = light = 0
    for y in range(h):
        for x in range(w):
            r, g, b = img.getpixel((x, y))
            lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
            if r < 6 and g < 6 and b < 6:
                black += 1
            elif lum > 150:
                light += 1
    return black, light


streams = {"voice": 0, "system": 1, "ring": 2, "music": 3, "alarm": 4, "notification": 5}
orig = {k: vol(v) for k, v in streams.items()}
orig_zen = sh("settings", "get", "global", "zen_mode")
orig_night = sh("cmd", "uimode", "night")
say("orig volumes", orig, "zen", orig_zen, "|", orig_night)
try:
    for k, v in streams.items():
        sh("cmd", "media_session", "volume", "--stream", str(v), "--set", "0")
    sh("settings", "put", "global", "zen_mode", "2")
    sh("cmd", "uimode", "night", "no")
    say("quiet volumes", {k: vol(v) for k, v in streams.items()}, "zen", sh("settings", "get", "global", "zen_mode"), "|",
        sh("cmd", "uimode", "night"))
    assert all(vol(v) == 0 for k, v in streams.items() if k != "voice"), "volume not zero; abort before ringing"

    say(adb("install", "-r", "-g", APK))
    sh("appops", "set", P, "USE_FULL_SCREEN_INTENT", "allow")
    sh("appops", "set", P, "SCHEDULE_EXACT_ALARM", "allow")
    sh("pm", "grant", P, "android.permission.POST_NOTIFICATIONS")
    sh("input", "keyevent", "KEYCODE_WAKEUP")
    sh("wm", "dismiss-keyguard")
    sh("am", "start", "-n", f"{P}/com.calendaralarm.MainActivity")
    time.sleep(4)
    out = sh("am", "broadcast", "-n", HARN, "-a", "com.calendaralarm.emu.SCHEDULE", "--ei", "seconds", "8", "--es", "label",
             "SysBarCheck")
    say("schedule:", out.replace("\n", " "))
    for _ in range(40):
        if "RingingActivity" in top():
            break
        time.sleep(1)
    time.sleep(2)
    say("top =", top(), "ringing =", ringing(), "night =", sh("cmd", "uimode", "night"))
    img = shot(os.path.join(OUT, "after-ringing-light-mode.png"))
    h = int(0.04 * img.height)  # about the status bar height (24dp) on this phone
    bg = img.getpixel((img.width // 2, 5))
    say(f"screenshot {img.width}x{img.height} bg at top centre={bg}")
    black, light = strip_stats(img, h)
    say(f"AFTER top {h}px x {img.width}px: black_glyph_px={black} light_glyph_px={light}")
    crop = img.crop((0, 0, img.width, 200))
    crop.save(os.path.join(OUT, "after-statusbar-strip.png"))
    ws = sh("dumpsys", "window")
    for line in ws.splitlines():
        if re.search(r"LIGHT_STATUS_BARS|mLightBarController|appearance=|mStatusBarAppearance|requestedVisibleTypes", line, re.I):
            say("  dumpsys window:", line.strip()[:200])
    # stop it
    sh("am", "broadcast", "-n", HARN, "-a", "com.calendaralarm.emu.DISMISS")
    for _ in range(10):
        if not ringing():
            break
        time.sleep(1)
    say("stopped, ringing =", ringing())

    # the same measurement on the PR's earlier screenshot (before the fix), if supplied
    before = os.environ.get("BEFORE_PNG")
    if before:
        b = Image.open(before).convert("RGB")
        bb, bl = strip_stats(b, int(h * b.height / img.height))
        say(f"BEFORE ({before}) top {int(h * b.height / img.height)}px: black_glyph_px={bb} light_glyph_px={bl}")
finally:
    for k, v in streams.items():
        if orig[k] is not None:
            sh("cmd", "media_session", "volume", "--stream", str(v), "--set", str(orig[k]))
    sh("settings", "put", "global", "zen_mode", orig_zen if orig_zen and orig_zen != "null" else "0")
    m = re.search(r"(yes|no|auto)", orig_night)
    sh("cmd", "uimode", "night", m.group(1) if m else "auto")
    say("restored volumes", {k: vol(v) for k, v in streams.items()}, "zen", sh("settings", "get", "global", "zen_mode"), "|",
        sh("cmd", "uimode", "night"))
