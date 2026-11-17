package com.calendaralarm

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.ui.EventRow
import kotlin.math.abs
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
 * #227「予定名が切れずに出る (「○分前」の札を下の段に置く)」の確かめ方。
 * 札が予定名の下の段にあることと、札の有無で予定名の取れる幅が変わらないことを、
 * 360dp の行の実際の配置で確かめる。個別の Text の位置を見るため非マージの木を使う。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class EventRowChipTest {

    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private fun item(minutesBefore: Int) = AlarmRepository.AgendaItem(
        event = CalendarEvent(
            id = "e1",
            calendarId = "c1",
            title = "定例ミーティング",
            startMillis = 1_700_000_000_000L,
            endMillis = 1_700_000_000_000L + 3_600_000L,
            allDay = false,
        ),
        calendarName = "仕事",
        calendarColor = 0xFF8AB4F8.toInt(),
        startAction = EventAction.ALARM,
        reminderAction = EventAction.MUTE,
        minutesBefore = minutesBefore,
        extraOffsets = emptyList(),
        hasOverride = false,
    )

    @Test
    fun `「○分前」の札は予定名の下の段にある`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxWidth()) {
                    // スイッチあり (onToggle あり) が一番幅が厳しい並び
                    EventRow(item(10), onClick = {}, onToggle = {})
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("10分前").fetchSemanticsNodes().isNotEmpty()
        }

        val title = compose.onNodeWithText("定例ミーティング", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val chip = compose.onNodeWithText("10分前", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val cal = compose.onNodeWithText("仕事", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        assertTrue(
            "札は予定名の下の段 (chip.top=${chip.top} title.bottom=${title.bottom})",
            chip.top >= title.bottom,
        )
        // 札はカレンダー名の前 (同じ段の左) に置く
        assertTrue("札はカレンダー名と同じ段", abs(chip.top - cal.top) < cal.height)
        assertTrue("札はカレンダー名より左", chip.right <= cal.left + cal.width)
    }

    @Test
    fun `札の有無で予定名の取れる幅は変わらない`() {
        // 札が右にあった頃は札ぶん予定名の幅が削られて切れていた。下の段に置いた今は、
        // 同じ予定名が札あり・なしの両行で同じ幅 (= 札で切れない) になるはず
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxWidth()) {
                    EventRow(item(10), onClick = {}, onToggle = {})
                    EventRow(item(0), onClick = {}, onToggle = {})
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("10分前").fetchSemanticsNodes().isNotEmpty()
        }

        val titles = compose.onAllNodesWithText("定例ミーティング", useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(2, titles.size)
        val rootRight = compose.onRoot().fetchSemanticsNode().boundsInRoot.right
        titles.forEach { b ->
            assertTrue("予定名が画面内に収まる (right=${b.right} root=$rootRight)", b.right <= rootRight)
        }
        assertEquals("札の有無で予定名の幅は変わらない", titles[0].width, titles[1].width, 0.5f)
    }
}
