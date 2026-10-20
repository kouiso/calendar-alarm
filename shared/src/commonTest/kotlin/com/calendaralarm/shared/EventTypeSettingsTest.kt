package com.calendaralarm.shared

import com.calendaralarm.shared.logic.AlarmExpander
import com.calendaralarm.shared.model.AlarmRule
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.shared.model.EventAvailability
import com.calendaralarm.shared.model.EventOverride
import com.calendaralarm.shared.model.EventType
import com.calendaralarm.shared.model.EventTypeFilter
import com.calendaralarm.shared.model.InviteFilter
import com.calendaralarm.shared.model.InviteStatus
import com.calendaralarm.shared.model.classifyEventType
import com.calendaralarm.shared.model.resolveEventType
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #79 予定の種類と「予定なし」ごとの鳴動設定。 */
class EventTypeSettingsTest {

    private val tz = TimeZone.of("Asia/Tokyo")
    private val now: Instant = LocalDateTime.parse("2026-10-05T08:00:00").toInstant(tz)
    private val horizon: Instant = LocalDateTime.parse("2026-10-19T08:00:00").toInstant(tz)
    private val start: Instant = LocalDateTime.parse("2026-10-05T10:00:00").toInstant(tz)

    private fun event(
        title: String = "定例ミーティング",
        googleType: String? = null,
        knownType: EventType? = null,
        availability: EventAvailability = EventAvailability.UNKNOWN,
        invite: InviteStatus? = null,
    ) = CalendarEvent(
        id = "e1", calendarId = "c1", title = title,
        startMillis = start.toEpochMilliseconds(),
        endMillis = start.toEpochMilliseconds() + 3_600_000,
        allDay = false,
        googleEventType = googleType,
        knownEventType = knownType,
        availability = availability,
        inviteStatus = invite,
    )

    private fun expand(
        ev: CalendarEvent,
        filter: EventTypeFilter = EventTypeFilter(),
        override: EventOverride? = null,
    ) = AlarmExpander.expandEvents(
        events = listOf(ev),
        calendarRules = mapOf("c1" to AlarmRule()),
        overrides = override?.let { mapOf(ev.instanceKey to it) } ?: emptyMap(),
        disabledCalendarIds = emptySet(),
        now = now, horizon = horizon, zone = tz,
        eventTypeFilter = filter,
    )

    // ---- 既定値 ----

    @Test
    fun 既定値は不在とサイレントモードと勤務場所と誕生日を鳴らさない() {
        val f = EventTypeFilter()
        assertFalse(f.allows(EventType.ABSENCE))
        assertFalse(f.allows(EventType.FOCUS_TIME))
        assertFalse(f.allows(EventType.WORKPLACE))
        assertFalse(f.allows(EventType.BIRTHDAY))
        assertTrue(f.allows(EventType.TASK))
        assertTrue(f.allows(EventType.EVENT))
        assertTrue(f.freeEvents)
    }

    @Test
    fun 既定値のまま不在の予定は予約されない() {
        assertTrue(expand(event(googleType = "outOfOffice")).isEmpty())
        assertTrue(expand(event(title = "夏季休暇 不在")).isEmpty())
    }

    // ---- 種類ごと (6種) ----

    @Test
    fun 種類ごとにONなら鳴りOFFなら鳴らない() {
        val cases = listOf(
            EventType.BIRTHDAY to event(googleType = "birthday"),
            EventType.ABSENCE to event(googleType = "outOfOffice"),
            EventType.WORKPLACE to event(googleType = "workingLocation"),
            EventType.FOCUS_TIME to event(googleType = "focusTime"),
            EventType.TASK to event(title = "タスク: 請求書送付"),
            EventType.EVENT to event(googleType = "default"),
        )
        assertEquals(EventType.entries.toSet(), cases.map { it.first }.toSet())
        for ((type, ev) in cases) {
            assertEquals(type, resolveEventType(ev), "resolve $type")
            val allOff = EventType.entries.fold(EventTypeFilter()) { f, t -> f.withType(t, false) }
            assertTrue(expand(ev, allOff.withType(type, true)).isNotEmpty(), "$type ON")
            val allOn = EventType.entries.fold(EventTypeFilter()) { f, t -> f.withType(t, true) }
            assertTrue(expand(ev, allOn.withType(type, false)).isEmpty(), "$type OFF")
        }
    }

    @Test
    fun サイレントモードはタイトルからも推定し勤務場所より先に判定する() {
        assertEquals(EventType.FOCUS_TIME, classifyEventType("Focus time"))
        assertEquals(EventType.FOCUS_TIME, classifyEventType("サイレントモード"))
        assertEquals(EventType.FOCUS_TIME, classifyEventType("Focus time @office"))
    }

    @Test
    fun iOSの誕生日カレンダーはタイトルに関係なく誕生日() {
        val ev = event(title = "山田太郎", knownType = EventType.BIRTHDAY)
        assertEquals(EventType.BIRTHDAY, resolveEventType(ev))
        assertTrue(expand(ev).isEmpty())
    }

    // ---- 予定あり／なし／仮／不明 ----

