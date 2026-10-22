import XCTest
@testable import iosApp

/// 予定の日の予報を引く日付は、端末ではなく予報の地点のタイムゾーンで決める (#51 PR3)
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
        XCTAssertEqual(WeatherService.forecast(on: start, in: tokyo)?.date, "2026-10-24")
        let la = [forecast("2026-10-23", zone: "America/Los_Angeles"), forecast("2026-10-24", zone: "America/Los_Angeles")]
        XCTAssertEqual(WeatherService.forecast(on: start, in: la)?.date, "2026-10-23")
    }

    func testOutsideTheForecastRangeReturnsNil() throws {
        let start = try utc("2026-11-30T00:00:00Z")
        XCTAssertNil(WeatherService.forecast(on: start, in: [forecast("2026-10-23", zone: "Asia/Tokyo")]))
        XCTAssertNil(WeatherService.forecast(on: start, in: nil))
        XCTAssertNil(WeatherService.forecast(on: start, in: []))
    }

    /// 前の版で保存した予報にはタイムゾーンが無い。端末のタイムゾーンで引く (今までと同じ)
    func testMissingZoneFallsBackToTheDeviceZone() throws {
        let start = try utc("2026-10-23T12:00:00Z")
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        let key = f.string(from: start)
        XCTAssertEqual(WeatherService.forecast(on: start, in: [forecast(key, zone: nil)])?.date, key)
    }
}
