package com.calendaralarm.shared

import com.calendaralarm.shared.logic.ScheduleReconciler
import com.calendaralarm.shared.logic.ScheduleReconciler.Row
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.AlarmState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 再同期の判定 (#304)。表 (testdata/shared-decisions.json の reconcile) で押さえた判定の外側、
 * 期限切れの選び方・SharedBridge の入口・個別の端の場合を押さえる。
 */
class ScheduleReconcilerTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun row(id: String, trigger: Long, state: AlarmState) = Row(
        AlarmInstance(id = id, triggerAtMillis = trigger, title = "t", kind = AlarmKind.EVENT),
        state,
    )

    @Test
    fun 鳴動時刻が猶予ちょうどのPENDINGは期限切れにしない() {
        val rows = listOf(row("ev:a", now - hour, AlarmState.PENDING))
        assertEquals(emptyList(), ScheduleReconciler.overdueIds(rows, now))
    }

    @Test
    fun 鳴動時刻が猶予より1ミリ秒前のPENDINGは期限切れ() {
        val rows = listOf(row("ev:a", now - hour - 1, AlarmState.PENDING))
        assertEquals(listOf("ev:a"), ScheduleReconciler.overdueIds(rows, now))
    }

    @Test
    fun PENDINGでない行は期限切れにしない() {
        val rows = listOf(
            row("ev:a", now - 2 * hour, AlarmState.FIRED),
            row("ev:b", now - 2 * hour, AlarmState.CANCELLED),
        )
        assertEquals(emptyList(), ScheduleReconciler.overdueIds(rows, now))
    }
}
