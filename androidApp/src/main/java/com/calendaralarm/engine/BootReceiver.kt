package com.calendaralarm.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.calendaralarm.data.sync.SyncWorker

/**
 * 再起動・パッケージ更新後の復元。
 * AlarmManager の予約は再起動で消えるため、DB に残る PENDING を再主張し、
 * 期限切れは MISSED に落とす。
 * 処理自体は BroadcastReceiver の ~10 秒枠に乗りきらない可能性があるため、
 * WorkManager の単発ジョブへ委譲する (WorkManager はブート直後でも確実に走る)。
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
        SyncWorker.enqueueNow(context, "boot:$action")
    }
}
