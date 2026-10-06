package com.calendaralarm.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.calendaralarm.CalendarAlarmApp
import com.calendaralarm.engine.MissedNotifier

/**
 * 定期健全性チェック + 手動同期。
 * 12h 周期で「DBのPENDINGを AlarmManager に再主張」「期限切れを MISSED 化」し、
 * カレンダーの最新状態と差分整合させる。
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as CalendarAlarmApp
        val reason = inputData.getString(KEY_REASON) ?: "workManager"
        return try {
            // resync より先に期限切れを MISSED 化する。resync の差分整合は
            // desired に無い PENDING を CANCELLED に倒すので、先に拾わないと
            // 「鳴らせなかった」事実が通知も状態も残さず消える。
            val missed = app.container.repository.markMissed()
            if (missed > 0) {
                // 端末OFF/強制停止中に時刻を過ぎたアラームがあることを
                // 黙って履歴に残すだけでなく、ユーザーへ通知する
                com.calendaralarm.engine.MissedNotifier
                    .postMissed(applicationContext, missed)
            }
            app.container.repository.resync(reason)
            app.container.repository.pruneOldInstances()
            Result.success()
        } catch (e: Exception) {
            app.container.repository.audit("ERROR", "sync worker: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        const val PERIODIC_NAME = "periodic-sync"
        private const val ONESHOT_NAME = "oneshot-sync"
        private const val KEY_REASON = "reason"

        /**
         * UI・レシーバ・ContentObserver からの即時同期要求。
         * 同名の単発 Work を置き換えるので、カレンダー変更が連発しても
         * 実行は最後の1回にデバウンスされる。
         */
        fun enqueueNow(context: Context, reason: String = "manual") {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(Data.Builder().putString(KEY_REASON, reason).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONESHOT_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
