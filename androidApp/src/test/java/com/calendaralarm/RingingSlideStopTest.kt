package com.calendaralarm

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 停止スライダーの操作契約: タップや途中までのドラッグでは止まらず、
 * 80%を超えるドラッグとアクセシビリティの click だけが停止 (DISMISS) を送る。
 * 誤タップ防止がこの画面の目的なので、レイアウトとは別に固定する。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-mdpi")
class RingingSlideStopTest {

    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private lateinit var app: CalendarAlarmApp
    private var scenario: ActivityScenario<RingingActivity>? = null

    private companion object {
        const val SLIDER = "スライドして停止"
        const val ID = "tm:slide"
        const val TITLE = "SlideStopTest"
    }

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        AlarmService.ringingInstanceId.value = null
        runBlocking {
            app.container.db.scheduledInstances().upsert(
                listOf(
                    ScheduledInstanceEntity.of(
                        AlarmInstance(ID, System.currentTimeMillis(), TITLE, AlarmKind.TIMER),
                    ),
                ),
            )
        }
        scenario = ActivityScenario.launch(RingingActivity.intent(app, ID))
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        // 起動時に積まれた他の startService を捨て、以後の送信だけを見る
        while (shadowOf(app).nextStartedService != null) { /* drain */ }
    }

    @After
    fun tearDown() {
        scenario?.close()
        AlarmService.ringingInstanceId.value = null
    }

    private fun dismissIntent(): Intent? = shadowOf(app).nextStartedService
        ?.takeIf { it.action == AlarmService.ACTION_DISMISS }

    private fun slide(toFraction: Float, fromX: Float = 40f) {
        compose.onNodeWithContentDescription(SLIDER).performTouchInput {
            swipe(Offset(fromX, centerY), Offset(width * toFraction, centerY), durationMillis = 300)
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    @Test
    fun `タップでは止まらない`() {
        compose.onNodeWithContentDescription(SLIDER).performClick()
        compose.waitForIdle()
        assertNull(dismissIntent())
    }

    @Test
    fun `80パーセント未満のドラッグでは止まらない`() {
        slide(toFraction = 0.5f)
        assertNull(dismissIntent())
    }

    @Test
    fun `丸の外から始めたスワイプでは止まらない`() {
        slide(toFraction = 0.97f, fromX = 200f)
        assertNull(dismissIntent())
    }

    @Test
    fun `80パーセントを超えるドラッグで停止を送る`() {
        slide(toFraction = 0.97f)
        val sent = dismissIntent()
        assertEquals(AlarmService.ACTION_DISMISS, sent?.action)
        assertEquals(ID, sent?.getStringExtra(AlarmService.EXTRA_INSTANCE_ID))
    }

    @Test
    fun `アクセシビリティの click で停止を送る`() {
        compose.onNodeWithContentDescription(SLIDER).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(AlarmService.ACTION_DISMISS, dismissIntent()?.action)
    }
}
