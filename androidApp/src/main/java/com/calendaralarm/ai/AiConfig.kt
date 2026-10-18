package com.calendaralarm.ai

import com.calendaralarm.BuildConfig
import com.calendaralarm.shared.ai.OpenRouterApi

/** AI 機能の接続先。キーはビルド時に渡され、利用者は入力しない。 */
object AiConfig {
    private const val MODEL = "openai/gpt-4.1-mini"

    val available: Boolean get() = BuildConfig.OPENROUTER_API_KEY.isNotBlank()

    /** キーを渡していないビルドでは null。呼び出し側は AI の入口ごと出さない。 */
    fun api(): OpenRouterApi? =
        BuildConfig.OPENROUTER_API_KEY.takeIf { it.isNotBlank() }
            ?.let { OpenRouterApi(apiKey = it, model = MODEL) }
}
