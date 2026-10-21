package com.calendaralarm.shared

import com.calendaralarm.shared.logic.SlideToStopRule
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlideToStopRuleTest {
    @Test
    fun `8割まで引くと止まり、8割に届かなければ止まらない`() {
        assertFalse(SlideToStopRule.shouldStop(0.4f))
        assertFalse(SlideToStopRule.shouldStop(0.79f))
        assertTrue(SlideToStopRule.shouldStop(0.8f))
        assertTrue(SlideToStopRule.shouldStop(1f))
    }

    @Test
    fun `丸の直径に余白3つ分を足した所までなら掴める`() {
        // 両 OS の丸 64・余白 8 なら 88 まで
        assertTrue(SlideToStopRule.isGrab(startX = 0f, thumbSize = 64f, pad = 8f))
        assertTrue(SlideToStopRule.isGrab(startX = 88f, thumbSize = 64f, pad = 8f))
        assertFalse(SlideToStopRule.isGrab(startX = 88.5f, thumbSize = 64f, pad = 8f))
        assertFalse(SlideToStopRule.isGrab(startX = 300f, thumbSize = 64f, pad = 8f))
    }
}
