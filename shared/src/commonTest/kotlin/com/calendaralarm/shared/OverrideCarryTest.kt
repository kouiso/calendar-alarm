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

    private fun ev(id: String, start: Long, cal: String = "1", recurring: Boolean = false) = CalendarEvent(
        id = id, calendarId = cal, title = "予定$id",
        startMillis = start, endMillis = start + hour, allDay = false, recurring = recurring,
    )

    /** 再同期と同じく、読み取り範囲は今の 14 日前から 28 日先まで */
    private fun reconcile(
        overrides: Map<String, EventOverride>,
        events: List<CalendarEvent>,
        until: Long = now + 28 * day,
    ) = OverrideCarry.reconcile(overrides, events, now, day, now - 14 * day, until)

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
        val events = old.map { ev("9", it + hour, recurring = true) }
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
        val events = listOf(ev("9", now + 2 * day, recurring = true), ev("9", now + 3 * day, recurring = true))
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

    @Test
    fun 読み取り範囲から出た過去の回の指定を新しく入った先の回へ付けない() {
        // 毎月の予定: 過去の回が範囲の外へ出て、先の回が範囲に入った (数は 1 対 1 で合う)
        val past = now - 15 * day
        val future = now + 16 * day
        val r = reconcile(mapOf("1:9:$past" to mute), listOf(ev("9", future)))
        assertEquals(emptyMap(), r.moved)
        assertEquals(setOf("1:9:$past"), r.dropped)
        assertEquals(emptyMap(), r.overrides)
    }

    @Test
    fun 繰り返しの1回が消えて別の回が入っても指定を付け替えない() {
        // 指定のある回 X が消え、同じ再同期で先の回 Y が範囲に入った。残る回 A にも指定がある。
        // はぐれた指定 (X) と指定の無い回 (Y) が 1 対 1 で数は合うが、X と Y は別の回
        val a = now + day
        val x = now + 3 * day
        val y = now + 20 * day
        val overrides = mapOf("1:9:$a" to mute, "1:9:$x" to thirty)
        val events = listOf(ev("9", a, recurring = true), ev("9", y, recurring = true))
        val r = reconcile(overrides, events)
        assertEquals(emptyMap(), r.moved)
        assertEquals(overrides, r.overrides)
    }

    @Test
    fun 繰り返しで回ごとの時刻の差がそろわない時は付け替えない() {
        val a = now + day
        val b = now + 2 * day
        val overrides = mapOf("1:9:$a" to mute, "1:9:$b" to thirty)
        val events = listOf(ev("9", a + hour, recurring = true), ev("9", b + 3 * hour, recurring = true))
        val r = reconcile(overrides, events)
        assertEquals(emptyMap(), r.moved)
    }

    @Test
    fun 読み取り範囲が縮んで先の回が範囲から出ても指定を近い回へ付けない() {
        // 毎月の予定: 近い回 Y は指定無し、先の回 X は鳴らさない。設定で範囲が 28 日先から 14 日先へ縮み、X が範囲から出た
        val y = now + 3 * day
        val x = now + 33 * day
        val r = reconcile(mapOf("1:9:$x" to mute), listOf(ev("9", y)), until = now + 14 * day)
        assertEquals(emptyMap(), r.moved)
        assertEquals(emptySet(), r.dropped)
        assertEquals(mapOf("1:9:$x" to mute), r.overrides)
    }

    @Test
    fun 範囲から出た先の回が全部同じ間隔でも近い回へ付けない() {
        val x1 = now + 30 * day
        val x2 = now + 37 * day
        val events = listOf(ev("9", now + 2 * day, recurring = true), ev("9", now + 9 * day, recurring = true))
        val r = reconcile(mapOf("1:9:$x1" to mute, "1:9:$x2" to mute), events, until = now + 14 * day)
        assertEquals(emptyMap(), r.moved)
    }

    @Test
    fun 繰り返しの1回を消して別の回が1つだけ残っても指定を付けない() {
        // 毎月の予定: 今月の回 X を鳴らさないにして、X だけを消した。範囲には来月の回 Y だけが残る
        val x = now + 5 * day
        val y = now + 35 * day
        val r = reconcile(mapOf("1:9:$x" to mute), listOf(ev("9", y, recurring = true)), until = now + 42 * day)
        assertEquals(emptyMap(), r.moved)
        assertEquals(mapOf("1:9:$x" to mute), r.overrides)
    }
}
