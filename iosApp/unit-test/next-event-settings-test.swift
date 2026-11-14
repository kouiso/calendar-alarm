import XCTest
@testable import iosApp

final class NextEventSettingsTests: XCTestCase {
    func testReminderOffsetsAndDisplayChoiceSurviveTogether() throws {
        let state = try JSONDecoder().decode(
            Store.Persisted.self,
            from: Data("{\"extraReminderOffsets\":[20],\"showNextEvent\":false}".utf8)
        )
        XCTAssertEqual(state.extraReminderOffsets, [20])
        XCTAssertFalse(state.showNextEvent)
        let restored = try JSONDecoder().decode(Store.Persisted.self, from: JSONEncoder().encode(state))
        XCTAssertEqual(restored.extraReminderOffsets, [20])
        XCTAssertFalse(restored.showNextEvent)
    }

    func testOldStoreShowsNextAlarmAndSavedChoiceSurvivesEncoding() throws {
        var state = try JSONDecoder().decode(Store.Persisted.self, from: Data("{}".utf8))
        XCTAssertTrue(state.showNextEvent)
        state.showNextEvent = false
        let saved = try JSONEncoder().encode(state)
        XCTAssertFalse(try JSONDecoder().decode(Store.Persisted.self, from: saved).showNextEvent)
        state.showNextEvent = true
        XCTAssertTrue(try JSONDecoder().decode(Store.Persisted.self, from: JSONEncoder().encode(state)).showNextEvent)
    }
}
