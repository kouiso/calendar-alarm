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
        assertEquals(EventType.EVENT, googleEventTypeToEventType("focusTime"))
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
    fun タイトルから各種別を分類() {
        assertEquals(EventType.BIRTHDAY, classifyEventType("田中さんの誕生日"))
        assertEquals(EventType.BIRTHDAY, classifyEventType("Mika's birthday"))
        assertEquals(EventType.ABSENCE, classifyEventType("夏季休暇"))
        assertEquals(EventType.ABSENCE, classifyEventType("Out of office"))
        assertEquals(EventType.WORKPLACE, classifyEventType("勤務場所: オフィス"))
        assertEquals(EventType.WORKPLACE, classifyEventType("在宅勤務"))
        assertEquals(EventType.TASK, classifyEventType("タスク: 請求書送付"))
        assertEquals(EventType.EVENT, classifyEventType("定例ミーティング"))
    }

    @Test
    fun 誕生日は他キーワードより優先() {
        // 「誕生日に休暇」→ BIRTHDAY が先勝ち
        assertEquals(EventType.BIRTHDAY, classifyEventType("誕生日休暇"))
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
        assertEquals(EventType.BIRTHDAY, resolveEventType(event("母の誕生日", googleType = null)))
        assertEquals(EventType.ABSENCE, resolveEventType(event("休暇", googleType = "futureType")))
    }

    // ---- EventTypeFilter ----

    @Test
    fun デフォルトは全種別許可() {
        val f = EventTypeFilter()
        EventType.entries.forEach { assertTrue(f.allows(it), "$it") }
    }

    @Test
    fun OFFにした種別だけ鳴らない() {
        val f = EventTypeFilter(birthday = false, absence = false)
        assertFalse(f.allows(EventType.BIRTHDAY))
        assertFalse(f.allows(EventType.ABSENCE))
        assertTrue(f.allows(EventType.WORKPLACE))
        assertTrue(f.allows(EventType.TASK))
        assertTrue(f.allows(EventType.EVENT))
    }

    @Test
    fun 種別フィルタとresolveを通した経路() {
        // API正本 BIRTHDAY + birthday OFF → 鳴らない
        val f = EventTypeFilter(birthday = false)
        assertFalse(f.allows(resolveEventType(event("会議", googleType = "birthday"))))
        // 未連携イベントのタイトル判定 + event ON → 鳴る
        assertTrue(f.allows(resolveEventType(event("会議"))))
    }
}
