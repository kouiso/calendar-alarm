package com.calendaralarm.engine

import android.app.KeyguardManager
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
    // mediaPlayer と同じく、生成スレッドと停止スレッドが異なるため
    // 世代撤退時の同一性判定 (===) が正しく見えるよう volatile にする
    @Volatile private var vibrator: Vibrator? = null
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    private var currentInstance: AlarmInstance? = null
    private var crescendoJob: kotlinx.coroutines.Job? = null
    private var ringTimeoutJob: kotlinx.coroutines.Job? = null

    // サービス死滅後に滞留中の beginRinging コルーチンが MediaPlayer を
    // 開始するのを防ぐフラグ。鳴り始めの一瞬に停止が押されると onDestroy→
    // stopRinging は mediaPlayer=null で空振りし、その後 start() が走って
    // サービス不在のまま音だけ鳴り続けるゾンビ状態になる (実機報告)。
    // IO ディスパッチャ上で開始されるので volatile で可視性を確保する。
    @Volatile private var destroyed = false
    // stopRinging が走るたびに進む世代番号。beginRinging/startAudio は
    // 途中で世代が進んでいたら鳴動を開始しない。DISMISS/SNOOZE の
    // stopRinging 直後に滞留コルーチンが start() へ滑り込む窓を塞ぐ
    @Volatile private var ringGen = 0
    // mediaPlayer への書き込みは IO コルーチン、stop() の読み出しは別スレッド。
    // 世代ガードで大抵は塞がるが、代入直後の stopRinging が stale null を
    // 読んで空振りする残存窓を volatile で埋める。
    @Volatile private var mediaPlayer: MediaPlayer? = null

    // 世代検査と共有状態への代入を同じクリティカルセクションで行うための
    // ロック。検査〜代入を分けると旧世代の代入が新世代の参照を上書きして
    // その鳴動を停止不能にする (Devin Review BUG 指摘)。teardown 側の
    // ringGen++ も同じロック下で行い、adopt/claim との順序を一意にする
    private val ringLock = Any()

    private val app get() = application as CalendarAlarmApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> {
                // 物理停止を最優先に潰す。DB記録が例外/遅延しても
                // 「UIは閉じたのに音だけ鳴り続ける」事故にしないため。
                // 畳むかどうかの判定は対象一致と世代奪取を不可分に行う:
                // 別インスタンス宛の遅延 DISMISS が現行鳴動を殺したり、
                // 確認〜畳みの間に後発鳴動が生まれてそれを殺さないようにする
                val targetId = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: ringingInstanceId.value
                when (claimTeardownFor(targetId)) {
                    TeardownClaim.MATCHED -> {
                        stopRinging()
                        scope.launch {
                            runCatching { app.container.repository.onDismissed(targetId) }
                            // startId 指定だと過去の ACTION_START の startId が未消化で残り
                            // サービスが終わらないことがある。引数なしで全て消化して確実に止める。
                            stopSelf()
                        }
                    }
                    TeardownClaim.BUSY -> {
                        // 別の鳴動が資源を持っている。それを殺さず対象の終端だけ記録する
                        scope.launch {
                            runCatching { app.container.repository.onDismissed(targetId) }
                            app.container.repository.audit(
                                "DISMISS_LATE", "${targetId ?: "(id不明)"}: 現行鳴動と不一致のため記録のみ")
                        }
                    }
                    TeardownClaim.IDLE -> {
                        // 鳴動中/要求中のものが無い: 滞留する通知・オーバーレイは
                        // ゾンビなので畳む (サービス死後の残滓もここで消せる)
                        scope.launch {
                            runCatching { app.container.repository.onDismissed(targetId) }
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                            RingingOverlay.hide(this@AlarmService)
                            stopSelf()
                        }
                    }
                }
            }
            ACTION_SNOOZE -> {
                val targetId = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: ringingInstanceId.value
                // 鳴動画面のプリセットからの分数指定 (未指定=インスタンス既定)
                val mins = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 0).takeIf { it > 0 }
                when (claimTeardownFor(targetId)) {
                    TeardownClaim.MATCHED -> {
                        stopRinging()
                        scope.launch {
                            runCatching { app.container.repository.onSnoozed(targetId, mins) }
                            stopSelf()
                        }
                    }
                    TeardownClaim.BUSY -> scope.launch {
                        runCatching { app.container.repository.onSnoozed(targetId, mins) }
                        app.container.repository.audit(
                            "SNOOZE_LATE", "${targetId ?: "(id不明)"}: 現行鳴動と不一致のため記録のみ")
                    }
                    TeardownClaim.IDLE -> scope.launch {
                        runCatching { app.container.repository.onSnoozed(targetId, mins) }
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                        RingingOverlay.hide(this@AlarmService)
                        stopSelf()
                    }
                }
            }
            else -> {
                // ACTION_START、または鳴動中にプロセスが殺された後の
                // START_STICKY null-intent 再起動。後者は明示 id が無いので
                // 鳴動猶予内の FIRED 行をDBから拾って鳴動を再開する。
                val explicitId = intent?.getStringExtra(EXTRA_INSTANCE_ID)
                if (explicitId != null) {
                    // コルーチン起動前に鳴動対象 id を同期公開する。通知表示から
                    // beginRinging 完了までの窓に届く DISMISS も対象一致で畳め、
                    // 「鳴動中 id が未登録で素通り→DBだけ終端化して鳴り続ける」
                    // 受信経路のゾンビ窓を塞ぐ
                    ringingInstanceId.value = explicitId
                }
                scope.launch {
                    val id = explicitId
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
        val gen = ringGen
        if (destroyed) return
        // 鳴動対象 id を世代ゲート経由で即座に公開する (通知表示〜各 adopt
        // までの窓に届く DISMISS が対象不明で素通りしないように)
        adoptIfCurrent(gen) { ringingInstanceId.value = instanceId }
        val instance = app.container.repository.instanceById(instanceId)
        if (instance == null) {
            app.container.repository.audit("ERROR", "鳴動要求されたがインスタンス不明: $instanceId")
            finishIfCurrent(gen)
            return
        }
        // 停止済み/見逃し済みのインスタンスへの遅延・重複起動は鳴らさない。
        // (receivers 側でも抑止するが、SNOOZE→DISMISS 直後の滞留放送等
        //  こちら側で二度と鳴らないことが絶対条件なので二重化する)
        val state = app.container.repository.instanceState(instanceId)
        if (state != AlarmState.PENDING && state != AlarmState.FIRED) {
            app.container.repository.audit(
                "STALE_FIRE", "$instanceId: state=$state のため鳴動を抑止")
            finishIfCurrent(gen)
            return
        }
        // グレース超過の過去分は鳴らさない。端末OFF中に過ぎたアラームを
        // 起動直後に延々鳴らし続けるより、見逃した事実を知らせる方が正しい。
        val overdueBy = System.currentTimeMillis() - instance.triggerAtMillis
        if (overdueBy > AlarmExpander.FIRE_GRACE.inWholeMilliseconds) {
            app.container.repository.markMissed(id = instanceId, detail = "鳴動時刻から${overdueBy / 60_000}分経過")
            MissedNotifier.post(this, instance.title)
            finishIfCurrent(gen)
            return
        }
        // 「ロック解除までミュート」: 端末がロック中なら鳴らさず留保し、
        // ACTION_USER_PRESENT で再発火する。予約は PENDING のまま残る。
        if (instance.muteUntilUnlock &&
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        ) {
            UnlockRingReceiver.defer(this, instance.id)
            app.container.repository.audit("DEFER", "${instance.id}: ロック解除まで鳴動を遅延")
            finishIfCurrent(gen)
            return
        }
        val ringSettings = app.container.settings.flow.first()
        // PENDING→FIRED のみ遷移 (DISMISSED を FIRED に戻さない atomic 制限)
        app.container.repository.onFired(instanceId)
        // 停止が先に届いていたら通知を出し直さない。
        // 放送経路の DISMISS は NotificationManager.cancel を先に実行済みなので、
        // ここで notify してしまうと取消後に通知だけが残るゾンビになる。
        // 世代が進んでいても全体停止は呼ばない (新しい鳴動を殺すので BUG_0001)。
        // この時点ではリソースを何も生成していないので、そのまま抜ければよい
        if (destroyed || ringGen != gen) return
        // 世代確定後にだけ自分のインスタンスを共有状態へ書く (stale 世代が
        // 書き残すと DISMISS の id フォールバックや救出経路が古い id を見る)
        if (!adoptIfCurrent(gen) {
            currentInstance = instance
            ringingInstanceId.value = instanceId
        }) return
        // タイトルが取れたので通知を張り替える
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(instanceId))
        startAudio(
            instance.soundUri ?: ringSettings.defaultSoundUri,
            volumePercent = ringSettings.alarmVolumePercent,
            crescendo = ringSettings.volumeCrescendo,
            gen = gen,
        )
        // startAudio 内で自分のプレイヤーは世代検査つきで畳まれているので、
        // 世代が進んでいたら新鳴動のリソースに触れないよう静かに抜ける
        if (destroyed || ringGen != gen) return
        // リソースは局所参照で生成し、世代がまだ現行の時だけ共有フィールド
        // へ adopt する。採用されなかったものは局所参照で畳み (新世代のもの
        // は止めない)、採用後に世代が進んだ分は新世代側の stopRinging が畳く
        val myVibrator = if (ringSettings.vibrateWhileRinging) startVibration() else null
        if (myVibrator != null && !adoptIfCurrent(gen) { vibrator = myVibrator }) {
            runCatching { myVibrator.cancel() }
            return
        }
        val myWakeLock = acquireWakeLock()
        if (!adoptIfCurrent(gen) { wakeLock = myWakeLock }) {
            runCatching { myWakeLock?.let { if (it.isHeld) it.release() } }
            return
        }
        // 停止UIの確保: フルスクリーン Intent は API34+ で権限拒否され得るし、
        // 端末ロック解除済みだと heads-up が畳まれて画面上の停止手段が無くなる。
        // SYSTEM_ALERT_WINDOW は BAL 対象外なので、許可済みなら必ず出せる
        // 停止画面をオーバーレイでも出す (鳴動画面と共存しないよう後者が出れば畳む)。
        val fsiOk = Build.VERSION.SDK_INT < 34 ||
            (getSystemService(NotificationManager::class.java)
                ?.canUseFullScreenIntent() ?: true)
        if (RingingOverlay.canShow(this)) {
            // 表示自体はメインスレッドで行い、成否はオーバーレイ側が鳴動ログへ記録する。
            // stillValid は addView 直前にメインスレッド上で評価される: 停止の hide が
            // 先に走っていたら attach を抑止し、ゾンビオーバーレイを防ぐ
            RingingOverlay.show(
                this, instance.id, instance.title,
                instance.snoozeMinutes.takeIf { it > 0 } ?: ringSettings.defaultSnoozeMinutes,
                stillValid = { synchronized(ringLock) { !destroyed && ringGen == gen } },
            )
        } else if (!fsiOk) {
            // オーバーレイ権限も FSI も無い: 通知シェード経路だけが残る危険状態を記録
            app.container.repository.audit(
                "WARN", "${instance.id}: FSI拒否+オーバーレイ未許可のため停止UIは通知のみ")
        }
        // 最終防衛線: 通知権限なし + FSI拒否 だと停止UIが1つも存在せず
        // 電源断まで鳴り続ける。上限に達したら自動停止して MISSED 扱いにする。
        // 上限タイマーの差し替えも世代ゲートの内側で行う。ゲート外で
        // cancel/assign すると新世代のタイマーを殺したり、stale 世代の
        // タイマーが残って後から現行世代の鳴動を止め得る
        adoptIfCurrent(gen) {
            ringTimeoutJob?.cancel()
            ringTimeoutJob = scope.launch {
                kotlinx.coroutines.delay(RING_TIMEOUT_MS)
                // 発火時点で後始末権を奪取できた場合だけ自分の鳴動を畳む
                if (!claimTeardown(gen)) return@launch
                app.container.repository.audit("ERROR", "${instance.id}: 鳴動上限到達で自動停止")
                app.container.repository.markMissed(instance.id, "鳴動が${RING_TIMEOUT_MS / 60_000}分続き自動停止")
                MissedNotifier.post(this@AlarmService, instance.title)
                stopRinging()
                stopSelf()
            }
        }
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
        // 「停止」は放送経路で受ける (AlarmReceiver)。getService だとサービスが
        // 死んでいる時に OS 側で起動拒否され、押しても何も起きず残るゾンビ通知になる。
        // 放送は起動制限の適用外なので必ず届き、receiver が service 停止+終端化を行う。
        val dismiss = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, AlarmReceiver::class.java).apply {
                action = ACTION_DISMISS
                putExtra(EXTRA_INSTANCE_ID, instanceId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(currentInstance?.title ?: "アラーム")
            .setContentText("「停止」でそのまま止められます")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            // setSilent(true) は入れない: Android 15 で「抑制グループ」扱いされ
            // フルスクリーン Intent が自動起動しない (E2E 実測)。無音化は
            // チャンネル側の setSound(null) に一本化し、音は MediaPlayer が出す。
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setDeleteIntent(dismiss)
            // 鳴動画面に辿り着けなくても通知シェードから必ず止められる退路
            .addAction(0, "停止", dismiss)
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

    private fun startAudio(soundUri: String?, volumePercent: Int, crescendo: Boolean, gen: Int) {
        // カスタム音が失効していても無音にはしない。候補を順に試す。
        val candidates = listOfNotNull(
            soundUri?.let(Uri::parse),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        ).distinct()
        // 0以下はシステムのアラーム音量に追従 (setVolume しない)
        val target = if (volumePercent > 0) volumePercent / 100f else -1f
        for (uri in candidates) {
            val player = MediaPlayer()
            if (destroyed || ringGen != gen) { player.release(); return }
            val ok = runCatching {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                player.setDataSource(this@AlarmService, uri)
                player.isLooping = true
                if (target >= 0f) {
                    // クレッシェンド開始はほぼ無音から、そうでなければ即目標音量
                    player.setVolume(if (crescendo) CRESCENDO_START else target, if (crescendo) CRESCENDO_START else target)
                }
                player.prepare()
                player.start()
            }.isSuccess
            if (ok) {
                // 共有フィールドへの代入は世代検査と同じクリティカル
                // セクションで行う。先に代入して再検査すると、旧世代の
                // 代入が新世代の mediaPlayer 参照を上書きして新しい音を
                // 停止不能にする
                if (!adoptIfCurrent(gen) { mediaPlayer = player }) {
                    // 世代が進んだ/破棄済み: 全体停止は新鳴動を殺すので
                    // 呼ばず、自分のプレイヤーだけ局所参照で畳む
                    runCatching { player.stop() }
                    player.release()
                    return
                }
                if (crescendo && target >= 0f) startCrescendo(player, target)
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

    /** 30秒かけて音量を目標値まで滑らかに上げる。 */
    private fun startCrescendo(player: MediaPlayer, target: Float) {
        val steps = 20
        crescendoJob = scope.launch {
            repeat(steps) { i ->
                kotlinx.coroutines.delay(CRESCENDO_MS / steps)
                if (mediaPlayer !== player || !player.isPlaying) return@launch
                val v = CRESCENDO_START + (target - CRESCENDO_START) * (i + 1) / steps
                runCatching { player.setVolume(v, v) }
            }
        }
    }

    // 共有フィールド vibrator/wakeLock へは触れず、生成したものを局所
    // 参照で返す。共有フィールドへの adopt は呼び出し側が世代ゲートの
    // 内側で行う (旧世代の代入が新世代の参照を上書きしないように)
    private fun startVibration(): Vibrator? {
        return try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            val pattern = longArrayOf(0, 800, 400, 800, 400)
            if (Build.VERSION.SDK_INT >= 33) {
                v?.vibrate(
                    VibrationEffect.createWaveform(pattern, 0),
                    VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_ALARM)
                        .build(),
                )
            } else {
                @Suppress("DEPRECATION")
                v?.vibrate(pattern, 0)
            }
            v
        } catch (e: Exception) {
            scope.launch { app.container.repository.audit("ERROR", "バイブ開始失敗: ${e.message}") }
            null
        }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // 安全側に10分で自動リリース。鳴動継続の上限でもある
        return pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "calendaralarm:ringing")
            .apply { acquire(10 * 60 * 1000L) }
    }

    /**
     * 鳴動しない判断が出た後の後始末。世代が進んでいなければ従来通り
     * 全体停止してサービスを畳む。世代が進んでいる=新しい鳴動が共有
     * リソースを所有しているので、それを殺さないよう何もせず抜ける
     * (gen 不一致で stopRinging/stopSelf を呼ぶと onDestroy→stopRinging
     *  経由で新鳴動まで消える)。
     */
    private fun finishIfCurrent(gen: Int) {
        // 後始末権を世代単位で atomic に奪取する。確認と世代前進を分けると、
        // 確認〜停止の間に新世代がリソースを得てそれを畳んでしまう
        if (!claimTeardown(gen)) return
        stopRinging()
        stopSelf()
    }

    /** gen が現行かつ未破棄なら assign を実行して true。共有フィールドへの代入は必ずこれを通す。 */
    private fun adoptIfCurrent(gen: Int, assign: () -> Unit): Boolean =
        synchronized(ringLock) {
            if (!destroyed && ringGen == gen) { assign(); true } else false
        }

    /** 後始末権を世代単位で atomic に奪取する。奪取できた世代だけが全体停止へ進める。 */
    private fun claimTeardown(gen: Int): Boolean =
        synchronized(ringLock) {
            if (!destroyed && ringGen == gen) { ringGen++; true } else false
        }

    private enum class TeardownClaim { MATCHED, BUSY, IDLE }

    /**
     * DISMISS/SNOOZE が指す対象の後始末権を奪取する。対象が現行の鳴動
     * (公開済みの鳴動要求を含む) と一致するときだけ世代を進めて MATCHED。
     * 別の鳴動が所有していれば BUSY (その鳴動は殺さない)、鳴動ゼロなら IDLE
     * (滞留UIの後始末だけ許す)。確認と世代前進は同じロック内で不可分に行う
     */
    private fun claimTeardownFor(targetId: String?): TeardownClaim =
        synchronized(ringLock) {
            if (destroyed) return@synchronized TeardownClaim.IDLE
            val cur = ringingInstanceId.value
            when {
                cur == null -> TeardownClaim.IDLE
                // id 不明の停止はフェイルセーフで現行鳴動宛とみなす
                targetId == null || targetId == cur -> { ringGen++; TeardownClaim.MATCHED }
                else -> TeardownClaim.BUSY
            }
        }

    private fun stopRinging() {
        // 世代前進も同じロック下で行い、adopt/claim の世代検査と順序を一意にする
        synchronized(ringLock) { ringGen++ }
        ringTimeoutJob?.cancel(); ringTimeoutJob = null
        crescendoJob?.cancel(); crescendoJob = null
        runCatching { mediaPlayer?.stop() }
        mediaPlayer?.release(); mediaPlayer = null
        vibrator?.cancel(); vibrator = null
        runCatching { wakeLock?.release() }
        wakeLock = null
        // FGS 状態も確実に抜く (通知だけ消すと前面化中サービスとして残り得る)
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        RingingOverlay.hide(this)
        currentInstance = null
        ringingInstanceId.value = null
    }

    override fun onDestroy() {
        destroyed = true
        stopRinging()
        scope.cancel()
        super.onDestroy()
    }

    /** タスク除去時は再鳴動を仕掛けない。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // foreground の started service はタスク除去ではプロセスごと死なない。
        // 以前ここで +15s の再鳴動を予約していたが、ユーザーが「アプリを全部
        // 閉じて止めた」直後に再度鳴り出す「止まらない」ループの原因だった。
        // プロセスを本当に殺された場合の復帰は START_STICKY の null-intent
        // 再起動 + firedWithinGrace 経路に一本化する。
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        /** 鳴動の絶対上限 (停止UIが皆無でもこれ以上は鳴り続けない)。 */
        private const val RING_TIMEOUT_MS = 10L * 60 * 1000
        const val ACTION_START = "com.calendaralarm.action.START"
        const val ACTION_DISMISS = "com.calendaralarm.action.DISMISS"
        const val ACTION_SNOOZE = "com.calendaralarm.action.SNOOZE"
        const val EXTRA_INSTANCE_ID = "instance_id"
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
        private const val CHANNEL_ID = "alarm_v1"
        /** クレッシェンドの立ち上がり時間と開始音量。 */
        private const val CRESCENDO_MS = 30_000L
        private const val CRESCENDO_START = 0.05f
        // AlarmReceiver が DISMISS 受信時に通知を直接畳むため公開
        const val NOTIFICATION_ID = 1

        /**
         * 鳴動中インスタンス id。通知権限が無い等で鳴動画面が上がらない時の
         * 救出経路として、メイン画面がこれを監視して鳴動画面へ誘導する。
         */
        val ringingInstanceId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }
}
