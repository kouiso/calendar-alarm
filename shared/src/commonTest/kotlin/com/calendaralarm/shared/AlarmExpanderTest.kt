package com.calendaralarm.shared

import com.calendaralarm.shared.logic.AlarmExpander
import com.calendaralarm.shared.logic.AlarmPlanner
import com.calendaralarm.shared.model.AlarmRule
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.EventOverride
import com.calendaralarm.shared.model.StandaloneAlarm
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val TZ = TimeZone.of("Asia/Tokyo")
private fun ldt(s: String) = LocalDateTime.parse(s).toInstant(TZ)

class AlarmExpanderTest {

    private val now: Instant = ldt("2026-10-05T08:00:00")
    private val horizon: Instant = ldt("2026-10-19T08:00:00")

    private fun event(
        id: String,
        calId: String = "c1",
        start: Instant,
        title: String = "会議",
    ) = CalendarEvent(
        id = id, calendarId = calId, title = title,
        startMillis = start.toEpochMilliseconds(),
        endMillis = start.toEpochMilliseconds() + 3_600_000,
        allDay = false,
    )

    @Test
    fun `イベントが開始時刻ちょうどに展開される`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"))
        val out = AlarmExpander.expandEvents(
            events = listOf(ev),
            calendarRules = mapOf("c1" to AlarmRule()),
            overrides = emptyMap(),
            disabledCalendarIds = emptySet(),
            now = now, horizon = horizon,
        )
        assertEquals(1, out.size)
        assertEquals(ldt("2026-10-05T10:00:00").toEpochMilliseconds(), out[0].triggerAtMillis)
    }

    @Test
    fun `X分前ルールが適用される`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"))
        val out = AlarmExpander.expandEvents(
            listOf(ev),
            calendarRules = mapOf("c1" to AlarmRule(minutesBefore = 15)),
            overrides = emptyMap(), disabledCalendarIds = emptySet(),
            now = now, horizon = horizon,
        )
        assertEquals(ldt("2026-10-05T09:45:00").toEpochMilliseconds(), out[0].triggerAtMillis)
        assertEquals("ev:c1:e1:${ev.startMillis}:b15", out[0].id)
    }

    @Test
    fun `イベント上書きがカレンダールールに優先する`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"))
        val out = AlarmExpander.expandEvents(
            listOf(ev),
            calendarRules = mapOf("c1" to AlarmRule(minutesBefore = 15)),
            overrides = mapOf(ev.instanceKey to EventOverride(minutesBefore = 5)),
            disabledCalendarIds = emptySet(),
            now = now, horizon = horizon,
        )
        assertEquals(ldt("2026-10-05T09:55:00").toEpochMilliseconds(), out[0].triggerAtMillis)
    }

    @Test
    fun `ミュートと無効カレンダーと無効ルールが除外される`() {
        val ev1 = event("e1", calId = "mutedCal", start = ldt("2026-10-05T10:00:00"))
        val ev2 = event("e2", calId = "offCal", start = ldt("2026-10-05T10:00:00"))
        val ev3 = event("e3", calId = "ruleOff", start = ldt("2026-10-05T10:00:00"))
        val out = AlarmExpander.expandEvents(
            listOf(ev1, ev2, ev3),
            calendarRules = mapOf(
                "mutedCal" to AlarmRule(),
                "offCal" to AlarmRule(),
                "ruleOff" to AlarmRule(enabled = false),
            ),
            overrides = mapOf(ev1.instanceKey to EventOverride(muted = true)),
            disabledCalendarIds = setOf("offCal"),
            now = now, horizon = horizon,
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `過去のイベントはグレース幅だけ残る`() {
        val recent = event("e1", start = ldt("2026-10-05T07:30:00")) // 30分前 → 残る
        val stale = event("e2", start = ldt("2026-10-05T05:00:00"))  // 3時間前 → 捨てる
        val out = AlarmExpander.expandEvents(
            listOf(recent, stale),
            calendarRules = mapOf("c1" to AlarmRule()),
            overrides = emptyMap(), disabledCalendarIds = emptySet(),
            now = now, horizon = horizon,
        )
        assertEquals(listOf("e1"), out.map { it.eventId?.split(":")?.get(1) })
    }

    @Test
    fun `曜日繰り返しと例外日`() {
        val alarm = StandaloneAlarm(
            id = 7, hour = 7, minute = 30,
            daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            exceptions = setOf(LocalDate(2026, 10, 7)),
        )
        val out = AlarmExpander.expandStandalone(alarm, now = now, days = 8, zone = TZ)
        // 10-05(Mon) 7:30 は30分前=グレース幅60分内→残る。10-07(Wed)は例外。10-09(Fri), 10-12(Mon) も残る
        assertEquals(
            listOf(LocalDate(2026, 10, 5), LocalDate(2026, 10, 9), LocalDate(2026, 10, 12)),
            out.map {
                Instant.fromEpochMilliseconds(it.triggerAtMillis)
                    .toLocalDateTime(TZ).date
            },
        )
    }

    @Test
    fun `単発アラームは当日が過ぎたら翌日に繰り越す`() {
        val alarm = StandaloneAlarm(id = 1, hour = 6, minute = 0) // now=08:00 → 当日分はグレース超過
        val out = AlarmExpander.expandStandalone(alarm, now = now, days = 14, zone = TZ)
        // 10-05T06:00 は2時間前(グレース60分超)→翌10-06T06:00が最初の回
        assertEquals(1, out.size)
        assertEquals(LocalDate(2026, 10, 6), Instant.fromEpochMilliseconds(out[0].triggerAtMillis).toLocalDateTime(TZ).date)
    }

    @Test
    fun `タイムゾーン変更でローカル時刻が動いてもUTC絶対時刻が正しい`() {
        val ny = TimeZone.of("America/New_York")
        val alarm = StandaloneAlarm(id = 1, hour = 9, minute = 0, daysOfWeek = DayOfWeek.entries.toSet())
        val outTokyo = AlarmExpander.expandStandalone(alarm, now = now, days = 1, zone = TZ)
        val outNy = AlarmExpander.expandStandalone(alarm, now = now, days = 1, zone = ny)
        // 東京 9:00 = UTC 0:00、NY 9:00 = UTC 13:00(EDT)。同じ wall-clock でも絶対時刻は別
        assertTrue(outTokyo[0].triggerAtMillis < outNy[0].triggerAtMillis)
    }
}

class AlarmPlannerTest {
    private val now: Instant = ldt("2026-10-05T08:00:00")

    private fun inst(id: String, trigger: Instant) = com.calendaralarm.shared.model.AlarmInstance(
        id = id, triggerAtMillis = trigger.toEpochMilliseconds(),
        title = id, kind = com.calendaralarm.shared.model.AlarmKind.EVENT,
    )

    @Test
    fun `新規・変更・削除・即時鳴動が正しく分類される`() {
        val scheduled = mapOf(
            "same" to ldt("2026-10-05T09:00:00").toEpochMilliseconds(),
            "moved" to ldt("2026-10-05T09:00:00").toEpochMilliseconds(),
            "gone" to ldt("2026-10-05T09:00:00").toEpochMilliseconds(),
        )
        val desired = listOf(
            inst("same", ldt("2026-10-05T09:00:00")),      // 変化なし
            inst("moved", ldt("2026-10-05T10:00:00")),    // 時刻変更→再予約
            inst("new", ldt("2026-10-05T11:00:00")),      // 新規
            inst("overdue", ldt("2026-10-05T07:30:00")),  // 過去(グレース内)→即鳴動
        )
        val plan = AlarmPlanner.plan(scheduled, desired, now)
        assertEquals(setOf("moved", "new", "overdue"), plan.toSchedule.map { it.id }.toSet())
        assertEquals(listOf("gone"), plan.toCancel)
        assertEquals(listOf("overdue"), plan.toFireNow.map { it.id })
    }
}
