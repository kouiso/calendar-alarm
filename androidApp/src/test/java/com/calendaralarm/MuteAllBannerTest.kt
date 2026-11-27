package com.calendaralarm

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calendaralarm.ui.MuteAllBanner
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 「すべてミュート中 ― 解除」の帯 (#272) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w360dp-h800dp-xxhdpi")
class MuteAllBannerTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private fun show(onUnmute: () -> Unit, waitFor: String) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { MaterialTheme { MuteAllBanner(onUnmute = onUnmute) } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(waitFor).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun japaneseBannerUnmutesOnTap() {
        var count = 0
        show({ count++ }, "すべてのアラームをミュート中です")
        assertEquals(1, compose.onAllNodesWithText("すべてのアラームをミュート中です").fetchSemanticsNodes().size)

        compose.onNodeWithText("解除").performClick()
        compose.waitForIdle()

        assertEquals(1, count)
    }

    @Test
    @Config(qualifiers = "en-w360dp-h800dp-xxhdpi")
    fun englishBannerText() {
        show({}, "All alarms are muted")
        assertEquals(1, compose.onAllNodesWithText("All alarms are muted").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("Unmute").fetchSemanticsNodes().size)
    }
}
