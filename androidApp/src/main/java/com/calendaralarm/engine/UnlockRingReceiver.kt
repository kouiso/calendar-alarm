package com.calendaralarm.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * 「ロック解除までミュート」アラームの遅延鳴動トリガ。
 * ACTION_USER_PRESENT はマニフェスト登録できないため実行時登録する。
 * ロック解除された時点で、保留中のインスタンスへ AlarmReceiver.ACTION_FIRE を再送する。
 */
object UnlockRingReceiver {

    private val deferred = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var registered = false

    fun defer(context: Context, instanceId: String) {
        deferred += instanceId
        ensureRegistered(context.applicationContext)
    }

    /** テスト/デバッグ用に留保中かを確認する。 */
    fun isDeferred(instanceId: String) = instanceId in deferred

    @Synchronized
    private fun ensureRegistered(context: Context) {
        if (registered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_USER_PRESENT) return
                val ids = deferred.toList()
                deferred.clear()
                ids.forEach { id ->
                    ctx.sendBroadcast(
                        Intent(ctx, AlarmReceiver::class.java)
                            .setAction(AlarmReceiver.ACTION_FIRE)
                            .setData(android.net.Uri.parse("alarm://instance/$id")),
                    )
                }
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT), flags)
        registered = true
    }
}
