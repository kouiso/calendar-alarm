import SwiftUI
import PhotosUI

/// 設定タブ: Android 版のアイコン主導UIと同構成。
/// 見出し・説明文なし — [アイコン + チップ/スイッチ] の行だけ。
struct SettingsView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @State private var expandedCal: String? = nil
    @State private var showPerms = false
    @State private var showLog = false
    @State private var showAccountHelp = false
    @State private var showCodes = false
    @State private var photoItem: PhotosPickerItem? = nil
    @State private var weatherLocDraft = ""
    @State private var apiKeyDraft = ""
    @State private var modelDraft = ""

    private let actionOptions: [(EventAction, String)] =
        [(.alarm, "アラーム"), (.notify, "通知"), (.mute, "OFF")]

    private let minutesOptions = [0, 5, 10, 15, 30]
    private let snoozeOptions = [5, 10, 15]
    private let allDayOptions: [(String, Int)] = [("OFF", -1), ("8:00", 480), ("9:00", 540), ("12:00", 720)]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {

                    // 既定値カード: 🔔タイミング / 😴スヌーズ / ☀天気
                    Card {
                        VStack(spacing: 14) {
                            IconRow("bell.fill") {
                                Chips(items: minutesOptions, current: store.state.defaultMinutesBefore) { m in
                                    store.setDefaultMinutes(m); resync("default")
                                } label: { m in m == 0 ? "開始時" : "\(m)分前" }
                            }
                            IconRow("zzz") {
                                Chips(items: snoozeOptions, current: store.state.defaultSnoozeMinutes) { m in
                                    store.setDefaultSnooze(m); resync("default")
                                } label: { "\($0)分" }
                            }
                            IconRow("sun.max.fill") {
                                Toggle("", isOn: Binding(get: { store.state.weatherEnabled },
                                                         set: { store.setWeatherEnabled($0) })).labelsHidden()
                            }
                            // 全アラームの一括ミュート (通知・タイマーは止まらない)
                            IconRow("bell.slash.fill") {
                                Text("アラームをすべてミュート")
                                    .font(NightTheme.font(13)).foregroundStyle(.secondary)
                                Spacer()
                                Toggle("", isOn: Binding(
                                    get: { store.state.muteAll },
                                    set: { store.setMuteAll($0); resync("mute all") }
                                )).labelsHidden()
                            }
                        }
                    }

                    // 外観カード: テーマ10種 + 背景画像 + 天気地点
                    Card {
                        VStack(spacing: 14) {
                            // テーマ (10種の色スウォッチ)
                            IconRow("paintpalette.fill") {
                                ScrollView(.horizontal) {
                                    HStack(spacing: 10) {
                                        ForEach(AppPalette.allCases, id: \.self) { p in
                                            let sel = (store.state.themeId == "default" ? .indigo : AppPalette.byId(store.state.themeId)) == p
                                            Button { store.setThemeId(p == .indigo ? "default" : p.rawValue) } label: {
                                                VStack(spacing: 3) {
                                                    Circle()
                                                        .fill(p.accent)
                                                        .frame(width: 26, height: 26)
                                                        .overlay {
                                                            if sel {
                                                                Image(systemName: "checkmark")
                                                                    .font(.system(size: 11, weight: .bold))
                                                                    .foregroundStyle(.white)
                                                            }
                                                        }
                                                    Text(p.label)
                                                        .font(NightTheme.font(9))
                                                        .foregroundStyle(.secondary)
                                                }
                                            }
                                            .buttonStyle(.plain)
                                        }
                                    }
                                }
                                .scrollIndicators(.hidden)
                            }
                            // カスタム背景
                            IconRow("photo.fill") {
                                PhotosPicker(
                                    selection: $photoItem,
                                    matching: .images,
                                    photoLibrary: .shared()
                                ) {
                                    Image(systemName: "ellipsis")
                                        .font(.system(size: 16)).foregroundStyle(NightTheme.indigo)
                                }
                                .onChange(of: photoItem) { _, item in
                                    Task {
                                        if let data = try? await item?.loadTransferable(type: Data.self) {
                                            store.setCustomBackground(data: data)
                                        }
                                    }
                                }
                                if store.state.hasCustomBackground {
                                    Button { store.setCustomBackground(data: nil) } label: {
                                        Image(systemName: "xmark")
                                            .font(.system(size: 13)).foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                            }
                            // 天気地点 (空=天気UI非表示) + 表示先
                            IconRow("location.fill") {
                                TextField("地点", text: $weatherLocDraft)
                                    .font(NightTheme.font(14))
                                    .textFieldStyle(.roundedBorder)
                                Button {
                                    store.setWeatherLocation(weatherLocDraft)
                                } label: {
                                    Text("保存").font(NightTheme.font(13)).foregroundStyle(NightTheme.indigo)
                                }
                            }
                            if !store.state.weatherLocation.isEmpty {
                                IconRow("rectangle.topthird.inset.filled") {
                                    Text("ヘッダー").font(NightTheme.font(13)).foregroundStyle(.secondary)
                                    Spacer()
                                    Toggle("", isOn: Binding(get: { store.state.weatherHeaderEnabled },
                                                              set: { store.setWeatherHeaderEnabled($0) })).labelsHidden()
                                }
                                IconRow("alarm.fill") {
                                    Text("鳴動画面").font(NightTheme.font(13)).foregroundStyle(.secondary)
                                    Spacer()
                                    Toggle("", isOn: Binding(get: { store.state.weatherOnAlarmScreen },
                                                              set: { store.setWeatherOnAlarmScreen($0) })).labelsHidden()
                                }
                            }
                        }
                    }
                    .onAppear {
                        weatherLocDraft = store.state.weatherLocation
                        apiKeyDraft = store.state.openRouterApiKey ?? ""
                        modelDraft = store.state.openRouterModel
                    }

                    // AI カード: OpenRouter (メール→予定・通知ルール生成に使用)
                    Card {
                        VStack(spacing: 14) {
                            IconRow("envelope.badge.fill") {
                                SecureField("OpenRouter キー", text: $apiKeyDraft)
                                    .font(NightTheme.font(14))
                                    .textFieldStyle(.roundedBorder)
                                Button {
                                    store.setOpenRouterApiKey(apiKeyDraft)
                                } label: {
                                    Text("保存").font(NightTheme.font(13)).foregroundStyle(NightTheme.indigo)
                                }
                            }
                            IconRow("brain") {
                                TextField("モデル", text: $modelDraft)
                                    .font(NightTheme.font(14))
                                    .textFieldStyle(.roundedBorder)
                                Button {
                                    store.setOpenRouterModel(modelDraft)
                                } label: {
                                    Text("保存").font(NightTheme.font(13)).foregroundStyle(NightTheme.indigo)
                                }
                            }
                        }
                    }

                    // 予定ルールカード (既定アクション/タイトルコード/招待フィルタ/リマインダー取込)
                    Card {
                        VStack(spacing: 14) {
                            // 開始時の既定アクション
                            IconRow("alarm.fill") {
                                Chips(items: actionOptions.map(\.0),
                                      current: EventAction(rawValue: store.state.defaultStartAction) ?? .alarm) { a in
                                    store.setDefaultStartAction(a); resync("default action")
                                } label: { a in actionOptions.first { $0.0 == a }?.1 ?? "" }
                            }
                            // 通知分の既定アクション
                            IconRow("bell.fill") {
                                Chips(items: actionOptions.map(\.0),
                                      current: EventAction(rawValue: store.state.defaultReminderAction) ?? .alarm) { a in
                                    store.setDefaultReminderAction(a); resync("default action")
                                } label: { a in actionOptions.first { $0.0 == a }?.1 ?? "" }
                            }
                            // タイトルコード
                            IconRow("tag.fill") {
                                let tc = store.state.titleCodes
                                Text(tc.alwaysCodes.isEmpty && tc.neverCodes.isEmpty
                                     ? "—" : "○\(tc.alwaysCodes.count) ×\(tc.neverCodes.count)")
                                    .font(NightTheme.font(13)).foregroundStyle(.secondary)
                                Spacer()
                                Button("編集") { showCodes = true }
                                    .font(NightTheme.font(13)).foregroundStyle(NightTheme.indigo)
                            }
                            // 招待予定フィルタ (複数選択)
                            IconRow("person.2.fill") {
                                let f = store.state.inviteFilter
                                MultiChips(
                                    items: [InviteStatus.accepted, .tentative, .needsAction, .declined],
                                    isOn: { s in
                                        switch s {
                                        case .accepted: return f.accepted
                                        case .tentative: return f.tentative
                                        case .needsAction: return f.needsAction
                                        case .declined: return f.declined
                                        }
                                    },
                                    onToggle: { s in store.toggleInviteStatus(s); resync("invite filter") },
                                    label: { s in
                                        switch s {
                                        case .accepted: return "出席"
                                        case .tentative: return "未定"
                                        case .needsAction: return "未回答"
                                        case .declined: return "辞退"
                                        }
                                    })
                            }
                            // イベント種別フィルタ (誕生日/不在/勤務場所/タスク/予定)
                            IconRow("square.grid.2x2.fill") {
                                let f = store.state.eventTypeFilter
                                MultiChips(
                                    items: EventType.allCases,
                                    isOn: { t in f.allows(t) },
                                    onToggle: { t in store.toggleEventType(t); resync("event type filter") },
                                    label: { t in t.label })
                            }
                            // 予定側リマインダー取込 (既定ON: 元カレンダー通知の「何分前」で鳴らす)
                            IconRow("bell.badge.fill") {
                                VStack(alignment: .leading, spacing: 1) {
                                    Text("カレンダーの通知設定を使う")
                                        .font(NightTheme.font(13))
                                    Text("開始時刻の鳴動に加えて、予定の「○分前」通知時刻でも鳴らします")
                                        .font(NightTheme.font(11)).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Toggle("", isOn: Binding(
                                    get: { store.state.importEventReminders },
                                    set: { store.setImportEventReminders($0); resync("import reminders") }
                                )).labelsHidden()
                            }
                        }
                    }

                    // 権限カード (OKなら1行、不足時のみ展開)
                    permissionCard

                    // カレンダー一覧カード
                    Card {
                        VStack(spacing: 0) {
                            ForEach(store.state.calendars) { cal in
                                calendarRow(cal)
                                if cal.id != store.state.calendars.last?.id { Divider().padding(.leading, 40) }
                            }
                            if store.state.calendars.isEmpty {
                                IconRow("calendar") {
                                    Text(store.state.calendars.isEmpty ? "カレンダー権限が必要" : "")
                                        .font(NightTheme.font(13)).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }

                    // アカウント行 + 再同期
                    Card {
                        HStack(spacing: 20) {
                            IconBtn("person.badge.plus") { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) }
                            IconBtn("info.circle") { showAccountHelp = true }
                            IconBtn("arrow.clockwise") { resync("manual") }
                            #if DEBUG
                            // 検証用: デモ予定を端末カレンダーへ投入して即 resync
                            IconBtn("ladybug") {
                                _ = engine.reader.seedDemoEvents()
                                resync("debug-seed")
                            }
                            #endif
                            Spacer()
                        }
                    }

                    // 鳴動ログ (畳み)
                    Card {
                        VStack(alignment: .leading, spacing: 0) {
                            Button { showLog.toggle() } label: {
                                HStack {
                                    Image(systemName: "list.bullet").frame(width: 22).foregroundStyle(.secondary)
                                    Text("直近 \(store.state.audit.count) 件")
                                        .font(NightTheme.font(13)).foregroundStyle(.secondary)
                                    Spacer()
                                    Image(systemName: showLog ? "chevron.up" : "chevron.down")
                                        .font(.system(size: 12)).foregroundStyle(.tertiary)
                                }
                            }
                            if showLog {
                                Divider().padding(.vertical, 8)
                                ForEach(store.state.audit.suffix(50).reversed()) { e in
                                    HStack {
                                        Text(Engine.hm(e.atMillis)).font(NightTheme.font(11)).foregroundStyle(.tertiary)
                                        Text(e.type).font(NightTheme.font(11, weight: .medium)).foregroundStyle(NightTheme.indigo)
                                        Text(e.detail).font(NightTheme.font(11)).foregroundStyle(.secondary).lineLimit(1)
                                    }
                                    .padding(.vertical, 2)
                                }
                            }
                        }
                    }
                }
                .padding()
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .sheet(isPresented: $showCodes) {
                TitleCodesSheet()
            }
            .alert("カレンダーの取り込み", isPresented: $showAccountHelp) {
                Button("OK") {}
            } message: {
                Text("アプリは端末に登録された全カレンダーを読みます。Microsoft (Outlook/Exchange) の予定は、iPhone の設定 → カレンダー → アカウント から追加すると自動で取り込まれます。")
            }
        }
    }

    // MARK: - 権限カード

    private var missingPerms: [String] {
        var missing: [String] = []
        if !engine.reader.authorizationGranted { missing.append("calendar") }
        if !engine.schedulerAuthorized { missing.append("alarm") }
        return missing
    }

    private var permissionCard: some View {
        Card {
            VStack(spacing: 0) {
                Button { if !missingPerms.isEmpty { showPerms.toggle() } } label: {
                    HStack {
                        Image(systemName: "checkmark.shield.fill").frame(width: 22)
                            .foregroundStyle(missingPerms.isEmpty ? .green : .orange)
                        if missingPerms.isEmpty {
                            Text("OK").font(NightTheme.font(13)).foregroundStyle(.secondary)
                        } else {
                            Text("要対応 \(missingPerms.count)").font(NightTheme.font(13)).foregroundStyle(.orange)
                        }
                        Spacer()
                        if !missingPerms.isEmpty {
                            Image(systemName: showPerms ? "chevron.up" : "chevron.down")
                                .font(.system(size: 12)).foregroundStyle(.tertiary)
                        }
                    }
                }
                if showPerms {
                    Divider().padding(.vertical, 8)
                    VStack(spacing: 10) {
                        if missingPerms.contains("calendar") {
                            permButton("カレンダー", "calendar")
                        }
                        if missingPerms.contains("alarm") {
                            permButton("アラーム", "bell.badge")
                        }
                        // 鳴らない時の最終確認: 10秒後に実際に鳴らして経路全体を検証
                        Button {
                            Task {
                                await engine.scheduleTimer(durationMillis: 10_000, label: "テスト鳴動")
                            }
                        } label: {
                            HStack {
                                Image(systemName: "bell.and.waves.left.and.right").foregroundStyle(NightTheme.indigo)
                                Text("10秒後に鳴動テスト").font(NightTheme.font(13))
                                Spacer()
                            }
                        }
                    }
                }
            }
        }
    }

    private func permButton(_ name: String, _ icon: String) -> some View {
        Button {
            if icon == "calendar" { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) }
            else { Task { await engine.authorizeScheduler() } }
        } label: {
            HStack {
                Image(systemName: icon).foregroundStyle(NightTheme.indigo)
                Text(name).font(NightTheme.font(13))
                Spacer()
                Image(systemName: "arrow.right").font(.system(size: 11)).foregroundStyle(.tertiary)
            }
        }
    }

    // MARK: - カレンダー行

    private func calendarRow(_ cal: CalendarSource) -> some View {
        let rule = store.rule(for: cal.id)
        let expanded = expandedCal == cal.id
        return VStack(spacing: 0) {
            Button { expandedCal = expanded ? nil : cal.id } label: {
                HStack(spacing: 10) {
                    Circle().fill(Color(argb: cal.color)).frame(width: 10, height: 10)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(cal.name).font(NightTheme.font(14, weight: .medium)).lineLimit(1)
                        Text("\(cal.accountName) · \(rule.minutesBefore == 0 ? "開始時" : "\(rule.minutesBefore)分前") · 終日\(rule.allDayMinutes < 0 ? "OFF" : "\(rule.allDayMinutes / 60):00")")
                            .font(NightTheme.font(11)).foregroundStyle(.tertiary)
                    }
                    Spacer()
                    Toggle("", isOn: Binding(get: { rule.enabled }, set: { on in
                        var r = rule; r.enabled = on
                        store.setCalendarRule(cal.id, r); resync("cal pref")
                    })).labelsHidden()
                    Image(systemName: expanded ? "chevron.up" : "chevron.down").font(.system(size: 11)).foregroundStyle(.tertiary)
                }
                .padding(.vertical, 10)
            }
            if expanded {
                VStack(alignment: .leading, spacing: 10) {
                    IconRow("bell.fill") {
                        Chips(items: minutesOptions, current: rule.minutesBefore) { m in
                            var r = rule; r.minutesBefore = m
                            store.setCalendarRule(cal.id, r); resync("cal pref")
                        } label: { $0 == 0 ? "開始時" : "\($0)分前" }
                    }
                    IconRow("calendar.badge.clock") {
                        Chips(items: allDayOptions.map(\.1), current: rule.allDayMinutes) { m in
                            var r = rule; r.allDayMinutes = m
                            store.setCalendarRule(cal.id, r); resync("cal pref")
                        } label: { m in
                            m < 0 ? "OFF" : "\(m / 60):00"
                        }
                    }
                    // 開始時 / 通知分のアクション (Android と同じ3択)
                    IconRow("alarm.fill") {
                        Chips(items: actionOptions.map(\.0),
                              current: EventAction(rawValue: rule.startAction) ?? .alarm) { a in
                            var r = rule; r.startAction = a.rawValue
                            store.setCalendarRule(cal.id, r); resync("cal pref")
                        } label: { a in actionOptions.first { $0.0 == a }?.1 ?? "" }
                    }
                    IconRow("bell.badge.fill") {
                        Chips(items: actionOptions.map(\.0),
                              current: EventAction(rawValue: rule.reminderAction) ?? .alarm) { a in
                            var r = rule; r.reminderAction = a.rawValue
                            store.setCalendarRule(cal.id, r); resync("cal pref")
                        } label: { a in actionOptions.first { $0.0 == a }?.1 ?? "" }
                    }
                }
                .padding(.bottom, 10)
            }
        }
    }

    private func resync(_ reason: String) { Task { await engine.resync(reason: reason) } }
}

