import SwiftUI
import EventKit
import UserNotifications

/// アプリが求める許可。並び順がそのまま「1つずつ頼む」順になる (Android の AppPermission と同じ考え方)。
enum AppPermission: String, CaseIterable, Identifiable {
    case calendar, alarm, notify

    var id: String { rawValue }

    var label: String {
        switch self {
        case .calendar: return "カレンダー"
        case .alarm: return "アラーム"
        case .notify: return "通知"
        }
    }

    var reason: String {
        switch self {
        case .calendar: return "予定を読んで、前もって鳴らす"
        case .alarm: return "マナー中でも時刻ちょうどに鳴らす"
        case .notify: return "鳴らせなかった時に知らせる"
        }
    }

    var askTitle: String {
        switch self {
        case .calendar: return "予定に合わせて、前もって鳴らすために"
        case .alarm: return "マナー中でも、きちんと鳴らすために"
        case .notify: return "鳴らせなかった時に、知らせるために"
        }
    }

    var askBody: String {
        switch self {
        case .calendar: return "カレンダーをつなぐと、予定の前に自動でアラームが入る。"
        case .alarm: return "アラームを許可すると、サイレントや集中モードでも鳴る。"
        case .notify: return "通知を許可すると、見逃したアラームを後から知らせる。"
        }
    }

    var icon: String {
        switch self {
        case .calendar: return "calendar"
        case .alarm: return "alarm"
        case .notify: return "bell.badge"
        }
    }
}

/// 許可の状態。OS のダイアログで頼めるのは未決定の時だけで、一度断られたら設定アプリで直す。
enum PermissionState: Equatable {
    case granted, notDetermined, denied
}

/// 「1つずつ頼む」の進め方。画面から切り離しておく。
enum PermissionPlan {
    /// 鳴らすのに足りない許可の数 (予定画面の鳴動の状態)。通知の許可は普段は見逃しを知らせるためだけなので数えないが、
    /// 通知だけで届く予約 (NOTIFY) がこの先にある時は、それを届けるのに要るので数える。
    /// iOS 26 未満はアラームの許可が通知の許可を写している (`refresh`) ので、二重に数えない
    static func missingForRinging(_ states: [AppPermission: PermissionState], notifyDeliveryAhead: Bool,
                                  alarmMirrorsNotify: Bool) -> Int {
        let others = states.filter { $0.key != .notify && $0.value != .granted }.count
        let notifyNeeded = notifyDeliveryAhead && !alarmMirrorsNotify
            && states[.notify].map { $0 != .granted } == true
        return others + (notifyNeeded ? 1 : 0)
    }

    /// 次に頼む許可。断られたものは同じ流れの中で二度頼まない。
    static func next(_ states: [AppPermission: PermissionState], declined: Set<AppPermission>) -> AppPermission? {
        AppPermission.allCases.first { states[$0] != .granted && !declined.contains($0) }
    }

    static func allGranted(_ states: [AppPermission: PermissionState]) -> Bool {
        AppPermission.allCases.allSatisfy { states[$0] == .granted }
    }
}

/// 端末の許可状態を読み、頼む。設定アプリから戻った時は refresh で取り直す。
/// アプリ全体で1つ持ち、権限画面と自動で頼む流れ (PermissionAsker) の両方が同じ状態を見る。
@MainActor
final class PermissionCenter: ObservableObject {
    @Published private(set) var states: [AppPermission: PermissionState] = [:]

    func refresh(_ engine: Engine) async {
        var s: [AppPermission: PermissionState] = [:]
        switch EKEventStore.authorizationStatus(for: .event) {
        case .fullAccess: s[.calendar] = .granted
        case .notDetermined: s[.calendar] = .notDetermined
        default: s[.calendar] = .denied
        }
        switch await UNUserNotificationCenter.current().notificationSettings().authorizationStatus {
        case .authorized, .provisional, .ephemeral: s[.notify] = .granted
        case .notDetermined: s[.notify] = .notDetermined
        default: s[.notify] = .denied
        }
        if engine.usesRealAlarms {
            s[.alarm] = engine.schedulerAuthorized ? .granted : (engine.schedulerUndetermined ? .notDetermined : .denied)
        } else {
            // iOS 26 未満のアラームは通知で鳴らすので、OS の通知の許可がそのままアラームの許可になる。
            // 設定アプリで後から許可された時も拾えるよう、通知経路が覚えている値も OS の値で更新する
            s[.alarm] = s[.notify]
            if s[.notify] != .notDetermined {
                UserDefaults.standard.set(s[.notify] == .granted, forKey: "notifAuthorized")
            }
        }
        states = s
    }

    /// 未決定なら OS のダイアログで頼む。断られた後なら設定アプリを開く (OS がダイアログを二度出さないため)。
    func request(_ p: AppPermission, _ engine: Engine) async {
        if states[p] == .notDetermined {
            switch p {
            case .calendar:
                if await engine.reader.requestAccess() { await engine.resync(reason: "calendar granted") }
            case .alarm:
                await engine.requestSchedulerAuthorization()
            case .notify:
                _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
            }
        } else if states[p] == .denied {
            openSettingsApp()
        }
        await refresh(engine)
    }

    /// 未許可を上から順に頼む。断られた分 (設定アプリが要る分) は最初の1つだけ設定アプリを開く。
    func requestAll(_ engine: Engine) async {
        for p in AppPermission.allCases where states[p] == .notDetermined {
            await request(p, engine)
        }
        if let denied = AppPermission.allCases.first(where: { states[$0] == .denied }) {
            await request(denied, engine)
        }
    }

    func openSettingsApp() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }
}

/// 権限画面: 「全部許可する」と項目ごとのスイッチ (局長決定 2026-10-17 の2)。
/// OS の許可はアプリから取り消せないので、オンのスイッチを切ろうとした時は設定アプリを開く。
struct PermissionsView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var center: PermissionCenter
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        List {
            Section {
                Button {
                    Task { await center.requestAll(engine) }
                } label: {
                    Text(PermissionPlan.allGranted(center.states) ? "すべて許可済み" : "全部許可する")
                        .font(NightTheme.font(16, weight: .semibold))
                        .frame(maxWidth: .infinity).padding(.vertical, 6)
                }
                .disabled(PermissionPlan.allGranted(center.states))
            }
            Section {
                ForEach(AppPermission.allCases) { p in
                    Toggle(isOn: Binding(
                        get: { center.states[p] == .granted },
                        set: { on in
                            Task {
                                if on { await center.request(p, engine) } else { center.openSettingsApp() }
                            }
                        },
                    )) {
                        HStack(spacing: 12) {
                            Image(systemName: p.icon).frame(width: 24).foregroundStyle(NightTheme.indigo)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(p.label).font(NightTheme.font(15, weight: .medium))
                                Text(p.reason).font(NightTheme.font(12)).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            } footer: {
                Text("オフにする時は iPhone の設定が開きます。")
            }
        }
        .navigationTitle("権限")
        .task { await center.refresh(engine) }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await center.refresh(engine) } }
        }
    }
}
