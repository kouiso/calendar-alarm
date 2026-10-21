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
    let audit = [
        entry(10, "FIRED", at: now - 3 * hour),
        entry(11, "DISMISS", at: now - 3 * hour),
        entry(12, "MISSED", at: now - hour),
        entry(13, "SNOOZE", at: now - 9 * 24 * hour),
    ]
    let seeded = RingHistory.seed(
        audit: audit,
        missed: [(id: "sa:1:2026-10-21", triggerAtMillis: now - 2 * hour, title: "朝", detail: "朝 @10/21 06:40")],
        nowMillis: now)
    check(seeded.map(\.type) == ["DISMISS", "MISSED"], "停止は監査ログから、鳴らずは予約の行から作り、古い記録は落として時刻順に並べる")
    check(RingHistory.recentMissedCount(seeded, nowMillis: now) == 1, "更新直後も見逃しが 0 に戻らない")
    check(seeded.last?.title == "朝" && (seeded.last?.id ?? 0) < 0, "予約の行由来の記録は負の id とタイトルを持つ")
}

if failures > 0 {
    print("\(failures) failure(s)")
    exit(1)
}
print("all passed")
