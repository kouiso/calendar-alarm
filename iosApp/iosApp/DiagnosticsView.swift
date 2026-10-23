import SwiftUI
import UserNotifications

// MARK: - 鳴らなかった時の自己診断 (#104)
// Android のトラブルシューティング画面に相当する。iOS には電池最適化が無いので、
// 許可・集中モード/サイレント・バックグラウンド更新・予約の状態を見せて、直す先へ案内する。

enum DiagnosisState: Equatable {
    /// 満たしている
    case ok
    /// 直さないと鳴らない・知らせが届かない
    case ng
    /// 鳴りはするが知っておくとよいこと
    case info
}

enum DiagnosisFix: Equatable {
    case permission(AppPermission)
    case openSettings
}

struct DiagnosisItem: Identifiable, Equatable {
    let id: String
    let state: DiagnosisState
    let title: String
    let detail: String
    let fix: DiagnosisFix?
}

/// 画面から切り離した診断の材料。OS から読んだ値をここへ集めてから判定する
struct DiagnosisInput {
    var calendar: PermissionState
    var alarm: PermissionState
    var notify: PermissionState
    /// AlarmKit (iOS 26+) で鳴らしているか。false なら通知で鳴らしている
    var usesRealAlarms: Bool
    /// 通知の「即時通知」(集中モードを越える) が有効か。読めなければ nil
    var timeSensitiveEnabled: Bool?
    /// 通知のサウンドが有効か。iOS 26 未満は通知の音で鳴らすので、オフだと無音になる。読めなければ nil
    var notificationSoundEnabled: Bool?
    var backgroundRefreshAvailable: Bool
    var lowPowerMode: Bool
    var nextAlarm: AlarmInstanceDTO?
    /// 時刻は先だが OS に予約できておらず、次の resync で入れ直しを待っている行の件数
    var unreservedCount: Int = 0
    var lastFired: AuditEntry?
    /// 直近24時間で、届いたか確かめられなかった通知の件数 (通知で鳴らす iOS だけ)
    var unconfirmedCount: Int = 0
    /// 設定の一番下に出る記録の件数
    var auditCount: Int
    /// 残っている記録の中の予約失敗と、直近24時間の見逃し (7日残る予約の行で数える) の件数
    var recentFailures: Int
    var nowMillis: Int64
}

enum DiagnosisPlan {
    /// 画面の外 (MainActor 以外) からも呼べるよう、Engine.hm と同じ書式を自前で持つ
    static func hm(_ millis: Int64) -> String {
        let f = DateFormatter()
        f.dateFormat = "M/d HH:mm"
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
    }

    /// 通知で鳴らす iOS で、予約から消えたが届いた証拠の無い通知の記録の種類
    static let unconfirmedType = "FIRED_UNCONFIRMED"

    /// 失敗とみなす監査ログの種類
    static let failureTypes: Set<String> = ["SCHEDULE_ERR", "RESYNC_FAIL", "MISSED", "CAPACITY_SKIP"]

    /// 見逃しは設定・予定画面と同じ数え方 (鳴動の記録 ringHistory を鳴動時刻で数える) で数える。
    /// 予約の行は単発アラームをオンに戻すと消えるので、行から数えると画面ごとに件数がずれる。
    /// 予約の失敗は監査ログに残っている範囲で数える (画面の文言もそう書く)
    static func recentFailures(_ audit: [AuditEntry], ringHistory: [AuditEntry], nowMillis: Int64) -> Int {
        let since = nowMillis - 24 * 60 * 60 * 1000
        let missed = RingHistory.recentMissedCount(ringHistory, nowMillis: nowMillis)
        let errors = audit.filter { $0.atMillis >= since && failureTypes.contains($0.type) && $0.type != "MISSED" }.count
        return missed + errors
    }

    static func lastFired(_ audit: [AuditEntry]) -> AuditEntry? {
        // 後から差し込んだ記録や旧版の記録もあるので、並び順ではなく時刻で一番新しいものを取る。
        // NOTIFY は「通知のみ」の予定の通知でアラームではないので入れない
        audit.filter { $0.type == "FIRED" }.max { $0.atMillis < $1.atMillis }
    }

    static func unconfirmedCount(_ audit: [AuditEntry], nowMillis: Int64) -> Int {
        let since = nowMillis - 24 * 60 * 60 * 1000
        return audit.filter { $0.type == unconfirmedType && $0.atMillis >= since }.count
    }

