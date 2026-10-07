import Foundation

// Kotlin shared (Models.kt / SharedBridge.kt) の JSON と完全一致する Codable モデル群。
// フィールド名・nullability は kotlinx.serialization の出力に合わせる。

// MARK: - ドメイン (shared JSON)

/// Models.kt EventAction。予定トリガの配信方法。
enum EventAction: String, Codable, Hashable, CaseIterable {
    case alarm = "ALARM"
    case notify = "NOTIFY"
    case mute = "MUTE"
}

/// Models.kt InviteStatus。招待予定の自分の参加可否。
enum InviteStatus: String, Codable, Hashable {
    case accepted = "ACCEPTED"
    case tentative = "TENTATIVE"
    case needsAction = "NEEDS_ACTION"
    case declined = "DECLINED"
}

/// Models.kt TitleCodeSettings。
struct TitleCodeSettings: Codable, Hashable {
    var alwaysCodes: [String] = []
    var neverCodes: [String] = []
    var applyToStart: Bool = true
    var applyToReminders: Bool = true
}

/// Models.kt InviteFilter。
struct InviteFilter: Codable, Hashable {
    var accepted: Bool = true
    var tentative: Bool = true
    var needsAction: Bool = true
    var declined: Bool = false

    func allows(_ status: String?) -> Bool {
        guard let raw = status, let s = InviteStatus(rawValue: raw) else { return true }
        switch s {
        case .accepted: return accepted
        case .tentative: return tentative
        case .needsAction: return needsAction
        case .declined: return declined
        }
    }
}

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
    /// 招待ステータス (自分主催の予定は null = フィルタ対象外)
    var inviteStatus: String? = nil
    /// カレンダー側に登録されたリマインダーの分数
    var calendarReminderMinutes: [Int] = []

    /// Models.kt の instanceKey と同じ規則 ("calendarId:id:startMillis")
    var instanceKey: String { "\(calendarId):\(id):\(startMillis)" }
}

struct AlarmRuleDTO: Codable, Hashable {
    var enabled: Bool = true
    var minutesBefore: Int = 0
    /// 終日イベントを鳴らす現地時刻 (0時からの分数)。負数 = 鳴らさない
    var allDayMinutes: Int = 540
    var extraOffsets: [Int] = []
    var startAction: String = EventAction.alarm.rawValue
    var reminderAction: String = EventAction.alarm.rawValue
}

struct EventOverrideDTO: Codable, Hashable {
    /// "ALARM"/"NOTIFY"/"MUTE"。null = 既定に従う
    var action: String? = nil
    var minutesBefore: Int? = nil
    var extraOffsets: [Int]? = nil

    var muted: Bool { action == EventAction.mute.rawValue }

    /// 旧形式 {muted: Bool} の store.json からの移行 (muted=true → "MUTE")
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        if let a = try c.decodeIfPresent(String.self, forKey: .action) {
            action = a
        } else if try c.decodeIfPresent(Bool.self, forKey: .muted) == true {
            action = EventAction.mute.rawValue
        } else {
            action = nil
        }
        minutesBefore = try c.decodeIfPresent(Int.self, forKey: .minutesBefore)
        extraOffsets = try c.decodeIfPresent([Int].self, forKey: .extraOffsets)
    }
    init(action: String? = nil, minutesBefore: Int? = nil, extraOffsets: [Int]? = nil) {
        self.action = action
        self.minutesBefore = minutesBefore
        self.extraOffsets = extraOffsets
    }

    private enum CodingKeys: String, CodingKey {
        case action, minutesBefore, extraOffsets
        // 旧キー。書き出し時には含めない
        case muted
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encodeIfPresent(action, forKey: .action)
        try c.encodeIfPresent(minutesBefore, forKey: .minutesBefore)
        try c.encodeIfPresent(extraOffsets, forKey: .extraOffsets)
    }
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
    /// 配信方法 (ALARM=鳴動 / NOTIFY=通知のみ)
    var delivery: String = EventAction.alarm.rawValue
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
    var defaultStartAction: String = EventAction.alarm.rawValue
    var defaultReminderAction: String = EventAction.alarm.rawValue
    var titleCodes: TitleCodeSettings = TitleCodeSettings()
    var inviteFilter: InviteFilter = InviteFilter()
    var importEventReminders: Bool = false
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
