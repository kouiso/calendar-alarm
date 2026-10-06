package com.calendaralarm.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.calendaralarm.CalendarAlarmApp
import com.calendaralarm.R
import com.calendaralarm.shared.logic.AlarmExpander
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 鳴動中のフォアグラウンドサービス。
 * 音とバイブを出し続け、鳴動画面を全面に上げるフルスクリーン通知を出す。
 * DISMISS/SNOOZE の全操作をここに集約し、鳴動状態は DB の監査ログに残す。
 */
class AlarmService : Service() {

    // MediaPlayer#prepare 等のブロッキングが鳴動開始を遅らせないよう IO で回す
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentInstance: AlarmInstance? = null

    private val app get() = application as CalendarAlarmApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> {
                val id = intent.getStringExtra(EXTRA_INSTANCE_ID)
                scope.launch {
                    app.container.repository.onDismissed(id ?: currentInstance?.id)
                    stopRinging()
                    stopSelf(startId)
                }
            }
            ACTION_SNOOZE -> {
                val id = intent.getStringExtra(EXTRA_INSTANCE_ID)
                scope.launch {
                    app.container.repository.onSnoozed(id ?: currentInstance?.id)
                    stopRinging()
                    stopSelf(startId)
                }
            }
            else -> {
                // ACTION_START、または鳴動中にプロセスが殺された後の
                // START_STICKY null-intent 再起動。後者は明示 id が無いので
                // 鳴動猶予内の FIRED 行をDBから拾って鳴動を再開する。
                scope.launch {
                    val id = intent?.getStringExtra(EXTRA_INSTANCE_ID)
                        ?: app.container.repository.firedWithinGrace()?.id
                    if (id == null) {
                        stopSelf(startId)
                        return@launch
                    }
                    if (!startForegroundWithNotification(id)) {
                        // BOOT_COMPLETED 処理中など、mediaPlayback FGS の起動が
                        // 禁止されているコンテキスト。ここで例外を上げると
                        // クラッシュループになるので見逃し扱いにして静かに畳む。
                        app.container.repository.markMissed(id, "鳴動サービスの前面化がOSに拒否")
                        MissedNotifier.post(this@AlarmService, null)
                        stopSelf(startId)
                        return@launch
                    }
                    beginRinging(id)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun beginRinging(instanceId: String) {
        // 同時刻に複数鳴った場合は新しい方へ張り替える（MediaPlayer のリークと多重発声を防ぐ）
        stopRinging()
        val instance = app.container.repository.instanceById(instanceId)
        if (instance == null) {
            app.container.repository.audit("ERROR", "鳴動要求されたがインスタンス不明: $instanceId")
            stopRinging()
            stopSelf()
            return
        }
        currentInstance = instance
        // グレース超過の過去分は鳴らさない。端末OFF中に過ぎたアラームを
        // 起動直後に延々鳴らし続けるより、見逃した事実を知らせる方が正しい。
        val overdueBy = System.currentTimeMillis() - instance.triggerAtMillis
        if (overdueBy > AlarmExpander.FIRE_GRACE.inWholeMilliseconds) {
            app.container.repository.markMissed(id = instanceId, detail = "鳴動時刻から${overdueBy / 60_000}分経過")
            MissedNotifier.post(this, instance.title)
            stopRinging()
            stopSelf()
            return
        }
        ringingInstanceId.value = instanceId
        app.container.repository.onFired(instanceId)
        // タイトルが取れたので通知を張り替える
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(instanceId))
        startAudio(instance.soundUri)
        startVibration()
        acquireWakeLock()
    }

    /** FGS 前面化を試みる。OS に拒否された場合は false (例外は飲み込む)。 */
    private fun startForegroundWithNotification(instanceId: String): Boolean {
        createChannel()
        val notification = buildNotification(instanceId)
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // BOOT_COMPLETED 等では ForegroundServiceStartNotAllowedException。
            // 落とすとクラッシュループになるので、呼び出し側で見逃し化する。
            false
        }
    }

