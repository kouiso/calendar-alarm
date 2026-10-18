package com.calendaralarm

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.Lifecycle
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
        // RingingActivity.SlideToStop の丸 64dp・余白 8dp (qualifiers の mdpi では 1dp = 1px)。変えたらここも合わせる
        const val THUMB_PX = 64f
        const val PAD_PX = 8f
        // 動き出しにタッチスロップ (8dp) が消費される分
        const val TOUCH_SLOP_PX = 8f
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

    /** 起動されたサービスを全部走査し、DISMISS だけを返す (他の起動が先にあっても見逃さない)。 */
    private fun dismissIntents(): List<Intent> {
        val all = mutableListOf<Intent>()
        while (true) {
            all += shadowOf(app).nextStartedService ?: break
        }
        return all.filter { it.action == AlarmService.ACTION_DISMISS }
    }

    private fun assertNotStopped() {
        assertTrue("DISMISS が送られている", dismissIntents().isEmpty())
        assertNotEquals(Lifecycle.State.DESTROYED, scenario?.state)
    }

    /** 丸を [progress] (0..1、トラックの移動量に対する割合) まで引いて離す。 */
    private fun slideTo(progress: Float, fromX: Float = 40f) {
        compose.onNodeWithContentDescription(SLIDER).performTouchInput {
            // 丸の移動量は トラック幅 - 丸64dp - 余白8dp*2。タッチスロップ (8) 分は動き出しに消費される
            val travel = width - (THUMB_PX + 2 * PAD_PX)
            swipe(
                Offset(fromX, centerY),
                Offset(fromX + TOUCH_SLOP_PX + progress * travel, centerY),
                durationMillis = 300,
            )
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    @Test
    fun `タップでは止まらない`() {
        compose.onNodeWithContentDescription(SLIDER).performClick()
        compose.waitForIdle()
        assertNotStopped()
    }

    @Test
    fun `80パーセント未満のドラッグでは止まらない`() {
        slideTo(progress = 0.5f)
        assertNotStopped()
    }

    @Test
    fun `閾値の手前の70パーセントでは止まらない`() {
        slideTo(progress = 0.7f)
        assertNotStopped()
    }

    @Test
    fun `丸の外から始めたスワイプでは止まらない`() {
        slideTo(progress = 0.97f, fromX = 200f)
        assertNotStopped()
    }

    @Test
    fun `80パーセントを超えるドラッグで停止を送る`() {
        slideTo(progress = 0.97f)
        val sent = dismissIntents()
        assertEquals(1, sent.size)
        assertEquals(ID, sent.single().getStringExtra(AlarmService.EXTRA_INSTANCE_ID))
    }

    @Test
    fun `閾値を超えた90パーセントのドラッグで停止を送る`() {
        slideTo(progress = 0.9f)
        assertEquals(1, dismissIntents().size)
    }

    @Test
    fun `アクセシビリティの click で停止を送る`() {
        compose.onNodeWithContentDescription(SLIDER).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, dismissIntents().size)
    }
}
