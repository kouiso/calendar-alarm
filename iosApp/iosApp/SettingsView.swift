import SwiftUI
import PhotosUI

/// 設定タブ: Android 版のアイコン主導UIと同構成。
/// 見出し・説明文なし — [アイコン + チップ/スイッチ] の行だけ。
struct SettingsView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @EnvironmentObject var center: PermissionCenter
    @State private var expandedCal: String? = nil
    @State private var showPerms = false
    @State private var showLog = false
    @State private var showRingLog = false
    @State private var showAccountHelp = false
    @State private var showCodes = false
    @State private var photoItem: PhotosPickerItem? = nil
    @State private var weatherLocDraft = ""

    private let actionOptions: [(EventAction, String)] =
        [(.alarm, "アラーム"), (.notify, "通知"), (.mute, "OFF")]

    private let minutesOptions = [0, 5, 10, 15, 30]
    private let snoozeOptions = [5, 10, 15]
    private let allDayOptions: [(String, Int)] = [("OFF", -1), ("8:00", 480), ("9:00", 540), ("12:00", 720)]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {

                    // 先頭: 鳴動の状態 (前の権限カード) と最近の鳴動記録 (#51 PR3)
                    ringStatusCard

                    // 既定値カード: 🔔タイミング / 😴スヌーズ / ☀天気
                    Card {
                        VStack(spacing: 14) {
                            IconRow("bell.fill") {
                                Chips(items: minutesOptions, current: store.state.defaultMinutesBefore) { m in
                                    store.setDefaultMinutes(m); resync("default")
                                } label: { m in m == 0 ? "開始時" : "\(m)分前" }
                            }
                            PreviewLine(sample: "9:00 の予定",
                                        result: "\(PreviewClock.clock(9 * 60 - store.state.defaultMinutesBefore)) に鳴る",
                                        // 「カレンダーの通知設定を使う」を切ると通知の時刻では鳴らないので、注記も出さない
                                        note: store.state.importEventReminders ? "＋ カレンダーの通知（重なれば1回）" : nil)
                            IconRow("zzz") {
                                Chips(items: snoozeOptions, current: store.state.defaultSnoozeMinutes) { m in
                                    store.setDefaultSnooze(m); resync("default")
                                } label: { "\($0)分" }
                            }
                            PreviewLine(sample: "6:30 に鳴ってスヌーズ",
                                        result: "\(PreviewClock.clock(6 * 60 + 30 + store.state.defaultSnoozeMinutes)) にもう一度",
                                        note: nil)
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
                            // 鳴らす予定の種類と「予定なし」を一か所でまとめて切り替える (#79)
                            VStack(alignment: .leading, spacing: 6) {
                                Text("鳴らす予定の種類").font(NightTheme.font(13, weight: .semibold))
                                // iOS は予定の種類を OS から取れない。誕生日カレンダー以外はタイトルから推定する
                                Text("種類は予定のタイトルから推定します（誕生日カレンダーの予定は誕生日として扱います）。")
                                    .font(NightTheme.font(11)).foregroundStyle(.secondary)
                                ForEach([EventType.event, .task, .absence, .focusTime, .workplace, .birthday], id: \.self) { t in
                                    Toggle(t.label, isOn: Binding(
                                        get: { store.state.eventTypeFilter.allows(t) },
                                        set: { _ in store.toggleEventType(t); resync("event type filter") }
                                    ))
                                    .font(NightTheme.font(13))
                                }
                                Toggle("「予定なし」にした予定", isOn: Binding(
                                    get: { store.state.eventTypeFilter.free },
                                    set: { v in store.setRingFree(v); resync("free events") }
                                ))
                                .font(NightTheme.font(13))
                            }
                            // 予定側リマインダー取込 (既定ON: 元カレンダー通知の「何分前」で鳴らす)
                            IconRow("bell.badge.fill") {
                                VStack(alignment: .leading, spacing: 1) {
                                    Text("カレンダーの通知設定を使う")
                                        .font(NightTheme.font(13))
                                    Text("開始時刻の鳴動に加えて、予定の「○分前」通知時刻でも鳴らします。取得できない既定通知は既定値（\(store.state.defaultMinutesBefore)分前）として扱います")
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

    // MARK: - 鳴動の状態カード (#51 PR3)

    /// 設定の先頭「鳴動の状態」。前の権限カードをここへ移して名前を変えた。
    /// 予定画面と同じ1行の状態、許可の一覧、テストで鳴らす、最近の鳴動3件を1枚に置く。
    /// テストで鳴らすは全部 OK の時も押せる (前は足りない許可がある時しか開けず押せなかった)。
    private var ringStatusCard: some View {
        let now = Engine.nowMillis
        let missing = AppPermission.allCases.filter { center.states[$0] != .granted }
        let status = RingStatusSummary(
            missingPermissions: missing.count,
            recentMissed: store.recentMissedCount(nowMillis: now),
            hasNextAlarm: store.state.scheduled.values.contains { $0.state == .pending && $0.instance.triggerAtMillis > now })
        let statusColor: Color = missing.isEmpty && status.recentMissed == 0
            ? (status.hasNextAlarm ? NightTheme.statusOk : .secondary)
            : NightTheme.statusWarn
        let recent = RingHistory.newestFirst(store.state.ringHistory, limit: 3)
        return Card {
            VStack(alignment: .leading, spacing: 0) {
                Button { showPerms.toggle() } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "checkmark.shield.fill").frame(width: 22).foregroundStyle(statusColor)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("鳴動の状態").font(NightTheme.font(14, weight: .semibold)).foregroundStyle(.primary)
                            Text(status.line).font(NightTheme.font(13)).foregroundStyle(statusColor)
                                .multilineTextAlignment(.leading)
                        }
                        Spacer()
                        Image(systemName: showPerms ? "chevron.up" : "chevron.down")
                            .font(.system(size: 12)).foregroundStyle(.tertiary)
                    }
                }
                .accessibilityLabel("鳴動の状態: \(status.line)")
                if showPerms {
                    Divider().padding(.vertical, 8)
                    VStack(spacing: 8) {
                        ForEach(AppPermission.allCases) { p in
                            HStack {
                                Image(systemName: p.icon).frame(width: 22).foregroundStyle(.secondary)
                                Text(p.label).font(NightTheme.font(13))
                                Spacer()
                                let ok = center.states[p] == .granted
                                Text(ok ? "OK" : "足りない")
                                    .font(NightTheme.font(12, weight: .semibold))
                                    .foregroundStyle(ok ? NightTheme.statusOk : NightTheme.statusWarn)
                            }
                        }
                    }
                }
                // 権限画面はカードの開閉に関係なく入れるようにする。通知だけ断られている時も直せるように
                Divider().padding(.vertical, 8)
                NavigationLink {
                    PermissionsView()
                } label: {
                    HStack {
                        Image(systemName: "checkmark.shield").frame(width: 22).foregroundStyle(.secondary)
                        Text("権限画面を開く").font(NightTheme.font(13))
                        Spacer()
                        Image(systemName: "chevron.right").font(.system(size: 11)).foregroundStyle(.tertiary)
                    }
                }
                Divider().padding(.vertical, 8)
                // 鳴らない時の最終確認: 10秒後に実際に鳴らして経路全体を確かめる。全部 OK の時も出す
                Button {
                    Task { await engine.scheduleTimer(durationMillis: 10_000, label: "テスト鳴動") }
                } label: {
                    HStack {
                        Image(systemName: "bell.and.waves.left.and.right").frame(width: 22).foregroundStyle(.secondary)
                        Text("テストで鳴らす（10秒後）").font(NightTheme.font(13))
                        Spacer()
                    }
                }
                Divider().padding(.vertical, 8)
                HStack {
                    Text("最近の鳴動").font(NightTheme.font(11)).foregroundStyle(.secondary)
                    Spacer()
                    Button("すべて見る") { showRingLog = true }
                        .font(NightTheme.font(12, weight: .medium))
                }
                if recent.isEmpty {
                    Text("まだ記録がありません").font(NightTheme.font(12)).foregroundStyle(.secondary)
                        .padding(.top, 4)
                }
                ForEach(recent) { RingLogRow(entry: $0) }
            }
        }
        .task {
            await center.refresh(engine)
            // 足りない許可がある時だけ一覧を開いておく
            if AppPermission.allCases.contains(where: { center.states[$0] != .granted }) { showPerms = true }
        }
        .sheet(isPresented: $showRingLog) {
            NavigationStack {
                List {
                    if store.state.ringHistory.isEmpty {
                        Text("まだ記録がありません").font(NightTheme.font(13)).foregroundStyle(.secondary)
                    }
                    // 7日分の停止・スヌーズ・鳴らずを新しい順に全部
                    ForEach(RingHistory.newestFirst(store.state.ringHistory, limit: Int.max)) { RingLogRow(entry: $0) }
                }
                .navigationTitle("鳴動の記録")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) { Button("閉じる") { showRingLog = false } }
                }
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

/// 最近の鳴動1行: 鳴るはずだった時刻 + 色付きラベル (停止/スヌーズ/鳴らず) + 予定名 (#51 PR3)
struct RingLogRow: View {
    let entry: AuditEntry

    var body: some View {
        let (label, fg, bg): (String, Color, Color) = {
            switch entry.type {
            case "DISMISS", "DISMISS_OFFAPP": return ("停止", NightTheme.statusOk, NightTheme.statusOk.opacity(0.14))
            case "MISSED": return ("鳴らず", NightTheme.statusWarn, NightTheme.statusWarnBackground)
            default: return ("スヌーズ", .secondary, NightTheme.surfaceVariant)
            }
        }()
        HStack(spacing: 8) {
            Text(Engine.hm(entry.triggerAtMillis ?? entry.atMillis))
                .font(NightTheme.font(12)).foregroundStyle(.secondary)
                .frame(width: 92, alignment: .leading)
            Text(label)
                .font(NightTheme.font(11, weight: .semibold))
                .foregroundStyle(fg)
                .padding(.horizontal, 8).padding(.vertical, 2)
                .background(bg, in: RoundedRectangle(cornerRadius: 8))
            Text(entry.title ?? "").font(NightTheme.font(12)).lineLimit(1)
            Spacer(minLength: 0)
        }
        .padding(.vertical, 3)
    }
}

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

/// 設定の選択肢の下に、選んだ結果を例の時刻で見せる1行 (局長決定 2026-10-17 の5)。
/// 計算は Android と同じ shared の SettingsPreview と揃える。
struct PreviewLine: View {
    let sample: String
    let result: String
    let note: String?
    var body: some View {
        HStack(spacing: 6) {
            Text(sample).foregroundStyle(.secondary)
            Image(systemName: "arrow.right").font(.system(size: 10)).foregroundStyle(.secondary)
            Text(result).fontWeight(.semibold).foregroundStyle(NightTheme.indigo)
            if let note { Text(note).font(NightTheme.font(10)).foregroundStyle(.secondary) }
            Spacer(minLength: 0)
        }
        .font(NightTheme.font(12))
        .padding(.leading, 34)
    }
}

enum PreviewClock {
    /// 分を 0:00〜23:59 に収めて "8:50" の形にする。日をまたげば前日/翌日の時刻。
    static func clock(_ minuteOfDay: Int) -> String {
        let m = ((minuteOfDay % 1440) + 1440) % 1440
        return "\(m / 60):" + String(format: "%02d", m % 60)
    }
}
