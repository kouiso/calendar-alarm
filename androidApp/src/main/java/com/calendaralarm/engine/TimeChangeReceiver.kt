package com.calendaralarm.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.calendaralarm.data.sync.SyncWorker

/**
 * 時刻・タイムゾーン・日付の変更で再展開。
 * 単発アラームのローカル時刻は TZ 変更で絶対時刻が変わるため、
 * 展開し直して差分適用しないと古い時刻で鳴ってしまう。
 * 実処理は WorkManager へ委譲 (Receiver の実行時間制限を回避)。
 */
class TimeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        SyncWorker.enqueueNow(context, "time:$action")
    }
}
