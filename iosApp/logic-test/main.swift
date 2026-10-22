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
// 停止ボタンの intent が書き残した id。"ev:past-ringing" は書き残しがあってもまだ鳴っている
let stopped: Set<String> = ["ev:past-stopped", "sa:1:snz1", "ev:past-ringing"]

check(OffAppStopPlan.consumedWhileAway(pending: rows, nowMillis: now, stopped: stopped, isLive: isLive)
      == ["ev:past-stopped", "sa:1:snz1"],
      "停止の書き残しがあり、予定時刻を過ぎて AlarmKit に無い行は止められた扱い (1時間を過ぎたスヌーズ子も見逃しにしない)")
check(!OffAppStopPlan.consumedWhileAway(pending: rows, nowMillis: now, stopped: stopped, isLive: isLive).contains("ev:past-ringing"),
      "まだ鳴っている (AlarmKit に残る) 行は、書き残しがあっても止められた扱いにしない")
check(!OffAppStopPlan.consumedWhileAway(pending: rows, nowMillis: now, stopped: stopped.union(["sa:2:snz1"]), isLive: isLive).contains("sa:2:snz1"),
      "予約に失敗したまま残した行 (alarmKitId なし) は、止められた扱いにしない")

// 予約が本当に消えた行 (再起動や OS の破棄) は、AlarmKit から消えて見えるのは止めた行と同じ。書き残しで見分ける
let purged = [Row(id: "ev:x", triggerAtMillis: now - 60_000, wasReserved: true)]
check(OffAppStopPlan.decide(pending: purged, nowMillis: now, stopped: []) { _ in false }.consumed.isEmpty,
      "書き残しの無い過去の行は、AlarmKit に無くても止めた扱いにしない (鳴らし損ねと嘘の履歴を防ぐ)")
check(OffAppStopPlan.decide(pending: purged, nowMillis: now, stopped: ["ev:x"]) { _ in false }.consumed == ["ev:x"],
      "同じ行でも停止の書き残しがあれば止めた扱いにする")
check(OffAppStopPlan.consumedWhileAway(pending: [Row(id: "ev:future", triggerAtMillis: now + 60_000, wasReserved: true)],
                                       nowMillis: now, stopped: ["ev:future"]) { _ in false }.isEmpty,
      "予定時刻より前の行は、書き残しがあっても止めた扱いにしない")

check(OffAppStopPlan.consumedWhileAway(pending: [Row(id: "ev:edge", triggerAtMillis: now, wasReserved: true)],
                                       nowMillis: now, stopped: ["ev:edge"]) { _ in false }
      == ["ev:edge"],
      "予定時刻ちょうどは過ぎた側に数える (再予約で二重に鳴らさない)")
check(OffAppStopPlan.consumedWhileAway(pending: [], nowMillis: now, stopped: stopped) { _ in false }.isEmpty,
      "予約が無ければ何もしない")

// 単発アラームの消費
let oneShot = OffAppStopPlan.decide(pending: [
    Row(id: "sa:10:d1", triggerAtMillis: now - 60_000, wasReserved: true, standaloneAlarmId: 10),
], nowMillis: now, stopped: ["sa:10:d1"]) { _ in false }
check(oneShot == OffAppStopPlan.Decision(consumed: ["sa:10:d1"], oneShotAlarmIds: [10]),
      "止められた単発は消費済みとして無効化対象にする")

let withChild = OffAppStopPlan.decide(pending: [
    Row(id: "sa:11:d1", triggerAtMillis: now - 60_000, wasReserved: true, standaloneAlarmId: 11),
    Row(id: "sa:11:d1:snz1", triggerAtMillis: now + 300_000, wasReserved: true, standaloneAlarmId: 11),
], nowMillis: now, stopped: ["sa:11:d1"]) { $0 == "sa:11:d1:snz1" }
check(withChild == OffAppStopPlan.Decision(consumed: ["sa:11:d1"], oneShotAlarmIds: []),
      "スヌーズ子がまだ待っている単発は無効化しない")

