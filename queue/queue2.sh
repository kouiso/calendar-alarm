#!/bin/bash
# after a secondary rate limit: wait, then drain pending writes 40s apart; stop at first 4xx/5xx; no retries
S=/tmp/claude-0/-home-user/c98a3d37-2e8b-5f9f-bf50-a9de72bb8786/scratchpad; R=repos/kouiso/calendar-alarm; cd $S
until [ $(date -u +%s) -ge $(date -u -d '2026-11-14T14:36:00Z' +%s) ]; do sleep 30; done
chk() { echo "$1" | grep -q 'HTTP [45]\|rate limit' && { echo "STOP at $2"; exit 1; }; }
for f in dgap-10 dgap-11a dgap-11b dgap-11c; do
  t=$(head -1 $f.md | sed 's/^# *//'); t="[PBI][UX] $t"
  o=$(gh api $R/issues -f title="$t" -F body=@$f.body.md -f 'labels[]=feature' --jq '"\(.number) \(.html_url)"' 2>&1|tail -1); echo "$f $o"; chk "$o" $f; sleep 40
done
for n in 396 397 398 399 400; do o=$(gh api $R/issues/$n/comments -F body=@review$n-r.md --jq .html_url 2>&1|tail -1); echo "#$n $o"; chk "$o" rev$n; [ $n = 398 ] && REV398=$o; sleep 40; done
o=$(gh api $R/pulls -f title="docs(design): copy canvas 1794658749-78c1 (adds logo board) and point the spec at the current design" -f head=docs/design-copy-78c1 -f base=master -F body=@pr-design.md --jq '"\(.number) \(.html_url)"' 2>&1|tail -1); echo "PR $o"; chk "$o" designpr
sleep 40
printf '%s\n' "398|5336dcb346fbb5f215a9612d295dd1468a535b5f|$REV398|./gradlew :shared:jvmTest --rerun (incl. SharedDecisionTableTest 4)|pass 133 fail 0|6 success / 0 fail / 0 cancelled / 0 pending (+1 neutral CodeRabbit; verify (fix), verify (control-3d106817), ios-build success). The earlier verify (fix) on d70ad1d failed PROBE_PRECONDITION (https://github.com/kouiso/calendar-alarm/actions/runs/43600072934/job/132462837015); the rerun API is 403 here, so master was merged in and the fresh run is green; not reproduced|Closes #301: its 確かめ方 (:shared:jvmTest passes) is met (133, fail 0), as the review states. want 5（変異 3 件が生き残る: 開始と通知の重複時の優先・horizon の境目・通知のアクション）は別の PR で表に足す。" > gate.tsv
EV=16118b5f35c6d1dee23d6cfd0e56bb435a368569 CHG="base merge only (reviewed d70ad1d + merge of master; no other change)" ./gate.sh
echo QDONE
