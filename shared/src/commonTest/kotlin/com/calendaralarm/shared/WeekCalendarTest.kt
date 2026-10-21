package com.calendaralarm.shared

import com.calendaralarm.shared.logic.WeekCalendar
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class WeekCalendarTest {
    @Test
    fun `週の開始曜日を月曜にすると月表示は月曜から並ぶ`() {
        // 2026-10-01 は木曜。月曜始まりなら左上は 9/28(月)
        assertEquals(LocalDate(2026, 9, 28), WeekCalendar.monthGridStart(LocalDate(2026, 10, 15), 1))
        assertEquals(DayOfWeek.MONDAY, WeekCalendar.weekdayOrder(1).first())
        assertEquals(DayOfWeek.SUNDAY, WeekCalendar.weekdayOrder(1).last())
    }

    @Test
    fun `既定の日曜始まりはこれまでの月表示と同じ左上になる`() {
        assertEquals(LocalDate(2026, 9, 27), WeekCalendar.monthGridStart(LocalDate(2026, 10, 1), WeekCalendar.DEFAULT_FIRST_WEEKDAY))
        assertEquals(
            listOf(
                DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
            ),
            WeekCalendar.weekdayOrder(7),
        )
    }

    @Test
    fun `1日が開始曜日と同じ月は前月の日を出さない`() {
        // 2026-06-01 は月曜
        assertEquals(LocalDate(2026, 6, 1), WeekCalendar.monthGridStart(LocalDate(2026, 6, 20), 1))
    }

    @Test
    fun `壊れた保存値は既定の日曜始まりに戻す`() {
        assertEquals(7, WeekCalendar.normalize(0))
        assertEquals(7, WeekCalendar.normalize(8))
        assertEquals(LocalDate(2026, 9, 27), WeekCalendar.monthGridStart(LocalDate(2026, 10, 1), -3))
    }

    @Test
    fun `週番号はISO8601で年またぎは木曜の年で数える`() {
        assertEquals(1, WeekCalendar.isoWeekNumber(LocalDate(2026, 1, 1))) // 木曜
        assertEquals(53, WeekCalendar.isoWeekNumber(LocalDate(2027, 1, 1))) // 金曜は前年の53週
        assertEquals(1, WeekCalendar.isoWeekNumber(LocalDate(2024, 12, 30))) // 月曜は翌年の1週
        assertEquals(43, WeekCalendar.isoWeekNumber(LocalDate(2026, 10, 21)))
    }
}
