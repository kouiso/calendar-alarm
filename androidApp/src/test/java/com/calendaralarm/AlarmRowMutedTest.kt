package com.calendaralarm

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.StandaloneAlarm
import com.calendaralarm.ui.AlarmRow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 全ミュート中のアラームの行は「ミュート中」を出す (#272) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w360dp-h800dp-xxhdpi")
class AlarmRowMutedTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    /** 今日の 7:00:00.000 (試験の端末の時間帯で 7:00 になる) */
    private fun todaySeven(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 7)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun show(enabled: Boolean, withNext: Boolean, allMuted: Boolean) {
        val next = if (withNext) {
            AlarmInstance(
                id = "s1", triggerAtMillis = todaySeven(), title = "",
                kind = AlarmKind.STANDALONE, standaloneAlarmId = 1,
            )
        } else {
            null
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            MaterialTheme {
                AlarmRow(
                    alarm = StandaloneAlarm(id = 1, enabled = enabled, hour = 7, minute = 0),
                    nextInstance = next,
                    allMuted = allMuted,
                    onToggle = {},
                    onDelete = {},
                    onClick = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("7:00").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun mutedEnabledRowShowsMutedLabel() {
        show(enabled = true, withNext = false, allMuted = true)
        assertEquals(1, compose.onAllNodesWithText("ミュート中").fetchSemanticsNodes().size)
    }

    @Test
    fun unmutedRowShowsNextAndNoMutedLabel() {
        show(enabled = true, withNext = true, allMuted = false)
        assertEquals(0, compose.onAllNodesWithText("ミュート中").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("次 7:00").fetchSemanticsNodes().size)
    }

    @Test
    fun disabledRowNeverShowsMutedLabel() {
        show(enabled = false, withNext = false, allMuted = true)
        assertEquals(0, compose.onAllNodesWithText("ミュート中").fetchSemanticsNodes().size)
    }
}