// MARK: - 共通部品

struct Card<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        content
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(NightTheme.nightSurface, in: RoundedRectangle(cornerRadius: 22))
    }
}

struct IconRow<Content: View>: View {
    let icon: String
    @ViewBuilder let content: Content
    init(_ icon: String, @ViewBuilder content: () -> Content) { self.icon = icon; self.content = content() }
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon).frame(width: 22).foregroundStyle(.secondary)
            content
        }
    }
}

struct IconBtn: View {
    let icon: String
    let action: () -> Void
    init(_ icon: String, action: @escaping () -> Void) { self.icon = icon; self.action = action }
    var body: some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 17)).foregroundStyle(NightTheme.indigo)
        }
    }
}

struct Chips<T: Hashable>: View {
    let items: [T]
    let current: T
    let onSelect: (T) -> Void
    let label: (T) -> String
    var body: some View {
        HStack(spacing: 6) {
            ForEach(items, id: \.self) { item in
                let on = item == current
                Button { onSelect(item) } label: {
                    Text(label(item))
                        .font(NightTheme.font(11, weight: .medium))
                        .padding(.horizontal, 9).padding(.vertical, 6)
                        .background(on ? NightTheme.indigo.opacity(0.2) : Color(uiColor: .tertiarySystemGroupedBackground), in: Capsule())
                        .foregroundStyle(on ? NightTheme.indigo : .secondary)
                }.buttonStyle(.plain)
            }
        }
    }
}

/// 複数選択トグル用のチップ列 (招待フィルタ等)
struct MultiChips<T: Hashable>: View {
    let items: [T]
    let isOn: (T) -> Bool
    let onToggle: (T) -> Void
    let label: (T) -> String
    var body: some View {
        HStack(spacing: 6) {
            ForEach(items, id: \.self) { item in
                let on = isOn(item)
                Button { onToggle(item) } label: {
                    Text(label(item))
                        .font(NightTheme.font(11, weight: .medium))
                        .padding(.horizontal, 9).padding(.vertical, 6)
                        .background(on ? NightTheme.indigo.opacity(0.2) : Color(uiColor: .tertiarySystemGroupedBackground), in: Capsule())
                        .foregroundStyle(on ? NightTheme.indigo : .secondary)
                }.buttonStyle(.plain)
            }
        }
    }
}
