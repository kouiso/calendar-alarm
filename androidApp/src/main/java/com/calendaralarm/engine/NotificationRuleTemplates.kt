package com.calendaralarm.engine

import com.calendaralarm.shared.model.NotificationRuleSpec

/**
 * 通知アラームのクイックテンプレート (元アプリの12種に対応)。
 * ユーザーは選ぶだけで典型ルールが作れる。追加後は自由に編集できる。
 */
object NotificationRuleTemplates {

    val ALL: List<NotificationRuleSpec> = listOf(
        NotificationRuleSpec(
            name = "Gmail 重要なメール",
            packageName = "com.google.android.gm",
            anyKeywords = listOf("重要", "important", "urgent", "緊急"),
        ),
        NotificationRuleSpec(
            name = "Outlook 重要なメール",
            packageName = "com.microsoft.office.outlook",
            anyKeywords = listOf("重要", "important", "high importance", "緊急"),
        ),
        NotificationRuleSpec(
            name = "WhatsApp 優先連絡先",
            packageName = "com.whatsapp",
            requiredKeywords = listOf("メッセージ"),
            excludeKeywords = listOf("まとめ", "summary"),
        ),
        NotificationRuleSpec(
            name = "Telegram 緊急連絡",
            packageName = "org.telegram.messenger",
            anyKeywords = listOf("緊急", "urgent", "重要"),
        ),
        NotificationRuleSpec(
            name = "配達通知",
            anyKeywords = listOf("配達員", "お届け", "届け物", "delivering", "out for delivery", "まもなくお届け"),
            excludeKeywords = listOf("広告", "クーポン"),
        ),
        NotificationRuleSpec(
            name = "フライト・ゲート変更",
            anyKeywords = listOf("ゲート変更", "gate change", "搭乗口", "遅延", "delay", "搭乗"),
        ),
        NotificationRuleSpec(
            name = "セキュリティ・確認コード",
            anyKeywords = listOf("確認コード", "認証コード", "OTP", "verification code", "ログイン確認", "不正なログイン", "security alert"),
        ),
        NotificationRuleSpec(
            name = "X (Twitter) 投稿",
            packageName = "com.twitter.android",
            anyKeywords = listOf("が投稿しました", "posted", "ポストしました"),
        ),
        NotificationRuleSpec(
            name = "YouTube ライブ開始",
            packageName = "com.google.android.youtube",
            anyKeywords = listOf("ライブ配信を開始", "is live", "ライブ", "premiere"),
        ),
        NotificationRuleSpec(
            name = "Twitch 配信開始",
            packageName = "tv.twitch.android.app",
            anyKeywords = listOf("went live", "配信を開始", "is live"),
        ),
        NotificationRuleSpec(
            name = "Google タスク",
            packageName = "com.google.android.apps.tasks",
            anyKeywords = listOf("期限", "due", "タスク"),
        ),
        NotificationRuleSpec(
            name = "ニュース速報",
            anyKeywords = listOf("速報", "breaking", "緊急速報"),
            excludeKeywords = listOf("広告", "PR"),
        ),
    )
}
