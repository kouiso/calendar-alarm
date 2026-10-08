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
                        // NOTIFY でも通知権限が無いと何も届かない → 鳴る側に倒す
                        val notificationsOk =
                            androidx.core.app.NotificationManagerCompat.from(context)
                                .areNotificationsEnabled()
                        when {
                            instance == null -> Unit
                            instance.delivery ==
                                com.calendaralarm.shared.model.EventAction.NOTIFY &&
                                notificationsOk -> {
                                repo.onFired(instanceId)
                                runCatching { EventNotifier.post(context, instance) }
                            }
                            else -> {
                                try {
                                    context.startForegroundService(
                                        ringingIntent(context, instanceId))
                                } catch (e: Exception) {
                                    // FGS起動自体が拒否されるコンテキストがあり得る。
                                    // 見逃しに逃がす (同じ放送枠内で記録をコミットする)
                                    repo.markMissed(
                                        instanceId,
                                        "鳴動サービス起動がOSに拒否: ${e.javaClass.simpleName}",
                                    )
                                    runCatching { MissedNotifier.post(context, null) }
                                }
                            }
                        }
                        return@launch
                    } finally {
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

    private fun ringingIntent(context: Context, instanceId: String) =
        Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START
            data = Uri.parse("alarm://instance/$instanceId")
            putExtra(AlarmService.EXTRA_INSTANCE_ID, instanceId)
        }

    companion object {
        const val ACTION_FIRE = "com.calendaralarm.action.FIRE"
    }
}
