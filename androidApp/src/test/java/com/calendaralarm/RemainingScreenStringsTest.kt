package com.calendaralarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.shared.weather.WeatherApi
import com.calendaralarm.shared.weather.WeatherKind
import com.calendaralarm.ui.labelRes
import com.calendaralarm.ui.theme.AppPalette
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #367: タブ・予定表示・テーマ名・既定タイマー・天気・カレンダー名・テスト鳴動の文言が ja/en で引ける。
 * ja は移す前の直書きと一字一句同じであること (見た目を変えない) を固定する。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemainingScreenStringsTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    @Config(qualifiers = "ja")
    fun japaneseKeepsTheOriginalText() {
        assertEquals(listOf("予定", "アラーム", "タイマー", "設定"),
            listOf(R.string.tab_agenda, R.string.tab_alarms, R.string.tab_timer, R.string.tab_settings).map { s(it) })
        assertEquals("予定なし", s(R.string.agenda_no_events))
        assertEquals("終日", s(R.string.agenda_all_day))
        assertEquals("(タイトルなし)", s(R.string.agenda_untitled))
        assertEquals("今 09:05", s(R.string.agenda_now_label, "09:05"))
        assertEquals("9:00〜10:30", s(R.string.agenda_time_range, "9:00", "10:30"))
        assertEquals("カレンダー", s(R.string.calendar_default_name))
        assertEquals("テスト鳴動", s(R.string.ask_test_ring_label))
        assertEquals(
            listOf("ゆで卵", "パスタ", "紅茶", "ピザ", "仮眠", "集中", "筋トレ休憩"),
            SettingsRepository.defaultTimerPresets(context).map { it.label },
        )
    }

    @Test
    @Config(qualifiers = "en")
    fun englishResolvesTheSameKeys() {
        assertEquals(listOf("Agenda", "Alarms", "Timer", "Settings"),
            listOf(R.string.tab_agenda, R.string.tab_alarms, R.string.tab_timer, R.string.tab_settings).map { s(it) })
        assertEquals("No events", s(R.string.agenda_no_events))
        assertEquals("All day", s(R.string.agenda_all_day))
        assertEquals("(No title)", s(R.string.agenda_untitled))
        assertEquals("Now 09:05", s(R.string.agenda_now_label, "09:05"))
        assertEquals("9:00–10:30", s(R.string.agenda_time_range, "9:00", "10:30"))
        assertEquals("Calendar", s(R.string.calendar_default_name))
        assertEquals("Test alarm", s(R.string.ask_test_ring_label))
        assertEquals(
            listOf("Boiled egg", "Pasta", "Tea", "Pizza", "Nap", "Focus", "Workout break"),
            SettingsRepository.defaultTimerPresets(context).map { it.label },
        )
    }

    @Test
    @Config(qualifiers = "ja")
    fun japaneseThemeNamesMatchTheOldLabels() {
        val old = mapOf(
            "indigo" to "インディゴ", "amber" to "琥珀", "forest" to "森", "ocean" to "海", "rose" to "薔薇",
            "violet" to "紫", "mono" to "墨", "sky" to "空", "orange" to "橙", "sakura" to "桜",
        )
        assertEquals(old.keys, AppPalette.entries.map { it.id }.toSet())
        AppPalette.entries.forEach { assertEquals(old[it.id], s(it.labelRes)) }
    }

    @Test
    @Config(qualifiers = "en")
    fun everyThemeHasAnEnglishName() {
        AppPalette.entries.forEach {
            val en = s(it.labelRes)
            assertEquals("${it.id} が日本語のまま", false, en.any { c -> c.code > 0x2FFF })
        }
        assertEquals("Indigo", s(AppPalette.INDIGO.labelRes))
        assertEquals("Ink", s(AppPalette.MONO.labelRes))
    }

    @Test
    @Config(qualifiers = "ja")
    fun japaneseWeatherWordsMatchDescribe() {
        // shared の describe (iOS 用) と Android の文字リソースが同じ日本語を出す
        for (code in listOf(0, 1, 2, 3, 45, 51, 56, 61, 66, 71, 77, 80, 85, 95, 96, 4)) {
            assertEquals("code $code", WeatherApi.describe(code), s(WeatherApi.kindOf(code).labelRes()))
        }
    }

    @Test
    @Config(qualifiers = "en")
    fun englishWeatherWordsExistForEveryKind() {
        val words = WeatherKind.entries.map { s(it.labelRes()) }
        assertEquals("同じ英語が2種類に付いている", words.size, words.toSet().size)
        assertEquals("Clear", s(WeatherApi.kindOf(0).labelRes()))
        assertEquals("Thunderstorm with hail", s(WeatherApi.kindOf(99).labelRes()))
        assertEquals("Unknown", s(WeatherApi.kindOf(1234).labelRes()))
        words.forEach { assertEquals("日本語が残っている: $it", false, it.any { c -> c.code > 0x2FFF }) }
    }
}
