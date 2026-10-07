import Foundation

/// OpenRouter の chat/completions 呼び出し。
/// Kotlin shared の OpenRouterApi/MailEventExtractor と同じ仕様の Swift 版。
/// (KMP suspend を直接呼ぶより URLSession で書く方が iOS 側は素直 — WeatherService と同方針)
struct OpenRouterService {

    struct ExtractedMailEvent: Decodable {
        let title: String
        let start: String
        let end: String?
        let allDay: Bool
        let location: String?
        let description: String?
    }

    let apiKey: String
    let model: String

    /// メール本文から予定を1件抽出。見つからなければ nil。
    func extractEvent(text: String) async throws -> ExtractedMailEvent? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 20 else { return nil }
        let body = String(trimmed.prefix(6000))

        let nowIso = ISO8601DateFormatter().string(from: Date())
        let tz = TimeZone.current.identifier
        let system = """
        メール本文からカレンダー予定を1件だけ抽出し、JSONのみで返す。
        形式: {"title":string,"start":"YYYY-MM-DDTHH:mm"または"YYYY-MM-DD","end":同形式|null,"allDay":bool,"location":string|null,"description":string|null}
        「明日」「来週火曜」等の相対表現は現在時刻 \(nowIso) (タイムゾーン \(tz)) を基準に解決する。
        時刻の無い予定は allDay:true で日付のみ。予定が見つからない/確度が低い場合は null とだけ返す。
        """
        let raw = try await complete(system: system, user: body)
        guard let jsonText = Self.extractJson(raw),
              let data = jsonText.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(ExtractedMailEvent.self, from: data)
    }

    /// OpenRouter chat/completions を1回呼ぶ。応答テキスト (失敗時 throw)。
    func complete(system: String, user: String) async throws -> String {
        var req = URLRequest(url: URL(string: "https://openrouter.ai/api/v1/chat/completions")!)
        req.httpMethod = "POST"
        req.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let payload: [String: Any] = [
            "model": model,
            "temperature": 0.2,
            "messages": [
                ["role": "system", "content": system],
                ["role": "user", "content": user],
            ],
        ]
        req.httpBody = try JSONSerialization.data(withJSONObject: payload)
        let (data, resp) = try await URLSession.shared.data(for: req)
        // 401/429 等のエラー応答は body が choices 形式でない → ステータスで弾く
        if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw URLError(.badServerResponse)
        }
        struct Resp: Decodable {
            struct Choice: Decodable {
                struct Msg: Decodable { let content: String }
                let message: Msg
            }
            let choices: [Choice]
        }
        return try JSONDecoder().decode(Resp.self, from: data)
            .choices.first?.message.content ?? ""
    }

    /// ```json フェンスや前後の説明文を剥いて JSON 部分だけ取り出す。
    static func extractJson(_ text: String) -> String? {
        var t = text.replacingOccurrences(
            of: "```(?:json)?\\s*", with: "", options: .regularExpression)
        t = t.replacingOccurrences(of: "```", with: "")
        guard let s = t.firstIndex(of: "{") ?? t.firstIndex(of: "["),
              let e = t.lastIndex(of: "}") ?? t.lastIndex(of: "]"),
              e >= s else { return nil }
        return String(t[s...e])
    }
}
