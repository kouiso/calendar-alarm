import XCTest
@testable import iosApp

/// 予定の日の予報を引く日付は、時刻のある予定は予報の地点のタイムゾーン、終日の予定は端末のタイムゾーンで決める (#51 PR3)
final class WeatherForecastLookupTests: XCTestCase {
    private func forecast(_ date: String, zone: String?) -> WeatherService.Forecast {
        WeatherService.Forecast(date: date, weatherCode: 0, tempMax: 20, tempMin: 10,
                                precipitationProbability: nil, timeZoneId: zone)
    }

    private func utc(_ iso: String) throws -> Date {
        try XCTUnwrap(ISO8601DateFormatter().date(from: iso))
    }

    /// 2026-10-23 20:00 UTC は東京では 10/24 05:00、ロサンゼルスでは 10/23 13:00
    func testUsesTheForecastLocationDayNotTheDeviceDay() throws {
        let start = try utc("2026-10-23T20:00:00Z")
        let tokyo = [forecast("2026-10-23", zone: "Asia/Tokyo"), forecast("2026-10-24", zone: "Asia/Tokyo")]
        XCTAssertEqual(WeatherService.forecast(on: start, allDay: false, in: tokyo)?.date, "2026-10-24")
        let la = [forecast("2026-10-23", zone: "America/Los_Angeles"), forecast("2026-10-24", zone: "America/Los_Angeles")]
        XCTAssertEqual(WeatherService.forecast(on: start, allDay: false, in: la)?.date, "2026-10-23")
    }

    /// 終日の予定の開始は端末のタイムゾーンの0時 (EventKit)。端末が東京・地点がロサンゼルスでも、
    /// 地点のタイムゾーンに直して前の日 (10/23) の予報を出さない
    func testAllDayUsesTheDeviceDayEvenWhenTheForecastZoneDiffers() throws {
        let tokyo = try XCTUnwrap(TimeZone(identifier: "Asia/Tokyo"))
        let allDayStart = try utc("2026-10-23T15:00:00Z")   // 東京の 2026-10-24 00:00
        let la = [forecast("2026-10-23", zone: "America/Los_Angeles"), forecast("2026-10-24", zone: "America/Los_Angeles"),
                  forecast("2026-10-25", zone: "America/Los_Angeles")]
        XCTAssertEqual(WeatherService.forecast(on: allDayStart, allDay: true, in: la, deviceZone: tokyo)?.date, "2026-10-24")
        // 時刻のある予定は今までどおり地点の日付 (同じ瞬間はロサンゼルスでは 10/23 08:00)
        XCTAssertEqual(WeatherService.forecast(on: allDayStart, allDay: false, in: la, deviceZone: tokyo)?.date, "2026-10-23")
    }

    func testOutsideTheForecastRangeReturnsNil() throws {
        let start = try utc("2026-11-30T00:00:00Z")
        XCTAssertNil(WeatherService.forecast(on: start, allDay: false, in: [forecast("2026-10-23", zone: "Asia/Tokyo")]))
        XCTAssertNil(WeatherService.forecast(on: start, allDay: false, in: nil))
        XCTAssertNil(WeatherService.forecast(on: start, allDay: false, in: []))
    }

    /// 前の版で保存した予報にはタイムゾーンが無い。端末のタイムゾーンで引く (今までと同じ)
    func testMissingZoneFallsBackToTheDeviceZone() throws {
        let start = try utc("2026-10-23T12:00:00Z")
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        let key = f.string(from: start)
        XCTAssertEqual(WeatherService.forecast(on: start, allDay: false, in: [forecast(key, zone: nil)])?.date, key)
    }
}
