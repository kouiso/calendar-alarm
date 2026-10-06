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
 * 再起動・パッケージ更新後の復元。
 * AlarmManager の予約は再起動で消えるため、DB に残る PENDING を再主張し、
 * 期限切れは MISSED に落とす。カレンダー自体の再読込は SyncWorker に任せる。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                "android.intent.action.QUICKBOOT_POWERON",
            )
        ) return

        val app = context.applicationContext as CalendarAlarmApp
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.container.repository.resync("boot:$action")
                app.container.repository.markMissed()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
