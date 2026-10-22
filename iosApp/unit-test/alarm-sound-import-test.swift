import AVFoundation
import XCTest
@testable import iosApp

/// 音の取り込み (#101) を、本物の AVFoundation でシミュレータ上に書き出して確かめる。音は鳴らさない
final class AlarmSoundImportTests: XCTestCase {
    private var imported: [String] = []

    override func tearDown() {
        imported.forEach { AlarmSoundLibrary.remove($0) }
    }

    /// [seconds] 秒の無音の WAV を一時フォルダに作る
    private func makeWav(seconds: Double, rate: Double = 44_100) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("src-\(UUID().uuidString).wav")
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1))
        let file = try AVAudioFile(forWriting: url, settings: format.settings)
        let frames = AVAudioFrameCount(rate * seconds)
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames))
        buffer.frameLength = frames
        try file.write(from: buffer)
        return url
    }

    private func duration(_ name: String) throws -> Double {
        let file = try AVAudioFile(forReading: AlarmSoundLibrary.soundsDirectory.appendingPathComponent(name))
        return Double(file.length) / file.processingFormat.sampleRate
    }

    /// 30秒を超える音は 29.5 秒で切って Library/Sounds に caf で置く (通知音は 30 秒未満でないと既定音になる)
    func testLongSoundIsTrimmedUnderThirtySeconds() throws {
        let name = try AlarmSoundLibrary.importSound(from: makeWav(seconds: 40))
        imported.append(name)
        XCTAssertTrue(name.hasPrefix(AlarmSoundLibrary.prefix))
        XCTAssertTrue(name.hasSuffix(".caf"))
        XCTAssertTrue(AlarmSoundLibrary.exists(name))
        XCTAssertEqual(try duration(name), AlarmSoundLibrary.maxSeconds, accuracy: 0.05)
        XCTAssertLessThan(try duration(name), 30)
    }

    /// 短い音はそのままの長さで取り込み、予約に使う名前として引ける
    func testShortSoundKeepsLengthAndIsUsedForReservations() throws {
        let name = try AlarmSoundLibrary.importSound(from: makeWav(seconds: 3))
        imported.append(name)
        XCTAssertEqual(try duration(name), 3, accuracy: 0.05)
        let saved = AlarmSoundLibrary.currentDefault
        defer { AlarmSoundLibrary.currentDefault = saved }
        AlarmSoundLibrary.currentDefault = name
        let inst = AlarmInstanceDTO(id: "t", triggerAtMillis: 0, title: "t", kind: "TIMER")
        XCTAssertEqual(AlarmSoundLibrary.soundName(for: inst), name)
    }

    /// 音声でないファイルは取り込まず、書きかけも残さない
    func testNonAudioFileIsRejectedWithoutLeftovers() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("not-audio-\(UUID().uuidString).wav")
        try Data("not audio".utf8).write(to: url)
        let before = Set((try? FileManager.default.contentsOfDirectory(atPath: AlarmSoundLibrary.soundsDirectory.path)) ?? [])
        XCTAssertThrowsError(try AlarmSoundLibrary.importSound(from: url))
        let after = Set((try? FileManager.default.contentsOfDirectory(atPath: AlarmSoundLibrary.soundsDirectory.path)) ?? [])
        XCTAssertEqual(before, after)
    }
}
