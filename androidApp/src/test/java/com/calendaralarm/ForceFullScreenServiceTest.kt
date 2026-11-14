package com.calendaralarm

import android.content.Intent
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.db.ScheduledInstanceEntity
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

/**
 * 「フルスクリーンを強制」(#153)。サービスを実際に動かし、OFF の時は前面化の通知に
 * 全画面 Intent が付かず、オーバーレイも出ないこと、既定 (ON) では今までどおり出ることを確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForceFullScreenServiceTest {

    private lateinit var app: CalendarAlarmApp
    private val controllers = mutableListOf<ServiceController<AlarmService>>()

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(600_000, 0) }
        // 通知・全画面・オーバーレイの許可は全部ある状態
        StopUiPermissions.probeOverride = { StopUiPermissions(true, true, true) }
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        AlarmService.ringingInstanceId.value = null
    }

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.destroy() } }
        shadowOf(Looper.getMainLooper()).idle()
        StopUiPermissions.probeOverride = null
        AlarmService.ringingInstanceId.value = null
        runBlocking {
            app.container.settings.setForceFullScreen(true)
            app.container.settings.setVibrateWhileRinging(true)
        }
        StopUiPermissions.lastProbedVibrate = null
        StopUiPermissions.lastProbedIgnoreDnd = null
    }

    private fun seed(id: String) = runBlocking {
        app.container.db.scheduledInstances().upsert(
            listOf(ScheduledInstanceEntity.of(AlarmInstance(id, System.currentTimeMillis(), "会議", AlarmKind.TIMER))),
        )
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

    private fun audits() = runBlocking {
        app.container.repository.auditFlow(200).first().map { it.action to it.detail }
    }

    @Test
    fun `強制OFFなら前面化の通知に全画面Intentが付かずオーバーレイも出ない`() {
        runBlocking { app.container.settings.setForceFullScreen(false) }
        seed("fsi-off")
        val c = start("fsi-off")
        assertTrue("鳴動が始まること", waitUntil(5_000) { c.get().isPlaying() })
        val n = shadowOf(c.get()).lastForegroundNotification
        assertNotNull(n)
        assertNull("全画面 Intent を付けないこと", n!!.fullScreenIntent)
        assertNotNull("通知を開けば鳴動画面へ行けること", n.contentIntent)
        Thread.sleep(500)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("オーバーレイ許可があっても出さないこと", RingingOverlay.isShowing())
        assertTrue(waitUntil(3_000) { audits().any { it.first == "WARN" && "フルスクリーン強制OFF" in it.second } })
    }

    @Test
    fun `既定のONでは全画面Intentもオーバーレイも出る`() {
        seed("fsi-on")
        val c = start("fsi-on")
        assertTrue(waitUntil(5_000) { c.get().isPlaying() })
        assertNotNull(shadowOf(c.get()).lastForegroundNotification!!.fullScreenIntent)
        assertTrue(waitUntil(3_000) { RingingOverlay.isShowing() })
    }

    @Test
    fun `停止手段の判定にはバイブ設定で選ぶ実際のチャンネルを渡す`() {
        runBlocking { app.container.settings.setVibrateWhileRinging(false) }
        seed("fsi-novib")
        val c = start("fsi-novib")
        assertTrue(waitUntil(5_000) { c.get().isPlaying() })
        assertTrue(waitUntil(3_000) { StopUiPermissions.lastProbedVibrate != null })
        assertEquals("バイブなしで鳴る時はバイブなしのチャンネルで判定すること", false, StopUiPermissions.lastProbedVibrate)
    }

    @Test
    fun `おやすみモード尊重で鳴る時は停止手段の判定にも尊重側のチャンネルを渡す`() {
        // おやすみモード中ではない (割り込みフィルタ=すべて許可) ので鳴る。通知は尊重側のチャンネルに出る
        runBlocking { app.container.settings.setIgnoreDnd(false) }
        seed("fsi-dnd")
        val c = start("fsi-dnd")
        assertTrue(waitUntil(5_000) { c.get().isPlaying() })
        assertEquals(AlarmService.CHANNEL_ID_VIB_DND, shadowOf(c.get()).lastForegroundNotification!!.channelId)
        assertTrue(waitUntil(3_000) { StopUiPermissions.lastProbedIgnoreDnd != null })
        assertEquals("通知を出したのと同じチャンネルで判定すること", false, StopUiPermissions.lastProbedIgnoreDnd)
    }
}
