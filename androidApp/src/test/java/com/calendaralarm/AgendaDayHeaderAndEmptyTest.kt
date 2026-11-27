package com.calendaralarm

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.ui.AgendaEmptyState
import com.calendaralarm.ui.AgendaList
import com.calendaralarm.ui.jaShort
import com.calendaralarm.ui.theme.CalendarAlarmTheme
import com.calendaralarm.ui.theme.OutfitFontFamily
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #408: 予定一覧の日付の見出し (今日・明日の札をやめて文字だけにする) と、予定がない画面の題・説明を板の値にそろえる。
 * 板は design/project/d68/home.dc.html の .dh と .empty。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w360dp-h740dp-notnight-mdpi")
class AgendaDayHeaderAndEmptyTest {

    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private var scheme: ColorScheme? = null

    private fun show(dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit): ComponentActivity {
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            CalendarAlarmTheme(darkTheme = dark) {
                scheme = MaterialTheme.colorScheme
                content()
            }
        }
        compose.waitForIdle()
        return activity
    }

    private fun styleOf(text: String): TextStyle {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single().layoutInput.style
    }

    private fun count(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun rgb(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    private fun Color.rgb() = rgb(toArgb())

    private val today: LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    /** 終日の予定 (UTC 0 時)。見出しの日付は UTC 解釈なので [date] の日にそのまま入る。 */
    private fun item(title: String, date: LocalDate): AlarmRepository.AgendaItem {
        val start = date.toEpochDays() * 86_400_000L
        return AlarmRepository.AgendaItem(
            event = CalendarEvent(
                id = title, calendarId = "c", title = title,
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

    private fun showList(dark: Boolean = false, vararg days: Pair<String, LocalDate>): ComponentActivity {
        val activity = show(dark) {
            AgendaList(items = days.map { item(it.first, it.second) }, onSelect = {}, showWeekNumbers = false)
        }
        return activity
    }

    @Test
    fun todayIsAccentTextWithoutBadge() {
        val activity = showList(false, "出張" to today)
        val s = styleOf("今日")
        assertEquals(scheme!!.primary.rgb(), s.color.rgb())
        assertEquals(13f, s.fontSize.value, 0f)
        assertEquals(FontWeight.SemiBold, s.fontWeight)
        // 札の背景 (primaryContainer) が文字の左上に無いこと
        val b = compose.onNodeWithText("今日", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val px = bitmap.getPixel(b.left.toInt() + 1, b.top.toInt() + 1)
        assertNotEquals(scheme!!.primaryContainer.rgb(), rgb(px))
        // 札の形や余白が変わっても検出できるよう、文字の左右の外側の画素も見る
        val midY = ((b.top + b.bottom) / 2).toInt()
        val leftX = (b.left.toInt() - 4).coerceAtLeast(0)
        val rightX = (b.right.toInt() + 4).coerceAtMost(bitmap.width - 1)
        assertNotEquals(scheme!!.primaryContainer.rgb(), rgb(bitmap.getPixel(leftX, midY)))
        assertNotEquals(scheme!!.primaryContainer.rgb(), rgb(bitmap.getPixel(rightX, midY)))
    }

    @Test
    fun tomorrowIsMutedText() {
        showList(false, "定例" to today + DatePeriod(days = 1))
        val s = styleOf("明日")
        assertEquals(scheme!!.onSurfaceVariant.rgb(), s.color.rgb())
        assertEquals(13f, s.fontSize.value, 0f)
        assertEquals(FontWeight.SemiBold, s.fontWeight)
    }

    @Test
    fun dateIsOutfit16Medium() {
        val d = today + DatePeriod(days = 2)
        showList(false, "会議" to d)
        val s = styleOf("${d.monthNumber}/${d.dayOfMonth}(${d.dayOfWeek.jaShort()})")
        assertEquals(16f, s.fontSize.value, 0f)
        assertEquals(FontWeight.Medium, s.fontWeight)
        assertEquals(OutfitFontFamily, s.fontFamily)
        assertEquals(0, count("今日"))
        assertEquals(0, count("明日"))
    }

    @Test
    fun emptyStateTitleAndBody() {
        show(false) { AgendaEmptyState(searching = false) }
        val title = styleOf("この先2週間の予定はありません")
        assertEquals(16f, title.fontSize.value, 0f)
        assertEquals(FontWeight.Medium, title.fontWeight)
        assertEquals(scheme!!.onSurface.rgb(), title.color.rgb())
        val body = styleOf("カレンダーに予定を入れると、ここに並んで前もって鳴ります")
        assertEquals(13f, body.fontSize.value, 0f)
        assertEquals(scheme!!.onSurfaceVariant.rgb(), body.color.rgb())
    }

    @Test
    fun emptyStateSearchingShowsTitleOnly() {
        show(false) { AgendaEmptyState(searching = true) }
        assertEquals(1, count("該当する予定はありません"))
        assertEquals(0, count("カレンダーに予定を入れると、ここに並んで前もって鳴ります"))
    }

    /** BOARD_SHOT_DIR がある時だけ、板の列と並べるための 4 枚を書く。無い時は例外が出ないことだけ見る。 */
    @Test
    fun `板の列の画像`() {
        listOf(true to "dark", false to "light").forEach { (dark, suffix) ->
            BoardShot.save(
                showList(dark, "出張" to today, "定例" to today + DatePeriod(days = 1)),
                "design408-home-1-headers-$suffix",
            )
            BoardShot.save(
                show(dark) { AgendaEmptyState(searching = false) },
                "design408-home-3-empty-$suffix",
            )
        }
    }
}
