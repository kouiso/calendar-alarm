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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentInstance: AlarmInstance? = null

    private val app get() = application as CalendarAlarmApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: run {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startForegroundWithNotification(id)
                scope.launch { beginRinging(id) }
            }
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
            else -> stopSelf(startId)
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
        ringingInstanceId.value = instanceId
        app.container.repository.onFired(instanceId)
        // タイトルが取れたので通知を張り替える
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(instanceId))
        startAudio(instance.soundUri)
        startVibration()
        acquireWakeLock()
    }

    private fun startForegroundWithNotification(instanceId: String) {
        createChannel()
        val notification = buildNotification(instanceId)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
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
            .setSilent(true) // 音はサービス側の MediaPlayer に一本化
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
            app.container.scheduler.schedule(
                it.copy(triggerAtMillis = System.currentTimeMillis() + 15_000L),
            )
            scope.launch {
                app.container.repository.audit("ERROR", "鳴動中にタスク除去: ${it.id} → 15秒後に再鳴動")
                app.container.repository.setState(it.id, AlarmState.PENDING)
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