    private fun buildNotification(instanceId: String): Notification {
        val fullScreen = PendingIntent.getActivity(
            this, 0,
            RingingActivity.intent(this, instanceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = PendingIntent.getService(
            this, 0,
            Intent(this, AlarmService::class.java).apply {
                action = ACTION_DISMISS
                putExtra(EXTRA_INSTANCE_ID, instanceId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(currentInstance?.title ?: "アラーム")
            .setContentText("止めるには開いてください")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            // setSilent(true) は入れない: Android 15 で「抑制グループ」扱いされ
            // フルスクリーン Intent が自動起動しない (E2E 実測)。無音化は
            // チャンネル側の setSound(null) に一本化し、音は MediaPlayer が出す。
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setDeleteIntent(dismiss)
            .build()
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "アラーム", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "アラーム鳴動時のフルスクリーン通知"
                setSound(null, null)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun startAudio(soundUri: String?) {
        // カスタム音が失効していても無音にはしない。候補を順に試す。
        val candidates = listOfNotNull(
            soundUri?.let(Uri::parse),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        ).distinct()
        for (uri in candidates) {
            val player = MediaPlayer()
            val ok = runCatching {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                player.setDataSource(this@AlarmService, uri)
                player.isLooping = true
                player.prepare()
                player.start()
            }.isSuccess
            if (ok) {
                mediaPlayer = player
                if (uri != candidates.firstOrNull()) {
                    scope.launch {
                        app.container.repository.audit("ERROR", "希望の音が使えず代替音へ: $uri")
                    }
                }
                return
            }
            player.release()
            scope.launch {
                app.container.repository.audit("ERROR", "音源 prepare 失敗 (次候補へ): $uri")
            }
        }
        scope.launch { app.container.repository.audit("ERROR", "全音源が失敗、無音のまま鳴動継続") }
    }

    private fun startVibration() {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            val pattern = longArrayOf(0, 800, 400, 800, 400)
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(pattern, 0),
                    VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_ALARM)
                        .build(),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            scope.launch { app.container.repository.audit("ERROR", "バイブ開始失敗: ${e.message}") }
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // 安全側に10分で自動リリース。鳴動継続の上限でもある
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "calendaralarm:ringing")
            .apply { acquire(10 * 60 * 1000L) }
    }

    private fun stopRinging() {
        runCatching { mediaPlayer?.stop() }
        mediaPlayer?.release(); mediaPlayer = null
        vibrator?.cancel(); vibrator = null
        runCatching { wakeLock?.release() }
        wakeLock = null
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        currentInstance = null
        ringingInstanceId.value = null
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }

    /** システムやユーザーにサービスを殺されても、鳴動を握り潰さない。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        currentInstance?.let {
            // AlarmManager の予約はプロセスをまたいで残るので、直近に再鳴動を仕掛け直す。
            // DB も PENDING に戻して健全性チェックの差分と整合させる。
            val rearmed = app.container.scheduler.schedule(
                it.copy(triggerAtMillis = System.currentTimeMillis() + 15_000L),
            )
            scope.launch {
                if (rearmed) {
                    app.container.repository.audit("ERROR", "鳴動中にタスク除去: ${it.id} → 15秒後に再鳴動")
                    app.container.repository.setState(it.id, AlarmState.PENDING)
                } else {
                    // 再武装そのものが拒否された場合も握り潰さず MISSED+通知に倒す
                    app.container.repository.markMissed(it.id, "タスク除去後の再鳴動予約がOSに拒否")
                    MissedNotifier.post(this@AlarmService, it.title)
                }
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        const val ACTION_START = "com.calendaralarm.action.START"
        const val ACTION_DISMISS = "com.calendaralarm.action.DISMISS"
        const val ACTION_SNOOZE = "com.calendaralarm.action.SNOOZE"
        const val EXTRA_INSTANCE_ID = "instance_id"
        private const val CHANNEL_ID = "alarm_v1"
        private const val NOTIFICATION_ID = 1

        /**
         * 鳴動中インスタンス id。通知権限が無い等で鳴動画面が上がらない時の
         * 救出経路として、メイン画面がこれを監視して鳴動画面へ誘導する。
         */
        val ringingInstanceId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }
}
