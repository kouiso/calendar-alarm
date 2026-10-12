#!/usr/bin/env bash
# run_scenarios.sh の結果をレーンの期待値と突き合わせ、ジョブサマリへ書く。
#   evaluate.sh <out_dir> <expect: pass|stop-ui-missing> <lane name>
# stop-ui-missing は陰性対照用: unlocked シナリオが STOP_UI_MISSING で落ちた時だけ成功とする
# (= 修正前のコードでバグが再現し、このテストがバグを検出できることの確認)。
set -uo pipefail

OUT=${1:?out dir}
EXPECT=${2:?expect}
LANE=${3:?lane}
RESULTS="$OUT/results.tsv"
SUMMARY=${GITHUB_STEP_SUMMARY:-/dev/stdout}

verdict=FAIL
note=
if [ ! -s "$RESULTS" ]; then
  note="results.tsv がありません (エミュレータ起動失敗またはスクリプト異常終了)"
else
  case "$EXPECT" in
    pass)
      if grep -q $'\tFAIL\t' "$RESULTS"; then
        note="期待: 全シナリオ PASS / 実際: FAIL あり"
      else
        verdict=PASS; note="全シナリオ PASS"
      fi
      ;;
    stop-ui-missing)
      if awk -F'\t' '$1 == "unlocked" && $2 == "FAIL" && $3 == "STOP_UI_MISSING" { found = 1 } END { exit !found }' "$RESULTS"; then
        verdict=PASS; note="陰性対照: 停止UI欠落を再現 (unlocked が STOP_UI_MISSING で失敗) = テストはバグを検出できる"
      else
        note="陰性対照なのに停止UI欠落を再現できなかった (テストがバグを検出できていない)"
      fi
      ;;
    *)
      note="unknown expect: $EXPECT"
      ;;
  esac
fi

{
  echo "### lane: $LANE (expect: $EXPECT) → $verdict"
  echo
  echo "$note"
  echo
  [ -s "$OUT/summary.md" ] && cat "$OUT/summary.md"
  echo
  for f in "$OUT"/*.facts.txt; do
    [ -e "$f" ] || continue
    echo "<details><summary>$(basename "$f")</summary>"
    echo
    echo '```'
    cat "$f"
    echo '```'
    echo "</details>"
  done
} >> "$SUMMARY"

[ "$verdict" = PASS ]
