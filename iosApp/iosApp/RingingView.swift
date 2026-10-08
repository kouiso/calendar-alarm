import SwiftUI

/// 鳴動画面: 常時ダークグラデ + 巨大時刻 + 丸い停止ボタン (Android RingingActivity と同等)。
/// AlarmKit 経路では OS がフルスクリーンアラートを出すので、これはアプリ前面時の補助表示。
struct RingingView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @State private var now = Date()
    @State private var weather: WeatherService.NowForecast? = nil
    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var current: AlarmInstanceDTO? {
        engine.alertingIds.compactMap { store.state.scheduled[$0]?.instance }.first
    }

    var body: some View {
        ZStack {
            if let w = weather {
                // 天気演出 (元アプリの鳴動アニメ)
                WeatherBackdropView(code: w.current.weatherCode, isDay: w.current.isDay)
            } else {
                LinearGradient(colors: [NightTheme.nightBg, NightTheme.nightSurface],
                               startPoint: .top, endPoint: .bottom)
                    .ignoresSafeArea()
            }
            VStack(spacing: 32) {
                Spacer()
                Text(now, format: .dateTime.hour().minute())
                    .font(NightTheme.numFont(112, weight: .ultraLight))
                    .foregroundStyle(NightTheme.onNight)
                    .onReceive(ticker) { now = $0 }
                if let inst = current {
                    if let w = weather {
                        Label(
                            "\(Int(w.current.temperature))°",
                            systemImage: WeatherService.icon(w.current.weatherCode)
                        )
                        .font(NightTheme.font(14))
                        .foregroundStyle(NightTheme.onNight.opacity(0.75))
                    }
                    Text(inst.title)
                        .font(NightTheme.font(20)).foregroundStyle(NightTheme.onNight.opacity(0.8))
                    Spacer()
                    HStack(spacing: 40) {
                        Button {
                            engine.snooze(inst)
                        } label: {
                            VStack(spacing: 6) {
                                Image(systemName: "zzz").font(.system(size: 26))
                                Text("\(inst.snoozeMinutes)分").font(NightTheme.font(12))
                            }
                            .frame(width: 88, height: 88)
                            .background(NightTheme.nightSurfaceHigh, in: Circle())
                            .foregroundStyle(NightTheme.onNight)
                        }
                        Button {
                            engine.dismiss(inst)
                        } label: {
                            Image(systemName: "stop.fill").font(.system(size: 30))
                                .frame(width: 96, height: 96)
                                .background(NightTheme.indigo, in: Circle())
                                .foregroundStyle(.white)
                        }
                    }
                    .padding(.bottom, 60)
                }
            }
        }
        .task {
            // 地点設定がある時だけ天気を取る (空=演出なし)
            let loc = store.state.weatherLocation
            guard store.state.weatherOnAlarmScreen, !loc.isEmpty else { return }
            weather = await WeatherService().now(for: loc, hours: 1)
        }
    }
}
