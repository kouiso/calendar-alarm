package com.calendaralarm.shared.ai

import com.calendaralarm.shared.model.ExtractedEvent
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json

/**
 * メール/印刷物の本文 → 予定情報の抽出。
 * 元アプリの「メールを印刷して予定化」に相当。
 * 現在日時とタイムゾーンをプロンプトに埋め込み、「来週火曜」系を日付に解決させる。
 */
class MailEventExtractor(
    private val api: OpenRouterApi,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /**
     * 本文から予定を抽出する。抽出不能/AI未設定なら null。
     * nowIso は "2026-10-04T15:30:00+09:00" のような現在日時。
     */
    suspend fun extract(text: String, nowIso: String, timeZone: TimeZone): ExtractedEvent? {
        val trimmed = text.trim().take(6000)
        if (trimmed.length < 20) return null
        val system = """
            あなたはメールや文書からカレンダー予定を抽出するアシスタント。
            本文中の約束・イベント・便・予約・会議を1件だけ選び、次のJSONのみを返す:
            {"title":"…","start":"YYYY-MM-DDTHH:mm","end":"YYYY-MM-DDTHH:mm","allDay":false,"location":"…","description":"…"}
            - 時刻は24時間制、日付は現在日時からの相対日も解決する
            - 終了が不明なら開始の1時間後、日付のみの予定は allDay=true (start=YYYY-MM-DD, end=null)
            - 予定が1件も見つからなければ null だけ返す
            - 説明文・マークダウン・コードフェンスは一切不要
        """.trimIndent()
        val user = "現在日時: $nowIso (タイムゾーン: ${timeZone.id})\n\n本文:\n$trimmed"
        val content = api.complete(system, user) ?: return null
        if (content.trim() == "null") return null
        val jsonText = api.extractJson(content) ?: return null
        return runCatching { json.decodeFromString<ExtractedEvent>(jsonText) }.getOrNull()
    }
}
