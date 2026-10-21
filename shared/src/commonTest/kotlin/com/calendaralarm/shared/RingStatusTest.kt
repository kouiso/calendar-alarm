package com.calendaralarm.shared

import com.calendaralarm.shared.logic.RingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RingStatusTest {
    @Test
    fun `許可が足りない時はそれを最優先で1行に出し、見逃しのバナーも同時に出す`() {
        val s = RingStatus.summarize(missingPermissions = 2, recentMissed = 1, hasNextAlarm = true)
        assertEquals(RingStatus.Kind.MISSING_PERMISSION, s.kind)
        assertEquals("許可が2つ足りません。このままだと鳴らないことがあります", s.line)
        assertTrue(s.showPermissionBanner)
        assertTrue(s.showMissedBanner)
    }

    @Test
    fun `許可がそろっていて見逃しがある時は見逃しを出す`() {
        val s = RingStatus.summarize(0, 3, true)
        assertEquals(RingStatus.Kind.MISSED_RECENTLY, s.kind)
        assertEquals("直近24時間に鳴らせなかったアラームが3件あります", s.line)
        assertFalse(s.showPermissionBanner)
    }

    @Test
    fun `予約が無い時と準備OKの時`() {
        assertEquals(RingStatus.Kind.NO_ALARM, RingStatus.summarize(0, 0, false).kind)
        val ready = RingStatus.summarize(0, 0, true)
        assertEquals(RingStatus.Kind.READY, ready.kind)
        assertFalse(ready.showPermissionBanner || ready.showMissedBanner)
    }

    @Test
    fun `見逃しは直近24時間の指定した種類だけ数える`() {
        val now = 100L * 60 * 60 * 1000
        val day = RingStatus.MISSED_WINDOW_MILLIS
        val entries = listOf(
            now - 1_000 to "MISS",
            now - day to "MISS",
            now - day - 1 to "MISS",
            now - 10 to "FIRE",
            now + 10 to "MISS",
        )
        assertEquals(2, RingStatus.countRecentMissed(entries, now, setOf("MISS")))
        assertEquals(0, RingStatus.countRecentMissed(entries, now, setOf("MISSED")))
    }
}
