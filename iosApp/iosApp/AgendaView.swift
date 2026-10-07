import SwiftUI

/// 予定タブ: 日付カード + 次アラーム帯 + イベント行 (アイコン主導、説明文なし)。
struct AgendaView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @State private var selected: DisplayEvent?
    @State private var weatherByLoc: [String: [WeatherService.Forecast]] = [:]
    @State private var nowForecast: WeatherService.NowForecast? = nil
    @State private var viewMode: AgendaViewMode = .list
    @State private var searchText = ""
    @State private var searchOpen = false
    @State private var focusDate = Date()

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
                    if let next = nextAlarm {
                        NextAlarmBanner(instance: next)
                    }
                    // ヘッダー天気 (元アプリ: 現在気温+時間別予報)。地点設定がある時だけ。
                    if let nf = nowForecast {
                        WeatherHeaderRow(forecast: nf)
                    }
                    switch viewMode {
                    case .list:
                        ForEach(groupedDays, id: \.0) { day, events in
                            DayCard(dayLabel: dayLabel(day), events: events, calendarColor: calColor, onTap: { ev in selected = ev })
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
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    HStack(spacing: 14) {
                        ForEach(AgendaViewMode.allCases) { m in
                            Button {
                                viewMode = m
                                if m != .list { focusDate = Date() }
                            } label: {
                                Image(systemName: m.icon)
                                    .foregroundStyle(viewMode == m ? NightTheme.indigo : .secondary)
                            }
                        }
                        Button {
                            searchOpen.toggle()
                            if !searchOpen { searchText = "" }
                        } label: {
                            Image(systemName: "magnifyingglass")
                                .foregroundStyle(searchOpen ? NightTheme.indigo : .secondary)
                        }
                        Button { Task { await engine.resync(reason: "manual") } } label: {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                }
            }
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
            EventDetailSheet(display: ev, weather: weatherByLoc[ev.event.location])
        }
        .task { await refreshWeather() }
    }

    // MARK: - データ整形

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
                return DisplayEvent(
                    event: ev,
                    calendarName: cal?.name ?? "",
                    calendarColor: cal?.color ?? 0xFF888888,
                    // 招待フィルタもミュート判定に含める (一覧表示と鳴動の一致)
                    muted: ov?.muted == true || !rule.enabled
                        || !store.state.inviteFilter.allows(ev.inviteStatus),
                    effectiveMinutes: ov?.minutesBefore ?? rule.minutesBefore
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

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "calendar.badge.checkmark")
                .font(.system(size: 40))
                .foregroundStyle(.secondary)
            Text("2週間内に予定なし")
                .foregroundStyle(.secondary)
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

struct NextAlarmBanner: View {
    let instance: AlarmInstanceDTO
    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle().fill(NightTheme.indigo.opacity(0.15)).frame(width: 44, height: 44)
                Image(systemName: "bell.fill").foregroundStyle(NightTheme.indigo)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(Self.timeStr(instance.triggerAtMillis))
                    .font(NightTheme.font(22, weight: .light))
                Text(instance.title)
                    .font(NightTheme.font(14))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
        }
        .padding(14)
        .background(NightTheme.indigo.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
    }
    static func timeStr(_ millis: Int64) -> String {
        let f = DateFormatter(); f.dateFormat = "M/d HH:mm"
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
    }
}

struct DayCard: View {
    let dayLabel: String
    let events: [DisplayEvent]
    let calendarColor: (DisplayEvent) -> Color
    let onTap: (DisplayEvent) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(dayLabel)
                .font(NightTheme.font(13, weight: .medium))
                .foregroundStyle(NightTheme.indigo)
                .padding(.bottom, 6)
            VStack(spacing: 0) {
                ForEach(events) { ev in
                    EventRow(ev: ev, accent: calendarColor(ev))
                        .onTapGesture { onTap(ev) }
                }
            }
            .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
        }
    }
}

/// 予定行: 時刻列 + カレンダー色アクセントバー + タイトル + ベル + オフセット
struct EventRow: View {
    let ev: DisplayEvent
    let accent: Color

    var body: some View {
        HStack(spacing: 12) {
            Text(Self.timeStr(ev.event))
                .font(NightTheme.font(14, weight: .medium))
                .frame(width: 52, alignment: .leading)
            RoundedRectangle(cornerRadius: 2)
                .fill(accent)
                .frame(width: 3, height: 34)
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
                Image(systemName: "bell.slash").foregroundStyle(.secondary).font(.system(size: 14))
            } else {
                Image(systemName: "bell.fill").foregroundStyle(NightTheme.indigo).font(.system(size: 14))
                if ev.effectiveMinutes > 0 {
                    Text("\(ev.effectiveMinutes)分前")
                        .font(NightTheme.font(11))
                        .foregroundStyle(.secondary)
                }
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
