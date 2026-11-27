package com.calendaralarm.shared.logic

import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.AlarmState
import com.calendaralarm.shared.model.RepeatMode
import com.calendaralarm.shared.model.StandaloneAlarm
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * 再同期 (resync) で「残す・予約する・取り消す・見逃しにする」行を決める純粋関数 (#304)。
 * 行の状態と展開の結果だけを見る。DB と OS の予約への適用は呼び出し側 (Android は AlarmRepository) が行う。
 *
 * 呼び出しは2段階。
 * 1. [overdueIds] で期限切れの PENDING を選び、呼び出し側が MISSED にする (カレンダーを読む前)
 * 2. 展開 (AlarmExpander) の後、書き戻す直前に行を読み直して [reconcile] を呼ぶ
 * [reconcile] は MISSED にし損ねた行を特別に扱わない。1 の後に行を読む順番は呼び出し側が守る。
 */
object ScheduleReconciler {

    /** 再同期が同じ id を予約し直さない状態。ユーザーが止めた・スヌーズした・鳴っている意味を守る */
    val NO_REVIVE_STATES: Set<AlarmState> = setOf(
        AlarmState.FIRED, AlarmState.DISMISSED, AlarmState.SNOOZED, AlarmState.MISSED, AlarmState.SKIPPED,
    )

    /** 予約の行 (鳴動の中身と状態)。状態か種類が読めない行は呼び出し側が渡さない */
    @Serializable
    data class Row(val instance: AlarmInstance, val state: AlarmState)

    @Serializable
    data class Input(
        val nowMillis: Long,
        /** 展開の前に読んだ全部の行 ([overdueIds] の結果を MISSED にした後) */
        val rowsAtRead: List<Row>,
        /** 書き戻す直前に読み直した全部の行 */
        val rowsNow: List<Row>,
        /** 展開の結果。アラーム → 予定の順。すべてミュートと既定のスヌーズ分数は呼び出し側で済ませる */
        val expanded: List<AlarmInstance>,
        /** 単発・繰り返しアラームの本体 (無効なものも全部) */
        val standalone: List<StandaloneAlarm> = emptyList(),
        /** 予定の PENDING を、展開に無くても残すか (カレンダーの読み取りに失敗した時) */
        val keepEventRows: Boolean = false,
        /** 今も鳴らす予定の instanceKey */
        val liveEventKeys: Set<String> = emptySet(),
        /** PENDING なのに OS に予約が無いと分かっている行の id */
        val unarmedPendingIds: Set<String> = emptySet(),
        /** 一度も予約していない予定 (ev:) の過ぎた鳴動を SKIPPED にするか (false なら今すぐ鳴らす) */
        val skipUnarmedPastEvents: Boolean = false,
    )

    @Serializable
    data class Decision(
        /** OS の予約を取り消し、行を CANCELLED にする id */
        val cancel: List<String>,
        /** 再同期の後に PENDING である行。鳴動時刻の早い順。fireNow と skip の行は入らない */
        val arm: List<AlarmInstance>,
        /** arm のうち、OS に予約が無いか、予約の時刻が変わった行の id */
        val changedIds: Set<String>,
        /** 行は元の時刻で PENDING にし、OS には今の時刻で予約する */
        val fireNow: List<AlarmInstance>,
        /** 行を作って SKIPPED にする。OS には予約しない */
        val skip: List<AlarmInstance>,
        /** 展開にあったが、同じ id の行が蘇らせない状態なので落とした物 */
        val suppressed: List<AlarmInstance>,
    )

    /** 鳴動時刻が nowMillis - AlarmExpander.FIRE_GRACE より前の PENDING の id。rows の並びのまま返す */
    fun overdueIds(rows: List<Row>, nowMillis: Long): List<String> {
        val cutoff = nowMillis - AlarmExpander.FIRE_GRACE.inWholeMilliseconds
        return rows
            .filter { it.state == AlarmState.PENDING && it.instance.triggerAtMillis < cutoff }
            .map { it.instance.id }
    }

    fun reconcile(input: Input): Decision {
        // 終端行は先に見る。同 id の蘇生防止と、「1回のみ」の消費判定の両方で使う
        val noReviveRowsAtRead = input.rowsAtRead.filter { it.state in NO_REVIVE_STATES }
        val noReviveAtRead = noReviveRowsAtRead.map { it.instance.id }.toSet()

        // 「1回のみ」は一度終端に達したら二度と発行しない。展開は毎回「次の in-window 日付」へ
        // 新 id を発行するので、終端行が残る限り約束が日跨ぎで破られて毎日鳴り続ける。
        // 繰り返しは使い切らない (止めた回だけ、同じ id の蘇生防止で外れる)
        val consumedAlarmIds = noReviveRowsAtRead.mapNotNull { it.instance.standaloneAlarmId }.toSet()
        val onceIds = input.standalone
            .filter { it.effectiveRepeatMode() == RepeatMode.ONCE }
            .map { it.id }
            .toSet()

        val suppressed = mutableListOf<AlarmInstance>()
        val desired = mutableListOf<AlarmInstance>()
        for (inst in input.expanded) {
            val alarmId = inst.standaloneAlarmId
            if (alarmId != null && alarmId in consumedAlarmIds && alarmId in onceIds) continue
            if (inst.id in noReviveAtRead) suppressed += inst else desired += inst
        }

        // タイマー・スヌーズなど展開が再生しない予約は「理想状態」へ持ち越す。
        // 持ち越さないと差分計算で毎回キャンセルされてしまう
        val pendingRowsAtRead = input.rowsAtRead.filter { it.state == AlarmState.PENDING }
        val desiredIds = desired.map { it.id }.toSet()
        desired += pendingRowsAtRead
            .map { it.instance }
            .filter { it.id !in desiredIds && carriesOver(it, input, desiredIds) }

        // 書き戻す直前の行で確かめる (#232)。蘇生させない状態になった行と、読んだ時に PENDING で
        // 今は PENDING でない行 (停止が取り消したスヌーズの子など) は書き戻さない。
        // 読んだ後にできた PENDING の行は、この再同期では触らない (次の再同期で扱う)
        val stateNow = input.rowsNow.associate { it.instance.id to it.state }
        val noReviveNow = input.rowsNow.filter { it.state in NO_REVIVE_STATES }.map { it.instance.id }.toSet()
        val stillPending = pendingRowsAtRead.filter { stateNow[it.instance.id] == AlarmState.PENDING }
        val stillPendingIds = stillPending.map { it.instance.id }
        val leftPending = pendingRowsAtRead.map { it.instance.id }.toSet() - stillPendingIds.toSet()
        val finalDesired = desired.filterNot { it.id in noReviveNow || it.id in leftPending }

        val armed = stillPending
            .filter { it.instance.id !in input.unarmedPendingIds }
            .associate { it.instance.id to it.instance.triggerAtMillis }
        val plan = AlarmPlanner.plan(armed, finalDesired, Instant.fromEpochMilliseconds(input.nowMillis))

        val finalIds = finalDesired.map { it.id }.toSet()
        val cancel = stillPendingIds.filter { it !in finalIds }

        // 一度も予約していない予定 (ev:) の過ぎた鳴動は「元カレンダー側が既に通知した時刻」。
        // 後追いで不意に鳴らさず、次回展開からも外れるよう終端にする。他の過ぎた鳴動は今すぐ鳴らす
        val (skip, fireNow) = plan.toFireNow.partition {
            input.skipUnarmedPastEvents && it.id !in armed && it.id.startsWith("ev:")
        }
        val fireNowIds = plan.toFireNow.map { it.id }.toSet()
        // 近い順に予約する。AlarmManager の同時予約上限に触れた時、最も遠い予約から落ちる退化にするため
        val arm = finalDesired.filter { it.id !in fireNowIds }.sortedBy { it.triggerAtMillis }
        val changedIds = plan.toSchedule.map { it.id }.toSet() - fireNowIds

        return Decision(
            cancel = cancel,
            arm = arm,
            changedIds = changedIds,
            fireNow = fireNow,
            skip = skip,
            suppressed = suppressed,
        )
    }

    /** 展開に無い PENDING の行を、理想状態へ持ち越すか */
    private fun carriesOver(inst: AlarmInstance, input: Input, desiredIds: Set<String>): Boolean = when {
        // TIMER / NOTIFICATION は即時発火の adhoc。展開に無くても保持
        inst.kind == AlarmKind.TIMER || inst.kind == AlarmKind.NOTIFICATION -> true
        // スヌーズ中の子は「親が今も理想状態にある」場合だけ持ち越す。
        // 親アラームの無効化・削除・予定ミュート後にスヌーズだけ鳴るのを防ぐ
        inst.snoozeSeq > 0 -> {
            // 親の「鳴動意思」を見る。親インスタンス id の照合では猶予窓 (60分) を跨ぐスヌーズが
            // 誤殺されるため、sa: はアラームの有効性、ev: は予定の存続と非ミュートを見る
            val root = inst.id.substringBefore(":snz")
            when {
                root.startsWith("sa:") ->
                    root.removePrefix("sa:").substringBefore(":")
                        .toLongOrNull()?.let { aid ->
                            input.standalone.any { it.id == aid && it.enabled }
                        } == true
                // カレンダー読み取り失敗時は平の EVENT 予約と同じく保守側に倒す。
                // id の末尾は ":b<分>" (開始基点) か ":d<分>" (終日の現地0時基点, #382) なので、
                // 最後の区切りを外せば予定のキーに戻る
                root.startsWith("ev:") ->
                    input.keepEventRows || root in desiredIds ||
                        root.removePrefix("ev:").substringBeforeLast(":") in input.liveEventKeys
                else -> input.keepEventRows && inst.kind == AlarmKind.EVENT
            }
        }
        else -> input.keepEventRows && inst.kind == AlarmKind.EVENT
    }
}