    /// OS に予約できた (予約 id がある) 未来の行と、AlarmKit でスヌーズを数えている行から次のアラームを選ぶ。
    /// スヌーズの予約に失敗した行は入れ直し待ちで残るが、そのままでは鳴らないので入れない。
    /// AlarmKit のスヌーズは同じ予約が数え終わって鳴り直すので、記録は .snoozed のまま子の行を作らない。
    /// 鳴り直す時刻は OS から取れないため、スヌーズした記録の時刻 + スヌーズ分で出す
    static func nextAlarm(_ scheduled: [ScheduledRecord], audit: [AuditEntry] = [],
                          countdownIds: Set<String> = [], nowMillis: Int64) -> AlarmInstanceDTO? {
        var candidates = scheduled
            .filter { $0.state == .pending && $0.alarmKitId != nil && $0.instance.triggerAtMillis > nowMillis }
            .map { $0.instance }
        for rec in scheduled where rec.state == .snoozed && countdownIds.contains(rec.instance.id) {
            let snoozedAt = audit
                .filter { $0.type == "SNOOZE" && $0.detail.hasPrefix(rec.instance.id + " ") }
                .map { $0.atMillis }.max()
            var inst = rec.instance
            // 記録が古くて消えていても、スヌーズ分より後になることはない
            inst.triggerAtMillis = (snoozedAt ?? nowMillis) + Int64(inst.snoozeMinutes) * 60_000
            inst.title += "（スヌーズ中）"
            candidates.append(inst)
        }
        return candidates.min { $0.triggerAtMillis < $1.triggerAtMillis }
    }

    static func unreservedCount(_ scheduled: [ScheduledRecord], nowMillis: Int64) -> Int {
        scheduled.filter { $0.state == .pending && $0.alarmKitId == nil && $0.instance.triggerAtMillis > nowMillis }.count
    }

