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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.DailyForecast
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.ui.DayTimelineView
import com.calendaralarm.ui.EventWeatherCard
import com.calendaralarm.ui.MonthView
import com.calendaralarm.ui.ThreeDayView
import com.calendaralarm.ui.theme.CalendarAlarmTheme
import com.calendaralarm.ui.theme.OutfitFontFamily
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #407: 月・3日・時間軸・予定の詳細の天気カードの色と文字を、デザインの板 (views.dc.html / home.dc.html) の値にそろえる。
 * 色は TextLayoutResult の文字スタイルと、描いた画素で確かめる。ダーク/ライトは端末の設定とテーマの両方をそろえる。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w360dp-h740dp-notnight-mdpi")
class AgendaViewsDesignTest {

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

    /** 画面に出ている [text] の文字スタイル (色・大きさ・太さ・書体)。 */
    private fun styleOf(text: String): TextStyle {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single().layoutInput.style
    }

    private fun rgb(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    private fun Color.rgb() = rgb(toArgb())

    private val june = LocalDate(2030, 6, 1)

    private fun showMonth(dark: Boolean) = show(dark) {
        MonthView(
            month = june, items = emptyList(), focusDate = null,
            onSelectDay = {}, onSelectEvent = {}, firstWeekday = 7,
        )
    }

    private fun meeting(allDay: Boolean = false, title: String = "定例"): AlarmRepository.AgendaItem {
        val day = LocalDate(2030, 6, 3)
        val start: Long
        val end: Long
        if (allDay) {
            start = day.toEpochDays() * 86_400_000L
            end = start + 86_400_000L
        } else {
            val tz = TimeZone.currentSystemDefault()
            start = LocalDateTime(2030, 6, 3, 9, 0).toInstant(tz).toEpochMilliseconds()
            end = LocalDateTime(2030, 6, 3, 10, 0).toInstant(tz).toEpochMilliseconds()
        }
        return AlarmRepository.AgendaItem(
            event = CalendarEvent(
                id = title, calendarId = "c", title = title,
                startMillis = start, endMillis = end, allDay = allDay,
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

    private fun forecast() = DailyForecast(
        date = LocalDate(2030, 6, 3), weatherCode = 0, tempMax = 24.0, tempMin = 18.0, precipitationProbability = 10,
    )

    @Test
    fun monthWeekendHeaderUsesBoardColorsDark() {
        showMonth(dark = true)
        val sun = styleOf("日")
        assertEquals(Color(0xFFFF6B6B).rgb(), sun.color.rgb())
        assertEquals(12f, sun.fontSize.value, 0f)
        assertEquals(FontWeight.Medium, sun.fontWeight)
        assertEquals(Color(0xFF8AB4F8).rgb(), styleOf("土").color.rgb())
        assertEquals(scheme!!.onSurfaceVariant.rgb(), styleOf("月").color.rgb())
    }

    @Test
    fun monthWeekendHeaderUsesBoardColorsLight() {
        showMonth(dark = false)
        assertEquals(Color(0xFFD32F2F).rgb(), styleOf("日").color.rgb())
        assertEquals(Color(0xFF1A56C4).rgb(), styleOf("土").color.rgb())
    }

    @Test
    fun monthWeekendDayNumbersUseBoardColors() {
        showMonth(dark = true)
        // 2030-06-08 が土曜、06-09 が日曜。板は日付の数字を土日でも色分けしない
        val onSurface = scheme!!.onSurface.rgb()
        assertEquals(onSurface, styleOf("9").color.rgb())
        assertEquals(onSurface, styleOf("8").color.rgb())
        assertEquals(16f, styleOf("9").fontSize.value, 0f)
    }

    @Test
    fun monthOutsideDaysUseBoardOpacity() {
        showMonth(dark = true)
        // 5月31日は前の月の日
        val actual = styleOf("31").color
        val expected = scheme!!.onSurfaceVariant.copy(alpha = 0.55f)
        assertEquals(expected.alpha, actual.alpha, 0.01f)
        assertEquals(expected.rgb(), actual.rgb())
    }

    @Test
    fun threeDayDateAndBlockText() {
        show(dark = true) {
            ThreeDayView(
                startDate = LocalDate(2030, 6, 3), items = listOf(meeting()),
                onSelectDay = {}, onSelectEvent = {},
            )
        }
        val date = styleOf("3(月)")
        assertEquals(20f, date.fontSize.value, 0f)
        assertEquals(FontWeight.Medium, date.fontWeight)
        val title = styleOf("定例")
        assertEquals(13f, title.fontSize.value, 0f)
        assertEquals(FontWeight.Medium, title.fontWeight)
        val time = styleOf("9:00")
        assertEquals(11f, time.fontSize.value, 0f)
        assertEquals(FontWeight.Normal, time.fontWeight)
        assertEquals(OutfitFontFamily, time.fontFamily)
    }

    @Test
    fun timelineBlockText() {
        show(dark = true) {
            DayTimelineView(
                date = LocalDate(2030, 6, 3),
                items = listOf(meeting(), meeting(allDay = true, title = "出張")),
                onSelectEvent = {},
            )
        }
        val title = styleOf("定例")
        assertEquals(13f, title.fontSize.value, 0f)
        assertEquals(FontWeight.Medium, title.fontWeight)
        val range = styleOf("9:00〜10:00")
        assertEquals(11f, range.fontSize.value, 0f)
        assertEquals(FontWeight.Normal, range.fontWeight)
        assertEquals(OutfitFontFamily, range.fontFamily)
    }

    /** 天気カードの左の余白 (カードの中) の画素。 */
    private fun weatherCardPixel(activity: ComponentActivity): Int {
        val bounds = compose.onNodeWithTag("event-weather-card", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        return bitmap.getPixel((bounds.left + 4f).toInt(), bounds.center.y.toInt())
    }

    @Test
    fun weatherCardUsesRaisedSurfaceDark() {
        val activity = show(dark = true) { EventWeatherCard(forecast(), "東京", 0L) }
        assertEquals(Color(0xFF1A1F2A).rgb(), rgb(weatherCardPixel(activity)))
    }

    @Test
    fun weatherCardUsesRaisedSurfaceLight() {
        val activity = show(dark = false) { EventWeatherCard(forecast(), "東京", 0L) }
        assertEquals(Color(0xFFEDEFF4).rgb(), rgb(weatherCardPixel(activity)))
    }

    /** BOARD_SHOT_DIR がある時だけ、板の列と並べるための 8 枚を書く。無い時は例外が出ないことだけ見る。 */
    @Test
    fun `板の列の画像`() {
        listOf(true to "dark", false to "light").forEach { (dark, suffix) ->
            val items = listOf(meeting(), meeting(allDay = true, title = "出張"))
            BoardShot.save(
                show(dark) {
                    ThreeDayView(
                        startDate = LocalDate(2030, 6, 3), items = items,
                        onSelectDay = {}, onSelectEvent = {},
                    )
                },
                "design407-views-1-$suffix",
            )
            BoardShot.save(
                show(dark) {
                    MonthView(
                        month = june, items = items, focusDate = null,
                        onSelectDay = {}, onSelectEvent = {}, firstWeekday = 7,
                    )
                },
                "design407-views-2-$suffix",
            )
            BoardShot.save(
                show(dark) {
                    DayTimelineView(date = LocalDate(2030, 6, 3), items = items, onSelectEvent = {})
                },
                "design407-views-3-$suffix",
            )
            BoardShot.save(
                show(dark) { EventWeatherCard(forecast(), "東京", 0L) },
                "design407-home-4-weather-$suffix",
            )
        }
    }
}
