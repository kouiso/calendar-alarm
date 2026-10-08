import SwiftUI

/// 予定詳細シート: ベルトグル + タイミング選択 + 追加リマインダーチップ + 場所/天気。
/// Android EventDetailSheet と同じ構成 (アイコン主導・説明文なし)。
struct EventDetailSheet: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss

    let display: DisplayEvent
    let weather: [WeatherService.Forecast]?

    private let timingOptions = [("開始時", 0), ("5分前", 5), ("10分前", 10), ("15分前", 15), ("30分前", 30), ("1時間前", 60)]
    private let extraOptions = [5, 10, 15, 30, 60]

    private var ev: CalendarEventDTO { display.event }
    private var ov: EventOverrideDTO { store.state.overrides[ev.instanceKey] ?? EventOverrideDTO() }
    private var rule: AlarmRuleDTO { store.rule(for: ev.calendarId) }

    /// 3状態アクション。未指定ならルールの開始アクションを表示
    private var action: EventAction {
        ov.action.flatMap(EventAction.init(rawValue:)) ??
            EventAction(rawValue: rule.startAction) ?? .alarm
    }
    private var muted: Bool { action == .mute }
    private var minutes: Int { ov.minutesBefore ?? rule.minutesBefore }
    private var extras: [Int] { ov.extraOffsets ?? rule.extraOffsets }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if let f = weather?.first { weatherHero(f) }
                    header
                    actionPicker
                    if !muted { timingPicker }
                    extraChips
                    if !ev.location.isEmpty { locationRow }
                    if store.state.overrides[ev.instanceKey] != nil {
                        resetButton
                    }
                }
                .padding(20)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { dismiss() } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(ev.title).font(NightTheme.font(20, weight: .semibold))
            Text(Self.dateStr(ev)).font(NightTheme.font(14)).foregroundStyle(.secondary)
            if !display.calendarName.isEmpty {
                HStack(spacing: 6) {
                    Circle().fill(Color(argb: display.calendarColor)).frame(width: 8, height: 8)
                    Text(display.calendarName).font(NightTheme.font(12)).foregroundStyle(.secondary)
                }
            }
        }
    }

    /// アクション3択: アラーム鳴動 / 通知のみ / 鳴らさない (Android版と同じ)
    private var actionPicker: some View {
        HStack(spacing: 8) {
            Image(systemName: action == .mute ? "bell.slash" : "bell.fill")
                .foregroundStyle(action == .mute ? .secondary : NightTheme.indigo)
            Spacer()
            ForEach([(EventAction.alarm, "アラーム"), (.notify, "通知"), (.mute, "OFF")], id: \.0) { a, label in
                Button {
                    store.setOverride(
                        instanceKey: ev.instanceKey, action: a,
                        minutes: a == .mute ? nil : ov.minutesBefore,
                        extras: a == .mute ? nil : ov.extraOffsets)
                    Task { await engine.resync(reason: "override") }
                } label: {
                    Text(label)
                        .font(NightTheme.font(12, weight: .medium))
                        .padding(.horizontal, 12).padding(.vertical, 7)
                        .background(action == a ? NightTheme.indigo : Color(uiColor: .tertiarySystemGroupedBackground),
                                    in: Capsule())
                        .foregroundStyle(action == a ? .white : .secondary)
                }.buttonStyle(.plain)
            }
        }
    }

    private var timingPicker: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
            ForEach(timingOptions, id: \.1) { label, m in
                Button {
                    store.setOverride(instanceKey: ev.instanceKey, action: action,
                                      minutes: m, extras: ov.extraOffsets)
                    Task { await engine.resync(reason: "override") }
                } label: {
                    Text(label)
                        .font(NightTheme.font(13, weight: .medium))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(minutes == m ? NightTheme.indigo : NightTheme.nightSurface,
                                    in: RoundedRectangle(cornerRadius: 10))
                        .foregroundStyle(minutes == m ? .white : .primary)
                }
            }
        }
    }

    private var extraChips: some View {
        HStack(spacing: 8) {
            Image(systemName: "plus.bell").foregroundStyle(.secondary)
            ForEach(extraOptions, id: \.self) { m in
                let on = extras.contains(m)
                Button {
                    var e = extras
                    if on { e.removeAll { $0 == m } } else if e.count < 5 { e.append(m); e.sort() }
                    store.setOverride(instanceKey: ev.instanceKey, action: action,
                                      minutes: ov.minutesBefore, extras: e)
                    Task { await engine.resync(reason: "override") }
                } label: {
                    Text("+\(m)")
                        .font(NightTheme.font(12, weight: .medium))
                        .padding(.horizontal, 10).padding(.vertical, 6)
                        .background(on ? NightTheme.indigo.opacity(0.2) : Color(uiColor: .tertiarySystemGroupedBackground),
                                    in: Capsule())
                        .foregroundStyle(on ? NightTheme.indigo : .secondary)
                }
            }
        }
    }

    private var locationRow: some View {
        HStack(spacing: 8) {
            Image(systemName: "mappin").foregroundStyle(.secondary)
            Text(ev.location).font(NightTheme.font(13)).foregroundStyle(.secondary).lineLimit(2)
        }
    }

    /// Night UI: 天気ヒーロー (グラデ + Outfit 84 気温 + グリフ)
    private func weatherHero(_ f: WeatherService.Forecast) -> some View {
        let dark = UITraitCollection.current.userInterfaceStyle == .dark
        let top = dark ? Color(red: 0.09, green: 0.16, blue: 0.27) : Color(red: 0.79, green: 0.87, blue: 0.97)
        let bottom = dark ? Color(red: 0.08, green: 0.13, blue: 0.23) : Color(red: 0.82, green: 0.91, blue: 0.97)
        return ZStack(alignment: .bottomLeading) {
            LinearGradient(colors: [top, bottom], startPoint: .top, endPoint: .bottom)
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(Int(f.tempMax))°")
                        .font(NightTheme.numFont(84, weight: .ultraLight))
                    HStack(spacing: 10) {
                        Text("最高 \(Int(f.tempMax))° / 最低 \(Int(f.tempMin))°")
                        if let p = f.precipitationProbability { Text("降水 \(p)%") }
                    }
                    .font(NightTheme.font(13, weight: .medium))
                    .foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: WeatherService.icon(f.weatherCode))
                    .font(.system(size: 60))
                    .foregroundStyle(.white.opacity(0.9))
                    .shadow(radius: 8)
            }
            .padding(20)
        }
        .frame(maxWidth: .infinity).frame(height: 200)
        .clipShape(RoundedRectangle(cornerRadius: 24))
    }

    private var resetButton: some View {
        Button {
            store.removeOverride(instanceKey: ev.instanceKey)
            Task { await engine.resync(reason: "override clear") }
        } label: {
            Label("既定に戻す", systemImage: "arrow.uturn.backward")
                .font(NightTheme.font(13))
                .foregroundStyle(.secondary)
        }
    }

    static func dateStr(_ ev: CalendarEventDTO) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ja_JP")
        f.dateFormat = ev.allDay ? "M月d日 (E) 終日" : "M月d日 (E) HH:mm"
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(ev.startMillis) / 1000))
    }
}
