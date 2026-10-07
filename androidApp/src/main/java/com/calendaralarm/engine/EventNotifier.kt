package com.calendaralarm.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.calendaralarm.R
import com.calendaralarm.shared.model.AlarmInstance

/**
 * NOTIFY 配信: 全画面鳴動ではなく通常のヘッズアップ通知を出す
 * (元アプリの「通知のみ」アクション)。鳴動画面は開かず、
 * タップでアプリを開き、消去で DISMISS を記録する。
 */
object EventNotifier {

    private const val CHANNEL_ID = "event_notify_v1"
    const val ACTION_DISMISS = "com.calendaralarm.action.NOTIFY_DISMISS"

    fun post(context: Context, instance: AlarmInstance) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "予定の通知", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "通知のみアクションで配信される予定通知"
            },
        )
        val open = PendingIntent.getActivity(
            context, instance.id.hashCode(),
            Intent(context, com.calendaralarm.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = PendingIntent.getBroadcast(
            context, instance.id.hashCode(),
            Intent(context, AlarmReceiver::class.java).apply {
                action = ACTION_DISMISS
                data = android.net.Uri.parse("alarm://instance/${instance.id}")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mgr.notify(
            instance.id.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(instance.title)
                .setContentText(
                    when {
                        instance.minutesBefore <= 0 -> "開始時刻です"
                        else -> "${instance.minutesBefore}分前です"
                    },
                )
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .setDeleteIntent(dismiss)
                .build(),
        )
    }

    fun cancel(context: Context, instanceId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(instanceId.hashCode())
    }
}
