import SwiftUI

/// 初回オンボード: ブランド + 1行コピー + 権限カード3枚 (Android OnboardingScreen と同構成)。
struct OnboardingView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @State private var requesting = false

    var body: some View {
        ScrollView {
        VStack(spacing: 24) {
            Spacer(minLength: 24)
            ZStack {
                Circle().fill(NightTheme.indigo.opacity(0.15)).frame(width: 96, height: 96)
                Image(systemName: "alarm.fill").font(.system(size: 40)).foregroundStyle(NightTheme.indigo)
            }
            Text("Calendar Alarm").font(NightTheme.font(28, weight: .semibold))
            Text("カレンダーの予定をアラームで鳴らす。\n初回だけ権限を設定する。")
                .font(NightTheme.font(14)).foregroundStyle(.secondary).multilineTextAlignment(.center)

            VStack(spacing: 12) {
                OnbCard(icon: "calendar", title: "カレンダー読み取り",
                        desc: "予定を自動でアラーム化")
                OnbCard(icon: "bell.badge", title: "アラーム/通知",
                        desc: engine.usesRealAlarms ? "AlarmKit の本物アラーム" : "通知 (iOS 26+ で本物アラーム)")
                OnbCard(icon: "moon.zzz", title: "マナー中でも鳴動",
                        desc: engine.usesRealAlarms ? "サイレント/集中を突破" : "通知はマナーに従う")
            }
            .padding(.horizontal)

            Spacer(minLength: 8)
            Button {
                requesting = true
                Task {
                    _ = await engine.reader.requestAccess()
                    await engine.authorizeScheduler()
                    store.completeOnboarding()
                    await engine.resync(reason: "onboarding")
                }
            } label: {
                Text("はじめる")
                    .font(NightTheme.font(17, weight: .semibold))
                    .frame(maxWidth: .infinity).padding(.vertical, 14)
                    .background(NightTheme.indigo, in: RoundedRectangle(cornerRadius: 14))
                    .foregroundStyle(.white)
            }
            .padding(.horizontal)
            .disabled(requesting)

            Button {
                store.completeOnboarding()
            } label: {
                Text("権限なしで始める").font(NightTheme.font(13)).foregroundStyle(.secondary)
            }
            .padding(.bottom, 24)
        }
        .frame(maxWidth: .infinity)
        }
        .background(Color(uiColor: .systemGroupedBackground))
    }
}

struct OnbCard: View {
    let icon: String
    let title: String
    let desc: String
    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon).font(.system(size: 20)).foregroundStyle(NightTheme.indigo).frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(NightTheme.font(14, weight: .medium))
                Text(desc).font(NightTheme.font(12)).foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(14)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }
}
