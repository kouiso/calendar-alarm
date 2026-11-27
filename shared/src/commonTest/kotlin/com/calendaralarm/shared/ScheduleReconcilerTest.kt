package com.calendaralarm.shared

import com.calendaralarm.shared.bridge.SharedBridge
import com.calendaralarm.shared.logic.ScheduleReconciler
import com.calendaralarm.shared.logic.ScheduleReconciler.Row
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.AlarmState
import com.calendaralarm.shared.model.StandaloneAlarm
import kotlinx.serialization.json.Json
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

    /** アラームのスヌーズの子3つ (親は 有効・無効・無い)。表の同名の行と同じ入力 */
    private fun snoozeChildrenInput(): ScheduleReconciler.Input {
        val children = (1L..3L).map {
            Row(
                AlarmInstance(
                    id = "sa:$it:x:snz1", triggerAtMillis = now + hour / 2, title = "t",
                    kind = AlarmKind.STANDALONE, snoozeSeq = 1,
                ),
                AlarmState.PENDING,
            )
        }
        return ScheduleReconciler.Input(
            nowMillis = now,
            rowsAtRead = children,
            rowsNow = children,
            expanded = emptyList(),
            standalone = listOf(
                StandaloneAlarm(id = 1, hour = 6, minute = 0),
                StandaloneAlarm(id = 2, enabled = false, hour = 6, minute = 0),
            ),
        )
    }

    @Test
    fun SharedBridgeのreconcileは直接呼んだ結果と同じ() {
        val input = snoozeChildrenInput()
        val out = SharedBridge.reconcile(Json.encodeToString(ScheduleReconciler.Input.serializer(), input))
        val viaBridge = Json.decodeFromString(ScheduleReconciler.Decision.serializer(), out)
        assertEquals(ScheduleReconciler.reconcile(input), viaBridge)
        // 判定そのものも確かめる (有効な親の子だけ残る)
        assertEquals(listOf("sa:1:x:snz1"), viaBridge.arm.map { it.id })
        assertEquals(listOf("sa:2:x:snz1", "sa:3:x:snz1"), viaBridge.cancel)
    }

    @Test
    fun SharedBridgeのoverdueはoverdueIdsと同じ() {
        val rows = listOf(
            row("ev:a", now - 2 * hour, AlarmState.PENDING),
            row("ev:b", now - 2 * hour, AlarmState.FIRED),
            row("ev:c", now + hour, AlarmState.PENDING),
        )
        val req = SharedBridge.OverdueRequest(rows, now)
        val out = SharedBridge.overdue(Json.encodeToString(SharedBridge.OverdueRequest.serializer(), req))
        val ids = Json.decodeFromString(SharedBridge.OverdueResult.serializer(), out).ids
        assertEquals(ScheduleReconciler.overdueIds(rows, now), ids)
        assertEquals(listOf("ev:a"), ids)
    }

    @Test
    fun SharedBridgeのreconcileとoverdueは読めないJSONで空文字を返す() {
        assertEquals("", SharedBridge.reconcile("{"))
        assertEquals("", SharedBridge.overdue("{"))
    }
}
