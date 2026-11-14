package com.calendaralarm.shared.logic

import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.EventOverride

/**
 * 予定ごとの指定 (鳴らさない・何分前など) を、予定の時刻が動いても引き継ぐ (#243)。
 *
 * 指定のキーは instanceKey (「カレンダー:予定:開始」) なので、時刻が動くとキーが変わり指定が外れる。
 * 再同期で読んだ予定と突き合わせ、次のように整える。指定を別の回へ付け間違えると、
 * 利用者が見てもいない回が鳴らなくなるので、確かな時だけ付け替える。
 * - はぐれた指定: 指定が付いているのに、今は同じキーの回が無いもの
 * - 引き継ぎ (どちらかの時だけ):
 *   - 1 回だけの予定: 同じ「カレンダー:予定」の回が今は 1 つだけで、はぐれた指定も 1 つだけ
 *   - 繰り返しの全体のずれ: 同じ「カレンダー:予定」の今の回が全部指定無しで、はぐれた指定と同じ数あり、
 *     開始の早い順に組にした時の時刻の差が全部同じ
 *   繰り返しの 1 回が消えて別の回が読み取り範囲に入った時などは、どれがどれか決められないので付け替えない
 * - 開始が [keepPastMillis] より前のはぐれた指定 (終わった回) は付け替えず、消す。
 *   まだ先の回のはぐれた指定は、読み取り範囲の外へ出ただけかもしれないので残す
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
        val cutoff = nowMillis - keepPastMillis
        val seriesInstances = events.groupBy { "${it.calendarId}:${it.id}" }
        orphans.filter { it.second >= cutoff }.groupBy { it.first }.forEach { (series, list) ->
            val all = seriesInstances[series].orEmpty()
            val targets = unmatchedBySeries[series].orEmpty().sortedBy { it.startMillis }
            val sorted = list.sortedBy { it.second }
            val single = all.size == 1 && sorted.size == 1 && targets.size == 1
            val wholeShift = sorted.size >= 2 && targets.size == all.size && targets.size == sorted.size &&
                sorted.zip(targets).map { (o, ev) -> ev.startMillis - o.second }.distinct().size == 1
            if (!single && !wholeShift) return@forEach
            sorted.zip(targets).forEach { (orphan, ev) -> moved[orphan.third] = ev.instanceKey }
        }
        val dropped = orphans
            .filter { it.third !in moved && it.second < cutoff }
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
