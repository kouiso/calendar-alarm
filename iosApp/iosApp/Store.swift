import Foundation
import Combine

/// アプリの永続状態。Android 側 Room DB (settings/calendar_prefs/event_overrides/
/// standalone_alarms/scheduled_instances/audit_log) に相当。
/// JSON ファイル1本にまとめて原子的に書き出す (件数が小さいため)。
@MainActor
final class Store: ObservableObject {

    struct Persisted: Codable {
        // settings
        var defaultMinutesBefore: Int = 0
        var defaultSnoozeMinutes: Int = 10
        var weatherEnabled: Bool = true
        var defaultStartAction: String = EventAction.alarm.rawValue
        var defaultReminderAction: String = EventAction.alarm.rawValue
        var titleCodes: TitleCodeSettings = TitleCodeSettings()
        var inviteFilter: InviteFilter = InviteFilter()
        var eventTypeFilter: EventTypeFilter = EventTypeFilter()
        /// 元カレンダー通知の「何分前」を正本として鳴らす仕様のため既定 ON。
        /// 旧版は未操作でも false が store.json に書き込まれており、保存済み false には
        /// 「明示OFF」と「旧版の未操作」の区別が付かない。識別不能なため既存値は
        /// そのまま維持し (明示OFFの可能性を尊重)、取込ON化は新規のみに適用する。
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
        /// 既定のアラーム音 (Library/Sounds のファイル名)。nil = iPhone の既定音 (#101)
        var alarmSoundFile: String? = nil
        /// 取り込んだ時の元のファイル名 (画面表示用)
        var alarmSoundLabel: String? = nil
        /// 予定一覧に週番号を出すか (旧版の「Show Calendar Week」)
        var showWeekNumbers: Bool = false
        /// 週の開始曜日 (ISO: 1=月 … 7=日)。既定はこれまでの月表示と同じ日曜
        var firstWeekday: Int = WeekDisplay.defaultFirstWeekday
        var timerPresets: [TimerPresetDTO] = TimerPresets.defaults
        /// OpenRouter APIキー (AI機能用)
        // calendar_prefs: calendarId -> AlarmRuleDTO
        var calendarRules: [String: AlarmRuleDTO] = [:]
        // 何分前をカレンダーで選んだ calendarId。含まれないカレンダーの何分前はアカウントの既定、
        // 無ければアプリの既定に従う (#74)。スイッチ等で calendarRules に行ができても既定は効き続ける
        var calendarMinutesChosen: Set<String> = []
        // CalendarSource.accountKey (EventKit のソース識別子) -> AccountSettingDTO (#74)
        var accountSettings: [String: AccountSettingDTO] = [:]
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
    /// いまの state をディスクから読んだ時刻。これより後に置かれたファイルを、この写しは知らない
    private(set) var loadedAt = Date()
    /// 保存は直列キューに流す。並列 detached だと古いスナップショットが後勝ちで残る
    /// (書き込み順序が呼び出し順と逆転する) のを防ぐため FIFO にする。
    private let saveQueue = DispatchQueue(label: "calendaralarm.store.save")

    /// [fileURL] はテストで端末の保存データを上書きしないための差し替え口。アプリは渡さない
    init(fileURL: URL? = nil) {
        if let fileURL {
            self.fileURL = fileURL
        } else {
            let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("CalendarAlarm", isDirectory: true)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            self.fileURL = dir.appendingPathComponent("store.json")
        }
        loadedAt = Date()
        if let data = try? Data(contentsOf: self.fileURL),
           let loaded = try? JSONDecoder().decode(Persisted.self, from: data) {
            state = loaded
            AlarmSoundLibrary.currentDefault = loaded.alarmSoundFile
            // 旧版で保存した AI のキーとモデルを端末から消す。読み込みでは落ちるだけで
            // ファイルに残るので、決定7のとおり起動時に書き直す
            if let raw = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               raw["openRouterApiKey"] != nil || raw["openRouterModel"] != nil {
                save()
            }
        }
    }

    /// ディスクの最新状態を読み直す。BG タスクは別プロセス上の別 Store で動くため、
    /// 前景復帰時にメモリのスナップショットが BG 側の保存を見落とすのを防ぐ。
    /// 保存キューの未完了書き込みを先に flush してから読む。
    func reload() {
        saveQueue.sync {}
        loadedAt = Date()
        if let data = try? Data(contentsOf: fileURL),
           let loaded = try? JSONDecoder().decode(Persisted.self, from: data) {
            state = loaded
            AlarmSoundLibrary.currentDefault = loaded.alarmSoundFile
        }
    }

