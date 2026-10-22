import XCTest
import SwiftUI
@testable import iosApp

/// 鳴動画面を実物の View のまま低い窓に入れて描き、画像の画素から、停止の丸が窓の中に残ることと、
/// 時刻と件名の領域が潰れないことを確かめる (#52)。
/// 窓の高さの判定 (RingingLayout) だけでは、下に固定した操作群が実際に窓へどう収まるかは分からない。
/// 窓の幅は 700pt。時刻の下の天気は出さない (背景が一色になり、部品の縁を画素で拾える)。
/// RING_SHOT_DIR を渡すと、描いた画像と測った値を書き出す
@MainActor
final class RingingWindowRenderTests: XCTestCase {
    private var engine: Engine!
    private var window: UIWindow!
    private var host: UIHostingController<AnyView>!
    private var outDir: String?
    private var log = ""
    private let width: CGFloat = 700

    override func setUp() async throws {
        engine = Engine(
            scheduler: FakeScheduler(),
            store: Store(fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("store-\(UUID().uuidString).json"))
        )
        engine.store.mutate {
            $0.scheduled = [:]
            $0.standaloneAlarms = []
        }
        outDir = ProcessInfo.processInfo.environment["RING_SHOT_DIR"]
        if let d = outDir { try FileManager.default.createDirectory(atPath: d, withIntermediateDirectories: true) }
        // 鳴っている 1 件を作る (EngineSnoozeMinutesTests.ringOneShot と同じ手順)
        let at = Date().addingTimeInterval(2 * 3600)
        let c = Calendar.current.dateComponents([.hour, .minute], from: at)
        let aid = engine.store.upsertAlarm(StandaloneAlarmDTO(hour: c.hour!, minute: c.minute!, label: "朝の定例", repeatMode: "ONCE"))
        await engine.resync(reason: "render test")
        let rec = try XCTUnwrap(engine.store.state.scheduled.values.first { $0.instance.standaloneAlarmId == aid })
        engine.store.markState(rec.instance.id, .fired)
        engine.alertingIds = [rec.instance.id]
    }

    override func tearDown() async throws {
        window?.isHidden = true
        window = nil
        host = nil
        engine.store.mutate {
            $0.scheduled = [:]
            $0.standaloneAlarms = []
        }
        if let d = outDir { try? log.write(toFile: "\(d)/render-\(name.filter { $0.isLetter }).log", atomically: true, encoding: .utf8) }
    }

    private func note(_ s: String) { log += s + "\n"; print("RINGRENDER " + s) }

    /// 実物の RingingView を、幅 700pt 高さ [height] の窓に入れる
    private func show(height: CGFloat, presetsOpen: Bool = false, textSize: UIContentSizeCategory? = nil) {
        let root = AnyView(RingingView(startsWithPresetsOpen: presetsOpen).environmentObject(engine).environmentObject(engine.store))
        host = UIHostingController(rootView: root)
        // 場面 (scene) に属さない窓は iOS 26 では何も描かれない。アプリが持っている場面の上に、別の窓を重ねる
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow(frame: .zero)
        window.frame = CGRect(x: 0, y: 0, width: width, height: height)
        window.windowLevel = .alert + 1
        window.rootViewController = host
        if let t = textSize { host.traitOverrides.preferredContentSizeCategory = t }
        window.makeKeyAndVisible()
        settle()
    }

    /// 画面の部品が見る高さ (安全領域を除いた窓の高さ) が [layoutHeight] になる窓に入れる。
    /// RingingLayout の判定はこの高さで見るので、境目の 520pt を窓の高さと取り違えない
    private func showForLayout(height layoutHeight: CGFloat, presetsOpen: Bool = false, textSize: UIContentSizeCategory? = nil) {
        show(height: layoutHeight, presetsOpen: presetsOpen, textSize: textSize)
        let insets = window.safeAreaInsets
        resize(height: layoutHeight + insets.top + insets.bottom)
        note("安全領域 top=\(insets.top) bottom=\(insets.bottom) → 窓の高さ=\(Int(window.bounds.height))")
    }

    private func resize(height: CGFloat) {
        window.frame = CGRect(x: 0, y: 0, width: width, height: height)
        window.layoutIfNeeded()
        settle()
    }

    private func settle() {
        RunLoop.main.run(until: Date().addingTimeInterval(0.8))
        window.layoutIfNeeded()
    }

    /// 窓を描いた画像 (RGBA8) とその倍率
    private struct Snapshot {
        let w: Int, h: Int, scale: CGFloat
        let data: [UInt8]
        func pixel(_ x: Int, _ y: Int) -> (r: Int, g: Int, b: Int) {
            let i = (y * w + x) * 4
            return (Int(data[i]), Int(data[i + 1]), Int(data[i + 2]))
        }
    }

