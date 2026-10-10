import Foundation
import Combine

/// アプリの永続状態。Android 側 Room DB (settings/calendar_prefs/event_overrides/
/// standalone_alarms/scheduled_instances/audit_log) に相当。
/// JSON ファイル1本にまとめて原子的に書き出す (件数が小さいため)。
@MainActor
final class Store: ObservableObject {

    struct Persisted: Codable {
        // settings
        var onboardingDone: Bool = false
        var defaultMinutesBefore: Int = 0
        var defaultSnoozeMinutes: Int = 10
        var weatherEnabled: Bool = true
        var defaultStartAction: String = EventAction.alarm.rawValue
        var defaultReminderAction: String = EventAction.alarm.rawValue
        var titleCodes: TitleCodeSettings = TitleCodeSettings()
        var inviteFilter: InviteFilter = InviteFilter()
        var eventTypeFilter: EventTypeFilter = EventTypeFilter()
        /// 元カレンダー通知の「何分前」を正本として鳴らす仕様のため既定 ON。
        var importEventReminders: Bool = true
        /// 全アラームの一括ミュート (通知・タイマーには効かない)
        var muteAll: Bool = false
        /// テーマ id ("default"=インディゴ)
        var themeId: String = "default"
        /// カスタム背景画像を設定済みか (実体は Application Support/background.jpg 固定)
        var hasCustomBackground: Bool = false
        /// 天気の地点 (空=天気UI非表示)
        var weatherLocation: String = ""
        var weatherHeaderEnabled: Bool = true
        var weatherOnAlarmScreen: Bool = true
        var timerPresets: [TimerPresetDTO] = TimerPresets.defaults
        /// OpenRouter APIキー (AI機能用)
        var openRouterApiKey: String? = nil
        var openRouterModel: String = "openai/gpt-4.1-mini"
        // calendar_prefs: calendarId -> AlarmRuleDTO
        var calendarRules: [String: AlarmRuleDTO] = [:]
        // event_overrides: instanceKey -> EventOverrideDTO
        var overrides: [String: EventOverrideDTO] = [:]
        var standaloneAlarms: [StandaloneAlarmDTO] = []
        // scheduled_instances: id -> ScheduledRecord
        var scheduled: [String: ScheduledRecord] = [:]
        var audit: [AuditEntry] = []
        var nextAlarmId: Int64 = 1
        var nextAuditId: Int64 = 1
        // 直近に読み取ったカレンダー/イベントのキャッシュ (予定画面表示用)
        var calendars: [CalendarSource] = []
        var lastEvents: [CalendarEventDTO] = []
    }

    @Published private(set) var state = Persisted()

    private let fileURL: URL
    /// 保存は直列キューに流す。並列 detached だと古いスナップショットが後勝ちで残る
    /// (書き込み順序が呼び出し順と逆転する) のを防ぐため FIFO にする。
    private let saveQueue = DispatchQueue(label: "calendaralarm.store.save")

    init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("CalendarAlarm", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        fileURL = dir.appendingPathComponent("store.json")
        if let data = try? Data(contentsOf: fileURL),
           let loaded = try? JSONDecoder().decode(Persisted.self, from: data) {
            state = loaded
        }
    }

    /// ディスクの最新状態を読み直す。BG タスクは別プロセス上の別 Store で動くため、
    /// 前景復帰時にメモリのスナップショットが BG 側の保存を見落とすのを防ぐ。
    /// 保存キューの未完了書き込みを先に flush してから読む。
    func reload() {
        saveQueue.sync {}
        if let data = try? Data(contentsOf: fileURL),
           let loaded = try? JSONDecoder().decode(Persisted.self, from: data) {
            state = loaded
        }
    }

    /// 書き出しは直列キューで原子的に (tmp → rename)。呼び出し順=書き込み順なので最後の保存が常に最新。
    func save() {
        let snapshot = state
        saveQueue.async { [fileURL] in
            guard let data = try? JSONEncoder().encode(snapshot) else { return }
            let tmp = fileURL.appendingPathExtension("tmp")
            try? data.write(to: tmp, options: .atomic)
            try? FileManager.default.replaceItemAt(fileURL, withItemAt: tmp)
        }
    }

