package com.calendaralarm.shared

import com.calendaralarm.shared.logic.AlarmExpander
import com.calendaralarm.shared.model.AlarmRule
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.shared.model.EventOverride
import com.calendaralarm.shared.model.EventTypeFilter
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #201: 勤務場所・誕生日・不在の予定が、既定の絞り込みで終日・繰り返し・日付の境目でも予約されないこと、
 * 設定を ON にした時に今の通知時刻のままであること、個別指定の優先順を固定する。
 * 現在の挙動を固定する試験で、挙動を変える試験ではない。
 */
class EventTypeDefaultsExpansionTest {

    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val now = LocalDateTime(2026, 11, 5, 12, 0).toInstant(tokyo)
    private val horizon = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 14 * 86_400_000L)

    /** Google の種類で3種類を表す (title は種類と無関係な題にして、種類の判定が Google 側に由来することを確かめる) */
    private val types = listOf("workingLocation", "birthday", "outOfOffice")

    private fun local(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime(y, mo, d, h, mi).toInstant(tokyo).toEpochMilliseconds()

    private fun utcMidnight(y: Int, mo: Int, d: Int): Long =
        LocalDateTime(y, mo, d, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()

    private fun timed(id: String, type: String?, startMillis: Long, recurring: Boolean = false) = CalendarEvent(
        id = id, calendarId = "c1", title = "定例", startMillis = startMillis,
        endMillis = startMillis + 3_600_000L, allDay = false, googleEventType = type, recurring = recurring,
    )

    private fun allDay(id: String, type: String?, y: Int, mo: Int, d: Int, reminders: List<Int> = emptyList()) =
        CalendarEvent(
            id = id, calendarId = "c1", title = "定例", startMillis = utcMidnight(y, mo, d),
            endMillis = utcMidnight(y, mo, d) + 86_400_000L, allDay = true, googleEventType = type,
            calendarReminderMinutes = reminders,
        )

    private fun expand(
        events: List<CalendarEvent>,
        filter: EventTypeFilter = EventTypeFilter(),
        overrides: Map<String, EventOverride> = emptyMap(),
        rule: AlarmRule = AlarmRule(),
        reminders: Boolean = false,
    ) = AlarmExpander.expandEvents(
        events, mapOf("c1" to rule), overrides, emptySet(), now, horizon, zone = tokyo,
        eventTypeFilter = filter, importEventReminders = reminders,
    )

    @Test
    fun `既定では3種類の終日の予定は予約されず通常の終日の予定は9時に予約される(現在の挙動を固定)`() {
        val events = types.mapIndexed { i, t -> allDay("t$i", t, 2026, 11, 6) } + allDay("normal", "default", 2026, 11, 6)
        val out = expand(events)
        assertEquals(1, out.size)
        assertTrue(out.single().eventId!!.startsWith("c1:normal:"))
        assertEquals(local(2026, 11, 6, 9, 0), out.single().triggerAtMillis)
    }

    @Test
    fun `既定では3種類の終日の予定にカレンダー側のリマインダーを取り込んでも予約されない(現在の挙動を固定)`() {
        val events = types.mapIndexed { i, t -> allDay("t$i", t, 2026, 11, 6, reminders = listOf(0, 60, 540)) }
        assertEquals(emptyList(), expand(events, reminders = true))
    }

    @Test
    fun `種類をONにした終日の予定は今までの時刻(既定は当日9時)で予約される(現在の挙動を固定)`() {
        val on = EventTypeFilter(birthday = true, absence = true, workplace = true)
        val out = expand(types.mapIndexed { i, t -> allDay("t$i", t, 2026, 11, 6) }, filter = on)
        assertEquals(3, out.size)
        assertTrue(out.all { it.triggerAtMillis == local(2026, 11, 6, 9, 0) })
    }

    @Test
    fun `既定では3種類の繰り返しの予定は毎回とも予約されない(現在の挙動を固定)`() {
        val events = types.flatMapIndexed { i, t ->
            (6..9).map { d -> timed("t$i-$d", t, local(2026, 11, d, 0, 0), recurring = true) }
        } + (6..9).map { d -> timed("normal-$d", "default", local(2026, 11, d, 0, 0), recurring = true) }
        val out = expand(events)
        assertEquals(4, out.size)
        assertTrue(out.all { it.eventId!!.startsWith("c1:normal-") })
    }

    @Test
    fun `既定では0時ちょうど前後の3種類の予定は予約されず通常の予定は0時に予約される(現在の挙動を固定)`() {
        val boundaries = listOf(local(2026, 11, 6, 0, 0), local(2026, 11, 5, 23, 59), local(2026, 11, 6, 0, 1))
        val events = types.flatMapIndexed { i, t -> boundaries.mapIndexed { j, s -> timed("t$i-$j", t, s) } } +
            timed("normal", null, local(2026, 11, 6, 0, 0))
        val out = expand(events)
        assertEquals(1, out.size)
        assertEquals(local(2026, 11, 6, 0, 0), out.single().triggerAtMillis)
    }

    @Test
    fun `題で判定される3種類も既定では予約されない(現在の挙動を固定)`() {
        val titles = listOf("勤務場所", "誕生日", "不在")
        val events = titles.mapIndexed { i, t ->
            CalendarEvent(
                id = "t$i", calendarId = "c1", title = t, startMillis = local(2026, 11, 6, 0, 0),
                endMillis = local(2026, 11, 6, 1, 0), allDay = false,
            )
        }
        assertEquals(emptyList(), expand(events))
    }

    @Test
    fun `個別にアラームを選んだ3種類の予定は既定の絞り込みでも予約される(現在の挙動を固定)`() {
        val events = types.mapIndexed { i, t -> timed("t$i", t, local(2026, 11, 6, 10, 0)) }
        val ov = events.associate { it.instanceKey to EventOverride(action = EventAction.ALARM) }
        assertEquals(3, expand(events, overrides = ov).size)
    }

    @Test
    fun `個別に鳴らさないを選んだ予定は種類がONでも予約されない(現在の挙動を固定)`() {
        val on = EventTypeFilter(birthday = true, absence = true, workplace = true)
        val events = types.mapIndexed { i, t -> timed("t$i", t, local(2026, 11, 6, 10, 0)) }
        val ov = events.associate { it.instanceKey to EventOverride(action = EventAction.MUTE) }
        assertEquals(emptyList(), expand(events, filter = on, overrides = ov))
    }

    @Test
    fun `終日を鳴らさないカレンダーは個別のアラーム指定より強い(現在の挙動を固定)`() {
        val e = allDay("t", "outOfOffice", 2026, 11, 6)
        val ov = mapOf(e.instanceKey to EventOverride(action = EventAction.ALARM))
        assertEquals(emptyList(), expand(listOf(e), overrides = ov, rule = AlarmRule(allDayMinutes = -1)))
    }
}
