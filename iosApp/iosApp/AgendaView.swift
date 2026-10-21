import SwiftUI

/// 予定タブ: 日付カード + 次アラーム帯 + イベント行 (アイコン主導、説明文なし)。
struct AgendaView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @EnvironmentObject var center: PermissionCenter
    @State private var selected: DisplayEvent?
    @State private var weatherByLoc: [String: [WeatherService.Forecast]] = [:]
    /// 予報を取った時刻 (予定詳細の「地点・HH:mm の予報」)
    @State private var weatherAtByLoc: [String: Date] = [:]
    @State private var nowForecast: WeatherService.NowForecast? = nil
    @State private var viewMode: AgendaViewMode = .list
    @State private var searchText = ""
    @State private var searchOpen = false
    @State private var focusDate = Date()
    /// 開いたままでも24時間を過ぎた見逃しを外すため、1分ごとに数え直す
    @State private var clockTick = Date()
    private static let minuteTimer = Timer.publish(every: 60, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
                    // Night UI: 日付 H1
                    Text(Self.todayLabel)
                        .font(NightTheme.font(30, weight: .semibold))
                        .padding(.top, 6)
                    // 次のアラーム欄は小さくし、その下に鳴動の状態を1行出す (#51 PR2)
                    AgendaNextAlarmCard(instance: nextAlarm, accent: accent, statusLine: ringStatus.line)
                    if ringStatus.missingPermissions > 0 {
                        NavigationLink { PermissionsView() } label: {
                            StatusBanner(message: "許可が\(ringStatus.missingPermissions)つ足りません。鳴らない・止められないことがあります", action: "直す")
                        }
                        .buttonStyle(.plain)
                    }
                    if ringStatus.recentMissed > 0 {
                        // 行き先は件数と同じ鳴動の記録 (24時間分が必ず残る)。件数で消える監査ログには送らない
                        NavigationLink { MissedAlarmsView(records: recentMissedRecords) } label: {
                            StatusBanner(message: "直近24時間に鳴らせなかったアラームが\(ringStatus.recentMissed)件あります", action: "見る")
                        }
                        .buttonStyle(.plain)
                    }
                    // ヘッダー天気 (元アプリ: 現在気温+時間別予報)。地点設定がある時だけ。
                    if let nf = nowForecast {
                        WeatherHeaderRow(forecast: nf)
                    }
                    // Night UI: 4-way セグメント (一覧/3日/月/タイムライン) + 検索/同期
                    HStack(spacing: 10) {
                        NightSegment(labels: modes.map { $0.label }, selected: Binding(
                            get: { modes.firstIndex(of: viewMode) ?? 0 },
                            set: { i in
                                viewMode = modes[i]
                                if modes[i] != .list { focusDate = Date() }
                            }))
                        Button {
                            searchOpen.toggle()
                            if !searchOpen { searchText = "" }
                        } label: {
                            Image(systemName: "magnifyingglass")
                                .foregroundStyle(searchOpen ? accent : .secondary)
                        }
                        Button { Task { await engine.resync(reason: "manual") } } label: {
                            Image(systemName: "arrow.clockwise")
                                .foregroundStyle(.secondary)
                        }
                    }
                    switch viewMode {
                    case .list:
                        ForEach(groupedDays, id: \.0) { day, events in
                            DayCard(dayLabel: dayLabel(day), events: events, calendarColor: calColor, onTap: { ev in selected = ev },
                                    onToggle: { ev, on in toggleRing(ev, on) })
                        }
                        if groupedDays.isEmpty { emptyState }
                    case .month:
                        MonthGridView(month: focusDate, events: filteredEvents, focusDate: $focusDate, calendarColor: calColor, onSelect: { selected = $0 })
                    case .threeDay:
                        ThreeDayColumnsView(startDate: focusDate, events: filteredEvents, calendarColor: calColor, onSelect: { selected = $0 })
                    case .timeline:
                        DayTimelineViewIOS(date: focusDate, events: filteredEvents, calendarColor: calColor, onSelect: { selected = $0 })
                    }
                }
                .padding()
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .onReceive(Self.minuteTimer) { clockTick = $0 }
            .safeAreaInset(edge: .top) {
                if searchOpen {
                    TextField("タイトルで絞り込み", text: $searchText)
                        .textFieldStyle(.roundedBorder)
                        .padding(.horizontal)
                        .padding(.vertical, 4)
                        .background(.bar)
                }
            }
        }
        .sheet(item: $selected) { ev in
            EventDetailSheet(display: ev, weather: weatherByLoc[ev.event.location],
                             weatherAt: weatherAtByLoc[ev.event.location])
        }
        .task { await refreshWeather() }
        .task { await center.refresh(engine) }
    }

    // MARK: - データ整形

    /// セグメント順 = 一覧/3日/月/タイムライン (Night UI スペック)
    private let modes: [AgendaViewMode] = [.list, .threeDay, .month, .timeline]
    private var accent: Color { AppPalette.byId(store.state.themeId).accent }
    private static var todayLabel: String {
        let f = DateFormatter(); f.dateFormat = "M月d日 (E)"
        f.locale = Locale(identifier: "ja_JP")
        return f.string(from: Date())
    }

    private var displayEvents: [DisplayEvent] {
        let now = Engine.nowMillis
        let horizon = now + 14 * 86_400_000
        return store.state.lastEvents
            .filter { $0.endMillis >= now && $0.startMillis < horizon }
            .sorted { $0.startMillis < $1.startMillis }
            .map { ev in
                let ov = store.state.overrides[ev.instanceKey]
                let rule = store.rule(for: ev.calendarId)
                let cal = store.state.calendars.first { $0.id == ev.calendarId }
                // 終日を鳴らさないカレンダーの終日予定は、個別指定より先に展開で外れる (shared `AlarmExpander.allDaySuppressed`)
                let allDayOff = ev.allDay && rule.allDayMinutes < 0
                return DisplayEvent(
                    event: ev,
                    calendarName: cal?.name ?? "",
                    calendarColor: cal?.color ?? 0xFF888888,
                    // 招待フィルタもミュート判定に含める (一覧表示と鳴動の一致)
                    // 予約の展開と同じ判定を使い、一覧の鳴る／鳴らない表示を実際の予約と一致させる (#79)
                    // 行のスイッチが実際の予約と食い違わないよう、開始とリマインダーの解決後の動作まで見る
                    muted: !rule.enabled || allDayOff
                        || !passesEventFilters(ev, ov, store.state.inviteFilter, store.state.eventTypeFilter)
                        || resolvedActionsMuted(ev, rule, ov, store.state.titleCodes),
                    effectiveMinutes: ov?.minutesBefore ?? rule.minutesBefore,
                    calendarOff: !rule.enabled,
                    allDayOff: allDayOff
                )
            }
    }

    /// 検索クエリ適用後のイベント (タイトル部分一致)
    private var filteredEvents: [DisplayEvent] {
        if searchText.isEmpty { return displayEvents }
        return displayEvents.filter { $0.event.title.localizedCaseInsensitiveContains(searchText) }
    }

    private var groupedDays: [(String, [DisplayEvent])] {
        let df = DateFormatter()
        df.dateFormat = "yyyy-MM-dd"
        var groups: [(String, [DisplayEvent])] = []
        for ev in filteredEvents {
            let key = df.string(from: Date(timeIntervalSince1970: TimeInterval(ev.event.startMillis) / 1000))
            if let i = groups.firstIndex(where: { $0.0 == key }) {
                groups[i].1.append(ev)
            } else {
                groups.append((key, [ev]))
            }
        }
        return groups
    }

    private var nextAlarm: AlarmInstanceDTO? {
        store.state.scheduled.values
            .filter { $0.state == .pending && $0.instance.triggerAtMillis > Engine.nowMillis }
            .map { $0.instance }
            .sorted { $0.triggerAtMillis < $1.triggerAtMillis }
            .first
    }

    private func calColor(_ ev: DisplayEvent) -> Color { Color(argb: ev.calendarColor) }

    private func dayLabel(_ key: String) -> String {
        let df = DateFormatter()
        df.dateFormat = "yyyy-MM-dd"
        guard let d = df.date(from: key) else { return key }
        if Calendar.current.isDateInToday(d) { return "今日" }
        if Calendar.current.isDateInTomorrow(d) { return "明日" }
        let out = DateFormatter()
        out.dateFormat = "M/d (E)"
        out.locale = Locale(identifier: "ja_JP")
        return out.string(from: d)
    }

    /// 鳴動の状態 (#51 PR2)。Android と同じ優先順 (shared `RingStatus`)
    private var ringStatus: RingStatusSummary {
        let missing = PermissionPlan.missingForRinging(center.states)
        return RingStatusSummary(missingPermissions: missing, recentMissed: recentMissedRecords.count, hasNextAlarm: nextAlarm != nil)
    }

    /// 直近24時間に鳴らせなかった予約 (件数と一覧の両方の元)。監査ログ (50件) や予約の行 (単発を使い直すと消える)
    /// ではなく、鳴動の結果だけの記録から鳴動時刻で取る (#51 PR3)。clockTick で1分ごとに取り直す
    private var recentMissedRecords: [AuditEntry] {
        _ = clockTick
        return RingHistory.recentMissed(store.state.ringHistory, nowMillis: Engine.nowMillis)
    }

    /// 行のスイッチ: その予定だけ鳴らす／鳴らさない (個別指定は種類などの設定より優先される)
    private func toggleRing(_ ev: DisplayEvent, _ on: Bool) {
        let ov = store.state.overrides[ev.event.instanceKey]
        store.setOverride(instanceKey: ev.event.instanceKey, action: on ? .alarm : .mute,
                          minutes: ov?.minutesBefore, extras: ov?.extraOffsets)
        Task { await engine.resync(reason: "event toggle") }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "calendar.badge.checkmark")
                .font(.system(size: 40))
                .foregroundStyle(.secondary)
            Text("この先2週間の予定はありません")
                .font(NightTheme.font(15, weight: .medium))
                .foregroundStyle(.secondary)
            // 予定が無い時の画面 (#51 PR2): 何をすれば鳴るようになるかを1行で
            Text("カレンダーに予定を入れると、ここに並んで前もって鳴ります")
                .font(NightTheme.font(12))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 80)
    }

    private func refreshWeather() async {
        guard store.state.weatherEnabled else { return }
        let svc = WeatherService()
        // ヘッダー用 現在+時間別
        let loc = store.state.weatherLocation
        if store.state.weatherHeaderEnabled && !loc.isEmpty {
            nowForecast = await svc.now(for: loc, hours: 9)
        } else {
            nowForecast = nil
        }
        let locs = Set(displayEvents.map { $0.event.location }.filter { !$0.isEmpty })
        for loc in locs {
            if let f = await svc.forecast(for: loc) {
                weatherByLoc[loc] = f
                weatherAtByLoc[loc] = Date()
            }
        }
    }
}

