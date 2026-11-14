package com.calendaralarm.shared.logic

import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.EventOverride

/**
 * 予定ごとの指定 (鳴らさない・何分前など) を、予定の時刻が動いても引き継ぐ (#243)。
 *
 * 指定のキーは instanceKey (「カレンダー:予定:開始」) なので、時刻が動くとキーが変わり指定が外れる。
 * 再同期で読んだ予定と突き合わせ、次のように整える。
 * - 引き継ぎ: 同じ「カレンダー:予定」で、指定が付いているのに今は無い回 (はぐれた指定) と、
 *   指定の無い今の回が同じ数だけある時、開始の早い順に組にしてキーを付け替える。
 *   1 回だけの予定の時刻が動いた時と、繰り返しの全部の回が同じだけずれた時がこれに当たる。
 *   数が合わない時は、どれがどれか決められないので付け替えない
 * - 削除: 引き継げなかったはぐれた指定のうち、開始が [keepPastMillis] より前のもの (終わった回) は消す。
 *   まだ先の回は、読み取り範囲の外へ出ただけかもしれないので残す
 *
 * カレンダーの読み取りに成功した時だけ呼ぶこと (失敗時に呼ぶと全部がはぐれて見える)。
 */
object OverrideCarry {
    data class Result(
        /** 付け替えた後の指定。展開にはこれを使う */
        val overrides: Map<String, EventOverride>,
        /** 古いキー → 新しいキー */
        val moved: Map<String, String>,
        /** 消すキー (終わった回の指定) */
        val dropped: Set<String>,
    )

    fun reconcile(
        overrides: Map<String, EventOverride>,
        events: List<CalendarEvent>,
        nowMillis: Long,
        keepPastMillis: Long,
    ): Result {
        val currentKeys = events.map { it.instanceKey }.toSet()
        // はぐれた指定。開始が数で読めないキーは触らない
        val orphans = overrides.keys
            .filter { it !in currentKeys }
            .mapNotNull { key -> startOf(key)?.let { Triple(key.substringBeforeLast(':'), it, key) } }
        val unmatchedBySeries = events
            .filter { it.instanceKey !in overrides }
            .groupBy { "${it.calendarId}:${it.id}" }

        val moved = mutableMapOf<String, String>()
        orphans.groupBy { it.first }.forEach { (series, list) ->
            val targets = unmatchedBySeries[series].orEmpty().sortedBy { it.startMillis }
            if (targets.size != list.size) return@forEach
            list.sortedBy { it.second }.zip(targets).forEach { (orphan, ev) -> moved[orphan.third] = ev.instanceKey }
        }
        val dropped = orphans
            .filter { it.third !in moved && it.second < nowMillis - keepPastMillis }
            .map { it.third }
            .toSet()
        val result = overrides.toMutableMap()
        moved.forEach { (old, new) -> result.remove(old)?.let { result[new] = it } }
        dropped.forEach { result.remove(it) }
        return Result(result, moved, dropped)
    }

    private fun startOf(key: String): Long? =
        key.substringAfterLast(':', "").toLongOrNull()
}