    static func items(_ i: DiagnosisInput) -> [DiagnosisItem] {
        var out: [DiagnosisItem] = []
        out.append(DiagnosisItem(
            id: "calendar", state: i.calendar == .granted ? .ok : .ng,
            title: i.calendar == .granted ? "カレンダーを読めます" : "カレンダーを読めません",
            detail: "予定を読めないと、予定の前のアラームが入りません。",
            fix: i.calendar == .granted ? nil : .permission(.calendar)))
        out.append(DiagnosisItem(
            id: "alarm", state: i.alarm == .granted ? .ok : .ng,
            title: i.alarm == .granted ? "アラームを鳴らせます" : "アラームが許可されていません",
            detail: i.usesRealAlarms
                ? "iPhone のアラーム機能 (AlarmKit) で鳴らします。"
                : "この iOS では通知で鳴らします。通知の許可がアラームの許可になります。",
            fix: i.alarm == .granted ? nil : .permission(.alarm)))
        if !i.usesRealAlarms && i.alarm == .granted && i.notificationSoundEnabled == false {
            // 通知は許可されていても、サウンドを切られていると通知で鳴らすアラームは無音になる
            out.append(DiagnosisItem(
                id: "sound", state: .ng,
                title: "通知のサウンドがオフです",
                detail: "この iOS では通知の音で鳴らします。設定でこのアプリのサウンドをオンにしてください。",
                fix: .openSettings))
        }
        out.append(DiagnosisItem(
            id: "notify", state: i.notify == .granted ? .ok : .ng,
            title: i.notify == .granted ? "見逃しを知らせられます" : "通知が許可されていません",
            detail: "鳴らせなかったアラームを後から知らせるのに使います。",
            fix: i.notify == .granted ? nil : .permission(.notify)))
        out.append(focusItem(i))
        if !i.usesRealAlarms {
            // サイレントスイッチの状態を読む公開 API は無いので、注意だけ出す
            out.append(DiagnosisItem(
                id: "silent", state: .info,
                title: "サイレントスイッチがオンだと音が出ません",
                detail: "この iOS では通知で鳴らすためです。iOS 26 以降はアラーム機能で鳴り、スイッチに関係なく鳴ります。",
                fix: nil))
        }
        out.append(DiagnosisItem(
            id: "background", state: i.backgroundRefreshAvailable ? .ok : .info,
            title: i.backgroundRefreshAvailable ? "裏で予定の変更を取り込めます" : "Appのバックグラウンド更新がオフです",
            detail: i.backgroundRefreshAvailable
                ? "アプリを開いていなくても、予定の変更を定期的に取り込みます。"
                : "予定を変えた後は、一度アプリを開くとアラームが入り直します。",
            fix: i.backgroundRefreshAvailable ? nil : .openSettings))
        if i.lowPowerMode {
            out.append(DiagnosisItem(
                id: "lowpower", state: .info,
                title: "低電力モードがオンです",
                detail: "裏での予定の取り込みが止まります。入っているアラームはそのまま鳴ります。",
                fix: nil))
        }
        if i.unreservedCount > 0 {
            out.append(DiagnosisItem(
                id: "unreserved", state: .ng,
                title: "予約できていないアラームが\(i.unreservedCount)件あります",
                detail: "このままでは鳴りません。上の許可を直してアプリを開き直すと、入れ直します。",
                fix: nil))
        }
        if let next = i.nextAlarm {
            out.append(DiagnosisItem(
                id: "next", state: .ok,
                title: "次のアラーム \(hm(next.triggerAtMillis))",
                detail: next.title, fix: nil))
        } else {
            out.append(DiagnosisItem(
                id: "next", state: .info,
                title: "予約中のアラームはありません",
                detail: "カレンダーの「鳴らす」設定と、予定の種類の設定を確かめてください。",
                fix: nil))
        }
        out.append(DiagnosisItem(
            id: "last", state: .info,
            // 通知で鳴らす iOS で分かるのは通知が届いたことまで (集中モードやサイレントで音が出ない場合がある)
            title: i.lastFired.map {
                i.usesRealAlarms ? "最後に鳴ったのは \(hm($0.atMillis))" : "最後にアラームの通知が届いたのは \(hm($0.atMillis))"
            } ?? (i.usesRealAlarms ? "まだ鳴った記録がありません" : "まだアラームの通知が届いた記録がありません"),
            detail: i.lastFired?.detail ?? "下の「10秒後に鳴動テスト」で、鳴るか確かめられます。",
            fix: nil))
        if !i.usesRealAlarms && i.unconfirmedCount > 0 {
            out.append(DiagnosisItem(
                id: "unconfirmed", state: .info,
                title: "届いたか分からない通知が\(i.unconfirmedCount)件あります",
                detail: "通知センターから消された時や、その時刻に通知がオフだった時はこうなります。鳴らなかった覚えがあれば、通知の設定を確かめてください。",
                fix: nil))
        }
        out.append(DiagnosisItem(
            id: "failures", state: i.recentFailures == 0 ? .ok : .ng,
            title: i.recentFailures == 0 ? "直近の記録に予約の失敗・見逃しはありません" : "直近の記録に予約の失敗・見逃しが\(i.recentFailures)件",
            detail: i.recentFailures == 0 ? "記録は設定の一番下（直近 \(i.auditCount) 件）で見られます。" : "上の許可を直してから、設定の一番下の記録で内容を確かめてください。",
            fix: nil))
        return out
    }

    private static func focusItem(_ i: DiagnosisInput) -> DiagnosisItem {
        if i.usesRealAlarms {
            return DiagnosisItem(
                id: "focus", state: .ok,
                title: "集中モード中も鳴ります",
                detail: "アラーム機能 (AlarmKit) は集中モードとサイレントスイッチを越えて鳴ります。",
                fix: nil)
        }
        // 「即時通知」のスイッチは設定に出ている時 (.disabled) だけ直せる。出ていない時は nil で来る
        if i.timeSensitiveEnabled == false {
            return DiagnosisItem(
                id: "focus", state: .ng,
                title: "集中モード中は鳴らないことがあります",
                detail: "通知の「即時通知」がオフです。設定でオンにすると集中モードでも届きます。",
                fix: .openSettings)
        }
        // 集中モードの許可は「設定 > 集中モード」にあり、アプリの設定画面からは開けないので案内だけにする
        return DiagnosisItem(
            id: "focus", state: .info,
            title: "集中モードの設定を確かめてください",
            detail: "通知で鳴らしているため、集中モード中は届かないことがあります。「設定 > 集中モード」でこのアプリを許可してください。",
            fix: nil)
    }
}

