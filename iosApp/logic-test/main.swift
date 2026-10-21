// iOS の画面や OS に依存しない判定だけを、Mac 上の swiftc で走らせる試験 (scripts/ios-logic-test.sh)。
// このリポジトリには iOS シミュレータで回す試験ターゲットが無いため、純粋な判定はここで確かめる。
import Foundation

var failures = 0
func check(_ cond: Bool, _ name: String) {
    if cond { print("PASS \(name)") } else { print("FAIL \(name)"); failures += 1 }
}

let now: Int64 = 1_800_000_000_000
let rows = [
    OffAppStopPlan.Row(id: "ev:past-stopped", triggerAtMillis: now - 60_000),
    OffAppStopPlan.Row(id: "sa:1:snz1", triggerAtMillis: now - 3 * 3_600_000),
    OffAppStopPlan.Row(id: "ev:past-ringing", triggerAtMillis: now - 30_000),
    OffAppStopPlan.Row(id: "ev:future-lost", triggerAtMillis: now + 600_000),
    OffAppStopPlan.Row(id: "ev:future-ok", triggerAtMillis: now + 600_000),
]
let live: Set<String> = ["ev:past-ringing", "ev:future-ok"]

check(OffAppStopPlan.consumedWhileAway(pending: rows, nowMillis: now) { live.contains($0) }
      == ["ev:past-stopped", "sa:1:snz1"],
      "予定時刻を過ぎて AlarmKit に無い行は、鳴って止められたとみなす (1時間を過ぎたスヌーズ子も見逃しにしない)")
check(!OffAppStopPlan.consumedWhileAway(pending: rows, nowMillis: now) { live.contains($0) }.contains("ev:past-ringing"),
      "まだ鳴っている (AlarmKit に残る) 行は止められた扱いにしない")
check(OffAppStopPlan.lostReservations(pending: rows, nowMillis: now) { live.contains($0) } == ["ev:future-lost"],
      "予定時刻がまだ先で AlarmKit に無い行だけを予約喪失として拾い直す")
check(OffAppStopPlan.consumedWhileAway(pending: [OffAppStopPlan.Row(id: "ev:edge", triggerAtMillis: now)], nowMillis: now) { _ in false }
      == ["ev:edge"],
      "予定時刻ちょうどは過ぎた側に数える (再予約で二重に鳴らさない)")
check(OffAppStopPlan.consumedWhileAway(pending: [], nowMillis: now) { _ in false }.isEmpty
      && OffAppStopPlan.lostReservations(pending: [], nowMillis: now) { _ in false }.isEmpty,
      "予約が無ければ何もしない")

if failures > 0 { print("\(failures) failed"); exit(1) }
print("all passed")