    func mutate(_ f: (inout Persisted) -> Void) {
        f(&state)
        save()
    }

    // MARK: - settings

    func completeOnboarding() { mutate { $0.onboardingDone = true } }
    func setDefaultMinutes(_ v: Int) { mutate { $0.defaultMinutesBefore = v } }
    func setDefaultSnooze(_ v: Int) { mutate { $0.defaultSnoozeMinutes = v } }
    func setWeatherEnabled(_ v: Bool) { mutate { $0.weatherEnabled = v } }
    func setDefaultStartAction(_ a: EventAction) { mutate { $0.defaultStartAction = a.rawValue } }
    func setDefaultReminderAction(_ a: EventAction) { mutate { $0.defaultReminderAction = a.rawValue } }
    func setTitleCodes(_ v: TitleCodeSettings) { mutate { $0.titleCodes = v } }
    func setEventTypeFilter(_ v: EventTypeFilter) { mutate { $0.eventTypeFilter = v } }
    func toggleEventType(_ t: EventType) {
        var g = state.eventTypeFilter
        switch t {
        case .birthday: g.birthday.toggle()
        case .absence: g.absence.toggle()
        case .workplace: g.workplace.toggle()
        case .task: g.task.toggle()
        case .event: g.event.toggle()
        }
        mutate { $0.eventTypeFilter = g }
    }
    func toggleInviteStatus(_ s: InviteStatus) {
        mutate { f in
            switch s {
            case .accepted: f.inviteFilter.accepted.toggle()
            case .tentative: f.inviteFilter.tentative.toggle()
            case .needsAction: f.inviteFilter.needsAction.toggle()
            case .declined: f.inviteFilter.declined.toggle()
            }
        }
    }
    func setImportEventReminders(_ v: Bool) { mutate { $0.importEventReminders = v } }
    func setMuteAll(_ v: Bool) { mutate { $0.muteAll = v } }
    func setThemeId(_ v: String) { mutate { $0.themeId = v } }
    func setWeatherLocation(_ v: String) { mutate { $0.weatherLocation = v.trimmingCharacters(in: .whitespacesAndNewlines) } }
    func setWeatherHeaderEnabled(_ v: Bool) { mutate { $0.weatherHeaderEnabled = v } }
    func setWeatherOnAlarmScreen(_ v: Bool) { mutate { $0.weatherOnAlarmScreen = v } }
    func setTimerPresets(_ v: [TimerPresetDTO]) { mutate { $0.timerPresets = v } }
    func setOpenRouterApiKey(_ v: String?) { mutate { $0.openRouterApiKey = v?.isEmpty == false ? v : nil } }
    func setOpenRouterModel(_ v: String) { mutate { $0.openRouterModel = v } }

