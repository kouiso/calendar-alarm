package com.calendaralarm

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.db.ScheduledInstanceEntity
import com.calendaralarm.engine.AlarmReceiver
import com.calendaralarm.engine.AlarmService
import com.calendaralarm.engine.RingingOverlay
import com.calendaralarm.engine.StopUiPermissions
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 鳴動サービスの停止経路の回帰テスト。
 * サービスは実コード (IO コルーチン + Robolectric の MediaPlayer/通知/WindowManager) で動かす。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmServiceStopTest {

    private lateinit var app: CalendarAlarmApp
    private val controllers = mutableListOf<ServiceController<AlarmService>>()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(600_000, 0) }
        grant(notifications = true, fsi = true, overlay = true)
        AlarmService.ringingInstanceId.value = null
    }

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.destroy() } }
        idleMain()
        StopUiPermissions.probeOverride = null
        AlarmService.ringingInstanceId.value = null
        runBlocking { app.container.settings.setVibrateWhileRinging(true) }
    }

    // ---- helpers ----

    private fun grant(notifications: Boolean, fsi: Boolean, overlay: Boolean) {
        StopUiPermissions.probeOverride = { StopUiPermissions(notifications, fsi, overlay) }
        ShadowSettings.setCanDrawOverlays(overlay)
    }

    private fun seed(id: String, title: String = "会議") = runBlocking {
        app.container.db.scheduledInstances().upsert(
            listOf(
                ScheduledInstanceEntity.of(
                    AlarmInstance(id, System.currentTimeMillis(), title, AlarmKind.TIMER),
                ),
            ),
        )
    }

    private fun startIntent(id: String) = Intent(app, AlarmService::class.java).apply {
        action = AlarmService.ACTION_START
        putExtra(AlarmService.EXTRA_INSTANCE_ID, id)
    }

    private fun dismissIntent(id: String?) = Intent(app, AlarmService::class.java).apply {
        action = AlarmService.ACTION_DISMISS
        if (id != null) putExtra(AlarmService.EXTRA_INSTANCE_ID, id)
    }

    private fun startService(id: String): ServiceController<AlarmService> {
        val c = Robolectric.buildService(AlarmService::class.java, startIntent(id)).create()
        controllers += c
        c.startCommand(0, 1)
        return c
    }

    private fun AlarmService.player(): MediaPlayer? =
        AlarmService::class.java.getDeclaredField("mediaPlayer")
            .apply { isAccessible = true }
            .get(this) as MediaPlayer?

    private fun AlarmService.isPlaying(): Boolean =
        runCatching { player()?.isPlaying == true }.getOrDefault(false)

    private fun AlarmService.ringLock(): Any =
        AlarmService::class.java.getDeclaredField("ringLock")
            .apply { isAccessible = true }.get(this)!!

    private fun AlarmService.ringTimeoutJob(): kotlinx.coroutines.Job? =
        AlarmService::class.java.getDeclaredField("ringTimeoutJob")
            .apply { isAccessible = true }.get(this) as kotlinx.coroutines.Job?

    /**
     * 鳴動開始コルーチンを座席確保 (ringLock) の手前で止めたまま [block] を実行する。
     * onStartCommand はメインスレッドで走るのでロックを再入できる
     */
    private fun <T> holdingSeat(c: ServiceController<AlarmService>, block: () -> T): T =
        synchronized(c.get().ringLock()) { block() }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            idleMain()
            if (cond()) return true
            Thread.sleep(20)
        }
        idleMain()
        return cond()
    }

    /** IO 上の鳴動開始コルーチンが走り切るまで待つ (成否どちらでも終わる時間)。 */
    private fun settle(ms: Long = 2_000) {
        waitUntil(ms) { false }
    }

    private fun audits(): List<Pair<String, String>> = runBlocking {
        app.container.repository.auditFlow(200).first().map { it.action to it.detail }
    }

    private fun ringingNotification(): Notification? =
        shadowOf(nm).allNotifications.firstOrNull()

    private fun stopAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { it.title?.toString() == "停止" }

    private fun stopActionTarget(n: Notification): String? =
        stopAction(n)?.let { shadowOf(it.actionIntent).savedIntent }
            ?.getStringExtra(AlarmService.EXTRA_INSTANCE_ID)

    // ---- overlay gating ----

    @Test
    fun `FSI許可かつオーバーレイ許可でも停止オーバーレイを出す`() {
        seed("ov-fsi")
        val c = startService("ov-fsi")
        assertTrue("鳴動が始まること", waitUntil { c.get().isPlaying() })
        assertTrue(
            "FSI 許可済みでもオーバーレイが出ること (ロック解除中は FSI が heads-up に畳まれるため)",
            waitUntil { RingingOverlay.isShowing() },
        )
        assertTrue(waitUntil { audits().any { it.first == "OVERLAY_SHOWN" && it.second.startsWith("ov-fsi") } })
        assertTrue(audits().none { it.first == "WARN" })
    }

    // ---- zombie race ----

    @Test
    fun `鳴動開始処理中にid無しDISMISSが届いても音が残らずサービスが止まる`() {
        seed("zr-noid")
        val c = Robolectric.buildService(AlarmService::class.java, startIntent("zr-noid")).create()
        controllers += c
        // 鳴動開始コルーチンが座席確保 (= MediaPlayer.start() より前) に達する前に停止を届ける
        holdingSeat(c) {
            c.startCommand(0, 1)
            c.withIntent(dismissIntent(null)).startCommand(0, 2)
        }
        settle()
        assertFalse("停止後に音が鳴り続けないこと", c.get().isPlaying())
        assertNull(c.get().player())
        assertTrue("サービスが自身を停止すること", shadowOf(c.get()).isStoppedBySelf)
        assertNull(AlarmService.ringingInstanceId.value)
        assertNull("停止後に鳴動通知が残らないこと", ringingNotification())
    }

    @Test
    fun `鳴動開始処理中に対象指定DISMISSが届いても音が残らずサービスが止まる`() {
        seed("zr-id")
        val c = Robolectric.buildService(AlarmService::class.java, startIntent("zr-id")).create()
        controllers += c
        holdingSeat(c) {
            c.startCommand(0, 1)
            c.withIntent(dismissIntent("zr-id")).startCommand(0, 2)
        }
        settle()
        assertFalse(c.get().isPlaying())
        assertNull(c.get().player())
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        assertNull(AlarmService.ringingInstanceId.value)
        assertNull(ringingNotification())
    }

    @Test
    fun `A鳴動中に未開始のBを停止してもAは鳴り続け通知の停止はAを指す`() {
        seed("multi-a", "A")
        seed("multi-b", "B")
        val c = startService("multi-a")
        assertTrue(waitUntil { c.get().isPlaying() && AlarmService.ringingInstanceId.value == "multi-a" })
        val playerA = c.get().player()
        assertTrue(waitUntil { c.get().ringTimeoutJob()?.isActive == true })
        val timeoutA = c.get().ringTimeoutJob()
        // B の開始コルーチンを座席確保の手前で止めた状態で、B への停止を届ける
        holdingSeat(c) {
            c.withIntent(startIntent("multi-b")).startCommand(0, 2)
            c.withIntent(dismissIntent("multi-b")).startCommand(0, 3)
        }
        settle()
        assertTrue("A の鳴動が止められていないこと", c.get().isPlaying())
        assertTrue(c.get().player() === playerA)
        assertTrue("A の鳴動上限タイマーが巻き添えで止まらないこと", timeoutA === c.get().ringTimeoutJob() && timeoutA!!.isActive)
        assertTrue(AlarmService.isDismissed("multi-b"))
        assertEquals("multi-a", AlarmService.ringingInstanceId.value)
        assertFalse("前面サービスのまま鳴動を続けること", shadowOf(c.get()).isForegroundStopped)
        val n = ringingNotification()
        assertNotNull(n)
        assertEquals("通知の「停止」が A を止める宛先であること", "multi-a", stopActionTarget(n!!))
        // 実際に A を止められる
        c.withIntent(dismissIntent("multi-a")).startCommand(0, 4)
        assertTrue(waitUntil { !c.get().isPlaying() && shadowOf(c.get()).isStoppedBySelf })
    }

    private fun state(id: String): String? = runBlocking {
        app.container.db.scheduledInstances().byId(id)?.state
    }

    @Test
    fun `同時刻の2件で押し出された側はFIREDのまま残らず停止後に再鳴動の対象にならない`() {
        seed("dup-a", "A")
        seed("dup-b", "B")
        val c = Robolectric.buildService(AlarmService::class.java, startIntent("dup-a")).create()
        controllers += c
        // 実機 10/14 0:00 と同じく、2件の START がほぼ同時に届く
        c.startCommand(0, 1)
        c.withIntent(startIntent("dup-b")).startCommand(0, 2)
        assertTrue(waitUntil { c.get().isPlaying() && AlarmService.ringingInstanceId.value != null })
        settle(1_000)
        val live = AlarmService.ringingInstanceId.value!!
        val loser = if (live == "dup-a") "dup-b" else "dup-a"
        assertEquals("押し出された側は DISMISSED に終端化されること", "DISMISSED", state(loser))
        assertTrue(audits().any { it.first == "SUPERSEDED" && it.second.startsWith(loser) })
        c.withIntent(dismissIntent(live)).startCommand(0, 3)
        assertTrue(waitUntil { !c.get().isPlaying() && shadowOf(c.get()).isStoppedBySelf })
        assertEquals("DISMISSED", state(live))
        assertNull(
            "停止後に鳴動猶予内の FIRED が残らないこと (残るとプロセス再起動で再鳴動する)",
            runBlocking { app.container.repository.firedWithinGrace() },
        )
    }

    @Test
    fun `鳴動中のAを後発のBが押し出すとAは終端化されBの停止で全て止まる`() {
        seed("sup-a", "A")
        seed("sup-b", "B")
        val c = startService("sup-a")
        assertTrue(waitUntil { c.get().isPlaying() && AlarmService.ringingInstanceId.value == "sup-a" })
        c.withIntent(startIntent("sup-b")).startCommand(0, 2)
        assertTrue(waitUntil { AlarmService.ringingInstanceId.value == "sup-b" && c.get().isPlaying() })
        assertTrue(waitUntil { state("sup-a") == "DISMISSED" })
        assertTrue(AlarmService.isDismissed("sup-a"))
        c.withIntent(dismissIntent("sup-b")).startCommand(0, 3)
        assertTrue(waitUntil { !c.get().isPlaying() && shadowOf(c.get()).isStoppedBySelf })
        assertNull(runBlocking { app.container.repository.firedWithinGrace() })
    }

    @Test
    fun `id無し停止は最新の未開始要求を潰し先の要求は鳴る`() {
        seed("nw-a", "A")
        seed("nw-b", "B")
        val c = Robolectric.buildService(AlarmService::class.java, startIntent("nw-a")).create()
        controllers += c
        holdingSeat(c) {
            c.startCommand(0, 1)
            c.withIntent(startIntent("nw-b")).startCommand(0, 2)
            c.withIntent(dismissIntent(null)).startCommand(0, 3)
        }
        assertTrue(waitUntil { c.get().isPlaying() && AlarmService.ringingInstanceId.value == "nw-a" })
        settle()
        assertEquals("nw-a", AlarmService.ringingInstanceId.value)
        assertTrue(AlarmService.isDismissed("nw-b"))
        assertFalse(AlarmService.isDismissed("nw-a"))
        assertFalse(shadowOf(c.get()).isForegroundStopped)
        assertEquals("nw-a", stopActionTarget(ringingNotification()!!))
    }

    @Test
    fun `未開始の要求がある間の無関係な停止は前面化と要求を潰さない`() {
        seed("busy-a")
        val c = Robolectric.buildService(AlarmService::class.java, startIntent("busy-a")).create()
        controllers += c
        holdingSeat(c) {
            c.startCommand(0, 1)
            c.withIntent(dismissIntent("busy-x")).startCommand(0, 2)
        }
        assertTrue(waitUntil { c.get().isPlaying() && AlarmService.ringingInstanceId.value == "busy-a" })
        settle()
        assertFalse("前面サービスのまま鳴動すること", shadowOf(c.get()).isForegroundStopped)
        assertFalse(shadowOf(c.get()).isStoppedBySelf)
        assertNotNull(ringingNotification())
        assertTrue(waitUntil { audits().any { it.first == "DISMISS_LATE" && it.second.startsWith("busy-x") } })
    }

    @Test
    fun `座席確保済みで未開始の鳴動へのid無し停止はその要求を潰してサービスを止める`() {
        val c = Robolectric.buildService(AlarmService::class.java).create()
        controllers += c
        AlarmService::class.java.getDeclaredField("pendingStartId")
            .apply { isAccessible = true }.set(c.get(), "pd-a")
        c.withIntent(dismissIntent(null)).startCommand(0, 1)
        assertTrue(waitUntil { shadowOf(c.get()).isStoppedBySelf })
        assertTrue(AlarmService.isDismissed("pd-a"))
        assertNull(AlarmService::class.java.getDeclaredField("pendingStartId")
            .apply { isAccessible = true }.get(c.get()))
        assertNull(ringingNotification())
    }

    // ---- 停止の後始末と後続鳴動の開始が交錯するレース ----

    /** 停止 (stop/release) が呼ばれた瞬間に一度だけ [hook] を差し込めるプレイヤー。 */
    private class HookPlayer(
        private val onStop: (() -> Unit)? = null,
        private val onRelease: (() -> Unit)? = null,
    ) : MediaPlayer() {
        private val fired = AtomicBoolean(false)
        override fun stop() {
            if (onStop != null && fired.compareAndSet(false, true)) onStop.invoke()
            super.stop()
        }
        override fun release() {
            if (onRelease != null && fired.compareAndSet(false, true)) onRelease.invoke()
            super.release()
        }
    }

    private fun MediaPlayer.playing(): Boolean = runCatching { isPlaying }.getOrDefault(false)

    /** 鳴動開始コルーチンが座席確保 (ringLock) の取得待ちでブロックしているか。 */
    private fun successorBlockedAtSeat(): Boolean =
        Thread.getAllStackTraces().any { (t, frames) ->
            t.state == Thread.State.BLOCKED &&
                frames.any { it.className == AlarmService::class.java.name && it.methodName == "beginRinging" }
        }

    /**
     * A の鳴動中に B の鳴動要求を受理させ、B の開始コルーチンが座席確保より先へ
     * 進めないよう前面化の notificationLock を塞いでおく。A への停止の後始末が A の
     * プレイヤーへ触れた瞬間 (stop か release) に B を解放し、B が自分のプレイヤーを
     * 採用するか、座席確保のロック待ちでブロックするまで停止側を待たせる。
     * 後始末が開始と不可分でなければ、B はその隙に座席確保・プレイヤー採用まで進む。
     * 戻り値は「後始末の途中で採用された B のプレイヤー」(不可分なら null)
     */
    private fun dismissWhileSuccessorStarts(
        c: ServiceController<AlarmService>,
        a: String,
        b: String,
        hookOnRelease: Boolean,
    ): MediaPlayer? {
        val svc = c.get()
        assertTrue(waitUntil { svc.isPlaying() && AlarmService.ringingInstanceId.value == a })
        assertTrue(waitUntil { svc.ringTimeoutJob()?.isActive == true })

        val notificationLock = AlarmService::class.java.getDeclaredField("notificationLock")
            .apply { isAccessible = true }.get(null)!!
        val held = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val gate = Thread {
            synchronized(notificationLock) {
                held.countDown()
                releaseB.await(10, TimeUnit.SECONDS)
            }
        }.apply { isDaemon = true; start() }
        assertTrue(held.await(5, TimeUnit.SECONDS))

        var adoptedDuringTeardown: MediaPlayer? = null
        var successorSettled = false
        lateinit var hookA: HookPlayer
        val hook = {
            releaseB.countDown()
            val end = System.currentTimeMillis() + SUCCESSOR_WAIT_MS
            while (System.currentTimeMillis() < end) {
                val p = svc.player()
                if (p != null && p !== hookA) { adoptedDuringTeardown = p; successorSettled = true; break }
                if (successorBlockedAtSeat()) { successorSettled = true; break }
                Thread.sleep(10)
            }
        }
        hookA = if (hookOnRelease) HookPlayer(onRelease = hook) else HookPlayer(onStop = hook)
        hookA.setDataSource(app, android.net.Uri.parse("content://test/$a"))
        hookA.prepare()
        hookA.start()
        val realA = svc.player()!!
        AlarmService::class.java.getDeclaredField("mediaPlayer")
            .apply { isAccessible = true }.set(svc, hookA)
        realA.stop(); realA.release()

        c.withIntent(startIntent(b)).startCommand(0, 2)
        c.withIntent(dismissIntent(a)).startCommand(0, 3)
        releaseB.countDown()
        gate.join(5_000)
        assertTrue("後始末の途中で後続が採用まで進むか座席待ちに入ること (交錯が成立したこと)", successorSettled)
        assertFalse("A の音が止まっていること", hookA.playing())
        return adoptedDuringTeardown
    }

    private fun assertSuccessorRingsAndStops(c: ServiceController<AlarmService>, b: String, orphan: MediaPlayer?) {
        val svc = c.get()
        if (orphan != null) {
            settle(500)
            assertFalse(
                "後始末の途中で採用された後続のプレイヤーが、参照を失ったまま鳴り続けないこと",
                orphan.playing() && svc.player() !== orphan,
            )
        }
        assertTrue(
            "A の停止が後続 B の鳴動を巻き添えにしないこと",
            waitUntil { svc.isPlaying() && AlarmService.ringingInstanceId.value == b },
        )
        val playerB = svc.player()!!
        assertEquals("通知の「停止」が B を指すこと", b, stopActionTarget(ringingNotification()!!))
        c.withIntent(dismissIntent(b)).startCommand(0, 4)
        assertTrue(waitUntil { !playerB.playing() && shadowOf(svc).isStoppedBySelf })
        assertFalse("停止後にどのプレイヤーも鳴っていないこと", orphan?.playing() == true)
        assertNull(svc.player())
        assertNull(AlarmService.ringingInstanceId.value)
    }

    @Test
    fun `停止の後始末がプレイヤーを止めている最中に後続が採用されても後続を殺さない`() {
        seed("race-stop-a", "A")
        seed("race-stop-b", "B")
        val c = startService("race-stop-a")
        val adopted = dismissWhileSuccessorStarts(c, "race-stop-a", "race-stop-b", hookOnRelease = false)
        assertSuccessorRingsAndStops(c, "race-stop-b", adopted)
        assertNull("停止の後始末中に後続が座席確保・採用まで進まないこと", adopted)
    }

    @Test
    fun `停止の後始末がプレイヤーを解放した直後に後続が採用されても参照を失わない`() {
        seed("race-rel-a", "A")
        seed("race-rel-b", "B")
        val c = startService("race-rel-a")
        val adopted = dismissWhileSuccessorStarts(c, "race-rel-a", "race-rel-b", hookOnRelease = true)
        assertSuccessorRingsAndStops(c, "race-rel-b", adopted)
        assertNull("停止の後始末中に後続が座席確保・採用まで進まないこと", adopted)
    }

    // ---- 停止後に遅れて着弾する鳴動通知 ----

    @Test
    fun `停止直後に鳴動通知が遅れて着弾しても後から掃除され残らない`() {
        seed("late-post")
        val c = startService("late-post")
        assertTrue(waitUntil { c.get().isPlaying() })
        val posted = ringingNotification()!!
        c.withIntent(dismissIntent("late-post")).startCommand(0, 2)
        assertTrue(waitUntil { !c.get().isPlaying() && shadowOf(c.get()).isStoppedBySelf })
        assertNull(ringingNotification())
        // NMS は投稿を遅延処理するため、鳴り始め直後の停止では cancel の後に前面化通知が着弾し得る
        nm.notify(AlarmService.NOTIFICATION_ID, posted)
        assertNotNull(ringingNotification())
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertNull("遅れて着弾した鳴動通知が掃除されること (次の鳴動の FSI を潰さない)", ringingNotification())
    }

    @Test
    fun `停止後の遅延掃除は直後に始まった次の鳴動の通知を消さない`() {
        seed("sweep-a", "A")
        seed("sweep-b", "B")
        val a = startService("sweep-a")
        assertTrue(waitUntil { a.get().isPlaying() })
        a.withIntent(dismissIntent("sweep-a")).startCommand(0, 2)
        assertTrue(waitUntil { !a.get().isPlaying() && shadowOf(a.get()).isStoppedBySelf })
        a.destroy()
        val b = startService("sweep-b")
        assertTrue(waitUntil { b.get().isPlaying() && AlarmService.ringingInstanceId.value == "sweep-b" })
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        val n = ringingNotification()
        assertNotNull("次の鳴動の通知が残っていること", n)
        assertEquals("sweep-b", stopActionTarget(n!!))
    }

    // ---- notification 停止 action ----

    @Test
    fun `オーバーレイ未許可でも鳴動通知に停止アクションがあり押すと鳴動が止まる`() {
        grant(notifications = true, fsi = true, overlay = false)
        seed("nt-stop")
        val c = startService("nt-stop")
        assertTrue(waitUntil { c.get().isPlaying() })
        assertFalse(RingingOverlay.isShowing())
        assertFalse("鳴動中は前面サービスであること", shadowOf(c.get()).isForegroundStopped)

        val n = ringingNotification()
        assertNotNull("鳴動通知が出ていること", n)
        assertTrue("常駐通知であること", n!!.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val action = stopAction(n)
        assertNotNull("通知に「停止」アクションがあること", action)
        val saved = shadowOf(action!!.actionIntent).savedIntent
        assertTrue("停止は放送経路 (サービス死亡時も届く)", shadowOf(action.actionIntent).isBroadcast)
        assertEquals(AlarmReceiver::class.java.name, saved.component?.className)
        assertEquals(AlarmService.ACTION_DISMISS, saved.action)
        assertEquals("nt-stop", saved.getStringExtra(AlarmService.EXTRA_INSTANCE_ID))

        // 押下: 放送 → AlarmReceiver → サービスへ DISMISS 転送
        action.actionIntent.send()
        val shadowApp = shadowOf(app)
        var forwarded: Intent? = null
        assertTrue(
            "receiver がサービスへ DISMISS を転送すること",
            waitUntil {
                forwarded = generateSequence { shadowApp.nextStartedService }
                    .firstOrNull { it.action == AlarmService.ACTION_DISMISS }
                forwarded != null
            },
        )
        c.withIntent(forwarded!!).startCommand(0, 2)
        assertTrue(waitUntil { !c.get().isPlaying() && shadowOf(c.get()).isStoppedBySelf })
        assertNull(c.get().player())
        assertNull(AlarmService.ringingInstanceId.value)
        assertNull(ringingNotification())
    }

    @Test
    fun `鳴動通知は二重アラートせずバイブ設定に応じたチャンネルを使い旧チャンネルを消す`() {
        // 旧チャンネルが残っている更新ユーザーを再現
        nm.createNotificationChannel(
            android.app.NotificationChannel("alarm_v1", "アラーム", NotificationManager.IMPORTANCE_HIGH),
        )
        runBlocking { app.container.settings.setVibrateWhileRinging(false) }
        seed("ch-novib")
        val c = startService("ch-novib")
        assertTrue(waitUntil { c.get().isPlaying() })
        val n = ringingNotification()!!
        assertEquals("alarm_novib", n.channelId)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertNull("旧チャンネル alarm_v1 は削除されること", nm.getNotificationChannel("alarm_v1"))
        assertFalse(nm.getNotificationChannel("alarm_novib").shouldVibrate())
        assertTrue(nm.getNotificationChannel("alarm_vib").shouldVibrate())
    }

    // ---- WARN audit for degraded states ----

    @Test
    fun `オーバーレイ未許可で鳴るとWARN監査が残る (FSI許可でも)`() {
        grant(notifications = true, fsi = true, overlay = false)
        seed("warn-ov")
        val c = startService("warn-ov")
        assertTrue(waitUntil { c.get().isPlaying() })
        assertTrue(
            waitUntil {
                audits().any { it.first == "WARN" && it.second.startsWith("warn-ov") && "オーバーレイ未許可" in it.second }
            },
        )
    }

    @Test
    fun `FSI拒否で鳴るとWARN監査が残りオーバーレイは出る`() {
        grant(notifications = true, fsi = false, overlay = true)
        seed("warn-fsi")
        val c = startService("warn-fsi")
        assertTrue(waitUntil { c.get().isPlaying() })
        assertTrue(waitUntil { RingingOverlay.isShowing() })
        assertTrue(
            waitUntil {
                audits().any { it.first == "WARN" && it.second.startsWith("warn-fsi") && "FSI拒否" in it.second }
            },
        )
        assertTrue(audits().none { "オーバーレイ未許可" in it.second })
    }

    @Test
    fun `通知OFFで鳴るとWARN監査が残る`() {
        grant(notifications = false, fsi = true, overlay = true)
        seed("warn-nt")
        val c = startService("warn-nt")
        assertTrue(waitUntil { c.get().isPlaying() })
        assertTrue(
            waitUntil {
                audits().any { it.first == "WARN" && it.second.startsWith("warn-nt") && "通知OFF" in it.second }
            },
        )
    }

    private companion object {
        /** 後始末の途中で後続が採用か座席待ちに至るまでを待つ上限 (遅い CI でも交錯を取り逃がさない長さ) */
        const val SUCCESSOR_WAIT_MS = 10_000L
    }
}
