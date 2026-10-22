package com.calendaralarm

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.db.ScheduledInstanceEntity
import com.calendaralarm.engine.AlarmService
import com.calendaralarm.engine.RingingActivity
import com.calendaralarm.engine.SLIDE_THUMB_TAG
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.ui.theme.AppPalette
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 鳴動画面は端末が昼 (ライト) 設定でも常時ダークで描く (半睡状態で眩しくしないため)。
 * 端末をライトにして実際に起動し、停止スライダーの丸 (アクセント色で塗られる) の描画色が
 * ダーク側のパレットの primary になっていることを画素で確かめる。
 * テーマ呼び出しの darkTheme を端末設定追従に戻すと、丸がライト側の primary になり落ちる。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-notnight-mdpi")
class RingingActivityDarkThemeTest {

    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private lateinit var app: CalendarAlarmApp
    private var scenario: ActivityScenario<RingingActivity>? = null

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        AlarmService.ringingInstanceId.value = null
    }

    @After
    fun tearDown() {
        scenario?.close()
        AlarmService.ringingInstanceId.value = null
    }

    private fun rgb(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    private fun Color.rgb() = rgb(toArgb())

    @Test
    fun `端末がライト設定でも鳴動画面のアクセントはダーク側のパレットで描かれる`() {
        val night = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        assertEquals("前提: 端末はライト設定であること", Configuration.UI_MODE_NIGHT_NO, night)

        val id = "tm:dark-theme"
        runBlocking {
            app.container.db.scheduledInstances().upsert(
                listOf(
                    ScheduledInstanceEntity.of(
                        AlarmInstance(id, System.currentTimeMillis(), "DarkThemeCheck", AlarmKind.TIMER),
                    ),
                ),
            )
        }
        val launched = ActivityScenario.launch<RingingActivity>(RingingActivity.intent(app, id))
        scenario = launched
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("DarkThemeCheck").fetchSemanticsNodes().isNotEmpty()
        }

        val palette = AppPalette.byId(null)
        val dark = palette.dark.primary
        val light = palette.light.primary
        assertNotEquals("前提: ライトとダークで primary が違うこと", dark, light)

        // 丸の中央は停止の■で上書きされるので、丸の左寄り (円の内側) の 1 画素を見る
        val bounds = compose.onNodeWithTag(SLIDE_THUMB_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow
        var actual = 0
        launched.onActivity { activity ->
            val decor = activity.window.decorView
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            actual = bitmap.getPixel(
                (bounds.left + bounds.width * 0.12f).toInt(),
                bounds.center.y.toInt(),
            )
        }
        assertEquals(
            "停止の丸の色がダーク側の primary であること (actual=${rgb(actual)} dark=${dark.rgb()} light=${light.rgb()})",
            dark.rgb(),
            rgb(actual),
        )
    }

    /**
     * アプリのテーマは windowLightStatusBar=true (ライト端末向けの暗いアイコン)。
     * 鳴動画面は常時ダークなので、そのままだと暗い背景に暗い時計・電池・電波が重なって見えない。
     * 起動した画面のステータスバー・ナビゲーションバーのアイコンが明るい側であることを確かめる。
     */
    @Test
    fun `端末がライト設定でも鳴動画面のステータスバーのアイコンは明るい色になる`() {
        val night = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        assertEquals("前提: 端末はライト設定であること", Configuration.UI_MODE_NIGHT_NO, night)

        val id = "tm:dark-system-bars"
        runBlocking {
            app.container.db.scheduledInstances().upsert(
                listOf(
                    ScheduledInstanceEntity.of(
                        AlarmInstance(id, System.currentTimeMillis(), "SystemBarsCheck", AlarmKind.TIMER),
                    ),
                ),
            )
        }
        val launched = ActivityScenario.launch<RingingActivity>(RingingActivity.intent(app, id))
        scenario = launched
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("SystemBarsCheck").fetchSemanticsNodes().isNotEmpty()
        }

        var statusBarLight = true
        var navigationBarLight = true
        launched.onActivity { activity ->
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            statusBarLight = controller.isAppearanceLightStatusBars
            navigationBarLight = controller.isAppearanceLightNavigationBars
        }
        assertFalse("ステータスバーのアイコンが暗い色 (ライト用) のまま", statusBarLight)
        assertFalse("ナビゲーションバーのアイコンが暗い色 (ライト用) のまま", navigationBarLight)
    }
}
