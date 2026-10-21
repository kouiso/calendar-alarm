import SwiftUI

/// 予定画面のビュー種別 (元アプリ: 一覧/月/3日/日タイムライン)
enum AgendaViewMode: String, CaseIterable, Identifiable {
    case list, month, threeDay, timeline
    var id: String { rawValue }
    var icon: String {
        switch self {
        case .list: return "list.bullet"
        case .month: return "calendar"
        case .threeDay: return "rectangle.split.3x1"
        case .timeline: return "clock"
        }
    }
    var label: String {
        switch self {
        case .list: return "一覧"
        case .month: return "月"
        case .threeDay: return "3日"
        // デザイン正本の推奨 (「タイムライン」は狭い幅で2行に折れる)
        case .timeline: return "時間軸"
        }
    }
}

// MARK: - 月表示

/// 月グリッド + ISO週番号列 + イベントドット。日タップで下にその日の一覧を出す。
struct MonthGridView: View {
    let month: Date
    let events: [DisplayEvent]
    @Binding var focusDate: Date
    let calendarColor: (DisplayEvent) -> Color
    let onSelect: (DisplayEvent) -> Void

    private var cal: Calendar { Calendar.current }
    private static let df: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; return f
    }()

    var body: some View {
        let first = cal.date(from: cal.dateComponents([.year, .month], from: month))!
        let weekdayOffset = (cal.component(.weekday, from: first) + 6) % 7 // 日曜始まり
        let gridStart = cal.date(byAdding: .day, value: -weekdayOffset, to: first)!
        let byDay = Dictionary(grouping: events) { Self.df.string(from: $0.startDate) }

        // Night UI: 週番号列なし / 日曜赤・土曜青 / 34px 円
        let dark = UITraitCollection.current.userInterfaceStyle == .dark
        let sundayRed = dark ? Color(red: 1, green: 0.56, blue: 0.56) : Color(red: 0.78, green: 0.16, blue: 0.16)
        let saturdayBlue = dark ? Color(red: 0.49, green: 0.53, blue: 1) : Color(red: 0.12, green: 0.44, blue: 0.82)
        VStack(spacing: 4) {
            HStack(spacing: 0) {
                ForEach(Array(["日", "月", "火", "水", "木", "金", "土"].enumerated()), id: \.offset) { i, d in
                    Text(d).font(NightTheme.font(11))
                        .foregroundStyle(i == 0 ? sundayRed : i == 6 ? saturdayBlue : .secondary)
                        .frame(maxWidth: .infinity)
                }
            }
            ForEach(0..<6, id: \.self) { w in
                let weekStart = cal.date(byAdding: .day, value: w * 7, to: gridStart)!
                if cal.isDate(weekStart, equalTo: first, toGranularity: .month) || weekStart < endOfMonth(first) {
                    HStack(spacing: 0) {
                        ForEach(0..<7, id: \.self) { d in
                            let date = cal.date(byAdding: .day, value: d, to: weekStart)!
                            let inMonth = cal.isDate(date, equalTo: first, toGranularity: .month)
                            let dayEvents = byDay[Self.df.string(from: date)] ?? []
                            let isToday = cal.isDateInToday(date)
                            let isFocus = cal.isDate(date, inSameDayAs: focusDate)
                            // テーマ色は「鳴る/オン」専用なので中立色にする (#51 PR3)。
                            // 今日 = 文字色で塗った丸、選択日 = surfaceVariant の塗りに outline の枠
                            let selected = isFocus && !isToday
                            VStack(spacing: 2) {
                                ZStack {
                                    Circle()
                                        .fill(isToday ? NightTheme.onNight : selected ? NightTheme.surfaceVariant : Color.clear)
                                        .frame(width: 34, height: 34)
                                    Circle()
                                        .stroke(NightTheme.outline, lineWidth: selected ? 1.5 : 0)
                                        .frame(width: 34, height: 34)
                                    Text("\(cal.component(.day, from: date))")
                                        .font(NightTheme.numFont(17,
                                            weight: isToday || isFocus ? .semibold : .regular))
                                        .foregroundStyle(
                                            isToday ? NightTheme.nightSurface
                                            : !inMonth ? Color.secondary.opacity(0.35)
                                            : d == 0 ? sundayRed
                                            : d == 6 ? saturdayBlue
                                            : .primary)
                                }
                                HStack(spacing: 3) {
                                    ForEach(dayEvents.prefix(2)) { ev in
                                        Circle().fill(calendarColor(ev)).frame(width: 5, height: 5)
                                    }
                                }
                                .frame(height: 5)
                            }
                            .frame(maxWidth: .infinity).frame(height: 44)
                            .onTapGesture { focusDate = date }
                        }
                    }
                }
            }
            // 選択日のイベント
            let dayEvents = byDay[Self.df.string(from: focusDate)] ?? []
            if !dayEvents.isEmpty {
                VStack(spacing: 0) {
                    ForEach(dayEvents) { ev in
                        EventRow(ev: ev, accent: calendarColor(ev))
                            .onTapGesture { onSelect(ev) }
                    }
                }
                .background(NightTheme.nightSurface,
                            in: RoundedRectangle(cornerRadius: 22))
                .padding(.top, 8)
            }
        }
    }

    private func endOfMonth(_ first: Date) -> Date {
        cal.date(byAdding: DateComponents(month: 1, day: -1), to: first)!
    }

}

