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

/// Models.kt EventType (誕生日/不在/勤務場所/タスク/予定)。
enum EventType: String, Codable, CaseIterable {
    case birthday = "BIRTHDAY", absence = "ABSENCE", workplace = "WORKPLACE", task = "TASK", event = "EVENT"
    var label: String {
        switch self {
        case .birthday: return "誕生日"
        case .absence: return "不在"
        case .workplace: return "勤務場所"
        case .task: return "タスク"
        case .event: return "予定"
        }
    }
}

/// Models.kt classifyEventType と同じ判定順・語彙 (タイトル文字列による分類)。
func classifyEventType(_ title: String) -> EventType {
    let t = title.lowercased()
    func hit(_ needles: [String]) -> Bool { needles.contains { t.contains($0) } }
    if hit(["誕生日","バースデー","birthday"]) { return .birthday }
    if hit(["不在","休暇","休み","有休","欠勤","absence","absent","away","out of office"]) { return .absence }
    if hit(["勤務場所","出社","在宅勤務","リモートワーク","workplace","work location","office"]) { return .workplace }
    if hit(["タスク","todo","to-do","task"]) { return .task }
    return .event
}

/// Models.kt googleEventTypeToEventType と同じ写像 (Google Calendar API の生値→EventType)。
func googleEventTypeToEventType(_ googleType: String?) -> EventType? {
    switch googleType {
    case "birthday": return .birthday
    case "outOfOffice": return .absence
    case "workingLocation": return .workplace
    case "task": return .task
    case "default", "focusTime", "fromGmail": return .event
    default: return nil
    }
}

/// Models.kt resolveEventType と同じ優先順: API の正本があればそれ、なければタイトル判定。
func resolveEventType(_ ev: CalendarEventDTO) -> EventType {
    googleEventTypeToEventType(ev.googleEventType) ?? classifyEventType(ev.title)
}

/// Models.kt EventTypeFilter (false の種別は鳴らさない)。
struct EventTypeFilter: Codable, Hashable {
    var birthday: Bool = true
    var absence: Bool = true
    var workplace: Bool = true
    var task: Bool = true
    var event: Bool = true

    func allows(_ type: EventType) -> Bool {
        switch type {
        case .birthday: return birthday
        case .absence: return absence
        case .workplace: return workplace
        case .task: return task
        case .event: return event
        }
    }
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
    /// iCalendar UID (EKCalendarItem.calendarItemExternalIdentifier)。
    /// Google Calendar API の iCalUID 照合キー。
    var iCalUID: String? = nil
    /// Google Calendar API の eventType 生値 (連携取得できた時のみ)。
    var googleEventType: String? = nil

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

/// タイマープリセット (元アプリの定型タイマー)。
struct TimerPresetDTO: Codable, Hashable {
    var label: String
    /// 秒保持 (元アプリの 90秒休憩を潰さないため)。
    /// 永続化 JSON の旧 "minutes" 値はデコード時に×60で移行する
    var seconds: Int
    var minutes: Int { seconds / 60 }

    enum CodingKeys: String, CodingKey { case label, seconds, minutes }
    init(label: String, seconds: Int) {
        self.label = label
        self.seconds = seconds
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        label = try c.decode(String.self, forKey: .label)
        // 新形式は "seconds"、旧形式の "minutes" は×60で吸収
        if let s = try c.decodeIfPresent(Int.self, forKey: .seconds) {
            seconds = s
        } else {
            seconds = (try c.decodeIfPresent(Int.self, forKey: .minutes) ?? 0) * 60
        }
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(label, forKey: .label)
        try c.encode(seconds, forKey: .seconds)
    }
    /// "90秒" or "5分" の表示用
    var durationLabel: String {
        seconds % 60 == 0 ? "\(seconds / 60)分" : "\(seconds)秒"
    }
}

/// デフォルトの定型タイマー (Android DEFAULT_TIMER_PRESETS と同一)。
enum TimerPresets {
    static let defaults: [TimerPresetDTO] = [
        .init(label: "ゆで卵", seconds: 300),
        .init(label: "パスタ", seconds: 540),
        .init(label: "紅茶", seconds: 240),
        .init(label: "ピザ", seconds: 720),
        .init(label: "仮眠", seconds: 1200),
        .init(label: "集中", seconds: 1500),
        // 元アプリの 90秒休憩 (分保持では潰れるので秒保持必須)
        .init(label: "筋トレ休憩", seconds: 90),
    ]
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
    /// 「ロック解除までミュート」(Android 側のみ意味を持つフラグ)
    var muteUntilUnlock: Bool = false
    /// 繰返しモード (RepeatMode.name)。nil=旧形式 (daysOfWeek 空→ONCE, 非空→WEEKLY)
    var repeatMode: String? = nil
    /// INTERVAL_* の間隔
    var repeatInterval: Int = 1
    /// MONTHLY/INTERVAL_* の起点日 (UTC 0時 epoch millis)
    var repeatAnchorMillis: Int64? = nil

