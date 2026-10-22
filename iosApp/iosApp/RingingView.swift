import SwiftUI
import SharedKit

/// 鳴動画面 (Night UI スペック): 日付チップ + アクセントリング2重 + Outfit極細112pt時刻
/// + 残りラベル + 天気チップ + スヌーズ大ボタン (80h) / 開閉式プリセットチップ / スライド停止 (80h)。常時ダーク基調。
/// CalendarAlarmApp が alertingIds 非空の間アプリ内全画面オーバーレイとして描画する。
/// AlarmKit のシステムカード UI は OS 描画であり本 View ではない (小さい窓対策は全画面前提のまま保持)。
struct RingingView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @State private var now = Date()
    @State private var weather: WeatherService.NowForecast? = nil
    @State private var showSnoozePresets: Bool
    /// プリセット設定が iOS 側に無いため固定候補 (Android は設定の snoozePresets を使用)
    private let snoozePresets = [5, 10, 15, 30]
    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    /// 試験で「チップを開いた状態」から始めるための引数。画面から開く時は既定の false のまま
    init(startsWithPresetsOpen: Bool = false) {
        _showSnoozePresets = State(initialValue: startsWithPresetsOpen)
    }

    private var accent: Color {
        AppPalette.byId(store.state.themeId).accent
    }

    /// 表示する鳴動。Set の順序は不定なので、最も古い発火 (同時刻なら id 順) に固定し、
    /// 別の鳴動が加わっても表示中の鳴動が勝手に入れ替わらないようにする
    private var current: AlarmInstanceDTO? {
        engine.alertingIds.compactMap { store.state.scheduled[$0]?.instance }
            .min { ($0.triggerAtMillis, $0.id) < ($1.triggerAtMillis, $1.id) }
    }

    var body: some View {
        ZStack {
            if let w = weather {
                WeatherBackdropView(code: w.current.weatherCode, isDay: w.current.isDay)
            } else {
                NightTheme.nightBg.ignoresSafeArea()
            }
            // 上部(日付/時刻/件名)は窓に応じてスクロール、下部の操作群(スヌーズ/スライド停止)は
            // 常時到達できるようピン留め — 止め方が画面外に逃げるのを防ぐための構造。
            GeometryReader { geo in
            VStack(spacing: 0) {
                ScrollView {
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
                            .padding(.top, 12)
                        // 時刻はチップ直下の固定位置に置く。Spacer 中央配置だと窓が
                        // 小さい時にリングがスクロール領域外へ沈み時刻が見えなくなる
                        Spacer(minLength: 8).frame(maxHeight: 48)
                        // アクセントリング + 大時刻 (デザイン正本: 112pt / リング 224・196)
                        // 窓が小さい時だけ高さに応じて縮める (レターボックス等の小窓で隠れない)。
                        ZStack {
                            Circle().stroke(accent.opacity(0.25), lineWidth: 1.5).frame(width: 224, height: 224)
                            Circle().stroke(accent.opacity(0.15), lineWidth: 1).frame(width: 196, height: 196)
                            Text(now, format: .dateTime.hour().minute())
                                .font(NightTheme.numFont(112, weight: .ultraLight))
                                .foregroundStyle(NightTheme.onNight)
                                // "1:09 AM" 12h 表記でも1行に収める
                                .lineLimit(1)
                                .minimumScaleFactor(0.45)
                                .onReceive(ticker) { now = $0 }
                        }
                        .scaleEffect(min(1, max(0.55, (geo.size.height - 320) / 420)))
                        .padding(.bottom, 10)
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
                        }
                        Spacer(minLength: 8)
                    }
                    .frame(maxWidth: .infinity, minHeight: geo.size.height - 280)
                }
                .scrollBounceBehavior(.basedOnSize)
                if let inst = current {
                    // スヌーズは大きい1ボタンのみ: プリセット常時表示は押し間違いの元なので畳む
                    Button { engine.snooze(inst) } label: {
                        Text("スヌーズ \(inst.snoozeMinutes)分")
                            .font(NightTheme.font(22, weight: .semibold))
                            .foregroundStyle(NightTheme.onNight)
                            .frame(maxWidth: .infinity).frame(height: 80)
                            .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 40))
                    }
                    .padding(.horizontal, 24)
                    // 別分数スヌーズは開閉式: 「スヌーズ時間を選ぶ」タップ時のみチップを展開。
                    // 窓が低すぎる時は出さない: チップを開くと固定した操作群が 287pt になり、
                    // 上の時刻と件名のスクロール領域が潰れるため (窓 320pt だと 1pt まで)。停止の丸はこの判定に関わらず窓に残る
                    let canChoose = RingingLayout.showsPresetPicker(windowHeight: geo.size.height)
                    if canChoose {
                        Button {
                            withAnimation(.easeInOut(duration: 0.15)) { showSnoozePresets.toggle() }
                        } label: {
                            // 三項演算子で文字列を渡すと LocalizedStringKey にならず翻訳が効かないため、リテラルを分けて書く
                            Group {
                                if showSnoozePresets {
                                    Text("スヌーズ時間を選ぶ ▴")
                                } else {
                                    Text("スヌーズ時間を選ぶ ▾")
                                }
                            }
                            .font(NightTheme.font(14))
                            .foregroundStyle(NightTheme.muted)
                            .padding(.horizontal, 12).padding(.vertical, 8)
                        }
                        .padding(.top, 4)
                    }
                    if showSnoozePresets && canChoose {
                        HStack(spacing: 8) {
                            ForEach(snoozePresets, id: \.self) { m in
                                Button { engine.snooze(inst, minutes: m) } label: {
                                    Text("+\(m)分")
                                        .font(NightTheme.font(14))
                                        .foregroundStyle(NightTheme.onNight)
                                        .frame(maxWidth: .infinity).frame(height: 40)
                                        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
                                }
                            }
                        }
                        .padding(.horizontal, 24).padding(.top, 4)
                    }
                    // 停止はスライド式: 誤タップで止まらないよう左端の丸を右へドラッグ
                    SlideToStop(accent: accent) {
                        engine.dismiss(inst)
                        // dismiss 失敗時 (silence 拒否) は id が alertingIds に残る。
                        // 画面が残る以上、スライダーの丸を戻して再操作させるため成否を返す
                        return !engine.alertingIds.contains(inst.id)
                    }
                    // 鳴動ごとに丸の状態を分ける: 同時に鳴る別アラームへ切り替わった時に
                    // 前の鳴動の「右端に貼り付いた丸」を引き継がない
                    .id(inst.id)
                    .padding(.top, 8).padding(.bottom, 8)
                    Text("Calendar Alarm")
                        .font(NightTheme.font(12))
                        .foregroundStyle(NightTheme.onNight.opacity(0.4))
                        .padding(.bottom, 8)
                }
            }
            }
        }
        .task {
            let loc = store.state.weatherLocation
            guard store.state.weatherOnAlarmScreen, !loc.isEmpty else { return }
            weather = await WeatherService().now(for: loc, hours: 1)
        }
        // 鳴動画面は端末設定に依らず常時ダーク固定 (半睡状態で眩しくしないため)。
        // NightTheme.adaptive は userInterfaceStyle を見るので階層ごと dark に倒す。
        .environment(\.colorScheme, .dark)
    }

    private func remainingLabel(_ inst: AlarmInstanceDTO) -> String {
        if let start = inst.eventStartMillis {
            let remain = Date(timeIntervalSince1970: TimeInterval(start) / 1000).timeIntervalSince(now)
            if remain > 0 { return "\(Int((remain + 30) / 60))分後に開始" }
        }
        return "まもなく開始"
    }
}

