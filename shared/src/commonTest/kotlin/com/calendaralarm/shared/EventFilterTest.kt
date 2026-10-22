package com.calendaralarm.shared

import com.calendaralarm.shared.logic.AlarmExpander
import com.calendaralarm.shared.model.AlarmRule
import com.calendaralarm.shared.model.Availability
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.shared.model.EventOverride
import com.calendaralarm.shared.model.EventType
import com.calendaralarm.shared.model.EventTypeFilter
import com.calendaralarm.shared.model.InviteFilter
import com.calendaralarm.shared.model.classifyEventType
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 一覧の行のスイッチを ON に戻す時の分かれ目 (#51 PR2): 指定を外して鳴るなら外す、鳴らないなら ALARM を書く。 */
class SilencedWithoutOverrideTest {

    private val event = CalendarEvent(
        id = "e", calendarId = "c1", title = "会議",
        startMillis = 1_800_000_000_000L, endMillis = 1_800_003_600_000L, allDay = false,
    )

    private fun silenced(
        rule: AlarmRule,
        title: String = event.title,
        codes: com.calendaralarm.shared.model.TitleCodeSettings = com.calendaralarm.shared.model.TitleCodeSettings(),
        types: EventTypeFilter = EventTypeFilter(),
        hasReminderTriggers: Boolean = false,
    ) = AlarmExpander.silencedWithoutOverride(event.copy(title = title), rule, codes, InviteFilter(), types, hasReminderTriggers)

    @Test
    fun `通知だけのカレンダーは指定を外せば鳴る側なので ALARM を書かない`() {
        assertFalse(silenced(AlarmRule(startAction = EventAction.NOTIFY, reminderAction = EventAction.NOTIFY)))
    }

    @Test
    fun `開始もリマインダーも鳴らさないカレンダーは指定を外すと鳴らない`() {
        assertTrue(silenced(AlarmRule(startAction = EventAction.MUTE, reminderAction = EventAction.MUTE), hasReminderTriggers = true))
    }

    @Test
    fun `開始だけ鳴らさず、リマインダーの時刻があって通知なら鳴る側`() {
        val rule = AlarmRule(startAction = EventAction.MUTE, reminderAction = EventAction.NOTIFY)
        assertFalse(silenced(rule, hasReminderTriggers = true))
        assertTrue(silenced(rule, hasReminderTriggers = false))
    }

    @Test
    fun `種類の絞り込みで外れる予定は指定を外すと鳴らない`() {
        val rule = AlarmRule(startAction = EventAction.NOTIFY)
        val birthday = event.copy(googleEventType = "birthday")
        assertTrue(
            AlarmExpander.silencedWithoutOverride(
                birthday, rule, com.calendaralarm.shared.model.TitleCodeSettings(), InviteFilter(), EventTypeFilter(), false,
            ),
        )
    }
}

/** 予定の種類と「予定あり／なし」で鳴らす・鳴らさないを決める判定 (#79)。 */
class EventFilterTest {

    private val start = 1_800_000_000_000L
    private val now = Instant.fromEpochMilliseconds(start - 3_600_000)
    private val horizon = Instant.fromEpochMilliseconds(start + 86_400_000)

    private fun ev(
        id: String,
        title: String = "会議",
        googleType: String? = null,
        availability: Availability = Availability.UNKNOWN,
    ) = CalendarEvent(
        id = id, calendarId = "c1", title = title,
        startMillis = start, endMillis = start + 3_600_000, allDay = false,
        googleEventType = googleType, availability = availability,
    )

    private fun passes(e: CalendarEvent, f: EventTypeFilter = EventTypeFilter(), ov: EventOverride? = null) =
        AlarmExpander.passesEventFilters(e, ov, InviteFilter(), f)

    @Test
    fun 既定では自分の不在とサイレントモードと勤務場所と誕生日は鳴らない() {
        assertFalse(passes(ev("1", googleType = "outOfOffice")))
        assertFalse(passes(ev("2", googleType = "focusTime")))
        assertFalse(passes(ev("3", googleType = "workingLocation")))
        assertFalse(passes(ev("4", googleType = "birthday")))
        assertTrue(passes(ev("5", googleType = "default")))
        assertTrue(passes(ev("6", title = "タスク: 請求書")))
    }

    @Test
    fun 予定なしは既定で鳴り設定で止められる() {
        val free = ev("1", availability = Availability.FREE)
        assertTrue(passes(free))
        assertFalse(passes(free, EventTypeFilter(free = false)))
    }

    @Test
    fun 予定ありと仮と不明は予定なしの設定に左右されない() {
        val f = EventTypeFilter(free = false)
        assertTrue(passes(ev("1", availability = Availability.BUSY), f))
        assertTrue(passes(ev("2", availability = Availability.TENTATIVE), f))
        assertTrue(passes(ev("3", availability = Availability.UNKNOWN), f))
    }

    @Test
    fun 種類で止まる予定は予定なしを許しても鳴らない() {
        // 判定の順番: 種類 → 予定あり／なし
        assertFalse(passes(ev("1", googleType = "outOfOffice", availability = Availability.FREE)))
    }

    @Test
    fun 予定一つへの個別のアラーム指定は種類と予定なしより優先する() {
        val alarm = EventOverride(action = EventAction.ALARM)
        val f = EventTypeFilter(free = false)
        assertTrue(passes(ev("1", googleType = "outOfOffice"), f, alarm))
        assertTrue(passes(ev("2", availability = Availability.FREE), f, alarm))
        assertFalse(passes(ev("3"), EventTypeFilter(), EventOverride(action = EventAction.MUTE)))
    }

    @Test
    fun タイトルからサイレントモードを推定する() {
        assertEquals(EventType.FOCUS_TIME, classifyEventType("サイレントモード"))
        assertEquals(EventType.FOCUS_TIME, classifyEventType("Focus time"))
    }

    @Test
    fun 展開でも既定では不在は予約されず予定なしの設定も効く() {
        val events = listOf(
            ev("absence", googleType = "outOfOffice"),
            ev("normal"),
            ev("free", availability = Availability.FREE),
        )
        fun expand(f: EventTypeFilter) = AlarmExpander.expandEvents(
            events, mapOf("c1" to AlarmRule()), emptyMap(), emptySet(), now, horizon,
            eventTypeFilter = f,
        ).map { it.eventId!!.split(":")[1] }.toSet()
        assertEquals(setOf("normal", "free"), expand(EventTypeFilter()))
        assertEquals(setOf("normal"), expand(EventTypeFilter(free = false)))
    }
}
