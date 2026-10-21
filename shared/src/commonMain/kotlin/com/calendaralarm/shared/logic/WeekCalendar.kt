package com.calendaralarm.shared.logic

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * 週番号と週の開始曜日の計算 (旧版の「Show Calendar Week」「First day of week」)。
 * 曜日は ISO の番号で持つ (1=月 … 7=日)。
 */
object WeekCalendar {
    /** 既定の週の開始曜日。これまでの月表示が日曜始まりだったので日曜にして見た目を変えない */
    const val DEFAULT_FIRST_WEEKDAY = 7

    /** 範囲外の値は既定に戻す (保存値が壊れていても月表示を崩さない) */
    fun normalize(firstWeekday: Int): Int = if (firstWeekday in 1..7) firstWeekday else DEFAULT_FIRST_WEEKDAY

    /** [firstWeekday] から始まる7曜日の並び */
    fun weekdayOrder(firstWeekday: Int): List<DayOfWeek> {
        val start = normalize(firstWeekday)
        return (0 until 7).map { DayOfWeek(((start - 1 + it) % 7) + 1) }
    }

    /** [date] を含む週の初日 */
    fun weekStart(date: LocalDate, firstWeekday: Int): LocalDate {
        val back = (date.dayOfWeek.isoDayNumber - normalize(firstWeekday) + 7) % 7
        return date.minus(DatePeriod(days = back))
    }

    /** 月表示の左上に来る日 (その月の1日を含む週の初日) */
    fun monthGridStart(month: LocalDate, firstWeekday: Int): LocalDate =
        weekStart(LocalDate(month.year, month.month, 1), firstWeekday)

    /**
     * ISO 8601 の週番号。週の開始曜日の設定には左右されない
     * (Google カレンダーと同じく、年をまたぐ週は木曜日が属する年で数える)。
     */
    fun isoWeekNumber(date: LocalDate): Int {
        val thursday = date.plus(DatePeriod(days = 4 - date.dayOfWeek.isoDayNumber))
        val jan4 = LocalDate(thursday.year, 1, 4)
        val week1Monday = jan4.minus(DatePeriod(days = jan4.dayOfWeek.isoDayNumber - 1))
        return ((thursday.toEpochDays() - week1Monday.toEpochDays()) / 7).toInt() + 1
    }
}
