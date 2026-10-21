// iOS の画面や OS に依存しない判定だけを、Mac 上の swiftc で走らせる試験 (scripts/ios-logic-test.sh)。
// このリポジトリには iOS シミュレータで回す試験ターゲットが無いため、純粋な判定はここで確かめる。
import Foundation

var failures = 0
func check(_ cond: Bool, _ name: String) {
    if cond { print("PASS \(name)") } else { print("FAIL \(name)"); failures += 1 }
}

typealias Row = OffAppStopPlan.Row
let now: Int64 = 1_800_000_000_000
let rows = [
    Row(id: "ev:past-stopped", triggerAtMillis: now - 60_000, wasReserved: true),
    Row(id: "sa:1:snz1", triggerAtMillis: now - 3 * 3_600_000, wasReserved: true),
    Row(id: "ev:past-ringing", triggerAtMillis: now - 30_000, wasReserved: true),
    Row(id: "ev:future-lost", triggerAtMillis: now + 600_000, wasReserved: true),
    Row(id: "ev:future-ok", triggerAtMillis: now + 600_000, wasReserved: true),
    Row(id: "sa:2:snz1", triggerAtMillis: now - 120_000, wasReserved: false),
]
let live: Set<String> = ["ev:past-ringing", "ev:future-ok"]
let isLive: (String) -> Bool = { live.contains($0) }

check(OffAppStopPlan.consumedWhileAway(pending: rows, nowMillis: now, isLive: isLive)
      == ["ev:past-stopped", "sa:1:snz1"],
      "予約済みで予定時刻を過ぎて AlarmKit に無い行は、鳴って止められたとみなす (1時間を過ぎたスヌーズ子も見逃しにしない)")
check(!OffAppStopPlan.consumedWhileAway(pending: rows, nowMillis: now, isLive: isLive).contains("ev:past-ringing"),
      "まだ鳴っている (AlarmKit に残る) 行は止められた扱いにしない")
check(!OffAppStopPlan.consumedWhileAway(pending: rows, nowMillis: now, isLive: isLive).contains("sa:2:snz1"),
      "予約に失敗したまま残した行 (alarmKitId なし) は、時刻を過ぎても止められた扱いにしない")
check(OffAppStopPlan.lostReservations(pending: rows, nowMillis: now, isLive: isLive) == ["ev:future-lost", "sa:2:snz1"],
      "予定時刻がまだ先の行と、予約に失敗したまま残した過去の行を、予約喪失として拾い直す")
check(OffAppStopPlan.consumedWhileAway(pending: [Row(id: "ev:edge", triggerAtMillis: now, wasReserved: true)], nowMillis: now) { _ in false }
      == ["ev:edge"],
      "予定時刻ちょうどは過ぎた側に数える (再予約で二重に鳴らさない)")
check(OffAppStopPlan.consumedWhileAway(pending: [], nowMillis: now) { _ in false }.isEmpty
      && OffAppStopPlan.lostReservations(pending: [], nowMillis: now) { _ in false }.isEmpty,
      "予約が無ければ何もしない")

// 拾い直しと止められた扱いは重ならず、AlarmKit に無い PENDING 行はどちらかに必ず入る
let consumedAll = Set(OffAppStopPlan.consumedWhileAway(pending: rows, nowMillis: now, isLive: isLive))
let lostAll = Set(OffAppStopPlan.lostReservations(pending: rows, nowMillis: now, isLive: isLive))
check(consumedAll.isDisjoint(with: lostAll)
      && consumedAll.union(lostAll) == Set(rows.filter { !live.contains($0.id) }.map { $0.id }),
      "AlarmKit に無い行は、止められた扱いか拾い直しのどちらか一方に必ず入る")

// 単発アラームの消費
let oneShot = OffAppStopPlan.decide(pending: [
    Row(id: "sa:10:d1", triggerAtMillis: now - 60_000, wasReserved: true, standaloneAlarmId: 10),
], nowMillis: now) { _ in false }
check(oneShot == OffAppStopPlan.Decision(consumed: ["sa:10:d1"], oneShotAlarmIds: [10]),
      "止められた単発は消費済みとして無効化対象にする")

let withChild = OffAppStopPlan.decide(pending: [
    Row(id: "sa:11:d1", triggerAtMillis: now - 60_000, wasReserved: true, standaloneAlarmId: 11),
    Row(id: "sa:11:d1:snz1", triggerAtMillis: now + 300_000, wasReserved: true, standaloneAlarmId: 11),
], nowMillis: now) { $0 == "sa:11:d1:snz1" }
check(withChild == OffAppStopPlan.Decision(consumed: ["sa:11:d1"], oneShotAlarmIds: []),
      "スヌーズ子がまだ待っている単発は無効化しない")

let unreservedChild = OffAppStopPlan.decide(pending: [
    Row(id: "sa:12:d1", triggerAtMillis: now - 600_000, wasReserved: true, standaloneAlarmId: 12),
    Row(id: "sa:12:d1:snz1", triggerAtMillis: now - 60_000, wasReserved: false, standaloneAlarmId: 12),
], nowMillis: now) { _ in false }
check(unreservedChild == OffAppStopPlan.Decision(consumed: ["sa:12:d1"], oneShotAlarmIds: []),
      "予約に失敗したスヌーズ子が残っている単発は無効化しない (子は拾い直されて鳴る)")

let bothStopped = OffAppStopPlan.decide(pending: [
    Row(id: "sa:13:d1", triggerAtMillis: now - 600_000, wasReserved: true, standaloneAlarmId: 13),
    Row(id: "sa:13:d1:snz1", triggerAtMillis: now - 60_000, wasReserved: true, standaloneAlarmId: 13),
], nowMillis: now) { _ in false }
check(bothStopped == OffAppStopPlan.Decision(consumed: ["sa:13:d1", "sa:13:d1:snz1"], oneShotAlarmIds: [13]),
      "親もスヌーズ子も止められていれば単発は消費済み")

let repeating = OffAppStopPlan.decide(pending: [
    Row(id: "ev:abc", triggerAtMillis: now - 60_000, wasReserved: true),
], nowMillis: now) { _ in false }
check(repeating.oneShotAlarmIds.isEmpty, "単発アラームでない行 (予定のアラーム) は無効化対象を作らない")

if failures > 0 { print("\(failures) failed"); exit(1) }
print("all passed")
