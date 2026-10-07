import SwiftUI

/// タイマー + ストップウォッチ (Android TimerView と同等)。
struct TimerView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store

    enum Mode: String, CaseIterable { case timer = "タイマー", stopwatch = "ストップウォッチ" }
    @State private var mode: Mode = .timer

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    Picker("", selection: $mode) {
                        ForEach(Mode.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal)
                    if mode == .timer { TimerFace() } else { StopwatchFace() }
                }
                .frame(maxWidth: .infinity)
            }
            .scrollIndicators(.hidden)
            .background(Color(uiColor: .systemGroupedBackground))
        }
    }
}

/// タイマー: 分ピッカー + 開始/取消。鳴動は AlarmKit/通知経由。
struct TimerFace: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @State private var minutes = 5
    @State private var label = ""
    @State private var running: AlarmInstanceDTO? = nil
    @State private var remaining: Int = 0
    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 20) {
            ZStack {
                // 時計フェイス風リング
                ClockRing()
                if let r = running {
                    VStack {
                        Text(Self.mmss(remaining)).font(NightTheme.font(44, weight: .light)).monospacedDigit()
                        Text(r.title).font(NightTheme.font(12)).foregroundStyle(.secondary)
                    }
                } else {
                    VStack(spacing: 4) {
                        Picker("分", selection: $minutes) {
                            ForEach([1, 3, 5, 10, 15, 20, 30, 45, 60], id: \.self) {
                                Text("\($0)分").tag($0)
                            }
                        }
                        .pickerStyle(.wheel).frame(width: 140, height: 120).clipped()
                    }
                }
            }
            .frame(width: 260, height: 260)

            if running == nil {
                TextField("ラベル", text: $label)
                    .textFieldStyle(.roundedBorder).frame(width: 200)
            }

            HStack(spacing: 16) {
                if running == nil {
                    Button {
                        Task {
                            await engine.scheduleTimer(durationMillis: Int64(minutes) * 60_000, label: label)
                            syncRunning()
                        }
                    } label: {
                        Label("開始", systemImage: "play.fill")
                            .font(NightTheme.font(16, weight: .medium))
                            .padding(.horizontal, 32).padding(.vertical, 12)
                            .background(NightTheme.indigo, in: Capsule()).foregroundStyle(.white)
                    }
                } else {
                    Button {
                        if let r = running { Task { await engine.cancelInstance(r.id); syncRunning() } }
                    } label: {
                        Label("取消", systemImage: "stop.fill")
                            .font(NightTheme.font(16, weight: .medium))
                            .padding(.horizontal, 32).padding(.vertical, 12)
                            .background(Color(uiColor: .tertiarySystemGroupedBackground), in: Capsule())
                            .foregroundStyle(.primary)
                    }
                }
            }
            .onReceive(ticker) { _ in syncRemaining() }
            .onAppear { syncRunning() }
        }
    }

    private func syncRunning() {
        let now = Engine.nowMillis
        running = store.state.scheduled.values
            .filter { $0.instance.kind == "TIMER" && $0.state == .pending && $0.instance.triggerAtMillis > now }
            .map { $0.instance }
            .sorted { $0.triggerAtMillis < $1.triggerAtMillis }
            .first
        syncRemaining()
    }
    private func syncRemaining() {
        if let r = running {
            remaining = max(0, Int((r.triggerAtMillis - Engine.nowMillis) / 1000))
            if remaining == 0 { running = nil }
        }
    }
    static func mmss(_ s: Int) -> String { String(format: "%d:%02d", s / 60, s % 60) }
}

/// ストップウォッチ (アプリ前面でのみ動く純粋 UI)。
struct StopwatchFace: View {
    @State private var running = false
    @State private var start: Date? = nil
    @State private var elapsed: TimeInterval = 0
    @State private var laps: [TimeInterval] = []
    private let ticker = Timer.publish(every: 0.05, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 20) {
            ZStack {
                ClockRing()
                Text(Self.fmt(current))
                    .font(NightTheme.font(44, weight: .light)).monospacedDigit()
            }
            .frame(width: 260, height: 260)
            .onReceive(ticker) { _ in }

            HStack(spacing: 16) {
                Button {
                    if running { laps.insert(current, at: 0) } else { reset() }
                } label: {
                    Text(running ? "ラップ" : "リセット")
                        .font(NightTheme.font(14, weight: .medium))
                        .frame(width: 88, height: 44)
                        .background(Color(uiColor: .tertiarySystemGroupedBackground), in: Capsule())
                }
                Button {
                    if running { elapsed = current; start = nil } else { start = Date() }
                    running.toggle()
                } label: {
                    Text(running ? "停止" : "開始")
                        .font(NightTheme.font(16, weight: .medium))
                        .frame(width: 88, height: 44)
                        .background(running ? NightTheme.amber : NightTheme.indigo, in: Capsule())
                        .foregroundStyle(running ? .black : .white)
                }
            }

            if !laps.isEmpty {
                List(laps.indices, id: \.self) { i in
                    HStack {
                        Text("Lap \(laps.count - i)").font(NightTheme.font(13)).foregroundStyle(.secondary)
                        Spacer()
                        Text(Self.fmt(laps[i])).font(NightTheme.font(13)).monospacedDigit()
                    }
                }
                .listStyle(.plain).frame(maxHeight: 220)
            }
        }
    }

    private var current: TimeInterval { elapsed + (start.map { Date().timeIntervalSince($0) } ?? 0) }
    private func reset() { elapsed = 0; laps = []; start = nil }

    static func fmt(_ t: TimeInterval) -> String {
        let m = Int(t) / 60, s = Int(t) % 60, c = Int((t.truncatingRemainder(dividingBy: 1)) * 100)
        return String(format: "%d:%02d.%02d", m, s, c)
    }
}

/// 60分目盛りの円リング (Android TimerView の時計フェイスと同等)。
struct ClockRing: View {
    var body: some View {
        Canvas { ctx, size in
            let c = CGPoint(x: size.width / 2, y: size.height / 2)
            let r = min(size.width, size.height) / 2
            for i in 0..<60 {
                let a = CGFloat(i) / 60 * 2 * .pi - .pi / 2
                let major = i % 5 == 0
                let r1 = r - (major ? 12 : 7)
                let p1 = CGPoint(x: c.x + cos(a) * r1, y: c.y + sin(a) * r1)
                let p2 = CGPoint(x: c.x + cos(a) * r, y: c.y + sin(a) * r)
                var path = Path(); path.move(to: p1); path.addLine(to: p2)
                ctx.stroke(path, with: .color(major ? NightTheme.indigo.opacity(0.7) : .secondary.opacity(0.25)),
                           lineWidth: major ? 2 : 1)
            }
        }
    }
}
