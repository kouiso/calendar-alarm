import SwiftUI
import BackgroundTasks
import UserNotifications
import WidgetKit

/// 起動の入口。単体テストの母体として起動された時は、アプリ本体の Engine・Store を作らない。
/// 母体のアプリが自分の Engine で resync や音の片付けを走らせると、試験が置いた音のファイルを消し、
/// アプリ全体で共有する `AlarmSoundLibrary.currentDefault` を試験の途中で書き換えて、試験が時々落ちる
@main
enum AppLauncher {
    enum Mode { case app, unitTestHost }

    /// main が実際に選んだ起動。試験が「母体が自分の Engine を動かしていない」ことを確かめるのに読む
    private(set) static var launchedMode: Mode?

    /// Xcode は単体テストの母体を起動する時だけ XCTestConfigurationFilePath を渡す。
    /// 通常の起動や UI テスト・WDA 経由の起動には付かないので、これ1つで見分ける
    static var isUnitTestHost: Bool {
        ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    }

    static func main() {
        if isUnitTestHost {
            launchedMode = .unitTestHost
            UnitTestHostApp.main()
        } else {
            launchedMode = .app
            CalendarAlarmApp.main()
        }
    }
}

/// 単体テストの母体。画面は空で、予約・保存・通知には何も触れない
struct UnitTestHostApp: App {
    var body: some Scene {
        WindowGroup { Color.clear }
    }
}

struct CalendarAlarmApp: App {

    @StateObject private var engine = Engine()
    @StateObject private var permissions = PermissionCenter()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // 起動処理が終わる前に受け口を置かないと、通知を開いて起動した時の知らせを取りこぼす
        UNUserNotificationCenter.current().delegate = NotificationPresenter.shared
        registerBackgroundTask()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(engine)
                .environmentObject(engine.store)
                .environmentObject(permissions)
                .nightFont()
                .task {
                    NotificationPresenter.shared.engine = engine
                    await engine.resync(reason: "app start")
                }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active {
                        // BG resync は別 Store で保存済み → 先にディスクを読み直してから resync
                        engine.store.reload()
                        Task { await engine.resync(reason: "foreground") }
                    }
                }
                // 時刻・タイムゾーンの手動変更を検知して予約を再展開する (#156)。
                // Android 側の TimeChangeReceiver (TIME_SET/TIMEZONE_CHANGED → resync) と同じ契機。
                // significantTimeChange は時計の手動変更と TZ 変更の両方で発火する。
                // 予約はエポックms登録で展開時のローカル時刻から作るため、TZ 変更後は
                // 古い TZ で展開した予約を張り替えないとずれた時刻に鳴る
                .onReceive(NotificationCenter.default.publisher(
                    for: UIApplication.significantTimeChangeNotification)) { _ in
                    engine.store.reload()
                    Task { await engine.resync(reason: "time change") }
                }
        }
    }

    /// バックグラウンドでの定期リシンク (展開ホライズンの繰り上げ用)。
    /// 登録だけでは実行されないので投入も行い、各実行の末尾で次回を再投入する。
    private func registerBackgroundTask() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: "com.calendaralarm.resync", using: nil) { task in
            guard let task = task as? BGProcessingTask else { return }
            Task { @MainActor in
                let engine = Engine()
                await engine.resync(reason: "bg")
                task.setTaskCompleted(success: true)
                Self.scheduleNextBgTask()
            }
        }
        Self.scheduleNextBgTask()
    }

    static func scheduleNextBgTask() {
        let req = BGProcessingTaskRequest(identifier: "com.calendaralarm.resync")
        req.earliestBeginDate = Date(timeIntervalSinceNow: 6 * 3600)
        try? BGTaskScheduler.shared.submit(req)
    }
}

/// 通知の受け口。iOS 26 未満のアラームは通知で鳴らすため、受け口が無いとアプリを開いている間は
/// 音も表示も出ない (診断画面の鳴動テストも鳴らない)。届いた行はここで鳴動済みにする
final class NotificationPresenter: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationPresenter()
    @MainActor weak var engine: Engine? {
        didSet { flushWaiting() }
    }
    /// engine が付く前 (通知を開いて起動した直後) に来た知らせ。付いた時に流す。
    /// 捨てると、resync で拾えない時 (通知の許可が外れた等) に届いた分が見逃しになる
    @MainActor private var waiting: [(id: String, at: Int64)] = []

    /// 通知が届いた知らせの入口。テストからも呼ぶので internal
    @MainActor func deliver(_ id: String, at: Int64) {
        guard let engine else { waiting.append((id, at)); return }
        engine.markNotificationDelivered(id, deliveredAtMillis: at)
    }

    @MainActor private func flushWaiting() {
        guard let engine, !waiting.isEmpty else { return }
        let items = waiting
        waiting = []
        for item in items { engine.markNotificationDelivered(item.id, deliveredAtMillis: item.at) }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        let id = notification.request.identifier
        let at = Int64(notification.date.timeIntervalSince1970 * 1000)
        await MainActor.run { deliver(id, at: at) }
        return [.banner, .list, .sound]
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        // 押された時刻ではなく届いた時刻で残す。通知を後から開いた時に時刻がずれないように
        let id = response.notification.request.identifier
        let at = Int64(response.notification.date.timeIntervalSince1970 * 1000)
        await MainActor.run { deliver(id, at: at) }
    }
}