    /// 旧データを含む実効モード
    var effectiveRepeatMode: String { repeatMode ?? (daysOfWeek.isEmpty ? "ONCE" : "WEEKLY") }
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
    /// STANDALONE の「ロック解除までミュート」コピー (Android 専用)
    var muteUntilUnlock: Bool = false
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
    var eventTypeFilter: EventTypeFilter = EventTypeFilter()
    var importEventReminders: Bool = false
    /// 全アラームの一括ミュート (NOTIFY・タイマーには効かない)
    var muteAll: Bool = false
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

// MARK: - 後方互換デコード
// extension 内の init(from:) なので memberwise init は残る。
// 旧 store.json には新キーが無いため decodeIfPresent に倒す。

extension StandaloneAlarmDTO {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(Int64.self, forKey: .id) ?? 0
        enabled = try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? true
        hour = try c.decode(Int.self, forKey: .hour)
        minute = try c.decode(Int.self, forKey: .minute)
        daysOfWeek = try c.decodeIfPresent(Set<String>.self, forKey: .daysOfWeek) ?? []
        label = try c.decodeIfPresent(String.self, forKey: .label) ?? ""
        soundUri = try c.decodeIfPresent(String.self, forKey: .soundUri)
        snoozeMinutes = try c.decodeIfPresent(Int.self, forKey: .snoozeMinutes) ?? 10
        exceptions = try c.decodeIfPresent(Set<String>.self, forKey: .exceptions) ?? []
        muteUntilUnlock = try c.decodeIfPresent(Bool.self, forKey: .muteUntilUnlock) ?? false
        repeatMode = try c.decodeIfPresent(String.self, forKey: .repeatMode)
        repeatInterval = try c.decodeIfPresent(Int.self, forKey: .repeatInterval) ?? 1
        repeatAnchorMillis = try c.decodeIfPresent(Int64.self, forKey: .repeatAnchorMillis)
    }
}

extension AlarmInstanceDTO {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        triggerAtMillis = try c.decode(Int64.self, forKey: .triggerAtMillis)
        title = try c.decode(String.self, forKey: .title)
        kind = try c.decode(String.self, forKey: .kind)
        eventId = try c.decodeIfPresent(String.self, forKey: .eventId)
        standaloneAlarmId = try c.decodeIfPresent(Int64.self, forKey: .standaloneAlarmId)
        snoozeMinutes = try c.decodeIfPresent(Int.self, forKey: .snoozeMinutes) ?? 10
        soundUri = try c.decodeIfPresent(String.self, forKey: .soundUri)
        minutesBefore = try c.decodeIfPresent(Int.self, forKey: .minutesBefore) ?? 0
        eventStartMillis = try c.decodeIfPresent(Int64.self, forKey: .eventStartMillis)
        snoozeSeq = try c.decodeIfPresent(Int.self, forKey: .snoozeSeq) ?? 0
        delivery = try c.decodeIfPresent(String.self, forKey: .delivery) ?? EventAction.alarm.rawValue
        muteUntilUnlock = try c.decodeIfPresent(Bool.self, forKey: .muteUntilUnlock) ?? false
    }
}
