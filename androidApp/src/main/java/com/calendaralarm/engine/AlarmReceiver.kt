package com.calendaralarm.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AlarmManager → 鳴動の入口。
 * setAlarmClock 経由のブロードキャストはバックグラウンド起動制限の適用外なので、
 * ここから鳴動フォアグラウンドサービスを即時起動できる。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> {
                val instanceId = intent.data?.lastPathSegment ?: return
                // NOTIFY 配信はサービスを立てず通知だけ出す。DB からの解決が要る
                // ため goAsync で coroutine に逃がす (読み1件なら放送枠内で間に合う)。
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    val repo = (context.applicationContext as com.calendaralarm.CalendarAlarmApp)
                        .container.repository
                    try {
                        val instance = repo.instanceById(instanceId)
                        when {
                            instance == null -> Unit
                            instance.delivery ==
                                com.calendaralarm.shared.model.EventAction.NOTIFY -> {
                                repo.onFired(instanceId)
                                runCatching { EventNotifier.post(context, instance) }
                            }
                            else -> startRingingService(context, instanceId)
                        }
                        return@launch
                    } finally {
                        // startForegroundService は同期なのでここで畳んでよい
                        pending.finish()
                    }
                }
            }
            EventNotifier.ACTION_DISMISS -> {
                val instanceId = intent.data?.lastPathSegment ?: return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        runCatching {
                            (context.applicationContext as com.calendaralarm.CalendarAlarmApp)
                                .container.repository.onDismissed(instanceId)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private fun startRingingService(context: Context, instanceId: String) {
        val service = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START
            data = Uri.parse("alarm://instance/$instanceId")
            putExtra(AlarmService.EXTRA_INSTANCE_ID, instanceId)
        }
        // BOOT_COMPLETED 直後など、FGS 起動自体が拒否されるコンテキストがあり得る。
        // ここで落とすとプロセス死亡=クラッシュループになるので、見逃しに逃がす。
        try {
            context.startForegroundService(service)
        } catch (e: Exception) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    (context.applicationContext as com.calendaralarm.CalendarAlarmApp)
                        .container.repository
                        .markMissed(instanceId, "鳴動サービス起動がOSに拒否: ${e.javaClass.simpleName}")
                }
                runCatching { MissedNotifier.post(context, null) }
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.calendaralarm.action.FIRE"
    }
}
