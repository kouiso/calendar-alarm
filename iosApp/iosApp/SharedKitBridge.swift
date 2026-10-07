import Foundation
import SharedKit

/// Kotlin shared の SharedBridge への薄い窓口。JSON 境界をここに隔離する。
enum SharedDomain {

    static func expand(_ req: ExpandRequest) -> ExpandResult? {
        guard let json = Bridge.encode(req) else { return nil }
        let out = SharedBridge.shared.expand(requestJson: json)
        return Bridge.decode(ExpandResult.self, out)
    }

    static func plan(_ req: PlanRequest) -> PlanResult? {
        guard let json = Bridge.encode(req) else { return nil }
        let out = SharedBridge.shared.plan(requestJson: json)
        return Bridge.decode(PlanResult.self, out)
    }

    /// 天気コードの表示名 (shared WeatherApi.Companion.describe と同一ロジック)。
    static func weatherDescription(_ code: Int) -> String {
        // WeatherApi.describe は companion メソッドだがインスタンス不要で呼ぶため Kotlin 側の
        // companion 経由になる。Swift からは WeatherApiCompanion 経由。
        WeatherApi.companion.describe(code: Int32(code))
    }
}