struct RootView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @EnvironmentObject var permissions: PermissionCenter
    @StateObject private var asker = PermissionAsker()
    @Environment(\.scenePhase) private var scenePhase

    /// 共有拡張経由で届いたメール本文。非nilで確認シートを表示する。
    @State private var mailImportText: String? = nil

    var body: some View {
        ZStack {
            // 初回も専用画面を挟まず予定画面へ直行し、足りない許可は1つずつ頼む (局長決定 2026-10-17 の1)
            mainTabs
            // 鳴動中は最前面にダーク画面
            if !engine.alertingIds.isEmpty {
                RingingView()
            }
        }
        // 鳴動中は許可を頼むシートを出さない。鳴動画面の停止ボタンを隠してしまうため
        .sheet(item: Binding(
            get: { engine.alertingIds.isEmpty ? asker.current : nil },
            set: { asker.current = $0 }
        )) { p in
            PermissionAskSheet(permission: p, retry: asker.isRetry(p), needsSettings: permissions.states[p] == .denied) { action in
                Task { await asker.handle(action, for: p, center: permissions, engine: engine) }
            }
            .presentationDetents([.medium])
        }
        .sheet(isPresented: Binding(
            get: { engine.alertingIds.isEmpty && asker.showReady },
            set: { asker.showReady = $0 }
        )) {
            ReadySheet {
                asker.showReady = false
                Task { await engine.scheduleTimer(durationMillis: 10_000, label: "テスト鳴動") }
            } onClose: {
                asker.showReady = false
            }
            .presentationDetents([.medium])
        }
        .task { await asker.advance(center: permissions, engine: engine) }
        // 広告 (#140): 同意確認と SDK 初期化は裏で進める。鳴動・停止・スヌーズは待たせない。
        // 同意フォームは鳴動中に出さず、鳴動が始まったら閉じて停止操作を優先させる
        .task { AdCoordinator.shared.startIfNeeded(isAlerting: { !engine.alertingIds.isEmpty }) }
        .onChange(of: engine.alertingIds.isEmpty) { _, idle in
            if idle {
                Task { await asker.advance(center: permissions, engine: engine) }
            } else {
                AdCoordinator.shared.dismissConsentFormForRinging()
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await asker.advance(center: permissions, engine: engine) } }
        }
        .onOpenURL { url in
            if url.scheme == "calendaralarm-import" { loadSharedMail() }
        }
        .sheet(
            isPresented: Binding(
                get: { mailImportText != nil },
                set: { if !$0 { mailImportText = nil } },
            )
        ) {
            if let text = mailImportText {
                MailImportSheet(rawText: text) { _ in mailImportText = nil }
            }
        }
    }

    /// 共有拡張が App Group に置いた mail-share.txt を読む。
    private func loadSharedMail() {
        guard let dir = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: "group.com.calendaralarm.ios")
        else { return }
        let file = dir.appendingPathComponent("mail-share.txt")
        guard let text = try? String(contentsOf: file, encoding: .utf8),
              !text.isEmpty else { return }
        try? FileManager.default.removeItem(at: file)
        mailImportText = text
    }

    private var mainTabs: some View {
        ZStack {
            // カスタム背景 (元アプリの背景画像設定)
            if store.state.hasCustomBackground,
               let ui = UIImage(contentsOfFile: Store.backgroundImageURL.path) {
                Image(uiImage: ui)
                    .resizable().scaledToFill()
                    .ignoresSafeArea()
                Color(uiColor: .systemBackground).opacity(0.72).ignoresSafeArea()
            }
            // Night UI: フローティングタブバー (h66, r26, 半透明)
            Group {
                switch tab {
                case 0: AgendaView()
                case 1: AlarmsView()
                case 2: TimerView()
                default: SettingsView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .scrollContentBackground(.hidden)
            // フローティングタブバー(高さ66+下マージン10)分の下端余白。
            // safeAreaInset は内側の ScrollView へ伝播しないため明示的に引く
            .padding(.bottom, 84)
            VStack {
                Spacer()
                HStack(spacing: 0) {
                    NightTab(icon: "calendar", label: "予定", sel: tab == 0) { tab = 0 }
                    NightTab(icon: "alarm", label: "アラーム", sel: tab == 1) { tab = 1 }
                    NightTab(icon: "timer", label: "タイマー", sel: tab == 2) { tab = 2 }
                    NightTab(icon: "gearshape", label: "設定", sel: tab == 3) { tab = 3 }
                }
                .frame(height: 66)
                .background(NightTheme.nightSurface.opacity(0.92))
                .clipShape(RoundedRectangle(cornerRadius: 26))
                .overlay(
                    RoundedRectangle(cornerRadius: 26)
                        .stroke(NightTheme.divider, lineWidth: 1)
                )
                .padding(.horizontal, 16)
                .padding(.bottom, 10)
            }
        }
        .tint(AppPalette.byId(store.state.themeId).accent)
    }

    @State private var tab = 0
}

private struct NightTab: View {
    let icon: String
    let label: String
    let sel: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: icon)
                    .font(.system(size: 20))
                Text(label)
                    .font(.system(size: 11, weight: sel ? .semibold : .regular))
            }
            .foregroundColor(sel ? .accentColor : NightTheme.muted)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}
