package com.calendaralarm

import android.app.NotificationManager
import com.calendaralarm.engine.AlarmService
import com.calendaralarm.engine.dndActive
import com.calendaralarm.engine.effectiveIgnoreDnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「おやすみモード中も鳴らす」(#149、盤 p149)。OFF は「おやすみモード中は鳴らさず、通知だけ残す」:
 * すべて許可以外のフィルタ (優先・アラームのみ・サイレント) の間は、アラーム許可の有無に関係なく鳴らさない。
 * ON でも通知ポリシーの許可が無ければ OFF と同じ (画面がスイッチを OFF で出すのと揃える)。
 */
class DndSilenceTest {
    private val all = NotificationManager.INTERRUPTION_FILTER_ALL
    private val priority = NotificationManager.INTERRUPTION_FILTER_PRIORITY
    private val alarms = NotificationManager.INTERRUPTION_FILTER_ALARMS
    private val none = NotificationManager.INTERRUPTION_FILTER_NONE
    private val unknown = NotificationManager.INTERRUPTION_FILTER_UNKNOWN

    private fun silenced(ignoreDnd: Boolean, granted: Boolean, filter: Int?) =
        AlarmService.silencedByDnd(ignoreDnd, granted, filter)

    @Test
    fun `ONで許可があればおやすみモードのどのフィルタでも鳴らす判断`() {
        listOf(all, priority, alarms, none, unknown, null).forEach { f ->
            assertFalse("filter=$f", silenced(true, true, f))
        }
    }

    @Test
    fun `OFFで優先モードはアラームを許可していてもしていなくても鳴らさない`() {
        // アラーム許可の有無は silencedByDnd の入力に無い。許可ありでも許可なしでも同じ答えになること
        assertTrue(silenced(false, true, priority))
        assertTrue(silenced(false, false, priority))
    }

    @Test
    fun `OFFでアラームのみなら鳴らさない`() {
        assertTrue(silenced(false, true, alarms))
    }

    @Test
    fun `OFFでサイレントなら鳴らさない`() {
        assertTrue(silenced(false, true, none))
    }

    @Test
    fun `OFFでもおやすみモードでなければ鳴らす`() {
        assertFalse(silenced(false, true, all))
    }

    @Test
    fun `状態が取れない時は鳴らす側に倒す`() {
        assertFalse(silenced(false, true, unknown))
        assertFalse(silenced(false, true, null))
        assertFalse(silenced(false, true, 99))
    }

    @Test
    fun `ONでも許可が無ければOFFと同じ`() {
        assertFalse(effectiveIgnoreDnd(ignoreDnd = true, policyAccessGranted = false))
        assertTrue(effectiveIgnoreDnd(ignoreDnd = true, policyAccessGranted = true))
        assertFalse(effectiveIgnoreDnd(ignoreDnd = false, policyAccessGranted = true))
        listOf(priority, alarms, none).forEach { f ->
            assertTrue("filter=$f", silenced(true, false, f))
        }
        assertFalse(silenced(true, false, all))
    }

    @Test
    fun `おやすみモードが有効なのはすべて許可以外の3つだけ`() {
        assertTrue(dndActive(priority))
        assertTrue(dndActive(alarms))
        assertTrue(dndActive(none))
        assertFalse(dndActive(all))
        assertFalse(dndActive(unknown))
        assertFalse(dndActive(null))
    }

    @Test
    fun `チャンネルは無視ONで既存のbypassDnd側を使う`() {
        assertEquals(AlarmService.CHANNEL_ID, AlarmService.ringChannelId(vibrate = true, ignoreDnd = true))
        assertEquals(AlarmService.CHANNEL_ID_NOVIB, AlarmService.ringChannelId(vibrate = false, ignoreDnd = true))
        assertEquals(AlarmService.CHANNEL_ID_VIB_DND, AlarmService.ringChannelId(vibrate = true, ignoreDnd = false))
        assertEquals(AlarmService.CHANNEL_ID_NOVIB_DND, AlarmService.ringChannelId(vibrate = false, ignoreDnd = false))
    }
}
