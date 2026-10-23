import XCTest
@testable import iosApp

/// 設定画面で選ぶスヌーズ分数の候補 (#107)。鳴動画面はこの一覧が空なら開閉ボタンを出さないので、保存と読み直しで中身が変わらないことを見る
@MainActor
final class SnoozePresetsTests: XCTestCase {
    private var url: URL!
    private var savedSoundDefault: String?

    override func setUp() async throws {
        url = FileManager.default.temporaryDirectory.appendingPathComponent("store-\(UUID().uuidString).json")
        // Store の読み込みは既定のアラーム音を書き換えるので、他のテストへ漏らさない
        savedSoundDefault = AlarmSoundLibrary.currentDefault
    }

    override func tearDown() async throws {
        AlarmSoundLibrary.currentDefault = savedSoundDefault
        try? FileManager.default.removeItem(at: url)
    }

    /// 押した分数が無ければ足して小さい順に並べ、有れば外す
    func testToggledAddsSortedAndRemoves() {
        XCTAssertEqual(SnoozePresets.toggled([5, 10, 30], 20), [5, 10, 20, 30])
        XCTAssertEqual(SnoozePresets.toggled([5, 10, 30], 1), [1, 5, 10, 30])
        XCTAssertEqual(SnoozePresets.toggled([5, 10, 30], 90), [5, 10, 30, 90])
        XCTAssertEqual(SnoozePresets.toggled([5, 10, 30], 10), [5, 30])
        XCTAssertEqual(SnoozePresets.toggled([5], 5), [])
        XCTAssertEqual(SnoozePresets.toggled([], 15), [15])
    }

    /// 鳴動画面に並べるのは保存した一覧の先頭 6 件まで。空なら空で、固定の一覧には戻らない (#107)
    func testOnRingingScreenUsesSavedListCappedAtSix() {
        XCTAssertEqual(SnoozePresets.onRingingScreen([]), [])
        XCTAssertEqual(SnoozePresets.onRingingScreen([20, 45, 90]), [20, 45, 90], "保存した一覧でなく旧来の固定の一覧が使われた")
        XCTAssertEqual(SnoozePresets.onRingingScreen([5, 10, 15, 30, 45, 60]), [5, 10, 15, 30, 45, 60])
        XCTAssertEqual(SnoozePresets.onRingingScreen(SnoozePresets.choices), [1, 5, 10, 15, 20, 30], "9 件のうち先頭 6 件だけ")
        XCTAssertEqual(SnoozePresets.onRingingScreen(SnoozePresets.choices).count, SnoozePresets.maxOnRingingScreen)
    }

    /// 候補を持たない旧版の store.json は Android と同じ既定値で読む。他の設定はそのまま残る
    func testOldStoreWithoutKeyDecodesToDefaults() throws {
        try Data(#"{"defaultMinutesBefore":15,"defaultSnoozeMinutes":5}"#.utf8).write(to: url)

        let store = Store(fileURL: url)

        XCTAssertEqual(store.state.snoozePresets, [5, 10, 15, 30, 45, 60])
        XCTAssertEqual(store.state.defaultMinutesBefore, 15)
        XCTAssertEqual(store.state.defaultSnoozeMinutes, 5)
    }

    /// 全部外した空の一覧は、読み直しても既定値に戻らず空のまま
    func testEmptyListStaysEmptyAfterReload() {
        let store = Store(fileURL: url)
        for m in SnoozePresets.defaults { store.toggleSnoozePreset(m) }
        XCTAssertEqual(store.state.snoozePresets, [])

        store.reload()
        XCTAssertEqual(store.state.snoozePresets, [], "reload で空の一覧が既定値に戻った")
        XCTAssertEqual(Store(fileURL: url).state.snoozePresets, [], "起動し直すと空の一覧が既定値に戻った")
    }

    /// 設定画面で変えた一覧は、起動し直した Store でも同じ並びで読める
    func testToggledListSurvivesRelaunch() {
        let store = Store(fileURL: url)
        store.toggleSnoozePreset(45)
        store.toggleSnoozePreset(20)
        store.reload()

        XCTAssertEqual(store.state.snoozePresets, [5, 10, 15, 20, 30, 60])
        XCTAssertEqual(Store(fileURL: url).state.snoozePresets, [5, 10, 15, 20, 30, 60])
    }
}

/// 設定の「鳴動画面で選べるスヌーズ」が英語の端末でも読めること (文言を表に入れ忘れると日本語のまま出る)
final class SnoozePresetLocalizationTests: XCTestCase {
    func testSettingsLabelHasEnglish() throws {
        let path = try XCTUnwrap(Bundle(for: Engine.self).path(forResource: "en", ofType: "lproj"), "英語の翻訳がアプリに入っていない")
        let en = try XCTUnwrap(Bundle(path: path))
        XCTAssertEqual(en.localizedString(forKey: "鳴動画面で選べるスヌーズ", value: "__missing__", table: nil),
                       "Snooze choices on the ringing screen")
        XCTAssertEqual(String(format: en.localizedString(forKey: "%lld分", value: "__missing__", table: nil), 15), "15 min")
    }
}
