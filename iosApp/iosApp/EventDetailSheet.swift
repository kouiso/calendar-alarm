import SwiftUI

/// 予定詳細シート: ベルトグル + タイミング選択 + 追加リマインダーチップ + 場所/天気。
/// Android EventDetailSheet と同じ構成 (アイコン主導・説明文なし)。
struct EventDetailSheet: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss

    let display: DisplayEvent
    let weather: [WeatherService.Forecast]?
    let weatherAt: Date?

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
                    // タイトルを主役にして一番上に置く。天気はその下の小さいカード (#51 PR3)
                    header
                    if let f = eventForecast { weatherCard(f) }
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
            // 予定詳細はタイトルを主役にする (#51 PR3: 28〜34 の太字)
            Text(ev.title).font(NightTheme.font(30, weight: .bold)).lineLimit(3)
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

    /// 予定の日の予報。予報は今日から3日分あり、先頭 (今日) ではなく予定の日の分を使う。
    /// 予定の日が予報の範囲の外なら天気は出さない
    private var eventForecast: WeatherService.Forecast? {
        let start = Date(timeIntervalSince1970: TimeInterval(ev.startMillis) / 1000)
        return WeatherService.forecast(on: start, allDay: ev.allDay, in: weather)
    }

    /// 小さい天気カード: アイコン40 + 気温34 + 天気・降水 + 「地点・HH:mm の予報」 (#51 PR3)。
    /// HH:mm は予報を取った時刻 (日ごとの予報なので、予定の時刻の予報ではない)
    private func weatherCard(_ f: WeatherService.Forecast) -> some View {
        HStack(spacing: 12) {
            Image(systemName: WeatherService.icon(f.weatherCode))
                .symbolRenderingMode(.multicolor)
                .font(.system(size: 40))
            Text("\(Int(f.tempMax))°")
                .font(NightTheme.numFont(34, weight: .light))
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Text(SharedDomain.weatherDescription(f.weatherCode))
                    Text("\(Int(f.tempMax))°/\(Int(f.tempMin))°")
                    if let p = f.precipitationProbability { Text("降水\(p)%") }
                }
                .font(NightTheme.font(13, weight: .medium))
                Text("\(ev.location)・\(weatherAt.map { Self.hmFmt.string(from: $0) } ?? "--:--") の予報")
                    .font(NightTheme.font(11))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(NightTheme.nightSurface, in: RoundedRectangle(cornerRadius: 16))
    }

    private static let hmFmt: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "HH:mm"; return f
    }()

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
