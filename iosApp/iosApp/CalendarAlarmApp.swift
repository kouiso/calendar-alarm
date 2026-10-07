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
            TabView {
                AgendaView()
                    .tabItem { Label("予定", systemImage: "calendar") }
                AlarmsView()
                    .tabItem { Label("アラーム", systemImage: "alarm") }
                TimerView()
                    .tabItem { Label("タイマー", systemImage: "timer") }
                SettingsView()
                    .tabItem { Label("設定", systemImage: "gearshape") }
            }
            .scrollContentBackground(.hidden)
        }
        .tint(AppPalette.byId(store.state.themeId).accent)
    }
}
