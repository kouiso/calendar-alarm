package com.calendaralarm.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AlarmManager → 鳴動の入口。
 * setAlarmClock 経由のブロードキャストはバックグラウンド起動制限の適用外なので、
 * ここから鳴動フォアグラウンドサービスを即時起動できる。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> {
                val instanceId = intent.data?.lastPathSegment ?: return
                // NOTIFY 配信はサービスを立てず通知だけ出す。DB からの解決が要る
                // ため goAsync で coroutine に逃がす (読み1件なら放送枠内で間に合う)。
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    val repo = (context.applicationContext as com.calendaralarm.CalendarAlarmApp)
                        .container.repository
                    try {
                        val instance = repo.instanceById(instanceId)
                        // NOTIFY でも通知権限が無いと何も届かない → 鳴る側に倒す
                        val notificationsOk =
                            androidx.core.app.NotificationManagerCompat.from(context)
                                .areNotificationsEnabled()
                        when {
                            instance == null -> Unit
                            // 停止済み等の終端インスタンスへ届いた遅延/重複放送は
                            // 鳴らさない (これを通すと「止めたのにまた鳴る」)
                            repo.instanceState(instanceId) !=
                                com.calendaralarm.shared.model.AlarmState.PENDING ->
                                repo.audit(
                                    "STALE_FIRE",
                                    "$instanceId: 終端済みのため鳴動を抑止",
                                )
                            instance.delivery ==
                                com.calendaralarm.shared.model.EventAction.NOTIFY &&
                                notificationsOk -> {
                                repo.onFired(instanceId)
                                runCatching { EventNotifier.post(context, instance) }
                            }
                            else -> {
                                try {
                                    context.startForegroundService(
                                        ringingIntent(context, instanceId))
                                } catch (e: Exception) {
                                    // FGS起動自体が拒否されるコンテキストがあり得る。
                                    // 見逃しに逃がす (同じ放送枠内で記録をコミットする)
                                    repo.markMissed(
                                        instanceId,
                                        "鳴動サービス起動がOSに拒否: ${e.javaClass.simpleName}",
                                    )
                                    runCatching { MissedNotifier.post(context, null) }
                                }
                            }
                        }
                        return@launch
                    } finally {
                        pending.finish()
                    }
                }
            }
            EventNotifier.ACTION_DISMISS -> {
                val instanceId = intent.data?.lastPathSegment ?: return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        runCatching { AlarmService.markDismissed(instanceId) }
                        runCatching {
                            (context.applicationContext as com.calendaralarm.CalendarAlarmApp)
                                .container.repository.onDismissed(instanceId)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            // 鳴動通知の「停止」/破棄アクション。getService 宛だとサービス不在時に
            // OS 側で握り潰されて停止が届かないことがあるため、必ず届く放送経路で受けて
            // サービス停止 + DB終端化を行う (生きていれば onDestroy→stopRinging が音を止める)
            AlarmService.ACTION_DISMISS -> {
                val instanceId = intent.getStringExtra(AlarmService.EXTRA_INSTANCE_ID)
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // 停止を最速で届けるため転送を先に行い、監査は後書きにする
                        runCatching {
                            context.startService(Intent(context, AlarmService::class.java).apply {
                                action = AlarmService.ACTION_DISMISS
                                if (instanceId != null) {
                                    putExtra(AlarmService.EXTRA_INSTANCE_ID, instanceId)
                                }
                            })
                        }.onFailure {
                            // サービスへ届けられない=未起動/起動不可。鳴動資源は
                            // 存在しないので滞留通知・オーバーレイだけ畳み、DB終端もここで済ませる
                            context.getSystemService(android.app.NotificationManager::class.java)
                                ?.cancel(AlarmService.NOTIFICATION_ID)
                            RingingOverlay.hide(context)
                            runCatching {
                                (context.applicationContext as com.calendaralarm.CalendarAlarmApp)
                                    .container.repository.onDismissed(instanceId)
                            }
                        }
                        // 届かなかった場合でも対象 id の終端意図を墓標へ記録し、
                        // 後から走る鳴動処理が止まるようにする (サービス側は
                        // 受信した DISMISS を必ず墓標へ書くので二重でも無害)
                        runCatching { AlarmService.markDismissed(instanceId) }
                        // 「届いた」事実を鳴動ログに残す。次の障害時に
                        // ボタン未到達か終端化失敗かをここで切り分けられる
                        runCatching {
                            (context.applicationContext as com.calendaralarm.CalendarAlarmApp)
                                .container.repository.audit(
                                    "DISMISS_RECV", instanceId ?: "(id不明)",
                                )
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private fun ringingIntent(context: Context, instanceId: String) =
        Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START
            data = Uri.parse("alarm://instance/$instanceId")
            putExtra(AlarmService.EXTRA_INSTANCE_ID, instanceId)
        }

    companion object {
        const val ACTION_FIRE = "com.calendaralarm.action.FIRE"
    }
}
