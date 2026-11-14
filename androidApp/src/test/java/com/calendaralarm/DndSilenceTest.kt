package com.calendaralarm

import android.app.NotificationManager
import com.calendaralarm.engine.AlarmService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「おやすみモード中も鳴らす」(#149)。ON は常に鳴らし、OFF は OS に従う:
 * OS がアラームを止める時 (サイレント / アラーム許可なしの優先モード) だけ黙る。
 */
class DndSilenceTest {
    private val all = NotificationManager.INTERRUPTION_FILTER_ALL
    private val priority = NotificationManager.INTERRUPTION_FILTER_PRIORITY
    private val alarms = NotificationManager.INTERRUPTION_FILTER_ALARMS
    private val none = NotificationManager.INTERRUPTION_FILTER_NONE
    private val unknown = NotificationManager.INTERRUPTION_FILTER_UNKNOWN

    @Test
    fun `無視ONはOSの状態に関係なく鳴らす判断`() {
        listOf(all, priority, alarms, none, unknown).forEach { f ->
            listOf(true, false, null).forEach { pol ->
                assertFalse("filter=$f policy=$pol", AlarmService.silencedByDnd(true, f, pol))
            }
        }
    }

    @Test
    fun `無視OFFでもすべて許可なら鳴らす`() {
        assertFalse(AlarmService.silencedByDnd(false, all, null))
    }

    @Test
    fun `無視OFFでアラームのみなら鳴らす`() {
        assertFalse(AlarmService.silencedByDnd(false, alarms, null))
    }

    @Test
    fun `無視OFFで優先モードにアラーム許可があれば鳴らす`() {
        assertFalse(AlarmService.silencedByDnd(false, priority, true))
    }

    @Test
    fun `無視OFFで優先モードにアラーム許可がなければ黙る`() {
        assertTrue(AlarmService.silencedByDnd(false, priority, false))
    }

    @Test
    fun `無視OFFでサイレントなら黙る`() {
        assertTrue(AlarmService.silencedByDnd(false, none, null))
        assertTrue(AlarmService.silencedByDnd(false, none, true))
    }

    @Test
    fun `無視OFFで状態が取れない時は鳴らす側に倒す`() {
        assertFalse(AlarmService.silencedByDnd(false, unknown, null))
        assertFalse(AlarmService.silencedByDnd(false, null, null))
        assertFalse(AlarmService.silencedByDnd(false, priority, null))
    }

    @Test
    fun `チャンネルは無視ONで既存のbypassDnd側を使う`() {
        assertEquals(AlarmService.CHANNEL_ID, AlarmService.ringChannelId(vibrate = true, ignoreDnd = true))
        assertEquals(AlarmService.CHANNEL_ID_NOVIB, AlarmService.ringChannelId(vibrate = false, ignoreDnd = true))
        assertEquals(AlarmService.CHANNEL_ID_VIB_DND, AlarmService.ringChannelId(vibrate = true, ignoreDnd = false))
        assertEquals(AlarmService.CHANNEL_ID_NOVIB_DND, AlarmService.ringChannelId(vibrate = false, ignoreDnd = false))
    }
}
