package com.calendaralarm.shared

import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.EventType
import com.calendaralarm.shared.model.EventTypeFilter
import com.calendaralarm.shared.model.classifyEventType
import com.calendaralarm.shared.model.googleEventTypeToEventType
import com.calendaralarm.shared.model.resolveEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** イベント種別判定の単体テスト (Google eventType 正本 + タイトルフォールバック)。 */
class EventTypeTest {

    private fun event(title: String, googleType: String? = null) = CalendarEvent(
        id = "e1", calendarId = "c1", title = title,
        startMillis = 0, endMillis = 3_600_000, allDay = false,
        googleEventType = googleType,
    )

    // ---- googleEventTypeToEventType ----

    @Test
    fun googleType正規値が写像される() {
        assertEquals(EventType.BIRTHDAY, googleEventTypeToEventType("birthday"))
        assertEquals(EventType.ABSENCE, googleEventTypeToEventType("outOfOffice"))
        assertEquals(EventType.WORKPLACE, googleEventTypeToEventType("workingLocation"))
        assertEquals(EventType.TASK, googleEventTypeToEventType("task"))
        assertEquals(EventType.EVENT, googleEventTypeToEventType("default"))
        assertEquals(EventType.FOCUS_TIME, googleEventTypeToEventType("focusTime"))
        assertEquals(EventType.EVENT, googleEventTypeToEventType("fromGmail"))
    }

    @Test
    fun googleType未知値はnull() {
        assertNull(googleEventTypeToEventType(null))
        assertNull(googleEventTypeToEventType(""))
        assertNull(googleEventTypeToEventType("unknownFutureType"))
    }

    // ---- classifyEventType (タイトルフォールバック) ----

    @Test
    fun 終日の予定はタイトルに語を含めば各種別を分類() {
        assertEquals(EventType.BIRTHDAY, classifyEventType("田中さんの誕生日", allDay = true))
        assertEquals(EventType.BIRTHDAY, classifyEventType("Mika's birthday", allDay = true))
        assertEquals(EventType.ABSENCE, classifyEventType("夏季休暇", allDay = true))
        assertEquals(EventType.ABSENCE, classifyEventType("Out of office", allDay = true))
        assertEquals(EventType.WORKPLACE, classifyEventType("勤務場所: オフィス", allDay = true))
        assertEquals(EventType.WORKPLACE, classifyEventType("在宅勤務", allDay = true))
        assertEquals(EventType.TASK, classifyEventType("タスク: 請求書送付", allDay = true))
        assertEquals(EventType.EVENT, classifyEventType("定例ミーティング", allDay = true))
    }

    @Test
    fun 時刻のある予定はタイトル全体が語と一致した時だけ分類() {
        assertEquals(EventType.BIRTHDAY, classifyEventType("誕生日", allDay = false))
        assertEquals(EventType.ABSENCE, classifyEventType("Out of office", allDay = false))
        assertEquals(EventType.WORKPLACE, classifyEventType("在宅勤務", allDay = false))
        assertEquals(EventType.TASK, classifyEventType("TODO", allDay = false))
        assertEquals(EventType.EVENT, classifyEventType("田中さんの誕生日", allDay = false))
        assertEquals(EventType.EVENT, classifyEventType("夏季休暇", allDay = false))
        assertEquals(EventType.EVENT, classifyEventType("タスク: 請求書送付", allDay = false))
        assertEquals(EventType.EVENT, classifyEventType("定例ミーティング", allDay = false))
    }

    @Test
    fun 誕生日は他キーワードより優先() {
        // 「誕生日に休暇」→ BIRTHDAY が先勝ち
        assertEquals(EventType.BIRTHDAY, classifyEventType("誕生日休暇", allDay = true))
    }

    // ---- resolveEventType (正本優先 + フォールバック) ----

    @Test
    fun googleTypeが取れていれば正本が勝つ() {
        // タイトルは「休暇」だが API は birthday → BIRTHDAY を優先
        assertEquals(EventType.BIRTHDAY, resolveEventType(event("夏季休暇", googleType = "birthday")))
        // タイトルに手がかりがなくても API があれば分類できる
        assertEquals(EventType.ABSENCE, resolveEventType(event("有給の日", googleType = "outOfOffice")))
    }

    @Test
    fun googleType未付与や未知値はタイトル判定へフォールバック() {
        assertEquals(EventType.BIRTHDAY, resolveEventType(event("誕生日", googleType = null)))
        assertEquals(EventType.ABSENCE, resolveEventType(event("休暇", googleType = "futureType")))
        assertEquals(EventType.EVENT, resolveEventType(event("母の誕生日", googleType = null)))
        assertEquals(
            EventType.BIRTHDAY,
            resolveEventType(event("母の誕生日", googleType = null).copy(allDay = true)),
        )
    }

    // ---- EventTypeFilter ----

    @Test
    fun 既定は予定とタスクだけ鳴らし不在などは鳴らさない() {
        val f = EventTypeFilter()
        assertTrue(f.allows(EventType.EVENT))
        assertTrue(f.allows(EventType.TASK))
        assertFalse(f.allows(EventType.ABSENCE))
        assertFalse(f.allows(EventType.FOCUS_TIME))
        assertFalse(f.allows(EventType.WORKPLACE))
        assertFalse(f.allows(EventType.BIRTHDAY))
        assertTrue(f.free)
    }

    @Test
    fun OFFにした種別だけ鳴らない() {
        val all = EventTypeFilter(birthday = true, absence = true, workplace = true, focusTime = true)
        val f = all.copy(birthday = false, absence = false)
        assertFalse(f.allows(EventType.BIRTHDAY))
        assertFalse(f.allows(EventType.ABSENCE))
        assertTrue(f.allows(EventType.WORKPLACE))
        assertTrue(f.allows(EventType.FOCUS_TIME))
        assertTrue(f.allows(EventType.TASK))
        assertTrue(f.allows(EventType.EVENT))
    }

    @Test
    fun 種別フィルタとresolveを通した経路() {
        // API正本 BIRTHDAY + birthday OFF → 鳴らない
        val f = EventTypeFilter(birthday = false, event = true)
        assertFalse(f.allows(resolveEventType(event("会議", googleType = "birthday"))))
        // 未連携イベントのタイトル判定 + event ON → 鳴る
        assertTrue(f.allows(resolveEventType(event("会議"))))
    }
}