private extension DisplayEvent {
    var startDate: Date { Date(timeIntervalSince1970: TimeInterval(event.startMillis) / 1000) }
    var endDate: Date { Date(timeIntervalSince1970: TimeInterval(event.endMillis) / 1000) }
}

// MARK: - 3日表示

struct ThreeDayColumnsView: View {
    let startDate: Date
    let events: [DisplayEvent]
    let calendarColor: (DisplayEvent) -> Color
    let onSelect: (DisplayEvent) -> Void

    private var cal: Calendar { Calendar.current }
    private static let df: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; return f
    }()
    private static let wdFmt: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "d(E)"; f.locale = Locale(identifier: "ja_JP"); return f
    }()

    var body: some View {
        let byDay = Dictionary(grouping: events) { Self.df.string(from: $0.startDate) }
        HStack(alignment: .top, spacing: 8) {
            ForEach(0..<3, id: \.self) { i in
                let date = cal.date(byAdding: .day, value: i, to: startDate)!
                let dayEvents = byDay[Self.df.string(from: date)] ?? []
                VStack(spacing: 6) {
                    // 今日の見出しは surfaceVariant。テーマ色は「鳴る/オン」専用なので使わない (#51 PR3)
                    Text(Self.wdFmt.string(from: date))
                        .font(NightTheme.numFont(22,
                            weight: cal.isDateInToday(date) ? .semibold : .medium))
                        .foregroundStyle(.primary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                        .background(cal.isDateInToday(date) ? NightTheme.surfaceVariant : Color.clear,
                                    in: RoundedRectangle(cornerRadius: 12))
                    ForEach(dayEvents) { ev in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(ev.event.allDay ? "終日" : Self.hm(ev.startDate))
                                .font(NightTheme.numFont(10))
                                .foregroundStyle(.secondary)
                            Text(ev.event.title)
                                .font(NightTheme.font(12))
                                .lineLimit(2)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(6)
                        .background(calendarColor(ev).opacity(0.15),
                                    in: RoundedRectangle(cornerRadius: 12))
                        .onTapGesture { onSelect(ev) }
                    }
                }
                .frame(maxWidth: .infinity)
            }
        }
        .padding(.horizontal, 8)
    }

    private static func hm(_ d: Date) -> String {
        let f = DateFormatter(); f.dateFormat = "H:mm"; return f.string(from: d)
    }
}

// MARK: - 日タイムライン

struct DayTimelineViewIOS: View {
    let date: Date
    let events: [DisplayEvent]
    let calendarColor: (DisplayEvent) -> Color
    let onSelect: (DisplayEvent) -> Void

    private let hourH: CGFloat = 48
    private var cal: Calendar { Calendar.current }