let unreservedChild = OffAppStopPlan.decide(pending: [
    Row(id: "sa:12:d1", triggerAtMillis: now - 600_000, wasReserved: true, standaloneAlarmId: 12),
    Row(id: "sa:12:d1:snz1", triggerAtMillis: now - 60_000, wasReserved: false, standaloneAlarmId: 12),
], nowMillis: now, stopped: ["sa:12:d1"]) { _ in false }
check(unreservedChild == OffAppStopPlan.Decision(consumed: ["sa:12:d1"], oneShotAlarmIds: []),
      "予約に失敗したスヌーズ子が残っている単発は無効化しない (子は拾い直されて鳴る)")

let bothStopped = OffAppStopPlan.decide(pending: [
    Row(id: "sa:13:d1", triggerAtMillis: now - 600_000, wasReserved: true, standaloneAlarmId: 13),
    Row(id: "sa:13:d1:snz1", triggerAtMillis: now - 60_000, wasReserved: true, standaloneAlarmId: 13),
], nowMillis: now, stopped: ["sa:13:d1", "sa:13:d1:snz1"]) { _ in false }
check(bothStopped == OffAppStopPlan.Decision(consumed: ["sa:13:d1", "sa:13:d1:snz1"], oneShotAlarmIds: [13]),
      "親もスヌーズ子も止められていれば単発は消費済み")

let parentOnlyUnrecorded = OffAppStopPlan.decide(pending: [
    Row(id: "sa:14:d1", triggerAtMillis: now - 60_000, wasReserved: true, standaloneAlarmId: 14),
], nowMillis: now, stopped: []) { _ in false }
check(parentOnlyUnrecorded == OffAppStopPlan.Decision(consumed: [], oneShotAlarmIds: []),
      "書き残しの無い単発は無効化しない (拾い直しで鳴らす)")

let repeating = OffAppStopPlan.decide(pending: [
    Row(id: "ev:abc", triggerAtMillis: now - 60_000, wasReserved: true),
], nowMillis: now, stopped: ["ev:abc"]) { _ in false }
check(repeating.oneShotAlarmIds.isEmpty, "単発アラームでない行 (予定のアラーム) は無効化対象を作らない")

// 停止の書き残し
let suite = "offAppStopLogTest-\(ProcessInfo.processInfo.processIdentifier)"
if let defaults = UserDefaults(suiteName: suite) {
    defaults.removePersistentDomain(forName: suite)
    let log = OffAppStopLog(defaults: defaults)
    check(log.entries.isEmpty, "書き残しは最初は空")
    log.record("ev:a", atMillis: now - 1_000)
    log.record("ev:b", atMillis: now - OffAppStopLog.retentionMillis - 1)
    log.record("ev:c", atMillis: now)
    check(Set(log.entries.keys) == ["ev:a", "ev:b", "ev:c"] && log.entries["ev:a"] == now - 1_000,
          "書き残しは id ごとに時刻付きで残る")
    log.remove(["ev:a"], nowMillis: now)
    check(Set(log.entries.keys) == ["ev:c"], "使った id と14日を過ぎた書き残しは消える")
    check(Set(OffAppStopLog(defaults: defaults).entries.keys) == ["ev:c"], "書き残しは別のインスタンスからも読める")

    // 停止 intent が同時に何本も走り、resync の remove とも重なる場合 (Codex P2)
    log.remove(Set(log.entries.keys), nowMillis: now)
    let n = 200
    DispatchQueue.concurrentPerform(iterations: n) { i in
        OffAppStopLog(defaults: defaults).record("ev:p\(i)", atMillis: now)
        if i % 10 == 0 { OffAppStopLog(defaults: defaults).remove(["ev:none"], nowMillis: now) }
    }
    check(log.entries.count == n, "同時に書き残しても、remove と重なっても1件も消えない (\(log.entries.count)/\(n))")
    defaults.removePersistentDomain(forName: suite)
} else {
    check(false, "試験用の UserDefaults を作れない")
}

if failures > 0 { print("\(failures) failed"); exit(1) }
print("all passed")
