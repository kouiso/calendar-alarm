import XCTest
@testable import iosApp

/// 鳴動画面の文言が英語でも出ること。Text に日本語のリテラルを置いても、翻訳の表に無ければ英語の端末で日本語のまま出る
final class RingingLocalizationTests: XCTestCase {
    private func english(_ key: String) throws -> String {
        let appBundle = Bundle(for: Engine.self)
        let path = try XCTUnwrap(appBundle.path(forResource: "en", ofType: "lproj"), "英語の翻訳がアプリに入っていない")
        let en = try XCTUnwrap(Bundle(path: path))
        return en.localizedString(forKey: key, value: "__missing__", table: nil)
    }

    func testSnoozePickerLabelsHaveEnglish() throws {
        XCTAssertEqual(try english("スヌーズ時間を選ぶ ▾"), "Choose snooze time ▾")
        XCTAssertEqual(try english("スヌーズ時間を選ぶ ▴"), "Choose snooze time ▴")
    }

    func testSlideToStopLabelHasEnglish() throws {
        XCTAssertEqual(try english("スライドして停止 ›"), "Slide to stop ›")
    }
}
