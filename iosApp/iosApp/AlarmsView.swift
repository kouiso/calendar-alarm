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

    private func alarmRow(_ a: StandaloneAlarmDTO) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(String(format: "%02d:%02d", a.hour, a.minute))
                        .font(NightTheme.font(42, weight: .light))
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
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
        .onTapGesture { editing = a; editingNew = false }
    }

    private func repeatLabel(_ a: StandaloneAlarmDTO) -> String {
        if a.daysOfWeek.isEmpty { return "1回のみ" }
        if a.daysOfWeek.count == 7 { return "毎日" }
        let order = ["SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"]
        return order.filter { a.daysOfWeek.contains($0) }.compactMap { Self.wdNames[$0] }.joined()
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
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
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
