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
import org.junit.Assert.assertEquals
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
 * 鳴動持続時間 (#148)。設定した秒数で鳴動サービスが自分で止まり、
 * 行が「鳴らせなかった」(MISSED) になることを、サービスを実際に動かして確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RingDurationServiceTest {

    private lateinit var app: CalendarAlarmApp
    private val controllers = mutableListOf<ServiceController<AlarmService>>()

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
        runBlocking { app.container.settings.setRingDurationSec(0) }
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

    @Test
    fun `鳴動持続時間を1秒にすると鳴動が自分で止まり鳴らせなかったになる`() {
        runBlocking { app.container.settings.setRingDurationSec(1) }
        seed("dur-1")
        val c = start("dur-1")
        assertTrue("鳴動が始まること", waitUntil(5_000) { c.get().isPlaying() })
        assertTrue(
            "設定した秒数で止まること",
            waitUntil(5_000) { !c.get().isPlaying() && shadowOf(c.get()).isStoppedBySelf },
        )
        assertEquals(AlarmState.MISSED.name, state("dur-1"))
    }

    @Test
    fun `既定の無制限では数秒たっても鳴り続ける`() {
        seed("dur-0")
        val c = start("dur-0")
        assertTrue(waitUntil(5_000) { c.get().isPlaying() })
        Thread.sleep(2_500)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("既定 (0 = 無制限扱いの上限) では 2.5 秒で止まらないこと", c.get().isPlaying())
        assertEquals(AlarmState.FIRED.name, state("dur-0"))
    }
}
