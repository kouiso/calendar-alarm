package com.calendaralarm

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.calendaralarm.ui.NextAlarmBanner
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w360dp-h800dp-xxhdpi")
class NextAlarmBannerTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private fun show(visible: Boolean) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            MaterialTheme { NextAlarmBanner("次の会議", null, "停止済み", showEvent = visible) }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("停止済み").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun offHidesOnlyAlarmDetails() {
        show(false)
        assertEquals(0, compose.onAllNodesWithText("次のアラーム").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("次の会議").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("停止済み").fetchSemanticsNodes().size)
    }

    @Test
    fun onShowsAlarmAndStatus() {
        show(true)
        assertEquals(1, compose.onAllNodesWithText("次のアラーム").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("次の会議").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("停止済み").fetchSemanticsNodes().size)
    }
}
