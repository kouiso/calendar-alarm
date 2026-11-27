package com.calendaralarm.data

import android.annotation.SuppressLint
import android.content.Context
import java.security.MessageDigest

/**
 * 通知アラームを鳴らした時の、通知の内容の印 (#384)。
 * 止めた直後の出し直しが、同じ知らせか新しい知らせかを比べるために覚える。題と本文そのものは保存しない
 * (確認コードなどが端末に平文で残らないようにするため)。
 * 印は `題 + NUL (\u0000, 設計書の ^@) + 本文` の SHA-256。区切りを変えると保存済みの印が全て食い違い、
 * 次の出し直しが鳴ってしまうので変えない。
 * 値の形は `<保存した時刻のミリ秒>|<印>`。読み書きは鳴動の経路から呼ぶので同期で確定させる
 */
class NotificationContentStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    /** [id] (`nf:…`) の鳴動を予約した時の印を書く。[MAX_ENTRIES] 件を超えたら保存した時刻が古い順に消す */
    // 鳴動の経路から呼ぶため apply ではなく commit で同期確定させる (#300 の方針どおり、意図した使い方)
    @SuppressLint("ApplySharedPref")
    fun put(id: String, contentKey: String, nowMillis: Long) {
        synchronized(lock) {
            val edit = prefs.edit()
            edit.putString(id, "$nowMillis|$contentKey")
            val others = prefs.all.filterKeys { it != id }
            val overflow = others.size + 1 - MAX_ENTRIES
            if (overflow > 0) {
                others.entries
                    .sortedBy { savedAt(it.value as? String) ?: Long.MIN_VALUE }
                    .take(overflow)
                    .forEach { edit.remove(it.key) }
            }
            edit.commit()
        }
    }

    /** [id] に保存した印。無ければ null。値の形が壊れていても null */
    fun get(id: String): String? = synchronized(lock) {
        val raw = prefs.getString(id, null) ?: return@synchronized null
        val sep = raw.indexOf('|')
        if (sep < 0 || raw.substring(0, sep).toLongOrNull() == null) return@synchronized null
        raw.substring(sep + 1)
    }

    private fun savedAt(raw: String?): Long? = raw?.substringBefore('|', "")?.toLongOrNull()

    companion object {
        private const val PREFS = "notification_alarm_content"
        internal const val MAX_ENTRIES = 256

        /** 題と本文から印を作る。文字は整えず (意味は見ず)、SHA-256 の16進64文字を返す */
        fun keyOf(title: String, text: String): String {
            val bytes = "$title\u0000$text".toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
        }
    }
}
