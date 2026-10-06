package com.calendaralarm.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.calendaralarm.MainActivity
import com.calendaralarm.shared.model.AlarmInstance

/**
 * AlarmManager への予約/取消。PendingIntent の同一性は
 * data URI (alarm://<instanceId>) で決まるため、requestCode 衝突は起きない。
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /**
     * 予約。USE_EXACT_ALARM が取り下げられた等で SecurityException が来た時は
     * false を返す (呼び出し側が監査してスキップ判断する)。
     */
    fun schedule(instance: AlarmInstance): Boolean {
        val alarmPi = fireIntent(instance.id)
        val showPi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(instance.triggerAtMillis, showPi),
                alarmPi,
            )
            true
        } catch (e: Exception) {
            // SecurityException (権限失効) に限らず、OEM 差異で
            // IllegalStateException 等が飛ぶ場合がある。ここで投げると
            // resync 全体が中断して他の予約まで巻き込むので false に倒す。
            false
        }
    }

    fun cancel(instanceId: String) {
        alarmManager.cancel(fireIntent(instanceId))
    }

    fun canScheduleExact(): Boolean =
        // API 31 未満はメソッド自体が存在せず、正確アラームも常時許可される
        Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()

    /** 鳴動ブロードキャストの PendingIntent。id で完全に一意。 */
    private fun fireIntent(instanceId: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            data = Uri.parse("alarm://instance/$instanceId")
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

}
