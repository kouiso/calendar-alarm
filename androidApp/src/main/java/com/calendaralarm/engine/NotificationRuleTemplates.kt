package com.calendaralarm.engine

import android.content.Context
import com.calendaralarm.R
import com.calendaralarm.shared.model.NotificationRuleSpec

/**
 * 通知アラームのクイックテンプレート (元アプリの12種に対応)。
 * ユーザーは選ぶだけで典型ルールが作れる。追加後は自由に編集できる。
 */
object NotificationRuleTemplates {

    /**
     * 名前は端末の言語で出す。キーワードは日英を両方持ったままにする:
     * 通知の本文は相手のアプリの言語で来るので、端末の言語に合わせて減らすと取りこぼす。
     */
    fun all(context: Context): List<NotificationRuleSpec> = listOf(
        NotificationRuleSpec(
            name = context.getString(R.string.rule_template_gmail),
            packageName = "com.google.android.gm",
            anyKeywords = listOf("重要", "important", "urgent", "緊急"),
        ),
        NotificationRuleSpec(
            name = context.getString(R.string.rule_template_outlook),
            packageName = "com.microsoft.office.outlook",
            anyKeywords = listOf("重要", "important", "high importance", "緊急"),
        ),
        NotificationRuleSpec(
            name = context.getString(R.string.rule_template_whatsapp),
            packageName = "com.whatsapp",
            requiredKeywords = listOf("メッセージ"),
            excludeKeywords = listOf("まとめ", "summary"),
        ),
        NotificationRuleSpec(
            name = context.getString(R.string.rule_template_telegram),
            packageName = "org.telegram.messenger",
            anyKeywords = listOf("緊急", "urgent", "重要"),
        ),
        NotificationRuleSpec(
            name = context.getString(R.string.rule_template_delivery),
            anyKeywords = listOf("配達員", "お届け", "届け物", "delivering", "out for delivery", "まもなくお届け"),
            excludeKeywords = listOf("広告", "クーポン"),
        ),
        NotificationRuleSpec(
            name = context.getString(R.string.rule_template_flight),
            anyKeywords = listOf("ゲート変更", "gate change", "搭乗口", "遅延", "delay", "搭乗"),
        ),
        NotificationRuleSpec(
            name = context.getString(R.string.rule_template_security),
            anyKeywords = listOf("確認コード", "認証コード", "OTP", "verification code", "ログイン確認", "不正なログイン", "security alert"),
        ),
        NotificationRuleSpec(
            name = context.getString(R.string.rule_template_x_posts),
            packageName = "com.twitter.android",
            anyKeywords = listOf("が投稿しました", "posted", "ポストしました"),
        ),
        NotificationRuleSpec(
            name = context.getString(R.string.rule_template_youtube_live),
            packageName = "com.google.android.youtube",
            anyKeywords = listOf("ライブ配信を開始", "is live", "ライブ", "premiere"),
        ),
        NotificationRuleSpec(
            name = context.getString(R.string.rule_template_twitch),
            packageName = "tv.twitch.android.app",
            anyKeywords = listOf("went live", "配信を開始", "is live"),
        ),
        NotificationRuleSpec(
            name = context.getString(R.string.rule_template_google_tasks),
            packageName = "com.google.android.apps.tasks",
            anyKeywords = listOf("期限", "due", "タスク"),
        ),
        NotificationRuleSpec(
            name = context.getString(R.string.rule_template_breaking_news),
            anyKeywords = listOf("速報", "breaking", "緊急速報"),
            excludeKeywords = listOf("広告", "PR"),
        ),
    )
}
