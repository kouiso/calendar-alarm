package com.calendaralarm

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.db.ScheduledInstanceEntity
import com.calendaralarm.engine.AlarmService
import com.calendaralarm.engine.LARGE_STOP_TAG
import com.calendaralarm.engine.RING_DIAL_TAG
import com.calendaralarm.engine.RingingActivity
import com.calendaralarm.engine.SNOOZE_BUTTON_TAG
import com.calendaralarm.engine.SNOOZE_CHOOSE_TAG
import com.calendaralarm.engine.TITLE_TAG
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 大きいボタンモード (#154) の鳴動画面を盤 p154 の 2 列目に合わせる。
 * スヌーズも停止と同じ大きさ (.bb 124)、「スヌーズ時間を選ぶ」は出さない、
 * 文字盤は dial.sm (176)、予定名は 22。OFF の時は今のまま (スヌーズ80・選ぶ有り・予定名32)。
 * 縦を高くして、文字盤が窓の高さで縮められない状態で測る。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h900dp-mdpi")
class LargeButtonRingingLayoutTest {

    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private lateinit var app: CalendarAlarmApp
    private var scenario: ActivityScenario<RingingActivity>? = null

    private companion object {
        const val ID = "tm:large-layout"
        const val TITLE = "LargeLayoutTitle"
    }

    private fun launch(large: Boolean, waitTag: String) {
        app = ApplicationProvider.getApplicationContext()
        AlarmService.ringingInstanceId.value = null
        runBlocking {
            app.container.settings.setLargeButtons(large)
            app.container.db.scheduledInstances().upsert(
                listOf(ScheduledInstanceEntity.of(AlarmInstance(ID, System.currentTimeMillis(), TITLE, AlarmKind.TIMER))),
            )
        }
        scenario = ActivityScenario.launch(RingingActivity.intent(app, ID))
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(TITLE).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(waitTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        scenario?.close()
        AlarmService.ringingInstanceId.value = null
        runBlocking { app.container.settings.setLargeButtons(false) }
    }

    private fun titleFontSizeSp(): Float {
        val node: SemanticsNode = compose.onNodeWithTag(TITLE_TAG).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single().layoutInput.style.fontSize.value
    }

    @Test
    fun `大きいボタンではスヌーズが停止と同じ124の高さになる`() {
        launch(large = true, waitTag = LARGE_STOP_TAG)
        compose.onNodeWithTag(SNOOZE_BUTTON_TAG).assertHeightIsEqualTo(124.dp)
        compose.onNodeWithTag(LARGE_STOP_TAG).assertHeightIsEqualTo(124.dp)
    }

    @Test
    fun `大きいボタンではスヌーズ時間を選ぶが出ない`() {
        launch(large = true, waitTag = LARGE_STOP_TAG)
        assertEquals(0, compose.onAllNodesWithTag(SNOOZE_CHOOSE_TAG).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("スヌーズ時間を選ぶ", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `大きいボタンでは文字盤が176に縮み、予定名は22になる`() {
        launch(large = true, waitTag = LARGE_STOP_TAG)
        compose.onNodeWithTag(RING_DIAL_TAG).assertWidthIsEqualTo(176.dp)
        assertEquals(22f, titleFontSizeSp(), 0.01f)
    }

    @Test
    fun `通常はスヌーズ80で、スヌーズ時間を選ぶが出て、文字盤と予定名は今の大きさ`() {
        launch(large = false, waitTag = SNOOZE_CHOOSE_TAG)
        compose.onNodeWithTag(SNOOZE_BUTTON_TAG).assertHeightIsEqualTo(80.dp)
        compose.onNodeWithTag(RING_DIAL_TAG).assertWidthIsEqualTo(290.dp)
        assertEquals(32f, titleFontSizeSp(), 0.01f)
    }
}
