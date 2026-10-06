package com.calendaralarm.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.calendaralarm.CalendarAlarmApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 時刻・タイムゾーン・日付の変更で再展開。
 * 単発アラームのローカル時刻は TZ 変更で絶対時刻が変わるため、
 * 展開し直して差分適用しないと古い時刻で鳴ってしまう。
 */
class TimeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext as CalendarAlarmApp
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.container.repository.resync("time:$action")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
