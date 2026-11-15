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
 * アラーム音量と「徐々に大きく」(#148)。サービスを実際に動かし、設定した音量がプレイヤーに渡ること、
 * 0 (端末の音量に従う) では音量に触れないこと、徐々に大きくは小さい音から始めて上がっていくことを確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmVolumeServiceTest {

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
            app.container.settings.setAlarmVolumePercent(0)
            app.container.settings.setVolumeCrescendo(false)
        }
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

    private fun AlarmService.player(): MediaPlayer? = runCatching {
        AlarmService::class.java.getDeclaredField("mediaPlayer").apply { isAccessible = true }.get(this) as MediaPlayer?
    }.getOrNull()

    private fun AlarmService.playingPlayer(): MediaPlayer? = player()?.takeIf { it.isPlaying }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    /** 音量に一度も触れていないプレイヤーが返す値。0 (端末の音量に従う) の時の比較に使う */
    private fun untouchedVolume(): Float = shadowOf(MediaPlayer()).leftVolume

    @Test
    fun `音量を40にするとプレイヤーの左右の音量が0_4になる`() {
        runBlocking { app.container.settings.setAlarmVolumePercent(40) }
        seed("vol-40")
        val c = start("vol-40")
        assertTrue(waitUntil(5_000) { c.get().playingPlayer() != null })
        val shadow = shadowOf(c.get().player())
        assertEquals(0.4f, shadow.leftVolume, 0.001f)
        assertEquals(0.4f, shadow.rightVolume, 0.001f)
    }

    @Test
    fun `音量0は端末の音量に従うのでプレイヤーの音量に触れない`() {
        runBlocking { app.container.settings.setAlarmVolumePercent(0) }
        seed("vol-0")
        val c = start("vol-0")
        assertTrue(waitUntil(5_000) { c.get().playingPlayer() != null })
        assertEquals(untouchedVolume(), shadowOf(c.get().player()).leftVolume, 0.001f)
    }

    @Test
    fun `徐々に大きくは目標より小さい音から始めて上がっていく`() {
        runBlocking {
            app.container.settings.setAlarmVolumePercent(80)
            app.container.settings.setVolumeCrescendo(true)
        }
        seed("vol-cres")
        val c = start("vol-cres")
        assertTrue(waitUntil(5_000) { c.get().playingPlayer() != null })
        val shadow = shadowOf(c.get().player())
        val first = shadow.leftVolume
        assertTrue("鳴り始めは目標 0.8 より小さいこと: $first", first < 0.8f)
        // 30 秒を 20 段に分けて上げる (1 段 1.5 秒)。1 段ぶん待って上がったことを見る
        assertTrue(
            "時間がたつと鳴り始めより大きくなること (鳴り始め $first)",
            waitUntil(6_000) { shadow.leftVolume > first },
        )
        assertTrue("目標を超えないこと: ${shadow.leftVolume}", shadow.leftVolume <= 0.8f + 0.001f)
    }

    @Test
    fun `徐々に大きくがOFFなら鳴り始めから目標の音量`() {
        runBlocking {
            app.container.settings.setAlarmVolumePercent(80)
            app.container.settings.setVolumeCrescendo(false)
        }
        seed("vol-flat")
        val c = start("vol-flat")
        assertTrue(waitUntil(5_000) { c.get().playingPlayer() != null })
        assertEquals(0.8f, shadowOf(c.get().player()).leftVolume, 0.001f)
    }
}
