import SwiftUI

/// アラームタブ: 単発/曜日繰り返しアラーム一覧 + 新規作成。
struct AlarmsView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @State private var editing: StandaloneAlarmDTO? = nil
    @State private var editingNew = false
    @State private var deleteTarget: StandaloneAlarmDTO? = nil

    private static let wdNames = ["SUNDAY": "日", "MONDAY": "月", "TUESDAY": "火", "WEDNESDAY": "水",
                                  "THURSDAY": "木", "FRIDAY": "金", "SATURDAY": "土"]

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 12) {
                    // Night UI: H1 + 44px FAB
                    HStack {
                        Text("アラーム")
                            .font(NightTheme.font(30, weight: .semibold))
                        Spacer()
                        Button {
                            editing = StandaloneAlarmDTO(hour: 8, minute: 0)
                            editingNew = true
                        } label: {
                            Image(systemName: "plus")
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundStyle(.white)
                                .frame(width: 44, height: 44)
                                .background(Color.accentColor, in: Circle())
                        }
                    }
                    .padding(.bottom, 4)
                    if let next = nextAlarmInstance {
                        NextAlarmBanner(instance: next, accent: accent)
                    }
                    ForEach(store.state.standaloneAlarms.sorted { ($0.hour, $0.minute) < ($1.hour, $1.minute) }) { a in
                        alarmRow(a)
                    }
                    if store.state.standaloneAlarms.isEmpty {
                        VStack(spacing: 12) {
                            Image(systemName: "alarm.slash").font(.system(size: 40)).foregroundStyle(.secondary)
                            Text("アラームなし").foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity).padding(.top, 80)
                    }
                }
                .padding()
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        editing = StandaloneAlarmDTO(hour: 8, minute: 0)
                        editingNew = true
                    } label: { Image(systemName: "plus") }
                }
            }
        }
        .sheet(item: $editing) { a in
            AlarmEditView(alarm: a, isNew: editingNew)
        }
        .alert("このアラームを削除する?", isPresented: .constant(deleteTarget != nil), presenting: deleteTarget) { a in
            Button("削除", role: .destructive) {
                store.deleteAlarm(id: a.id)
                deleteTarget = nil
                Task { await engine.resync(reason: "alarm delete") }
            }
            Button("やめる", role: .cancel) { deleteTarget = nil }
        }
    }

    private var accent: Color { AppPalette.byId(store.state.themeId).accent }
    /// 最も近い pending の ALARM インスタンス (次発バナー用)
    private var nextAlarmInstance: AlarmInstanceDTO? {
        store.state.scheduled.values
            .filter { $0.instance.standaloneAlarmId != nil && $0.state == .pending }
            .map { $0.instance }
            .min { $0.triggerAtMillis < $1.triggerAtMillis }
    }

    private func alarmRow(_ a: StandaloneAlarmDTO) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(String(format: "%02d:%02d", a.hour, a.minute))
                        .font(NightTheme.numFont(44, weight: .light))
                        .foregroundStyle(a.enabled ? .primary : .secondary)
                    Text(repeatLabel(a))
                        .font(NightTheme.font(13))
                        .foregroundStyle(.secondary)
                }
                HStack(spacing: 10) {
                    if !a.label.isEmpty {
                        Text(a.label).font(NightTheme.font(13)).foregroundStyle(.secondary)
                    }
                    if !a.exceptions.isEmpty {
                        Text("休止 \(a.exceptions.count)").font(NightTheme.font(11)).foregroundStyle(.tertiary)
                    }
                    if let next = nextInstance(a) {
                        Text("次 \(Engine.hm(next.triggerAtMillis))").font(NightTheme.font(11)).foregroundStyle(.tertiary)
                    }
                }
            }
            Spacer()
            VStack(spacing: 8) {
                Toggle("", isOn: Binding(get: { a.enabled }, set: { on in
                    store.setAlarmEnabled(id: a.id, on)
                    Task { await engine.resync(reason: "alarm toggle") }
                })).labelsHidden()
                Button { deleteTarget = a } label: {
                    Image(systemName: "trash").foregroundStyle(.secondary).font(.system(size: 14))
                }
            }
        }
        .padding(16)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 22))
        .onTapGesture { editing = a; editingNew = false }
    }

    private func repeatLabel(_ a: StandaloneAlarmDTO) -> String {
        switch a.effectiveRepeatMode {
        case "ONCE": return "1回のみ"
        case "MONTHLY": return "毎月"
        case "INTERVAL_DAYS": return "\(a.repeatInterval)日ごと"
        case "INTERVAL_WEEKS": return "\(a.repeatInterval)週ごと"
        case "INTERVAL_MONTHS": return "\(a.repeatInterval)ヶ月ごと"
        default:
            // 曜日空のWEEKLYはONCEとして鳴る (展開側と同じ意味)
            if a.daysOfWeek.isEmpty { return "1回のみ" }
            if a.daysOfWeek.count == 7 { return "毎日" }
            let order = ["SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"]
            return order.filter { a.daysOfWeek.contains($0) }.compactMap { Self.wdNames[$0] }.joined()
        }
    }

    private func nextInstance(_ a: StandaloneAlarmDTO) -> AlarmInstanceDTO? {
        store.state.scheduled.values
            .filter { $0.instance.standaloneAlarmId == a.id && $0.state == .pending && $0.instance.triggerAtMillis > Engine.nowMillis }
            .map { $0.instance }
            .sorted { $0.triggerAtMillis < $1.triggerAtMillis }
            .first
    }
}

