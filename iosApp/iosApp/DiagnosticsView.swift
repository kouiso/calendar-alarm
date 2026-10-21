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
    var backgroundRefreshAvailable: Bool
    var lowPowerMode: Bool
    var nextAlarm: AlarmInstanceDTO?
    var lastFired: AuditEntry?
    /// 直近24時間の予約失敗・見逃しの件数
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

    /// 失敗とみなす監査ログの種類
    static let failureTypes: Set<String> = ["SCHEDULE_ERR", "RESYNC_FAIL", "MISSED", "CAPACITY_SKIP"]

    static func recentFailures(_ audit: [AuditEntry], nowMillis: Int64) -> Int {
        let since = nowMillis - 24 * 60 * 60 * 1000
        return audit.filter { $0.atMillis >= since && failureTypes.contains($0.type) }.count
    }

    static func lastFired(_ audit: [AuditEntry]) -> AuditEntry? {
        audit.last { $0.type == "FIRED" || $0.type == "NOTIFY" }
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
            title: i.lastFired.map { "最後に鳴ったのは \(hm($0.atMillis))" } ?? "まだ鳴った記録がありません",
            detail: i.lastFired?.detail ?? "下の「10秒後に鳴動テスト」で、鳴るか確かめられます。",
            fix: nil))
        out.append(DiagnosisItem(
            id: "failures", state: i.recentFailures == 0 ? .ok : .ng,
            title: i.recentFailures == 0 ? "直近24時間の予約の失敗はありません" : "直近24時間に予約の失敗・見逃しが\(i.recentFailures)件",
            detail: i.recentFailures == 0 ? "記録は設定の一番下（直近 ◯ 件）で見られます。" : "上の許可を直してから、設定の一番下の記録で内容を確かめてください。",
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
        if i.timeSensitiveEnabled == false {
            return DiagnosisItem(
                id: "focus", state: .ng,
                title: "集中モード中は鳴らないことがあります",
                detail: "通知の「即時通知」がオフです。設定でオンにすると集中モードでも届きます。",
                fix: .openSettings)
        }
        return DiagnosisItem(
            id: "focus", state: .info,
            title: "集中モードの設定を確かめてください",
            detail: "通知で鳴らしているため、集中モードでこのアプリを許可していないと届かないことがあります。",
            fix: .openSettings)
    }
}

/// 自己診断の画面。設定アプリから戻った時に読み直す
struct DiagnosticsView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @EnvironmentObject var center: PermissionCenter
    @Environment(\.scenePhase) private var scenePhase
    @State private var items: [DiagnosisItem] = []
    @State private var testScheduled = false

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
                        await engine.scheduleTimer(durationMillis: 10_000, label: "テスト鳴動")
                        testScheduled = true
                    }
                } label: {
                    Label("10秒後に鳴動テスト", systemImage: "bell.and.waves.left.and.right")
                }
            } footer: {
                Text(testScheduled ? "10秒後に鳴ります。鳴らなければ上の項目を直してください。" : "実際に鳴らして、予約から鳴動までを確かめます。")
            }
        }
        .navigationTitle("鳴らない時の診断")
        .task { await reload() }
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
        let next = store.state.scheduled.values
            .filter { $0.state == .pending && $0.instance.triggerAtMillis > now }
            .map { $0.instance }
            .min { $0.triggerAtMillis < $1.triggerAtMillis }
        let input = DiagnosisInput(
            calendar: center.states[.calendar] ?? .notDetermined,
            alarm: center.states[.alarm] ?? .notDetermined,
            notify: center.states[.notify] ?? .notDetermined,
            usesRealAlarms: engine.usesRealAlarms,
            timeSensitiveEnabled: settings.authorizationStatus == .authorized
                ? settings.timeSensitiveSetting == .enabled : nil,
            backgroundRefreshAvailable: UIApplication.shared.backgroundRefreshStatus == .available,
            lowPowerMode: ProcessInfo.processInfo.isLowPowerModeEnabled,
            nextAlarm: next,
            lastFired: DiagnosisPlan.lastFired(store.state.audit),
            recentFailures: DiagnosisPlan.recentFailures(store.state.audit, nowMillis: now),
            nowMillis: now)
        items = DiagnosisPlan.items(input)
    }
}