/// 現在気温 + 時間別チップの1行 (Android WeatherHeaderRow と同等)。
struct WeatherHeaderRow: View {
    let forecast: WeatherService.NowForecast
    private static let hourFmt: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "H時"; return f
    }()
    private static let isoFmt: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd'T'HH:mm"; return f
    }()

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 14) {
                Label(
                    "\(Int(forecast.current.temperature))°",
                    systemImage: WeatherService.icon(forecast.current.weatherCode)
                )
                .font(NightTheme.font(14, weight: .medium))
                ForEach(forecast.hourly.prefix(9), id: \.time) { h in
                    let hr = Self.isoFmt.date(from: h.time).map { Self.hourFmt.string(from: $0) } ?? ""
                    HStack(spacing: 4) {
                        Text(hr)
                        Image(systemName: WeatherService.icon(h.weatherCode))
                        Text("\(Int(h.temperature))°")
                    }
                    .font(NightTheme.font(12))
                    .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 4)
        }
        .scrollIndicators(.hidden)
    }
}

// MARK: - 部品

/// 予定画面の次のアラーム欄 (#51 PR2 で小さくした): タイトルと時刻を1段に、下に鳴動の状態を1行。
/// アラーム一覧の大きい欄 (NextAlarmBanner) はそのまま
struct AgendaNextAlarmCard: View {
    let instance: AlarmInstanceDTO?
    let accent: Color
    let statusLine: String
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("次のアラーム")
                        .font(NightTheme.font(12, weight: .semibold))
                        .tracking(1)
                        .foregroundStyle(accent)
                    Text(instance?.title ?? "なし")
                        .font(NightTheme.font(15))
                        .foregroundStyle(NightTheme.muted)
                        .lineLimit(1)
                }
                Spacer()
                if let instance {
                    Text(Self.timeStr(instance.triggerAtMillis))
                        .font(NightTheme.numFont(36, weight: .light))
                        .foregroundStyle(NightTheme.onNight)
                }
            }
            Text(statusLine)
                .font(NightTheme.font(12))
                .foregroundStyle(.secondary)
                .lineLimit(2)
                .accessibilityLabel("鳴動の状態: \(statusLine)")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 18).padding(.vertical, 12)
        .background(accent.opacity(0.15), in: RoundedRectangle(cornerRadius: 22))
    }

    static func timeStr(_ millis: Int64) -> String {
        let f = DateFormatter(); f.dateFormat = "H:mm"
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
    }
}

