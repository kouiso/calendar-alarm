import SwiftUI

/// 鳴動画面の天気演出。Android WeatherBackdrop.kt と同等:
/// WMOコード → 雨/雪/霧/雷/星空/日射 の軽量パーティクルを TimelineView + Canvas で描く。
struct WeatherBackdropView: View {
    var code: Int
    var isDay: Bool

    private enum Kind {
        case clear, cloudy, fog, rain, shower, snow, thunder
    }

    private var kind: Kind {
        switch code {
        case 0, 1: return .clear
        case 2, 3: return .cloudy
        case 45, 48: return .fog
        case 51, 53, 55, 56, 57: return .shower
        case 61, 63, 65, 66, 67: return .rain
        case 80, 81, 82: return .shower
        case 71, 73, 75, 77, 85, 86: return .snow
        case 95, 96, 99: return .thunder
        default: return .cloudy
        }
    }

    private var baseColors: [Color] {
        switch kind {
        case .clear:
            return isDay
                ? [Color(hex: 0xFF2C3E6B), Color(hex: 0xFF1A2340)]
                : [Color(hex: 0xFF0A0F22), Color(hex: 0xFF05070F)]
        case .cloudy:
            return [Color(hex: 0xFF252B3E), Color(hex: 0xFF141827)]
        case .fog:
            return [Color(hex: 0xFF30343F), Color(hex: 0xFF1B1E26)]
        case .rain, .shower:
            return [Color(hex: 0xFF1E2A3C), Color(hex: 0xFF0E1420)]
        case .snow:
            return [Color(hex: 0xFF2B3242), Color(hex: 0xFF151A26)]
        case .thunder:
            return [Color(hex: 0xFF241F33), Color(hex: 0xFF120F1C)]
        }
    }

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 24)) { tl in
            Canvas { ctx, size in
                let t = tl.date.timeIntervalSince1970
                drawBase(&ctx, size: size)
                switch kind {
                case .clear:
                    isDay ? drawSun(&ctx, size: size, t: t) : drawStars(&ctx, size: size, t: t)
                case .cloudy, .fog:
                    drawClouds(&ctx, size: size, t: t, dense: kind == .fog)
                case .rain:
                    drawRain(&ctx, size: size, t: t, drops: 60, slant: 0.10)
                case .shower:
                    drawRain(&ctx, size: size, t: t, drops: 40, slant: 0.18)
                case .snow:
                    drawSnow(&ctx, size: size, t: t)
                case .thunder:
                    drawRain(&ctx, size: size, t: t, drops: 50, slant: 0.12)
                    drawFlash(&ctx, size: size, t: t)
                }
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }

    private func drawBase(_ ctx: inout GraphicsContext, size: CGSize) {
        ctx.fill(
            Path(CGRect(origin: .zero, size: size)),
            with: .linearGradient(
                Gradient(colors: baseColors),
                startPoint: .zero,
                endPoint: CGPoint(x: 0, y: size.height)
            )
        )
    }

    /// 星空: 擬似乱数で星を撒き、周期で明滅。
    private func drawStars(_ ctx: inout GraphicsContext, size: CGSize, t: TimeInterval) {
        var rnd = SplitMix(0xC0FFEE)
        for _ in 0..<70 {
            let x = Double(rnd.next()) * Double(size.width)
            let y = Double(rnd.next()) * Double(size.height) * 0.7
            let phase = Double(rnd.next()) * 6.28
            let a = 0.25 + 0.55 * (0.5 + 0.5 * sin(t * 1.4 + phase))
            ctx.opacity = a
            ctx.fill(
                Path(ellipseIn: CGRect(x: x, y: y, width: 2.2, height: 2.2)),
                with: .color(.white)
            )
        }
        ctx.opacity = 1
    }

    /// 日射: 上部の柔らかい光。
    private func drawSun(_ ctx: inout GraphicsContext, size: CGSize, t: TimeInterval) {
        let cx = size.width * 0.72, cy = size.height * 0.16
        let pulse = 0.85 + 0.15 * sin(t * 1.1)
        for i in stride(from: 6, through: 1, by: -1) {
            let r = CGFloat(i) * 46 * pulse
            ctx.opacity = 0.045
            ctx.fill(
                Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2)),
                with: .color(Color(hex: 0xFFFFD9A0))
            )
        }
        ctx.opacity = 1
    }

    /// 雲/霧: 横に流れる半透明ブロブ。
    private func drawClouds(_ ctx: inout GraphicsContext, size: CGSize, t: TimeInterval, dense: Bool) {
        var rnd = SplitMix(0xBEEF)
        let n = dense ? 10 : 7
        for i in 0..<n {
            let baseX = Double(rnd.next()) * 1.4 - 0.2
            let y = Double(rnd.next()) * Double(size.height)
            let speed = 0.008 + Double(rnd.next()) * 0.02
            let w = 180 + Double(rnd.next()) * 160
            let x = (baseX + t * speed).truncatingRemainder(dividingBy: 1.4) * Double(size.width) - 0.2 * Double(size.width)
            ctx.opacity = dense ? 0.10 : 0.07
            ctx.fill(
                Path(ellipseIn: CGRect(x: x, y: y, width: w, height: w * 0.36)),
                with: .color(.white)
            )
            _ = i
        }
        ctx.opacity = 1
    }

    /// 雨: 上から下へ流れる筋。
    private func drawRain(_ ctx: inout GraphicsContext, size: CGSize, t: TimeInterval, drops: Int, slant: Double) {
        var rnd = SplitMix(0xF00D)
        for _ in 0..<drops {
            let x0 = Double(rnd.next()) * Double(size.width) * 1.15
            let speed = 0.55 + Double(rnd.next()) * 0.5
            let len = 14 + Double(rnd.next()) * 18
            let y = (Double(rnd.next()) + t * speed).truncatingRemainder(dividingBy: 1.0) * Double(size.height)
            let dx = len * slant
            var p = Path()
            p.move(to: CGPoint(x: x0, y: y))
            p.addLine(to: CGPoint(x: x0 + dx, y: y + len))
            ctx.opacity = 0.28
            ctx.stroke(p, with: .color(Color(hex: 0xFF9FB7D9)), lineWidth: 1.1)
        }
        ctx.opacity = 1
    }

    /// 雪: ゆっくり落ちて横に揺れる点。
    private func drawSnow(_ ctx: inout GraphicsContext, size: CGSize, t: TimeInterval) {
        var rnd = SplitMix(0x5EED)
        for _ in 0..<50 {
            let xBase = Double(rnd.next()) * Double(size.width)
            let speed = 0.045 + Double(rnd.next()) * 0.06
            let amp = 8 + Double(rnd.next()) * 16
            let phase = Double(rnd.next()) * 6.28
            let r = 1.6 + Double(rnd.next()) * 2.2
            let y = (Double(rnd.next()) + t * speed).truncatingRemainder(dividingBy: 1.0) * Double(size.height)
            let x = xBase + sin(t * 0.9 + phase) * amp
            ctx.opacity = 0.7
            ctx.fill(
                Path(ellipseIn: CGRect(x: x, y: y, width: r * 2, height: r * 2)),
                with: .color(.white)
            )
        }
        ctx.opacity = 1
    }

    /// 雷: 周期の8%だけ全面フラッシュ。
    private func drawFlash(_ ctx: inout GraphicsContext, size: CGSize, t: TimeInterval) {
        let cycle = t.truncatingRemainder(dividingBy: 2.4) / 2.4
        guard cycle < 0.08 else { return }
        ctx.opacity = 0.35 * (1 - cycle / 0.08)
        ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(.white))
        ctx.opacity = 1
    }
}

/// 乱数を毎フレーム同じにするための軽量 PRNG (Swift の Random() は呼ぶたび違う)。
private struct SplitMix {
    private var s: UInt64
    init(_ seed: UInt64) { s = seed }
    mutating func next() -> Double {
        s &+= 0x9E3779B97F4A7C15
        var z = s
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        z = z ^ (z >> 31)
        return Double(z >> 11) / Double(1 << 53)
    }
}
