#!/usr/bin/env bash
# アラーム停止 E2E (エミュレータ)。emulator_verify.yml から呼ばれる。
#
#   run_scenarios.sh <apk> <out_dir> <scenario>...
#     unlocked : ロック解除 + 他アプリ (設定) 前面で鳴動 → 発火12秒後に停止UIがあるか → 「停止」で
#                音 (USAGE_ALARM)・鳴動サービス・鳴動通知が3秒以内に消えるか
#     race     : 鳴動サービス起動を検知した直後に停止 (鳴り始めの一瞬の停止) → 10秒間ゾンビ鳴動が無いか
#     locked   : PIN ロック + 画面OFF で鳴動 → 鳴動画面とオーバーレイが重なっていないか → 停止できるか
#     locked_screen_on : 同上をロック画面点灯中に鳴動させる
#
# 結果: <out_dir>/results.tsv (scenario<TAB>PASS|FAIL<TAB>理由コード<TAB>詳細), summary.md, 採取物一式。
# 1 つでも FAIL なら exit 1。
set -uo pipefail

APK=${1:?apk}
OUT=${2:?out dir}
shift 2
SCENARIOS=("$@")
[ ${#SCENARIOS[@]} -gt 0 ] || SCENARIOS=(unlocked race locked locked_screen_on)

HERE=$(cd "$(dirname "$0")" && pwd)
CHECK=(python3 "$HERE/emu_check.py")
PKG=com.calendaralarm
HARNESS="$PKG/.emu.EmuHarnessReceiver"
STOP_TEXT="停止"
NOTIF_ID=1
PIN=1111

mkdir -p "$OUT"
: > "$OUT/results.tsv"

log() { echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$OUT/run.log" >&2; }
sh_() { adb shell "$@" | tr -d '\r'; }
now_ms() { date +%s%3N; }

# ---------- 採取 ----------

# 自アプリの表示名 (全ロケール)。他アプリの応答なしダイアログと見分けるのに使う。読めなければ空で、何も閉じない
APP_DIR="${APK%%/androidApp/build/*}"
APP_LABELS=()
while IFS= read -r label; do
  [ -n "$label" ] && APP_LABELS+=("$label")
done < <(sed -n 's|.*name="app_name"[^>]*>\([^<]*\)</string>.*|\1|p' "$APP_DIR"/androidApp/src/main/res/values*/strings.xml 2>/dev/null)

ui_dump() {
  local dst=$1 _ dismissed=0 xy
  for _ in 1 2 3 4 5; do
    adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1
    # --windows は全窓 (オーバーレイ・SystemUI を含む) を出す。非対応版なら単一窓で取り直す
    if sh_ uiautomator dump --windows /sdcard/ui.xml 2>&1 | grep -q "dumped to" ||
       sh_ uiautomator dump /sdcard/ui.xml 2>&1 | grep -q "dumped to"; then
      if adb pull /sdcard/ui.xml "$dst" >/dev/null 2>&1; then
        # 他アプリの「応答なし」ダイアログが停止UIを隠していたら閉じて取り直す (最大3回)
        if xy=$("${CHECK[@]}" anr-wait "$dst" "${APP_LABELS[@]}"); then
          if [ "$dismissed" -ge 3 ]; then
            # 閉じても出続けるなら、停止UIが無いのか画面が隠れているのか区別できない。UI_DUMP_FAILED に回す
            log "another app's ANR dialog keeps coming back: $dst"
            rm -f "$dst"
            return 1
          fi
          log "dismissing another app's ANR dialog at $xy: $dst"
          sh_ input tap $xy >/dev/null 2>&1
          dismissed=$((dismissed + 1))
          sleep 2
          continue
        fi
        return 0
      fi
    fi
    sleep 1
  done
  # 空の階層で代用すると「停止UIが無い」と区別できず、陰性対照が誤って成功するため残さない
  log "uiautomator dump failed: $dst"
  rm -f "$dst"
  return 1
}

# capture 後に UI ダンプが取れていなければ UI_DUMP_FAILED で落とす (STOP_UI_MISSING と区別する)
ui_dump_ok() { [ -s "$1" ]; }

capture() {
  local p=$1
  sh_ dumpsys window windows > "$p.window.txt" 2>&1
  sh_ dumpsys activity activities > "$p.activities.txt" 2>&1
  sh_ dumpsys activity services "$PKG" > "$p.services.txt" 2>&1
  sh_ dumpsys notification --noredact > "$p.notification.txt" 2>&1
  sh_ dumpsys audio > "$p.audio.txt" 2>&1
  sh_ dumpsys media_session > "$p.media_session.txt" 2>&1
  ui_dump "$p.ui.xml"
  adb exec-out screencap -p > "$p.png" 2>/dev/null
}

start_recording() {
  local name=$1
  adb shell rm -f "/sdcard/rec_$name.mp4" >/dev/null 2>&1
  adb shell screenrecord --time-limit 175 --bit-rate 4000000 "/sdcard/rec_$name.mp4" >/dev/null 2>&1 &
  REC_PID=$!
}

stop_recording() {
  local name=$1
  adb shell pkill -INT screenrecord >/dev/null 2>&1
  wait "$REC_PID" 2>/dev/null
  sleep 2
  adb pull "/sdcard/rec_$name.mp4" "$OUT/$name.mp4" >/dev/null 2>&1 || log "recording pull failed: $name"
}

# ---------- 状態 ----------

service_alive() { sh_ dumpsys activity services "$PKG" | grep -q "engine.AlarmService"; }

# 1 回の adb 往復で 音・サービス・通知 を同時に見る。"<alarm_players> <service> <notif>" を出す。
# 採取に失敗したら "err err err" を出す (欠損を「全部 0 = 停止済み」と読ませない)
stop_state() {
  local tmp ok=1 f
  tmp=$(mktemp -d)
  adb shell "dumpsys audio > /data/local/tmp/s_audio.txt && dumpsys activity services $PKG > /data/local/tmp/s_svc.txt && dumpsys notification --noredact > /data/local/tmp/s_notif.txt" >/dev/null 2>&1 || ok=0
  adb pull /data/local/tmp/s_audio.txt "$tmp/audio" >/dev/null 2>&1 || ok=0
  adb pull /data/local/tmp/s_svc.txt "$tmp/svc" >/dev/null 2>&1 || ok=0
  adb pull /data/local/tmp/s_notif.txt "$tmp/notif" >/dev/null 2>&1 || ok=0
  for f in audio svc notif; do [ -s "$tmp/$f" ] || ok=0; done
  if [ "$ok" != 1 ]; then
    rm -rf "$tmp"
    echo "err err err"
    return
  fi
  local a s n
  a=$("${CHECK[@]}" alarm-players "$tmp/audio" "$APP_UID")
  if grep -q "engine.AlarmService" "$tmp/svc"; then s=1; else s=0; fi
  n=$("${CHECK[@]}" notif "$tmp/notif" "$PKG" "$NOTIF_ID")
  if [ -n "${STATE_KEEP:-}" ]; then
    cp "$tmp/audio" "$STATE_KEEP.audio.txt"
    cp "$tmp/svc" "$STATE_KEEP.services.txt"
    cp "$tmp/notif" "$STATE_KEEP.notification.txt"
  fi
  rm -rf "$tmp"
  echo "$a $s $n"
}

ringing_resumed() { grep -Eq "(topResumedActivity|ResumedActivity).*engine\.RingingActivity" "$1"; }

top_activity() { grep -Em1 "topResumedActivity|mResumedActivity" "$1" | sed 's/^ *//'; }

# ---------- 操作 ----------

schedule() {
  local secs=$1 label=$2 out
  out=$(sh_ am broadcast --include-stopped-packages -n "$HARNESS" \
    -a com.calendaralarm.emu.SCHEDULE --ei seconds "$secs" --es label "$label")
  log "schedule: $out"
  echo "$out" | sed -n 's/.*result=1, data="\([^"]*\)".*/\1/p'
}

harness_dismiss() {
  sh_ am broadcast --include-stopped-packages -n "$HARNESS" \
    -a com.calendaralarm.emu.DISMISS ${1:+--es id "$1"} >/dev/null
}

wait_fire() {
  local deadline=$(( $(date +%s) + $1 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    service_alive && return 0
    sleep 0.5
  done
  return 1
}

unlock_screen() {
  adb shell input keyevent KEYCODE_WAKEUP
  sleep 1
  adb shell wm dismiss-keyguard >/dev/null 2>&1
  sleep 1
  adb shell input keyevent KEYCODE_MENU >/dev/null 2>&1
}

# 「YouTube 視聴中」の代替となる他アプリ候補 (エミュレータ画像に YouTube は無いことが多い)。
# 設定アプリは不可: HIDE_NON_SYSTEM_OVERLAY_WINDOWS で他アプリのオーバーレイを強制的に隠すため、
# YouTube と違う条件になる。候補は前面化でき、かつオーバーレイを隠さないものだけ採用する
FG_CANDIDATES=(
  com.google.android.youtube
  com.android.chrome
  com.google.android.apps.photos
  com.google.android.deskclock com.android.deskclock
  com.google.android.contacts com.android.contacts
  com.google.android.dialer com.android.dialer
  com.google.android.documentsui com.android.documentsui
)
FG_PKG=

launch_foreground_app() {
  local log=$1 pkg hiding
  for pkg in ${FG_PKG:-} "${FG_CANDIDATES[@]}"; do
    sh_ pm path "$pkg" | grep -q '^package:' || continue
    # 初回起動の通知許可ダイアログ (権限コントローラはオーバーレイを隠す) を出さない
    sh_ pm grant "$pkg" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
    sh_ monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >> "$log" 2>&1
    sleep 6
    sh_ dumpsys activity activities > "$log.activities.txt"
    if ! grep -Em1 "topResumedActivity" "$log.activities.txt" | grep -q " $pkg/"; then
      echo "$pkg: not resumed ($(top_activity "$log.activities.txt"))" >> "$log"
      continue
    fi
    sh_ dumpsys window windows > "$log.window.txt"
    hiding=$("${CHECK[@]}" hiding-windows "$log.window.txt")
    if [ -n "$hiding" ]; then
      echo "$pkg: on-screen window hides non-system overlays; skipped: $hiding" >> "$log"
      adb shell input keyevent KEYCODE_BACK
      adb shell input keyevent KEYCODE_HOME
      continue
    fi
    FG_PKG=$pkg
    echo "foreground=$pkg" >> "$log"
    adb exec-out screencap -p > "$log.png" 2>/dev/null
    log "foreground app: $pkg"
    return 0
  done
  log "no usable foreground app"
  return 1
}

# 自分のオーバーレイ窓が他の窓の HIDE_NON_SYSTEM_OVERLAY_WINDOWS で強制的に隠されているか
overlay_force_hidden() {
  awk -v pkg="$PKG" '
    /^ *Window #[0-9]+ Window\{/ { mine = ($0 ~ (" u0 " pkg "}:")) }
    mine && /mForceHideNonSystemOverlayWindow=true/ { found = 1 }
    END { exit !found }' "$1"
}

# 停止後 3 秒以内に 音/サービス/通知 が全て消えるか。消えたら経過 ms を STOPPED_AFTER_MS に入れる。
# 経過は採取完了時刻で測る (採取が遅いと開始時刻基準では期限超過を見逃すため、上限側で判定する)
wait_stopped() {
  local t0=$1 limit_ms=$2 st a s n done_at
  STOPPED_AFTER_MS=
  LAST_STATE=
  while :; do
    st=$(stop_state)
    done_at=$(now_ms)
    LAST_STATE=$st
    read -r a s n <<<"$st"
    if [ "$a" = 0 ] && [ "$s" = 0 ] && [ "$n" = 0 ]; then
      STOPPED_AFTER_MS=$(( done_at - t0 ))
      [ "$STOPPED_AFTER_MS" -le "$limit_ms" ] && return 0
      return 1
    fi
    [ $(( done_at - t0 )) -gt "$limit_ms" ] && return 1
    sleep 0.2
  done
}

record() {
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" >> "$OUT/results.tsv"
  log "RESULT $1: $2 $3 $4"
}

# 停止前に 音/サービス/通知 の全検出器が鳴動を捉えていること。
# 捉えていない検出器があると停止後の「0 0 0」が素通りで成立してしまう
probes_ringing() { [ "$1" -ge 1 ] 2>/dev/null && [ "$2" -ge 1 ] 2>/dev/null && [ "$3" -ge 1 ] 2>/dev/null; }

# 停止後の採取物 <prefix> に鳴動画面やオーバーレイが残っていれば内容を出して成功する
stop_ui_left() {
  local p=$1 left=
  # 採取に失敗した空ファイルを「何も残っていない」と読まない
  if [ ! -s "$p.activities.txt" ] || [ ! -s "$p.window.txt" ]; then
    echo "after-stop dumpsys capture failed"; return 0
  fi
  ringing_resumed "$p.activities.txt" && left="RingingActivity resumed;"
  [ "$("${CHECK[@]}" overlay "$p.window.txt" "$PKG")" != 0 ] && left="$left overlay window;"
  [ -n "$left" ] && { echo "$left"; return 0; }
  return 1
}

cleanup_ring() {
  # 失敗時でも後続シナリオへ鳴動を持ち越さない
  if service_alive; then
    harness_dismiss "${1:-}"
    sleep 3
  fi
}

# シナリオ開始前に前のシナリオの鳴動・通知が残っていないこと。
# 残った鳴動通知は次の鳴動の投稿を「更新」にして FSI を出させず、次のシナリオの結果を歪める
ensure_clean_start() {
  local sc=$1 st
  st=$(stop_state)
  [ "$st" = "0 0 0" ] && return 0
  record "$sc" FAIL DIRTY_START "leftover from previous scenario (players service notif)=$st; force-stopped before running"
  adb shell am force-stop "$PKG"
  sleep 2
}

# ---------- シナリオ ----------

scenario_unlocked() {
  local name=unlocked p="$OUT/unlocked" id t_fire t_tap xy st a s n hun ov
  unlock_screen
  start_recording "$name"
  id=$(schedule 45 EmuStopUnlocked)
  [ -n "$id" ] || { stop_recording "$name"; record "$name" FAIL SCHEDULE_FAILED "harness broadcast failed"; return; }
  if ! launch_foreground_app "$p.foreground.txt"; then
    wait_fire 120; cleanup_ring "$id"; stop_recording "$name"
    record "$name" FAIL NO_FOREGROUND_APP "no foreground app that keeps third-party overlays visible"; return
  fi
  if ! wait_fire 120; then
    stop_recording "$name"; record "$name" FAIL NO_FIRE "AlarmService did not start within 120s ($id)"; return
  fi
  t_fire=$(now_ms)
  log "$name: fired ($id); waiting 12s for heads-up to settle"
  sleep 12
  capture "$p.t12"
  st=$(STATE_KEEP="$p.t12.pre" stop_state)
  read -r a s n <<<"$st"
  ov=$("${CHECK[@]}" overlay "$p.t12.window.txt" "$PKG")
  if "${CHECK[@]}" stop-node "$p.t12.ui.xml" com.android.systemui "$STOP_TEXT" >/dev/null; then hun=yes; else hun=no; fi
  {
    echo "instance=$id"
    echo "fired_at_ms=$t_fire"
    echo "captured_after_fire_ms=$(( $(now_ms) - t_fire ))"
    echo "foreground=$(top_activity "$p.t12.activities.txt")"
    echo "overlay_windows=$ov"
    echo "ringing_activity_resumed=$(ringing_resumed "$p.t12.activities.txt" && echo yes || echo no)"
    echo "systemui_headsup_stop_visible=$hun"
    echo "overlay_force_hidden=$(overlay_force_hidden "$p.t12.window.txt" && echo yes || echo no)"
    echo "overlay_hiding_windows=$("${CHECK[@]}" hiding-windows "$p.t12.window.txt" | tr '\n' ' ')"
    echo "pre_stop alarm_players=$a service=$s notification=$n"
  } > "$p.facts.txt"
  cat "$p.facts.txt" >&2
  # 鳴動が続いていることを停止UIの判定より先に確かめる。鳴動が途中で死んだだけの
  # 「停止UI無し」を陰性対照の STOP_UI_MISSING として成立させないため
  if ! probes_ringing "$a" "$s" "$n"; then
    cleanup_ring "$id"; stop_recording "$name"
    record "$name" FAIL PROBE_PRECONDITION "before stop (players service notif)=$st; every probe must see the ring before stop"; return
  fi
  if overlay_force_hidden "$p.t12.window.txt"; then
    cleanup_ring "$id"; stop_recording "$name"
    record "$name" FAIL OVERLAY_FORCE_HIDDEN "overlay window exists but another window hides non-system overlays (invalid foreground app)"
    return
  fi
  if ! ui_dump_ok "$p.t12.ui.xml"; then
    cleanup_ring "$id"; stop_recording "$name"
    record "$name" FAIL UI_DUMP_FAILED "uiautomator dump failed 12s after fire; stop UI presence unknown"
    return
  fi
  if ! xy=$("${CHECK[@]}" stop-node "$p.t12.ui.xml" "$PKG" "$STOP_TEXT"); then
    cleanup_ring "$id"
    stop_recording "$name"
    record "$name" FAIL STOP_UI_MISSING "no $PKG '$STOP_TEXT' node on screen 12s after fire (overlay_windows=$ov, headsup_stop=$hun)"
    return
  fi
  t_tap=$(now_ms)
  # shellcheck disable=SC2086
  adb shell input tap $xy
  log "$name: tapped $STOP_TEXT at $xy"
  if wait_stopped "$t_tap" 3000; then
    sleep 2
    capture "$p.after"
    stop_recording "$name"
    if left=$(stop_ui_left "$p.after"); then
      record "$name" FAIL UI_REMAINS "stopped ${STOPPED_AFTER_MS}ms after tap but stop UI remains: $left"
      return
    fi
    record "$name" PASS OK "stop UI at +12s; stopped ${STOPPED_AFTER_MS}ms after tap (audio/service/notification); stop UI gone"
  else
    capture "$p.after"
    cleanup_ring "$id"; stop_recording "$name"
    record "$name" FAIL NOT_STOPPED "state after tap (players service notif)=${LAST_STATE} after ${STOPPED_AFTER_MS:-?}ms"
  fi
}

scenario_race() {
  local name=race p="$OUT/race" id out t_dismiss i st a s n bad=
  unlock_screen
  start_recording "$name"
  id=$(schedule 45 EmuStopRace)
  [ -n "$id" ] || { stop_recording "$name"; record "$name" FAIL SCHEDULE_FAILED "harness broadcast failed"; return; }
  launch_foreground_app "$p.foreground.txt" || log "$name: no foreground app (continuing; stop path does not depend on it)"
  # 端末内ループで鳴動サービスの出現を検知し、直ちに通知「停止」と同じ放送を送る
  out=$(sh_ "end=\$((\$(date +%s)+120)); while [ \$(date +%s) -lt \$end ]; do if dumpsys activity services $PKG | grep -q engine.AlarmService; then am broadcast --include-stopped-packages -n $HARNESS -a com.calendaralarm.emu.DISMISS --es id $id >/dev/null; echo DISMISSED; exit 0; fi; done; echo TIMEOUT")
  t_dismiss=$(now_ms)
  log "$name: $out"
  if [ "$out" != DISMISSED ]; then
    stop_recording "$name"; record "$name" FAIL NO_FIRE "AlarmService did not start within 120s ($id)"; return
  fi
  # 3 秒以内に止まり、その後 10 秒間 音・サービス・通知・オーバーレイが復活しないこと
  if ! wait_stopped "$t_dismiss" 3000; then
    capture "$p.after"; cleanup_ring "$id"; stop_recording "$name"
    record "$name" FAIL NOT_STOPPED "state after early stop=${LAST_STATE}"
    return
  fi
  local stopped_ms=$STOPPED_AFTER_MS wov
  for i in $(seq 1 10); do
    sleep 1
    st=$(stop_state)
    # オーバーレイも毎秒見る (途中で出て消えた復活を終点の採取だけでは見逃す)
    sh_ dumpsys window windows > "$p.watch.window.txt" 2>&1
    if [ -s "$p.watch.window.txt" ]; then wov=$("${CHECK[@]}" overlay "$p.watch.window.txt" "$PKG"); else wov=err; fi
    echo "t+${i}s players/service/notif=$st overlay=$wov" >> "$p.watch.txt"
    read -r a s n <<<"$st"
    if [ "$a" != 0 ] || [ "$s" != 0 ] || [ "$n" != 0 ] || [ "$wov" != 0 ]; then bad="t+${i}s $st overlay=$wov"; break; fi
  done
  capture "$p.after"
  local ov
  ov=$("${CHECK[@]}" overlay "$p.after.window.txt" "$PKG")
  stop_recording "$name"
  if [ -n "$bad" ]; then
    cleanup_ring "$id"
    record "$name" FAIL ZOMBIE "alarm or stop UI came back after early stop (players service notif overlay): $bad"
  elif [ "$ov" != 0 ]; then
    record "$name" FAIL ZOMBIE_OVERLAY "overlay still on screen after early stop"
  else
    record "$name" PASS OK "early stop stopped in ${stopped_ms}ms; no audio/service/notification/overlay for 10s"
  fi
}

scenario_locked() { locked_case locked off; }
scenario_locked_screen_on() { locked_case locked_screen_on on; }

# PIN ロック中に鳴動。screen=off は消灯中 (机に置いた端末)、on はロック画面が点灯中
locked_case() {
  local name=$1 screen=$2 p="$OUT/$1" id t_tap xy ov rr st a s n
  unlock_screen
  sh_ locksettings set-pin "$PIN" > "$p.locksettings.txt" 2>&1
  # PIN が設定できないとロック無しで流れて PASS してしまい、PIN 入力画面の退行を検出できない
  if ! grep -q "Pin set to" "$p.locksettings.txt"; then
    record "$name" FAIL LOCK_SETUP_FAILED "locksettings set-pin failed: $(tr '\n' ' ' < "$p.locksettings.txt")"
    locked_restore "$name"; return
  fi
  adb shell svc power stayon false
  start_recording "$name"
  id=$(schedule 45 "EmuStop_$name")
  [ -n "$id" ] || { stop_recording "$name"; record "$name" FAIL SCHEDULE_FAILED "harness broadcast failed"; locked_restore "$name"; return; }
  adb shell input keyevent KEYCODE_SLEEP
  sleep 3
  if [ "$screen" = on ]; then
    adb shell input keyevent KEYCODE_WAKEUP
    adb shell svc power stayon true
    sleep 2
  fi
  sh_ dumpsys window | grep -Ei "keyguard|showing_lockscreen|mDreaming" > "$p.keyguard_before.txt" 2>&1
  if ! grep -q "isKeyguardShowing=true" "$p.keyguard_before.txt"; then
    wait_fire 120; cleanup_ring "$id"; stop_recording "$name"
    record "$name" FAIL LOCK_NOT_ACTIVE "keyguard not showing before fire (see $name.keyguard_before.txt)"
    locked_restore "$name"; return
  fi
  if ! wait_fire 120; then
    stop_recording "$name"; record "$name" FAIL NO_FIRE "AlarmService did not start within 120s ($id)"
    locked_restore "$name"; return
  fi
  log "$name: fired ($id); waiting 12s"
  sleep 12
  capture "$p.t12"
  st=$(STATE_KEEP="$p.t12.pre" stop_state)
  read -r a s n <<<"$st"
  ov=$("${CHECK[@]}" overlay "$p.t12.window.txt" "$PKG")
  if ringing_resumed "$p.t12.activities.txt"; then rr=yes; else rr=no; fi
  {
    echo "instance=$id"
    echo "screen_before_fire=$screen"
    echo "top=$(top_activity "$p.t12.activities.txt")"
    echo "ringing_activity_resumed=$rr"
    echo "overlay_windows=$ov"
    echo "pre_stop alarm_players=$a service=$s notification=$n"
  } > "$p.facts.txt"
  cat "$p.facts.txt" >&2
  if [ "$rr" = yes ] && [ "$ov" != 0 ]; then
    xy=$("${CHECK[@]}" stop-node "$p.t12.ui.xml" "$PKG" "$STOP_TEXT") || xy=
    [ -n "$xy" ] && adb shell input tap $xy
    sleep 3; cleanup_ring "$id"; stop_recording "$name"
    record "$name" FAIL STACKED_UI "RingingActivity resumed AND overlay window on screen at the same time"
    locked_restore "$name"; return
  fi
  if ! ui_dump_ok "$p.t12.ui.xml"; then
    cleanup_ring "$id"; stop_recording "$name"
    record "$name" FAIL UI_DUMP_FAILED "uiautomator dump failed 12s after fire while locked"
    locked_restore "$name"; return
  fi
  if ! xy=$("${CHECK[@]}" stop-node "$p.t12.ui.xml" "$PKG" "$STOP_TEXT"); then
    cleanup_ring "$id"; stop_recording "$name"
    record "$name" FAIL STOP_UI_MISSING "no $PKG '$STOP_TEXT' node while locked (ringing_activity=$rr overlay=$ov)"
    locked_restore "$name"; return
  fi
  if ! probes_ringing "$a" "$s" "$n"; then
    cleanup_ring "$id"; stop_recording "$name"
    record "$name" FAIL PROBE_PRECONDITION "before stop (players service notif)=$st; every probe must see the ring before stop"
    locked_restore "$name"; return
  fi
  t_tap=$(now_ms)
  # shellcheck disable=SC2086
  adb shell input tap $xy
  if wait_stopped "$t_tap" 3000; then
    sleep 2; capture "$p.after"; stop_recording "$name"
    sh_ dumpsys window | grep -Ei "keyguard|showing_lockscreen|mDreaming" > "$p.keyguard_after.txt" 2>&1
    if left=$(stop_ui_left "$p.after"); then
      record "$name" FAIL UI_REMAINS "stopped ${STOPPED_AFTER_MS}ms after tap but stop UI remains: $left"
    elif ! grep -q "isKeyguardShowing=true" "$p.keyguard_after.txt"; then
      # 停止でロックが外れてはいけない (鳴動画面はキーガード解除を要求しない)
      record "$name" FAIL UNLOCKED_BY_STOP "keyguard not showing after stop (see $name.keyguard_after.txt)"
    else
      record "$name" PASS OK "single stop UI (ringing_activity=$rr overlay=$ov); stopped ${STOPPED_AFTER_MS}ms after tap; keyguard kept"
    fi
  else
    capture "$p.after"; cleanup_ring "$id"; stop_recording "$name"
    record "$name" FAIL NOT_STOPPED "state after tap=${LAST_STATE}"
  fi
  locked_restore "$name"
}

locked_restore() {
  adb shell input keyevent KEYCODE_WAKEUP
  sh_ locksettings clear --old "$PIN" >> "$OUT/$1.locksettings.txt" 2>&1
  adb shell svc power stayon true
  unlock_screen
}

export_audit() {
  local d="$OUT/db"
  mkdir -p "$d"
  for f in calendar-alarm.db calendar-alarm.db-wal calendar-alarm.db-shm; do
    adb exec-out run-as "$PKG" cat "databases/$f" > "$d/$f" 2>/dev/null || rm -f "$d/$f"
  done
  if [ -s "$d/calendar-alarm.db" ]; then
    "${CHECK[@]}" audit "$d/calendar-alarm.db" > "$OUT/audit_log.tsv" 2>&1 || true
  else
    echo "audit DB export failed" > "$OUT/audit_log.tsv"
  fi
}

# ---------- 本体 ----------

log "booted: $(sh_ getprop ro.build.fingerprint)"
adb install -r -g "$APK" > "$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 1; }
APP_UID=$(sh_ pm list packages -U "$PKG" | sed -n "s/^package:$PKG uid:\([0-9]*\).*/\1/p" | head -1)
log "app uid=$APP_UID"
# uid が取れないと音の検出器が常に 0 を返し、停止の判定が素通りになる
[ -n "$APP_UID" ] || { log "could not resolve app uid; audio probe would be blind"; exit 1; }
sh_ pm grant "$PKG" android.permission.POST_NOTIFICATIONS
sh_ appops set "$PKG" SYSTEM_ALERT_WINDOW allow
sh_ appops set "$PKG" USE_FULL_SCREEN_INTENT allow
sh_ appops set "$PKG" SCHEDULE_EXACT_ALARM allow >/dev/null 2>&1
{
  echo "--- appops"; sh_ appops get "$PKG"
  echo "--- permissions"; sh_ dumpsys package "$PKG" | grep -E "granted=|USE_FULL_SCREEN_INTENT|SYSTEM_ALERT_WINDOW"
} > "$OUT/permissions.txt" 2>&1
sh_ settings put system screen_off_timeout 1800000
adb shell svc power stayon true
sh_ settings put global heads_up_notifications_enabled 1
sh_ pm list packages > "$OUT/packages.txt" 2>&1

for sc in "${SCENARIOS[@]}"; do
  log "=== scenario: $sc ==="
  ensure_clean_start "$sc"
  "scenario_$sc"
done

export_audit
sh_ logcat -d -v threadtime > "$OUT/logcat.txt" 2>&1

{
  echo "## alarm stop E2E (emulator)"
  echo
  echo "- device: $(sh_ getprop ro.build.fingerprint)"
  echo "- apk: $APK"
  echo
  echo "| scenario | result | code | detail |"
  echo "|---|---|---|---|"
  awk -F'\t' '{printf "| %s | %s | %s | %s |\n", $1, $2, $3, $4}' "$OUT/results.tsv"
} > "$OUT/summary.md"
cat "$OUT/summary.md"

if grep -q $'\tFAIL\t' "$OUT/results.tsv"; then exit 1; fi
exit 0
