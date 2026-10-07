package com.calendaralarm.shared

import com.calendaralarm.shared.model.NotificationRuleSpec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 通知ルールのテキスト評価。除外>必須>任意の優先順位を固定する。 */
class NotificationRuleSpecTest {

    @Test
    fun `除外キーワードは必須より優先される`() {
        val rule = NotificationRuleSpec(
            name = "配達",
            requiredKeywords = listOf("配達"),
            excludeKeywords = listOf("広告"),
        )
        assertFalse(rule.matchesText("配達のお知らせ [広告]"))
    }

    @Test
    fun `必須キーワードは全部必要`() {
        val rule = NotificationRuleSpec(
            name = "VIP",
            requiredKeywords = listOf("山田", "緊急"),
        )
        assertFalse(rule.matchesText("山田さんから連絡"))
        assertTrue(rule.matchesText("緊急: 山田さんから連絡"))
    }

    @Test
    fun `任意キーワードは一つで発動`() {
        val rule = NotificationRuleSpec(
            name = "配達系",
            anyKeywords = listOf("お届け", "配達員"),
        )
        assertTrue(rule.matchesText("配達員が近づいています"))
        assertFalse(rule.matchesText("ニュースレター"))
    }

    @Test
    fun `キーワード条件なしは常に発動`() {
        val rule = NotificationRuleSpec(name = "全通", packageName = "com.slack")
        assertTrue(rule.matchesText("何でも"))
    }

    @Test
    fun `大文字小文字を区別しない`() {
        val rule = NotificationRuleSpec(name = "x", requiredKeywords = listOf("vip"))
        assertTrue(rule.matchesText("From VIP desk"))
    }
}
