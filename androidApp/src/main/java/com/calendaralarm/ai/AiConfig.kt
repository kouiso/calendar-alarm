package com.calendaralarm.ai

import com.calendaralarm.BuildConfig
import com.calendaralarm.shared.ai.OpenRouterApi

/** AI 機能の接続先。キーはビルド時に渡され、利用者は入力しない。配布ビルドには渡さない。 */
object AiConfig {
    private const val MODEL = "openai/gpt-4.1-mini"

    val available: Boolean get() = apiFor(BuildConfig.OPENROUTER_API_KEY) != null

    /** キーを渡していないビルドでは null。呼び出し側は AI の入口ごと出さない。 */
    fun api(): OpenRouterApi? = apiFor(BuildConfig.OPENROUTER_API_KEY)

    // ビルドに渡したキーと切り離して試験できるよう、キーから接続先を作る部分を分けている
    internal fun apiFor(key: String): OpenRouterApi? =
        key.takeIf { it.isNotBlank() }?.let { OpenRouterApi(apiKey = it, model = MODEL) }
}