/// 許可が足りない時・鳴らせなかった時のバナー (#51 PR2)
struct StatusBanner: View {
    let message: String
    let action: String?
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            Text(message).font(NightTheme.font(12)).foregroundStyle(.primary)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let action {
                Text(action).font(NightTheme.font(12, weight: .semibold)).foregroundStyle(NightTheme.indigo)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 16))
    }
}

/// 直近24時間に鳴らせなかったアラームの一覧 (見逃しバナーの行き先)。件数と同じ予約の行を並べる
struct MissedAlarmsView: View {
    let records: [AuditEntry]
    var body: some View {
        List {
            if records.isEmpty {
                Text("直近24時間に鳴らせなかったアラームはありません").foregroundStyle(.secondary)
            }
            ForEach(records) { rec in
                VStack(alignment: .leading, spacing: 2) {
                    Text((rec.title ?? "").isEmpty ? "(タイトルなし)" : rec.title ?? "")
                    Text(Self.label(rec.triggerAtMillis ?? rec.atMillis))
                        .font(NightTheme.font(12)).foregroundStyle(.secondary)
                }
            }
            Section {
                Text("端末の電源が切れていた、通知の許可が外れていた、などで鳴らせなかった分です")
                    .font(NightTheme.font(12)).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("鳴らせなかったアラーム")
    }

    static func label(_ millis: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ja_JP")
        f.dateFormat = "M/d (E) HH:mm に鳴る予定でした"
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
    }
}

/// 鳴動の状態の1行 (Android と同じ文言・優先順。正本は shared の `RingStatus`)
struct RingStatusSummary {
    let missingPermissions: Int
    let recentMissed: Int
    let hasNextAlarm: Bool

    var line: String {
        if missingPermissions > 0 { return "許可が\(missingPermissions)つ足りません。このままだと鳴らないことがあります" }
        if recentMissed > 0 { return "直近24時間に鳴らせなかったアラームが\(recentMissed)件あります" }
        if !hasNextAlarm { return "予約中のアラームはありません" }
        return "鳴る準備ができています"
    }
}

/// Night UI hero card: accent-soft 背景 r28 + Outfit 64 時刻 + ドット+タイトル
struct NextAlarmBanner: View {
    let instance: AlarmInstanceDTO
    let accent: Color
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("次のアラーム")
                .font(NightTheme.font(12, weight: .semibold))
                .tracking(1)
                .foregroundStyle(accent)
            Text(Self.timeStr(instance.triggerAtMillis))
                .font(NightTheme.numFont(64, weight: .light))
                .tracking(-2)
                .foregroundStyle(NightTheme.onNight)
            HStack(spacing: 8) {
                Circle().fill(accent).frame(width: 8, height: 8)
                Text(instance.title)
                    .font(NightTheme.font(15))
                    .foregroundStyle(NightTheme.muted)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 22).padding(.vertical, 18)
        .background(accent.opacity(0.15), in: RoundedRectangle(cornerRadius: 28))
    }
    static func timeStr(_ millis: Int64) -> String {
        let f = DateFormatter(); f.dateFormat = "H:mm"
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
    }
}

struct DayCard: View {
    let dayLabel: String
    let events: [DisplayEvent]
    let calendarColor: (DisplayEvent) -> Color
    let onTap: (DisplayEvent) -> Void
    /// 渡された時は行の右端を鳴らす／鳴らさないのスイッチにする
    var onToggle: ((DisplayEvent, Bool) -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(dayLabel)
                .font(NightTheme.font(13, weight: .medium))
                .foregroundStyle(NightTheme.indigo)
                .padding(.bottom, 6)
            VStack(spacing: 0) {
                ForEach(events) { ev in
                    EventRow(ev: ev, accent: calendarColor(ev), onToggle: onToggle.map { t in { on in t(ev, on) } })
                        .onTapGesture { onTap(ev) }
                }
            }
            .background(NightTheme.nightSurface, in: RoundedRectangle(cornerRadius: 22))
        }
    }
}