    /// ディスクに保存済みの状態。別の Store (バックグラウンドの Engine) が書いた分も入る。手元の state は変えない
    func savedState() -> Persisted? {
        saveQueue.sync {}
        guard let data = try? Data(contentsOf: fileURL) else { return nil }
        return try? JSONDecoder().decode(Persisted.self, from: data)
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

    func setDefaultMinutes(_ v: Int) { mutate { $0.defaultMinutesBefore = v } }
    func setDefaultSnooze(_ v: Int) { mutate { $0.defaultSnoozeMinutes = v } }
    func setWeatherEnabled(_ v: Bool) { mutate { $0.weatherEnabled = v } }
    /// 既定のアラーム音を変える。前の音のファイルは、どの予約の記録も指さなくなってから Engine が消す
    /// (鳴る直前・鳴動中・スヌーズ中の予約は前の音のまま鳴るため)
    func setAlarmSound(file: String?, label: String?) {
        mutate { $0.alarmSoundFile = file; $0.alarmSoundLabel = label }
        AlarmSoundLibrary.currentDefault = file
    }
    func setShowWeekNumbers(_ v: Bool) { mutate { $0.showWeekNumbers = v } }
    func setFirstWeekday(_ v: Int) { mutate { $0.firstWeekday = WeekDisplay.normalize(v) } }
    func setDefaultStartAction(_ a: EventAction) { mutate { $0.defaultStartAction = a.rawValue } }
    func setDefaultReminderAction(_ a: EventAction) { mutate { $0.defaultReminderAction = a.rawValue } }
    func setTitleCodes(_ v: TitleCodeSettings) { mutate { $0.titleCodes = v } }
    func setEventTypeFilter(_ v: EventTypeFilter) { mutate { $0.eventTypeFilter = v } }
    func setRingFree(_ v: Bool) { mutate { $0.eventTypeFilter.free = v } }
    func toggleEventType(_ t: EventType) {
        var g = state.eventTypeFilter
        switch t {
        case .birthday: g.birthday.toggle()
        case .absence: g.absence.toggle()
        case .workplace: g.workplace.toggle()
        case .focusTime: g.focusTime.toggle()
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

    /// カレンダー単位のルール。何分前はカレンダーで選んだ値、無ければアカウントの「何分前」→ アプリの既定の順
    func rule(for calendarId: String) -> AlarmRuleDTO {
        var r = state.calendarRules[calendarId] ?? AlarmRuleDTO(minutesBefore: state.defaultMinutesBefore)
        if !state.calendarMinutesChosen.contains(calendarId) {
            r.minutesBefore = accountSetting(forCalendar: calendarId).minutesBefore ?? state.defaultMinutesBefore
        }
        return r
    }

    /// このカレンダーで何分前を選んだか (false = 既定に従う)
    func isCalendarMinutesChosen(_ calendarId: String) -> Bool { state.calendarMinutesChosen.contains(calendarId) }

    /// カレンダーの何分前を選ぶ。nil で既定 (アカウント → アプリ) に戻す
    func setCalendarMinutes(_ calendarId: String, _ minutes: Int?) {
        mutate {
            var r = $0.calendarRules[calendarId] ?? AlarmRuleDTO(minutesBefore: $0.defaultMinutesBefore)
            if let m = minutes {
                r.minutesBefore = m
                $0.calendarMinutesChosen.insert(calendarId)
            } else {
                $0.calendarMinutesChosen.remove(calendarId)
            }
            $0.calendarRules[calendarId] = r
        }
    }

    // MARK: - account settings (#74)

    func accountSetting(forCalendar calendarId: String) -> AccountSettingDTO {
        let account = state.calendars.first { $0.id == calendarId }?.accountKey ?? ""
        return state.accountSettings[account] ?? AccountSettingDTO()
    }

    func setAccountSetting(_ accountKey: String, _ setting: AccountSettingDTO) {
        mutate { $0.accountSettings[accountKey] = setting }
    }

    /// アカウントの「使う」「鳴らす」が両方 ON か
    func accountAllowsRing(_ calendarId: String) -> Bool {
        let a = accountSetting(forCalendar: calendarId)
        return a.use && a.ring
    }

    /// このアプリで使わないアカウントのカレンダーか (一覧にも出さない)
    func isHiddenCalendar(_ calendarId: String) -> Bool { !accountSetting(forCalendar: calendarId).use }

    /// 展開に渡すカレンダー単位のルール。アカウント設定を配下カレンダーへ当てはめる
    /// (Android の `AccountRules.apply` と同じ規則: 鳴らさないアカウントは止め、何分前はカレンダーで選んだ時だけそちらが勝つ)
    func effectiveCalendarRules() -> [String: AlarmRuleDTO] {
        // 選んでいない行の何分前は、ここでアプリの既定に揃えてからアカウントの既定を重ねる
        var out = state.calendarRules
        for (id, rule) in state.calendarRules where !state.calendarMinutesChosen.contains(id) {
            var r = rule; r.minutesBefore = state.defaultMinutesBefore; out[id] = r
        }
        for cal in state.calendars {
            let account = state.accountSettings[cal.accountKey] ?? AccountSettingDTO()
            let allows = account.use && account.ring
            if allows && account.minutesBefore == nil { continue }
            // 何も決めていないカレンダーはアプリ既定の動作 (開始/リマインダーの既定アクション) を引き継ぐ
            var r = out[cal.id] ?? AlarmRuleDTO(
                minutesBefore: state.defaultMinutesBefore,
                startAction: state.defaultStartAction,
                reminderAction: state.defaultReminderAction)
            if let m = account.minutesBefore, !state.calendarMinutesChosen.contains(cal.id) { r.minutesBefore = m }
            if !allows { r.enabled = false }
            out[cal.id] = r
        }
        return out
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

    /// atMillis は後から分かった出来事 (閉じている間に届いた通知) を起きた時刻で残す時だけ渡す。
    /// 一覧が時刻順のままになるよう、その時刻の位置に差し込む
    func audit(_ type: String, _ detail: String, atMillis: Int64? = nil) {
        mutate { s in
            let at = atMillis ?? Int64(Date().timeIntervalSince1970 * 1000)
            let index = s.audit.lastIndex { $0.atMillis <= at }.map { $0 + 1 } ?? 0
            s.audit.insert(AuditEntry(id: s.nextAuditId, atMillis: at, type: type, detail: detail), at: index)
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
        defaultMinutesBefore = try c.decodeIfPresent(Int.self, forKey: .defaultMinutesBefore) ?? 0
        defaultSnoozeMinutes = try c.decodeIfPresent(Int.self, forKey: .defaultSnoozeMinutes) ?? 10
        weatherEnabled = try c.decodeIfPresent(Bool.self, forKey: .weatherEnabled) ?? true
        defaultStartAction = try c.decodeIfPresent(String.self, forKey: .defaultStartAction) ?? EventAction.alarm.rawValue
        defaultReminderAction = try c.decodeIfPresent(String.self, forKey: .defaultReminderAction) ?? EventAction.alarm.rawValue
        titleCodes = try c.decodeIfPresent(TitleCodeSettings.self, forKey: .titleCodes) ?? TitleCodeSettings()
        inviteFilter = try c.decodeIfPresent(InviteFilter.self, forKey: .inviteFilter) ?? InviteFilter()
        eventTypeFilter = try c.decodeIfPresent(EventTypeFilter.self, forKey: .eventTypeFilter) ?? EventTypeFilter()
        // キーが無い=新規のみ ON。旧データに保存済みの値は識別不能のため維持する
        importEventReminders = try c.decodeIfPresent(Bool.self, forKey: .importEventReminders) ?? true
        muteAll = try c.decodeIfPresent(Bool.self, forKey: .muteAll) ?? false
        themeId = try c.decodeIfPresent(String.self, forKey: .themeId) ?? "default"
        hasCustomBackground = try c.decodeIfPresent(Bool.self, forKey: .hasCustomBackground) ?? false
        weatherLocation = try c.decodeIfPresent(String.self, forKey: .weatherLocation) ?? ""
        weatherHeaderEnabled = try c.decodeIfPresent(Bool.self, forKey: .weatherHeaderEnabled) ?? true
        weatherOnAlarmScreen = try c.decodeIfPresent(Bool.self, forKey: .weatherOnAlarmScreen) ?? true
        alarmSoundFile = try c.decodeIfPresent(String.self, forKey: .alarmSoundFile)
        alarmSoundLabel = try c.decodeIfPresent(String.self, forKey: .alarmSoundLabel)
        showWeekNumbers = try c.decodeIfPresent(Bool.self, forKey: .showWeekNumbers) ?? false
        firstWeekday = WeekDisplay.normalize(try c.decodeIfPresent(Int.self, forKey: .firstWeekday) ?? WeekDisplay.defaultFirstWeekday)
        timerPresets = try c.decodeIfPresent([TimerPresetDTO].self, forKey: .timerPresets) ?? TimerPresets.defaults
        calendarRules = try c.decodeIfPresent([String: AlarmRuleDTO].self, forKey: .calendarRules) ?? [:]
        // キーが無い旧データは、アプリ既定と違う分数の行だけを選んだ値とみなす。旧版はスイッチ等を触っただけでも
        // その時の既定を書き込んでいたため、既定と同じ値は「選んでいない」と扱う (Android の v5→v6 移行と同じ規則)
        calendarMinutesChosen = try c.decodeIfPresent(Set<String>.self, forKey: .calendarMinutesChosen)
            ?? Set(calendarRules.filter { $0.value.minutesBefore != defaultMinutesBefore }.keys)
        accountSettings = try c.decodeIfPresent([String: AccountSettingDTO].self, forKey: .accountSettings) ?? [:]
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
