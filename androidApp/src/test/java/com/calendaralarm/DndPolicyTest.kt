package com.calendaralarm

import android.app.NotificationManager
import com.calendaralarm.engine.dndAllowsAlarms
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OS がおやすみモード中にアラーム音を通すかの判定 (#149)。トラブルシューティング画面と鳴動判断が共有する。 */
class DndPolicyTest {
    @Test
    fun `すべて許可とアラームのみは通す`() {
        assertTrue(dndAllowsAlarms(NotificationManager.INTERRUPTION_FILTER_ALL, null))
        assertTrue(dndAllowsAlarms(NotificationManager.INTERRUPTION_FILTER_ALARMS, false))
    }

    @Test
    fun `優先モードはアラーム許可の有無で決まる`() {
        assertTrue(dndAllowsAlarms(NotificationManager.INTERRUPTION_FILTER_PRIORITY, true))
        assertFalse(dndAllowsAlarms(NotificationManager.INTERRUPTION_FILTER_PRIORITY, false))
    }

    @Test
    fun `サイレントはポリシーに関係なく通さない`() {
        assertFalse(dndAllowsAlarms(NotificationManager.INTERRUPTION_FILTER_NONE, true))
        assertFalse(dndAllowsAlarms(NotificationManager.INTERRUPTION_FILTER_NONE, null))
    }

    @Test
    fun `読めない時は通す側に倒す`() {
        assertTrue(dndAllowsAlarms(null, null))
        assertTrue(dndAllowsAlarms(NotificationManager.INTERRUPTION_FILTER_UNKNOWN, null))
        assertTrue(dndAllowsAlarms(NotificationManager.INTERRUPTION_FILTER_PRIORITY, null))
    }
}