/// 鳴動画面の寸法に関する決まり。下に固定した操作群が背伸びして、上の時刻と件名の領域が潰れるのを防ぐ
enum RingingLayout {
    /// 「スヌーズ時間を選ぶ」を出す窓の最低の高さ (pt)。安全領域を除いた、画面の部品が見る高さで比べる。
    /// 固定した操作群は、開いたチップ込みで 287pt (RingingWindowRenderTests で実測)。
    /// これを引いても上のスクロール領域に 233pt 残る高さにしてある。
    /// これより低い窓では、既定のスヌーズと停止だけを残す
    static let minHeightForPresetPicker: CGFloat = 520

    static func showsPresetPicker(windowHeight: CGFloat) -> Bool {
        windowHeight >= minHeightForPresetPicker
    }
}

/// スライド停止の指の判定。閾値と掴める幅は Android と同じ shared の SlideToStopRule を読む
enum SlideToStopLogic {
    /// トラック左端から [startX] の所に指を置いた時、丸を掴んだことにするか
    static func isGrab(startX: CGFloat, thumbSize: CGFloat, pad: CGFloat) -> Bool {
        SlideToStopRule.shared.isGrab(startX: Float(startX), thumbSize: Float(thumbSize), pad: Float(pad))
    }

    /// 指を離した後の丸の位置。止める位置まで引けていて、かつ止められた時だけ右端 (1) に残す。
    /// 止められなかった時は左端 (0) へ戻す: 掴める所は左端なので、右に残すともう一度引けない
    static func releasedProgress(_ progress: CGFloat, stop: () -> Bool) -> CGFloat {
        guard SlideToStopRule.shared.shouldStop(progress: Float(progress)) else { return 0 }
        return stop() ? 1 : 0
    }
}

