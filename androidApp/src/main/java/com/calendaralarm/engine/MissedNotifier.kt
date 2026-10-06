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
            NotificationChannel(CHANNEL_ID, "鳴らせなかったアラーム", NotificationManager.IMPORTANCE_HIGH),
        )
        val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val text = if (alarmTitle.isNullOrBlank()) {
            "鳴動サービスを起動できなかったため、アラームを鳴らせませんでした"
        } else {
            "「$alarmTitle」を鳴らせませんでした"
        }
        mgr.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle("鳴らせなかったアラーム")
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
            NotificationChannel(CHANNEL_ID, "鳴らせなかったアラーム", NotificationManager.IMPORTANCE_HIGH),
        )
        val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        mgr.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle("鳴らせなかったアラーム")
                .setContentText("端末の停止等により ${count}件のアラームを鳴らせませんでした")
                .setAutoCancel(true)
                .apply { openApp?.let(::setContentIntent) }
                .build(),
        )
    }
}
