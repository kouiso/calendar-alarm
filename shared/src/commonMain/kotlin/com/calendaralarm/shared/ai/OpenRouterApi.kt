package com.calendaralarm.shared.ai

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * OpenRouter の chat completions 最小クライアント。
 * APIキーはユーザー自身が設定画面から入力する (アプリに鍵を埋め込まない)。
 * 失敗時は null を返し、呼び出し側で「AI未設定/失敗」として扱う。
 */
class OpenRouterApi(
    private val apiKey: String,
    private val model: String = "openai/gpt-4.1-mini",
    private val http: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    },
) {

    /** 1往復の completions。本文だけ返す (失敗時 null)。 */
    suspend fun complete(system: String, user: String, temperature: Double = 0.2): String? {
        if (apiKey.isBlank()) return null
        return runCatching {
            val res: ChatResponse = http.post("https://openrouter.ai/api/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(
                    ChatRequest(
                        model = model,
                        messages = listOf(
                            Message(role = "system", content = system),
                            Message(role = "user", content = user),
                        ),
                        temperature = temperature,
                    ),
                )
            }.body()
            res.choices?.firstOrNull()?.message?.content?.trim()?.ifBlank { null }
        }.getOrNull()
    }

    /** ```json フェンスや前後の説明文を削って JSON 片だけを取り出す。 */
    fun extractJson(text: String): String? {
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(text)
        val candidate = fenced?.groupValues?.get(1)?.trim() ?: text.trim()
        val start = candidate.indexOfFirst { it == '{' || it == '[' }
        val end = candidate.indexOfLast { it == '}' || it == ']' }
        if (start < 0 || end <= start) return null
        return candidate.substring(start, end + 1)
    }

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val temperature: Double = 0.2,
    )

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class ChatResponse(val choices: List<Choice>? = null)

    @Serializable
    private data class Choice(val message: Message? = null)
}
