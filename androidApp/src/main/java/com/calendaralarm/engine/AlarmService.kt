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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    // 開始するのを防ぐフラグ。鳴り始めの一瞬に停止が押されると onDestroy の
    // 全体停止は mediaPlayer=null で空振りし、その後 start() が走って
    // サービス不在のまま音だけ鳴り続けるゾンビ状態になる (実機報告)。
    // IO ディスパッチャ上で開始されるので volatile で可視性を確保する。
    @Volatile private var destroyed = false
    // 停止 (stopRingingLocked) が走るたびに進む世代番号。beginRinging/startAudio は
    // 途中で世代が進んでいたら鳴動を開始しない。DISMISS/SNOOZE の
    // 停止直後に滞留コルーチンが start() へ滑り込む窓を塞ぐ
    @Volatile private var ringGen = 0
    // 書き込みと停止時の読み出しは ringLock 内だが、クレッシェンドの同一性判定
    // (mediaPlayer !== player) はロック外の別スレッドから読むので volatile にする。
    @Volatile private var mediaPlayer: MediaPlayer? = null

    // 世代検査と共有状態への代入を同じクリティカルセクションで行うための
    // ロック。検査〜代入を分けると旧世代の代入が新世代の参照を上書きして
    // その鳴動を停止不能にする (Devin Review BUG 指摘)。teardown 側の
    // ringGen++ も同じロック下で行い、adopt/claim との順序を一意にする
    private val ringLock = Any()
    // 鳴動座席を確保済み (先代を畳んだ) だが鳴動開始に至っていない対象の id。
    // 実際に鳴動を開始し得た時点で ringingInstanceId へ繰り上げられる。
    // 全アクセスは ringLock 内のみ (claim の対象判定に使う)
    private var pendingStartId: String? = null
    // ACTION_START を受理したがまだ座席を確保していない id 群。座席確保前の
    // 要求への停止は墓標だけで潰せるので、世代を進めて現行鳴動の上限タイマー等を
    // 巻き添えにしない。全アクセスは ringLock 内のみ
    private val requestedIds = mutableSetOf<String>()
    // 鳴動通知のチャンネル選択に使う。前面化前に鳴動設定から確定させる
    @Volatile private var vibrateForRing = true
    // 直近に受け取ったコマンドの startId (ringLock 内のみ)。終了判定時点の値で
    // stopSelfResult すると、判定後に届いた ACTION_START があればサービスは止まらない
    // (引数なし stopSelf は判定〜停止の間に届いた新しい鳴動ごと殺す)
    private var latestStartId = 0
    // 直近に「アラートあり」で出した鳴動通知の対象。同じ鳴動の張替えでは再アラートせず、
    // 別の鳴動へ差し替わった時はヘッズアップ/FSI をもう一度出す
    @Volatile private var alertedInstanceId: String? = null

    private val app get() = application as CalendarAlarmApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(ringLock) { latestStartId = startId }
        when (intent?.action) {
            ACTION_DISMISS -> {
                // 物理停止を最優先に潰す。DB記録が例外/遅延しても
                // 「UIは閉じたのに音だけ鳴り続ける」事故にしないため。
                // 対象一致の判定・世代奪取・資源の畳みを不可分に行う:
                // 別インスタンス宛の遅延 DISMISS が現行鳴動を殺したり、
                // 判定〜畳みの間に後発鳴動が生まれてそれを殺さないようにする
                val (claim, targetId) = claimTeardownFor(intent.getStringExtra(EXTRA_INSTANCE_ID)) {
                    // IDLE確定時に同じロック内で実行される。判定〜畳みの間に
                    // 新鳴動が始まってもその adopt はロック待ちになるので、
                    // ここで畳むUIは必ず滞留分 (BUG_0003)
                    removeRingNotification()
                    RingingOverlay.hide(this@AlarmService)
                }
                when (claim) {
                    // MATCHED/PENDING は claimTeardownFor のロック内で畳み済み。
                    // PENDING (座席確保済みの未開始鳴動) は先代を畳み済みなので、守るべき
                    // 他の鳴動は無い。採用済みの資源ごと現行と同じく畳んである
                    TeardownClaim.MATCHED, TeardownClaim.PENDING -> scope.launch {
                        runCatching { app.container.repository.onDismissed(targetId) }
                        finishIfIdle()
                    }
                    TeardownClaim.REQUESTED -> scope.launch {
                        // 座席確保前の要求は墓標で抑止済み。現行鳴動には触れない
                        // (後続の beginRinging が STALE_FIRE で通知を現行へ戻す)
                        runCatching { app.container.repository.onDismissed(targetId) }
                    }
                    TeardownClaim.BUSY -> {
                        // 別の鳴動が資源を持っている。それを殺さず対象の終端だけ記録する
                        scope.launch {
                            runCatching { app.container.repository.onDismissed(targetId) }
                            app.container.repository.audit(
                                "DISMISS_LATE", "${targetId ?: "(id不明)"}: 現行鳴動と不一致のため記録のみ")
                        }
                    }
                    TeardownClaim.IDLE -> scope.launch {
                        // 鳴動中・要求中ともに無し: 滞留UIは onIdle で畳み済み
                        runCatching { app.container.repository.onDismissed(targetId) }
                        finishIfIdle()
                    }
                }
            }
            ACTION_SNOOZE -> {
                // 鳴動画面のプリセットからの分数指定 (未指定=インスタンス既定)
                val mins = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 0).takeIf { it > 0 }
                val (claim, targetId) = claimTeardownFor(intent.getStringExtra(EXTRA_INSTANCE_ID)) {
                    removeRingNotification()
                    RingingOverlay.hide(this@AlarmService)
                }
                when (claim) {
                    TeardownClaim.MATCHED, TeardownClaim.PENDING -> scope.launch {
                        runCatching { app.container.repository.onSnoozed(targetId, mins) }
                        finishIfIdle()
                    }
                    TeardownClaim.REQUESTED -> scope.launch {
                        runCatching { app.container.repository.onSnoozed(targetId, mins) }
                    }
                    TeardownClaim.BUSY -> scope.launch {
                        runCatching { app.container.repository.onSnoozed(targetId, mins) }
                        app.container.repository.audit(
                            "SNOOZE_LATE", "${targetId ?: "(id不明)"}: 現行鳴動と不一致のため記録のみ")
                    }
                    TeardownClaim.IDLE -> scope.launch {
                        runCatching { app.container.repository.onSnoozed(targetId, mins) }
                        finishIfIdle()
                    }
                }
            }
            else -> {
                // ACTION_START、または鳴動中にプロセスが殺された後の
                // START_STICKY null-intent 再起動。後者は明示 id が無いので
                // 鳴動猶予内の FIRED 行をDBから拾って鳴動を再開する。
                val explicitId = intent?.getStringExtra(EXTRA_INSTANCE_ID)
                if (explicitId != null) {
                    // コルーチン起動前に鳴動「要求」の対象 id を同期公開する。
                    // 通知表示〜鳴動開始までの窓に届く DISMISS を対象一致で畳める
                    // ようになる。鳴動中ID (ringingInstanceId) はここでは上書き
                    // しない: 要求時点で上書きすると A鳴動中にBが要求されただけで
                    // Aへの停止がBUSY化してAが殺せなくなる (BUG_0002)
                    synchronized(ringLock) { requestedIds += explicitId }
                }
                scope.launch {
                    val id = explicitId
                        ?: app.container.repository.firedWithinGrace()?.id
                    if (id == null) {
                        finishIfIdle()
                        return@launch
                    }
                    // チャンネル (バイブ有無) は前面化の通知で決まるので先に設定を読む。
                    // 前面化の期限があるので読めなければ既定 (バイブあり) で進む
                    vibrateForRing = readVibrateSetting()
                    if (!startForegroundWithNotification(id)) {
                        // BOOT_COMPLETED 処理中など、mediaPlayback FGS の起動が
                        // 禁止されているコンテキスト。ここで例外を上げると
                        // クラッシュループになるので見逃し扱いにして静かに畳む。
                        synchronized(ringLock) { requestedIds -= id }
                        if (!isDismissed(id)) {
                            app.container.repository.markMissed(id, "鳴動サービスの前面化がOSに拒否")
                            MissedNotifier.post(this@AlarmService, null)
                        }
                        finishIfIdle()
                        return@launch
                    }
                    beginRinging(id)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun beginRinging(instanceId: String) {
        // 墓標確認・自世代確定・現行鳴動の停止・鳴動要求公開をひとつの
        // クリティカルセクションで行う。分割すると、その間に届く DISMISS が
        // 「確認〜停止」の窓で墓標済み要求を殺せずに現行鳴動が犠牲になるか、
        // 「確認〜公開」の窓で鳴動ゼロ(IDLE)判定のまま素通りして墓標済み
        // 要求が鳴る、という同族レースが残る。先に自世代を確定してから先代を
        // 畳むので、公開直後に届く DISMISS も PENDING 判定でこの要求だけ潰せる
        // 座席確保で押し出した先代 (鳴動中または座席確保済み) の id。押し出された側は
        // 自分のコルーチンで何も記録せずに抜けるため、ここで終端化しないと DB に FIRED の
        // まま残り、停止後のプロセス再起動 (START_STICKY → firedWithinGrace) で
        // 「止めたのにまた鳴る」原因になる (同時刻の予定2件で実機再現 10/14 0:00)
        var displaced: String? = null
        val gen = synchronized(ringLock) {
            requestedIds -= instanceId
            if (destroyed || isDismissed(instanceId)) {
                null
            } else {
                displaced = (ringingInstanceId.value ?: pendingStartId)?.takeIf { it != instanceId }
                // 押し出した先代は墓標で以後の鳴動を抑止する (滞留中の再発火も潰す)
                markDismissed(displaced)
                ringGen++
                // 前面化は維持する: 直前に自分の startForeground で出した通知を
                // ここで外すと、以後の鳴動は前面サービスでなくなり OS に畳まれ得る
                teardownRingLocked(keepForeground = true)
                pendingStartId = instanceId
                ringGen
            }
        }
        if (gen == null) {
            // 墓標済みか破棄済み: 他の鳴動へ一切触れず終端のみ記録して抜ける
            runCatching {
                app.container.repository.audit("STALE_FIRE", "$instanceId: DISMISS済みのため鳴動を抑止")
            }
            // 自分の前面化で通知 (停止の宛先を含む) を差し替えているので、
            // 現行鳴動があればその停止UIへ戻す。無ければ滞留通知ごと畳む
            restoreOrFinish()
            return
        }
        displaced?.let { prev ->
            runCatching { app.container.repository.onSuperseded(prev, instanceId) }
        }
        if (destroyed) return
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
            // タイトルが取れたので通知を張り替える。停止の世代前進と同じロック下で行う:
            // ロック外だと停止が通知を消した直後に張り替えが出し直し、通知だけ残る
            postRingNotification(instanceId)
        }) return
        startAudio(
            instance.soundUri ?: ringSettings.defaultSoundUri,
            volumePercent = ringSettings.alarmVolumePercent,
            crescendo = ringSettings.volumeCrescendo,
            gen = gen,
        )
        // 鳴動を実際に開始し得た時点で、公開IDを「要求中」から「鳴動中」へ
        // 繰り上げる (繰上げ前の要求は宛先違いの DISMISS で個別に潰せる)
        adoptIfCurrent(gen) {
            ringingInstanceId.value = instanceId
            pendingStartId = null
        }
        // startAudio 内で自分のプレイヤーは世代検査つきで畳まれているので、
        // 世代が進んでいたら新鳴動のリソースに触れないよう静かに抜ける
        if (destroyed || ringGen != gen) return
        // リソースは局所参照で生成し、世代がまだ現行の時だけ共有フィールド
        // へ adopt する。採用されなかったものは局所参照で畳み (新世代のもの
        // は止めない)、採用後に世代が進んだ分は新世代側の停止が畳む
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
        // 端末ロック解除中に他アプリが前面だと heads-up が畳まれて画面上の停止手段が無くなる。
        // SYSTEM_ALERT_WINDOW は BAL 対象外なので、許可済みなら FSI の可否に関係なく
        // 停止画面をオーバーレイでも出す (鳴動画面と共存しないよう後者が出れば畳む)。
        val plan = StopUiPlan.of(StopUiPermissions.probe(this))
        if (plan.showOverlay) {
            // 表示自体はメインスレッドで行い、成否はオーバーレイ側が鳴動ログへ記録する。
            // stillValid は addView 直前にメインスレッド上で評価される: 停止の hide が
            // 先に走っていたら attach を抑止し、ゾンビオーバーレイを防ぐ
            RingingOverlay.show(
                this, instance.id, instance.title,
                instance.snoozeMinutes.takeIf { it > 0 } ?: ringSettings.defaultSnoozeMinutes,
                stillValid = { synchronized(ringLock) { !destroyed && ringGen == gen } },
            )
        }
        // 停止経路が欠けた状態を鳴動ログに残し、次の「止まらない」報告をログだけで切り分ける。
        // 監査の失敗で鳴動処理ごと落とさない
        plan.warnings.forEach { w ->
            runCatching { app.container.repository.audit("WARN", "${instance.id}: $w") }
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
                // 発火時点で後始末権を奪取できた場合だけ、同じロック内で自分の鳴動を畳む。
                // 畳みの中でこのジョブ自身もキャンセルされるので、以降は NonCancellable で行う
                if (!stopIfCurrent(gen)) return@launch
                withContext(NonCancellable) {
                    runCatching {
                        app.container.repository.audit("ERROR", "${instance.id}: 鳴動上限到達で自動停止")
                    }
                    runCatching {
                        app.container.repository.markMissed(instance.id, "鳴動が${RING_TIMEOUT_MS / 60_000}分続き自動停止")
                    }
                    MissedNotifier.post(this@AlarmService, instance.title)
                    finishIfIdle()
                }
            }
        }
    }

    /** FGS 前面化を試みる。OS に拒否された場合は false (例外は飲み込む)。 */
    private fun startForegroundWithNotification(instanceId: String): Boolean {
        createChannel()
        val notification = buildNotification(instanceId)
        return try {
            synchronized(notificationLock) {
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(
                        NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                // 前面化を拒否された時は何も投稿していないので、先代の遅延掃除を止めない
                notificationEpoch++
            }
            true
        } catch (e: Exception) {
            // BOOT_COMPLETED 等では ForegroundServiceStartNotAllowedException。
            // 落とすとクラッシュループになるので、呼び出し側で見逃し化する。
            false
        }
    }

    private fun postRingNotification(instanceId: String) {
        val notification = buildNotification(instanceId)
        synchronized(notificationLock) {
            notificationEpoch++
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 前面化を解いて鳴動通知を消す。NotificationManagerService は投稿を遅延処理するため、
     * 鳴り始め直後の停止では cancel の後に前面化通知が着弾して残ることがある (エミュレータ E2E で再現)。
     * 残った通知は次の鳴動の投稿を「更新」扱いにして FSI (ロック中の鳴動画面) を出させないので、
     * 少し後にもう一度消す。その間に次の鳴動通知が出ていれば (世代が進んでいれば) 触らない
     */
    private fun removeRingNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        val epoch = synchronized(notificationLock) {
            nm.cancel(NOTIFICATION_ID)
            notificationEpoch
        }
        Handler(Looper.getMainLooper()).postDelayed({
            synchronized(notificationLock) {
                if (notificationEpoch == epoch) nm.cancel(NOTIFICATION_ID)
            }
        }, LATE_POST_SWEEP_MS)
    }

    private fun buildNotification(instanceId: String): Notification {
        val sameRing = alertedInstanceId == instanceId
        alertedInstanceId = instanceId
        val fullScreen = PendingIntent.getActivity(
            this, 0,
            RingingActivity.intent(this, instanceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 「停止」は放送経路で受ける (AlarmReceiver)。getService だとサービスが
        // 死んでいる時に OS 側で起動拒否され、押しても何も起きず残るゾンビ通知になる。
        // 放送は起動制限の適用外なので必ず届き、receiver が service 停止+終端化を行う。
        // FSI が拒否/抑止されている環境 (Android 14+ の「アラームとリマインダー」
        // 特別アクセス未許可、OEM 独自抑制など) ではこのアクション付き
        // ヘッズアップ通知が唯一の停止導線になる。障害調査で判別できるよう記録する
        if (Build.VERSION.SDK_INT >= 34 &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) {
            scope.launch {
                app.container.repository.audit(
                    "FSI_DENIED",
                    "フルスクリーンIntent未許可のためヘッズアップ通知へフォールバック",
                )
            }
        }
        val dismiss = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, AlarmReceiver::class.java).apply {
                action = ACTION_DISMISS
                putExtra(EXTRA_INSTANCE_ID, instanceId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, if (vibrateForRing) CHANNEL_ID else CHANNEL_ID_NOVIB)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(currentInstance?.title ?: "アラーム")
            .setContentText("「停止」でそのまま止められます")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            // 前面化→タイトル張替えで同じ鳴動の通知を2回 post するため、2回目で再アラート
            // (heads-up の出し直し・チャンネル振動の重複) させない。別の鳴動へ差し替わる
            // 時はアラートを出し直し、新しい鳴動の heads-up/FSI を出す
            .setOnlyAlertOnce(sameRing)
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

    private fun createChannel() = ensureChannels(this)

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
                // クレッシェンドのジョブも同じゲート内で採用する (ゲート外だと旧世代の
                // 代入が新世代のジョブ参照を上書きし、停止時にキャンセルされなくなる)
                if (!adoptIfCurrent(gen) {
                    mediaPlayer = player
                    if (crescendo && target >= 0f) crescendoJob = startCrescendo(player, target)
                }) {
                    // 世代が進んだ/破棄済み: 全体停止は新鳴動を殺すので
                    // 呼ばず、自分のプレイヤーだけ局所参照で畳む
                    runCatching { player.stop() }
                    player.release()
                    return
                }
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
    private fun startCrescendo(player: MediaPlayer, target: Float): kotlinx.coroutines.Job {
        val steps = 20
        return scope.launch {
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
            val pattern = VIBRATION_PATTERN
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
     * (gen 不一致で全体停止や stopSelf を呼ぶと onDestroy→全体停止
     *  経由で新鳴動まで消える)。
     */
    private fun finishIfCurrent(gen: Int) {
        if (!stopIfCurrent(gen)) return
        finishIfIdle()
    }

    /** gen が現行かつ未破棄なら assign を実行して true。共有フィールドへの代入は必ずこれを通す。 */
    private fun adoptIfCurrent(gen: Int, assign: () -> Unit): Boolean =
        synchronized(ringLock) {
            if (!destroyed && ringGen == gen) { assign(); true } else false
        }

    /**
     * 後始末権を世代単位で奪取し、奪取できたら同じロック内で鳴動を畳む。
     * 奪取と畳みを分けると、その間に座席確保した後続鳴動の世代を無効化したり、
     * 後続が採用したプレイヤー参照を止めずに捨てたりする (停止不能な音)
     */
    private fun stopIfCurrent(gen: Int): Boolean =
        synchronized(ringLock) {
            if (!destroyed && ringGen == gen) { stopRingingLocked(); true } else false
        }

    private enum class TeardownClaim { MATCHED, PENDING, REQUESTED, BUSY, IDLE }

    /**
     * DISMISS/SNOOZE が指す対象の後始末権を、対象判定・世代前進・資源の畳みを
     * 同じロック内で不可分に行って奪取する。
     * - MATCHED:   対象が現行の鳴動 → ロック内で畳み済み
     * - PENDING:   対象が座席確保済みの未開始鳴動 → その要求 (と採用済みの資源) をロック内で畳み済み
     * - REQUESTED: 対象が座席確保前の要求 → 墓標だけで抑止し、現行鳴動の世代は進めない
     * - BUSY:      別の鳴動が資源を持つ → 殺さず終端記録のみ
     * - IDLE:      鳴動中・要求中ともに無し → onIdle (滞留UIの後始末) を
     *              同じロック内で実行してから返す。判定〜畳みの間に新鳴動が
     *              生まれてもその adopt はロック待ちなので畳んでよい
     * 対象 id は判定と同時に墓標へも書く (終端化を DB 書込みに先行させる)。
     * id 不明の停止はフェイルセーフで「今鳴っている → 座席確保済み → 最新の要求」の順に
     * 宛先を解決し、その id を墓標に書く (書かないと座席確保前の要求がそのまま鳴り出す)。
     * 戻り値の second は解決後の宛先 id (DB 終端化に使う)
     */
    private fun claimTeardownFor(
        targetId: String?,
        onIdle: () -> Unit = {},
    ): Pair<TeardownClaim, String?> =
        synchronized(ringLock) {
            if (destroyed) { onIdle(); return@synchronized TeardownClaim.IDLE to targetId }
            val cur = ringingInstanceId.value
            val target = targetId ?: cur ?: pendingStartId ?: requestedIds.lastOrNull()
            markDismissed(target)
            val wasRequested = target != null && requestedIds.remove(target)
            val claim = when {
                cur != null && target == cur -> { stopRingingLocked(); TeardownClaim.MATCHED }
                pendingStartId != null && target == pendingStartId -> {
                    stopRingingLocked(); TeardownClaim.PENDING
                }
                wasRequested -> TeardownClaim.REQUESTED
                cur == null && pendingStartId == null && requestedIds.isEmpty() -> {
                    onIdle(); TeardownClaim.IDLE
                }
                else -> TeardownClaim.BUSY
            }
            claim to target
        }

    /**
     * 潰した要求が差し替えた通知を、鳴動中 (または座席確保済み) の鳴動の停止UIへ戻す。
     * どちらも未開始の要求も無ければ滞留通知・オーバーレイを畳んで終了する。
     * 判定と前面解除を同じロック内で行う: 判定後に届いた ACTION_START は requestedIds に
     * 載ってから自分で前面化するので、その通知を消してしまうことがない
     */
    private fun restoreOrFinish() {
        var finishId: Int? = null
        synchronized(ringLock) {
            val live = ringingInstanceId.value ?: pendingStartId
            if (live != null) {
                // 判定と同じロック下で張り替える (判定後に現行鳴動が止まると通知だけ残るため)
                postRingNotification(live)
            } else if (requestedIds.isEmpty()) {
                removeRingNotification()
                RingingOverlay.hide(this)
                finishId = latestStartId
            }
        }
        finishId?.let { stopSelfResult(it) }
    }

    /** 鳴動中・座席確保済み・未開始の要求がどれも無い時だけサービスを終了する。 */
    private fun finishIfIdle() {
        val finishId = synchronized(ringLock) {
            if (ringingInstanceId.value != null || pendingStartId != null || requestedIds.isNotEmpty()) {
                return
            }
            latestStartId
        }
        stopSelfResult(finishId)
    }

    private suspend fun readVibrateSetting(): Boolean =
        try {
            withTimeoutOrNull(SETTINGS_READ_TIMEOUT_MS) {
                app.container.settings.flow.first().vibrateWhileRinging
            } ?: true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            true
        }

    /**
     * 世代前進から資源の畳みまでを ringLock 内で一続きに行う (呼び出し側がロック保持)。
     * 途中でロックを手放すと、その隙に座席確保・採用した後続鳴動の世代や資源を
     * この停止が無効化・破棄してしまう (BUG: 停止不能な音)。
     * 滞留中の鳴動要求は世代が進んだ時点で全て死ぬのでマーカーも消す。
     * 座席確保前の要求が待っていれば、その鳴動のために前面化と通知は残す
     */
    private fun stopRingingLocked() {
        ringGen++; pendingStartId = null
        teardownRingLocked(keepForeground = !destroyed && requestedIds.isNotEmpty())
    }

    /**
     * 現行の鳴動資源を畳む本体。必ず ringLock 内で、世代前進と同じクリティカル
     * セクションから呼ぶ (stopRingingLocked と beginRinging の座席確定)。
     * 共有フィールドから局所参照へ切り離してから止めるので、参照を捨てる
     * 対象は必ずここで止めた資源そのものになる。
     * keepForeground: 座席確定時は自分の前面化通知を残し、前面サービスのまま鳴動へ進む
     */
    private fun teardownRingLocked(keepForeground: Boolean = false) {
        val timeout = ringTimeoutJob; ringTimeoutJob = null
        val crescendo = crescendoJob; crescendoJob = null
        val player = mediaPlayer; mediaPlayer = null
        val vib = vibrator; vibrator = null
        val lock = wakeLock; wakeLock = null
        timeout?.cancel()
        crescendo?.cancel()
        if (player != null) {
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        runCatching { vib?.cancel() }
        runCatching { lock?.release() }
        if (!keepForeground) {
            // FGS 状態も確実に抜く (通知だけ消すと前面化中サービスとして残り得る)
            removeRingNotification()
        }
        RingingOverlay.hide(this)
        currentInstance = null
        ringingInstanceId.value = null
    }

    override fun onDestroy() {
        synchronized(ringLock) {
            destroyed = true
            stopRingingLocked()
        }
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
        /** 前面化前の設定読込の上限。startForeground の期限を設定読込で食い潰さない。 */
        private const val SETTINGS_READ_TIMEOUT_MS = 500L
        const val ACTION_START = "com.calendaralarm.action.START"
        const val ACTION_DISMISS = "com.calendaralarm.action.DISMISS"
        const val ACTION_SNOOZE = "com.calendaralarm.action.SNOOZE"
        const val EXTRA_INSTANCE_ID = "instance_id"
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
        // トラブルシューティング画面がチャンネルの重要度・ロック画面表示を検査するので公開
        const val CHANNEL_ID = "alarm_vib"
        const val CHANNEL_ID_NOVIB = "alarm_novib"
        private const val LEGACY_CHANNEL_ID = "alarm_v1"
        private val VIBRATION_PATTERN = longArrayOf(0, 800, 400, 800, 400)
        /** クレッシェンドの立ち上がり時間と開始音量。 */
        private const val CRESCENDO_MS = 30_000L
        private const val CRESCENDO_START = 0.05f
        // AlarmReceiver が DISMISS 受信時に通知を直接畳むため公開
        const val NOTIFICATION_ID = 1
        /** 停止後に遅れて着弾した鳴動通知を掃除するまでの待ち。NMS の投稿遅延 (~200ms) より十分長く。 */
        private const val LATE_POST_SWEEP_MS = 1_500L

        // 鳴動通知の投稿世代。サービス個体をまたいで共有し、停止後の遅延掃除が
        // 次の鳴動 (新しいサービス個体) の通知を消さないようにする
        private val notificationLock = Any()
        private var notificationEpoch = 0

        /**
         * 鳴動中インスタンス id。通知権限が無い等で鳴動画面が上がらない時の
         * 救出経路として、メイン画面がこれを監視して鳴動画面へ誘導する。
         */
        val ringingInstanceId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

        // DISMISS の終端化を DB 書込みより先に記録する墓標。DISMISS の
        // DB 書込みは非同期なので、これがないと「通知表示→停止→DB終端化前に
        // 鳴動処理が状態を読む」窓で終端済みの鳴動が起きる。
        // beginRinging 冒頭で必ず引く。サイズは最新32件に制限
        private val dismissTombstone = LinkedHashSet<String>()
        private val tombstoneLock = Any()

        /**
         * 鳴動通知チャンネルを作る (冪等)。チャンネルの振動属性は作成後に変更できないため、
         * 鳴動中バイブの ON/OFF でチャンネルを分ける。旧 1 チャンネル (alarm_v1) は消す。
         * 鳴動前でもトラブルシューティング画面から検査・設定できるよう公開する
         */
        fun ensureChannels(context: Context) {
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            mgr.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            fun channel(id: String, label: String, vibrate: Boolean) =
                NotificationChannel(id, label, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "アラーム鳴動時のフルスクリーン通知"
                    // 音は MediaPlayer が出すのでチャンネルは無音
                    setSound(null, null)
                    enableVibration(vibrate)
                    if (vibrate) vibrationPattern = VIBRATION_PATTERN
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            mgr.createNotificationChannels(
                listOf(
                    channel(CHANNEL_ID, "アラーム", vibrate = true),
                    channel(CHANNEL_ID_NOVIB, "アラーム（バイブなし）", vibrate = false),
                ),
            )
        }

        fun markDismissed(id: String?) {
            if (id == null) return
            synchronized(tombstoneLock) {
                if (dismissTombstone.add(id) && dismissTombstone.size > 32) {
                    dismissTombstone.remove(dismissTombstone.first())
                }
            }
        }

        fun isDismissed(id: String): Boolean =
            synchronized(tombstoneLock) { dismissTombstone.contains(id) }
    }
}