/// 予定行: Outfit 時刻列 + カレンダー色ドット + タイトル + ベル + オフセット (Night UI)
struct EventRow: View {
    let ev: DisplayEvent
    let accent: Color
    var onToggle: ((Bool) -> Void)? = nil

    var body: some View {
        HStack(spacing: 12) {
            Text(Self.timeStr(ev.event))
                .font(NightTheme.numFont(20, weight: .medium))
                .frame(width: 52, alignment: .leading)
            Circle()
                .fill(accent)
                .frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 2) {
                Text(ev.event.title)
                    .font(NightTheme.font(15))
                    .lineLimit(1)
                    .foregroundStyle(ev.muted ? .secondary : .primary)
                if !ev.event.location.isEmpty || !ev.calendarName.isEmpty {
                    Text([ev.calendarName, ev.event.location].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(NightTheme.font(12))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer()
            if ev.muted {
                if onToggle == nil {
                    Image(systemName: "bell.slash").foregroundStyle(.secondary).font(.system(size: 14))
                }
            } else {
                if onToggle == nil {
                    Image(systemName: "bell.fill").foregroundStyle(accent).font(.system(size: 14))
                }
                if ev.effectiveMinutes > 0 {
                    Text("\(ev.effectiveMinutes)分前")
                        .font(NightTheme.font(11))
                        .foregroundStyle(accent)
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(accent.opacity(0.15), in: RoundedRectangle(cornerRadius: 10))
                }
            }
            if let onToggle {
                // カレンダーごと、または終日の予定を鳴らさない設定の時は、予定のスイッチを入れても鳴らないので押せなくする
                Toggle("", isOn: Binding(get: { !ev.muted }, set: { onToggle($0) }))
                    .labelsHidden()
                    .disabled(ev.calendarOff || ev.allDayOff)
                    .accessibilityLabel(ev.calendarOff ? "カレンダーが鳴らさない設定です。設定のカレンダー欄で変えられます"
                        : ev.allDayOff ? "このカレンダーは終日の予定を鳴らさない設定です。設定のカレンダー欄で変えられます"
                        : "\(ev.event.title)で鳴らす")
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    static func timeStr(_ ev: CalendarEventDTO) -> String {
        if ev.allDay { return "終日" }
        let f = DateFormatter(); f.dateFormat = "HH:mm"
        let s = f.string(from: Date(timeIntervalSince1970: TimeInterval(ev.startMillis) / 1000))
        // 0:00〜0:00 の実質終日は「終日」に統一 (Android と同じ表示規則)
        let e = f.string(from: Date(timeIntervalSince1970: TimeInterval(ev.endMillis) / 1000))
        return s == "00:00" && e == "00:00" ? "終日" : s
    }
}

/// 開始とリマインダーの両方が鳴らない (MUTE) か。shared `AlarmExpander.resolveActions` と同じ優先順:
/// 予定の個別指定 > never コード > always コード > カレンダーの既定
func resolvedActionsMuted(_ ev: CalendarEventDTO, _ rule: AlarmRuleDTO, _ ov: EventOverrideDTO?, _ codes: TitleCodeSettings) -> Bool {
    func matches(_ list: [String]) -> Bool {
        list.contains { !$0.trimmingCharacters(in: .whitespaces).isEmpty && ev.title.localizedCaseInsensitiveContains($0) }
    }
    func resolve(_ apply: Bool, _ fallback: String) -> String {
        if let a = ov?.action { return a }
        if apply && matches(codes.neverCodes) { return EventAction.mute.rawValue }
        if apply && matches(codes.alwaysCodes) { return EventAction.alarm.rawValue }
        return fallback
    }
    let mute = EventAction.mute.rawValue
    return resolve(codes.applyToStart, rule.startAction) == mute && resolve(codes.applyToReminders, rule.reminderAction) == mute
}
