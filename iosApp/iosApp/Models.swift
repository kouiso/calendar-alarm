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

/// Models.kt EventType (誕生日/不在/勤務場所/サイレントモード/タスク/予定)。
enum EventType: String, Codable, CaseIterable {
    case birthday = "BIRTHDAY", absence = "ABSENCE", workplace = "WORKPLACE", focusTime = "FOCUS_TIME", task = "TASK", event = "EVENT"
    var label: String {
        switch self {
        case .birthday: return "誕生日"
        case .absence: return "不在"
        case .workplace: return "勤務場所"
        case .focusTime: return "サイレントモード"
        case .task: return "タスク"
        case .event: return "予定"
        }
    }
}

/// Models.kt Availability (予定あり／予定なし)。仮 (tentative) は予定ありとして扱う。
enum Availability: String, Codable {
    case busy = "BUSY", free = "FREE", tentative = "TENTATIVE", unknown = "UNKNOWN"
}

/// Models.kt classifyEventType と同じ判定順・語彙 (タイトル文字列による分類)。
func classifyEventType(_ title: String) -> EventType {
    let t = title.lowercased()
    func hit(_ needles: [String]) -> Bool { needles.contains { t.contains($0) } }
    if hit(["誕生日","バースデー","birthday"]) { return .birthday }
    if hit(["不在","休暇","休み","有休","欠勤","absence","absent","away","out of office"]) { return .absence }
    if hit(["勤務場所","出社","在宅勤務","リモートワーク","workplace","work location","office"]) { return .workplace }
    if hit(["サイレントモード","フォーカスタイム","集中タイム","focus time"]) { return .focusTime }
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
    case "focusTime": return .focusTime
    case "default", "fromGmail": return .event
    default: return nil
    }
}

/// Models.kt resolveEventType と同じ優先順: API の正本があればそれ、なければタイトル判定。
func resolveEventType(_ ev: CalendarEventDTO) -> EventType {
    googleEventTypeToEventType(ev.googleEventType) ?? classifyEventType(ev.title)
}

/// Models.kt EventTypeFilter (#79)。false のものは鳴らさない。既定値は Kotlin 側と同じ。
struct EventTypeFilter: Codable, Hashable {
    var birthday: Bool = false
    var absence: Bool = false
    var workplace: Bool = false
    var focusTime: Bool = false
    var task: Bool = true
    var event: Bool = true
    /// 「予定なし」(free) の予定を鳴らすか
    var free: Bool = true

    init() {}

    init(birthday: Bool, absence: Bool, workplace: Bool, focusTime: Bool, task: Bool, event: Bool, free: Bool) {
        self.birthday = birthday; self.absence = absence; self.workplace = workplace
        self.focusTime = focusTime; self.task = task; self.event = event; self.free = free
    }

    /// 保存済みの旧版の値に新しい項目が無くても読めるよう、項目ごとに読む。
    /// 旧版の既定 (5 種類すべてオン) のままの人は一度も触っていないとみなして新しい既定値にする。
    /// 触っていた人の選択は保つ。サイレントモードは旧版では「予定」扱いだったので「予定」と同じ値にし、
    /// 予定なしは旧版では常に鳴っていたので鳴らす
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let b = try c.decodeIfPresent(Bool.self, forKey: .birthday)
        let a = try c.decodeIfPresent(Bool.self, forKey: .absence)
        let w = try c.decodeIfPresent(Bool.self, forKey: .workplace)
        let t = try c.decodeIfPresent(Bool.self, forKey: .task)
        let e = try c.decodeIfPresent(Bool.self, forKey: .event)
        let f = try c.decodeIfPresent(Bool.self, forKey: .focusTime)
        let fr = try c.decodeIfPresent(Bool.self, forKey: .free)
        let legacy = f == nil && fr == nil
        if legacy && [b, a, w, t, e].allSatisfy({ $0 ?? true }) {
            self.init()
            return
        }
        let d = EventTypeFilter()
        self.init(
            birthday: b ?? d.birthday, absence: a ?? d.absence, workplace: w ?? d.workplace,
            focusTime: f ?? (legacy ? (e ?? true) : d.focusTime),
            task: t ?? d.task, event: e ?? d.event, free: fr ?? true
        )
    }

    func allows(_ type: EventType) -> Bool {
        switch type {
        case .birthday: return birthday
        case .absence: return absence
        case .workplace: return workplace
        case .focusTime: return focusTime
        case .task: return task
        case .event: return event
        }
    }

    /// 種類と予定あり／なしの両方で判定する (順番は 種類 → 予定あり／なし)
    func allows(_ ev: CalendarEventDTO) -> Bool {
        allows(resolveEventType(ev)) && (ev.availability != Availability.free.rawValue || free)
    }
}

