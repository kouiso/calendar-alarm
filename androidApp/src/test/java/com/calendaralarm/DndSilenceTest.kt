package com.calendaralarm

import android.app.NotificationManager
import com.calendaralarm.engine.AlarmService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「おやすみモードを無視」(#149)。ON は常に鳴らし、OFF はおやすみモード中だけ黙る。
 * 音は USAGE_ALARM なので、チャンネルの bypassDnd だけに任せると OFF でも鳴ってしまう。
 */
class DndSilenceTest {

    @Test
    fun `無視ONならおやすみモードの種類に関係なく鳴らす`() {
        listOf(
            NotificationManager.INTERRUPTION_FILTER_ALL,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            NotificationManager.INTERRUPTION_FILTER_NONE,
        ).forEach { assertFalse(AlarmService.silencedByDnd(ignoreDnd = true, interruptionFilter = it)) }
    }

    @Test
    fun `無視OFFはおやすみモード中だけ黙る`() {
        assertFalse(AlarmService.silencedByDnd(false, NotificationManager.INTERRUPTION_FILTER_ALL))
        assertTrue(AlarmService.silencedByDnd(false, NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertTrue(AlarmService.silencedByDnd(false, NotificationManager.INTERRUPTION_FILTER_ALARMS))
        assertTrue(AlarmService.silencedByDnd(false, NotificationManager.INTERRUPTION_FILTER_NONE))
    }

    @Test
    fun `状態が取れない時は鳴らす側に倒す`() {
        assertFalse(AlarmService.silencedByDnd(false, NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
    }

    @Test
    fun `チャンネルは無視ONで既存のbypassDnd側を使う`() {
        assertEquals(AlarmService.CHANNEL_ID, AlarmService.channelIdFor(vibrate = true, ignoreDnd = true))
        assertEquals(AlarmService.CHANNEL_ID_NOVIB, AlarmService.channelIdFor(vibrate = false, ignoreDnd = true))
        assertEquals(AlarmService.CHANNEL_ID_VIB_DND, AlarmService.channelIdFor(vibrate = true, ignoreDnd = false))
        assertEquals(AlarmService.CHANNEL_ID_NOVIB_DND, AlarmService.channelIdFor(vibrate = false, ignoreDnd = false))
    }
}
