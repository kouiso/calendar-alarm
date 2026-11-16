package com.calendaralarm

import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.db.ScheduledInstanceEntity
import com.calendaralarm.engine.AlarmService
import com.calendaralarm.engine.LARGE_STOP_TAG
import com.calendaralarm.engine.RingingActivity
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 大ボタンモード (#154)。ON の時は停止スライダーの代わりに大きな停止ボタンが出て、
 * 押すとその鳴動の停止 (DISMISS) を送ることを、鳴動画面を実際に開いて確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-mdpi")
class LargeButtonStopTest {

    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private lateinit var app: CalendarAlarmApp
    private var scenario: ActivityScenario<RingingActivity>? = null

    private companion object {
        const val ID = "tm:large"
        const val TITLE = "LargeButtonTest"
    }

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        AlarmService.ringingInstanceId.value = null
        runBlocking {
            app.container.settings.setLargeButtons(true)
            app.container.db.scheduledInstances().upsert(
                listOf(ScheduledInstanceEntity.of(AlarmInstance(ID, System.currentTimeMillis(), TITLE, AlarmKind.TIMER))),
            )
        }
        scenario = ActivityScenario.launch(RingingActivity.intent(app, ID))
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        while (shadowOf(app).nextStartedService != null) { /* drain */ }
    }

    @After
    fun tearDown() {
        scenario?.close()
        AlarmService.ringingInstanceId.value = null
        runBlocking { app.container.settings.setLargeButtons(false) }
    }

    private fun dismissIntents(): List<Intent> {
        val all = mutableListOf<Intent>()
        while (true) {
            all += shadowOf(app).nextStartedService ?: break
        }
        return all.filter { it.action == AlarmService.ACTION_DISMISS }
    }

    @Test
    fun `大ボタンモードでは大きな停止ボタンを押すとその鳴動の停止を送る`() {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("スライドして停止").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(LARGE_STOP_TAG).performClick()
        compose.waitForIdle()
        val dismisses = dismissIntents()
        assertEquals(1, dismisses.size)
        assertEquals(ID, dismisses.single().getStringExtra(AlarmService.EXTRA_INSTANCE_ID))
    }
}