/// Models.kt AlarmExpander.passesEventFilters と同じ判定。予定一覧の表示が予約と一致するよう両方で使う (#79)。
/// 順番: 予定一つへの個別指定 → 招待 → 種類 → 予定あり／なし
func passesEventFilters(_ ev: CalendarEventDTO, _ ov: EventOverrideDTO?, _ invite: InviteFilter, _ types: EventTypeFilter) -> Bool {
    if ov?.action == EventAction.mute.rawValue { return false }
    if ov?.action == EventAction.alarm.rawValue { return true }
    return invite.allows(ev.inviteStatus) && types.allows(ev)
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
    /// iOS では連絡先の誕生日カレンダーの予定に "birthday" を入れる (EKCalendar.type で確実に分かるため)
    var googleEventType: String? = nil
    /// 予定あり／なし (Availability の生値)。取れない時は nil (= 不明)
    var availability: String? = nil

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
    var importEventReminders: Bool = true
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

/// アカウント (EventKit のソース。Gmail・iCloud など) 単位の設定 (局長決定6, #74)。
/// Android の `AccountSetting` と同じ意味。キーは `CalendarSource.accountName`
struct AccountSettingDTO: Codable, Hashable {
    /// このアプリで使うか。OFF なら配下の予定を一覧にも出さない (端末からは消さない)
    var use: Bool = true
    /// このアカウントの予定で鳴らすか
    var ring: Bool = true
    /// 配下カレンダーの既定の「何分前」。nil ならアプリ全体の既定
    var minutesBefore: Int? = nil
}

struct CalendarSource: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    /// 表示用のアカウント名 (EventKit のソースの表示名)
    var accountName: String
    var color: Int
    var isPrimary: Bool = false
    /// アカウント設定のキー (EventKit のソース識別子)。表示名は重なり得るので、こちらで区別する (#74)
    var accountId: String = ""

    /// アカウント設定とまとめ表示に使うキー。旧データ (識別子なし) は表示名で代用する
    var accountKey: String { accountId.isEmpty ? accountName : accountId }

    init(id: String, name: String, accountName: String, color: Int, isPrimary: Bool = false, accountId: String = "") {
        self.id = id; self.name = name; self.accountName = accountName
        self.color = color; self.isPrimary = isPrimary; self.accountId = accountId
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        accountName = try c.decode(String.self, forKey: .accountName)
        color = try c.decode(Int.self, forKey: .color)
        isPrimary = try c.decodeIfPresent(Bool.self, forKey: .isPrimary) ?? false
        accountId = try c.decodeIfPresent(String.self, forKey: .accountId) ?? ""
    }
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
    /// カレンダーを鳴らさない設定にしている (予定ごとの指定より強い)
    var calendarOff: Bool = false
    /// アカウントを鳴らさない設定にしている (#74。展開が予定ごとの指定より先に外す)
    var accountOff: Bool = false
    /// 終日の予定で、カレンダーが終日を鳴らさない設定 (予定ごとの指定より強い)
    var allDayOff: Bool = false
    /// 設定の「アラームをすべてミュート」中 (展開がアラームを全部外すので、鳴らない行はスイッチで入れても鳴らない)
    var muteAll: Bool = false
    /// 始まる前で、通知の動作で届く (通知の許可が要る)
    var notifies: Bool = false
    /// 予定ごとの指定を外すと何も鳴らない (カレンダーの既定が鳴らさない・絞り込みで外れる等)。
    /// 行のスイッチを ON にする時、これが true の時だけ「アラーム」を書く (通知だけの予定を格上げしないため)
    var defaultSilenced: Bool = false
    /// 行のスイッチを ON にして戻る動作が、すべてミュートで外れて鳴らない (通知だけの既定は数えない)
    var restoreSilenced: Bool = false

    /// 行のスイッチを押せなくする (Android の `AgendaItem.switchLocked` と同じ規則)。
    /// ON で戻る先が鳴らない時だけ押せなくする。ON で通知に戻る行は、今 OFF でも入れられるように残す
    var switchLocked: Bool { calendarOff || accountOff || allDayOff || (muteAll && restoreSilenced) }
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

/// 通知で届く時刻がこれから先にあるか。終日は shared `AlarmExpander.expandEvents` と同じく、
/// 予定日 (UTC の日付) のローカル allDayMinutes に倒してから引く。生の startMillis だと、
/// 0時を過ぎた当日の終日通知 (まだ9時前) を「もう過ぎた」と見てしまい、許可の警告が消える
func notificationDeliveryAhead(_ ev: CalendarEventDTO, _ rule: AlarmRuleDTO, _ ov: EventOverrideDTO?,
                               actions: (start: String, reminder: String?), importReminders: Bool,
                               muteAll: Bool = false, nowMillis: Int64) -> Bool {
    let notify = EventAction.notify.rawValue
    var startMillis = ev.startMillis
    if ev.allDay {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC") ?? utc.timeZone
        let day = utc.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: TimeInterval(ev.startMillis) / 1000))
        var local = DateComponents()
        local.year = day.year; local.month = day.month; local.day = day.day
        local.hour = rule.allDayMinutes / 60; local.minute = rule.allDayMinutes % 60
        // 端末の暦が和暦などでも、取り出した西暦の年月日をそのまま端末のタイムゾーンの日時にする (shared は西暦で組む)
        var gregorian = Calendar(identifier: .gregorian)
        gregorian.timeZone = TimeZone.current
        if let d = gregorian.date(from: local) {
            startMillis = Int64(d.timeIntervalSince1970 * 1000)
        }
    }
    func ahead(_ minutes: Int) -> Bool { startMillis - Int64(minutes) * 60_000 > nowMillis }
    let startMinutes = ov?.minutesBefore ?? rule.minutesBefore
    if actions.start == notify && ahead(startMinutes) { return true }
    guard actions.reminder == notify else { return false }
    // shared `AlarmExpander` は開始の予約を先に作り、同じ時刻のリマインダーは重複として作らない。
    // 開始がミュートの時は何も作らないので、その時刻のリマインダーは残る
    // すべてミュート中のアラームの開始も、展開は時刻を記録する前に外すので、その時刻のリマインダーは残る
    let startTriggerTaken = actions.start != EventAction.mute.rawValue
        && !(muteAll && actions.start == EventAction.alarm.rawValue)
    let offsets = (ov?.extraOffsets ?? rule.extraOffsets)
        + (importReminders ? ev.calendarReminderMinutes.filter { $0 >= 0 } : [])
    return offsets.contains { ahead($0) && !(startTriggerTaken && $0 == startMinutes) }
}

