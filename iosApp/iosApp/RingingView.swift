import SwiftUI

/// 鳴動画面: 常時ダークグラデ + 巨大時刻 + 丸い停止ボタン (Android RingingActivity と同等)。
/// AlarmKit 経路では OS がフルスクリーンアラートを出すので、これはアプリ前面時の補助表示。
struct RingingView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @State private var now = Date()
    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var current: AlarmInstanceDTO? {
        engine.alertingIds.compactMap { store.state.scheduled[$0]?.instance }.first
    }

    var body: some View {
        ZStack {
            LinearGradient(colors: [NightTheme.nightBg, NightTheme.nightSurface],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            VStack(spacing: 32) {
                Spacer()
                Text(now, format: .dateTime.hour().minute())
                    .font(NightTheme.font(96, weight: .light))
                    .foregroundStyle(NightTheme.onNight)
                    .onReceive(ticker) { now = $0 }
                if let inst = current {
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
    }
}
