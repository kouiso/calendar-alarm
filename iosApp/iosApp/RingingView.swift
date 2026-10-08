import SwiftUI

/// 鳴動画面 (Night UI スペック): 日付チップ + アクセントリング2重 + Outfit極細112pt時刻
/// + 残りラベル + 天気チップ + スヌーズ/停止 pill (72h)。常時ダーク基調。
/// AlarmKit 経路では OS がフルスクリーンアラートを出すので、これはアプリ前面時の補助表示。
struct RingingView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @State private var now = Date()
    @State private var weather: WeatherService.NowForecast? = nil
    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var accent: Color {
        AppPalette.byId(store.state.themeId).accent
    }

    private var current: AlarmInstanceDTO? {
        engine.alertingIds.compactMap { store.state.scheduled[$0]?.instance }.first
    }

    var body: some View {
        ZStack {
            if let w = weather {
                WeatherBackdropView(code: w.current.weatherCode, isDay: w.current.isDay)
            } else {
                NightTheme.nightBg.ignoresSafeArea()
            }
            VStack(spacing: 0) {
                // 日付チップ
                Text(now, format: .dateTime.month(.defaultDigits).day().weekday(.abbreviated))
                    .font(NightTheme.font(13))
                    .foregroundStyle(NightTheme.muted)
                    .padding(.horizontal, 18).padding(.vertical, 8)
                    .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 14))
                    .overlay(
                        RoundedRectangle(cornerRadius: 14)
                            .stroke(Color.white.opacity(0.10), lineWidth: 1)
                    )
                    .padding(.top, 20)
                Spacer()
                // アクセントリング 290/230 + 大時刻
                ZStack {
                    Circle().stroke(accent.opacity(0.25), lineWidth: 1.5).frame(width: 290, height: 290)
                    Circle().stroke(accent.opacity(0.15), lineWidth: 1).frame(width: 230, height: 230)
                    Text(now, format: .dateTime.hour().minute())
                        .font(NightTheme.numFont(112, weight: .ultraLight))
                        .foregroundStyle(NightTheme.onNight)
                        .onReceive(ticker) { now = $0 }
                }
                .padding(.bottom, 18)
                if let inst = current {
                    // 開始までの残り (eventStartMillis は DTO の eventStartMillis 想定)
                    Text(remainingLabel(inst))
                        .font(NightTheme.font(15, weight: .semibold))
                        .foregroundStyle(accent)
                        .padding(.bottom, 8)
                    Text(inst.title)
                        .font(NightTheme.font(32, weight: .semibold))
                        .foregroundStyle(NightTheme.onNight)
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                        .padding(.horizontal, 24)
                    if let w = weather {
                        Label(
                            "\(Int(w.current.temperature))°",
                            systemImage: WeatherService.icon(w.current.weatherCode)
                        )
                        .font(NightTheme.font(14))
                        .foregroundStyle(NightTheme.muted)
                        .padding(.horizontal, 16).padding(.vertical, 8)
                        .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 14))
                        .overlay(
                            RoundedRectangle(cornerRadius: 14)
                                .stroke(Color.white.opacity(0.10), lineWidth: 1)
                        )
                        .padding(.top, 14)
                    }
                    Spacer()
                    // スヌーズ pill → 停止 pill (72h)
                    Button { engine.snooze(inst) } label: {
                        Text("スヌーズ \(inst.snoozeMinutes)分")
                            .font(NightTheme.font(22, weight: .semibold))
                            .foregroundStyle(NightTheme.onNight)
                            .frame(maxWidth: .infinity).frame(height: 72)
                            .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 36))
                    }
                    .padding(.horizontal, 24).padding(.bottom, 12)
                    Button { engine.dismiss(inst) } label: {
                        Text("停止")
                            .font(NightTheme.font(22, weight: .semibold))
                            .foregroundStyle(.black.opacity(0.75))
                            .frame(maxWidth: .infinity).frame(height: 72)
                            .background(accent, in: RoundedRectangle(cornerRadius: 36))
                    }
                    .padding(.horizontal, 24).padding(.bottom, 10)
                    Text("Calendar Alarm")
                        .font(NightTheme.font(12))
                        .foregroundStyle(NightTheme.onNight.opacity(0.4))
                        .padding(.bottom, 20)
                } else {
                    Spacer()
                }
            }
        }
        .task {
            let loc = store.state.weatherLocation
            guard store.state.weatherOnAlarmScreen, !loc.isEmpty else { return }
            weather = await WeatherService().now(for: loc, hours: 1)
        }
    }

    private func remainingLabel(_ inst: AlarmInstanceDTO) -> String {
        if let start = inst.eventStartMillis {
            let remain = Date(timeIntervalSince1970: TimeInterval(start) / 1000).timeIntervalSince(now)
            if remain > 0 { return "\(Int((remain + 30) / 60))分後に開始" }
        }
        return "まもなく開始"
    }
}