/// 予定が実際に使う動作。優先順は shared `AlarmExpander.resolveActions` と同じ:
/// 予定の個別指定 > never コード > always コード > カレンダーの既定。
/// 展開と同じく、リマインダーの動作はリマインダー側の時刻 (追加の何分前・取り込んだカレンダーの通知) がある時だけ使う (無ければ nil)
func resolvedRingActions(_ ev: CalendarEventDTO, _ rule: AlarmRuleDTO, _ ov: EventOverrideDTO?, _ codes: TitleCodeSettings,
                         importReminders: Bool) -> (start: String, reminder: String?) {
    func matches(_ list: [String]) -> Bool {
        list.contains { !$0.trimmingCharacters(in: .whitespaces).isEmpty && ev.title.localizedCaseInsensitiveContains($0) }
    }
    func resolve(_ apply: Bool, _ fallback: String) -> String {
        if let a = ov?.action { return a }
        if apply && matches(codes.neverCodes) { return EventAction.mute.rawValue }
        if apply && matches(codes.alwaysCodes) { return EventAction.alarm.rawValue }
        return fallback
    }
    let hasReminderTriggers = !(ov?.extraOffsets ?? rule.extraOffsets).isEmpty
        || (importReminders && ev.calendarReminderMinutes.contains { $0 >= 0 })
    return (resolve(codes.applyToStart, rule.startAction),
            hasReminderTriggers ? resolve(codes.applyToReminders, rule.reminderAction) : nil)
}

