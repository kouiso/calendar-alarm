package com.calendaralarm.shared.ai

import com.calendaralarm.shared.model.NotificationRuleSpec
import kotlinx.serialization.json.Json

/**
 * 自然言語 → 通知アラームルールの生成 (元アプリの「AIでルール生成」相当)。
 * 例: 「Gmailで上司からメールが来たら鳴らして」→ packageName=com.google.android.gm,
 *     requiredKeywords=["上司"] 等。生成はユーザー確認前の下書きとして使う。
 */
class NotificationRuleGenerator(private val api: OpenRouterApi) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 日本語の要望文からルールを1件生成。生成不能時は null。 */
    suspend fun generate(request: String): NotificationRuleSpec? {
        val text = request.trim()
        if (text.length < 3) return null
        val raw = api.complete(
            system = SYSTEM_PROMPT,
            user = text.take(1000),
        ) ?: return null
        val body = api.extractJson(raw) ?: return null
        return runCatching { json.decodeFromString<NotificationRuleSpec>(body) }
            .getOrNull()
            ?.takeIf { it.name.isNotBlank() }
            // AI生成値の検証: 範囲外の曜日/分数は死にルールになるので除去
            ?.let { r ->
                r.copy(
                    daysOfWeek = r.daysOfWeek.filter { it in 1..7 },
                    startMinuteOfDay = r.startMinuteOfDay?.takeIf { it in 0..1439 },
                    endMinuteOfDay = r.endMinuteOfDay?.takeIf { it in 0..1439 },
                )
            }
    }

    private companion object {
        // アプリのパッケージ名はモデルの知識に頼ると誤りが多いため、
        // 主要アプリだけは確定値をプロンプトに埋め込む。
        val SYSTEM_PROMPT = """
ユーザーの要望を、他アプリの通知をアラーム化するルールのJSONに変換する。
形式: {"name":string,"packageName":string|null,"requiredKeywords":[string],"anyKeywords":[string],"excludeKeywords":[string],"daysOfWeek":[int],"startMinuteOfDay":int|null,"endMinuteOfDay":int|null}
- name: ルールの短い日本語名
- packageName: 対象アプリを特定できる場合のみ。主要アプリの確定値:
  Gmail=com.google.android.gm, Outlook=com.microsoft.office.outlook,
  WhatsApp=com.whatsapp, Telegram=org.telegram.messenger,
  LINE=jp.naver.line.android, X(Twitter)=com.twitter.android,
  YouTube=com.google.android.youtube, Twitch=tv.twitch.android.app,
  Slack=com.slack, 楽天市場=jp.co.rakuten.ichiba.android, Amazon=com.amazon.mShop.android.shopping
  上記以外・不明な場合は null (全アプリ監視)
- requiredKeywords: 通知タイトル+本文に全て含む必要がある語
- anyKeywords: いずれか含む語 (条件なしなら空配列)
- excludeKeywords: 広告等を弾く語
- daysOfWeek: 1=月..7=日。毎日なら空配列
- startMinuteOfDay/endMinuteOfDay: 時間帯限定がある場合のみ (分)。終日は両方null
- 「VIP/重要/緊急」等の曖昧語は、文脈から妥当なキーワードに分解する
- JSONのみ返す。説明は書かない
""".trimIndent()
    }
}