    /// カスタム背景画像の保存先 (固定パス)。
    static var backgroundImageURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("CalendarAlarm/background.jpg")
    }

    /// 背景画像を固定パスに保存 (PhotosPicker からの Data をそのまま書く)。
    func setCustomBackground(data: Data?) {
        if let data {
            try? data.write(to: Store.backgroundImageURL, options: .atomic)
        } else {
            try? FileManager.default.removeItem(at: Store.backgroundImageURL)
        }
        mutate { $0.hasCustomBackground = (data != nil) }
    }

    // MARK: - calendar prefs

    func rule(for calendarId: String) -> AlarmRuleDTO {
        state.calendarRules[calendarId] ?? AlarmRuleDTO(minutesBefore: state.defaultMinutesBefore)
    }

    func setCalendarRule(_ calendarId: String, _ rule: AlarmRuleDTO) {
        mutate { $0.calendarRules[calendarId] = rule }
    }

    // MARK: - event overrides

    func setOverride(instanceKey: String, action: EventAction, minutes: Int?, extras: [Int]?) {
        mutate { $0.overrides[instanceKey] = EventOverrideDTO(action: action.rawValue, minutesBefore: minutes, extraOffsets: extras) }
    }
    func removeOverride(instanceKey: String) { mutate { $0.overrides.removeValue(forKey: instanceKey) } }

    // MARK: - standalone alarms

    /// 単発アラームの終端到達行を消す (消費リセット)。
    /// 再編集・再有効化で「1回のみ」が復活できるようにするためのもの。
    func deleteTerminalByAlarmId(_ alarmId: Int64) {
        mutate { s in
            s.scheduled = s.scheduled.filter {
                $0.value.state == .pending || $0.value.instance.standaloneAlarmId != alarmId
            }
        }
    }

    func upsertAlarm(_ a: StandaloneAlarmDTO) -> Int64 {
        var id = a.id
        mutate { s in
            if id == 0 {
                id = s.nextAlarmId
                s.nextAlarmId += 1
            }
            var copy = a; copy.id = id
            s.standaloneAlarms.removeAll { $0.id == id }
            s.standaloneAlarms.append(copy)
        }
        // 有効な単発の保存は消費のリセット (Android upsertStandaloneAlarm と同じ)。
        if a.enabled && a.effectiveRepeatMode == "ONCE" { deleteTerminalByAlarmId(id) }
        return id
    }
    func deleteAlarm(id: Int64) { mutate { $0.standaloneAlarms.removeAll { $0.id == id } } }
    func setAlarmEnabled(id: Int64, _ enabled: Bool) {
        var oneShot = false
        mutate { s in
            guard let i = s.standaloneAlarms.firstIndex(where: { $0.id == id }) else { return }
            s.standaloneAlarms[i].enabled = enabled
            oneShot = s.standaloneAlarms[i].effectiveRepeatMode == "ONCE"
        }
        // 単発の再有効化は消費のリセット
        if enabled && oneShot { deleteTerminalByAlarmId(id) }
    }

    // MARK: - scheduled instances

    func pendingScheduled() -> [String: Int64] {
        state.scheduled.compactMapValues { $0.state == .pending ? $0.instance.triggerAtMillis : nil }
    }

    func markState(_ id: String, _ st: InstanceState) {
        mutate { s in
            guard var r = s.scheduled[id] else { return }
            r.state = st
            s.scheduled[id] = r
        }
    }

    func putScheduled(_ r: ScheduledRecord) { mutate { $0.scheduled[r.instance.id] = r } }
    func removeScheduled(_ id: String) { mutate { $0.scheduled.removeValue(forKey: id) } }

    /// 7日より古い終了行を実削除 (Android 側と同じ保持期間)。
    func pruneOldInstances(nowMillis: Int64) {
        mutate { s in
            let cutoff = nowMillis - 7 * 24 * 3600 * 1000
            s.scheduled = s.scheduled.filter {
                $0.value.state == .pending || $0.value.instance.triggerAtMillis > cutoff
            }
        }
    }

    // MARK: - audit

    func audit(_ type: String, _ detail: String) {
        mutate { s in
            s.audit.append(AuditEntry(id: s.nextAuditId, atMillis: Int64(Date().timeIntervalSince1970 * 1000), type: type, detail: detail))
            s.nextAuditId += 1
            if s.audit.count > 50 { s.audit.removeFirst(s.audit.count - 50) }
        }
    }

    // MARK: - cache

    func setCalendars(_ c: [CalendarSource]) { mutate { $0.calendars = c } }
    func setLastEvents(_ e: [CalendarEventDTO]) { mutate { $0.lastEvents = e } }

    // MARK: - widget snapshot (App Group)

    /// ウィジェットが読む「次のアラーム」スナップショットを共有コンテナに書く。
    func writeWidgetSnapshot() {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.com.calendaralarm.ios") else { return }
        let next = state.scheduled.values
            .filter { $0.state == .pending && $0.instance.triggerAtMillis > Int64(Date().timeIntervalSince1970 * 1000) }
            .sorted { $0.instance.triggerAtMillis < $1.instance.triggerAtMillis }
            .first
        let payload: [String: Any] = next.map {
            ["title": $0.instance.title, "triggerAtMillis": $0.instance.triggerAtMillis]
        } ?? [:]
        let url = container.appendingPathComponent("next_alarm.json")
        try? JSONSerialization.data(withJSONObject: payload).write(to: url, options: .atomic)
    }
}

