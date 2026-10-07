import Foundation

// Kotlin shared (Models.kt / SharedBridge.kt) の JSON と完全一致する Codable モデル群。
// フィールド名・nullability は kotlinx.serialization の出力に合わせる。

// MARK: - ドメイン (shared JSON)

struct CalendarEventDTO: Codable, Identifiable, Hashable {
    var id: String
    var calendarId: String
    var title: String
    var description: String = ""
    var location: String = ""
    var startMillis: Int64
    var endMillis: Int64
    var allDay: Bool
    var timezone: String? = nil

    /// Models.kt の instanceKey と同じ規則 ("calendarId:id:startMillis")
    var instanceKey: String { "\(calendarId):\(id):\(startMillis)" }
}

struct AlarmRuleDTO: Codable, Hashable {
    var enabled: Bool = true
    var minutesBefore: Int = 0
    /// 終日イベントを鳴らす現地時刻 (0時からの分数)。負数 = 鳴らさない
    var allDayMinutes: Int = 540
    var extraOffsets: [Int] = []
}

struct EventOverrideDTO: Codable, Hashable {
    var muted: Bool = false
    var minutesBefore: Int? = nil
    var extraOffsets: [Int]? = nil
}

struct StandaloneAlarmDTO: Codable, Identifiable, Hashable {
    var id: Int64 = 0
    var enabled: Bool = true
    var hour: Int
    var minute: Int
    /// kotlinx DayOfWeek 名: "SUNDAY".."SATURDAY"
    var daysOfWeek: Set<String> = []
    var label: String = ""
    var soundUri: String? = nil
    var snoozeMinutes: Int = 10
    /// "YYYY-MM-DD"
    var exceptions: Set<String> = []
}

struct AlarmInstanceDTO: Codable, Identifiable, Hashable {
    var id: String
    var triggerAtMillis: Int64
    var title: String
    var kind: String // "EVENT" | "STANDALONE" | "TIMER"
    var eventId: String? = nil
    var standaloneAlarmId: Int64? = nil
    var snoozeMinutes: Int = 10
    var soundUri: String? = nil
    var minutesBefore: Int = 0
    var eventStartMillis: Int64? = nil
    var snoozeSeq: Int = 0
}

// MARK: - SharedBridge ペイロード

struct ExpandRequest: Codable {
    var events: [CalendarEventDTO] = []
    var standalone: [StandaloneAlarmDTO] = []
    var calendarRules: [String: AlarmRuleDTO] = [:]
    var overrides: [String: EventOverrideDTO] = [:]
    var disabledCalendarIds: Set<String> = []
    var nowMillis: Int64
    var horizonMillis: Int64
    var zoneId: String
    var defaultMinutesBefore: Int = 0
    var defaultSnoozeMinutes: Int = 10
    var standaloneDays: Int = 14
}

struct ExpandResult: Codable {
    var instances: [AlarmInstanceDTO]
}

struct PlanRequest: Codable {
    var desired: [AlarmInstanceDTO]
    var scheduled: [String: Int64] = [:]
    var nowMillis: Int64
}

struct PlanResult: Codable {
    var toSchedule: [AlarmInstanceDTO]
    var toCancel: [String]
    var toFireNow: [AlarmInstanceDTO]
}

// MARK: - アプリ内永続化モデル

struct CalendarSource: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var accountName: String
    var color: Int
    var isPrimary: Bool = false
}

/// 予定一覧に並べる表示用イベント (Kotlin 側は expand 済み occurrence を期待するので
/// EventKit 側も events(matching:) が返す個別回をそのまま DTO 化する)
struct DisplayEvent: Identifiable, Hashable {
    var id: String { event.instanceKey }
    var event: CalendarEventDTO
    var calendarName: String
    var calendarColor: Int
    var muted: Bool
    var effectiveMinutes: Int
}

struct AuditEntry: Codable, Identifiable, Hashable {
    var id: Int64
    var atMillis: Int64
    var type: String
    var detail: String
}

enum InstanceState: String, Codable {
    case pending = "PENDING"
    case fired = "FIRED"
    case dismissed = "DISMISSED"
    case snoozed = "SNOOZED"
    case missed = "MISSED"
    case cancelled = "CANCELLED"
}

struct ScheduledRecord: Codable, Hashable {
    var instance: AlarmInstanceDTO
    var state: InstanceState
    var alarmKitId: String? = nil // AlarmKit UUID (iOS26 経路のみ)
}

// MARK: - JSON エンコード規約

enum Bridge {
    private static func makeEncoder() -> JSONEncoder {
        let e = JSONEncoder()
        return e
    }
    private static func makeDecoder() -> JSONDecoder {
        return JSONDecoder()
    }
    static func encode<T: Encodable>(_ v: T) -> String? {
        guard let data = try? makeEncoder().encode(v) else { return nil }
        return String(data: data, encoding: .utf8)
    }
    static func decode<T: Decodable>(_ type: T.Type, _ json: String?) -> T? {
        guard let json, let data = json.data(using: .utf8) else { return nil }
        return try? makeDecoder().decode(T.self, from: data)
    }
}