    var body: some View {
        let dayEvents = events.filter { cal.isDate($0.startDate, inSameDayAs: date) }
        let allDay = dayEvents.filter { $0.event.allDay || $0.event.endMillis - $0.event.startMillis >= 23 * 3_600_000 }
        let timed = dayEvents.filter { !allDay.contains($0) }

        ScrollViewReader { proxy in
        ScrollView {
            VStack(spacing: 8) {
                if !allDay.isEmpty {
                    HStack(spacing: 6) {
                        ForEach(allDay) { ev in
                            Text(ev.event.title)
                                .font(NightTheme.font(12))
                                .padding(6)
                                .background(calendarColor(ev).opacity(0.2),
                                            in: RoundedRectangle(cornerRadius: 8))
                                .onTapGesture { onSelect(ev) }
                        }
                    }
                }
                ZStack(alignment: .topLeading) {
                    // 時間目盛り
                    VStack(spacing: 0) {
                        ForEach(0..<24, id: \.self) { h in
                            HStack(alignment: .top, spacing: 8) {
                                Text(String(format: "%02d:00", h))
                                    .font(NightTheme.font(10))
                                    .foregroundStyle(.secondary)
                                    .frame(width: 40, alignment: .leading)
                                Rectangle().fill(Color.secondary.opacity(0.15)).frame(height: 1)
                            }
                            .frame(height: hourH, alignment: .top)
                            .id(h)
                        }
                    }
                    // イベントブロック
                    ForEach(timed) { ev in
                        let s = ev.startDate
                        let e = max(ev.endDate, s.addingTimeInterval(15 * 60))
                        let startMin = minutesOfDay(s)
                        let dur = min((e.timeIntervalSince(s) / 60), 1440 - startMin)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(ev.event.title)
                                .font(NightTheme.font(12))
                                .lineLimit(dur < 45 ? 1 : 3)
                            if dur >= 45 {
                                Text("\(hm(s))〜\(hm(e))")
                                    .font(NightTheme.font(10))
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .frame(height: hourH * CGFloat(dur / 60))
                        .background(calendarColor(ev).opacity(0.25),
                                    in: RoundedRectangle(cornerRadius: 6))
                        .offset(y: hourH * CGFloat(startMin / 60))
                        .padding(.leading, 48)
                        .onTapGesture { onSelect(ev) }
                    }
                    // 今の時刻の線 (#51 PR3)。予定のブロックより上に描く。今日を見ている時だけ。
                    // テーマ色は「鳴る/オン」専用なので色表の赤で引く
                    TimelineView(.periodic(from: .now, by: 60)) { ctx in
                        // 日付の判定も1分ごとにやり直す (開いたまま日付が変わったら前日の画面から線を消す)
                        if cal.isDate(date, inSameDayAs: ctx.date) {
                            let label = "今 \(Self.nowFmt.string(from: ctx.date))"
                            HStack(spacing: 0) {
                                // 時刻目盛りの文字に重なるので、面の色で下地を敷いて読めるようにする
                                Text(label)
                                    .font(NightTheme.numFont(10, weight: .semibold))
                                    .foregroundStyle(NightTheme.nowLine)
                                    .lineLimit(1)
                                    .frame(width: 44, alignment: .leading)
                                    .background(Color(uiColor: .systemGroupedBackground))
                                Circle().fill(NightTheme.nowLine).frame(width: 8, height: 8)
                                Rectangle().fill(NightTheme.nowLine).frame(height: 2)
                            }
                            .frame(height: 16)
                            .offset(y: hourH * CGFloat(minutesOfDay(ctx.date)) / 60 - 8)
                            .accessibilityElement(children: .ignore)
                            .accessibilityLabel(label)
                        }
                    }
                }
                .frame(height: hourH * 24)
            }
            .padding(.horizontal, 12)
        }
        // 今日を開いた時は、今の時刻の1時間前の目盛りが上に来るところまで送る (#51 PR3)
        .onAppear {
            if cal.isDateInToday(date) {
                proxy.scrollTo(max(0, cal.component(.hour, from: Date()) - 1), anchor: .top)
            }
        }
        }
    }

    private static let nowFmt: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "HH:mm"; return f
    }()

    private func minutesOfDay(_ d: Date) -> Double {
        Double(cal.component(.hour, from: d) * 60 + cal.component(.minute, from: d))
    }
    private func hm(_ d: Date) -> String {
        let f = DateFormatter(); f.dateFormat = "H:mm"; return f.string(from: d)
    }
}
