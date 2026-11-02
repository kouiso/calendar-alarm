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
 * 放置の自動スヌーズ (#145)。サービスを実際に動かし、時間切れで子を予約すること、
 * 回数上限に達した連鎖と OFF の時は今までどおり「鳴らせなかった」になることを確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoSnoozeServiceTest {

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
        runBlocking {
            app.container.settings.setRingDurationSec(0)
            app.container.settings.setAutoSnooze(false)
        }
    }

    private fun seed(id: String, snoozeSeq: Int = 0) = runBlocking {
        app.container.db.scheduledInstances().upsert(
            listOf(
                ScheduledInstanceEntity.of(
                    AlarmInstance(id, System.currentTimeMillis(), "会議", AlarmKind.TIMER, snoozeMinutes = 10, snoozeSeq = snoozeSeq),
                ),
            ),
        )
    }

    private fun children(id: String) = runBlocking {
        app.container.db.scheduledInstances().all().filter { it.id.startsWith("$id:snz") }.map { it.state }
    }

    /** 1 秒で時間切れにして、サービスが自分で止まるまで待つ */
    private fun ringUntilTimeout(id: String) {
        val c = start(id)
        assertTrue("鳴動が始まること", waitUntil(5_000) { c.get().isPlaying() })
        assertTrue("時間切れで止まること", waitUntil(6_000) { !c.get().isPlaying() && shadowOf(c.get()).isStoppedBySelf })
        waitUntil(2_000) { state(id) != AlarmState.FIRED.name }
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
    fun `自動スヌーズONで放置すると子を予約して元はSNOOZEDになる`() {
        runBlocking {
            app.container.settings.setRingDurationSec(1)
            app.container.settings.setAutoSnooze(true)
            app.container.settings.setAutoSnoozeMax(3)
        }
        seed("as-0", snoozeSeq = 0)
        ringUntilTimeout("as-0")
        assertEquals(AlarmState.SNOOZED.name, state("as-0"))
        assertEquals(listOf(AlarmState.PENDING.name), children("as-0"))
    }

    @Test
    fun `回数上限に達した連鎖は自動スヌーズせず鳴らせなかったになる`() {
        runBlocking {
            app.container.settings.setRingDurationSec(1)
            app.container.settings.setAutoSnooze(true)
            app.container.settings.setAutoSnoozeMax(3)
        }
        seed("as-cap", snoozeSeq = 3)
        ringUntilTimeout("as-cap")
        assertEquals(AlarmState.MISSED.name, state("as-cap"))
        assertEquals(emptyList<String>(), children("as-cap"))
    }

    @Test
    fun `自動スヌーズOFFなら今までどおり鳴らせなかったになる`() {
        runBlocking {
            app.container.settings.setRingDurationSec(1)
            app.container.settings.setAutoSnooze(false)
        }
        seed("as-off", snoozeSeq = 0)
        ringUntilTimeout("as-off")
        assertEquals(AlarmState.MISSED.name, state("as-off"))
        assertEquals(emptyList<String>(), children("as-off"))
    }
}
