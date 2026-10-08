import Foundation
import EventKit
import UIKit

/// EventKit からカレンダー・イベントを読み取る。
/// iOS 17+ の full-access モデル (requestFullAccessToEvents) を使う。
final class EventKitReader {

    let store = EKEventStore()

    var authorizationGranted: Bool {
        EKEventStore.authorizationStatus(for: .event) == .fullAccess
    }

    @discardableResult
    func requestAccess() async -> Bool {
        if #available(iOS 17.0, *) {
            do { return try await store.requestFullAccessToEvents() }
            catch { return false }
        } else {
            return await withCheckedContinuation { c in
                store.requestAccess(to: .event) { ok, _ in c.resume(returning: ok) }
            }
        }
    }

    /// 端末に登録された全カレンダー。
    func calendars() -> [CalendarSource] {
        guard authorizationGranted else { return [] }
        return store.calendars(for: .event).map { cal in
            CalendarSource(
                id: cal.calendarIdentifier,
                name: cal.title,
                accountName: cal.source.title,
                color: Self.argb(from: cal.cgColor),
                isPrimary: cal.source.sourceType == .calDAV && cal.title.lowercased().contains("calendar")
            )
        }
    }

    /// [start, end) のイベントを個別回に展開して返す (EventKit が繰り返しを展開する)。
    func events(from start: Date, to end: Date) -> [CalendarEventDTO] {
        guard authorizationGranted else { return [] }
        let pred = store.predicateForEvents(withStart: start, end: end, calendars: nil)
        return store.events(matching: pred).compactMap { ev in
            guard let cal = ev.calendar else { return nil }
            let tzId = ev.isAllDay ? "UTC" : (ev.timeZone?.identifier ?? TimeZone.current.identifier)
            return CalendarEventDTO(
                id: ev.eventIdentifier ?? "\(cal.calendarIdentifier)-\(Int64(ev.startDate.timeIntervalSince1970))",
                calendarId: cal.calendarIdentifier,
                title: ev.title ?? "(無題)",
                description: ev.notes ?? "",
                location: ev.location ?? "",
                startMillis: Int64(ev.startDate.timeIntervalSince1970 * 1000),
                endMillis: Int64((ev.endDate ?? ev.startDate).timeIntervalSince1970 * 1000),
                allDay: ev.isAllDay,
                timezone: tzId,
                inviteStatus: Self.inviteStatus(of: ev),
                calendarReminderMinutes: Self.reminderMinutes(of: ev),
                // Google Calendar API の iCalUID と照合するための外部UID
                iCalUID: ev.calendarItemExternalIdentifier
            )
        }
    }

    /// メール→予定: 抽出された予定をデフォルトの書き込み可カレンダーへ挿入。
    /// allDay は日付のみのローカル時刻で入れる (EventKit が allDay として保存)。
    /// 成功なら挿入したイベントの identifier、失敗なら nil。
    @discardableResult
    func insert(title: String, startIso: String, endIso: String?,
                allDay: Bool, location: String?, notes: String?) -> String? {
        guard authorizationGranted,
              let cal = store.defaultCalendarForNewEvents else { return nil }
        let ev = EKEvent(eventStore: store)
        ev.title = title
        ev.calendar = cal
        ev.location = location
        ev.notes = notes
        ev.isAllDay = allDay
        if allDay {
            let fmt = DateFormatter()
            fmt.dateFormat = "yyyy-MM-dd"
            // 展開側の契約 (allDay は UTC の日解釈) に合わせる。
            // ローカル時刻で保存すると西寄りTZで前日に鳴る
            fmt.timeZone = TimeZone(secondsFromGMT: 0)
            guard let s = fmt.date(from: startIso) else { return nil }
            ev.startDate = s
            ev.endDate = endIso.flatMap { fmt.date(from: $0) } ?? s.addingTimeInterval(86400)
        } else {
            let fmt = DateFormatter()
            fmt.dateFormat = "yyyy-MM-dd'T'HH:mm"
            fmt.timeZone = TimeZone.current
            guard let s = fmt.date(from: startIso) else { return nil }
            ev.startDate = s
            ev.endDate = endIso.flatMap { fmt.date(from: $0) } ?? s.addingTimeInterval(3600)
        }
        do {
            try store.save(ev, span: .thisEvent)
            return ev.eventIdentifier
        } catch {
            return nil
        }
    }

    /// 自分の参加可否 → InviteStatus。自分主催の予定は nil (フィルタ対象外)。
    private static func inviteStatus(of ev: EKEvent) -> String? {
        // 主催者が自分なら招待ではない
        if ev.organizer?.isCurrentUser == true { return nil }
        guard let me = ev.attendees?.first(where: { $0.isCurrentUser }) else { return nil }
        switch me.participantStatus {
        case .accepted: return InviteStatus.accepted.rawValue
        case .tentative: return InviteStatus.tentative.rawValue
        case .declined: return InviteStatus.declined.rawValue
        case .pending: return InviteStatus.needsAction.rawValue
        default: return nil
        }
    }

    /// カレンダー側アラーム → 開始N分前の分数一覧。
    private static func reminderMinutes(of ev: EKEvent) -> [Int] {
        (ev.alarms ?? []).compactMap { alarm in
            let seconds = alarm.relativeOffset
            // relativeOffset は開始前が負値。絶対時刻指定のアラームは拾わない
            // (absoluteDate 形式を minutes に倒すとイベント開始との差が必要で
            //  読み取り時に毎回計算するより、近似は残すなら正確に残すべき)。
            guard seconds < 0 else { return nil }
            return Int(-seconds / 60)
        }
    }

