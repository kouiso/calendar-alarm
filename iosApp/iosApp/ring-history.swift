import Foundation

/// 監査ログの1行。停止・スヌーズ・鳴らずの行だけ、どの予約の何時の鳴動かを持つ。
/// 予約の行は単発アラームを使い直すと消えるので、見逃しの数と「最近の鳴動」をこの記録だけで出す。
struct AuditEntry: Codable, Identifiable, Hashable {
    var id: Int64
    var atMillis: Int64
    var type: String
    var detail: String
    var instanceId: String? = nil
    var triggerAtMillis: Int64? = nil
    var title: String? = nil
}

/// 鳴動の結果の記録 (#51 PR3)。Store から切り離して、UI も SharedKit も無しで単体テストする。
/// 数え方は Android (鳴動ログの MISS 行を鳴動時刻で数える) と同じ。
enum RingHistory {
    /// 停止 (アプリ内 / アラームの画面) ・スヌーズ・鳴らず。鳴った (FIRED) は必ずどれかと対になるので持たない
    static let types: Set<String> = ["DISMISS", "DISMISS_OFFAPP", "SNOOZE", "MISSED"]
    static let retentionMillis: Int64 = 7 * 24 * 60 * 60 * 1000
    /// shared の RingStatus.MISSED_WINDOW_MILLIS と同じ
    static let missedWindowMillis: Int64 = 24 * 60 * 60 * 1000

    /// 鳴動の結果なら足して、7日より古い記録を落とす。結果でない種類はそのまま返す
    static func appending(_ history: [AuditEntry], _ entry: AuditEntry) -> [AuditEntry] {
        guard types.contains(entry.type) else { return history }
        let cutoff = entry.atMillis - retentionMillis
        return (history + [entry]).filter { $0.atMillis >= cutoff }
    }

    /// 鳴動時刻が直近24時間の「鳴らず」を予約ごとに1件で数える。
    /// 見つけた時刻ではなく鳴るはずだった時刻で数える (何日も後で見つけた見逃しを「直近」と言わない)
    static func recentMissedCount(_ history: [AuditEntry], nowMillis: Int64) -> Int {
        let since = nowMillis - missedWindowMillis
        let ids = history
            .filter { $0.type == "MISSED" && ($0.triggerAtMillis ?? $0.atMillis) >= since }
            .map { $0.instanceId ?? "audit:\($0.id)" }
        return Set(ids).count
    }

    /// 新しい順の結果 (設定の「最近の鳴動」と「すべて見る」)
    static func newestFirst(_ history: [AuditEntry], limit: Int) -> [AuditEntry] {
        Array(history.reversed().prefix(limit))
    }

    /// 前の版で保存した状態には記録が無い。鳴らずは残っている予約の行から、
    /// 停止とスヌーズは監査ログから作り直す (見逃しの数が更新直後に 0 に戻らないように)。
    /// 予約の行が既に消えた見逃しは作れない (前の版の数え方でも数えていなかった)。
    static func seed(audit: [AuditEntry], missed: [(id: String, triggerAtMillis: Int64, title: String, detail: String)],
                     nowMillis: Int64) -> [AuditEntry] {
        let cutoff = nowMillis - retentionMillis
        let fromAudit = audit.filter { types.contains($0.type) && $0.type != "MISSED" }
        // 予約の行由来の記録には監査ログの id が無いので、ぶつからない負の id を振る
        let fromRows = missed.sorted { $0.triggerAtMillis < $1.triggerAtMillis }.enumerated().map { i, m in
            AuditEntry(id: -Int64(i + 1), atMillis: m.triggerAtMillis, type: "MISSED", detail: m.detail,
                       instanceId: m.id, triggerAtMillis: m.triggerAtMillis, title: m.title)
        }
        return (fromAudit + fromRows)
            .filter { $0.atMillis >= cutoff }
            .sorted { $0.atMillis < $1.atMillis }
    }
}
