import XCTest

// 使い捨ての UI テスト (PR には入れない)。通常起動のアプリを XCUIApplication で起動し、設定画面まで進む
final class LaunchProbe: XCTestCase {
    func testRealAppLaunchesToSettings() throws {
        let app = XCUIApplication()
        app.launch()
        let later = app.buttons["あとで"]
        if later.waitForExistence(timeout: 20) { later.tap() }
        let settings = app.buttons["設定"]
        XCTAssertTrue(settings.waitForExistence(timeout: 20), "tab bar missing: the real app UI did not start")
        settings.tap()
        let row = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'アラーム音'")).firstMatch
        var tries = 0
        while !(row.exists && row.isHittable) && tries < 8 { app.swipeUp(); tries += 1 }
        XCTAssertTrue(row.exists, "settings screen missing the alarm sound row")
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try png.write(to: URL(fileURLWithPath: ProcessInfo.processInfo.environment["PROBE_OUT"] ?? "/tmp/probe.png"))
    }
}
