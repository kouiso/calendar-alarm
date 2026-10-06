package com.calendaralarm.shared.logic

import com.calendaralarm.shared.model.AlarmInstance
import kotlinx.datetime.Instant

/**
 * 「DB に記録済みの予約」と「今回展開した理想状態」の差分から
 * 追加予約・キャンセル対象を決める純粋関数。
 * OS 側はこの結果をそのまま AlarmManager / DB に反映する。
 */
object AlarmPlanner {

    data class Plan(
        /** 新規に予約すべきインスタンス。 */
        val toSchedule: List<AlarmInstance>,
        /** AlarmManager と DB から取り消すべき既存インスタンス id。 */
        val toCancel: List<String>,
        /** triggerAt が過去（グレース幅内）のインスタンス = 直ちに鳴らすべきもの。 */
        val toFireNow: List<AlarmInstance>,
    )

    /**
     * @param scheduled 現行予約 (id → triggerAtMillis)。鳴動済み/取り消し済みは含めない。
     * @param desired 展開された理想状態。
     * @param now 現在時刻。
     */
    fun plan(
        scheduled: Map<String, Long>,
        desired: List<AlarmInstance>,
        now: Instant,
    ): Plan {
        val desiredById = desired.associateBy { it.id }
        // id が同じでも triggerAt が変わっていたら予約し直す
        val toSchedule = desired.filter { scheduled[it.id] != it.triggerAtMillis }
        val toCancel = scheduled.keys.filter { it !in desiredById }
        val toFireNow = toSchedule.filter { it.triggerAtMillis <= now.toEpochMilliseconds() }
        return Plan(toSchedule = toSchedule, toCancel = toCancel, toFireNow = toFireNow)
    }
}
