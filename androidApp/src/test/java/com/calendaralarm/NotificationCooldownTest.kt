package com.calendaralarm

import com.calendaralarm.engine.CooldownEntry
import com.calendaralarm.engine.shouldSkipCooldown
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通知アラームの 60 秒の間隔は、内容の印が同じ時だけ落とす (#384) */
class NotificationCooldownTest {

    @Test
    fun `間隔の中で印が同じなら落とす`() {
        assertTrue(shouldSkipCooldown(CooldownEntry(1_000, "A"), 30_000, "A"))
    }

    @Test
    fun `間隔の中でも印が違えば通す`() {
        assertFalse(shouldSkipCooldown(CooldownEntry(1_000, "A"), 30_000, "B"))
    }

    @Test
    fun `間隔の外なら印が同じでも通す`() {
        assertFalse(shouldSkipCooldown(CooldownEntry(1_000, "A"), 61_001, "A"))
    }

    @Test
    fun `前の行が無ければ通す`() {
        assertFalse(shouldSkipCooldown(null, 30_000, "A"))
    }
}
