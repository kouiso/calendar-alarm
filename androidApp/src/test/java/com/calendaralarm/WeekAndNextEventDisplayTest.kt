package com.calendaralarm

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.ui.AgendaList
import com.calendaralarm.ui.MonthView
import com.calendaralarm.ui.NextAlarmBanner
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.datetime.LocalDate
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
 * #151: 週番号・次のアラーム・週の始まりの設定が画面の表示に出る/消えることを確かめる (Android 側)。
 * 文言は design/project/p151.dc.html の「第44週」「第45週」「次のアラーム」に合わせる。
 * 日付は終日 (UTC 0 時) の予定で固定し、端末の時刻帯に左右されない。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w360dp-h800dp-xxhdpi")
class WeekAndNextEventDisplayTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private fun item(id: String, date: LocalDate): AlarmRepository.AgendaItem {
        val start = date.toEpochDays() * 86_400_000L
        return AlarmRepository.AgendaItem(
            event = CalendarEvent(
                id = id, calendarId = "c", title = "予定$id",
                startMillis = start, endMillis = start + 86_400_000L, allDay = true,
            ),
            calendarName = "仕事",
            calendarColor = 0xFF8AB4F8.toInt(),
            startAction = EventAction.ALARM,
            reminderAction = EventAction.NOTIFY,
            minutesBefore = 10,
            extraOffsets = emptyList(),
            hasOverride = false,
        )
    }

    // 2026-10-27 は ISO 第44週、2026-11-04 は第45週 (デザイン案の見本と同じ)
    private val items = listOf(
        item("1", LocalDate(2026, 10, 27)),
        item("2", LocalDate(2026, 11, 4)),
    )

    private fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { MaterialTheme { content() } }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun showList(showWeekNumbers: Boolean) {
        setContent { AgendaList(items = items, onSelect = {}, showWeekNumbers = showWeekNumbers) }
        compose.waitUntil(5_000) { count("10/27(火)") > 0 }
    }

    @Test
    fun weekNumbersOnShowsIsoWeekLabelPerDayHeader() {
        showList(true)
        assertEquals(1, count("第44週"))
        assertEquals(1, count("第45週"))
    }

    @Test
    fun weekNumbersOffHidesEveryWeekLabel() {
        showList(false)
        assertEquals(1, count("10/27(火)"))
        assertEquals(1, count("11/4(水)"))
        assertEquals(0, count("第44週"))
        assertEquals(0, count("第45週"))
    }

    @Test
    fun nextAlarmBannerOnShowsLabelTitleAndTimeOffKeepsOnlyStatus() {
        val at = 1_790_000_000_000L
        setContent { NextAlarmBanner("定例ミーティング", at, "鳴る準備ができています", showEvent = true) }
        compose.waitUntil(5_000) { count("鳴る準備ができています") > 0 }
        assertEquals(1, count("次のアラーム"))
        assertEquals(1, count("定例ミーティング"))
        assertEquals(1, count("鳴る準備ができています"))
    }

    @Test
    fun nextAlarmBannerOffHidesLabelTitleAndKeepsStatus() {
        setContent { NextAlarmBanner("定例ミーティング", 1_790_000_000_000L, "鳴る準備ができています", showEvent = false) }
        compose.waitUntil(5_000) { count("鳴る準備ができています") > 0 }
        assertEquals(0, count("次のアラーム"))
        assertEquals(0, count("定例ミーティング"))
        assertEquals(1, count("鳴る準備ができています"))
    }

    private fun firstColumnIsLeftOf(left: String, right: String): Boolean {
        val l = compose.onNodeWithText(left).fetchSemanticsNode().boundsInRoot.left
        val r = compose.onNodeWithText(right).fetchSemanticsNode().boundsInRoot.left
        return l < r
    }

    private fun showMonth(firstWeekday: Int) {
        setContent {
            MonthView(
                month = LocalDate(2026, 11, 1), items = emptyList(), focusDate = null,
                onSelectDay = {}, onSelectEvent = {}, firstWeekday = firstWeekday,
            )
        }
        compose.waitUntil(5_000) { count("月") > 0 }
    }

    @Test
    fun weekStartMondayPutsMondayColumnBeforeSunday() {
        showMonth(1)
        assertTrue(firstColumnIsLeftOf("月", "日"))
    }

    @Test
    fun weekStartSundayPutsSundayColumnBeforeMonday() {
        showMonth(7)
        assertTrue(firstColumnIsLeftOf("日", "月"))
    }
}
