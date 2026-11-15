package com.calendaralarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.shared.logic.RingStatus
import com.calendaralarm.ui.ringStatusLine
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 鳴動の状態の1行は shared の種類と数から Android の文字リソースで引く (#291・#271) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RingStatusTextTest {

    private val res get() = ApplicationProvider.getApplicationContext<Context>().resources

    private fun line(missing: Int, missed: Int, hasNext: Boolean, alarmMuted: Boolean = false, allMuted: Boolean = false) =
        ringStatusLine(res, RingStatus.summarize(missing, missed, hasNext, alarmMuted = alarmMuted, allMuted = allMuted))

    @Test
    @Config(qualifiers = "ja")
    fun japaneseKeepsTheSameLines() {
        assertEquals("許可が2つ足りません。このままだと鳴らないことがあります", line(2, 1, true))
        assertEquals("すべてのアラームをミュート中です。通知とタイマーは届きます", line(0, 0, true, allMuted = true))
        assertEquals("アラームの音量が0です。鳴っても音が出ません", line(0, 0, true, alarmMuted = true))
        assertEquals("直近24時間に鳴らせなかったアラームが3件あります", line(0, 3, true))
        assertEquals("予約中のアラームはありません", line(0, 0, false))
        assertEquals("鳴る準備ができています", line(0, 0, true))
    }

    @Test
    @Config(qualifiers = "en")
    fun englishHasEveryLineAndPicksSingularForOne() {
        assertEquals("1 permission is missing. Alarms may not ring", line(1, 0, true))
        assertEquals("2 permissions are missing. Alarms may not ring", line(2, 0, true))
        assertEquals("All alarms are muted. Notifications and timers still arrive", line(0, 0, true, allMuted = true))
        assertEquals("Alarm volume is 0. Alarms will ring without sound", line(0, 0, true, alarmMuted = true))
        assertEquals("1 alarm could not ring in the last 24 hours", line(0, 1, true))
        assertEquals("3 alarms could not ring in the last 24 hours", line(0, 3, true))
        assertEquals("No alarms scheduled", line(0, 0, false))
        assertEquals("Ready to ring", line(0, 0, true))
    }
}
