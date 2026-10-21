package com.calendaralarm.shared

import com.calendaralarm.shared.logic.SettingsPreview
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsPreviewTest {
    @Test
    fun `何分前を選ぶと例の予定に対して鳴る時刻を返す`() {
        assertEquals("9:00", SettingsPreview.ringClock(SettingsPreview.SAMPLE_EVENT_MINUTE, 0))
        assertEquals("8:50", SettingsPreview.ringClock(SettingsPreview.SAMPLE_EVENT_MINUTE, 10))
        assertEquals("8:00", SettingsPreview.ringClock(SettingsPreview.SAMPLE_EVENT_MINUTE, 60))
    }

    @Test
    fun `日をまたぐ時刻も24時間表記に収める`() {
        assertEquals("23:45", SettingsPreview.ringClock(0, 15))
        assertEquals("0:10", SettingsPreview.snoozeClock(23 * 60 + 55, 15))
    }

    @Test
    fun `スヌーズを選ぶと例のアラームの再鳴動時刻を返す`() {
        assertEquals("6:40", SettingsPreview.snoozeClock(SettingsPreview.SAMPLE_ALARM_MINUTE, 10))
    }

    @Test
    fun `鳴る時刻のカードはアプリと Google の時刻を早い順に並べる`() {
        assertEquals(
            "8:30（アプリ）・8:55（Google）",
            SettingsPreview.ringTimesLabel(SettingsPreview.SAMPLE_EVENT_MINUTE, 30, SettingsPreview.SAMPLE_GOOGLE_REMINDER_MINUTES),
        )
        assertEquals("8:55（Google）・9:00（アプリ）", SettingsPreview.ringTimesLabel(9 * 60, 0, 5))
    }

    @Test
    fun `同じ時刻は1つにまとめる`() {
        assertEquals("8:55（アプリ・Google）", SettingsPreview.ringTimesLabel(9 * 60, 5, 5))
    }

    @Test
    fun `カレンダーの通知を使わない時はアプリの時刻だけ`() {
        assertEquals("8:50（アプリ）", SettingsPreview.ringTimesLabel(9 * 60, 10, null))
    }
}