    private func snapshot(_ name: String) throws -> Snapshot {
        let r = UIGraphicsImageRenderer(bounds: window.bounds)
        let img = r.image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
        if let d = outDir { try? img.pngData()?.write(to: URL(fileURLWithPath: "\(d)/\(name).png")) }
        let cg = try XCTUnwrap(img.cgImage)
        var data = [UInt8](repeating: 0, count: cg.width * cg.height * 4)
        let ctx = try XCTUnwrap(CGContext(
            data: &data, width: cg.width, height: cg.height, bitsPerComponent: 8, bytesPerRow: cg.width * 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
        return Snapshot(w: cg.width, h: cg.height, scale: img.scale, data: data)
    }

    /// 縦の 1 列 (窓の左から [xPt]) で、背景と違う色が続く区間 [上端, 下端) を pt で返す。背景は一番上の画素の色
    private func runs(_ s: Snapshot, xPt: CGFloat) -> [(top: CGFloat, bottom: CGFloat)] {
        let x = Int(xPt * s.scale)
        let bg = s.pixel(x, 0)
        func isBg(_ p: (r: Int, g: Int, b: Int)) -> Bool { abs(p.r - bg.r) + abs(p.g - bg.g) + abs(p.b - bg.b) < 12 }
        var out: [(CGFloat, CGFloat)] = []
        var start: Int?
        for y in 0..<s.h {
            let on = !isBg(s.pixel(x, y))
            if on, start == nil { start = y }
            if !on, let st = start {
                // 1pt 未満は縁の濃淡として捨てる
                if CGFloat(y - st) / s.scale >= 1 { out.append((CGFloat(st) / s.scale, CGFloat(y) / s.scale)) }
                start = nil
            }
        }
        if let st = start { out.append((CGFloat(st) / s.scale, CGFloat(s.h) / s.scale)) }
        return out
    }

    /// 停止の丸 (アクセント色の塗り) の上端と下端。丸は窓の左から 32〜96pt にあるので、その中の列だけを見る
    private func thumb(_ s: Snapshot) -> (top: CGFloat, bottom: CGFloat)? {
        var top: Int?, bottom: Int?
        for y in 0..<s.h {
            var hits = 0
            for xPt in stride(from: 40.0, through: 88.0, by: 2.0) {
                let p = s.pixel(Int(CGFloat(xPt) * s.scale), y)
                if p.r > 200 && p.g > 140 && p.r - p.b > 70 { hits += 1 }
            }
            if hits >= 8 { if top == nil { top = y }; bottom = y + 1 }
        }
        guard let t = top, let b = bottom else { return nil }
        return (CGFloat(t) / s.scale, CGFloat(b) / s.scale)
    }

    private struct Measure {
        /// 縦 1 列で見つかった塊 (上から)。窓の右寄りの列なので、時刻・件名・文字は入らない
        let blocks: [(top: CGFloat, bottom: CGFloat)]
        let thumb: (top: CGFloat, bottom: CGFloat)?
        var description: String {
            "blocks=" + blocks.map { "\(Int($0.top))..\(Int($0.bottom))(\(Int($0.bottom - $0.top)))" }.joined(separator: ",")
                + " thumb=" + (thumb.map { "\(Int($0.top))..\(Int($0.bottom))" } ?? "none")
        }
    }

    private func measure(_ name: String) throws -> Measure {
        let s = try snapshot(name)
        // 右寄りの列 (窓の幅 - 100pt)。スヌーズの大ボタン (高さ 80)・チップ (高さ 40)・停止の溝 (高さ 80) だけが横切る
        let m = Measure(blocks: runs(s, xPt: width - 100), thumb: thumb(s))
        note("\(name) window=\(Int(width))x\(Int(window.bounds.height)) :: \(m.description)")
        return m
    }

    /// 停止の丸が丸ごと窓の中にある (高さ 64pt)
    private func assertStopInside(_ m: Measure, _ tag: String, file: StaticString = #filePath, line: UInt = #line) throws {
        let h = window.bounds.height
        let t = try XCTUnwrap(m.thumb, "\(tag): 停止の丸が窓の中に描かれていない", file: file, line: line)
        XCTAssertEqual(t.bottom - t.top, 64, accuracy: 2, "\(tag): 停止の丸が欠けている (\(t.top)..\(t.bottom))", file: file, line: line)
        XCTAssertLessThanOrEqual(t.bottom, h, "\(tag): 停止の丸が窓の下へはみ出した", file: file, line: line)
        XCTAssertGreaterThanOrEqual(t.top, 0, file: file, line: line)
    }

    /// 上のスクロール領域 (時刻・件名) に残る高さ。スヌーズ大ボタンの上端から、上の安全領域を引いた値
    private func scrollArea(_ m: Measure) -> CGFloat { (m.blocks.first?.top ?? 0) - window.safeAreaInsets.top }

    /// 時刻と件名の領域が潰れていない。固定した操作群が背伸びして、スクロール領域が 0 になると画面に時刻が出ない
    private func assertScrollAreaKept(_ m: Measure, _ tag: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertGreaterThanOrEqual(scrollArea(m), 80, "\(tag): 時刻と件名の領域が \(Int(scrollArea(m)))pt に潰れている", file: file, line: line)
    }

    /// チップ (高さ 40) の塊の数
    private func chipBlocks(_ m: Measure) -> Int { m.blocks.filter { abs(($0.bottom - $0.top) - 40) < 3 }.count }

    /// 窓の高さごとに、折りたたんだ状態で停止の丸が窓に収まる
    func testStopStaysInsideAtEveryHeight() throws {
        for h in [667.0, 560.0, 520.0, 519.0, 480.0, 400.0, 320.0, 260.0] {
            show(height: CGFloat(h))
            let m = try measure("collapsed-h\(Int(h))")
            try assertStopInside(m, "折りたたみ h=\(Int(h))")
        }
    }

    /// チップを開いたまま、出す最小の高さ (画面の部品が見る高さで 520pt) と、その上に置く。
    /// 固定した操作群の高さは「スヌーズ大ボタンの上端〜窓の下端」、残るスクロール領域はその上端の位置。
    /// 境目の 1pt 下 (519pt) ではチップと選ぶボタンが消え、停止の丸は窓に残る
    func testChipsOpenAtThresholdKeepsStopInside() throws {
        for h in [520.0, 560.0, 667.0] {
            showForLayout(height: CGFloat(h), presetsOpen: true)
            let m = try measure("chips-open-layout\(Int(h))")
            XCTAssertEqual(chipBlocks(m), 1, "layout \(Int(h)): チップの行が出ていない")
            try assertStopInside(m, "チップ開 layout \(Int(h))")
            assertScrollAreaKept(m, "チップ開 layout \(Int(h))")
            let pillTop = try XCTUnwrap(m.blocks.first?.top)
            note("chips-open-layout\(Int(h)): 固定した操作群の高さ=\(Int(window.bounds.height - pillTop)) 残るスクロール領域=\(Int(pillTop))")
        }
        showForLayout(height: 519, presetsOpen: true)
        let below = try measure("chips-open-layout519")
        XCTAssertEqual(chipBlocks(below), 0, "layout 519: チップが残っている")
        try assertStopInside(below, "チップ開 layout 519")
        assertScrollAreaKept(below, "チップ開 layout 519")
    }

    /// チップを開いてから窓を 520pt 未満へ縮める: 選ぶボタンとチップは消え、停止の丸は窓に残る。戻すとチップも戻る
    func testShrinkingBelowThresholdAfterOpeningChipsHidesPickerAndKeepsStop() throws {
        show(height: 700, presetsOpen: true)
        let open = try measure("shrink-0-open-h700")
        XCTAssertEqual(chipBlocks(open), 1, "開いた直後にチップが無い")
        for h in [519.0, 480.0, 400.0, 320.0] {
            resize(height: CGFloat(h))
            let m = try measure("shrink-h\(Int(h))")
            XCTAssertEqual(chipBlocks(m), 0, "h=\(Int(h)): チップが残っている")
            try assertStopInside(m, "縮めた h=\(Int(h))")
            assertScrollAreaKept(m, "縮めた h=\(Int(h))")
        }
        resize(height: 700)
        let back = try measure("shrink-back-h700")
        XCTAssertEqual(chipBlocks(back), 1, "窓を戻してもチップが戻らない")
        try assertStopInside(back, "戻した h=700")
    }

    /// アクセシビリティの一番大きい文字サイズ
    func testStopStaysInsideWithLargestAccessibilityText() throws {
        for h in [667.0, 520.0, 480.0, 320.0] {
            show(height: CGFloat(h), textSize: .accessibilityExtraExtraExtraLarge)
            let m = try measure("axxxl-h\(Int(h))")
            try assertStopInside(m, "大文字 h=\(Int(h))")
        }
        show(height: 520, presetsOpen: true, textSize: .accessibilityExtraExtraExtraLarge)
        let m = try measure("axxxl-chips-open-h520")
        try assertStopInside(m, "大文字 チップ開 h=520")
    }
}