/// アラーム編集: アイコン行構成 (曜日/休止日/スヌーズ/音/ラベル)。
struct AlarmEditView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss

    @State var alarm: StandaloneAlarmDTO
    let isNew: Bool
    @State private var newException = Date()
    @State private var anchorDate = Date()

    private static let weekdays = ["SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"]
    private static let wdNames = ["SUNDAY": "日", "MONDAY": "月", "TUESDAY": "火", "WEDNESDAY": "水",
                                  "THURSDAY": "木", "FRIDAY": "金", "SATURDAY": "土"]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker("時刻", selection: Binding(
                        get: { dateFrom(hour: alarm.hour, minute: alarm.minute) },
                        set: { let c = Calendar.current.dateComponents([.hour, .minute], from: $0)
                               alarm.hour = c.hour ?? 8; alarm.minute = c.minute ?? 0 }),
                        displayedComponents: .hourAndMinute)
                    TextField("ラベル", text: $alarm.label)
                }
                Section {
                    iconRow("repeat") {
                        VStack(alignment: .leading, spacing: 8) {
                            // 繰返しモード (元アプリ: 1回のみ/曜日/毎月/x日ごと/x週ごと/xヶ月ごと)
                            Picker("", selection: Binding(
                                get: { alarm.effectiveRepeatMode },
                                set: { alarm.repeatMode = $0 },
                            )) {
                                Text("1回").tag("ONCE")
                                Text("曜日").tag("WEEKLY")
                                Text("毎月").tag("MONTHLY")
                                Text("日ごと").tag("INTERVAL_DAYS")
                                Text("週ごと").tag("INTERVAL_WEEKS")
                                Text("月ごと").tag("INTERVAL_MONTHS")
                            }.pickerStyle(.menu).labelsHidden()
                            if alarm.effectiveRepeatMode == "WEEKLY" {
                                HStack(spacing: 6) {
                                    ForEach(Self.weekdays, id: \.self) { wd in
                                        let on = alarm.daysOfWeek.contains(wd)
                                        Button {
                                            if on { alarm.daysOfWeek.remove(wd) } else { alarm.daysOfWeek.insert(wd) }
                                        } label: {
                                            Text(Self.wdNames[wd] ?? "?")
                                                .font(NightTheme.font(13, weight: .medium))
                                                .frame(width: 32, height: 32)
                                                .background(on ? NightTheme.indigo : Color(uiColor: .tertiarySystemGroupedBackground), in: Circle())
                                                .foregroundStyle(on ? .white : .secondary)
                                        }.buttonStyle(.plain)
                                    }
                                }
                            }
                            // 起点日: 毎月=この「日」、周期=この日から数える
                            if ["MONTHLY", "INTERVAL_DAYS", "INTERVAL_WEEKS", "INTERVAL_MONTHS"]
                                .contains(alarm.effectiveRepeatMode) {
                                DatePicker(alarm.effectiveRepeatMode == "MONTHLY" ? "毎月の日" : "起点日",
                                           selection: $anchorDate, displayedComponents: .date)
                                    .font(NightTheme.font(13))
                            }
                            if ["INTERVAL_DAYS", "INTERVAL_WEEKS", "INTERVAL_MONTHS"]
                                .contains(alarm.effectiveRepeatMode) {
                                HStack(spacing: 8) {
                                    ForEach([1, 2, 3, 5, 7, 10, 14, 30], id: \.self) { n in
                                        let on = alarm.repeatInterval == n
                                        Button { alarm.repeatInterval = n } label: {
                                            Text("\(n)")
                                                .font(NightTheme.font(12, weight: .medium))
                                                .padding(.horizontal, 10).padding(.vertical, 6)
                                                .background(on ? NightTheme.indigo.opacity(0.2) : Color(uiColor: .tertiarySystemGroupedBackground), in: Capsule())
                                                .foregroundStyle(on ? NightTheme.indigo : .secondary)
                                        }.buttonStyle(.plain)
                                    }
                                }
                            }
                        }
                    }
                    // 休止日
                    iconRow("calendar.badge.minus") {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text("休止日").font(NightTheme.font(13)).foregroundStyle(.secondary)
                                Spacer()
                                DatePicker("", selection: $newException, displayedComponents: .date).labelsHidden()
                                Button {
                                    alarm.exceptions.insert(Self.dateKey(newException))
                                } label: { Image(systemName: "plus.circle.fill").foregroundStyle(NightTheme.indigo) }
                            }
                            if !alarm.exceptions.isEmpty {
                                FlowChips(items: alarm.exceptions.sorted()) { d in
                                    Chip(text: d.dropFirst(5).description) {
                                        alarm.exceptions.remove(d)
                                    }
                                }
                            }
                        }
                    }
                    iconRow("zzz") {
                        HStack(spacing: 8) {
                            ForEach([5, 10, 15], id: \.self) { m in
                                let on = alarm.snoozeMinutes == m
                                Button { alarm.snoozeMinutes = m } label: {
                                    Text("\(m)分")
                                        .font(NightTheme.font(12, weight: .medium))
                                        .padding(.horizontal, 10).padding(.vertical, 6)
                                        .background(on ? NightTheme.indigo.opacity(0.2) : Color(uiColor: .tertiarySystemGroupedBackground), in: Capsule())
                                        .foregroundStyle(on ? NightTheme.indigo : .secondary)
                                }.buttonStyle(.plain)
                            }
                        }
                    }
                }
            }
            .navigationTitle(isNew ? "アラーム" : "アラーム編集")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear(perform: loadAnchor)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        applyAnchor()
                        _ = store.upsertAlarm(alarm)
                        dismiss()
                        Task { await engine.resync(reason: "alarm save") }
                    }
                    .font(NightTheme.font(16, weight: .semibold))
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                }
            }
        }
    }

    private func iconRow(_ icon: String, @ViewBuilder content: () -> some View) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon).frame(width: 22).foregroundStyle(.secondary)
            content()
        }
    }

    /// 編集画面を開いた時点で起点日を復元する (UTC 日付 → ローカル Date)。
    private func loadAnchor() {
        if let m = alarm.repeatAnchorMillis {
            anchorDate = Date(timeIntervalSince1970: TimeInterval(m) / 1000)
        }
    }

    /// 選択されたローカル日付を「その日付の UTC 0時」millis として保存する。
    /// 展開側 (Kotlin) は UTC の日として読むので、暦日単位で往復させる。
    private func applyAnchor() {
        guard ["MONTHLY", "INTERVAL_DAYS", "INTERVAL_WEEKS", "INTERVAL_MONTHS"]
            .contains(alarm.effectiveRepeatMode) else {
            alarm.repeatAnchorMillis = nil
            return
        }
        let comps = Calendar.current.dateComponents([.year, .month, .day], from: anchorDate)
        var utc = Calendar(identifier: .gregorian); utc.timeZone = TimeZone(identifier: "UTC")!
        if let d = utc.date(from: comps) {
            alarm.repeatAnchorMillis = Int64(d.timeIntervalSince1970 * 1000)
        }
        // 曜日モード以外では曜日指定は意味を持たないので空にする
        if alarm.effectiveRepeatMode != "WEEKLY" { alarm.daysOfWeek = [] }
    }

    private func dateFrom(hour: Int, minute: Int) -> Date {
        var c = DateComponents(); c.hour = hour; c.minute = minute
        return Calendar.current.date(from: c) ?? Date()
    }

    static func dateKey(_ d: Date) -> String {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"
        return f.string(from: d)
    }
}

