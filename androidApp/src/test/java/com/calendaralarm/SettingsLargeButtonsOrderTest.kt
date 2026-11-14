package com.calendaralarm

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.calendaralarm.ui.SettingsScreen
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 設定画面の「鳴動画面を大きいボタンにする」は、盤 p154 の 4 列目どおり独立したカードで、
 * テーマ・背景画像のカードより上にある。角丸20・上下4・左右16 の `card ls`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ja-w360dp-h4000dp-mdpi")
class SettingsLargeButtonsOrderTest {

    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    @Test
    fun `大きいボタンの行は独立したカードで、テーマと背景画像より上にある`() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<CalendarAlarmApp>()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            MaterialTheme { SettingsScreen(app.container.repository, app.container.settings) }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTextSafe("背景画像")
        }
        val card = compose.onNodeWithTag(com.calendaralarm.ui.LARGE_BUTTONS_CARD_TAG).getUnclippedBoundsInRoot()
        val bg = compose.onNodeWithText("背景画像").getUnclippedBoundsInRoot()
        val palette = compose.onNodeWithText("インディゴ").getUnclippedBoundsInRoot()
        assertTrue("大きいボタンのカードがテーマの見本より下", card.bottom <= palette.top)
        assertTrue("大きいボタンのカードが背景画像より下", card.bottom <= bg.top)
        // card ls: 行 56 + 上下 4 = 64 以上、独立したカードなので背景画像の行は含まない
        assertTrue(card.height >= 64.dp)
        assertEquals(true, card.height < 80.dp)
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextSafe(t: String): Boolean =
        onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty()
}
