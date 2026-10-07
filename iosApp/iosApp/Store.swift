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
        var importEventReminders: Bool = false
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
        if a.enabled && a.daysOfWeek.isEmpty { deleteTerminalByAlarmId(id) }
        return id
    }
    func deleteAlarm(id: Int64) { mutate { $0.standaloneAlarms.removeAll { $0.id == id } } }
    func setAlarmEnabled(id: Int64, _ enabled: Bool) {
        var oneShot = false
        mutate { s in
            guard let i = s.standaloneAlarms.firstIndex(where: { $0.id == id }) else { return }
            s.standaloneAlarms[i].enabled = enabled
            oneShot = s.standaloneAlarms[i].daysOfWeek.isEmpty
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
