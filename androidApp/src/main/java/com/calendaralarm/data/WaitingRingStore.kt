package com.calendaralarm.data

import android.content.Context

/**
 * 同時刻の後発に押し出され、鳴らし直しを待つ鳴動の id (#84)。
 * DB の状態だけでは「通知だけの予定が通知権限なしで鳴動に回った」場合を見分けられないので、別に覚える。
 * プロセスが死んでも鳴らし直せるよう端末に保存する。読み書きは鳴動の経路から呼ぶので同期で確定させる
 */
class WaitingRingStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun add(id: String) = synchronized(lock) {
        prefs.edit().putStringSet(KEY, all() + id).commit()
    }

    fun remove(id: String) = synchronized(lock) {
        val now = all()
        if (id in now) prefs.edit().putStringSet(KEY, now - id).commit()
    }

    // getStringSet が返す集合は書き換えてはいけないので写しを返す
    fun all(): Set<String> = synchronized(lock) { prefs.getStringSet(KEY, emptySet()).orEmpty().toSet() }

    private companion object {
        const val PREFS = "waiting_rings"
        const val KEY = "ids"
    }
}
