import XCTest
@testable import iosApp

/// 英語の翻訳の表から [key] の訳を引く。Text に日本語のリテラルを置いても、表に無ければ英語の端末で日本語のまま出る。
/// 引けなかった時は "__missing__" を返す
private func english(_ key: String) throws -> String {
    let appBundle = Bundle(for: Engine.self)
    let path = try XCTUnwrap(appBundle.path(forResource: "en", ofType: "lproj"), "英語の翻訳がアプリに入っていない")
    let en = try XCTUnwrap(Bundle(path: path))
    return en.localizedString(forKey: key, value: "__missing__", table: nil)
}

/// 鳴動画面の文言が英語でも出ること
final class RingingLocalizationTests: XCTestCase {
    func testSnoozePickerLabelsHaveEnglish() throws {
        XCTAssertEqual(try english("スヌーズ時間を選ぶ ▾"), "Choose snooze time ▾")
        XCTAssertEqual(try english("スヌーズ時間を選ぶ ▴"), "Choose snooze time ▴")
    }

    func testSlideToStopLabelHasEnglish() throws {
        XCTAssertEqual(try english("スライドして停止 ›"), "Slide to stop ›")
    }

    /// 数を入れる文言は、訳の表では %lld のまま持つ。差し込んだ後の形で確かめる
    func testSnoozeAndChipLabelsWithMinutesHaveEnglish() throws {
        XCTAssertEqual(String(format: try english("スヌーズ %lld分"), 10), "Snooze 10 min")
        XCTAssertEqual(String(format: try english("+%lld分"), 5), "+5 min")
    }
}

/// 設定画面のアラーム音の行 (#101) の文言が英語でも出ること。ここが日本語のままだと、英語の端末だけ音の設定が読めない
final class SettingsSoundLocalizationTests: XCTestCase {
    func testSoundRowHasEnglish() throws {
        XCTAssertEqual(String(format: try english("アラーム音: %@"), "My tone"), "Alarm sound: My tone")
        XCTAssertEqual(try english("iPhone の標準"), "iPhone default")
        XCTAssertEqual(try english("選ぶ"), "Choose")
        XCTAssertEqual(try english("標準に戻す"), "Reset to default")
    }

    func testAlarmKitLimitNoteHasEnglish() throws {
        let note = "音量・だんだん大きくする・バイブは、iPhone の「サウンドと触覚」の設定に従います。iPhone のアラーム機能では、アプリから変えられるのは音だけです。"
        let en = try english(note)
        XCTAssertNotEqual(en, "__missing__", "AlarmKit で変えられない項目の注記に英語の訳が無い")
        XCTAssertTrue(en.contains("Sounds & Haptics"), en)
    }

    func testSoundImportErrorsHaveEnglish() throws {
        XCTAssertEqual(String(format: try english("この音声は使えませんでした: %@"), "bad"), "This audio file could not be used: bad")
        XCTAssertEqual(String(format: try english("音声を開けませんでした: %@"), "denied"), "Could not open the audio file: denied")
    }
}