/// 「スライドして停止」トラック。左端の丸を右へドラッグし、トラック幅の80%を
/// 超えた時点で停止を実行する (誤タップ防止の閾値)。VoiceOver では
/// accessibilityRepresentation で「停止」ボタンとして公開し、ダブルタップで止まる。
private struct SlideToStop: View {
    let accent: Color
    /// 停止実行。true=停止成功 (画面が閉じる) / false=失敗で鳴動継続 (丸を戻して再操作させる)
    let onStop: () -> Bool
    @State private var progress: CGFloat = 0
    private let thumbSize: CGFloat = 64
    private let pad: CGFloat = 8

    var body: some View {
        GeometryReader { geo in
            let travel = max(geo.size.width - thumbSize - pad * 2, 1)
            // 式を modifier の引数に直接置くと、Xcode 26 で .font が曖昧と判定される
            let labelOpacity = Double(max(0, 1 - progress * 1.2))
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 40)
                    .fill(Color.white.opacity(0.08))
                RoundedRectangle(cornerRadius: 40)
                    .stroke(accent.opacity(0.35), lineWidth: 1)
                Text("スライドして停止 ›")
                    .font(NightTheme.font(20, weight: .semibold))
                    .foregroundStyle(NightTheme.onNight)
                    .frame(maxWidth: .infinity)
                    .opacity(labelOpacity)
                Circle()
                    .fill(accent)
                    .frame(width: thumbSize, height: thumbSize)
                    // 丸の中は停止の■ (デザイン正本: 矢印ではなく停止アイコン)
                    .overlay(
                        RoundedRectangle(cornerRadius: 3)
                            .fill(Color.black.opacity(0.75))
                            .frame(width: 22, height: 22)
                    )
                    .padding(.leading, pad)
                    .offset(x: progress * travel)
            }
            .contentShape(RoundedRectangle(cornerRadius: 40))
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        // 掴み始めが丸の外 (トラック右側等) なら無効: 画面のどこでも
                        // 横スワイプが効くと意図しない停止につながるため起点を縛る
                        guard SlideToStopLogic.isGrab(startX: value.startLocation.x, thumbSize: thumbSize, pad: pad) else { return }
                        progress = min(max(value.translation.width / travel, 0), 1)
                    }
                    .onEnded { value in
                        // 掴み始めが丸の外のジェスチャー (取り消しで残った progress を含む) では停止しない
                        guard SlideToStopLogic.isGrab(startX: value.startLocation.x, thumbSize: thumbSize, pad: pad) else {
                            withAnimation(.easeOut(duration: 0.15)) { progress = 0 }
                            return
                        }
                        let released = SlideToStopLogic.releasedProgress(progress, stop: onStop)
                        withAnimation(.easeOut(duration: 0.15)) { progress = released }
                    }
            )
        }
        .frame(height: 80)
        .padding(.horizontal, 24)
        .accessibilityRepresentation {
            Button("停止") { _ = onStop() }
        }
    }
}
