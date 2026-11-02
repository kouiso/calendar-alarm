import XCTest
@testable import iosApp

final class InviteDefaultMigrationTests: XCTestCase {
    func testNewStoreDefaultsToAcceptedOnly() {
        XCTAssertEqual(Store.Persisted().inviteFilter, InviteFilter())
        XCTAssertFalse(Store.Persisted().inviteFilter.tentative)
        XCTAssertFalse(Store.Persisted().inviteFilter.needsAction)
    }

    func testOldStoreWithoutTheKeyKeepsItsPreviousDefault() throws {
        let state = try JSONDecoder().decode(Store.Persisted.self, from: Data("{}".utf8))
        XCTAssertEqual(state.inviteFilter, InviteFilter(accepted: true, tentative: true, needsAction: true, declined: false))
    }

    func testExplicitSavedChoicesSurviveDecodingAndReencoding() throws {
        let data = Data(#"{"inviteFilter":{"accepted":false,"tentative":true,"needsAction":false,"declined":true}}"#.utf8)
        let state = try JSONDecoder().decode(Store.Persisted.self, from: data)
        let saved = try JSONDecoder().decode(Store.Persisted.self, from: JSONEncoder().encode(state))
        XCTAssertEqual(saved.inviteFilter, InviteFilter(accepted: false, tentative: true, needsAction: false, declined: true))
    }
}
