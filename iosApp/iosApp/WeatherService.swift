import Foundation

/// Open-Meteo 天気。shared の WeatherApi と同じエンドポイント・同じ WMO コード解釈。
/// (KMP suspend 関数を直接呼ぶより URLSession で書く方が iOS 側は素直)
final class WeatherService {

    struct Forecast: Codable {
        var date: String        // YYYY-MM-DD
        var weatherCode: Int
        var tempMax: Double
        var tempMin: Double
        var precipitationProbability: Int?
    }

    func forecast(for location: String, days: Int = 3) async -> [Forecast]? {
        guard !location.isEmpty,
              let point = await geocode(location) else { return nil }
        var comps = URLComponents(string: "https://api.open-meteo.com/v1/forecast")!
        comps.queryItems = [
            .init(name: "latitude", value: "\(point.lat)"),
            .init(name: "longitude", value: "\(point.lon)"),
            .init(name: "daily", value: "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"),
            .init(name: "timezone", value: "auto"),
            .init(name: "forecast_days", value: "\(days)"),
        ]
        guard let url = comps.url,
              let (data, _) = try? await URLSession.shared.data(from: url),
              let res = try? JSONDecoder().decode(ForecastResponse.self, from: data),
              let daily = res.daily else { return nil }
        return daily.time.indices.map { i in
            Forecast(
                date: daily.time[i],
                weatherCode: daily.weatherCode[safe: i] ?? -1,
                tempMax: daily.tempMax[safe: i] ?? .nan,
                tempMin: daily.tempMin[safe: i] ?? .nan,
                precipitationProbability: daily.pop?[safe: i] ?? nil
            )
        }
    }

    private func geocode(_ query: String) async -> (lat: Double, lon: Double)? {
        var comps = URLComponents(string: "https://geocoding-api.open-meteo.com/v1/search")!
        comps.queryItems = [
            .init(name: "name", value: query),
            .init(name: "count", value: "1"),
            .init(name: "language", value: "ja"),
            .init(name: "format", value: "json"),
        ]
        guard let url = comps.url,
              let (data, _) = try? await URLSession.shared.data(from: url),
              let res = try? JSONDecoder().decode(GeoResponse.self, from: data),
              let hit = res.results?.first else { return nil }
        return (hit.latitude, hit.longitude)
    }

    /// WMO コード → アイコン (SF Symbols 名)
    static func icon(_ code: Int) -> String {
        switch code {
        case 0, 1: return "sun.max.fill"
        case 2: return "cloud.sun.fill"
        case 3: return "cloud.fill"
        case 45, 48: return "cloud.fog.fill"
        case 51, 53, 55, 56, 57: return "cloud.drizzle.fill"
        case 61, 63, 65, 66, 67, 80, 81, 82: return "cloud.rain.fill"
        case 71, 73, 75, 77, 85, 86: return "cloud.snow.fill"
        case 95, 96, 99: return "cloud.bolt.rain.fill"
        default: return "questionmark.circle"
        }
    }

    private struct GeoResponse: Codable {
        struct Hit: Codable { var latitude: Double; var longitude: Double }
        var results: [Hit]?
    }

    private struct ForecastResponse: Codable {
        struct Daily: Codable {
            var time: [String]
            var weatherCode: [Int] { weather_code }
            var tempMax: [Double] { temperature_2m_max }
            var tempMin: [Double] { temperature_2m_min }
            var pop: [Int?]? { precipitation_probability_max }
            private var weather_code: [Int] = []
            private var temperature_2m_max: [Double] = []
            private var temperature_2m_min: [Double] = []
            private var precipitation_probability_max: [Int?]? = nil
            private enum CodingKeys: String, CodingKey {
                case time, weather_code, temperature_2m_max, temperature_2m_min, precipitation_probability_max
            }
        }
        var daily: Daily?
    }
}

private extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}
