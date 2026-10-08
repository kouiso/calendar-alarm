import SwiftUI
import BackgroundTasks
import WidgetKit

@main
struct CalendarAlarmApp: App {

    @StateObject private var engine = Engine()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        registerBackgroundTask()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(engine)
                .environmentObject(engine.store)
                .nightFont()
                .task {
                    await engine.resync(reason: "app start")
                }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active {
                        // BG resync は別 Store で保存済み → 先にディスクを読み直してから resync
                        engine.store.reload()
                        Task { await engine.resync(reason: "foreground") }
                    }
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

struct RootView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store

    /// 共有拡張経由で届いたメール本文。非nilで確認シートを表示する。
    @State private var mailImportText: String? = nil

    var body: some View {
        ZStack {
            if !store.state.onboardingDone {
                OnboardingView()
            } else {
                mainTabs
            }
            // 鳴動中は最前面にダーク画面
            if !engine.alertingIds.isEmpty {
                RingingView()
            }
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
