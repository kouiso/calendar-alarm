package com.calendaralarm.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.calendaralarm.CalendarAlarmApp

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
        return try {
            app.container.repository.resync("workManager")
            app.container.repository.markMissed()
            app.container.repository.pruneOldInstances()
            Result.success()
        } catch (e: Exception) {
            app.container.repository.audit("ERROR", "sync worker: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        const val PERIODIC_NAME = "periodic-sync"

        /** UI からの即時同期要求。 */
        fun enqueueNow(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<SyncWorker>().build(),
            )
        }
    }
}
