package com.calendaralarm

import android.app.NotificationManager
import com.calendaralarm.engine.dndAllowsAlarms
import com.calendaralarm.engine.policyAllowsAlarms
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

    @Test
    fun `知らないフィルタの値は通す側に倒す`() {
        assertTrue(dndAllowsAlarms(99, null))
    }

    @Test
    fun `Android8の優先モードはアラームの項目が無くても通す`() {
        // API 26/27 のポリシーには「アラーム」の項目が無い。無いのを「許可なし」と読むと、OFF で全部のアラームが黙って鳴らなくなる
        assertTrue(policyAllowsAlarms(sdkInt = 27, priorityCategories = 0))
        assertTrue(policyAllowsAlarms(sdkInt = 26, priorityCategories = 0))
        assertFalse(policyAllowsAlarms(sdkInt = 28, priorityCategories = 0))
        assertTrue(policyAllowsAlarms(sdkInt = 28, priorityCategories = NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS))
    }
}