/// 何も鳴らないか (`resolvedRingActions` の結果で決める)。
/// すべてミュート中のアラームも鳴らない側に数える (展開の emit と同じく通知は残る)
func resolvedActionsMuted(_ ev: CalendarEventDTO, _ rule: AlarmRuleDTO, _ ov: EventOverrideDTO?, _ codes: TitleCodeSettings,
                          importReminders: Bool, muteAll: Bool = false) -> Bool {
    let actions = resolvedRingActions(ev, rule, ov, codes, importReminders: importReminders)
    func silenced(_ action: String) -> Bool {
        action == EventAction.mute.rawValue || (muteAll && action == EventAction.alarm.rawValue)
    }
    return silenced(actions.start) && (actions.reminder.map(silenced) ?? true)
}

/// 予定ごとの指定を外した時に、何も鳴らないか (shared `AlarmExpander.silencedWithoutOverride` と同じ)。
/// 行のスイッチを ON にする時の分かれ目: 鳴る側に戻るなら指定を外してカレンダーの既定の動作 (通知だけ等) を使い、
/// 鳴らない時だけ「アラーム」を書く。常に ALARM を書くと、通知だけの予定が全画面のアラームに格上げされる。
/// muteAll が true なら、すべてミュートで外れるアラームも鳴らない側に数える (スイッチを押せるかの判定用)。
/// 何分前などの個別指定は ON でも残すので、リマインダー側の時刻の有無には指定の分まで数える
func silencedWithoutOverride(_ ev: CalendarEventDTO, _ rule: AlarmRuleDTO, _ ov: EventOverrideDTO?, _ codes: TitleCodeSettings,
                             invite: InviteFilter, types: EventTypeFilter, importReminders: Bool, muteAll: Bool = false) -> Bool {
    if !passesEventFilters(ev, nil, invite, types) { return true }
    let rest = EventOverrideDTO(action: nil, minutesBefore: ov?.minutesBefore, extraOffsets: ov?.extraOffsets)
    return resolvedActionsMuted(ev, rule, rest, codes, importReminders: importReminders, muteAll: muteAll)
}

/// 行のスイッチを押した後の、その予定の個別指定。OFF は「鳴らさない」を書く。
/// ON は、指定を外すとそのまま鳴る予定なら「アクション」の指定を外し (カレンダーの既定の通知はそのまま)、
/// 鳴らない予定だけ「アラーム」を書く。何分前などの指定はどちらでも残す。指定が空になれば nil (指定ごと消す)
func overrideAfterRingSwitch(_ ov: EventOverrideDTO?, on: Bool, defaultSilenced: Bool) -> EventOverrideDTO? {
    if !on { return EventOverrideDTO(action: EventAction.mute.rawValue, minutesBefore: ov?.minutesBefore, extraOffsets: ov?.extraOffsets) }
    if defaultSilenced { return EventOverrideDTO(action: EventAction.alarm.rawValue, minutesBefore: ov?.minutesBefore, extraOffsets: ov?.extraOffsets) }
    guard var kept = ov else { return nil }
    kept.action = nil
    return kept.minutesBefore == nil && kept.extraOffsets == nil ? nil : kept
}
