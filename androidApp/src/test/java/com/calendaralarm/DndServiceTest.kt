package com.calendaralarm

import android.content.Intent
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.db.ScheduledInstanceEntity
import com.calendaralarm.engine.AlarmService
import com.calendaralarm.engine.StopUiPermissions
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.AlarmState
import kotlinx.coroutines.runBlocking
import org.junit.After
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * 「おやすみモード中も鳴らす」(#149)。サービスを実際に動かし、OFF でおやすみモード中は鳴らさず
 * すぐ終えること、鳴動中の同じアラームは上書きしないこと、その間の通知に全画面とバイブを
 * 付けないこと、おやすみモード尊重のチャンネルが bypassDnd しないことを確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DndServiceTest {

    private lateinit var app: CalendarAlarmApp
    private val controllers = mutableListOf<ServiceController<AlarmService>>()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(600_000, 0) }
        StopUiPermissions.probeOverride = { StopUiPermissions(true, true, false) }
        AlarmService.ringingInstanceId.value = null
    }

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.destroy() } }
        shadowOf(Looper.getMainLooper()).idle()
        StopUiPermissions.probeOverride = null
        AlarmService.ringingInstanceId.value = null
        AlarmService.settingsReadTimeoutMs = 500L
        runBlocking {
            app.container.settings.setIgnoreDnd(true)
            app.container.settings.setVibrateWhileRinging(true)
        }
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        shadowOf(nm).setConsolidatedNotificationPolicy(NotificationManager.Policy(0, 0, 0))
    }

    private fun setPolicy(alarmsAllowed: Boolean) {
        // 画面・鳴動判断は API 30+ で実効中の consolidated ポリシーを読む
        shadowOf(nm).setConsolidatedNotificationPolicy(
            NotificationManager.Policy(
                if (alarmsAllowed) NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS else 0, 0, 0,
            ),
        )
    }

    private fun seed(id: String) = runBlocking {
        app.container.db.scheduledInstances().upsert(
            listOf(ScheduledInstanceEntity.of(AlarmInstance(id, System.currentTimeMillis(), "会議", AlarmKind.TIMER))),
        )
    }

    private fun startIntent(id: String) = Intent(app, AlarmService::class.java).apply {
        action = AlarmService.ACTION_START
        putExtra(AlarmService.EXTRA_INSTANCE_ID, id)
    }

    private fun start(id: String): ServiceController<AlarmService> {
        val intent = Intent(app, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START
            putExtra(AlarmService.EXTRA_INSTANCE_ID, id)
        }
        val c = Robolectric.buildService(AlarmService::class.java, intent).create()
        controllers += c
        c.startCommand(0, 1)
        return c
    }

    private fun AlarmService.isPlaying(): Boolean = runCatching {
        (AlarmService::class.java.getDeclaredField("mediaPlayer").apply { isAccessible = true }.get(this) as MediaPlayer?)
            ?.isPlaying == true
    }.getOrDefault(false)

    private fun state(id: String) = runBlocking { app.container.db.scheduledInstances().byId(id)?.state }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    private fun AlarmService.ringLock(): Any =
        AlarmService::class.java.getDeclaredField("ringLock").apply { isAccessible = true }.get(this)!!

    @Test
    fun `無視OFFでOSがアラームを止める時は鳴らさずすぐ終えて鳴らせなかったになる`() {
        runBlocking { app.container.settings.setIgnoreDnd(false) }
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        seed("dnd-off")
        val c = Robolectric.buildService(AlarmService::class.java, startIntent("dnd-off")).create()
        controllers += c
        // 判定の直前 (ringLock 待ち) で止め、その間に出ている前面化の通知を見る。
        // 止めた後は前面化ごと畳まれて通知が残らないため
        var n: android.app.Notification? = null
        synchronized(c.get().ringLock()) {
            c.startCommand(0, 1)
            val end = System.currentTimeMillis() + 3_000
            while (n == null && System.currentTimeMillis() < end) {
                n = shadowOf(c.get()).lastForegroundNotification
                Thread.sleep(20)
            }
        }
        org.junit.Assert.assertNotNull("前面化の通知は出ていること (前面サービスの決まり)", n)
        assertNull("全画面 Intent を付けないこと", n!!.fullScreenIntent)
        assertEquals("バイブなし・おやすみモード尊重のチャンネル", AlarmService.CHANNEL_ID_NOVIB_DND, n!!.channelId)
        assertTrue("サービスがすぐ自分で止まること", waitUntil(5_000) { shadowOf(c.get()).isStoppedBySelf })
        assertFalse("音を出さないこと", c.get().isPlaying())
        assertEquals(AlarmState.MISSED.name, state("dnd-off"))
    }

    @Test
    fun `無視OFFで優先モードにアラーム許可がなければ鳴らさず鳴らせなかったになる`() {
        runBlocking { app.container.settings.setIgnoreDnd(false) }
        setPolicy(alarmsAllowed = false)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        seed("dnd-prio-no")
        val c = start("dnd-prio-no")
        assertTrue(waitUntil(5_000) { shadowOf(c.get()).isStoppedBySelf })
        assertFalse(c.get().isPlaying())
        assertEquals(AlarmState.MISSED.name, state("dnd-prio-no"))
    }

    @Test
    fun `無視OFFでもアラームのみなら鳴る`() {
        runBlocking { app.container.settings.setIgnoreDnd(false) }
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        seed("dnd-alarms")
        val c = start("dnd-alarms")
        assertTrue(waitUntil(5_000) { c.get().isPlaying() })
        assertEquals(AlarmState.FIRED.name, state("dnd-alarms"))
    }

    @Test
    fun `無視OFFで優先モードにアラーム許可があれば鳴る`() {
        runBlocking { app.container.settings.setIgnoreDnd(false) }
        setPolicy(alarmsAllowed = true)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        seed("dnd-prio-yes")
        val c = start("dnd-prio-yes")
        assertTrue(waitUntil(5_000) { c.get().isPlaying() })
        assertEquals(AlarmState.FIRED.name, state("dnd-prio-yes"))
    }

    @Test
    fun `無視ONならおやすみモード中でも鳴る`() {
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        seed("dnd-on")
        val c = start("dnd-on")
        assertTrue(waitUntil(5_000) { c.get().isPlaying() })
        assertEquals(AlarmState.FIRED.name, state("dnd-on"))
    }

    @Test
    fun `鳴動中の同じアラームへの二重STARTは途中でおやすみモードになっても上書きしない`() {
        seed("dnd-dup")
        val c = start("dnd-dup")
        assertTrue(waitUntil(5_000) { c.get().isPlaying() })
        // 鳴動中におやすみモードが始まり、無視が OFF に変わった後で同じ id の START が重なる
        runBlocking { app.container.settings.setIgnoreDnd(false) }
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        c.withIntent(startIntent("dnd-dup")).startCommand(0, 2)
        Thread.sleep(1_500)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("鳴り続けること", c.get().isPlaying())
        assertEquals("鳴動中の行を MISSED で上書きしないこと", AlarmState.FIRED.name, state("dnd-dup"))
    }

    @Test
    fun `おやすみモード尊重のチャンネルはおやすみモードを素通りしない`() {
        AlarmService.ensureChannels(app)
        assertFalse(nm.getNotificationChannel(AlarmService.CHANNEL_ID_VIB_DND).canBypassDnd())
        assertFalse(nm.getNotificationChannel(AlarmService.CHANNEL_ID_NOVIB_DND).canBypassDnd())
        assertTrue(nm.getNotificationChannel(AlarmService.CHANNEL_ID).canBypassDnd())
    }

    private fun ringNotification(): android.app.Notification? =
        nm.activeNotifications.firstOrNull { it.id == AlarmService.NOTIFICATION_ID }?.notification

    @Test
    fun `鳴動中に後発がおやすみモードで弾かれても先行の通知は先行のチャンネルのまま`() {
        // 先行 A: 設定 X (バイブあり・DND無視ON) で鳴る → 通知は bypass のチャンネル
        seed("own-a")
        val c = start("own-a")
        assertTrue(waitUntil(5_000) { c.get().isPlaying() })
        assertEquals(AlarmService.CHANNEL_ID, ringNotification()?.channelId)
        // 設定が Y (バイブなし・DND無視OFF) に変わり、おやすみモード (NONE) の中で後発 B が来る
        runBlocking {
            app.container.settings.setVibrateWhileRinging(false)
            app.container.settings.setIgnoreDnd(false)
        }
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        seed("own-b")
        c.withIntent(startIntent("own-b")).startCommand(0, 2)
        assertTrue("B は鳴らせなかったになる", waitUntil(5_000) { state("own-b") == AlarmState.MISSED.name })
        // B を畳んだ後 (restoreOrFinish) に A の通知が張り直されるのを待つ
        assertTrue(
            "A の通知は X のチャンネル (bypass) に戻ること。B の設定 Y のチャンネルで組み直さない",
            waitUntil(5_000) { ringNotification()?.channelId == AlarmService.CHANNEL_ID },
        )
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AlarmService.CHANNEL_ID, ringNotification()?.channelId)
        org.junit.Assert.assertNotNull("A の通知は全画面 Intent も持つこと", ringNotification()?.fullScreenIntent)
        assertTrue("A は鳴り続けること", c.get().isPlaying())
        assertEquals(AlarmState.FIRED.name, state("own-a"))
    }

    @Test
    fun `設定を読めない間の仮の通知も鳴らす側の既定で全画面を付け、一度目の投稿でアラートする`() {
        // 静かな仮の通知にすると、鳴らす時の張り替えが「更新」(一度だけアラート) になって全画面が出ない
        AlarmService.settingsReadTimeoutMs = 0L // 設定の読込が時間切れになる状態
        seed("unread")
        val c = Robolectric.buildService(AlarmService::class.java, startIntent("unread")).create()
        controllers += c
        var provisional: android.app.Notification? = null
        synchronized(c.get().ringLock()) {
            c.startCommand(0, 1)
            val end = System.currentTimeMillis() + 3_000
            while (provisional == null && System.currentTimeMillis() < end) {
                provisional = shadowOf(c.get()).lastForegroundNotification
                Thread.sleep(20)
            }
        }
        org.junit.Assert.assertNotNull(provisional)
        assertEquals(AlarmService.CHANNEL_ID, provisional!!.channelId)
        org.junit.Assert.assertNotNull("全画面 Intent を最初の投稿から付けること", provisional!!.fullScreenIntent)
        assertEquals(
            "最初の投稿は一度だけアラートの印を持たない (アラートする)",
            0, provisional!!.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE,
        )
        assertTrue(waitUntil(5_000) { c.get().isPlaying() })
    }
}
