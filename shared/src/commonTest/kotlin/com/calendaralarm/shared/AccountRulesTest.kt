package com.calendaralarm.shared

import com.calendaralarm.shared.logic.AccountRules
import com.calendaralarm.shared.logic.AlarmExpander
import com.calendaralarm.shared.model.AccountSetting
import com.calendaralarm.shared.model.AlarmRule
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.CalendarSource
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountRulesTest {
    private val work = CalendarSource("1", "仕事", "work@example.com", 0, isPrimary = true)
    private val workShared = CalendarSource("2", "共有", "work@example.com", 0)
    private val home = CalendarSource("3", "家", "home@example.com", 0, isPrimary = true)
    private val calendars = listOf(work, workShared, home)
    private val default = AlarmRule(minutesBefore = 0)

    private val now = Instant.parse("2026-10-21T00:00:00Z")
    private fun event(id: String, calId: String) = CalendarEvent(
        id = id, calendarId = calId, title = "会議 $id",
        startMillis = Instant.parse("2026-10-21T03:00:00Z").toEpochMilliseconds(),
        endMillis = Instant.parse("2026-10-21T04:00:00Z").toEpochMilliseconds(),
        allDay = false,
    )

    /** eventId は instanceKey ("カレンダー:予定:開始") なので予定 id だけ取り出す */
    private val com.calendaralarm.shared.model.AlarmInstance.eventKey: String
        get() = eventId!!.split(":")[1]

    private fun expand(applied: AccountRules.Applied) = AlarmExpander.expandEvents(
        events = listOf(event("a", "1"), event("b", "2"), event("c", "3")),
        calendarRules = applied.calendarRules,
        overrides = emptyMap(),
        disabledCalendarIds = applied.disabledCalendarIds,
        now = now,
        horizon = Instant.parse("2026-10-22T00:00:00Z"),
        zone = TimeZone.UTC,
        defaultRule = default,
        importEventReminders = false,
    )

    @Test
    fun `片方のアカウントだけ鳴らさない設定にすると、そのアカウントの予定だけ予約されない`() {
        val applied = AccountRules.apply(
            calendars, mapOf("home@example.com" to AccountSetting(ring = false)), emptyMap(), default,
        )
        val ids = expand(applied).map { it.eventKey }.toSet()
        assertEquals(setOf("a", "b"), ids)
        assertTrue("3" !in applied.hiddenCalendarIds, "鳴らさないだけなら一覧には出す")
    }

    @Test
    fun `使わないアカウントは鳴らさず一覧にも出さない`() {
        val applied = AccountRules.apply(
            calendars, mapOf("work@example.com" to AccountSetting(use = false)), emptyMap(), default,
        )
        assertEquals(setOf("1", "2"), applied.hiddenCalendarIds)
        assertEquals(setOf("c"), expand(applied).map { it.eventKey }.toSet())
    }

    @Test
    fun `アカウントの何分前は配下カレンダーの既定になり、カレンダーで決めた値が勝つ`() {
        val applied = AccountRules.apply(
            calendars,
            mapOf("work@example.com" to AccountSetting(minutesBefore = 15)),
            mapOf("2" to AlarmRule(minutesBefore = 5)),
            default,
        )
        assertEquals(15, applied.calendarRules["1"]?.minutesBefore)
        assertEquals(5, applied.calendarRules["2"]?.minutesBefore)
        assertEquals(null, applied.calendarRules["3"])
        val byEvent = expand(applied).associate { it.eventKey to it.minutesBefore }
        assertEquals(15, byEvent["a"])
        assertEquals(5, byEvent["b"])
        assertEquals(0, byEvent["c"])
    }

    @Test
    fun `カレンダーで鳴らさないにした分はアカウントが鳴らす設定でも鳴らさない`() {
        val applied = AccountRules.apply(
            calendars, emptyMap(), mapOf("1" to AlarmRule(enabled = false)), default,
        )
        assertEquals(setOf("1"), applied.disabledCalendarIds)
        assertEquals(setOf("b", "c"), expand(applied).map { it.eventKey }.toSet())
    }

    @Test
    fun `設定の無いアカウントはこれまでどおり全部鳴る`() {
        val applied = AccountRules.apply(calendars, emptyMap(), emptyMap(), default)
        assertEquals(setOf("a", "b", "c"), expand(applied).map { it.eventKey }.toSet())
    }

    @Test
    fun `設定画面ではアカウントごとに主カレンダーを先に並べる`() {
        val groups = AccountRules.groupByAccount(listOf(workShared, home, work))
        assertEquals(listOf("home@example.com", "work@example.com"), groups.map { it.first })
        assertEquals(listOf("1", "2"), groups[1].second.map { it.id })
    }
}
