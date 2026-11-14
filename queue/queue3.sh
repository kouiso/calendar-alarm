#!/bin/bash
S=/tmp/claude-0/-home-user/c98a3d37-2e8b-5f9f-bf50-a9de72bb8786/scratchpad; R=repos/kouiso/calendar-alarm; cd $S
Q=/tmp/claude-0/-home-user/c98a3d37-2e8b-5f9f-bf50-a9de72bb8786/tasks/bbdmn80mc.output
until grep -qE 'QDONE|STOP' $Q 2>/dev/null; do sleep 60; done; grep -q STOP $Q && { echo "queue2 stopped; not posting"; exit 1; }; sleep 40
chk() { echo "$1" | grep -q 'HTTP [45]\|rate limit' && { echo "STOP at $2"; exit 1; }; }
for n in 396 397 399 400; do o=$(gh api -X PATCH $R/pulls/$n -F body=@body$n-fix.md --jq .html_url 2>&1|tail -1); echo "body #$n $o"; chk "$o" body$n; sleep 40; done
o=$(gh api $R/pulls/396/comments/4573679730/replies -F body=@reply396-nbsp.md --jq .html_url 2>&1|tail -1); echo "reply396 $o"; chk "$o" reply396; sleep 40
o=$(gh api $R/issues/399/comments -F body=@reply399-must1.md --jq .html_url 2>&1|tail -1); echo "reply399 $o"; chk "$o" reply399; sleep 40
o=$(gh api $R/issues/307/comments -F body=@reply396-ios307.md --jq .html_url 2>&1|tail -1); echo "c307 $o"; chk "$o" c307
echo Q3DONE
