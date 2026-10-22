import Foundation

// iosApp/iosApp/ring-history.swift の単体テスト。シミュレータ無しで macOS 上で動かす:
//   bash scripts/ci.sh ios-logic
// (アプリの他の型や SharedKit に依存しないよう、計算は ring-history.swift に切り出してある)

var failures = 0
func check(_ cond: Bool, _ name: String) {
    if cond { print("ok   \(name)") } else { print("FAIL \(name)"); failures += 1 }
}

let hour: Int64 = 60 * 60 * 1000
let now: Int64 = 1_792_000_000_000

func entry(_ id: Int64, _ type: String, at: Int64, instance: String? = nil, trigger: Int64? = nil) -> AuditEntry {
    AuditEntry(id: id, atMillis: at, type: type, detail: "d\(id)", instanceId: instance, triggerAtMillis: trigger, title: "t\(id)")
}

// 鳴動の結果だけを足し、7日より古い記録を落とす
do {
    var h: [AuditEntry] = [entry(1, "DISMISS", at: now - 8 * 24 * hour)]
    h = RingHistory.appending(h, entry(2, "FIRED", at: now))
    h = RingHistory.appending(h, entry(3, "RESYNC", at: now))
    check(h.map(\.id) == [1], "鳴った・再同期は記録に足さない")
    h = RingHistory.appending(h, entry(4, "SNOOZE", at: now))
    check(h.map(\.id) == [4], "結果を足すと7日より古い記録が落ちる")
    h = RingHistory.appending(h, entry(5, "DISMISS_OFFAPP", at: now + 1))
    h = RingHistory.appending(h, entry(6, "MISSED", at: now + 2))
    check(RingHistory.newestFirst(h, limit: 2).map(\.id) == [6, 5], "新しい順に件数で切る")
}

// 見逃しの数は鳴るはずだった時刻で数え、同じ予約は1件にする (Android と同じ)
do {
    let h = [
        entry(1, "MISSED", at: now, instance: "x", trigger: now - 2 * hour),
        entry(2, "MISSED", at: now, instance: "x", trigger: now - 2 * hour),
        // 30時間前に鳴るはずだったのを今見つけた: 24時間の外
        entry(3, "MISSED", at: now, instance: "y", trigger: now - 30 * hour),
        entry(4, "DISMISS", at: now, instance: "z", trigger: now - hour),
    ]
    check(RingHistory.recentMissedCount(h, nowMillis: now) == 1, "鳴動時刻で数え、同じ予約は1件")
    let list = RingHistory.recentMissed(h + [entry(5, "MISSED", at: now, instance: "w", trigger: now - hour)], nowMillis: now)
    check(list.map { $0.instanceId ?? "" } == ["w", "x"], "バナーの一覧は件数と同じ記録を鳴動時刻の新しい順に出す")
}

// 前の版の保存データ (記録が無い) から作り直す
do {
    let rowDetail = "朝 @10/21 06:40"
    func legacyMissed(_ id: Int64, at: Int64, detail: String) -> AuditEntry {
        AuditEntry(id: id, atMillis: at, type: "MISSED", detail: detail)
    }
    let audit = [
        entry(10, "FIRED", at: now - 3 * hour),
        entry(11, "DISMISS", at: now - 3 * hour),
        legacyMissed(12, at: now - 2 * hour, detail: rowDetail),          // 今ある行と同じ見逃し (前の版の行)
        legacyMissed(14, at: now - hour, detail: "夜 @10/21 22:00"),      // 行がもう無い見逃し
        entry(15, "MISSED", at: now - hour, instance: "sa:1:2026-10-21", trigger: now - 2 * hour), // 行と同じ予約
        entry(16, "MISSED", at: now - hour, instance: "gone", trigger: now - hour),                 // 行がもう無い予約
        entry(13, "SNOOZE", at: now - 9 * 24 * hour),
    ]
    let seeded = RingHistory.seed(
        audit: audit,
        missed: [(id: "sa:1:2026-10-21", triggerAtMillis: now - 2 * hour, title: "朝", detail: rowDetail)],
        nowMillis: now)
    check(seeded.map(\.type) == ["DISMISS", "MISSED", "MISSED", "MISSED"],
          "停止は監査ログから、鳴らずは行と監査ログから作り、古い記録は落として時刻順に並べる")
    check(RingHistory.recentMissedCount(seeded, nowMillis: now) == 3,
          "行と同じ見逃しは二重に数えず、行がもう無い見逃し (id あり・なし) は残す")
    check(seeded.filter { $0.type == "MISSED" && $0.id < 0 }.first?.title == "朝", "予約の行由来の記録は負の id とタイトルを持つ")
    // 同じ detail の行が2つあっても、監査ログの前の版の見逃しは1行につき1件だけ消す
    let twice = RingHistory.seed(
        audit: [legacyMissed(20, at: now - hour, detail: "同 @10/21 07:00"), legacyMissed(21, at: now - hour, detail: "同 @10/21 07:00")],
        missed: [(id: "a", triggerAtMillis: now - hour, title: "同", detail: "同 @10/21 07:00")],
        nowMillis: now)
    check(twice.count == 2, "前の版の見逃しは行1つにつき1件だけ消し、残りは残す")
    // 端末のタイムゾーンが変わって、記録した時の detail と今の行の detail の時刻が違っても二重に数えない
    let zoneChanged = RingHistory.seed(
        audit: [legacyMissed(22, at: now - hour, detail: "朝 @10/21 07:00")],
        missed: [(id: "tz", triggerAtMillis: now - 2 * hour, title: "朝", detail: "朝 @10/20 15:00")],
        nowMillis: now)
    check(RingHistory.recentMissedCount(zoneChanged, nowMillis: now) == 1, "タイムゾーンが変わって detail がずれても、同じ見逃しは1件")
    // 別のタイトルや、行より前に記録された見逃しは、同じ見逃しとして消さない
    let unrelated = RingHistory.seed(
        audit: [legacyMissed(23, at: now - hour, detail: "昼 @10/21 12:00"), legacyMissed(24, at: now - 5 * hour, detail: "朝 @10/20 15:00")],
        missed: [(id: "tz2", triggerAtMillis: now - 2 * hour, title: "朝", detail: "朝 @10/21 07:00")],
        nowMillis: now)
    check(unrelated.count == 3, "タイトルが違う・行より前の記録は別の見逃しとして残す")
}

if failures > 0 {
    print("\(failures) failure(s)")
    exit(1)
}
print("all passed")
