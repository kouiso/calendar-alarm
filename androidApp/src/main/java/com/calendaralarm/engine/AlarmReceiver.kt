package com.calendaralarm.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * AlarmManager → 鳴動の入口。
 * setAlarmClock 経由のブロードキャストはバックグラウンド起動制限の適用外なので、
 * ここから鳴動フォアグラウンドサービスを即時起動できる。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val instanceId = intent.data?.lastPathSegment ?: return
        // サービス側でインスタンス情報をDB解決して鳴らす。即時起動が重要なので
        // goAsync ではなく startForegroundService を直接呼ぶ
        val service = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START
            data = Uri.parse("alarm://instance/$instanceId")
            putExtra(AlarmService.EXTRA_INSTANCE_ID, instanceId)
        }
        // BOOT_COMPLETED 直後など、FGS 起動自体が拒否されるコンテキストがあり得る。
        // ここで落とすとプロセス死亡=クラッシュループになるので、見逃しに逃がす。
        val pending = goAsync()
        try {
            context.startForegroundService(service)
        } catch (e: Exception) {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    (context.applicationContext as com.calendaralarm.CalendarAlarmApp)
                        .container.repository
                        .markMissed(instanceId, "鳴動サービス起動がOSに拒否: ${e.javaClass.simpleName}")
                }
            }
            MissedNotifier.post(context, null)
        } finally {
            pending.finish()
        }
    }

    companion object {
        const val ACTION_FIRE = "com.calendaralarm.action.FIRE"
    }
}