/// 休止日チップ等の簡易フロー (横並び→折返し)
struct FlowChips<Item: Hashable, Content: View>: View {
    let items: [Item]
    @ViewBuilder let content: (Item) -> Content
    var body: some View {
        // iOS16+ Layout プロトコルで簡易フロー。件数は最大でも数十なので Lazy 不要。
        _FlowLayout {
            ForEach(items, id: \.self) { content($0) }
        }
    }
}

struct _FlowLayout: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        layout(proposal: proposal, subviews: subviews).size
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        _ = layout(proposal: proposal, subviews: subviews, in: bounds)
    }
    private func layout(proposal: ProposedViewSize, subviews: Subviews, in bounds: CGRect? = nil) -> (size: CGSize, done: Bool) {
        let maxW = bounds?.width ?? proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, lineH: CGFloat = 0
        var maxX: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x + s.width > maxW && x > 0 { x = 0; y += lineH + 6; lineH = 0 }
            if let b = bounds { v.place(at: CGPoint(x: b.minX + x, y: b.minY + y), proposal: .unspecified) }
            x += s.width + 6; lineH = max(lineH, s.height); maxX = max(maxX, x)
        }
        return (CGSize(width: maxX, height: y + lineH), true)
    }
}

/// 削除可能な小チップ (休止日表示用)
struct Chip: View {
    let text: String
    let onRemove: () -> Void
    var body: some View {
        HStack(spacing: 4) {
            Text(text).font(NightTheme.font(12))
            Button(action: onRemove) {
                Image(systemName: "xmark.circle.fill").font(.system(size: 12))
            }
        }
        .padding(.horizontal, 8).padding(.vertical, 4)
        .background(Color(uiColor: .tertiarySystemGroupedBackground), in: Capsule())
        .foregroundStyle(.secondary)
    }
}
