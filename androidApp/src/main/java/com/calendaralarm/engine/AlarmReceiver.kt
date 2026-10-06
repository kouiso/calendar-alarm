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
        context.startForegroundService(service)
    }

    companion object {
        const val ACTION_FIRE = "com.calendaralarm.action.FIRE"
    }
}
