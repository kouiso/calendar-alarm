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

// 前の版の保存データ (記録が無い) から作り直す。detail の時刻は記録した時の端末のタイムゾーンの書き方なので、
// 試験では端末のタイムゾーンを引数で渡して固定する
do {
    let tokyo = TimeZone(identifier: "Asia/Tokyo")!
    let losAngeles = TimeZone(identifier: "America/Los_Angeles")!
    /// 前の版の Engine が書いた MISSED の detail と同じ書式「タイトル @M/d HH:mm」
    func det(_ title: String, _ millis: Int64, zone: TimeZone = tokyo) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = zone
        f.dateFormat = "M/d HH:mm"
        return "\(title) @\(f.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000)))"
    }
    func seed(_ audit: [AuditEntry], _ rows: [(id: String, triggerAtMillis: Int64, title: String, detail: String)],
              at nowMillis: Int64 = now, titles: [String: String] = [:]) -> [AuditEntry] {
        RingHistory.seed(audit: audit, missed: rows, nowMillis: nowMillis, deviceZone: tokyo, titleById: titles)
    }
    func legacyMissed(_ id: Int64, at: Int64, detail: String) -> AuditEntry {
        AuditEntry(id: id, atMillis: at, type: "MISSED", detail: detail)
    }
    let rowDetail = det("朝", now - 2 * hour)
    let audit = [
        entry(10, "FIRED", at: now - 3 * hour),
        entry(11, "DISMISS", at: now - 3 * hour),
        legacyMissed(12, at: now - 2 * hour, detail: rowDetail),                    // 今ある行と同じ見逃し (前の版の行)
        legacyMissed(14, at: now - hour, detail: det("夜", now - 3 * hour)),         // 行がもう無い見逃し
        entry(15, "MISSED", at: now - hour, instance: "sa:1:2026-10-21", trigger: now - 2 * hour), // 行と同じ予約
        entry(16, "MISSED", at: now - hour, instance: "gone", trigger: now - hour),                 // 行がもう無い予約
        entry(13, "SNOOZE", at: now - 9 * 24 * hour),
    ]
    let seeded = seed(audit, [(id: "sa:1:2026-10-21", triggerAtMillis: now - 2 * hour, title: "朝", detail: rowDetail)])
    check(seeded.map(\.type) == ["DISMISS", "MISSED", "MISSED", "MISSED"],
          "停止は監査ログから、鳴らずは行と監査ログから作り、古い記録は落として時刻順に並べる")
    check(RingHistory.recentMissedCount(seeded, nowMillis: now) == 3,
          "行と同じ見逃しは二重に数えず、行がもう無い見逃し (id あり・なし) は残す")
    check(seeded.filter { $0.type == "MISSED" && $0.id < 0 }.first?.title == "朝", "予約の行由来の記録は負の id とタイトルを持つ")
    // 同じ detail の行が2つあっても、監査ログの前の版の見逃しは1行につき1件だけ消す
    let sameDetail = det("同", now - hour)
    let twice = seed([legacyMissed(20, at: now - hour, detail: sameDetail), legacyMissed(21, at: now - hour, detail: sameDetail)],
                     [(id: "a", triggerAtMillis: now - hour, title: "同", detail: sameDetail)])
    check(twice.count == 2, "前の版の見逃しは行1つにつき1件だけ消し、残りは残す")
    // 端末のタイムゾーンが変わって、記録した時の detail と今の行の detail の時刻が違っても二重に数えない
    let zoneChanged = seed([legacyMissed(22, at: now - hour, detail: det("朝", now - 2 * hour, zone: losAngeles))],
                           [(id: "tz", triggerAtMillis: now - 2 * hour, title: "朝", detail: det("朝", now - 2 * hour))])
    check(RingHistory.recentMissedCount(zoneChanged, nowMillis: now) == 1, "タイムゾーンが変わって detail がずれても、同じ見逃しは1件")
    // 別のタイトルや、行より前に記録された見逃しは、同じ見逃しとして消さない
    let unrelated = seed([legacyMissed(23, at: now - hour, detail: det("昼", now - 2 * hour)),
                          legacyMissed(24, at: now - 5 * hour, detail: det("朝", now - 6 * hour))],
                         [(id: "tz2", triggerAtMillis: now - 2 * hour, title: "朝", detail: det("朝", now - 2 * hour))])
    check(unrelated.count == 3, "タイトルが違う・行より前の記録は別の見逃しとして残す")

    // 行の無い前の版の見逃しは、見つけた時刻ではなく detail の鳴動時刻で数える
    let late = seed([legacyMissed(30, at: now - hour, detail: det("遅", now - 40 * hour))], [])
    check(late.count == 1 && RingHistory.recentMissedCount(late, nowMillis: now) == 0,
          "1時間前に見つけた40時間前の見逃しは、直近24時間に数えない")
    let lateTrigger = late.first?.triggerAtMillis ?? 0
    check(abs(lateTrigger - (now - 40 * hour)) < 60_000, "鳴動時刻は detail の時刻 (分まで) で埋める")
    // 年をまたぐ: 年は detail に無いので、見つけた時刻に近い年で補う
    let newYear = ISO8601DateFormatter().date(from: "2026-12-31T15:30:00Z").map { Int64($0.timeIntervalSince1970 * 1000) } ?? 0
    let beforeYear = seed([legacyMissed(31, at: newYear, detail: "年 @12/31 22:00")], [], at: newYear)
    let expected = ISO8601DateFormatter().date(from: "2026-12-31T13:00:00Z").map { Int64($0.timeIntervalSince1970 * 1000) } ?? -1
    check(beforeYear.first?.triggerAtMillis == expected, "年をまたいでも前の年の12月として読む")
    // 読めない書式でも落とさず、鳴動時刻なしで残す (記録した時刻で数える)
    let odd = seed([legacyMissed(32, at: now - hour, detail: "書式が違う")], [])
    check(odd.count == 1 && odd.first?.triggerAtMillis == nil, "detail が読めない見逃しは落とさず残す")
    // 同じタイトルの別の見逃しを、離れた行と取り違えて消さない
    let farRow = seed([legacyMissed(33, at: now - hour, detail: det("X", now - 2 * hour))],
                      [(id: "far", triggerAtMillis: now - 72 * hour, title: "X", detail: det("X", now - 72 * hour))])
    check(farRow.count == 2 && RingHistory.recentMissedCount(farRow, nowMillis: now) == 1,
          "同じタイトルでも72時間前の行と2時間前の見逃しは別の見逃し")
    // 同じタイトルの行が複数あれば、detail の時刻に最も近い行と対応づける
    let two = seed([legacyMissed(34, at: now - hour, detail: det("日課", now - 2 * hour))],
                   [(id: "d1", triggerAtMillis: now - 26 * hour, title: "日課", detail: det("日課", now - 26 * hour)),
                    (id: "d2", triggerAtMillis: now - 2 * hour, title: "日課", detail: det("日課", now - 2 * hour))])
    check(two.count == 2 && two.contains { $0.instanceId == "d1" } && two.contains { $0.instanceId == "d2" },
          "同じタイトルの行が2つなら近い方と対応づけ、行は2件とも残る")
    // 前の版の記録にはタイトルの欄が無い。画面はタイトルだけを出すので、分かるものは引き継ぎで埋める
    func legacy(_ id: Int64, _ type: String, _ detail: String) -> AuditEntry {
        AuditEntry(id: id, atMillis: now - hour, type: type, detail: detail)
    }
    let titled = seed([legacy(40, "DISMISS", "朝"), legacy(41, "DISMISS_OFFAPP", "昼"),
                       legacy(42, "SNOOZE", "sa:9:2026-10-21 +5m (native)"), legacy(43, "SNOOZE", "gone:1 (native)"),
                       legacyMissed(44, at: now - hour, detail: det("夜", now - 3 * hour))],
                      [], titles: ["sa:9:2026-10-21": "スヌーズ中"])
    check(titled.map { $0.title ?? "-" } == ["朝", "昼", "スヌーズ中", "-", "夜"],
          "停止は detail、スヌーズは予約の行、鳴らずは detail からタイトルを埋め、分からないものは空のまま")
}

if failures > 0 {
    print("\(failures) failure(s)")
    exit(1)
}
print("all passed")
