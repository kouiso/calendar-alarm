package com.calendaralarm.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.calendaralarm.R

/**
 * 「鳴らせなかった」ことをユーザーへ伝えるフォールバック通知。
 * BOOT_COMPLETED 経路など、OS が鳴動 FGS (mediaPlayback) の起動を
 * 拒否した時に、クラッシュせず静かに MISS へ逃がすための出口。
 */
object MissedNotifier {

    private const val CHANNEL_ID = "missed_v1"
    private const val NOTIFICATION_ID = 2

    fun post(context: Context, alarmTitle: String?) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.missed_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val text = if (alarmTitle.isNullOrBlank()) {
            context.getString(R.string.missed_service_failed)
        } else {
            context.getString(R.string.missed_one, alarmTitle)
        }
        mgr.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(context.getString(R.string.missed_channel))
                .setContentText(text)
                .setAutoCancel(true)
                .apply { openApp?.let(::setContentIntent) }
                .build(),
        )
    }

    /** 端末停止等で時刻を過ぎたまま MISSED 化した件数を伝える通知。 */
    fun postMissed(context: Context, count: Int) {
        if (count <= 0) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.missed_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        mgr.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(context.getString(R.string.missed_channel))
                .setContentText(context.getString(R.string.missed_count, count))
                .setAutoCancel(true)
                .apply { openApp?.let(::setContentIntent) }
                .build(),
        )
    }
}