    @Test
    fun 予定なしは設定どおり_予定ありと仮と不明は常に鳴る() {
        val freeOff = EventTypeFilter(freeEvents = false)
        val freeOn = EventTypeFilter(freeEvents = true)
        assertTrue(expand(event(availability = EventAvailability.FREE), freeOff).isEmpty())
        assertTrue(expand(event(availability = EventAvailability.FREE), freeOn).isNotEmpty())
        for (a in listOf(EventAvailability.BUSY, EventAvailability.TENTATIVE, EventAvailability.UNKNOWN)) {
            assertTrue(expand(event(availability = a), freeOff).isNotEmpty(), "$a")
        }
    }

    @Test
    fun 既定値では予定なしも鳴る() {
        assertTrue(expand(event(availability = EventAvailability.FREE)).isNotEmpty())
    }

    @Test
    fun 種類で止まる予定は予定ありでも鳴らない() {
        assertTrue(expand(event(googleType = "outOfOffice", availability = EventAvailability.BUSY)).isEmpty())
    }

    // ---- 個別指定の優先 ----

    @Test
    fun 個別のアラーム指定は種類と予定なしの設定より優先する() {
        val alarm = EventOverride(action = EventAction.ALARM)
        val strict = EventTypeFilter(freeEvents = false)
        val ev = event(googleType = "outOfOffice", availability = EventAvailability.FREE)
        assertTrue(expand(ev, strict).isEmpty())
        assertTrue(expand(ev, strict, alarm).isNotEmpty())
        assertTrue(expand(event(availability = EventAvailability.FREE), strict, alarm).isNotEmpty())
    }

    @Test
    fun 個別のミュートは種類がONでも鳴らさない() {
        val all = EventType.entries.fold(EventTypeFilter()) { f, t -> f.withType(t, true) }
        assertTrue(expand(event(), all, EventOverride(action = EventAction.MUTE)).isEmpty())
    }

    @Test
    fun 個別の通知指定は種類フィルタを越えない() {
        // NOTIFY は鳴動方法の指定で「必ず鳴らす」意思ではないため、従来どおり種類の設定に従う
        assertTrue(expand(event(googleType = "outOfOffice"), override = EventOverride(action = EventAction.NOTIFY)).isEmpty())
    }

    @Test
    fun 一覧表示の判定と展開結果が一致する() {
        val filter = EventTypeFilter(freeEvents = false)
        val events = listOf(
            event(googleType = "outOfOffice"),
            event(availability = EventAvailability.FREE),
            event(availability = EventAvailability.BUSY),
            event(title = "誕生日"),
            event(invite = InviteStatus.DECLINED),
        )
        val overrides = listOf(null, EventOverride(action = EventAction.ALARM), EventOverride(action = EventAction.MUTE))
        for (ev in events) for (ov in overrides) {
            val listed = AlarmExpander.isEventEligible(ev, AlarmRule(), ov, InviteFilter(), filter)
            assertEquals(listed, expand(ev, filter, ov).isNotEmpty(), "$ev / $ov")
        }
    }

    // ---- 保存値の移行 ----

    @Test
    fun 一度も触っていない人は新しい既定値になる() {
        assertEquals(EventTypeFilter(), EventTypeFilter.fromStored(null, null, null))
    }

    @Test
    fun 旧版で保存したマスクは選択を保ち新しい項目だけ既定値() {
        // 旧版の全ON (31): 不在も鳴らす選択を変えない
        val all = EventTypeFilter.fromStored(31, null, null)
        assertTrue(all.birthday && all.absence && all.workplace && all.task && all.event)
        assertFalse(all.focusTime)
        assertTrue(all.freeEvents)
        // 旧版で誕生日だけ OFF (30)
        val noBirthday = EventTypeFilter.fromStored(30, null, null)
        assertFalse(noBirthday.birthday)
        assertTrue(noBirthday.absence)
        // マスクの書き戻しは同じ値になる (旧版と同じビット割り当て)
        assertEquals(31, all.toLegacyMask())
        assertEquals(30, noBirthday.toLegacyMask())
        assertEquals(EventTypeFilter.MASK_TASK + EventTypeFilter.MASK_EVENT, EventTypeFilter().toLegacyMask())
    }

    @Test
    fun 新しい項目の保存値はマスクと別に効く() {
        val f = EventTypeFilter.fromStored(31, focusTime = true, freeEvents = false)
        assertTrue(f.focusTime)
        assertFalse(f.freeEvents)
        assertEquals(31, f.toLegacyMask())
    }

    @Test
    fun 旧形式のJSONは選択を保ち新しい項目だけ既定値() {
        // iOS から SharedBridge へ届く旧形式 (5項目) の JSON
        val json = Json { ignoreUnknownKeys = true }
        val old = """{"birthday":true,"absence":true,"workplace":true,"task":true,"event":true}"""
        val f = json.decodeFromString(EventTypeFilter.serializer(), old)
        assertTrue(f.absence)
        assertFalse(f.focusTime)
        assertTrue(f.freeEvents)
    }

    @Test
    fun 空き状況が無い旧形式の予定JSONは不明として読む() {
        val json = Json { ignoreUnknownKeys = true }
        val ev = json.decodeFromString(
            CalendarEvent.serializer(),
            """{"id":"e","calendarId":"c","title":"t","startMillis":0,"endMillis":1,"allDay":false}""",
        )
        assertEquals(EventAvailability.UNKNOWN, ev.availability)
    }
}
