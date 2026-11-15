#!/usr/bin/env bash
# stop_state_retry / state_capture_failed (run_scenarios.sh, #375) の採り直しループの単体テスト。
# run_scenarios.sh は実行すると adb に触るので、関数の定義だけを取り出し、
# adb・sleep・timeout・stop_state を差し替えて (エミュレータ無しで) 呼ぶ。
#   bash scripts/emulator/test_run_scenarios_retry.sh
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SRC="$HERE/run_scenarios.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# 関数 1 つ分 (行頭の "name() {" から、行頭の "}" まで) を取り出す
extract() {
  awk -v n="$1" '$0 == n "() {" { p = 1 } p { print } p && $0 == "}" { exit }' "$SRC"
}
for fn in stop_state_retry state_capture_failed; do
  extract "$fn" > "$work/$fn.sh"
  if [ ! -s "$work/$fn.sh" ]; then echo "FAIL: $fn が $SRC に見つからない" >&2; exit 1; fi
  # shellcheck disable=SC1090
  . "$work/$fn.sh"
done

CHECK=(python3 "$HERE/emu_check.py")
adb() { :; }
sleep() { :; }
timeout() { :; }
log() { :; }
# stop_state の代わり: $SEQ (空白区切りでなく '|' 区切り) を 1 回ごとに 1 つ返し、呼ばれた回数を数える。
# 使い切ったら最後の値を返し続ける。$(...) の中 (サブシェル) で呼ばれるので回数はファイルで持つ
stop_state() {
  local n
  n=$(cat "$work/count" 2>/dev/null || echo 0)
  n=$((n + 1))
  echo "$n" > "$work/count"
  local IFS='|' vals
  read -ra vals <<<"$SEQ"
  local i=$((n - 1))
  [ "$i" -lt "${#vals[@]}" ] || i=$((${#vals[@]} - 1))
  echo "${vals[$i]}"
}

fail=0
check() { # name seq expected_result expected_captures
  local name=$1 got n
  SEQ=$2
  echo 0 > "$work/count"
  got=$(stop_state_retry)
  n=$(cat "$work/count")
  if [ "$got" = "$3" ] && [ "$n" = "$4" ]; then
    echo "ok   $name (result='$got' captures=$n)"
  else
    echo "FAIL $name: result='$got' captures=$n (want '$3' / $4)" >&2
    fail=1
  fi
}

check "err,err,ok → 3 回目で採れる" "err err err|err err err|1 1 1" "1 1 1" 3
check "ずっと err → 初回+採り直し3回で諦める" "err err err" "err err err" 4
check "0 1 1 は採り直さない (鳴っていないものを通さない)" "0 1 1" "0 1 1" 1
check "初回から採れる値はそのまま" "1 1 1" "1 1 1" 1

# state_capture_failed: err なら 0 (採取失敗)、採れた値なら 1
if state_capture_failed "err err err"; then echo "ok   state_capture_failed err"; else echo "FAIL state_capture_failed err" >&2; fail=1; fi
if state_capture_failed "0 1 1"; then echo "FAIL state_capture_failed 0 1 1" >&2; fail=1; else echo "ok   state_capture_failed 0 1 1"; fi

# 呼び出し側 (run_scenarios.sh の 2 か所) の並び: stop_state_retry → state_capture_failed →
# FAIL INFRA_ERROR → probes_ringing。採取失敗を probes_ringing より先に INFRA_ERROR にしないと、
# 採れなかっただけの回が PROBE_PRECONDITION (鳴っていない) に化ける。行番号は grep で拾うので、行が動いても追従する
lines_of() { grep -nE "$1" "$SRC" | cut -d: -f1; }
mapfile -t L_RETRY < <(lines_of 'st=\$\(STATE_KEEP="[^"]*" stop_state_retry\)')
mapfile -t L_CAPFAIL < <(lines_of '^[[:space:]]*if state_capture_failed "\$st"; then')
mapfile -t L_INFRA < <(lines_of 'record "\$name" FAIL INFRA_ERROR "could not capture pre-stop')
mapfile -t L_PROBES < <(lines_of '^[[:space:]]*if ! probes_ringing "\$a" "\$s" "\$n"; then')
sites=${#L_RETRY[@]}
if [ "$sites" -ne 2 ] || [ "${#L_CAPFAIL[@]}" -ne "$sites" ] || [ "${#L_INFRA[@]}" -ne "$sites" ] || [ "${#L_PROBES[@]}" -ne "$sites" ]; then
  echo "FAIL 呼び出し側の数が揃わない: retry=${#L_RETRY[@]} capfail=${#L_CAPFAIL[@]} infra=${#L_INFRA[@]} probes=${#L_PROBES[@]} (want 各 2)" >&2
  fail=1
else
  for i in $(seq 0 $((sites - 1))); do
    r=${L_RETRY[$i]} c=${L_CAPFAIL[$i]} f=${L_INFRA[$i]} p=${L_PROBES[$i]}
    next=${L_RETRY[$((i + 1))]:-999999}
    if [ "$r" -lt "$c" ] && [ "$c" -lt "$f" ] && [ "$f" -lt "$p" ] && [ "$p" -lt "$next" ]; then
      echo "ok   呼び出し側 $((i + 1)) の順序 (L$r retry < L$c state_capture_failed < L$f INFRA_ERROR < L$p probes_ringing)"
    else
      echo "FAIL 呼び出し側 $((i + 1)) の順序: retry=L$r capfail=L$c infra=L$f probes=L$p (want 昇順、次の呼び出し L$next より前)" >&2
      fail=1
    fi
  done
fi

exit "$fail"