/// 自己診断の画面。設定アプリから戻った時に読み直す
struct DiagnosticsView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @EnvironmentObject var center: PermissionCenter
    @Environment(\.scenePhase) private var scenePhase
    @State private var items: [DiagnosisItem] = []
    /// nil = まだ押していない、true = 予約できた、false = 予約できなかった
    @State private var testScheduled: Bool? = nil

    var body: some View {
        List {
            Section {
                ForEach(items) { item in
                    row(item)
                }
            }
            Section {
                Button {
                    Task {
                        testScheduled = await engine.scheduleTimer(durationMillis: 10_000, label: "テスト鳴動")
                        // テストの予約結果 (失敗の記録や次のアラーム) を項目に反映する
                        await reload()
                    }
                } label: {
                    Label("10秒後に鳴動テスト", systemImage: "bell.and.waves.left.and.right")
                }
            } footer: {
                switch testScheduled {
                case true?: Text("10秒後に鳴ります。鳴らなければ上の項目を直してください。")
                case false?: Text("テストを予約できませんでした。上の要対応の項目を直してから、もう一度押してください。")
                case nil: Text("実際に鳴らして、予約から鳴動までを確かめます。")
                }
            }
        }
        .navigationTitle("鳴らない時の診断")
        .task { await reload() }
        // 開いている間に鳴った・予約が変わった時も、項目を作り直す
        // 記録は時刻の位置に差し込まれ末尾が変わらないことがあるので、採番で増えたかを見る
        .onChange(of: store.state.nextAuditId) { _, _ in Task { await reload() } }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await reload() } }
        }
    }

    private func row(_ item: DiagnosisItem) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon(item.state))
                .foregroundStyle(color(item.state))
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 3) {
                Text(item.title).font(NightTheme.font(14, weight: .medium))
                Text(item.detail).font(NightTheme.font(12)).foregroundStyle(.secondary)
                if let fix = item.fix {
                    Button(fixLabel(fix)) { Task { await apply(fix) } }
                        .font(NightTheme.font(12, weight: .medium))
                        .padding(.top, 2)
                }
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }

    private func icon(_ s: DiagnosisState) -> String {
        switch s {
        case .ok: return "checkmark.circle.fill"
        case .ng: return "exclamationmark.triangle.fill"
        case .info: return "info.circle.fill"
        }
    }

    private func color(_ s: DiagnosisState) -> Color {
        switch s {
        case .ok: return .green
        case .ng: return .orange
        case .info: return .secondary
        }
    }

    private func fixLabel(_ f: DiagnosisFix) -> String {
        switch f {
        case .permission: return "許可する"
        case .openSettings: return "設定を開く"
        }
    }

    private func apply(_ f: DiagnosisFix) async {
        switch f {
        case .permission(let p): await center.request(p, engine)
        case .openSettings: center.openSettingsApp()
        }
        await reload()
    }

    private func reload() async {
        await center.refresh(engine)
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        let now = Engine.nowMillis
        let rows = Array(store.state.scheduled.values)
        let input = DiagnosisInput(
            calendar: center.states[.calendar] ?? .notDetermined,
            alarm: center.states[.alarm] ?? .notDetermined,
            notify: center.states[.notify] ?? .notDetermined,
            usesRealAlarms: engine.usesRealAlarms,
            timeSensitiveEnabled: settings.authorizationStatus == .authorized
                ? Self.switchState(settings.timeSensitiveSetting) : nil,
            notificationSoundEnabled: settings.authorizationStatus == .authorized
                ? Self.switchState(settings.soundSetting) : nil,
            backgroundRefreshAvailable: UIApplication.shared.backgroundRefreshStatus == .available,
            lowPowerMode: ProcessInfo.processInfo.isLowPowerModeEnabled,
            nextAlarm: DiagnosisPlan.nextAlarm(rows, audit: store.state.audit,
                                               countdownIds: engine.nativeCountdownIds(), nowMillis: now),
            unreservedCount: DiagnosisPlan.unreservedCount(rows, nowMillis: now),
            lastFired: DiagnosisPlan.lastFired(store.state.audit),
            unconfirmedCount: DiagnosisPlan.unconfirmedCount(store.state.audit, nowMillis: now),
            auditCount: store.state.audit.count,
            recentFailures: DiagnosisPlan.recentFailures(store.state.audit, ringHistory: store.state.ringHistory, nowMillis: now),
            nowMillis: now)
        items = DiagnosisPlan.items(input)
    }

    /// 設定のスイッチが出ていない (.notSupported) 時は、オフではなく「分からない」として扱う
    static func switchState(_ s: UNNotificationSetting) -> Bool? {
        switch s {
        case .enabled: return true
        case .disabled: return false
        default: return nil
        }
    }
}