// MARK: - 後方互換デコード
// フィールド追加時に旧 store.json の欠落キーで全体が初期化されるのを防ぐ。
// extension 内 init なので memberwise init は残る。

extension Store.Persisted {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        onboardingDone = try c.decodeIfPresent(Bool.self, forKey: .onboardingDone) ?? false
        defaultMinutesBefore = try c.decodeIfPresent(Int.self, forKey: .defaultMinutesBefore) ?? 0
        defaultSnoozeMinutes = try c.decodeIfPresent(Int.self, forKey: .defaultSnoozeMinutes) ?? 10
        weatherEnabled = try c.decodeIfPresent(Bool.self, forKey: .weatherEnabled) ?? true
        defaultStartAction = try c.decodeIfPresent(String.self, forKey: .defaultStartAction) ?? EventAction.alarm.rawValue
        defaultReminderAction = try c.decodeIfPresent(String.self, forKey: .defaultReminderAction) ?? EventAction.alarm.rawValue
        titleCodes = try c.decodeIfPresent(TitleCodeSettings.self, forKey: .titleCodes) ?? TitleCodeSettings()
        inviteFilter = try c.decodeIfPresent(InviteFilter.self, forKey: .inviteFilter) ?? InviteFilter()
        eventTypeFilter = try c.decodeIfPresent(EventTypeFilter.self, forKey: .eventTypeFilter) ?? EventTypeFilter()
        importEventReminders = try c.decodeIfPresent(Bool.self, forKey: .importEventReminders) ?? true
        muteAll = try c.decodeIfPresent(Bool.self, forKey: .muteAll) ?? false
        themeId = try c.decodeIfPresent(String.self, forKey: .themeId) ?? "default"
        hasCustomBackground = try c.decodeIfPresent(Bool.self, forKey: .hasCustomBackground) ?? false
        weatherLocation = try c.decodeIfPresent(String.self, forKey: .weatherLocation) ?? ""
        weatherHeaderEnabled = try c.decodeIfPresent(Bool.self, forKey: .weatherHeaderEnabled) ?? true
        weatherOnAlarmScreen = try c.decodeIfPresent(Bool.self, forKey: .weatherOnAlarmScreen) ?? true
        timerPresets = try c.decodeIfPresent([TimerPresetDTO].self, forKey: .timerPresets) ?? TimerPresets.defaults
        openRouterApiKey = try c.decodeIfPresent(String.self, forKey: .openRouterApiKey)
        openRouterModel = try c.decodeIfPresent(String.self, forKey: .openRouterModel) ?? "openai/gpt-4.1-mini"
        calendarRules = try c.decodeIfPresent([String: AlarmRuleDTO].self, forKey: .calendarRules) ?? [:]
        overrides = try c.decodeIfPresent([String: EventOverrideDTO].self, forKey: .overrides) ?? [:]
        standaloneAlarms = try c.decodeIfPresent([StandaloneAlarmDTO].self, forKey: .standaloneAlarms) ?? []
        scheduled = try c.decodeIfPresent([String: ScheduledRecord].self, forKey: .scheduled) ?? [:]
        audit = try c.decodeIfPresent([AuditEntry].self, forKey: .audit) ?? []
        nextAlarmId = try c.decodeIfPresent(Int64.self, forKey: .nextAlarmId) ?? 1
        nextAuditId = try c.decodeIfPresent(Int64.self, forKey: .nextAuditId) ?? 1
        calendars = try c.decodeIfPresent([CalendarSource].self, forKey: .calendars) ?? []
        lastEvents = try c.decodeIfPresent([CalendarEventDTO].self, forKey: .lastEvents) ?? []
    }
}
