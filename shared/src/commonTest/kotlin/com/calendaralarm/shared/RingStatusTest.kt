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
    fun `許可がそろっていてもアラームの音量が0なら準備OKとは出さない`() {
        val s = RingStatus.summarize(0, 0, true, alarmMuted = true)
        assertEquals(RingStatus.Kind.ALARM_MUTED, s.kind)
        assertEquals("アラームの音量が0です。鳴っても音が出ません", s.line)
        // 許可が足りない方を先に出す (許可が無いと音量に関係なく鳴らない)
        assertEquals(RingStatus.Kind.MISSING_PERMISSION, RingStatus.summarize(1, 0, true, alarmMuted = true).kind)
        // 音量0は見逃しより先に出す (直さない限りこの先も音が出ない)
        assertEquals(RingStatus.Kind.ALARM_MUTED, RingStatus.summarize(0, 2, true, alarmMuted = true).kind)
    }

    @Test
    fun `予約が無い時と準備OKの時`() {
        assertEquals(RingStatus.Kind.NO_ALARM, RingStatus.summarize(0, 0, false).kind)
        val ready = RingStatus.summarize(0, 0, true)
        assertEquals(RingStatus.Kind.READY, ready.kind)
        assertFalse(ready.showPermissionBanner || ready.showMissedBanner)
    }
}
