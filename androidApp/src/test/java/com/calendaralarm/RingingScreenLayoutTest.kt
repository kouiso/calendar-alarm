package com.calendaralarm

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.db.ScheduledInstanceEntity
import com.calendaralarm.engine.AlarmService
import com.calendaralarm.engine.RingingActivity
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 鳴動画面のレイアウト回帰テスト。
 * 小さい画面 (320x640dp) で時計リングや長いタイトルが縦を食うと、停止ボタンが
 * 画面外に押し出されて押せなくなる (エミュレータ E2E の locked_screen_on で実測)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-mdpi")
class RingingScreenLayoutTest {

    // 既定の UnconfinedTestDispatcher だと、Room/DataStore のスレッドから emit された
    // collectAsState の再開と Recomposer がそのスレッド上で走り、メインスレッドの初回合成と
    // 競合して CompositionImpl.addPendingInvalidationsLocked で NPE になることがある。
    // StandardTestDispatcher に積めば mainClock のフレーム進行時にメインスレッドで実行される。
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
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun launch(id: String, title: String) {
        runBlocking {
            app.container.db.scheduledInstances().upsert(
                listOf(
                    ScheduledInstanceEntity.of(
                        AlarmInstance(id, System.currentTimeMillis(), title, AlarmKind.TIMER),
                    ),
                ),
            )
        }
        scenario = ActivityScenario.launch(RingingActivity.intent(app, id))
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertStopFullyOnScreen() {
        compose.onNodeWithText("停止").assertIsDisplayed()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val stop = compose.onNodeWithText("停止").fetchSemanticsNode().boundsInRoot
        assertTrue("停止ボタンが画面下端からはみ出さないこと: stop=$stop root=$root", stop.bottom <= root.bottom)
        assertTrue("停止ボタンが押せる高さを保つこと: stop=$stop", stop.height >= 48f)
    }

    @Test
    fun `小さい画面でも停止ボタンが画面内に収まる`() {
        launch("tm:small", "EmuStop_locked_screen_on")
        assertStopFullyOnScreen()
    }

    @Test
    fun `文字サイズ最大と2行タイトルでも停止ボタンが画面内に収まる`() {
        RuntimeEnvironment.setFontScale(2f)
        launch("tm:big", "とても長い会議のタイトルがここに入って二行に折り返される場合")
        assertStopFullyOnScreen()
    }
}