#if DEBUG
    /// 検証用のデモ予定を端末カレンダーへ書き込む (DEBUG ビルド限定)。
    /// ローカルソースの書き込み可能カレンダーを使い、無ければ新規作成する。
    @discardableResult
    func seedDemoEvents() -> Int {
        guard authorizationGranted else { return -1 }
        let cal: EKCalendar
        if let existing = store.calendars(for: .event).first(where: { $0.title == "デモ" && $0.allowsContentModifications }) {
            cal = existing
        } else if let local = store.sources.first(where: { $0.sourceType == .local }) {
            let c = EKCalendar(for: .event, eventStore: store)
            c.title = "デモ"
            c.source = local
            c.cgColor = UIColor.systemIndigo.cgColor
            do { try store.saveCalendar(c, commit: true); cal = c }
            catch { return -2 }
        } else { return -3 }

        let now = Date()
        var defs: [(String, Date, Date, Bool)] = [
            ("テスト発火予定", now.addingTimeInterval(150), now.addingTimeInterval(150 + 1800), false),
            ("厩舎ミーティング", now.addingTimeInterval(3600 * 3), now.addingTimeInterval(3600 * 4), false),
            ("歯科検診", now.addingTimeInterval(86400 * 2 + 3600 * 8), now.addingTimeInterval(86400 * 2 + 3600 * 9), false),
        ]
        // 終日予定 (休日) は明日
        if let tomorrow = Calendar.current.date(byAdding: .day, value: 1, to: Calendar.current.startOfDay(for: now)) {
            defs.append(("休日", tomorrow, Calendar.current.date(byAdding: .day, value: 1, to: tomorrow)!, true))
        }
        var count = 0
        for (title, s, e, allDay) in defs {
            let ev = EKEvent(eventStore: store)
            ev.title = title
            ev.startDate = s
            ev.endDate = e
            ev.isAllDay = allDay
            ev.calendar = cal
            do { try store.save(ev, span: .thisEvent); count += 1 } catch {}
        }
        return count
    }
#endif

    private static func argb(from cg: CGColor?) -> Int {
        guard let comps = cg?.components, comps.count >= 3 else { return 0xFF888888 }
        let a = comps.count >= 4 ? comps[3] : 1.0
        return (Int(a * 255) << 24) | (Int(comps[0] * 255) << 16) | (Int(comps[1] * 255) << 8) | Int(comps[2] * 255)
    }
}
