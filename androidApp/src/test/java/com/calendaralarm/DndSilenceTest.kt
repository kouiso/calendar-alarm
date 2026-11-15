package com.calendaralarm

import android.app.NotificationManager
import com.calendaralarm.engine.AlarmService
import com.calendaralarm.engine.dndActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「おやすみモード中も鳴らす」(#149、盤 p149)。OFF は「おやすみモード中は鳴らさず、通知だけ残す」:
 * すべて許可以外のフィルタ (優先・アラームのみ・サイレント) の間は、アラーム許可の有無に関係なく鳴らさない。
 * ON は通知ポリシーの許可が無くても止めず、OS のおやすみモード設定に任せる (持ち主の決定 2026-11-15)。
 * 許可の有無は判定の入力に無い。
 */
class DndSilenceTest {
    private val all = NotificationManager.INTERRUPTION_FILTER_ALL
    private val priority = NotificationManager.INTERRUPTION_FILTER_PRIORITY
    private val alarms = NotificationManager.INTERRUPTION_FILTER_ALARMS
    private val none = NotificationManager.INTERRUPTION_FILTER_NONE
    private val unknown = NotificationManager.INTERRUPTION_FILTER_UNKNOWN

    private fun silenced(ignoreDnd: Boolean, filter: Int?) =
        AlarmService.silencedByDnd(ignoreDnd, filter)

    @Test
    fun `ONならおやすみモードのどのフィルタでも止めない`() {
        // 許可が無い ON もここを通る。止めるのは利用者が自分で OFF にした時だけ
        listOf(all, priority, alarms, none, unknown, null).forEach { f ->
            assertFalse("filter=$f", silenced(true, f))
        }
    }

    @Test
    fun `OFFで優先モードなら鳴らさない`() {
        // 優先モードの「アラーム」許可の有無は silencedByDnd の入力に無い
        assertTrue(silenced(false, priority))
    }

    @Test
    fun `OFFでアラームのみなら鳴らさない`() {
        assertTrue(silenced(false, alarms))
    }

    @Test
    fun `OFFでサイレントなら鳴らさない`() {
        assertTrue(silenced(false, none))
    }

    @Test
    fun `OFFでもおやすみモードでなければ鳴らす`() {
        assertFalse(silenced(false, all))
    }

    @Test
    fun `状態が取れない時は鳴らす側に倒す`() {
        assertFalse(silenced(false, unknown))
        assertFalse(silenced(false, null))
        assertFalse(silenced(false, 99))
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
