package com.calendaralarm

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.db.ScheduledInstanceEntity
import com.calendaralarm.engine.AlarmService
import com.calendaralarm.engine.RingingOverlay
import com.calendaralarm.engine.StopUiPermissions
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

/**
 * 鳴動サービス (AlarmService) が代わりの画面 (RingingOverlay) を出す時、設定のスヌーズ分数が
 * 開閉式チップとして届くことの回帰テスト。
 * buildView を直に呼ぶテストでは、サービスからオーバーレイへの受け渡しの渡し忘れ
 * (チップと見出しが静かに消える) を検知できないので、実際の鳴動経路で確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-mdpi")
class AlarmServiceOverlayChipsTest {

    private lateinit var app: CalendarAlarmApp
    private var controller: ServiceController<AlarmService>? = null

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(600_000, 0) }
        StopUiPermissions.probeOverride = { StopUiPermissions(true, true, true) }
        ShadowSettings.setCanDrawOverlays(true)
        AlarmService.ringingInstanceId.value = null
    }

    @After
    fun tearDown() {
        runCatching { controller?.destroy() }
        RingingOverlay.hide(app)
        shadowOf(Looper.getMainLooper()).idle()
        StopUiPermissions.probeOverride = null
        AlarmService.ringingInstanceId.value = null
        runBlocking { app.container.settings.setSnoozePresets(listOf(5, 10, 15, 30, 45, 60)) }
    }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return true
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return cond()
    }

    /** 閉じていて見えない View も含め、文字が [text] と一致する TextView を木をたどって集める。 */
    private fun texts(root: View, text: String): List<View> {
        val found = ArrayList<View>()
        fun walk(v: View) {
            if (v is TextView && v.text.toString() == text) found.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return found
    }

    private fun ringWithOverlay(id: String, presets: List<Int>): View {
        runBlocking {
            app.container.settings.setSnoozePresets(presets)
            app.container.db.scheduledInstances().upsert(
                listOf(
                    ScheduledInstanceEntity.of(
                        AlarmInstance(id, System.currentTimeMillis(), "会議", AlarmKind.TIMER),
                    ),
                ),
            )
        }
        val intent = android.content.Intent(app, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START
            putExtra(AlarmService.EXTRA_INSTANCE_ID, id)
        }
        val c = Robolectric.buildService(AlarmService::class.java, intent).create()
        controller = c
        c.startCommand(0, 1)
        assertTrue("サービス経由でオーバーレイが出ること", waitUntil { RingingOverlay.isShowing() })
        val shown = RingingOverlay.shownViewForTest()
        assertNotNull("表示中のオーバーレイ View が取れること", shown)
        return shown!!
    }

    @Test
    fun `サービスが出すオーバーレイに設定のスヌーズ分数が開閉式チップとして載る`() {
        val view = ringWithOverlay("ov-chips", listOf(7, 25))

        assertEquals("見出しが 1 つ出ること (閉じた形)", 1, texts(view, "スヌーズ時間を選ぶ ▾").size)
        assertEquals("設定した 7 分のチップ", 1, texts(view, "+7分").size)
        assertEquals("設定した 25 分のチップ", 1, texts(view, "+25分").size)
        assertTrue("設定に無い分数は出ない", texts(view, "+5分").isEmpty() && texts(view, "+30分").isEmpty())

        texts(view, "スヌーズ時間を選ぶ ▾").single().performClick()
        assertEquals("押すと開いた形になること", 1, texts(view, "スヌーズ時間を選ぶ ▴").size)
    }
}
