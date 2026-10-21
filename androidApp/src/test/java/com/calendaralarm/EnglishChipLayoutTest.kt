package com.calendaralarm

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.ui.AlarmEditScreen
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 英語表記のチップ行の回帰テスト。
 * 英語のラベルは日本語より長く、1行の Row に6枚並べると末尾の「Months」が描画されず、
 * 「Weeks」が1文字ずつ縦に潰れて選べなくなった (PR #126 の Android 英語画面で実測)。
 * 文字幅を実測させるため NATIVE グラフィックスで描く。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w360dp-h800dp-xxhdpi")
class EnglishChipLayoutTest {

    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private fun showNewAlarm() {
        val app = ApplicationProvider.getApplicationContext<CalendarAlarmApp>()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            MaterialTheme {
                AlarmEditScreen(
                    repository = app.container.repository,
                    settings = app.container.settings,
                    alarmId = 0L,
                    onDone = {},
                )
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Once").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * ラベルのチップ。onNodeWithText は一致しなかった時に意味ツリー全体を文字にし、この画面ではそれが
     * 何分も終わらずメモリが尽きた。数を自分で数えて、外れたら数だけを出して落とす
     */
    private fun chip(label: String): SemanticsNodeInteraction {
        val all = compose.onAllNodesWithText(label)
        val count = all.fetchSemanticsNodes().size
        assertEquals("$label が $count 個ある", 1, count)
        return all[0]
    }

    /** どのチップも画面内に1行の高さで収まり、押せること。 */
    private fun assertChipsFit(labels: List<String>) {
        val rootRight = compose.onRoot().fetchSemanticsNode().boundsInRoot.right
        val heights = labels.map { label ->
            chip(label).performScrollTo().assertIsDisplayed()
            val b = chip(label).fetchSemanticsNode().boundsInRoot
            assertTrue("$label の右端 ${b.right} が画面幅 $rootRight を超えた", b.right <= rootRight)
            b.height
        }
        val oneLine = heights.first()
        labels.zip(heights).forEach { (label, h) ->
            assertEquals("$label が1行の高さで描かれていない (潰れて折り返した)", oneLine, h, 1f)
        }
    }

    @Test
    fun repeatModeAndWeekdayChipsAllFitInEnglish() {
        showNewAlarm()
        assertChipsFit(listOf("Once", "Weekly", "Monthly", "Days", "Weeks", "Months"))

        chip("Weekly").performClick()
        compose.waitForIdle()
        assertChipsFit(listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"))
    }
}
