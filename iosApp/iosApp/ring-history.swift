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
        recentMissed(history, nowMillis: nowMillis).count
    }

    /// 数えた見逃しそのもの (予定画面のバナーから開く一覧)。予約ごとに最初の記録を残し、鳴動時刻の新しい順
    static func recentMissed(_ history: [AuditEntry], nowMillis: Int64) -> [AuditEntry] {
        let since = nowMillis - missedWindowMillis
        var seen = Set<String>()
        return history
            .filter { $0.type == "MISSED" && ($0.triggerAtMillis ?? $0.atMillis) >= since }
            .filter { seen.insert($0.instanceId ?? "audit:\($0.id)").inserted }
            .sorted { ($0.triggerAtMillis ?? $0.atMillis) > ($1.triggerAtMillis ?? $1.atMillis) }
    }

    /// 新しい順の結果 (設定の「最近の鳴動」と「すべて見る」)
    static func newestFirst(_ history: [AuditEntry], limit: Int) -> [AuditEntry] {
        Array(history.reversed().prefix(limit))
    }

    /// 前の版の detail の時刻は、記録した時のタイムゾーンの書き方。今のタイムゾーンで読んだ時刻 (parsed) と
    /// 予約の行の鳴動時刻 (rowTrigger) の差は、「記録した時のタイムゾーンの UTC からの差 - 今の差」に等しい。
    /// その「記録した時のタイムゾーン」が実在する差 (UTC-12 から UTC+14、15分刻み) に収まる時だけ、
    /// タイムゾーンが変わっただけの同じ見逃しとみなす。
    /// 日課のように同じタイトルで24時間ずれた別の日の見逃しは、実在するタイムゾーンでは説明できないので同じとみなさない
    static func isPlausibleZoneShift(rowTrigger: Int64, parsed: Int64, deviceZone: TimeZone) -> Bool {
        // detail は分までしか持たないので、行の鳴動時刻も分に切り下げて比べる
        let rowMinute = rowTrigger - rowTrigger % 60_000
        let shift = parsed - rowMinute
        guard shift % (15 * 60 * 1000) == 0 else { return false }
        let current = deviceZone.secondsFromGMT(for: Date(timeIntervalSince1970: TimeInterval(rowTrigger) / 1000))
        let recorded = current + Int(shift / 1000)
        return recorded >= -12 * 3600 && recorded <= 14 * 3600
    }

    /// 前の版の MISSED の detail は「タイトル @M/d HH:mm」で、後ろは鳴るはずだった時刻。
    /// 前の版の記録には鳴動時刻の欄が無いので、ここから読む。年は書いていないので記録した時刻に最も近い年で補う。
    /// 読めない書式なら nil
    static func legacyMissed(_ entry: AuditEntry, zone: TimeZone) -> (title: String, triggerAtMillis: Int64)? {
        guard let marker = entry.detail.range(of: " @", options: .backwards) else { return nil }
        let title = String(entry.detail[..<marker.lowerBound])
        let parts = entry.detail[marker.upperBound...].split(separator: " ")
        guard parts.count == 2 else { return nil }
        let md = parts[0].split(separator: "/").compactMap { Int($0) }
        let hm = parts[1].split(separator: ":").compactMap { Int($0) }
        guard md.count == 2, hm.count == 2 else { return nil }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        let year = cal.component(.year, from: Date(timeIntervalSince1970: TimeInterval(entry.atMillis) / 1000))
        let candidates: [Int64] = [year - 1, year, year + 1].compactMap { y in
            cal.date(from: DateComponents(year: y, month: md[0], day: md[1], hour: hm[0], minute: hm[1]))
                .map { Int64($0.timeIntervalSince1970 * 1000) }
        }
        guard let nearest = candidates.min(by: { abs($0 - entry.atMillis) < abs($1 - entry.atMillis) }) else { return nil }
        return (title, nearest)
    }

    /// 前の版の記録にはタイトルの欄が無い。停止は detail がタイトルそのもの、鳴らずは「タイトル @時刻」、
    /// スヌーズは先頭が予約の id なので、残っている予約の行から引く。分からなければ nil
    static func legacyTitle(_ entry: AuditEntry, titleById: [String: String], deviceZone: TimeZone) -> String? {
        switch entry.type {
        case "DISMISS", "DISMISS_OFFAPP":
            return entry.detail.isEmpty ? nil : entry.detail
        case "MISSED":
            return legacyMissed(entry, zone: deviceZone)?.title
        case "SNOOZE":
            return entry.detail.split(separator: " ").first.flatMap { titleById[String($0)] }
        default:
            return nil
        }
    }

    /// 前の版で保存した状態には記録が無い。鳴らずは残っている予約の行と監査ログから、
    /// 停止とスヌーズは監査ログから作り直す (見逃しの数が更新直後に 0 に戻らないように)。
    /// 予約の行は単発アラームを使い直すと消えるので、行の無い見逃しは監査ログの MISSED で残す。
    /// 監査ログの MISSED が今ある行と同じ見逃しなら二重に数えない
    /// (予約の id があればそれで、前の版の行は id が無いので detail が同じものを1行につき1件だけ。
    /// 端末のタイムゾーンが変わって detail の時刻がずれていても、タイトルが同じで、ずれが実在するタイムゾーンの差で
    /// 説明でき、detail の時刻が行の鳴動時刻に最も近いものを1行につき1件だけ。説明できない曖昧なものは別の見逃しとして残す)。
    /// 前の版の MISSED には鳴動時刻の欄が無いので、残すものは detail の時刻から埋める
    /// (記録した時刻で数えると、何日も前の見逃しを「直近」と言ってしまう)
    static func seed(audit: [AuditEntry], missed: [(id: String, triggerAtMillis: Int64, title: String, detail: String)],
                     nowMillis: Int64, deviceZone: TimeZone = .current, titleById: [String: String] = [:]) -> [AuditEntry] {
        let cutoff = nowMillis - retentionMillis
        // 予約の行由来の記録には監査ログの id が無いので、ぶつからない負の id を振る
        let fromRows = missed.sorted { $0.triggerAtMillis < $1.triggerAtMillis }.enumerated().map { i, m in
            AuditEntry(id: -Int64(i + 1), atMillis: m.triggerAtMillis, type: "MISSED", detail: m.detail,
                       instanceId: m.id, triggerAtMillis: m.triggerAtMillis, title: m.title)
        }
        let rowIds = Set(missed.map(\.id))
        // 前の版の監査ログの MISSED (予約の id が無い) を、同じ見逃しの予約の行と1対1で対応づけて二重に数えない。
        // まず detail が同じもの、次に端末のタイムゾーンが変わって detail の時刻の書き方がずれたものを
        // 「タイトルが同じで、detail の時刻が行の鳴動時刻に最も近いもの」で対応づける
        var unmatchedRows = fromRows
        var consumed = Set<Int>()
        let legacy = audit.indices.filter { audit[$0].type == "MISSED" && audit[$0].instanceId == nil }
        for i in legacy {
            guard let r = unmatchedRows.firstIndex(where: { $0.detail == audit[i].detail }) else { continue }
            unmatchedRows.remove(at: r)
            consumed.insert(i)
        }
        var legacyTriggers: [Int: Int64] = [:]
        for i in legacy where !consumed.contains(i) {
            guard let parsed = legacyMissed(audit[i], zone: deviceZone) else { continue }
            legacyTriggers[i] = parsed.triggerAtMillis
            let candidates = unmatchedRows.indices.filter { r in
                let row = unmatchedRows[r]
                return row.title == parsed.title
                    && row.atMillis <= audit[i].atMillis
                    && isPlausibleZoneShift(rowTrigger: row.atMillis, parsed: parsed.triggerAtMillis, deviceZone: deviceZone)
            }
            guard let r = candidates.min(by: {
                abs(unmatchedRows[$0].atMillis - parsed.triggerAtMillis) < abs(unmatchedRows[$1].atMillis - parsed.triggerAtMillis)
            }) else { continue }
            unmatchedRows.remove(at: r)
            consumed.insert(i)
        }
        let fromAudit: [AuditEntry] = audit.enumerated().compactMap { i, entry in
            guard types.contains(entry.type) else { return nil }
            if entry.type == "MISSED", let instanceId = entry.instanceId, rowIds.contains(instanceId) { return nil }
            if entry.type == "MISSED", entry.instanceId == nil, consumed.contains(i) { return nil }
            var kept = entry
            if entry.type == "MISSED", entry.instanceId == nil {
                kept.triggerAtMillis = legacyTriggers[i]
            }
            if kept.title == nil { kept.title = legacyTitle(entry, titleById: titleById, deviceZone: deviceZone) }
            return kept
        }
        return (fromAudit + fromRows)
            .filter { $0.atMillis >= cutoff }
            .sorted { $0.atMillis < $1.atMillis }
    }
}
