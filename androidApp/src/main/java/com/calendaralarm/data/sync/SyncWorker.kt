package com.calendaralarm.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
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
        val reason = inputData.getString(KEY_REASON) ?: "workManager"
        return try {
            // 期限切れの MISSED 化+通知は resync 内部で行われる
            // (boot/時刻変更/手動/アプリ起動のどの入口からも同じ経路を通る)。
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
            // expedited 化: ブート/パッケージ更新/カレンダー変更の直後は
            // AlarmManager 側の予約が無い空白になる。非 expedited だと
            // スタンバイバケットや Doze で再同期が長時間遅延されて
            // 「予約済みのはずのアラームが鳴らない」窓ができる。
            // クォータ超過時は従来の遅延実行に静かにフォールバックする。
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(Data.Builder().putString(KEY_REASON, reason).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONESHOT_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
