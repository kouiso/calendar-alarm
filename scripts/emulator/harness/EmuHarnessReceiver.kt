package com.calendaralarm.emu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.calendaralarm.CalendarAlarmApp
import com.calendaralarm.engine.AlarmReceiver
import com.calendaralarm.engine.AlarmService
import com.calendaralarm.shared.logic.AlarmExpander
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * エミュレータ E2E 専用の操作口 (scripts/emulator/inject_harness.sh が CI 上でだけ
 * debug ソースセットへ差し込む。リポジトリのビルド・FAD 配布物には含まれない)。
 *
 * - SCHEDULE: タイマーと同じ経路 (scheduleAdhoc → AlarmManager.setAlarmClock) で
 *   N 秒後の鳴動を予約し、インスタンス id を放送の結果データで返す
 * - DISMISS: 鳴動通知/オーバーレイの「停止」と同じ放送 (AlarmReceiver 宛) を送る
 *
 * 既存のアプリ API だけを使うので、修正前のコミットにも同じものを差し込める。
 */
class EmuHarnessReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SCHEDULE -> {
                val seconds = intent.getIntExtra(EXTRA_SECONDS, 45)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "EmuStopTest"
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val repo = (context.applicationContext as CalendarAlarmApp).container.repository
                        val inst = AlarmExpander.timerInstance(
                            durationMillis = seconds * 1_000L,
                            now = Clock.System.now(),
                            label = label,
                        )
                        repo.scheduleAdhoc(inst)
                        pending.resultCode = 1
                        pending.resultData = inst.id
                    } catch (e: Exception) {
                        pending.resultCode = 2
                        pending.resultData = "${e.javaClass.simpleName}: ${e.message}"
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_DISMISS -> {
                context.sendBroadcast(
                    Intent(context, AlarmReceiver::class.java).apply {
                        action = AlarmService.ACTION_DISMISS
                        intent.getStringExtra(EXTRA_ID)?.let {
                            putExtra(AlarmService.EXTRA_INSTANCE_ID, it)
                        }
                    },
                )
                resultCode = 1
            }
        }
    }

    companion object {
        const val ACTION_SCHEDULE = "com.calendaralarm.emu.SCHEDULE"
        const val ACTION_DISMISS = "com.calendaralarm.emu.DISMISS"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_LABEL = "label"
        const val EXTRA_ID = "id"
    }
}
