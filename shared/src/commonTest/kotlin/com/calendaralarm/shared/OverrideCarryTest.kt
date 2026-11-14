package com.calendaralarm.shared

import com.calendaralarm.shared.logic.OverrideCarry
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.shared.model.EventOverride
import kotlin.test.Test
import kotlin.test.assertEquals

class OverrideCarryTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_800_000_000_000L
    private val mute = EventOverride(action = EventAction.MUTE)
    private val thirty = EventOverride(minutesBefore = 30)

    private fun ev(id: String, start: Long, cal: String = "1") = CalendarEvent(
        id = id, calendarId = cal, title = "予定$id",
        startMillis = start, endMillis = start + hour, allDay = false,
    )

    private fun reconcile(overrides: Map<String, EventOverride>, events: List<CalendarEvent>) =
        OverrideCarry.reconcile(overrides, events, now, day)

    @Test
    fun 時刻が動いた予定は指定を引き継ぐ() {
        val r = reconcile(mapOf("1:7:${now + 2 * hour}" to thirty), listOf(ev("7", now + 3 * hour)))
        assertEquals(mapOf("1:7:${now + 2 * hour}" to "1:7:${now + 3 * hour}"), r.moved)
        assertEquals(mapOf("1:7:${now + 3 * hour}" to thirty), r.overrides)
        assertEquals(emptySet(), r.dropped)
    }

    @Test
    fun 繰り返しの全部の回が同じだけずれたら早い順に引き継ぐ() {
        val old = listOf(now + hour, now + day + hour, now + 2 * day + hour)
        val overrides = mapOf("1:9:${old[0]}" to mute, "1:9:${old[1]}" to thirty, "1:9:${old[2]}" to mute)
        val events = old.map { ev("9", it + hour) }
        val r = reconcile(overrides, events)
        assertEquals(
            mapOf("1:9:${old[0] + hour}" to mute, "1:9:${old[1] + hour}" to thirty, "1:9:${old[2] + hour}" to mute),
            r.overrides,
        )
    }

    @Test
    fun 数が合わない時は付け替えず先の回の指定は残す() {
        // 繰り返しの 1 回だけに指定があり、その回が消えた。他の回のどれに付けるか決められない
        val overrides = mapOf("1:9:${now + day}" to mute)
        val events = listOf(ev("9", now + 2 * day), ev("9", now + 3 * day))
        val r = reconcile(overrides, events)
        assertEquals(emptyMap(), r.moved)
        assertEquals(emptySet(), r.dropped)
        assertEquals(overrides, r.overrides)
    }

    @Test
    fun 終わった回の指定は消える() {
        val past = now - 3 * day
        val r = reconcile(mapOf("1:5:$past" to mute), listOf(ev("6", now + hour)))
        assertEquals(setOf("1:5:$past"), r.dropped)
        assertEquals(emptyMap(), r.overrides)
    }

    @Test
    fun 今も読めている回の指定と別の予定の指定には触らない() {
        val overrides = mapOf("1:7:${now + hour}" to mute, "2:7:${now + 5 * hour}" to thirty)
        // カレンダー 2 の予定 7 は別の予定なので、カレンダー 1 の予定 7 の指定と組にしない
        val events = listOf(ev("7", now + hour), ev("7", now + 6 * hour, cal = "3"))
        val r = reconcile(overrides, events)
        assertEquals(emptyMap(), r.moved)
        assertEquals(overrides, r.overrides)
    }

    @Test
    fun 開始が数で読めないキーは触らない() {
        val overrides = mapOf("1:7:abc" to mute)
        val r = reconcile(overrides, listOf(ev("7", now + hour)))
        assertEquals(overrides, r.overrides)
        assertEquals(emptySet(), r.dropped)
    }
}
